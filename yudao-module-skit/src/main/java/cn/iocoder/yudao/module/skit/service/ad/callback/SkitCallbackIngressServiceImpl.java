package cn.iocoder.yudao.module.skit.service.ad.callback;

import cn.iocoder.yudao.framework.tenant.core.util.TenantUtils;
import cn.iocoder.yudao.module.skit.dal.dataobject.ad.SkitAdCallbackAttemptDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.ad.SkitAdCallbackEdgeAttemptDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.ad.SkitAdCallbackInboxDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.ad.SkitAdNetworkCapabilityDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.ad.SkitAdSessionDO;
import cn.iocoder.yudao.module.skit.dal.mysql.ad.SkitAdCallbackAttemptMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.ad.SkitAdCallbackEdgeAttemptMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.ad.SkitAdCallbackInboxMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.ad.SkitAdNetworkCapabilityMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.ad.SkitAdSessionMapper;
import cn.iocoder.yudao.module.skit.framework.crypto.SkitCallbackPayloadCryptoService;
import cn.iocoder.yudao.module.skit.service.ad.SkitAdCredentialVersionService;
import cn.iocoder.yudao.module.skit.service.ad.SkitAdSessionTokenService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Converts one public Taku delivery into an immutable tenant-bound inbox item.
 * Client observations and unsigned provider fields never grant content or money here.
 */
@Service
public class SkitCallbackIngressServiceImpl implements SkitCallbackIngressService {

    private static final String PROVIDER = "TAKU";
    private static final String REWARD = "REWARD";
    private static final String IMPRESSION = "IMPRESSION";
    private static final int PAYLOAD_RETENTION_DAYS = 90;
    private static final long SIGNED_REWARD_FIELD_MASK = 0x3fL;
    private static final String SIGNED_REWARD_AUTHORITY = "SIGNED_REWARD";

    private final SkitCallbackRoutingService routingService;
    private final TakuCallbackCanonicalizer canonicalizer;
    private final TakuRewardSignatureVerifier signatureVerifier;
    private final SkitAdCredentialVersionService credentialService;
    private final SkitAdSessionTokenService tokenService;
    private final SkitAdSessionMapper sessionMapper;
    private final SkitAdCallbackInboxMapper inboxMapper;
    private final SkitAdCallbackAttemptMapper attemptMapper;
    private final SkitAdCallbackEdgeAttemptMapper edgeAttemptMapper;
    private final SkitAdNetworkCapabilityMapper networkCapabilityMapper;
    private final SkitCallbackPayloadCryptoService payloadCryptoService;
    private final SkitCallbackRateLimiter rateLimiter;
    private final Clock clock;

    @Autowired
    public SkitCallbackIngressServiceImpl(
            SkitCallbackRoutingService routingService,
            TakuCallbackCanonicalizer canonicalizer,
            TakuRewardSignatureVerifier signatureVerifier,
            SkitAdCredentialVersionService credentialService,
            SkitAdSessionTokenService tokenService,
            SkitAdSessionMapper sessionMapper,
            SkitAdCallbackInboxMapper inboxMapper,
            SkitAdCallbackAttemptMapper attemptMapper,
            SkitAdCallbackEdgeAttemptMapper edgeAttemptMapper,
            SkitAdNetworkCapabilityMapper networkCapabilityMapper,
            SkitCallbackPayloadCryptoService payloadCryptoService,
            SkitCallbackRateLimiter rateLimiter) {
        this(routingService, canonicalizer, signatureVerifier, credentialService, tokenService,
                sessionMapper, inboxMapper, attemptMapper, edgeAttemptMapper, networkCapabilityMapper,
                payloadCryptoService, rateLimiter, Clock.systemDefaultZone());
    }

    SkitCallbackIngressServiceImpl(
            SkitCallbackRoutingService routingService,
            TakuCallbackCanonicalizer canonicalizer,
            TakuRewardSignatureVerifier signatureVerifier,
            SkitAdCredentialVersionService credentialService,
            SkitAdSessionTokenService tokenService,
            SkitAdSessionMapper sessionMapper,
            SkitAdCallbackInboxMapper inboxMapper,
            SkitAdCallbackAttemptMapper attemptMapper,
            SkitAdCallbackEdgeAttemptMapper edgeAttemptMapper,
            SkitAdNetworkCapabilityMapper networkCapabilityMapper,
            SkitCallbackPayloadCryptoService payloadCryptoService,
            SkitCallbackRateLimiter rateLimiter,
            Clock clock) {
        this.routingService = Objects.requireNonNull(routingService, "routingService");
        this.canonicalizer = Objects.requireNonNull(canonicalizer, "canonicalizer");
        this.signatureVerifier = Objects.requireNonNull(signatureVerifier, "signatureVerifier");
        this.credentialService = Objects.requireNonNull(credentialService, "credentialService");
        this.tokenService = Objects.requireNonNull(tokenService, "tokenService");
        this.sessionMapper = Objects.requireNonNull(sessionMapper, "sessionMapper");
        this.inboxMapper = Objects.requireNonNull(inboxMapper, "inboxMapper");
        this.attemptMapper = Objects.requireNonNull(attemptMapper, "attemptMapper");
        this.edgeAttemptMapper = Objects.requireNonNull(edgeAttemptMapper, "edgeAttemptMapper");
        this.networkCapabilityMapper = Objects.requireNonNull(
                networkCapabilityMapper, "networkCapabilityMapper");
        this.payloadCryptoService = Objects.requireNonNull(payloadCryptoService, "payloadCryptoService");
        this.rateLimiter = Objects.requireNonNull(rateLimiter, "rateLimiter");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 2, rollbackFor = Exception.class)
    public IngressResponse receiveReward(String callbackKey, String rawQuery, String clientIp) {
        LocalDateTime receivedAt = now();
        applyRateLimit(callbackKey, clientIp, REWARD);
        SkitCallbackRoutingService.CallbackRoute route = resolveRoute(
                callbackKey, clientIp, REWARD, receivedAt);
        if (route == null) {
            return IngressResponse.REJECTED;
        }

        TakuRewardCallback callback;
        try {
            callback = canonicalizer.canonicalizeReward(rawQuery);
        } catch (TakuCallbackCanonicalizer.CallbackParseException invalid) {
            String result = invalid.getErrorCode() == TakuCallbackCanonicalizer.ErrorCode.INVALID_SIGNATURE
                    ? "INVALID_SIGNATURE" : "INVALID_QUERY";
            recordEdge(route, callbackKey, clientIp, REWARD, result, receivedAt);
            return "INVALID_SIGNATURE".equals(result)
                    ? IngressResponse.INVALID_SIGNATURE : IngressResponse.REJECTED;
        }
        if (callback.isHealthTestProbe()) {
            recordEdge(route, callbackKey, clientIp, REWARD, "HEALTH_PROBE", receivedAt);
            return IngressResponse.OK;
        }

        AtomicReference<IngressResponse> result = new AtomicReference<>();
        TenantUtils.execute(route.getTenantId(), () -> result.set(receiveRewardInsideTenant(
                route, callbackKey, clientIp, rawQuery, callback, receivedAt)));
        return Objects.requireNonNull(result.get(), "reward ingress result");
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 2, rollbackFor = Exception.class)
    public IngressResponse receiveImpression(String callbackKey, String rawQuery, String clientIp) {
        LocalDateTime receivedAt = now();
        applyRateLimit(callbackKey, clientIp, IMPRESSION);
        SkitCallbackRoutingService.CallbackRoute route = resolveRoute(
                callbackKey, clientIp, IMPRESSION, receivedAt);
        if (route == null) {
            return IngressResponse.REJECTED;
        }

        TakuImpressionCallback callback;
        try {
            callback = canonicalizer.canonicalizeImpression(rawQuery);
        } catch (TakuCallbackCanonicalizer.CallbackParseException invalid) {
            recordEdge(route, callbackKey, clientIp, IMPRESSION, "INVALID_QUERY", receivedAt);
            return IngressResponse.REJECTED;
        }
        AtomicReference<IngressResponse> result = new AtomicReference<>();
        TenantUtils.execute(route.getTenantId(), () -> result.set(receiveImpressionInsideTenant(
                route, rawQuery, callback, receivedAt)));
        return Objects.requireNonNull(result.get(), "impression ingress result");
    }

    private IngressResponse receiveRewardInsideTenant(
            SkitCallbackRoutingService.CallbackRoute route, String callbackKey, String clientIp,
            String rawQuery, TakuRewardCallback callback, LocalDateTime receivedAt) {
        byte[] tokenHash;
        try {
            tokenHash = tokenService.hashCustomData(callback.getExtraData());
        } catch (IllegalArgumentException invalidToken) {
            recordEdge(route, callbackKey, clientIp, REWARD, "SESSION_TOKEN_INVALID", receivedAt);
            return IngressResponse.REJECTED;
        }
        SkitAdSessionDO session;
        String showCustomExtHint = signatureVerifier.extractUnverifiedShowCustomExtHint(callback);
        try {
            session = showCustomExtHint == null
                    ? sessionMapper.selectByTokenHashForUpdate(route.getTenantId(),
                    route.getAdAccountId(), tokenHash)
                    : sessionMapper.selectByAccountAndSessionIdForUpdate(route.getTenantId(),
                    route.getAdAccountId(), showCustomExtHint);
        } finally {
            Arrays.fill(tokenHash, (byte) 0);
        }
        if (!rewardSessionMatches(route, session, callback, receivedAt)) {
            recordEdge(route, callbackKey, clientIp, REWARD, "SESSION_MISMATCH", receivedAt);
            return IngressResponse.REJECTED;
        }

        if (session.getRewardCallbackInboxId() != null) {
            return replayReceivedReward(route, session, callback, receivedAt,
                    callback.getCanonicalPayloadHash());
        }

        TakuRewardSignatureVerifier.VerificationResult verification;
        try (SkitAdCredentialVersionService.ResolvedRewardSecret secret =
                     credentialService.resolveRewardSecret(route.getTenantId(), route.getAdAccountId(),
                             session.getRewardSecretVersion(), session.getRewardAcceptUntil(), receivedAt)) {
            verification = secret.withSecret(value -> signatureVerifier.verify(callback, value));
        } catch (SkitAdCredentialVersionService.CredentialUnavailableException unavailable) {
            recordEdge(route, callbackKey, clientIp, REWARD, "SECRET_VERSION_REJECTED", receivedAt);
            return IngressResponse.REJECTED;
        }
        if (!verification.isCoreDigestValid()) {
            recordEdge(route, callbackKey, clientIp, REWARD, "INVALID_SIGNATURE", receivedAt);
            return IngressResponse.INVALID_SIGNATURE;
        }
        if (!verification.hasSignedRewardAuthority()
                || !signedAuthorityMatches(session, verification.getAuthority())) {
            recordEdge(route, callbackKey, clientIp, REWARD, verification.getStatus().name(), receivedAt);
            return IngressResponse.REJECTED;
        }

        TakuRewardSignatureVerifier.SignedRewardAuthority authority = verification.getAuthority();
        int networkFirmId = authority.getSignedIlrdEvidence().getNetworkFirmId();
        SkitAdNetworkCapabilityDO capability = networkCapabilityMapper.selectForShare(
                route.getTenantId(), route.getAdAccountId(), networkFirmId);
        if (!signedRewardCapabilityAllows(route, networkFirmId, capability, receivedAt)) {
            recordEdge(route, callbackKey, clientIp, REWARD, "CAPABILITY_DISABLED", receivedAt);
            return IngressResponse.REJECTED;
        }
        SkitAdCallbackInboxDO candidate = baseInbox(route, session.getId(), session.getRewardSecretVersion(),
                REWARD, callback.getTransactionId(), callback.getCanonicalPayloadHash(), receivedAt)
                .setProviderUserId(callback.getUserId())
                .setExtraDataHash(tokenService.hashCustomData(callback.getExtraData()))
                .setProviderTransactionId(authority.getTransactionId())
                .setProviderShowId(authority.getSignedIlrdEvidence().getShowId())
                .setPlacementId(authority.getPlacementId())
                .setAdsourceId(authority.getAdsourceId())
                .setNetworkFirmId(networkFirmId)
                .setSignedFieldMask(SIGNED_REWARD_FIELD_MASK)
                .setEvidenceProvenance("SIGNED_ILRD")
                .setAuthenticationLevel("SIGNED_REWARD")
                .setSignatureStatus("VALID");
        encryptPayload(candidate, rawQuery);
        CanonicalResult canonical = persistCanonical(candidate, callback.getCanonicalPayloadHash(), receivedAt);
        if (canonical.response != IngressResponse.OK) {
            return canonical.response;
        }
        int marked = sessionMapper.markRewardCallbackReceivedCas(route.getTenantId(), session.getId(),
                route.getAdAccountId(), canonical.row.getId(), receivedAt);
        if (marked != 1) {
            throw new IllegalStateException("Reward callback receipt changed concurrently");
        }
        return IngressResponse.OK;
    }

    private IngressResponse replayReceivedReward(
            SkitCallbackRoutingService.CallbackRoute route, SkitAdSessionDO session,
            TakuRewardCallback callback, LocalDateTime receivedAt, byte[] payloadHash) {
        SkitAdCallbackInboxDO existing = inboxMapper.selectByTenantAccountAndIdForUpdate(
                route.getTenantId(), route.getAdAccountId(), session.getRewardCallbackInboxId());
        if (!canonicalScopeMatches(existing, route, REWARD, callback.getTransactionId(), session.getId())) {
            return IngressResponse.REJECTED;
        }
        if (!sameHash(payloadHash, existing.getCanonicalPayloadHash())) {
            if ("CANONICAL".equals(existing.getDeliveryIntegrityStatus())) {
                int conflicted = inboxMapper.markPayloadConflict(route.getTenantId(), route.getAdAccountId(),
                        existing.getId(), receivedAt);
                if (conflicted != 1) {
                    throw new IllegalStateException("Callback payload conflict changed concurrently");
                }
            } else if (!"PAYLOAD_CONFLICT".equals(existing.getDeliveryIntegrityStatus())) {
                throw new IllegalStateException("Callback delivery integrity state is invalid");
            }
            appendAttempt(existing, payloadHash, "PAYLOAD_CONFLICT", receivedAt);
            return IngressResponse.REJECTED;
        }
        appendAttempt(existing, payloadHash, "DUPLICATE", receivedAt);
        return response(existing.getIngressResponseCode());
    }

    private IngressResponse receiveImpressionInsideTenant(
            SkitCallbackRoutingService.CallbackRoute route, String rawQuery,
            TakuImpressionCallback callback, LocalDateTime receivedAt) {
        SkitAdSessionDO matched = null;
        if (callback.getShowCustomExt() != null && !callback.getShowCustomExt().isEmpty()) {
            SkitAdSessionDO candidate = sessionMapper.selectByAccountAndSessionIdForUpdate(
                    route.getTenantId(), route.getAdAccountId(), callback.getShowCustomExt());
            if (impressionSessionMatches(route, candidate, callback)) {
                matched = candidate;
            }
        }
        String idempotencyKey = lengthPrefix(callback.getRequestId()) + ':' + callback.getAdsourceId();
        SkitAdCallbackInboxDO candidate = baseInbox(route, matched == null ? null : matched.getId(), null,
                IMPRESSION, idempotencyKey, callback.getCanonicalPayloadHash(), receivedAt)
                .setProviderUserId(callback.getUserId())
                .setProviderRequestId(callback.getRequestId())
                .setPlacementId(callback.getPlacementId())
                .setAdsourceId(callback.getAdsourceId())
                .setNetworkFirmId(callback.getObservedNetworkFirmId())
                .setSourceCurrency(callback.getCurrency())
                .setSignedFieldMask(0L)
                .setEvidenceProvenance(matched == null ? "UNMATCHED" : "MATCHED_SESSION")
                .setAuthenticationLevel("UNSIGNED_PROVIDER_OBSERVATION")
                .setSignatureStatus("NOT_APPLICABLE");
        encryptPayload(candidate, rawQuery);
        return persistCanonical(candidate, callback.getCanonicalPayloadHash(), receivedAt).response;
    }

    private CanonicalResult persistCanonical(SkitAdCallbackInboxDO candidate,
                                             byte[] incomingHash, LocalDateTime receivedAt) {
        int affected = inboxMapper.insertOrGetCanonical(candidate);
        if (affected < 0 || candidate.getId() == null || candidate.getId() <= 0) {
            throw new IllegalStateException("Callback canonical inbox id was not returned");
        }
        SkitAdCallbackInboxDO canonical = inboxMapper.selectByTenantAccountAndIdForUpdate(
                candidate.getTenantId(), candidate.getAdAccountId(), candidate.getId());
        if (!canonicalScopeMatches(canonical,
                new SkitCallbackRoutingService.CallbackRoute(candidate.getTenantId(),
                        candidate.getAdAccountId(), candidate.getCallbackKeyVersion(), true, null),
                candidate.getCallbackType(), candidate.getIdempotencyKey(), candidate.getAdSessionId())) {
            throw new IllegalStateException("Callback canonical row escaped its tenant/account envelope");
        }
        if (!sameHash(incomingHash, canonical.getCanonicalPayloadHash())) {
            if ("CANONICAL".equals(canonical.getDeliveryIntegrityStatus())) {
                int conflicted = inboxMapper.markPayloadConflict(candidate.getTenantId(),
                        candidate.getAdAccountId(), canonical.getId(), receivedAt);
                if (conflicted != 1) {
                    throw new IllegalStateException("Callback payload conflict changed concurrently");
                }
            }
            appendAttempt(canonical, incomingHash, "PAYLOAD_CONFLICT", receivedAt);
            return new CanonicalResult(canonical, IngressResponse.REJECTED);
        }
        appendAttempt(canonical, incomingHash, affected == 1 ? "CANONICAL" : "DUPLICATE", receivedAt);
        return new CanonicalResult(canonical, response(canonical.getIngressResponseCode()));
    }

    private void appendAttempt(SkitAdCallbackInboxDO inbox, byte[] payloadHash,
                               String resultCode, LocalDateTime receivedAt) {
        Integer maximum = attemptMapper.selectMaxAttemptNo(inbox.getTenantId(), inbox.getId());
        int next = maximum == null ? 1 : Math.addExact(maximum, 1);
        SkitAdCallbackAttemptDO attempt = new SkitAdCallbackAttemptDO()
                .setCallbackInboxId(inbox.getId()).setAdAccountId(inbox.getAdAccountId())
                .setAdSessionId(inbox.getAdSessionId()).setAttemptNo(next)
                .setPayloadHash(payloadHash).setResultCode(resultCode).setReceivedAt(receivedAt);
        attempt.setTenantId(inbox.getTenantId());
        if (attemptMapper.insert(attempt) != 1) {
            throw new IllegalStateException("Callback delivery attempt was not appended exactly once");
        }
    }

    private SkitAdCallbackInboxDO baseInbox(
            SkitCallbackRoutingService.CallbackRoute route, Long sessionId, Integer secretVersion,
            String callbackType, String idempotencyKey, byte[] payloadHash, LocalDateTime receivedAt) {
        if (idempotencyKey == null || idempotencyKey.isEmpty() || idempotencyKey.length() > 255) {
            throw new IllegalArgumentException("Callback idempotency key is invalid");
        }
        SkitAdCallbackInboxDO row = new SkitAdCallbackInboxDO()
                .setAdAccountId(route.getAdAccountId()).setAdSessionId(sessionId)
                .setCallbackKeyVersion(route.getCallbackKeyVersion())
                .setRewardSecretVersion(secretVersion).setProvider(PROVIDER)
                .setCallbackType(callbackType).setIdempotencyKey(idempotencyKey)
                .setCanonicalPayloadHash(payloadHash).setDeliveryIntegrityStatus("CANONICAL")
                .setProcessingStatus("PENDING").setProcessingAttemptCount(0)
                .setReceivedAt(receivedAt).setIngressResponseCode(200);
        row.setTenantId(route.getTenantId());
        return row;
    }

    private void encryptPayload(SkitAdCallbackInboxDO row, String rawQuery) {
        if (rawQuery == null || rawQuery.isEmpty()) {
            throw new IllegalArgumentException("Callback payload is missing");
        }
        byte[] plaintext = rawQuery.getBytes(StandardCharsets.US_ASCII);
        SkitCallbackPayloadCryptoService.PayloadEnvelope encrypted;
        try {
            encrypted = payloadCryptoService.encrypt(
                    SkitCallbackPayloadCryptoService.Context.callbackPayload(
                            row.getTenantId(), row.getAdAccountId(), row.getCallbackType(),
                            row.getIdempotencyKey(), row.getCanonicalPayloadHash(),
                            SkitCallbackPayloadCryptoService.CURRENT_ENVELOPE_VERSION), plaintext);
        } finally {
            Arrays.fill(plaintext, (byte) 0);
        }
        row.setPayloadCiphertext(encrypted.getCiphertext()).setPayloadNonce(encrypted.getNonce())
                .setPayloadKeyId(encrypted.getKeyId())
                .setPayloadEnvelopeVersion(encrypted.getEnvelopeVersion())
                .setPayloadExpiresAt(row.getReceivedAt().plusDays(PAYLOAD_RETENTION_DAYS));
    }

    private boolean rewardSessionMatches(
            SkitCallbackRoutingService.CallbackRoute route, SkitAdSessionDO session,
            TakuRewardCallback callback, LocalDateTime receivedAt) {
        if (session == null || !Objects.equals(session.getTenantId(), route.getTenantId())
                || !Objects.equals(session.getAdAccountId(), route.getAdAccountId())
                || !Objects.equals(session.getCallbackKeyVersion(), route.getCallbackKeyVersion())
                || !PROVIDER.equals(session.getProvider())
                || !Objects.equals(session.getPseudonymousUserId(), callback.getUserId())
                || !Objects.equals(session.getPlacementId(), callback.getPlacementId())
                || (callback.getScenarioId() != null && !callback.getScenarioId().isEmpty()
                && !Objects.equals(session.getScenarioId(), callback.getScenarioId()))
                || session.getRewardAcceptUntil() == null) {
            return false;
        }
        boolean received = session.getRewardCallbackInboxId() != null
                && session.getRewardCallbackReceivedAt() != null;
        if (received) {
            return "PENDING".equals(session.getRewardVerificationStatus())
                    || "SIGNED_VERIFIED".equals(session.getRewardVerificationStatus());
        }
        return "PENDING".equals(session.getRewardVerificationStatus())
                && !receivedAt.isAfter(session.getRewardAcceptUntil())
                && session.getRewardCallbackInboxId() == null
                && session.getRewardCallbackReceivedAt() == null;
    }

    private boolean signedAuthorityMatches(
            SkitAdSessionDO session, TakuRewardSignatureVerifier.SignedRewardAuthority authority) {
        if (authority == null || authority.getSignedIlrdEvidence() == null
                || !Objects.equals(session.getPlacementId(), authority.getPlacementId())) {
            return false;
        }
        int network = authority.getSignedIlrdEvidence().getNetworkFirmId();
        if (network != 35 && network != 66 && network != 67) {
            return false;
        }
        String signedSessionId = authority.getSignedIlrdEvidence().getShowCustomExt();
        if (signedSessionId != null && !signedSessionId.equals(session.getSessionId())) {
            return false;
        }
        String signedShowId = authority.getSignedIlrdEvidence().getShowId();
        return signedShowId == null || session.getProviderShowId() == null
                || signedShowId.equals(session.getProviderShowId());
    }

    private boolean signedRewardCapabilityAllows(
            SkitCallbackRoutingService.CallbackRoute route, int networkFirmId,
            SkitAdNetworkCapabilityDO capability, LocalDateTime receivedAt) {
        return capability != null
                && Objects.equals(capability.getTenantId(), route.getTenantId())
                && Objects.equals(capability.getAdAccountId(), route.getAdAccountId())
                && Objects.equals(capability.getNetworkFirmId(), networkFirmId)
                && SIGNED_REWARD_AUTHORITY.equals(capability.getRewardAuthority())
                && Boolean.TRUE.equals(capability.getSupportsUserId())
                && Boolean.TRUE.equals(capability.getSupportsCustomData())
                && Boolean.TRUE.equals(capability.getSupportsStableTransaction())
                && Boolean.TRUE.equals(capability.getEnabled())
                && capability.getVerifiedAt() != null
                && !capability.getVerifiedAt().isAfter(receivedAt);
    }

    private boolean impressionSessionMatches(
            SkitCallbackRoutingService.CallbackRoute route, SkitAdSessionDO session,
            TakuImpressionCallback callback) {
        return session != null && Objects.equals(session.getTenantId(), route.getTenantId())
                && Objects.equals(session.getAdAccountId(), route.getAdAccountId())
                && Objects.equals(session.getCallbackKeyVersion(), route.getCallbackKeyVersion())
                && PROVIDER.equals(session.getProvider())
                && Objects.equals(session.getPlacementId(), callback.getPlacementId())
                && Objects.equals(session.getPseudonymousUserId(), callback.getUserId());
    }

    private boolean canonicalScopeMatches(
            SkitAdCallbackInboxDO row, SkitCallbackRoutingService.CallbackRoute route,
            String callbackType, String idempotencyKey, Long sessionId) {
        return row != null && Objects.equals(row.getTenantId(), route.getTenantId())
                && Objects.equals(row.getAdAccountId(), route.getAdAccountId())
                && Objects.equals(row.getCallbackKeyVersion(), route.getCallbackKeyVersion())
                && Objects.equals(row.getProvider(), PROVIDER)
                && Objects.equals(row.getCallbackType(), callbackType)
                && Objects.equals(row.getIdempotencyKey(), idempotencyKey)
                && Objects.equals(row.getAdSessionId(), sessionId);
    }

    private void applyRateLimit(String callbackKey, String clientIp, String callbackType) {
        // A rate limit is transient overload, never a deterministic Taku 602. Propagating keeps
        // provider retry semantics while still stopping routing and durable edge/inbox writes.
        rateLimiter.check(callbackKey, clientIp, callbackType);
    }

    private SkitCallbackRoutingService.CallbackRoute resolveRoute(
            String callbackKey, String clientIp, String callbackType, LocalDateTime receivedAt) {
        try {
            return routingService.resolve(callbackKey, receivedAt);
        } catch (SkitAdCredentialVersionService.CredentialUnavailableException unavailable) {
            recordEdge(null, callbackKey, clientIp, callbackType, "UNKNOWN_OR_EXPIRED_KEY", receivedAt);
            return null;
        }
    }

    private void recordEdge(
            SkitCallbackRoutingService.CallbackRoute route, String callbackKey, String clientIp,
            String callbackType, String resultCode, LocalDateTime receivedAt) {
        byte[] callbackKeyHash = sha256("callback-key\0", bounded(callbackKey));
        byte[] clientIpHash = hmacSha256(bounded(callbackKey), "client-ip\0", bounded(clientIp));
        try {
            SkitAdCallbackEdgeAttemptDO row = new SkitAdCallbackEdgeAttemptDO()
                    .setTenantId(route == null ? null : route.getTenantId())
                    .setAdAccountId(route == null ? null : route.getAdAccountId())
                    .setCallbackKeyHash(callbackKeyHash).setProvider(PROVIDER)
                    .setCallbackType(callbackType).setClientIpHash(clientIpHash)
                    .setRequestMethod("GET").setResultCode(resultCode).setReceivedAt(receivedAt);
            AtomicReference<Integer> inserted = new AtomicReference<>();
            TenantUtils.executeIgnore(() -> inserted.set(edgeAttemptMapper.insert(row)));
            if (!Integer.valueOf(1).equals(inserted.get())) {
                throw new IllegalStateException("Callback edge attempt was not appended exactly once");
            }
        } finally {
            Arrays.fill(callbackKeyHash, (byte) 0);
            Arrays.fill(clientIpHash, (byte) 0);
        }
    }

    private static IngressResponse response(Integer status) {
        if (status == null) {
            throw new IllegalStateException("Callback canonical response is missing");
        }
        if (status == 200) return IngressResponse.OK;
        if (status == 601) return IngressResponse.INVALID_SIGNATURE;
        if (status == 602) return IngressResponse.REJECTED;
        throw new IllegalStateException("Callback canonical response is invalid");
    }

    private static boolean sameHash(byte[] first, byte[] second) {
        return first != null && second != null && MessageDigest.isEqual(first, second);
    }

    private static String lengthPrefix(String value) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException("Callback request id is missing");
        }
        return value.length() + ":" + value;
    }

    private static String bounded(String value) {
        if (value == null) {
            return "<null>";
        }
        return value.length() <= 512 ? value : "<oversized:" + value.length() + ">";
    }

    private static byte[] sha256(String domain, String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(domain.getBytes(StandardCharsets.US_ASCII));
            return digest.digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private static byte[] hmacSha256(String key, String domain, String value) {
        byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(keyBytes, "HmacSHA256"));
            mac.update(domain.getBytes(StandardCharsets.US_ASCII));
            return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("HmacSHA256 is unavailable", ex);
        } finally {
            Arrays.fill(keyBytes, (byte) 0);
        }
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock).withNano(0);
    }

    private static final class CanonicalResult {

        private final SkitAdCallbackInboxDO row;
        private final IngressResponse response;

        private CanonicalResult(SkitAdCallbackInboxDO row, IngressResponse response) {
            this.row = row;
            this.response = response;
        }
    }

}
