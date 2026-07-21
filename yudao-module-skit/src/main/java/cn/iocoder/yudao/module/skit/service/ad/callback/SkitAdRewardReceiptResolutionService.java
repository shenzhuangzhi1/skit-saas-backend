package cn.iocoder.yudao.module.skit.service.ad.callback;

import cn.iocoder.yudao.module.skit.dal.dataobject.ad.SkitAdCallbackInboxDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.ad.SkitAdSessionDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.revenue.SkitAdRevenueEventDO;
import cn.iocoder.yudao.module.skit.dal.mysql.ad.SkitAdCallbackInboxMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.ad.SkitAdSessionMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.revenue.SkitAdRevenueEventMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * Converges a permanently failed, authenticated reward receipt into the same
 * episode-scoped rejection used by the callback processor.
 *
 * <p>The caller must already hold the session row lock and own the surrounding
 * transaction. The inbox read intentionally stays non-locking: callback workers
 * lock inbox then session, while this compensation path locks session first.
 * Terminal inbox states are monotonic and the session CAS rechecks the terminal
 * inbox row before releasing the active episode scope.</p>
 */
@Service
public class SkitAdRewardReceiptResolutionService {

    private static final String PROVIDER = "TAKU";
    private static final String REWARD = "REWARD";
    private static final String IMPRESSION_SOURCE = "TAKU_IMPRESSION";
    private static final String SIGNED_REWARD = "SIGNED_REWARD";
    private static final long SIGNED_REWARD_FIELD_MASK = 0x3fL;
    private static final String DEAD_LETTER_FAILURE = "CALLBACK_DEAD_LETTER";
    private static final String PAYLOAD_CONFLICT_FAILURE = "CALLBACK_PAYLOAD_CONFLICT";
    private static final String REJECTED_FALLBACK = "CALLBACK_REJECTED";

    private final SkitAdCallbackInboxMapper inboxMapper;
    private final SkitAdSessionMapper sessionMapper;
    private final SkitAdRevenueEventMapper revenueMapper;

    public SkitAdRewardReceiptResolutionService(
            SkitAdCallbackInboxMapper inboxMapper,
            SkitAdSessionMapper sessionMapper,
            SkitAdRevenueEventMapper revenueMapper) {
        this.inboxMapper = Objects.requireNonNull(inboxMapper, "inboxMapper");
        this.sessionMapper = Objects.requireNonNull(sessionMapper, "sessionMapper");
        this.revenueMapper = Objects.requireNonNull(revenueMapper, "revenueMapper");
    }

    /**
     * @return {@code true} only when this call changed the pending session to REJECTED
     */
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public boolean resolveTerminalReceipt(SkitAdSessionDO session, LocalDateTime resolvedAt) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(resolvedAt, "resolvedAt");
        boolean hasInboxId = session.getRewardCallbackInboxId() != null;
        boolean hasReceivedAt = session.getRewardCallbackReceivedAt() != null;
        if (hasInboxId != hasReceivedAt) {
            throw new IllegalStateException("Reward callback receipt binding is partial");
        }
        if (!hasInboxId) {
            return false;
        }
        if (isAuthorized(session) || isIrreversibleFailure(session)) {
            return false;
        }
        if (!"PENDING".equals(session.getRewardVerificationStatus())) {
            throw new IllegalStateException("Reward receipt is bound to an unsupported verification state");
        }

        SkitAdCallbackInboxDO inbox = inboxMapper.selectByTenantAccountAndId(
                session.getTenantId(), session.getAdAccountId(), session.getRewardCallbackInboxId());
        validateInboxRoute(session, inbox);
        String inboxStatus = inbox.getProcessingStatus();
        if ("PENDING".equals(inboxStatus) || "PROCESSING".equals(inboxStatus)
                || "RETRY_WAIT".equals(inboxStatus) || "SUCCEEDED".equals(inboxStatus)) {
            return false;
        }
        String failureReason;
        if ("DEAD_LETTER".equals(inboxStatus)) {
            validateDeadLetterAuthority(session, inbox);
            failureReason = "PAYLOAD_CONFLICT".equals(inbox.getDeliveryIntegrityStatus())
                    ? PAYLOAD_CONFLICT_FAILURE : DEAD_LETTER_FAILURE;
        } else if ("REJECTED".equals(inboxStatus)) {
            failureReason = safeRejectedFailure(inbox.getErrorCode());
        } else {
            throw new IllegalStateException("Reward callback inbox has an unsupported processing state");
        }

        validatePendingSessionEnvelope(session);
        SkitAdRevenueEventDO event = lockAndValidateImpression(session);
        LocalDateTime rejectedAt = resolvedAt.withNano(0);
        int changed = sessionMapper.markTerminalRewardReceiptRejectedAndReleaseScopeCas(
                session.getTenantId(), session.getId(), session.getAdAccountId(), session.getMemberId(),
                inbox.getId(), inbox.getReceivedAt(), session.getCallbackKeyVersion(),
                session.getRewardSecretVersion(), session.getDramaId(), session.getEpisodeFrom(),
                session.getActiveScopeHash(), session.getVersion(), inboxStatus, failureReason, rejectedAt);
        if (changed != 1) {
            throw new IllegalStateException(
                    "Terminal reward receipt changed before episode scope compensation");
        }
        if (event != null && "PENDING_REWARD".equals(event.getRewardQualificationStatus())) {
            int eventChanged = revenueMapper.markNonRewardedSuspenseCas(
                    session.getTenantId(), event.getId(), session.getId(), session.getAdAccountId(),
                    event.getVersion(), rejectedAt);
            if (eventChanged != 1) {
                throw new IllegalStateException(
                        "Frozen impression changed during terminal reward compensation");
            }
            event.setRewardQualificationStatus("NON_REWARDED")
                    .setReconciliationStatus("SUSPENSE")
                    .setVersion(event.getVersion() + 1);
        }
        session.setRewardVerificationStatus("REJECTED")
                .setRevenueStatus(event == null ? session.getRevenueStatus() : "SUSPENSE")
                .setActiveScopeHash(null)
                .setActiveScopeReleasedAt(rejectedAt)
                .setActiveScopeReleaseReason("REWARD_REJECTED")
                .setFailureReason(failureReason)
                .setVersion(session.getVersion() + 1);
        return true;
    }

    private static boolean isAuthorized(SkitAdSessionDO session) {
        return "SIGNED_VERIFIED".equals(session.getRewardVerificationStatus())
                || "GRANTED".equals(session.getEntitlementStatus())
                || session.getProviderTransactionId() != null;
    }

    private static boolean isIrreversibleFailure(SkitAdSessionDO session) {
        return "REJECTED".equals(session.getRewardVerificationStatus())
                || "VERIFY_TIMEOUT".equals(session.getRewardVerificationStatus());
    }

    private static void validateInboxRoute(SkitAdSessionDO session,
                                           SkitAdCallbackInboxDO inbox) {
        if (inbox == null
                || !Objects.equals(session.getTenantId(), inbox.getTenantId())
                || !Objects.equals(session.getAdAccountId(), inbox.getAdAccountId())
                || !Objects.equals(session.getRewardCallbackInboxId(), inbox.getId())
                || !Objects.equals(session.getId(), inbox.getAdSessionId())
                || !Objects.equals(session.getRewardCallbackReceivedAt(), inbox.getReceivedAt())
                || !REWARD.equals(inbox.getCallbackType())) {
            throw new IllegalStateException("Reward callback inbox escaped its session route envelope");
        }
    }

    private static void validateDeadLetterAuthority(SkitAdSessionDO session,
                                                    SkitAdCallbackInboxDO inbox) {
        boolean supportedDeliveryIntegrity = "CANONICAL".equals(inbox.getDeliveryIntegrityStatus())
                || "PAYLOAD_CONFLICT".equals(inbox.getDeliveryIntegrityStatus());
        if (!PROVIDER.equals(session.getProvider()) || !PROVIDER.equals(inbox.getProvider())
                || !SIGNED_REWARD.equals(inbox.getAuthenticationLevel())
                || !"VALID".equals(inbox.getSignatureStatus())
                || !"SIGNED_ILRD".equals(inbox.getEvidenceProvenance())
                || !Objects.equals(inbox.getSignedFieldMask(), SIGNED_REWARD_FIELD_MASK)
                || !supportedDeliveryIntegrity) {
            throw new IllegalStateException("Dead-letter reward callback provenance is invalid");
        }
        if (!Objects.equals(session.getCallbackKeyVersion(), inbox.getCallbackKeyVersion())
                || !Objects.equals(session.getRewardSecretVersion(), inbox.getRewardSecretVersion())) {
            throw new IllegalStateException("Dead-letter reward callback credential versions changed");
        }
        if (inbox.getReceivedAt() == null || session.getRewardAcceptUntil() == null
                || inbox.getReceivedAt().isAfter(session.getRewardAcceptUntil())) {
            throw new IllegalStateException("Dead-letter reward callback missed its signed receipt deadline");
        }
    }

    private static void validatePendingSessionEnvelope(SkitAdSessionDO session) {
        boolean supportedRevenue = "NONE".equals(session.getRevenueStatus())
                || "IMPRESSION_PENDING_REWARD".equals(session.getRevenueStatus());
        if (session.getTenantId() == null || session.getTenantId() <= 0
                || session.getId() == null || session.getId() <= 0
                || session.getAdAccountId() == null || session.getAdAccountId() <= 0
                || session.getMemberId() == null || session.getMemberId() <= 0
                || session.getPolicySnapshotId() == null || session.getPolicySnapshotId() <= 0
                || session.getCallbackKeyVersion() == null || session.getCallbackKeyVersion() <= 0
                || session.getRewardSecretVersion() == null || session.getRewardSecretVersion() <= 0
                || session.getVersion() == null || session.getVersion() < 0
                || !"PENDING".equals(session.getRewardVerificationStatus())
                || !"NONE".equals(session.getEntitlementStatus())
                || !supportedRevenue || session.getProviderTransactionId() != null
                || session.getFailureReason() != null
                || session.getDramaId() == null || session.getDramaId() <= 0
                || session.getEpisodeFrom() == null || session.getEpisodeFrom() <= 0
                || !Objects.equals(session.getEpisodeFrom(), session.getEpisodeTo())
                || session.getActiveScopeHash() == null
                || session.getActiveScopeReleasedAt() != null
                || session.getActiveScopeReleaseReason() != null) {
            throw new IllegalStateException(
                    "Terminal reward compensation escaped its pending episode scope");
        }
    }

    private SkitAdRevenueEventDO lockAndValidateImpression(SkitAdSessionDO session) {
        SkitAdRevenueEventDO event = revenueMapper.selectByTenantSessionAndSourceForUpdate(
                session.getTenantId(), session.getId(), IMPRESSION_SOURCE);
        if ("NONE".equals(session.getRevenueStatus())) {
            if (event != null) {
                throw new IllegalStateException(
                        "Reward session revenue state omits its existing impression fact");
            }
            return null;
        }
        if (event == null
                || !Objects.equals(session.getTenantId(), event.getTenantId())
                || !Objects.equals(session.getAdAccountId(), event.getAdAccountId())
                || !Objects.equals(session.getId(), event.getAdSessionId())
                || !Objects.equals(session.getMemberId(), event.getSourceMemberId())
                || !Objects.equals(session.getPolicySnapshotId(), event.getPolicySnapshotId())
                || event.getId() == null || event.getId() <= 0
                || event.getVersion() == null || event.getVersion() < 0
                || !IMPRESSION_SOURCE.equals(event.getSourceType())
                || !"UNSIGNED_OBSERVATION".equals(event.getSourceVerificationStatus())
                || !Boolean.FALSE.equals(event.getLegacyUnverified())) {
            throw new IllegalStateException(
                    "Terminal reward impression escaped its immutable session envelope");
        }
        boolean pending = "PENDING_REWARD".equals(event.getRewardQualificationStatus())
                && "FROZEN".equals(event.getReconciliationStatus());
        boolean alreadyConverged = "NON_REWARDED".equals(event.getRewardQualificationStatus())
                && "SUSPENSE".equals(event.getReconciliationStatus());
        if (!pending && !alreadyConverged) {
            throw new IllegalStateException(
                    "Terminal reward impression cannot converge to non-rewarded suspense");
        }
        return event;
    }

    private static String safeRejectedFailure(String errorCode) {
        if (errorCode == null || !errorCode.matches("[A-Z0-9_]{1,64}")) {
            return REJECTED_FALLBACK;
        }
        return errorCode;
    }
}
