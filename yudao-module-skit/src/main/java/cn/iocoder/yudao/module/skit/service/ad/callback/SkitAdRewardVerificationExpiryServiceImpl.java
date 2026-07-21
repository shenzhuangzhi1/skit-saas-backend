package cn.iocoder.yudao.module.skit.service.ad.callback;

import cn.iocoder.yudao.framework.tenant.core.util.TenantUtils;
import cn.iocoder.yudao.module.skit.dal.dataobject.ad.SkitAdRewardExpiryClaimDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.ad.SkitAdSessionDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.revenue.SkitAdRevenueEventDO;
import cn.iocoder.yudao.module.skit.dal.mysql.ad.SkitAdSessionMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.revenue.SkitAdRevenueEventMapper;
import cn.iocoder.yudao.module.skit.service.commission.SkitFrozenCommissionProjectionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
@Slf4j
public class SkitAdRewardVerificationExpiryServiceImpl
        implements SkitAdRewardVerificationExpiryService {

    private static final String IMPRESSION_SOURCE = "TAKU_IMPRESSION";

    private final SkitAdSessionMapper sessionMapper;
    private final SkitAdRevenueEventMapper eventMapper;
    private final SkitAdRewardReceiptResolutionService rewardReceiptResolutionService;
    private final SkitFrozenCommissionProjectionService projectionService;
    private final TransactionTemplate transactionTemplate;
    private final int batchSize;

    @Autowired
    public SkitAdRewardVerificationExpiryServiceImpl(
            SkitAdSessionMapper sessionMapper,
            SkitAdRevenueEventMapper eventMapper,
            SkitAdRewardReceiptResolutionService rewardReceiptResolutionService,
            SkitFrozenCommissionProjectionService projectionService,
            PlatformTransactionManager transactionManager,
            @Value("${skit.ad.reward-expiry.batch-size:100}") int batchSize) {
        this.sessionMapper = Objects.requireNonNull(sessionMapper, "sessionMapper");
        this.eventMapper = Objects.requireNonNull(eventMapper, "eventMapper");
        this.rewardReceiptResolutionService = Objects.requireNonNull(
                rewardReceiptResolutionService, "rewardReceiptResolutionService");
        this.projectionService = Objects.requireNonNull(projectionService, "projectionService");
        this.transactionTemplate = new TransactionTemplate(
                Objects.requireNonNull(transactionManager, "transactionManager"));
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.transactionTemplate.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        if (batchSize < 1 || batchSize > 500) {
            throw new IllegalArgumentException("batchSize must be between 1 and 500");
        }
        this.batchSize = batchSize;
    }

    @Override
    public int sweepOnce() {
        List<SkitAdRewardExpiryClaimDO> claims = TenantUtils.executeIgnore(() ->
                transactionTemplate.execute(status -> sessionMapper.selectExpiredRewardClaims(batchSize)));
        if (claims == null) {
            throw new IllegalStateException("Reward expiry claim projection returned null");
        }
        int expired = 0;
        for (SkitAdRewardExpiryClaimDO claim : claims) {
            try {
                validateClaim(claim);
                AtomicBoolean changed = new AtomicBoolean();
                TenantUtils.execute(claim.getTenantId(), (Runnable) () -> changed.set(Boolean.TRUE.equals(
                        transactionTemplate.execute(status -> expireInsideTenant(claim)))));
                if (changed.get()) {
                    expired++;
                }
            } catch (RuntimeException ex) {
                log.error("[sweepOnce][reward expiry claim failed; continuing] "
                                + "tenantId={}, adAccountId={}, sessionId={}, exceptionType={}",
                        claim == null ? null : claim.getTenantId(),
                        claim == null ? null : claim.getAdAccountId(),
                        claim == null ? null : claim.getId(),
                        ex.getClass().getName());
            }
        }
        return expired;
    }

    private boolean expireInsideTenant(SkitAdRewardExpiryClaimDO claim) {
        SkitAdSessionDO session = sessionMapper.selectByTenantAccountAndIdForUpdate(
                claim.getTenantId(), claim.getAdAccountId(), claim.getId());
        if (session == null) {
            return false;
        }
        validateSessionRoute(claim, session);
        if (!"PENDING".equals(session.getRewardVerificationStatus())) {
            if ("SIGNED_VERIFIED".equals(session.getRewardVerificationStatus())
                    || "REJECTED".equals(session.getRewardVerificationStatus())
                    || "VERIFY_TIMEOUT".equals(session.getRewardVerificationStatus())) {
                return false;
            }
            throw new IllegalStateException("Reward verification state is invalid");
        }
        if (session.getRewardCallbackInboxId() != null
                || session.getRewardCallbackReceivedAt() != null) {
            if (session.getRewardCallbackInboxId() == null
                    || session.getRewardCallbackReceivedAt() == null) {
                throw new IllegalStateException("Reward callback receipt is partially bound");
            }
            LocalDateTime authoritativeNow = sessionMapper.selectDatabaseNow();
            if (authoritativeNow == null) {
                throw new IllegalStateException(
                        "Database time is unavailable for reward receipt compensation");
            }
            return rewardReceiptResolutionService.resolveTerminalReceipt(
                    session, authoritativeNow.withNano(0));
        }
        if (session.getActiveScopeHash() == null
                || session.getActiveScopeReleasedAt() != null
                || session.getActiveScopeReleaseReason() != null) {
            throw new IllegalStateException("Pending reward session has no active scope");
        }

        SkitAdRevenueEventDO event = eventMapper.selectByTenantSessionAndSourceForUpdate(
                claim.getTenantId(), claim.getId(), IMPRESSION_SOURCE);
        if (event == null) {
            if (!"NONE".equals(session.getRevenueStatus())) {
                throw new IllegalStateException("Reward session revenue exists without an impression fact");
            }
            return updateSessionTimeout(session, "NONE", "NONE");
        }
        validatePendingImpression(session, event);
        int eventChanged = eventMapper.markNonRewardedFrozenOnTimeoutCas(
                session.getTenantId(), event.getId(), session.getId(),
                session.getAdAccountId(), event.getVersion());
        if (eventChanged != 1) {
            throw new IllegalStateException("Impression timeout CAS did not change exactly one row");
        }
        if (!updateSessionTimeout(session, "IMPRESSION_PENDING_REWARD", "FROZEN")) {
            throw new IllegalStateException("Reward timeout CAS lost after locking its session");
        }
        event.setRewardQualificationStatus("NON_REWARDED").setVersion(event.getVersion() + 1);
        projectionService.projectNonRewardedEstimate(event);
        return true;
    }

    private boolean updateSessionTimeout(SkitAdSessionDO session,
                                         String expectedRevenueStatus,
                                         String nextRevenueStatus) {
        int changed = sessionMapper.markRewardVerifyTimeoutByAccountCas(
                session.getTenantId(), session.getId(), session.getAdAccountId(),
                session.getMemberId(), session.getVersion(), expectedRevenueStatus, nextRevenueStatus);
        if (changed != 0 && changed != 1) {
            throw new IllegalStateException("Reward timeout CAS changed multiple rows");
        }
        return changed == 1;
    }

    private static void validateClaim(SkitAdRewardExpiryClaimDO claim) {
        if (claim == null || claim.getTenantId() == null || claim.getTenantId() <= 0
                || claim.getAdAccountId() == null || claim.getAdAccountId() <= 0
                || claim.getId() == null || claim.getId() <= 0) {
            throw new IllegalStateException("Reward expiry route projection is invalid");
        }
    }

    private static void validateSessionRoute(SkitAdRewardExpiryClaimDO claim, SkitAdSessionDO session) {
        if (!Objects.equals(claim.getTenantId(), session.getTenantId())
                || !Objects.equals(claim.getAdAccountId(), session.getAdAccountId())
                || !Objects.equals(claim.getId(), session.getId())
                || session.getMemberId() == null || session.getMemberId() <= 0
                || session.getPolicySnapshotId() == null || session.getPolicySnapshotId() <= 0
                || session.getVersion() == null || session.getVersion() < 0) {
            throw new IllegalStateException("Reward expiry session escaped its route envelope");
        }
    }

    private static void validatePendingImpression(SkitAdSessionDO session,
                                                  SkitAdRevenueEventDO event) {
        if (!"IMPRESSION_PENDING_REWARD".equals(session.getRevenueStatus())
                || !Objects.equals(session.getTenantId(), event.getTenantId())
                || !Objects.equals(session.getAdAccountId(), event.getAdAccountId())
                || !Objects.equals(session.getId(), event.getAdSessionId())
                || !Objects.equals(session.getMemberId(), event.getSourceMemberId())
                || !Objects.equals(session.getPolicySnapshotId(), event.getPolicySnapshotId())
                || event.getId() == null || event.getId() <= 0
                || event.getVersion() == null || event.getVersion() < 0
                || !IMPRESSION_SOURCE.equals(event.getSourceType())
                || !"PENDING_REWARD".equals(event.getRewardQualificationStatus())
                || !"UNSIGNED_OBSERVATION".equals(event.getSourceVerificationStatus())
                || !"FROZEN".equals(event.getReconciliationStatus())
                || !Boolean.FALSE.equals(event.getLegacyUnverified())) {
            throw new IllegalStateException("Pending impression escaped its immutable session envelope");
        }
    }
}
