package cn.iocoder.yudao.module.skit.service.ad.callback;

import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.skit.dal.dataobject.ad.SkitAdCallbackInboxDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.ad.SkitAdNetworkCapabilityDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.ad.SkitAdSessionDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.member.SkitContentEntitlementDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.member.SkitEntitlementGrantDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.revenue.SkitAdRevenueEventDO;
import cn.iocoder.yudao.module.skit.dal.mysql.ad.SkitAdCallbackInboxMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.ad.SkitAdNetworkCapabilityMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.ad.SkitAdSessionMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.member.SkitContentEntitlementMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.member.SkitEntitlementGrantMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.revenue.SkitAdRevenueEventMapper;
import cn.iocoder.yudao.module.skit.framework.crypto.SkitCallbackPayloadCryptoService;
import cn.iocoder.yudao.module.skit.service.ad.SkitAdCredentialVersionService;
import cn.iocoder.yudao.module.skit.service.ad.SkitAdSessionTokenService;
import cn.iocoder.yudao.module.skit.service.commission.SkitFrozenCommissionProjectionService;
import cn.iocoder.yudao.module.skit.service.commission.SkitPolicySnapshotService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Applies authenticated callback evidence to tenant-scoped domain facts. */
@Service
public class SkitAdCallbackProcessorImpl implements SkitAdCallbackProcessor {

    private static final String PROVIDER = "TAKU";
    private static final String REWARD = "REWARD";
    private static final String IMPRESSION = "IMPRESSION";
    private static final String IMPRESSION_SOURCE = "TAKU_IMPRESSION";
    private static final String SIGNED_REWARD = "SIGNED_REWARD";
    private static final long SIGNED_REWARD_FIELD_MASK = 0x3fL;
    private static final int LEGACY_GROSS_SCALE = 8;

    private final SkitAdCallbackInboxMapper inboxMapper;
    private final SkitAdSessionMapper sessionMapper;
    private final SkitAdNetworkCapabilityMapper capabilityMapper;
    private final SkitContentEntitlementMapper entitlementMapper;
    private final SkitEntitlementGrantMapper grantMapper;
    private final SkitAdRevenueEventMapper revenueMapper;
    private final SkitCallbackPayloadCryptoService payloadCrypto;
    private final SkitAdCredentialVersionService credentialService;
    private final SkitAdSessionTokenService tokenService;
    private final SkitPolicySnapshotService snapshotService;
    private final SkitFrozenCommissionProjectionService projectionService;
    private final TakuCallbackCanonicalizer canonicalizer;
    private final TakuRewardSignatureVerifier signatureVerifier;
    private final Clock clock;

    @Autowired
    public SkitAdCallbackProcessorImpl(SkitAdCallbackInboxMapper inboxMapper,
                                       SkitAdSessionMapper sessionMapper,
                                       SkitAdNetworkCapabilityMapper capabilityMapper,
                                       SkitContentEntitlementMapper entitlementMapper,
                                       SkitEntitlementGrantMapper grantMapper,
                                       SkitAdRevenueEventMapper revenueMapper,
                                       SkitCallbackPayloadCryptoService payloadCrypto,
                                       SkitAdCredentialVersionService credentialService,
                                       SkitAdSessionTokenService tokenService,
                                       SkitPolicySnapshotService snapshotService,
                                       SkitFrozenCommissionProjectionService projectionService,
                                       TakuCallbackCanonicalizer canonicalizer,
                                       TakuRewardSignatureVerifier signatureVerifier) {
        this(inboxMapper, sessionMapper, capabilityMapper, entitlementMapper, grantMapper,
                revenueMapper, payloadCrypto, credentialService, tokenService, snapshotService,
                projectionService, canonicalizer, signatureVerifier, Clock.systemDefaultZone());
    }

    SkitAdCallbackProcessorImpl(SkitAdCallbackInboxMapper inboxMapper,
                                SkitAdSessionMapper sessionMapper,
                                SkitAdNetworkCapabilityMapper capabilityMapper,
                                SkitContentEntitlementMapper entitlementMapper,
                                SkitEntitlementGrantMapper grantMapper,
                                SkitAdRevenueEventMapper revenueMapper,
                                SkitCallbackPayloadCryptoService payloadCrypto,
                                SkitAdCredentialVersionService credentialService,
                                SkitAdSessionTokenService tokenService,
                                SkitPolicySnapshotService snapshotService,
                                SkitFrozenCommissionProjectionService projectionService,
                                TakuCallbackCanonicalizer canonicalizer,
                                TakuRewardSignatureVerifier signatureVerifier,
                                Clock clock) {
        this.inboxMapper = Objects.requireNonNull(inboxMapper, "inboxMapper");
        this.sessionMapper = Objects.requireNonNull(sessionMapper, "sessionMapper");
        this.capabilityMapper = Objects.requireNonNull(capabilityMapper, "capabilityMapper");
        this.entitlementMapper = Objects.requireNonNull(entitlementMapper, "entitlementMapper");
        this.grantMapper = Objects.requireNonNull(grantMapper, "grantMapper");
        this.revenueMapper = Objects.requireNonNull(revenueMapper, "revenueMapper");
        this.payloadCrypto = Objects.requireNonNull(payloadCrypto, "payloadCrypto");
        this.credentialService = Objects.requireNonNull(credentialService, "credentialService");
        this.tokenService = Objects.requireNonNull(tokenService, "tokenService");
        this.snapshotService = Objects.requireNonNull(snapshotService, "snapshotService");
        this.projectionService = Objects.requireNonNull(projectionService, "projectionService");
        this.canonicalizer = Objects.requireNonNull(canonicalizer, "canonicalizer");
        this.signatureVerifier = Objects.requireNonNull(signatureVerifier, "signatureVerifier");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public ProcessResult process(long tenantId, long adAccountId, long callbackInboxId,
                                 String leaseOwner) {
        validateClaim(tenantId, adAccountId, callbackInboxId, leaseOwner);
        LocalDateTime processedAt = now();
        SkitAdCallbackInboxDO inbox = inboxMapper.selectActiveClaimForUpdate(
                tenantId, adAccountId, callbackInboxId, leaseOwner);
        validateInboxClaim(inbox, tenantId, adAccountId, callbackInboxId, leaseOwner);
        if (REWARD.equals(inbox.getCallbackType())) {
            return processReward(inbox, processedAt);
        }
        if (IMPRESSION.equals(inbox.getCallbackType())) {
            return processImpression(inbox, processedAt);
        }
        return rejectInbox(inbox, "CALLBACK_TYPE_UNSUPPORTED");
    }

    private ProcessResult processReward(SkitAdCallbackInboxDO inbox, LocalDateTime processedAt) {
        SkitAdSessionDO session = lockSession(inbox);
        String envelopeError = rewardEnvelopeError(inbox, session);
        if (envelopeError != null) {
            return rejectReward(inbox, session, envelopeError, processedAt);
        }
        if (!"CANONICAL".equals(inbox.getDeliveryIntegrityStatus())) {
            return rejectReward(inbox, session, "CALLBACK_PAYLOAD_CONFLICT", processedAt);
        }

        TakuRewardCallback callback;
        try {
            callback = canonicalizer.canonicalizeReward(decryptRawQuery(inbox));
        } catch (TakuCallbackCanonicalizer.CallbackParseException invalid) {
            return rejectReward(inbox, session, "STORED_REWARD_PAYLOAD_INVALID", processedAt);
        }
        if (!sameHash(callback.getCanonicalPayloadHash(), inbox.getCanonicalPayloadHash())) {
            return rejectReward(inbox, session, "CALLBACK_PAYLOAD_HASH_MISMATCH", processedAt);
        }
        String callbackError = rewardCallbackEnvelopeError(inbox, session, callback);
        if (callbackError != null) {
            return rejectReward(inbox, session, callbackError, processedAt);
        }

        TakuRewardSignatureVerifier.VerificationResult verification;
        try (SkitAdCredentialVersionService.ResolvedRewardSecret secret =
                     credentialService.resolveRewardSecret(inbox.getTenantId(), inbox.getAdAccountId(),
                             inbox.getRewardSecretVersion(), session.getRewardAcceptUntil(),
                             inbox.getReceivedAt())) {
            verification = secret.withSecret(value -> signatureVerifier.verify(callback, value));
        } catch (SkitAdCredentialVersionService.CredentialUnavailableException unavailable) {
            return rejectReward(inbox, session, "REWARD_SECRET_VERSION_REJECTED", processedAt);
        }
        if (!verification.isCoreDigestValid() || !verification.hasSignedRewardAuthority()) {
            return rejectReward(inbox, session, "REWARD_REVALIDATION_FAILED", processedAt);
        }
        TakuRewardSignatureVerifier.SignedRewardAuthority authority = verification.getAuthority();
        String authorityError = rewardAuthorityError(inbox, session, authority);
        if (authorityError != null) {
            return rejectReward(inbox, session, authorityError, processedAt);
        }

        int networkFirmId = authority.getSignedIlrdEvidence().getNetworkFirmId();
        SkitAdNetworkCapabilityDO capability = capabilityMapper.selectForShare(
                inbox.getTenantId(), inbox.getAdAccountId(), networkFirmId);
        if (!rewardCapabilityAllows(inbox, capability, networkFirmId)) {
            return rejectReward(inbox, session, "NETWORK_CAPABILITY_REJECTED", processedAt);
        }

        LockedImpressionAuthority impressionAuthority = lockExistingImpressionAuthority(session);
        String sourceAuthorityError = impressionAuthorityError(impressionAuthority, authority);
        if (sourceAuthorityError != null) {
            return rejectRewardPreservingImpression(
                    inbox, session, sourceAuthorityError, processedAt);
        }

        List<Integer> episodes = episodeRange(session);
        Map<Integer, SkitContentEntitlementDO> existing = lockExistingEntitlements(session, episodes);
        for (SkitContentEntitlementDO row : existing.values()) {
            if ("SECURITY_REVOKED".equals(row.getStatus())) {
                return rejectReward(inbox, session, "ENTITLEMENT_SECURITY_REVOKED", processedAt);
            }
            if (!"GRANTED".equals(row.getStatus())) {
                throw new IllegalStateException("Entitlement status is outside the supported state machine");
            }
        }

        SkitAdRevenueEventDO rewardedEstimate = prepareRewardEstimate(
                session, impressionAuthority, authority, inbox.getReceivedAt());

        String signedShowId = authority.getSignedIlrdEvidence().getShowId();
        int updated = sessionMapper.markSignedRewardAndGrantCas(
                inbox.getTenantId(), session.getId(), inbox.getAdAccountId(), inbox.getId(),
                inbox.getReceivedAt(), session.getVersion(), inbox.getCallbackKeyVersion(),
                inbox.getRewardSecretVersion(), authority.getTransactionId(), signedShowId,
                networkFirmId, authority.getAdsourceId(), processedAt);
        if (updated != 1) {
            throw new IllegalStateException("Reward session changed before verified entitlement commit");
        }

        grantEpisodes(session, episodes, existing, authority.getTransactionId(), processedAt);
        if (rewardedEstimate != null) {
            projectionService.projectRewardedEstimate(rewardedEstimate);
        }
        finishSucceeded(inbox);
        return ProcessResult.succeeded();
    }

    private ProcessResult processImpression(SkitAdCallbackInboxDO inbox, LocalDateTime processedAt) {
        if (inbox.getAdSessionId() == null) {
            return rejectInbox(inbox, "IMPRESSION_SESSION_UNMATCHED");
        }
        SkitAdSessionDO session = lockSession(inbox);
        String envelopeError = impressionEnvelopeError(inbox, session);
        if (envelopeError != null) {
            return rejectInbox(inbox, envelopeError);
        }
        if (!"CANONICAL".equals(inbox.getDeliveryIntegrityStatus())) {
            return rejectInbox(inbox, "CALLBACK_PAYLOAD_CONFLICT");
        }

        TakuImpressionCallback callback;
        try {
            callback = canonicalizer.canonicalizeImpression(decryptRawQuery(inbox));
        } catch (TakuCallbackCanonicalizer.CallbackParseException invalid) {
            return rejectInbox(inbox, "STORED_IMPRESSION_PAYLOAD_INVALID");
        }
        if (!sameHash(callback.getCanonicalPayloadHash(), inbox.getCanonicalPayloadHash())) {
            return rejectInbox(inbox, "CALLBACK_PAYLOAD_HASH_MISMATCH");
        }
        String callbackError = impressionCallbackEnvelopeError(inbox, session, callback);
        if (callbackError != null) {
            return rejectInbox(inbox, callbackError);
        }
        SkitAdNetworkCapabilityDO capability = capabilityMapper.selectForShare(
                inbox.getTenantId(), inbox.getAdAccountId(), callback.getObservedNetworkFirmId());
        if (!impressionCapabilityAllows(inbox, capability, callback.getObservedNetworkFirmId())) {
            return rejectInbox(inbox, "IMPRESSION_CAPABILITY_REJECTED");
        }

        ImpressionMoney money;
        try {
            money = ImpressionMoney.fromEcpm(callback.getAdsourcePriceLexical());
        } catch (ArithmeticException | NumberFormatException invalidMoney) {
            return rejectInbox(inbox, "IMPRESSION_AMOUNT_OUT_OF_RANGE");
        }
        String qualification = rewardQualification(session);
        String reconciliationStatus = impressionReconciliationStatus(session);
        String nextRevenueStatus = sessionRevenueStatus(session);
        SkitPolicySnapshotService.PolicySnapshot snapshot = requireSessionSnapshot(session);
        SkitAdRevenueEventDO existing = revenueMapper.selectByTenantSessionAndSourceForUpdate(
                inbox.getTenantId(), session.getId(), IMPRESSION_SOURCE);
        SkitAdRevenueEventDO canonicalEvent;
        if (existing == null) {
            SkitAdRevenueEventDO event = newRevenueEvent(inbox, session, callback, money,
                    qualification, reconciliationStatus, snapshot.getRuleVersion());
            if (revenueMapper.insert(event) != 1 || event.getId() == null || event.getId() <= 0) {
                throw new IllegalStateException("Impression revenue event was not inserted exactly once");
            }
            canonicalEvent = event;
        } else {
            validateExistingRevenue(existing, inbox, session, callback, money, qualification,
                    reconciliationStatus, snapshot.getRuleVersion());
            canonicalEvent = existing;
        }
        if (!Objects.equals(session.getRevenueStatus(), nextRevenueStatus)) {
            if (!"NONE".equals(session.getRevenueStatus())) {
                throw new IllegalStateException("Session revenue state conflicts with its frozen impression");
            }
            int updated = sessionMapper.updateRevenueStateCas(inbox.getTenantId(), session.getId(),
                    inbox.getAdAccountId(), session.getVersion(), session.getRevenueStatus(),
                    nextRevenueStatus, processedAt);
            if (updated != 1) {
                throw new IllegalStateException("Session revenue state changed during impression processing");
            }
        }
        if ("REWARDED".equals(qualification) && "FROZEN".equals(reconciliationStatus)) {
            projectionService.projectRewardedEstimate(canonicalEvent);
        } else if ("VERIFY_TIMEOUT".equals(session.getRewardVerificationStatus())) {
            projectionService.projectNonRewardedEstimate(canonicalEvent);
        }
        finishSucceeded(inbox);
        return ProcessResult.succeeded();
    }

    private SkitAdSessionDO lockSession(SkitAdCallbackInboxDO inbox) {
        if (inbox.getAdSessionId() == null || inbox.getAdSessionId() <= 0) {
            throw new IllegalStateException("Callback session identity is missing");
        }
        SkitAdSessionDO session = sessionMapper.selectByTenantAccountAndIdForUpdate(
                inbox.getTenantId(), inbox.getAdAccountId(), inbox.getAdSessionId());
        if (session == null || !Objects.equals(session.getTenantId(), inbox.getTenantId())
                || !Objects.equals(session.getAdAccountId(), inbox.getAdAccountId())
                || !Objects.equals(session.getId(), inbox.getAdSessionId())) {
            throw new IllegalStateException("Callback session escaped its tenant/account envelope");
        }
        return session;
    }

    private String rewardEnvelopeError(SkitAdCallbackInboxDO inbox, SkitAdSessionDO session) {
        if (!PROVIDER.equals(inbox.getProvider()) || !PROVIDER.equals(session.getProvider())
                || !REWARD.equals(inbox.getCallbackType())
                || !SIGNED_REWARD.equals(inbox.getAuthenticationLevel())
                || !"VALID".equals(inbox.getSignatureStatus())
                || !"SIGNED_ILRD".equals(inbox.getEvidenceProvenance())
                || !Objects.equals(inbox.getSignedFieldMask(), SIGNED_REWARD_FIELD_MASK)) {
            return "REWARD_PROVENANCE_INVALID";
        }
        if (!Objects.equals(session.getRewardCallbackInboxId(), inbox.getId())
                || !Objects.equals(session.getRewardCallbackReceivedAt(), inbox.getReceivedAt())) {
            return "REWARD_RECEIPT_BINDING_INVALID";
        }
        if (!Objects.equals(session.getCallbackKeyVersion(), inbox.getCallbackKeyVersion())
                || !Objects.equals(session.getRewardSecretVersion(), inbox.getRewardSecretVersion())) {
            return "REWARD_CREDENTIAL_VERSION_MISMATCH";
        }
        if (inbox.getReceivedAt() == null || session.getRewardAcceptUntil() == null
                || inbox.getReceivedAt().isAfter(session.getRewardAcceptUntil())) {
            return "REWARD_RECEIVED_AFTER_DEADLINE";
        }
        if (!"PENDING".equals(session.getRewardVerificationStatus())
                || !"NONE".equals(session.getEntitlementStatus())
                || session.getVersion() == null || session.getActiveScopeHash() == null
                || session.getActiveScopeReleasedAt() != null
                || session.getActiveScopeReleaseReason() != null) {
            return "REWARD_SESSION_NOT_PENDING";
        }
        if (session.getMemberId() == null || session.getMemberId() <= 0
                || session.getPolicySnapshotId() == null || session.getPolicySnapshotId() <= 0
                || session.getDramaId() == null || session.getDramaId() <= 0
                || session.getPseudonymousUserId() == null || session.getPlacementId() == null) {
            return "REWARD_SESSION_ENVELOPE_INVALID";
        }
        return null;
    }

    private String rewardCallbackEnvelopeError(SkitAdCallbackInboxDO inbox, SkitAdSessionDO session,
                                                TakuRewardCallback callback) {
        byte[] callbackTokenHash;
        try {
            callbackTokenHash = tokenService.hashCustomData(callback.getExtraData());
        } catch (IllegalArgumentException invalid) {
            return "REWARD_SESSION_TOKEN_INVALID";
        }
        try {
            if (!sameHash(callbackTokenHash, inbox.getExtraDataHash())) {
                return "REWARD_CALLBACK_TOKEN_INTEGRITY_MISMATCH";
            }
        } finally {
            Arrays.fill(callbackTokenHash, (byte) 0);
        }
        if (!Objects.equals(callback.getUserId(), session.getPseudonymousUserId())
                || !Objects.equals(callback.getUserId(), inbox.getProviderUserId())) {
            return "REWARD_USER_MISMATCH";
        }
        if (!Objects.equals(callback.getTransactionId(), inbox.getIdempotencyKey())
                || !Objects.equals(callback.getTransactionId(), inbox.getProviderTransactionId())) {
            return "REWARD_TRANSACTION_MISMATCH";
        }
        return null;
    }

    private String rewardAuthorityError(SkitAdCallbackInboxDO inbox, SkitAdSessionDO session,
                                        TakuRewardSignatureVerifier.SignedRewardAuthority authority) {
        TakuRewardSignatureVerifier.SignedIlrdEvidence evidence = authority.getSignedIlrdEvidence();
        if (!Objects.equals(authority.getTransactionId(), inbox.getProviderTransactionId())
                || !Objects.equals(authority.getPlacementId(), session.getPlacementId())
                || !Objects.equals(authority.getPlacementId(), inbox.getPlacementId())
                || !Objects.equals(authority.getAdsourceId(), inbox.getAdsourceId())
                || evidence == null || !Objects.equals(evidence.getNetworkFirmId(), inbox.getNetworkFirmId())
                || !Objects.equals(evidence.getShowId(), inbox.getProviderShowId())) {
            return "SIGNED_REWARD_AUTHORITY_MISMATCH";
        }
        String signedSessionId = evidence.getShowCustomExt();
        boolean tokenMatchesSession = sameHash(inbox.getExtraDataHash(), session.getSessionTokenHash());
        boolean signedSessionMatches = signedSessionId != null
                && signedSessionId.equals(session.getSessionId());
        if (signedSessionId != null && !signedSessionMatches) {
            return "SIGNED_SHOW_CUSTOM_EXT_MISMATCH";
        }
        if (!tokenMatchesSession && !signedSessionMatches) {
            return "REWARD_SESSION_BINDING_MISMATCH";
        }
        if (evidence.getShowId() != null && session.getProviderShowId() != null
                && !evidence.getShowId().equals(session.getProviderShowId())) {
            return "SIGNED_SHOW_MISMATCH";
        }
        return null;
    }

    private String impressionEnvelopeError(SkitAdCallbackInboxDO inbox, SkitAdSessionDO session) {
        if (!PROVIDER.equals(inbox.getProvider()) || !PROVIDER.equals(session.getProvider())
                || !IMPRESSION.equals(inbox.getCallbackType())
                || !"UNSIGNED_PROVIDER_OBSERVATION".equals(inbox.getAuthenticationLevel())
                || !"NOT_APPLICABLE".equals(inbox.getSignatureStatus())
                || !"MATCHED_SESSION".equals(inbox.getEvidenceProvenance())
                || !Objects.equals(inbox.getSignedFieldMask(), 0L)) {
            return "IMPRESSION_PROVENANCE_INVALID";
        }
        if (!Objects.equals(session.getCallbackKeyVersion(), inbox.getCallbackKeyVersion())
                || session.getMemberId() == null || session.getPolicySnapshotId() == null
                || session.getVersion() == null || session.getRevenueStatus() == null) {
            return "IMPRESSION_SESSION_ENVELOPE_INVALID";
        }
        String rewardStatus = session.getRewardVerificationStatus();
        String entitlementStatus = session.getEntitlementStatus();
        if (("PENDING".equals(rewardStatus) || "REJECTED".equals(rewardStatus)
                || "VERIFY_TIMEOUT".equals(rewardStatus)) && !"NONE".equals(entitlementStatus)) {
            return "IMPRESSION_SESSION_STATE_INVALID";
        }
        if ("SIGNED_VERIFIED".equals(rewardStatus)
                && !("GRANTED".equals(entitlementStatus)
                || "SECURITY_REVOKED".equals(entitlementStatus))) {
            return "IMPRESSION_SESSION_STATE_INVALID";
        }
        if ("SIGNED_VERIFIED".equals(rewardStatus)
                && (session.getProviderTransactionId() == null
                || session.getProviderTransactionId().isEmpty()
                || session.getNetworkFirmId() == null
                || session.getAdsourceId() == null
                || session.getAdsourceId().isEmpty())) {
            return "IMPRESSION_SESSION_STATE_INVALID";
        }
        return null;
    }

    private String impressionCallbackEnvelopeError(SkitAdCallbackInboxDO inbox, SkitAdSessionDO session,
                                                    TakuImpressionCallback callback) {
        String expectedIdempotency = callback.getRequestId().length() + ":" + callback.getRequestId()
                + ':' + callback.getAdsourceId();
        if (!Objects.equals(callback.getShowCustomExt(), session.getSessionId())) {
            return "IMPRESSION_SESSION_UNMATCHED";
        }
        if (!Objects.equals(callback.getUserId(), session.getPseudonymousUserId())
                || !Objects.equals(callback.getUserId(), inbox.getProviderUserId())) {
            return "IMPRESSION_USER_MISMATCH";
        }
        if (!Objects.equals(callback.getPlacementId(), session.getPlacementId())
                || !Objects.equals(callback.getPlacementId(), inbox.getPlacementId())
                || !Objects.equals(callback.getRequestId(), inbox.getProviderRequestId())
                || !Objects.equals(callback.getAdsourceId(), inbox.getAdsourceId())
                || !Objects.equals(callback.getObservedNetworkFirmId(), inbox.getNetworkFirmId())
                || !Objects.equals(callback.getCurrency(), inbox.getSourceCurrency())
                || !Objects.equals(expectedIdempotency, inbox.getIdempotencyKey())) {
            return "IMPRESSION_BINDING_MISMATCH";
        }
        if ("SIGNED_VERIFIED".equals(session.getRewardVerificationStatus())
                && (!Objects.equals(callback.getObservedNetworkFirmId(), session.getNetworkFirmId())
                || !Objects.equals(callback.getAdsourceId(), session.getAdsourceId()))) {
            return "IMPRESSION_SIGNED_AUTHORITY_MISMATCH";
        }
        return null;
    }

    private boolean rewardCapabilityAllows(SkitAdCallbackInboxDO inbox,
                                           SkitAdNetworkCapabilityDO capability,
                                           int networkFirmId) {
        return hardAllowedRewardNetwork(networkFirmId)
                && capabilityScopeMatches(inbox, capability, networkFirmId)
                && SIGNED_REWARD.equals(capability.getRewardAuthority())
                && Boolean.TRUE.equals(capability.getSupportsUserId())
                && Boolean.TRUE.equals(capability.getSupportsCustomData())
                && Boolean.TRUE.equals(capability.getSupportsStableTransaction());
    }

    private boolean impressionCapabilityAllows(SkitAdCallbackInboxDO inbox,
                                               SkitAdNetworkCapabilityDO capability,
                                               Integer networkFirmId) {
        return networkFirmId != null && capabilityScopeMatches(inbox, capability, networkFirmId)
                && Boolean.TRUE.equals(capability.getSupportsImpressionRevenue());
    }

    private boolean capabilityScopeMatches(SkitAdCallbackInboxDO inbox,
                                           SkitAdNetworkCapabilityDO capability,
                                           int networkFirmId) {
        return capability != null
                && Objects.equals(capability.getTenantId(), inbox.getTenantId())
                && Objects.equals(capability.getAdAccountId(), inbox.getAdAccountId())
                && Objects.equals(capability.getNetworkFirmId(), networkFirmId)
                && Boolean.TRUE.equals(capability.getEnabled())
                && capability.getVerifiedAt() != null
                && !capability.getVerifiedAt().isAfter(inbox.getReceivedAt());
    }

    private Map<Integer, SkitContentEntitlementDO> lockExistingEntitlements(
            SkitAdSessionDO session, List<Integer> episodes) {
        List<SkitContentEntitlementDO> rows = entitlementMapper.selectEpisodesForUpdate(
                session.getTenantId(), session.getMemberId(), session.getDramaId(), episodes);
        Map<Integer, SkitContentEntitlementDO> byEpisode = new HashMap<>();
        if (rows == null) {
            return byEpisode;
        }
        for (SkitContentEntitlementDO row : rows) {
            validateEntitlement(row, session, episodes);
            if (byEpisode.put(row.getEpisodeNo(), row) != null) {
                throw new IllegalStateException("Entitlement uniqueness was violated");
            }
        }
        return byEpisode;
    }

    private void grantEpisodes(SkitAdSessionDO session, List<Integer> episodes,
                               Map<Integer, SkitContentEntitlementDO> existing,
                               String transactionId, LocalDateTime grantedAt) {
        for (Integer episode : episodes) {
            SkitContentEntitlementDO entitlement = existing.get(episode);
            String grantResult;
            if (entitlement == null) {
                entitlement = new SkitContentEntitlementDO().setMemberId(session.getMemberId())
                        .setDramaId(session.getDramaId()).setEpisodeNo(episode).setStatus("GRANTED")
                        .setGrantedAt(grantedAt).setVersion(0);
                entitlement.setTenantId(session.getTenantId());
                int inserted = entitlementMapper.insertGrantedIfAbsent(entitlement);
                if (inserted < 0 || entitlement.getId() == null || entitlement.getId() <= 0) {
                    throw new IllegalStateException("Entitlement id was not returned after upsert");
                }
                grantResult = "CREATED";
            } else {
                grantResult = "ALREADY_OWNED";
            }
            SkitEntitlementGrantDO grant = grantMapper.selectBySessionAndEpisodeForUpdate(
                    session.getTenantId(), session.getId(), episode);
            if (grant == null) {
                grant = new SkitEntitlementGrantDO().setAdSessionId(session.getId())
                        .setEntitlementId(entitlement.getId()).setMemberId(session.getMemberId())
                        .setDramaId(session.getDramaId()).setEpisodeNo(episode)
                        .setProviderTransactionId(transactionId).setGrantResult(grantResult)
                        .setGrantedAt(grantedAt);
                grant.setTenantId(session.getTenantId());
                if (grantMapper.insert(grant) != 1) {
                    throw new IllegalStateException("Entitlement grant was not appended exactly once");
                }
            } else {
                validateExistingGrant(grant, session, entitlement, episode, transactionId);
            }
        }
    }

    private LockedImpressionAuthority lockExistingImpressionAuthority(SkitAdSessionDO session) {
        SkitAdRevenueEventDO event = revenueMapper.selectByTenantSessionAndSourceForUpdate(
                session.getTenantId(), session.getId(), IMPRESSION_SOURCE);
        if (event == null) {
            return null;
        }
        validateRevenueSessionEnvelope(event, session);
        SkitAdCallbackInboxDO impressionInbox = event.getCallbackInboxId() == null
                ? null : inboxMapper.selectByTenantAccountAndIdForUpdate(
                session.getTenantId(), session.getAdAccountId(), event.getCallbackInboxId());
        return new LockedImpressionAuthority(event, impressionInbox);
    }

    private String impressionAuthorityError(
            LockedImpressionAuthority locked,
            TakuRewardSignatureVerifier.SignedRewardAuthority signedAuthority) {
        if (locked == null) {
            return null;
        }
        SkitAdRevenueEventDO event = locked.event;
        SkitAdCallbackInboxDO impression = locked.inbox;
        TakuRewardSignatureVerifier.SignedIlrdEvidence evidence =
                signedAuthority.getSignedIlrdEvidence();
        if (impression == null || evidence == null || event.getCallbackInboxId() == null
                || event.getCallbackInboxId() <= 0
                || !Objects.equals(impression.getTenantId(), event.getTenantId())
                || !Objects.equals(impression.getAdAccountId(), event.getAdAccountId())
                || !Objects.equals(impression.getId(), event.getCallbackInboxId())
                || !Objects.equals(impression.getAdSessionId(), event.getAdSessionId())
                || !PROVIDER.equals(event.getProvider())
                || !IMPRESSION_SOURCE.equals(event.getSourceType())
                || !PROVIDER.equals(impression.getProvider())
                || !IMPRESSION.equals(impression.getCallbackType())
                || !"CANONICAL".equals(impression.getDeliveryIntegrityStatus())
                || !"SUCCEEDED".equals(impression.getProcessingStatus())
                || Boolean.TRUE.equals(event.getDeleted())
                || Boolean.TRUE.equals(impression.getDeleted())
                || !Objects.equals(event.getPlacementId(), impression.getPlacementId())
                || !Objects.equals(event.getPlacementId(), signedAuthority.getPlacementId())
                || !Objects.equals(event.getAdsourceId(), impression.getAdsourceId())
                || !Objects.equals(event.getAdsourceId(), signedAuthority.getAdsourceId())
                || !Objects.equals(impression.getNetworkFirmId(), evidence.getNetworkFirmId())) {
            return "SIGNED_REWARD_IMPRESSION_AUTHORITY_MISMATCH";
        }
        return null;
    }

    private SkitAdRevenueEventDO prepareRewardEstimate(
            SkitAdSessionDO session, LockedImpressionAuthority locked,
            TakuRewardSignatureVerifier.SignedRewardAuthority authority,
            LocalDateTime verifiedAt) {
        if (locked == null) {
            return null;
        }
        SkitAdRevenueEventDO event = locked.event;
        String transactionId = authority.getTransactionId();
        String signedShowId = authority.getSignedIlrdEvidence().getShowId();
        if ("PENDING_REWARD".equals(event.getRewardQualificationStatus())) {
            int updated = revenueMapper.markRewardQualifiedCas(session.getTenantId(), event.getId(),
                    session.getId(), session.getAdAccountId(), event.getVersion(),
                    event.getCallbackInboxId(), event.getPlacementId(), locked.inbox.getNetworkFirmId(),
                    event.getAdsourceId(), transactionId, signedShowId, verifiedAt);
            if (updated != 1) {
                throw new IllegalStateException("Frozen impression changed during reward convergence");
            }
            event = revenueMapper.selectByTenantSessionAndSourceForUpdate(
                    session.getTenantId(), session.getId(), IMPRESSION_SOURCE);
            validateRevenueSessionEnvelope(event, session);
            if (impressionAuthorityError(new LockedImpressionAuthority(event, locked.inbox),
                    authority) != null || !"REWARDED".equals(event.getRewardQualificationStatus())
                    || !"FROZEN".equals(event.getReconciliationStatus())
                    || !Objects.equals(event.getProviderTransactionId(), transactionId)
                    || (signedShowId != null
                    && !Objects.equals(event.getProviderShowId(), signedShowId))) {
                throw new IllegalStateException("Rewarded impression did not converge after CAS");
            }
        } else if (!"REWARDED".equals(event.getRewardQualificationStatus())
                || !Objects.equals(event.getProviderTransactionId(), transactionId)
                || (signedShowId != null
                && !Objects.equals(event.getProviderShowId(), signedShowId))) {
            throw new IllegalStateException("Frozen impression reward state conflicts with signed evidence");
        }
        return event;
    }

    private SkitAdRevenueEventDO newRevenueEvent(SkitAdCallbackInboxDO inbox, SkitAdSessionDO session,
                                                 TakuImpressionCallback callback,
                                                 ImpressionMoney money, String qualification,
                                                 String reconciliationStatus,
                                                 Integer ruleVersion) {
        // gross_amount is a conservative legacy display mirror only. Exact allocation always uses
        // estimated_amount_units + amount_scale and must never consume this rounded-down field.
        BigDecimal grossMirror = money.estimatedDecimal.setScale(LEGACY_GROSS_SCALE, RoundingMode.DOWN);
        if (grossMirror.precision() > 20) {
            throw new ArithmeticException("legacy gross display mirror exceeds DECIMAL(20,8)");
        }
        SkitAdRevenueEventDO row = new SkitAdRevenueEventDO()
                .setAdAccountId(inbox.getAdAccountId()).setProvider(PROVIDER)
                .setPlacementId(callback.getPlacementId()).setExternalEventId(inbox.getIdempotencyKey())
                .setSourceMemberId(session.getMemberId()).setGrossAmount(grossMirror)
                .setOccurredTime(inbox.getReceivedAt()).setCompleted(false).setMock(false).setStatus(0)
                .setRuleVersion(ruleVersion)
                .setRawData("calculation=TAKU_ECPM_DIV_1000;gross_amount=display_only_down_scale_8")
                .setAdSessionId(session.getId()).setCallbackInboxId(inbox.getId())
                .setPolicySnapshotId(session.getPolicySnapshotId()).setSourceType(IMPRESSION_SOURCE)
                .setProviderTransactionId("SIGNED_VERIFIED".equals(session.getRewardVerificationStatus())
                        ? session.getProviderTransactionId() : null)
                .setProviderShowId(session.getProviderShowId()).setSdkRequestId(session.getSdkRequestId())
                .setAdsourceId(callback.getAdsourceId()).setSourceAmountUnits(money.sourceEcpmUnits)
                .setEstimatedAmountUnits(money.estimatedUnits).setReconciledAmountUnits(0L)
                .setAmountScale(money.scale).setSourceCurrency(callback.getCurrency())
                .setMatchStatus("MATCHED").setSourceVerificationStatus("UNSIGNED_OBSERVATION")
                .setRewardQualificationStatus(qualification)
                .setReconciliationStatus(reconciliationStatus)
                .setPayloadHash(inbox.getCanonicalPayloadHash()).setVersion(0).setLegacyUnverified(false);
        row.setTenantId(inbox.getTenantId());
        return row;
    }

    private SkitPolicySnapshotService.PolicySnapshot requireSessionSnapshot(SkitAdSessionDO session) {
        SkitPolicySnapshotService.PolicySnapshot snapshot =
                snapshotService.getRequired(session.getPolicySnapshotId());
        if (snapshot == null || !Objects.equals(snapshot.getId(), session.getPolicySnapshotId())
                || !Objects.equals(snapshot.getTenantId(), session.getTenantId())
                || !Objects.equals(snapshot.getSourceMemberId(), session.getMemberId())
                || snapshot.getRuleVersion() == null || snapshot.getRuleVersion() <= 0) {
            throw new IllegalStateException("Policy snapshot escaped the locked session envelope");
        }
        return snapshot;
    }

    private void validateExistingRevenue(SkitAdRevenueEventDO row, SkitAdCallbackInboxDO inbox,
                                         SkitAdSessionDO session, TakuImpressionCallback callback,
                                         ImpressionMoney money, String qualification,
                                         String reconciliationStatus, Integer expectedRuleVersion) {
        validateRevenueSessionEnvelope(row, session);
        if (!Objects.equals(row.getCallbackInboxId(), inbox.getId())
                || !Objects.equals(row.getSourceType(), IMPRESSION_SOURCE)
                || !Objects.equals(row.getExternalEventId(), inbox.getIdempotencyKey())
                || !Objects.equals(row.getAdsourceId(), callback.getAdsourceId())
                || !Objects.equals(row.getSourceCurrency(), callback.getCurrency())
                || !Objects.equals(row.getSourceAmountUnits(), money.sourceEcpmUnits)
                || !Objects.equals(row.getEstimatedAmountUnits(), money.estimatedUnits)
                || !Objects.equals(row.getAmountScale(), money.scale)
                || !Objects.equals(row.getRewardQualificationStatus(), qualification)
                || ("SIGNED_VERIFIED".equals(session.getRewardVerificationStatus())
                && !Objects.equals(row.getProviderTransactionId(),
                session.getProviderTransactionId()))
                || (session.getProviderShowId() != null
                && !Objects.equals(row.getProviderShowId(), session.getProviderShowId()))
                || !Objects.equals(row.getRuleVersion(), expectedRuleVersion)
                || !"MATCHED".equals(row.getMatchStatus())
                || !"UNSIGNED_OBSERVATION".equals(row.getSourceVerificationStatus())
                || !Objects.equals(row.getReconciliationStatus(), reconciliationStatus)
                || Boolean.TRUE.equals(row.getLegacyUnverified())
                || !sameHash(row.getPayloadHash(), inbox.getCanonicalPayloadHash())) {
            throw new IllegalStateException("Existing impression event conflicts with canonical inbox evidence");
        }
    }

    private void validateRevenueSessionEnvelope(SkitAdRevenueEventDO row, SkitAdSessionDO session) {
        if (row == null || !Objects.equals(row.getTenantId(), session.getTenantId())
                || !Objects.equals(row.getAdAccountId(), session.getAdAccountId())
                || !Objects.equals(row.getAdSessionId(), session.getId())
                || !Objects.equals(row.getSourceMemberId(), session.getMemberId())
                || !Objects.equals(row.getPolicySnapshotId(), session.getPolicySnapshotId())
                || row.getRuleVersion() == null || row.getRuleVersion() <= 0
                || row.getVersion() == null) {
            throw new IllegalStateException("Revenue event escaped the session tenant/account snapshot envelope");
        }
    }

    private void validateEntitlement(SkitContentEntitlementDO row, SkitAdSessionDO session,
                                     List<Integer> episodes) {
        if (row == null || !Objects.equals(row.getTenantId(), session.getTenantId())
                || !Objects.equals(row.getMemberId(), session.getMemberId())
                || !Objects.equals(row.getDramaId(), session.getDramaId())
                || row.getEpisodeNo() == null || !episodes.contains(row.getEpisodeNo())
                || row.getId() == null || row.getId() <= 0) {
            throw new IllegalStateException("Entitlement escaped the session content envelope");
        }
    }

    private void validateExistingGrant(SkitEntitlementGrantDO row, SkitAdSessionDO session,
                                       SkitContentEntitlementDO entitlement, Integer episode,
                                       String transactionId) {
        if (!Objects.equals(row.getTenantId(), session.getTenantId())
                || !Objects.equals(row.getAdSessionId(), session.getId())
                || !Objects.equals(row.getEntitlementId(), entitlement.getId())
                || !Objects.equals(row.getMemberId(), session.getMemberId())
                || !Objects.equals(row.getDramaId(), session.getDramaId())
                || !Objects.equals(row.getEpisodeNo(), episode)
                || !Objects.equals(row.getProviderTransactionId(), transactionId)
                || !("CREATED".equals(row.getGrantResult())
                || "ALREADY_OWNED".equals(row.getGrantResult()))) {
            throw new IllegalStateException("Existing grant conflicts with signed reward evidence");
        }
    }

    private ProcessResult rejectReward(SkitAdCallbackInboxDO inbox, SkitAdSessionDO session,
                                       String errorCode, LocalDateTime processedAt) {
        return rejectReward(inbox, session, errorCode, processedAt, true);
    }

    private ProcessResult rejectRewardPreservingImpression(
            SkitAdCallbackInboxDO inbox, SkitAdSessionDO session,
            String errorCode, LocalDateTime processedAt) {
        return rejectReward(inbox, session, errorCode, processedAt, false);
    }

    private ProcessResult rejectReward(SkitAdCallbackInboxDO inbox, SkitAdSessionDO session,
                                       String errorCode, LocalDateTime processedAt,
                                       boolean convergeImpression) {
        if (session != null && "PENDING".equals(session.getRewardVerificationStatus())
                && "NONE".equals(session.getEntitlementStatus())
                && Objects.equals(session.getRewardCallbackInboxId(), inbox.getId())
                && Objects.equals(session.getRewardCallbackReceivedAt(), inbox.getReceivedAt())
                && session.getVersion() != null && session.getActiveScopeHash() != null
                && session.getActiveScopeReleasedAt() == null
                && session.getActiveScopeReleaseReason() == null) {
            int rejected = sessionMapper.markRewardReceiptRejectedCas(inbox.getTenantId(), session.getId(),
                    inbox.getAdAccountId(), inbox.getId(), inbox.getReceivedAt(), session.getVersion(),
                    processedAt, errorCode);
            if (rejected != 1) {
                throw new IllegalStateException("Reward session changed while rejecting callback evidence");
            }
            if (convergeImpression) {
                convergeRejectedEstimate(session, processedAt);
            }
        }
        return rejectInbox(inbox, errorCode);
    }

    private void convergeRejectedEstimate(SkitAdSessionDO session, LocalDateTime rejectedAt) {
        SkitAdRevenueEventDO event = revenueMapper.selectByTenantSessionAndSourceForUpdate(
                session.getTenantId(), session.getId(), IMPRESSION_SOURCE);
        if (event == null) {
            return;
        }
        validateRevenueSessionEnvelope(event, session);
        if ("PENDING_REWARD".equals(event.getRewardQualificationStatus())
                && "FROZEN".equals(event.getReconciliationStatus())) {
            int updated = revenueMapper.markNonRewardedSuspenseCas(session.getTenantId(), event.getId(),
                    session.getId(), session.getAdAccountId(), event.getVersion(), rejectedAt);
            if (updated != 1) {
                throw new IllegalStateException("Frozen impression changed during reward rejection");
            }
            return;
        }
        if (!"NON_REWARDED".equals(event.getRewardQualificationStatus())
                || !"SUSPENSE".equals(event.getReconciliationStatus())) {
            throw new IllegalStateException("Impression cannot converge to rejected reward suspense");
        }
    }

    private ProcessResult rejectInbox(SkitAdCallbackInboxDO inbox, String errorCode) {
        if (inboxMapper.markRejectedCas(inbox.getTenantId(), inbox.getAdAccountId(), inbox.getId(),
                inbox.getLeaseOwner(), errorCode) != 1) {
            throw new IllegalStateException("Callback inbox lease changed before deterministic rejection");
        }
        return ProcessResult.rejected(errorCode);
    }

    private void finishSucceeded(SkitAdCallbackInboxDO inbox) {
        if (inboxMapper.markSucceededCas(inbox.getTenantId(), inbox.getAdAccountId(), inbox.getId(),
                inbox.getLeaseOwner()) != 1) {
            throw new IllegalStateException("Callback inbox lease changed before successful commit");
        }
    }

    private String decryptRawQuery(SkitAdCallbackInboxDO inbox) {
        if (inbox.getPayloadCiphertext() == null || inbox.getPayloadNonce() == null
                || inbox.getPayloadKeyId() == null || inbox.getPayloadEnvelopeVersion() == null
                || inbox.getCanonicalPayloadHash() == null) {
            throw new IllegalStateException("Callback payload envelope is unavailable");
        }
        SkitCallbackPayloadCryptoService.Context context =
                SkitCallbackPayloadCryptoService.Context.callbackPayload(inbox.getTenantId(),
                        inbox.getAdAccountId(), inbox.getCallbackType(), inbox.getIdempotencyKey(),
                        inbox.getCanonicalPayloadHash(), inbox.getPayloadEnvelopeVersion());
        SkitCallbackPayloadCryptoService.PayloadEnvelope envelope =
                new SkitCallbackPayloadCryptoService.PayloadEnvelope(inbox.getPayloadCiphertext(),
                        inbox.getPayloadNonce(), inbox.getPayloadKeyId(),
                        inbox.getPayloadEnvelopeVersion());
        byte[] plaintext = payloadCrypto.decrypt(context, envelope);
        try {
            return new String(plaintext, StandardCharsets.US_ASCII);
        } finally {
            Arrays.fill(plaintext, (byte) 0);
        }
    }

    private void validateClaim(long tenantId, long adAccountId, long callbackInboxId,
                               String leaseOwner) {
        if (tenantId <= 0 || adAccountId <= 0 || callbackInboxId <= 0
                || leaseOwner == null || leaseOwner.isEmpty() || leaseOwner.length() > 64) {
            throw new IllegalArgumentException("Callback processor claim is invalid");
        }
        Long currentTenant = TenantContextHolder.getRequiredTenantId();
        if (!Objects.equals(currentTenant, tenantId) || TenantContextHolder.isIgnore()) {
            throw new IllegalStateException("Callback processor is outside its derived tenant context");
        }
    }

    private void validateInboxClaim(SkitAdCallbackInboxDO inbox, long tenantId, long adAccountId,
                                    long callbackInboxId, String leaseOwner) {
        if (inbox == null || !Objects.equals(inbox.getTenantId(), tenantId)
                || !Objects.equals(inbox.getAdAccountId(), adAccountId)
                || !Objects.equals(inbox.getId(), callbackInboxId)) {
            throw new IllegalStateException("Callback inbox escaped its claimed tenant/account envelope");
        }
        if (!"PROCESSING".equals(inbox.getProcessingStatus())
                || !Objects.equals(inbox.getLeaseOwner(), leaseOwner)
                || inbox.getLeaseUntil() == null) {
            throw new IllegalStateException("Callback inbox is not owned by the active processor lease");
        }
    }

    private static boolean hardAllowedRewardNetwork(int networkFirmId) {
        return networkFirmId == 35 || networkFirmId == 66 || networkFirmId == 67;
    }

    private static List<Integer> episodeRange(SkitAdSessionDO session) {
        Integer first = session.getEpisodeFrom();
        Integer last = session.getEpisodeTo();
        if (first == null || last == null || first <= 0 || last < first || last - first > 100) {
            throw new IllegalStateException("Session episode range is invalid");
        }
        List<Integer> episodes = new ArrayList<>(last - first + 1);
        for (int episode = first; episode <= last; episode++) {
            episodes.add(episode);
        }
        return episodes;
    }

    private static String rewardQualification(SkitAdSessionDO session) {
        String rewardStatus = session.getRewardVerificationStatus();
        if ("SIGNED_VERIFIED".equals(rewardStatus)) {
            return "REWARDED";
        }
        if ("PENDING".equals(rewardStatus)) {
            return "PENDING_REWARD";
        }
        if ("REJECTED".equals(rewardStatus) || "VERIFY_TIMEOUT".equals(rewardStatus)) {
            return "NON_REWARDED";
        }
        throw new IllegalStateException("Unknown reward verification state");
    }

    private static String sessionRevenueStatus(SkitAdSessionDO session) {
        if ("SECURITY_REVOKED".equals(session.getEntitlementStatus())) {
            return "SUSPENSE";
        }
        String rewardStatus = session.getRewardVerificationStatus();
        if ("SIGNED_VERIFIED".equals(rewardStatus)) {
            return "FROZEN";
        }
        if ("PENDING".equals(rewardStatus)) {
            return "IMPRESSION_PENDING_REWARD";
        }
        if ("VERIFY_TIMEOUT".equals(rewardStatus)) {
            return "FROZEN";
        }
        if ("REJECTED".equals(rewardStatus)) {
            return "SUSPENSE";
        }
        throw new IllegalStateException("Unknown reward verification state");
    }

    private static String impressionReconciliationStatus(SkitAdSessionDO session) {
        if ("SECURITY_REVOKED".equals(session.getEntitlementStatus())) {
            return "SUSPENSE";
        }
        String rewardStatus = session.getRewardVerificationStatus();
        if ("REJECTED".equals(rewardStatus)) {
            return "SUSPENSE";
        }
        if ("PENDING".equals(rewardStatus) || "SIGNED_VERIFIED".equals(rewardStatus)
                || "VERIFY_TIMEOUT".equals(rewardStatus)) {
            return "FROZEN";
        }
        throw new IllegalStateException("Unknown reward verification state");
    }

    private static boolean sameHash(byte[] first, byte[] second) {
        return first != null && second != null && MessageDigest.isEqual(first, second);
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock).withNano(0);
    }

    private static final class LockedImpressionAuthority {

        private final SkitAdRevenueEventDO event;
        private final SkitAdCallbackInboxDO inbox;

        private LockedImpressionAuthority(SkitAdRevenueEventDO event,
                                          SkitAdCallbackInboxDO inbox) {
            this.event = event;
            this.inbox = inbox;
        }
    }

    private static final class ImpressionMoney {

        private final long sourceEcpmUnits;
        private final long estimatedUnits;
        private final int scale;
        private final BigDecimal estimatedDecimal;

        private ImpressionMoney(long sourceEcpmUnits, long estimatedUnits,
                                int scale, BigDecimal estimatedDecimal) {
            this.sourceEcpmUnits = sourceEcpmUnits;
            this.estimatedUnits = estimatedUnits;
            this.scale = scale;
            this.estimatedDecimal = estimatedDecimal;
        }

        private static ImpressionMoney fromEcpm(String lexicalEcpm) {
            BigDecimal estimate = new BigDecimal(lexicalEcpm).movePointLeft(3).stripTrailingZeros();
            if (estimate.scale() < 0) {
                estimate = estimate.setScale(0);
            }
            if (estimate.signum() < 0 || estimate.scale() > 18) {
                throw new ArithmeticException("impression estimate scale is unsupported");
            }
            BigInteger exactUnits = estimate.unscaledValue();
            long estimatedUnits = exactUnits.longValueExact();
            long sourceUnits = Math.multiplyExact(estimatedUnits, 1000L);
            return new ImpressionMoney(sourceUnits, estimatedUnits, estimate.scale(), estimate);
        }
    }
}
