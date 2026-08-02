package cn.iocoder.yudao.module.skit.service.ad;

import cn.iocoder.yudao.framework.tenant.core.util.TenantUtils;
import cn.iocoder.yudao.module.skit.dal.dataobject.ad.SkitAdCallbackKeyDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.ad.SkitAdRewardSecretVersionDO;
import cn.iocoder.yudao.module.skit.dal.mysql.ad.SkitAdAccountMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.ad.SkitAdCallbackKeyMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.ad.SkitAdRewardSecretVersionMapper;
import cn.iocoder.yudao.module.skit.framework.crypto.SkitAdCredentialCryptoService;
import cn.iocoder.yudao.module.skit.service.ad.callback.SkitCallbackRouteRegistryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class SkitAdCredentialVersionServiceImpl implements SkitAdCredentialVersionService {

    static final Duration MAX_PRIOR_ACCEPTANCE_WINDOW = Duration.ofHours(24);
    private static final int CALLBACK_KEY_BYTES = 32;
    private static final int CALLBACK_HASH_RETRIES = 8;

    private final SkitAdAccountMapper accountMapper;
    private final SkitAdCallbackKeyMapper callbackKeyMapper;
    private final SkitAdRewardSecretVersionMapper rewardSecretMapper;
    private final SkitAdCredentialCryptoService cryptoService;
    private final SkitCallbackRouteRegistryService callbackRouteRegistryService;
    private final Clock clock;
    private final SecureRandom secureRandom;

    @Autowired
    public SkitAdCredentialVersionServiceImpl(SkitAdAccountMapper accountMapper,
                                              SkitAdCallbackKeyMapper callbackKeyMapper,
                                              SkitAdRewardSecretVersionMapper rewardSecretMapper,
                                              SkitAdCredentialCryptoService cryptoService,
                                              SkitCallbackRouteRegistryService callbackRouteRegistryService) {
        this(accountMapper, callbackKeyMapper, rewardSecretMapper, cryptoService,
                callbackRouteRegistryService,
                Clock.systemDefaultZone(), new SecureRandom());
    }

    public SkitAdCredentialVersionServiceImpl(SkitAdAccountMapper accountMapper,
                                              SkitAdCallbackKeyMapper callbackKeyMapper,
                                              SkitAdRewardSecretVersionMapper rewardSecretMapper,
                                              SkitAdCredentialCryptoService cryptoService,
                                              SkitCallbackRouteRegistryService callbackRouteRegistryService,
                                              Clock clock, SecureRandom secureRandom) {
        this.accountMapper = Objects.requireNonNull(accountMapper, "accountMapper");
        this.callbackKeyMapper = Objects.requireNonNull(callbackKeyMapper, "callbackKeyMapper");
        this.rewardSecretMapper = Objects.requireNonNull(rewardSecretMapper, "rewardSecretMapper");
        this.cryptoService = Objects.requireNonNull(cryptoService, "cryptoService");
        this.callbackRouteRegistryService = Objects.requireNonNull(
                callbackRouteRegistryService, "callbackRouteRegistryService");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.secureRandom = Objects.requireNonNull(secureRandom, "secureRandom");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CallbackKeyIssue rotateCallbackKey(long tenantId, long adAccountId,
                                              Duration priorAcceptanceWindow) {
        validateScopeAndWindow(tenantId, adAccountId, priorAcceptanceWindow);
        AtomicReference<CallbackKeyIssue> result = new AtomicReference<>();
        TenantUtils.execute(tenantId, () -> result.set(
                rotateCallbackKeyInsideTenant(tenantId, adAccountId, priorAcceptanceWindow)));
        return result.get();
    }

    private CallbackKeyIssue rotateCallbackKeyInsideTenant(long tenantId, long adAccountId,
                                                           Duration priorAcceptanceWindow) {
        lockAccount(tenantId, adAccountId);
        SkitCallbackRouteRegistryService.TenantKeyMutation mutation =
                callbackRouteRegistryService.beginTenantKeyMutation(tenantId, adAccountId);
        int nextVersion = nextVersion(callbackKeyMapper.selectMaxVersion(tenantId, adAccountId));
        SkitAdCallbackKeyDO prior = callbackKeyMapper.selectActiveForUpdate(tenantId, adAccountId);
        validateCallbackRowScope(prior, tenantId, adAccountId);
        LocalDateTime rotationTime = now();
        if (prior != null) {
            callbackRouteRegistryService.registerTenantKey(mutation,
                    SkitCallbackRouteRegistryService.TenantCallbackKeyRegistration.fromLegacy(
                            prior, rotationTime));
            retireCallbackVersion(prior, tenantId, adAccountId,
                    rotationTime.plus(priorAcceptanceWindow));
        }

        for (int attempt = 0; attempt < CALLBACK_HASH_RETRIES; attempt++) {
            byte[] randomBytes = new byte[CALLBACK_KEY_BYTES];
            secureRandom.nextBytes(randomBytes);
            String callbackKey = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
            Arrays.fill(randomBytes, (byte) 0);
            if (callbackKey.startsWith("acct_")) {
                continue;
            }
            byte[] keyHash = sha256(callbackKey);
            SkitAdCallbackKeyDO row = new SkitAdCallbackKeyDO()
                    .setAdAccountId(adAccountId).setKeyVersion(nextVersion)
                    .setCallbackKeyHash(keyHash).setActive(true);
            row.setTenantId(tenantId);
            try {
                if (callbackKeyMapper.insert(row) != 1) {
                    throw new IllegalStateException("Callback credential version was not inserted");
                }
            } catch (DuplicateKeyException collision) {
                // Account locking and the monotonic version make the global hash the only retryable unique key.
                // Generate unrelated material without looking up or exposing the colliding tenant row.
                if (attempt + 1 == CALLBACK_HASH_RETRIES) {
                    throw new IllegalStateException("Could not allocate a globally unique callback credential",
                            collision);
                }
                continue;
            }
            callbackRouteRegistryService.registerTenantKey(mutation,
                    new SkitCallbackRouteRegistryService.TenantCallbackKeyRegistration(
                            row.getId() == null ? 0L : row.getId(), keyHash,
                            tenantId, adAccountId, nextVersion, rotationTime, null));
            return new CallbackKeyIssue(tenantId, adAccountId, nextVersion,
                    rotationTime, callbackKey);
        }
        throw new IllegalStateException("Could not allocate a globally unique callback credential");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean revokeAllCallbackKeys(long tenantId, long adAccountId) {
        validateScope(tenantId, adAccountId);
        AtomicReference<Boolean> revoked = new AtomicReference<>(Boolean.FALSE);
        TenantUtils.execute(tenantId, () -> revoked.set(
                revokeAllCallbackKeysInsideTenant(tenantId, adAccountId)));
        return Boolean.TRUE.equals(revoked.get());
    }

    private boolean revokeAllCallbackKeysInsideTenant(long tenantId, long adAccountId) {
        lockAccount(tenantId, adAccountId);
        SkitCallbackRouteRegistryService.TenantKeyMutation mutation =
                callbackRouteRegistryService.beginTenantKeyMutation(tenantId, adAccountId);
        LocalDateTime revokedAt = now();
        List<SkitAdCallbackKeyDO> historical = callbackKeyMapper.selectUnrevokedForUpdate(
                tenantId, adAccountId);
        callbackRouteRegistryService.registerTenantKeys(mutation, historical.stream()
                .map(row -> SkitCallbackRouteRegistryService.TenantCallbackKeyRegistration.fromLegacy(
                        row, revokedAt))
                .collect(Collectors.toList()));
        int changed = callbackKeyMapper.revokeAllUnrevokedVersions(
                tenantId, adAccountId, revokedAt);
        if (changed != historical.size()) {
            throw new IllegalStateException("Callback credential revocation count mismatch");
        }
        callbackRouteRegistryService.tombstoneRevokedTenantKeys(mutation,
                tenantId, adAccountId, revokedAt, changed);
        return changed > 0;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CredentialMetadata rotateRewardSecret(long tenantId, long adAccountId, byte[] rewardSecret,
                                                 Duration priorAcceptanceWindow) {
        validateScopeAndWindow(tenantId, adAccountId, priorAcceptanceWindow);
        if (rewardSecret == null
                || rewardSecret.length < MIN_REWARD_SECRET_PLAINTEXT_BYTES
                || rewardSecret.length > MAX_REWARD_SECRET_PLAINTEXT_BYTES) {
            throw new IllegalArgumentException(
                    "Reward secret must contain between "
                            + MIN_REWARD_SECRET_PLAINTEXT_BYTES + " and "
                            + MAX_REWARD_SECRET_PLAINTEXT_BYTES + " bytes");
        }
        AtomicReference<CredentialMetadata> result = new AtomicReference<>();
        TenantUtils.execute(tenantId, () -> result.set(rotateRewardSecretInsideTenant(
                tenantId, adAccountId, rewardSecret, priorAcceptanceWindow)));
        return result.get();
    }

    private CredentialMetadata rotateRewardSecretInsideTenant(long tenantId, long adAccountId,
                                                               byte[] rewardSecret,
                                                               Duration priorAcceptanceWindow) {
        lockAccount(tenantId, adAccountId);
        int nextVersion = nextVersion(rewardSecretMapper.selectMaxVersion(tenantId, adAccountId));
        SkitAdRewardSecretVersionDO prior = rewardSecretMapper.selectActiveForUpdate(tenantId, adAccountId);
        validateRewardRowScope(prior, tenantId, adAccountId);
        LocalDateTime rotationTime = now();

        byte[] plaintext = rewardSecret.clone();
        SkitAdCredentialCryptoService.EncryptedSecret encrypted;
        try {
            encrypted = cryptoService.encrypt(SkitAdCredentialCryptoService.Context.rewardSecret(
                    tenantId, adAccountId, nextVersion,
                    SkitAdCredentialCryptoService.CURRENT_ENVELOPE_VERSION), plaintext);
        } finally {
            Arrays.fill(plaintext, (byte) 0);
        }

        if (prior != null) {
            int retired = rewardSecretMapper.retireActiveVersion(tenantId, adAccountId, prior.getId(),
                    rotationTime.plus(priorAcceptanceWindow));
            if (retired != 1) {
                throw new IllegalStateException("Active reward credential changed during rotation");
            }
        }
        SkitAdRewardSecretVersionDO row = new SkitAdRewardSecretVersionDO()
                .setAdAccountId(adAccountId).setSecretVersion(nextVersion)
                .setCiphertext(encrypted.getCiphertext()).setNonce(encrypted.getNonce())
                .setEncryptionKeyId(encrypted.getKeyId()).setEnvelopeVersion(encrypted.getEnvelopeVersion())
                .setActive(true);
        row.setTenantId(tenantId);
        if (rewardSecretMapper.insert(row) != 1) {
            throw new IllegalStateException("Reward credential version was not inserted");
        }
        return new CredentialMetadata(tenantId, adAccountId, nextVersion, true,
                rotationTime, null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean revokeAllRewardSecrets(long tenantId, long adAccountId) {
        validateScope(tenantId, adAccountId);
        AtomicReference<Boolean> revoked = new AtomicReference<>(Boolean.FALSE);
        TenantUtils.execute(tenantId, () -> revoked.set(
                revokeAllRewardSecretsInsideTenant(tenantId, adAccountId)));
        return Boolean.TRUE.equals(revoked.get());
    }

    private boolean revokeAllRewardSecretsInsideTenant(long tenantId, long adAccountId) {
        lockAccount(tenantId, adAccountId);
        int changed = rewardSecretMapper.revokeAllUnrevokedVersions(
                tenantId, adAccountId, now());
        if (changed < 0) {
            throw new IllegalStateException("Reward credential revocation count is invalid");
        }
        return changed > 0;
    }

    @Override
    public CredentialMetadata getActiveCallbackKeyVersion(long tenantId, long adAccountId) {
        validateScope(tenantId, adAccountId);
        AtomicReference<SkitAdCallbackKeyDO> row = new AtomicReference<>();
        TenantUtils.execute(tenantId,
                () -> row.set(callbackKeyMapper.selectActive(tenantId, adAccountId)));
        validateCallbackRowScope(row.get(), tenantId, adAccountId);
        return row.get() == null ? null : callbackMetadata(row.get());
    }

    @Override
    public CredentialMetadata getActiveRewardSecretVersion(long tenantId, long adAccountId) {
        validateScope(tenantId, adAccountId);
        AtomicReference<SkitAdRewardSecretVersionDO> row = new AtomicReference<>();
        TenantUtils.execute(tenantId,
                () -> row.set(rewardSecretMapper.selectActive(tenantId, adAccountId)));
        validateRewardRowScope(row.get(), tenantId, adAccountId);
        return row.get() == null ? null : rewardMetadata(row.get());
    }

    @Override
    public CallbackKeyResolution resolveCallbackKey(String callbackKey, LocalDateTime authoritativeReceivedAt) {
        if (callbackKey == null || !callbackKey.matches("^[A-Za-z0-9_-]{43}$")
                || authoritativeReceivedAt == null) {
            throw unavailableCredential();
        }
        byte[] hash = sha256(callbackKey);
        AtomicReference<SkitAdCallbackKeyDO> row = new AtomicReference<>();
        try {
            TenantUtils.executeIgnore(() -> row.set(callbackKeyMapper.selectByHash(hash)));
        } finally {
            Arrays.fill(hash, (byte) 0);
        }
        SkitAdCallbackKeyDO resolved = row.get();
        if (!isAccepted(resolved == null ? null : resolved.getActive(),
                resolved == null ? null : resolved.getAcceptUntil(),
                resolved == null ? null : resolved.getRevokedAt(), authoritativeReceivedAt)) {
            throw unavailableCredential();
        }
        return new CallbackKeyResolution(resolved.getTenantId(), resolved.getAdAccountId(),
                resolved.getKeyVersion(), Boolean.TRUE.equals(resolved.getActive()), resolved.getAcceptUntil());
    }

    @Override
    public ResolvedRewardSecret resolveRewardSecret(long tenantId, long adAccountId, int secretVersion,
                                                    LocalDateTime sessionRewardAcceptUntil,
                                                    LocalDateTime authoritativeReceivedAt) {
        validateScope(tenantId, adAccountId);
        if (secretVersion <= 0 || sessionRewardAcceptUntil == null || authoritativeReceivedAt == null) {
            throw unavailableCredential();
        }
        if (authoritativeReceivedAt.isAfter(sessionRewardAcceptUntil)) {
            throw unavailableCredential();
        }
        AtomicReference<SkitAdRewardSecretVersionDO> row = new AtomicReference<>();
        TenantUtils.execute(tenantId, () -> row.set(
                rewardSecretMapper.selectByVersion(tenantId, adAccountId, secretVersion)));
        SkitAdRewardSecretVersionDO resolved = row.get();
        if (!rewardRowMatches(resolved, tenantId, adAccountId, secretVersion)
                || !isAccepted(resolved.getActive(), resolved.getAcceptUntil(), resolved.getRevokedAt(),
                authoritativeReceivedAt)) {
            throw unavailableCredential();
        }
        SkitAdCredentialCryptoService.EncryptedSecret encrypted =
                new SkitAdCredentialCryptoService.EncryptedSecret(resolved.getCiphertext(), resolved.getNonce(),
                        resolved.getEncryptionKeyId(), resolved.getEnvelopeVersion());
        byte[] plaintext = cryptoService.decrypt(SkitAdCredentialCryptoService.Context.rewardSecret(
                resolved.getTenantId(), resolved.getAdAccountId(), resolved.getSecretVersion(),
                resolved.getEnvelopeVersion()), encrypted);
        try {
            return new ResolvedRewardSecret(resolved.getTenantId(), resolved.getAdAccountId(),
                    resolved.getSecretVersion(), Boolean.TRUE.equals(resolved.getActive()),
                    resolved.getAcceptUntil(), plaintext);
        } finally {
            Arrays.fill(plaintext, (byte) 0);
        }
    }

    private void lockAccount(long tenantId, long adAccountId) {
        Long locked = accountMapper.lockByTenantAndId(tenantId, adAccountId);
        if (locked == null || locked != adAccountId) {
            throw new IllegalStateException("Advertising account does not exist in the requested tenant");
        }
    }

    private void retireCallbackVersion(SkitAdCallbackKeyDO prior, long tenantId, long adAccountId,
                                       LocalDateTime acceptUntil) {
        int retired = callbackKeyMapper.retireActiveVersion(tenantId, adAccountId, prior.getId(), acceptUntil);
        if (retired != 1) {
            throw new IllegalStateException("Active callback credential changed during rotation");
        }
    }

    private static int nextVersion(Integer maximum) {
        int current = maximum == null ? 0 : maximum;
        if (current < 0 || current == Integer.MAX_VALUE) {
            throw new IllegalStateException("Credential version range is exhausted or corrupt");
        }
        return current + 1;
    }

    private static void validateScopeAndWindow(long tenantId, long adAccountId, Duration window) {
        validateScope(tenantId, adAccountId);
        if (window == null || window.isNegative()
                || window.compareTo(MAX_PRIOR_ACCEPTANCE_WINDOW) > 0) {
            throw new IllegalArgumentException("Prior credential acceptance window must be between 0 and 24 hours");
        }
    }

    private static void validateScope(long tenantId, long adAccountId) {
        if (tenantId <= 0 || adAccountId <= 0) {
            throw new IllegalArgumentException("Tenant and advertising account identifiers must be positive");
        }
    }

    private static void validateCallbackRowScope(SkitAdCallbackKeyDO row, long tenantId, long adAccountId) {
        if (row != null && (!Objects.equals(row.getTenantId(), tenantId)
                || !Objects.equals(row.getAdAccountId(), adAccountId))) {
            throw new IllegalStateException("Callback credential mapper returned a cross-tenant row");
        }
    }

    private static void validateRewardRowScope(SkitAdRewardSecretVersionDO row,
                                               long tenantId, long adAccountId) {
        if (row != null && (!Objects.equals(row.getTenantId(), tenantId)
                || !Objects.equals(row.getAdAccountId(), adAccountId))) {
            throw new IllegalStateException("Reward credential mapper returned a cross-tenant row");
        }
    }

    private static boolean rewardRowMatches(SkitAdRewardSecretVersionDO row, long tenantId,
                                            long adAccountId, int version) {
        return row != null && Objects.equals(row.getTenantId(), tenantId)
                && Objects.equals(row.getAdAccountId(), adAccountId)
                && Objects.equals(row.getSecretVersion(), version)
                && row.getCiphertext() != null && row.getNonce() != null
                && row.getEncryptionKeyId() != null && row.getEnvelopeVersion() != null;
    }

    private static boolean isAccepted(Boolean active, LocalDateTime acceptUntil,
                                      LocalDateTime revokedAt, LocalDateTime authoritativeReceivedAt) {
        if (revokedAt != null) {
            return authoritativeReceivedAt.isBefore(revokedAt)
                    && (acceptUntil == null || !authoritativeReceivedAt.isAfter(acceptUntil));
        }
        return Boolean.TRUE.equals(active)
                || (acceptUntil != null && !authoritativeReceivedAt.isAfter(acceptUntil));
    }

    private static CredentialMetadata callbackMetadata(SkitAdCallbackKeyDO row) {
        return new CredentialMetadata(row.getTenantId(), row.getAdAccountId(), row.getKeyVersion(),
                Boolean.TRUE.equals(row.getActive()), row.getCreateTime(), row.getAcceptUntil());
    }

    private static CredentialMetadata rewardMetadata(SkitAdRewardSecretVersionDO row) {
        return new CredentialMetadata(row.getTenantId(), row.getAdAccountId(), row.getSecretVersion(),
                Boolean.TRUE.equals(row.getActive()), row.getCreateTime(), row.getAcceptUntil());
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.US_ASCII));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static CredentialUnavailableException unavailableCredential() {
        return new CredentialUnavailableException();
    }

}
