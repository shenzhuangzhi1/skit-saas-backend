package cn.iocoder.yudao.module.skit.service.ad.callback;

import cn.iocoder.yudao.framework.tenant.core.util.TenantUtils;
import cn.iocoder.yudao.module.skit.dal.dataobject.provider.SkitProviderImpressionInboxDO;
import cn.iocoder.yudao.module.skit.dal.mysql.provider.SkitProviderImpressionInboxMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

@Service
@Slf4j
public class SkitProviderImpressionAttributionDrainServiceImpl
        implements SkitProviderImpressionAttributionDrainService {

    private static final String PROCESSOR_ERROR = "ATTRIBUTION_PROCESSOR_EXCEPTION";
    private static final String LEASE_EXHAUSTED = "ATTRIBUTION_LEASE_EXHAUSTED";

    private final SkitProviderImpressionInboxMapper inboxMapper;
    private final SkitProviderImpressionAttributionProcessor processor;
    private final TransactionTemplate transactions;
    private final Supplier<String> leaseOwnerSupplier;
    private final int batchSize;
    private final int leaseSeconds;
    private final int maxAttempts;
    private final int backoffSeconds;

    @Autowired
    public SkitProviderImpressionAttributionDrainServiceImpl(
            SkitProviderImpressionInboxMapper inboxMapper,
            SkitProviderImpressionAttributionProcessor processor,
            PlatformTransactionManager transactionManager,
            @Value("${skit.ad.provider-impression.attribution.batch-size:50}") int batchSize,
            @Value("${skit.ad.provider-impression.attribution.lease-seconds:120}") int leaseSeconds,
            @Value("${skit.ad.provider-impression.attribution.max-attempts:8}") int maxAttempts,
            @Value("${skit.ad.provider-impression.attribution.backoff-seconds:30}")
            int backoffSeconds) {
        this(inboxMapper, processor, transactionManager,
                SkitProviderImpressionAttributionDrainServiceImpl::newLeaseOwner,
                batchSize, leaseSeconds, maxAttempts, backoffSeconds);
    }

    SkitProviderImpressionAttributionDrainServiceImpl(
            SkitProviderImpressionInboxMapper inboxMapper,
            SkitProviderImpressionAttributionProcessor processor,
            PlatformTransactionManager transactionManager,
            Supplier<String> leaseOwnerSupplier,
            int batchSize, int leaseSeconds, int maxAttempts, int backoffSeconds) {
        this.inboxMapper = Objects.requireNonNull(inboxMapper, "inboxMapper");
        this.processor = Objects.requireNonNull(processor, "processor");
        this.transactions = new TransactionTemplate(
                Objects.requireNonNull(transactionManager, "transactionManager"));
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.leaseOwnerSupplier = Objects.requireNonNull(leaseOwnerSupplier, "leaseOwnerSupplier");
        this.batchSize = requireRange("batchSize", batchSize, 1, 500);
        this.leaseSeconds = requireRange("leaseSeconds", leaseSeconds, 1, 3600);
        this.maxAttempts = requireRange("maxAttempts", maxAttempts, 1, 30);
        this.backoffSeconds = requireRange("backoffSeconds", backoffSeconds, 1, 86400);
    }

    @Override
    public int drainOnce() {
        String leaseOwner = requireLeaseOwner(leaseOwnerSupplier.get());
        List<SkitProviderImpressionInboxDO> claims = claimBatch(leaseOwner);
        for (SkitProviderImpressionInboxDO claim : claims) {
            processClaim(claim, leaseOwner);
        }
        return claims.size();
    }

    private List<SkitProviderImpressionInboxDO> claimBatch(String leaseOwner) {
        return TenantUtils.executeIgnore(() -> transactions.execute(status -> {
            List<SkitProviderImpressionInboxDO> candidates =
                    inboxMapper.selectReadyAttributionClaimsForUpdate(batchSize);
            List<SkitProviderImpressionInboxDO> claimed = new ArrayList<>(candidates.size());
            for (SkitProviderImpressionInboxDO candidate : candidates) {
                validateClaim(candidate);
                int exhausted = inboxMapper.markExpiredAttributionDeadLetterCas(
                        candidate.getProviderConnectionId(), candidate.getId(),
                        LEASE_EXHAUSTED, maxAttempts);
                requireZeroOrOne(exhausted, "Provider expired attribution CAS changed multiple rows");
                if (exhausted == 1) {
                    log.error("[claimBatch][provider attribution exhausted; connectionId={}, inboxId={}]",
                            candidate.getProviderConnectionId(), candidate.getId());
                    continue;
                }
                int changed = inboxMapper.claimForAttributionCas(
                        candidate.getProviderConnectionId(), candidate.getId(), leaseOwner,
                        leaseSeconds, maxAttempts);
                requireZeroOrOne(changed, "Provider attribution claim CAS changed multiple rows");
                if (changed == 1) {
                    claimed.add(candidate);
                }
            }
            return claimed;
        }));
    }

    private void processClaim(SkitProviderImpressionInboxDO claim, String leaseOwner) {
        try {
            processor.process(claim.getProviderConnectionId(), claim.getId(), leaseOwner);
        } catch (RuntimeException unexpected) {
            log.warn("[processClaim][provider attribution failed; connectionId={}, inboxId={}, "
                            + "exceptionType={}]", claim.getProviderConnectionId(), claim.getId(),
                    unexpected.getClass().getSimpleName());
            transitionFailure(claim, leaseOwner);
        }
    }

    private void transitionFailure(SkitProviderImpressionInboxDO claim, String leaseOwner) {
        TenantUtils.executeIgnore(() -> transactions.executeWithoutResult(status -> {
            int deadLettered = inboxMapper.markAttributionDeadLetterCas(
                    claim.getProviderConnectionId(), claim.getId(), leaseOwner,
                    PROCESSOR_ERROR, maxAttempts);
            requireZeroOrOne(deadLettered,
                    "Provider attribution dead-letter CAS changed multiple rows");
            if (deadLettered == 1) {
                log.error("[transitionFailure][provider attribution dead-lettered; "
                                + "connectionId={}, inboxId={}]",
                        claim.getProviderConnectionId(), claim.getId());
                return;
            }
            int retry = inboxMapper.markAttributionRetryWaitCas(
                    claim.getProviderConnectionId(), claim.getId(), leaseOwner,
                    PROCESSOR_ERROR, maxAttempts, backoffSeconds);
            requireZeroOrOne(retry, "Provider attribution retry CAS changed multiple rows");
        }));
    }

    private static void validateClaim(SkitProviderImpressionInboxDO claim) {
        if (claim == null || claim.getProviderConnectionId() == null
                || claim.getProviderConnectionId() <= 0 || claim.getId() == null
                || claim.getId() <= 0 || claim.getCanonicalAttemptId() == null
                || claim.getCanonicalAttemptId() <= 0) {
            throw new IllegalStateException("Provider attribution claim is invalid");
        }
    }

    private static String requireLeaseOwner(String value) {
        if (value == null || value.isEmpty() || value.length() > 64) {
            throw new IllegalStateException("Provider attribution lease owner is invalid");
        }
        return value;
    }

    private static int requireRange(String name, int value, int minimum, int maximum) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(
                    name + " must be between " + minimum + " and " + maximum);
        }
        return value;
    }

    private static void requireZeroOrOne(int changed, String message) {
        if (changed != 0 && changed != 1) {
            throw new IllegalStateException(message);
        }
    }

    private static String newLeaseOwner() {
        return "provider-attribution-" + UUID.randomUUID().toString();
    }
}
