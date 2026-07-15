package cn.iocoder.yudao.module.skit.service.ad.callback;

import cn.iocoder.yudao.framework.tenant.core.util.TenantUtils;
import cn.iocoder.yudao.module.skit.dal.dataobject.ad.SkitAdRetentionClaimDO;
import cn.iocoder.yudao.module.skit.dal.mysql.ad.SkitAdCallbackAttemptMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.ad.SkitAdCallbackEdgeAttemptMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.ad.SkitAdCallbackInboxMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Objects;

@Service
public class SkitAdCallbackEvidenceRetentionServiceImpl
        implements SkitAdCallbackEvidenceRetentionService {

    private final SkitAdCallbackInboxMapper inboxMapper;
    private final SkitAdCallbackAttemptMapper attemptMapper;
    private final SkitAdCallbackEdgeAttemptMapper edgeAttemptMapper;
    private final TransactionTemplate transactionTemplate;
    private final int batchSize;
    private final int retentionDays;

    @Autowired
    public SkitAdCallbackEvidenceRetentionServiceImpl(
            SkitAdCallbackInboxMapper inboxMapper,
            SkitAdCallbackAttemptMapper attemptMapper,
            SkitAdCallbackEdgeAttemptMapper edgeAttemptMapper,
            PlatformTransactionManager transactionManager,
            @Value("${skit.ad.callback.retention.batch-size:200}") int batchSize,
            @Value("${skit.ad.callback.retention.delivery-days:180}") int retentionDays) {
        this.inboxMapper = Objects.requireNonNull(inboxMapper, "inboxMapper");
        this.attemptMapper = Objects.requireNonNull(attemptMapper, "attemptMapper");
        this.edgeAttemptMapper = Objects.requireNonNull(edgeAttemptMapper, "edgeAttemptMapper");
        this.transactionTemplate = new TransactionTemplate(
                Objects.requireNonNull(transactionManager, "transactionManager"));
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.transactionTemplate.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        if (batchSize < 1 || batchSize > 1000) {
            throw new IllegalArgumentException("batchSize must be between 1 and 1000");
        }
        if (retentionDays < 180 || retentionDays > 3650) {
            throw new IllegalArgumentException("retentionDays must be between 180 and 3650");
        }
        this.batchSize = batchSize;
        this.retentionDays = retentionDays;
    }

    @Override
    public RetentionResult runOnce() {
        ClaimBatch claims = TenantUtils.executeIgnore(() -> transactionTemplate.execute(status ->
                new ClaimBatch(inboxMapper.selectExpiredTerminalPayloadClaims(batchSize),
                        attemptMapper.selectExpiredRetentionClaims(retentionDays, batchSize),
                        edgeAttemptMapper.selectExpiredRetentionClaims(retentionDays, batchSize))));
        if (claims == null || claims.payloads == null || claims.attempts == null || claims.edges == null) {
            throw new IllegalStateException("Callback retention projection returned null");
        }
        validateKnownRoutes(claims.payloads, "payload");
        validateKnownRoutes(claims.attempts, "attempt");
        validateEdgeRoutes(claims.edges);

        int payloads = 0;
        for (SkitAdRetentionClaimDO claim : claims.payloads) {
            Integer changed = TenantUtils.execute(claim.getTenantId(), () ->
                    transactionTemplate.execute(status -> inboxMapper.eraseExpiredTerminalPayloadCas(
                            claim.getTenantId(), claim.getAdAccountId(), claim.getId())));
            payloads += requireCasCount(changed, "payload erase");
        }
        int attempts = 0;
        for (SkitAdRetentionClaimDO claim : claims.attempts) {
            Integer changed = TenantUtils.execute(claim.getTenantId(), () ->
                    transactionTemplate.execute(status -> attemptMapper.deleteExpiredRetentionClaimCas(
                            claim.getTenantId(), claim.getAdAccountId(), claim.getId(), retentionDays)));
            attempts += requireCasCount(changed, "attempt delete");
        }
        int edges = 0;
        for (SkitAdRetentionClaimDO claim : claims.edges) {
            Integer changed;
            if (claim.getTenantId() == null) {
                changed = TenantUtils.executeIgnore(() -> transactionTemplate.execute(status ->
                        edgeAttemptMapper.deleteExpiredUnknownRouteClaimCas(
                                claim.getId(), retentionDays)));
            } else {
                changed = TenantUtils.execute(claim.getTenantId(), () ->
                        transactionTemplate.execute(status ->
                                edgeAttemptMapper.deleteExpiredKnownRouteClaimCas(
                                        claim.getTenantId(), claim.getAdAccountId(),
                                        claim.getId(), retentionDays)));
            }
            edges += requireCasCount(changed, "edge attempt delete");
        }
        return new RetentionResult(payloads, attempts, edges);
    }

    private static void validateKnownRoutes(List<SkitAdRetentionClaimDO> claims, String kind) {
        for (SkitAdRetentionClaimDO claim : claims) {
            if (claim == null || claim.getTenantId() == null || claim.getTenantId() <= 0
                    || claim.getAdAccountId() == null || claim.getAdAccountId() <= 0
                    || claim.getId() == null || claim.getId() <= 0) {
                throw new IllegalStateException("Callback " + kind + " retention route is invalid");
            }
        }
    }

    private static void validateEdgeRoutes(List<SkitAdRetentionClaimDO> claims) {
        for (SkitAdRetentionClaimDO claim : claims) {
            boolean anonymous = claim != null && claim.getTenantId() == null
                    && claim.getAdAccountId() == null;
            boolean known = claim != null && claim.getTenantId() != null
                    && claim.getTenantId() > 0 && claim.getAdAccountId() != null
                    && claim.getAdAccountId() > 0;
            if ((!anonymous && !known) || claim.getId() == null || claim.getId() <= 0) {
                throw new IllegalStateException("Callback edge retention route is invalid");
            }
        }
    }

    private static int requireCasCount(Integer changed, String operation) {
        if (changed == null || (changed != 0 && changed != 1)) {
            throw new IllegalStateException(operation + " CAS changed an invalid number of rows");
        }
        return changed;
    }

    private static final class ClaimBatch {
        private final List<SkitAdRetentionClaimDO> payloads;
        private final List<SkitAdRetentionClaimDO> attempts;
        private final List<SkitAdRetentionClaimDO> edges;

        private ClaimBatch(List<SkitAdRetentionClaimDO> payloads,
                           List<SkitAdRetentionClaimDO> attempts,
                           List<SkitAdRetentionClaimDO> edges) {
            this.payloads = payloads;
            this.attempts = attempts;
            this.edges = edges;
        }
    }
}
