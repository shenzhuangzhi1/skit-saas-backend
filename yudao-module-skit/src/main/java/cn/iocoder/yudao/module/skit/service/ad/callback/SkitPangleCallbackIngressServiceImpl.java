package cn.iocoder.yudao.module.skit.service.ad.callback;

import cn.iocoder.yudao.framework.tenant.core.util.TenantUtils;
import cn.iocoder.yudao.module.skit.dal.dataobject.ad.SkitAdSessionDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.ad.SkitPangleRewardAttestationDO;
import cn.iocoder.yudao.module.skit.dal.mysql.ad.SkitAdSessionMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.ad.SkitPangleRewardAttestationMapper;
import cn.iocoder.yudao.module.skit.service.ad.SkitAdCredentialVersionService;
import cn.iocoder.yudao.module.skit.service.ad.SkitAdSessionTokenService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Validates Pangle reward evidence against the immutable Taku session snapshot.
 *
 * <p>This service can only append an attestation. The Taku callback processor remains the only
 * entitlement grant path.</p>
 */
@Service
public class SkitPangleCallbackIngressServiceImpl
        implements SkitPangleCallbackIngressService {

    private static final String PROVIDER = "PANGLE";
    private static final String TAKU_PROVIDER = "TAKU";
    private static final String REWARD = "REWARD";
    private static final byte[] CREDENTIAL_METADATA_FINGERPRINT_DOMAIN =
            "skit-pangle-reward-credential-metadata-v2\0"
                    .getBytes(StandardCharsets.US_ASCII);

    private final SkitCallbackRoutingService routingService;
    private final PangleRewardCallbackCanonicalizer canonicalizer;
    private final PangleRewardSignatureVerifier signatureVerifier;
    private final SkitAdCredentialVersionService credentialService;
    private final SkitAdSessionTokenService tokenService;
    private final SkitAdSessionMapper sessionMapper;
    private final SkitPangleRewardAttestationMapper attestationMapper;
    private final SkitPangleAttestationInboxWakeService inboxWakeService;
    private final SkitCallbackRateLimiter rateLimiter;
    private final Clock clock;

    @Autowired
    public SkitPangleCallbackIngressServiceImpl(
            SkitCallbackRoutingService routingService,
            PangleRewardCallbackCanonicalizer canonicalizer,
            PangleRewardSignatureVerifier signatureVerifier,
            SkitAdCredentialVersionService credentialService,
            SkitAdSessionTokenService tokenService,
            SkitAdSessionMapper sessionMapper,
            SkitPangleRewardAttestationMapper attestationMapper,
            SkitPangleAttestationInboxWakeService inboxWakeService,
            SkitCallbackRateLimiter rateLimiter) {
        this(routingService, canonicalizer, signatureVerifier, credentialService, tokenService,
                sessionMapper, attestationMapper, inboxWakeService, rateLimiter,
                Clock.systemDefaultZone());
    }

    SkitPangleCallbackIngressServiceImpl(
            SkitCallbackRoutingService routingService,
            PangleRewardCallbackCanonicalizer canonicalizer,
            PangleRewardSignatureVerifier signatureVerifier,
            SkitAdCredentialVersionService credentialService,
            SkitAdSessionTokenService tokenService,
            SkitAdSessionMapper sessionMapper,
            SkitPangleRewardAttestationMapper attestationMapper,
            SkitPangleAttestationInboxWakeService inboxWakeService,
            SkitCallbackRateLimiter rateLimiter,
            Clock clock) {
        this.routingService = Objects.requireNonNull(routingService, "routingService");
        this.canonicalizer = Objects.requireNonNull(canonicalizer, "canonicalizer");
        this.signatureVerifier = Objects.requireNonNull(signatureVerifier, "signatureVerifier");
        this.credentialService = Objects.requireNonNull(credentialService, "credentialService");
        this.tokenService = Objects.requireNonNull(tokenService, "tokenService");
        this.sessionMapper = Objects.requireNonNull(sessionMapper, "sessionMapper");
        this.attestationMapper = Objects.requireNonNull(attestationMapper, "attestationMapper");
        this.inboxWakeService = Objects.requireNonNull(inboxWakeService, "inboxWakeService");
        this.rateLimiter = Objects.requireNonNull(rateLimiter, "rateLimiter");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 2, rollbackFor = Exception.class)
    public boolean receiveReward(String callbackKey, String rawQuery, String clientIp) {
        LocalDateTime receivedAt = now();
        rateLimiter.check(PROVIDER, callbackKey, clientIp, REWARD);
        SkitCallbackRoutingService.CallbackRoute route;
        try {
            route = routingService.resolve(callbackKey, receivedAt);
        } catch (SkitAdCredentialVersionService.CredentialUnavailableException unavailable) {
            return false;
        }

        PangleRewardCallback callback;
        try {
            callback = canonicalizer.canonicalize(rawQuery);
        } catch (PangleRewardCallbackCanonicalizer.CallbackFormatException invalid) {
            return false;
        }

        byte[] extraHash;
        try {
            extraHash = tokenService.hashCustomData(callback.getExtra());
        } catch (IllegalArgumentException invalidSessionToken) {
            return false;
        }
        AtomicReference<Boolean> result = new AtomicReference<>();
        try {
            TenantUtils.execute(route.getTenantId(), () -> result.set(receiveInsideTenant(
                    route, callback, extraHash, receivedAt)));
        } finally {
            Arrays.fill(extraHash, (byte) 0);
        }
        return Boolean.TRUE.equals(result.get());
    }

    private boolean receiveInsideTenant(
            SkitCallbackRoutingService.CallbackRoute route, PangleRewardCallback callback,
            byte[] extraHash, LocalDateTime receivedAt) {
        SkitAdSessionDO session = sessionMapper.selectByTokenHashForUpdate(
                route.getTenantId(), route.getAdAccountId(), extraHash);
        if (!sessionIdentityMatches(route, session, callback, extraHash)) {
            return false;
        }

        SkitPangleRewardAttestationDO existingByTransaction =
                attestationMapper.selectByTransactionId(route.getTenantId(),
                        session.getPangleAdAccountId(), callback.getTransactionId());
        if (existingByTransaction != null) {
            boolean replay = exactStoredReplay(route, session, callback, extraHash,
                    existingByTransaction);
            if (replay) {
                wakeMatchingTakuInbox(route, session);
            }
            return replay;
        }
        if (!sessionAllowsNewAttestation(session, receivedAt)) {
            return false;
        }

        boolean signatureValid;
        try (SkitAdCredentialVersionService.ResolvedRewardSecret secret =
                     credentialService.resolveRewardSecret(
                             route.getTenantId(), session.getPangleAdAccountId(),
                             session.getPangleRewardSecretVersion(),
                             session.getRewardAcceptUntil(), receivedAt)) {
            signatureValid = secret.withSecret(value ->
                    signatureVerifier.verify(callback, value));
        } catch (SkitAdCredentialVersionService.CredentialUnavailableException unavailable) {
            return false;
        }
        if (!signatureValid) {
            return false;
        }
        byte[] credentialMetadataFingerprint =
                credentialMetadataFingerprint(route, session);

        SkitPangleRewardAttestationDO candidate = new SkitPangleRewardAttestationDO()
                .setTakuAdAccountId(route.getAdAccountId())
                .setPangleAdAccountId(session.getPangleAdAccountId())
                .setAdSessionId(session.getId())
                .setCallbackKeyVersion(route.getCallbackKeyVersion())
                .setPangleRewardSecretVersion(session.getPangleRewardSecretVersion())
                .setPangleRewardPlacementId(session.getPangleRewardPlacementId())
                .setProvider(PROVIDER)
                .setProviderTransactionId(callback.getTransactionId())
                .setProviderUserId(callback.getUserId())
                .setExtraDataHash(extraHash.clone())
                .setRewardName(callback.getRewardName())
                .setRewardAmount(Integer.valueOf(callback.getRewardAmountLexical()))
                .setCanonicalPayloadHash(callback.getCanonicalPayloadHash())
                .setCredentialFingerprint(credentialMetadataFingerprint.clone())
                .setReceivedAt(receivedAt);
        candidate.setTenantId(route.getTenantId());
        Arrays.fill(credentialMetadataFingerprint, (byte) 0);

        SkitPangleRewardAttestationDO byTransaction =
                attestationMapper.selectByTransactionId(route.getTenantId(),
                        session.getPangleAdAccountId(), callback.getTransactionId());
        if (byTransaction != null) {
            return exactReplayAndWake(candidate, byTransaction);
        }
        SkitPangleRewardAttestationDO bySession = attestationMapper.selectBySession(
                route.getTenantId(), route.getAdAccountId(), session.getId());
        if (bySession != null) {
            return exactReplayAndWake(candidate, bySession);
        }
        try {
            int inserted = attestationMapper.insert(candidate);
            if (inserted != 1 || candidate.getId() == null || candidate.getId() <= 0) {
                throw new IllegalStateException(
                        "Pangle reward attestation was not inserted exactly once");
            }
            wakeMatchingTakuInbox(candidate);
            return true;
        } catch (DuplicateKeyException concurrentDelivery) {
            return resolveConcurrentDuplicate(candidate);
        }
    }

    private boolean resolveConcurrentDuplicate(SkitPangleRewardAttestationDO candidate) {
        SkitPangleRewardAttestationDO byTransaction =
                attestationMapper.selectByTransactionId(candidate.getTenantId(),
                        candidate.getPangleAdAccountId(), candidate.getProviderTransactionId());
        if (byTransaction != null) {
            return exactReplayAndWake(candidate, byTransaction);
        }
        SkitPangleRewardAttestationDO bySession = attestationMapper.selectBySession(
                candidate.getTenantId(), candidate.getTakuAdAccountId(),
                candidate.getAdSessionId());
        return bySession != null && exactReplayAndWake(candidate, bySession);
    }

    private boolean exactReplayAndWake(
            SkitPangleRewardAttestationDO candidate,
            SkitPangleRewardAttestationDO existing) {
        if (!exactReplay(candidate, existing)) {
            return false;
        }
        wakeMatchingTakuInbox(candidate);
        return true;
    }

    private void wakeMatchingTakuInbox(
            SkitCallbackRoutingService.CallbackRoute route, SkitAdSessionDO session) {
        wakeMatchingTakuInboxAfterCommit(route.getTenantId(), route.getAdAccountId(),
                session.getId(), route.getCallbackKeyVersion());
    }

    private void wakeMatchingTakuInbox(SkitPangleRewardAttestationDO attestation) {
        wakeMatchingTakuInboxAfterCommit(attestation.getTenantId(),
                attestation.getTakuAdAccountId(), attestation.getAdSessionId(),
                attestation.getCallbackKeyVersion());
    }

    private void wakeMatchingTakuInboxAfterCommit(
            Long tenantId, Long takuAdAccountId, Long adSessionId,
            Integer callbackKeyVersion) {
        Runnable wake = () -> inboxWakeService.wakeRetry(
                tenantId, takuAdAccountId, adSessionId, callbackKeyVersion);
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            wake.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {

                    @Override
                    public void afterCommit() {
                        wake.run();
                    }

                });
    }

    private static boolean sessionIdentityMatches(
            SkitCallbackRoutingService.CallbackRoute route, SkitAdSessionDO session,
            PangleRewardCallback callback, byte[] extraHash) {
        if (session == null || !Objects.equals(session.getTenantId(), route.getTenantId())
                || !Objects.equals(session.getAdAccountId(), route.getAdAccountId())
                || !Objects.equals(session.getCallbackKeyVersion(), route.getCallbackKeyVersion())
                || !TAKU_PROVIDER.equals(session.getProvider())
                || !Objects.equals(session.getPseudonymousUserId(), callback.getUserId())
                || session.getSessionTokenHash() == null
                || !MessageDigest.isEqual(session.getSessionTokenHash(), extraHash)) {
            return false;
        }
        return session.getPangleAdAccountId() != null
                && session.getPangleAdAccountId() > 0
                && session.getPangleRewardSecretVersion() != null
                && session.getPangleRewardSecretVersion() > 0
                && session.getPangleRewardPlacementId() != null
                && !session.getPangleRewardPlacementId().isEmpty();
    }

    private static boolean sessionAllowsNewAttestation(
            SkitAdSessionDO session, LocalDateTime receivedAt) {
        return session.getRewardAcceptUntil() != null
                && !receivedAt.isAfter(session.getRewardAcceptUntil())
                && ("PENDING".equals(session.getRewardVerificationStatus())
                || "SIGNED_VERIFIED".equals(session.getRewardVerificationStatus()));
    }

    private static boolean exactStoredReplay(
            SkitCallbackRoutingService.CallbackRoute route,
            SkitAdSessionDO session,
            PangleRewardCallback callback,
            byte[] extraHash,
            SkitPangleRewardAttestationDO existing) {
        byte[] storedFingerprint = existing.getCredentialFingerprint();
        return Objects.equals(existing.getTenantId(), route.getTenantId())
                && Objects.equals(existing.getTakuAdAccountId(), route.getAdAccountId())
                && Objects.equals(existing.getPangleAdAccountId(),
                session.getPangleAdAccountId())
                && Objects.equals(existing.getAdSessionId(), session.getId())
                && Objects.equals(existing.getCallbackKeyVersion(),
                route.getCallbackKeyVersion())
                && Objects.equals(existing.getPangleRewardSecretVersion(),
                session.getPangleRewardSecretVersion())
                && Objects.equals(existing.getPangleRewardPlacementId(),
                session.getPangleRewardPlacementId())
                && PROVIDER.equals(existing.getProvider())
                && Objects.equals(existing.getProviderTransactionId(),
                callback.getTransactionId())
                && Objects.equals(existing.getProviderUserId(), callback.getUserId())
                && Objects.equals(existing.getRewardName(), callback.getRewardName())
                && Objects.equals(existing.getRewardAmount(),
                Integer.valueOf(callback.getRewardAmountLexical()))
                && sameHash(existing.getExtraDataHash(), extraHash)
                && sameHash(existing.getCanonicalPayloadHash(),
                callback.getCanonicalPayloadHash())
                && storedFingerprint != null
                && storedFingerprint.length == 32;
    }

    private static boolean exactReplay(
            SkitPangleRewardAttestationDO candidate,
            SkitPangleRewardAttestationDO existing) {
        return Objects.equals(existing.getTenantId(), candidate.getTenantId())
                && Objects.equals(existing.getTakuAdAccountId(), candidate.getTakuAdAccountId())
                && Objects.equals(existing.getPangleAdAccountId(), candidate.getPangleAdAccountId())
                && Objects.equals(existing.getAdSessionId(), candidate.getAdSessionId())
                && Objects.equals(existing.getCallbackKeyVersion(),
                candidate.getCallbackKeyVersion())
                && Objects.equals(existing.getPangleRewardSecretVersion(),
                candidate.getPangleRewardSecretVersion())
                && Objects.equals(existing.getPangleRewardPlacementId(),
                candidate.getPangleRewardPlacementId())
                && PROVIDER.equals(existing.getProvider())
                && Objects.equals(existing.getProviderTransactionId(),
                candidate.getProviderTransactionId())
                && Objects.equals(existing.getProviderUserId(), candidate.getProviderUserId())
                && Objects.equals(existing.getRewardName(), candidate.getRewardName())
                && Objects.equals(existing.getRewardAmount(), candidate.getRewardAmount())
                && sameHash(existing.getExtraDataHash(), candidate.getExtraDataHash())
                && sameHash(existing.getCanonicalPayloadHash(),
                candidate.getCanonicalPayloadHash())
                && sameHash(existing.getCredentialFingerprint(),
                candidate.getCredentialFingerprint());
    }

    private static byte[] credentialMetadataFingerprint(
            SkitCallbackRoutingService.CallbackRoute route, SkitAdSessionDO session) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(CREDENTIAL_METADATA_FINGERPRINT_DOMAIN);
            digest.update(ByteBuffer.allocate(Long.BYTES).putLong(route.getTenantId()).array());
            digest.update(ByteBuffer.allocate(Long.BYTES).putLong(route.getAdAccountId()).array());
            digest.update(ByteBuffer.allocate(Long.BYTES)
                    .putLong(session.getPangleAdAccountId()).array());
            digest.update(ByteBuffer.allocate(Integer.BYTES)
                    .putInt(session.getPangleRewardSecretVersion()).array());
            return digest.digest();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private static boolean sameHash(byte[] first, byte[] second) {
        return first != null && second != null && MessageDigest.isEqual(first, second);
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock).withNano(0);
    }

}
