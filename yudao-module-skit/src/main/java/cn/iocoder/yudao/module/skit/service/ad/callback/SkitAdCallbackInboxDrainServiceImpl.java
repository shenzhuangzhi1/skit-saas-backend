package cn.iocoder.yudao.module.skit.service.ad.callback;

import cn.iocoder.yudao.framework.tenant.core.util.TenantUtils;
import cn.iocoder.yudao.module.skit.dal.dataobject.ad.SkitAdCallbackClaimDO;
import cn.iocoder.yudao.module.skit.dal.mysql.ad.SkitAdCallbackInboxMapper;
import cn.iocoder.yudao.module.skit.framework.observability.SkitAdCallbackDrainObservation;
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

/**
 * Lease-based callback drain with a deliberately small cross-tenant trust boundary.
 *
 * <p>The global transaction sees only immutable routing fields. Every callback payload and all
 * business writes remain inside a transaction created after applying the row's derived tenant.</p>
 */
@Service
@Slf4j
public class SkitAdCallbackInboxDrainServiceImpl implements SkitAdCallbackInboxDrainService {

    private static final String UNEXPECTED_ERROR_CODE = "CALLBACK_PROCESSOR_EXCEPTION";
    private static final String EXHAUSTED_LEASE_ERROR_CODE = "CALLBACK_LEASE_EXHAUSTED";

    private final SkitAdCallbackInboxMapper inboxMapper;
    private final SkitAdCallbackProcessor processor;
    private final SkitAdCallbackDrainObservation observation;
    private final TransactionTemplate transactionTemplate;
    private final Supplier<String> leaseOwnerSupplier;
    private final int batchSize;
    private final int leaseSeconds;
    private final int maxAttempts;
    private final int baseBackoffSeconds;
    private final int maxBackoffSeconds;

    @Autowired
    public SkitAdCallbackInboxDrainServiceImpl(
            SkitAdCallbackInboxMapper inboxMapper,
            SkitAdCallbackProcessor processor,
            SkitAdCallbackDrainObservation observation,
            PlatformTransactionManager transactionManager,
            @Value("${skit.ad.callback.drain.batch-size:50}") int batchSize,
            @Value("${skit.ad.callback.drain.lease-seconds:120}") int leaseSeconds,
            @Value("${skit.ad.callback.drain.max-attempts:8}") int maxAttempts,
            @Value("${skit.ad.callback.drain.base-backoff-seconds:30}") int baseBackoffSeconds,
            @Value("${skit.ad.callback.drain.max-backoff-seconds:3600}") int maxBackoffSeconds) {
        this(inboxMapper, processor, observation, transactionManager,
                SkitAdCallbackInboxDrainServiceImpl::newLeaseOwner, batchSize, leaseSeconds,
                maxAttempts, baseBackoffSeconds, maxBackoffSeconds);
    }

    SkitAdCallbackInboxDrainServiceImpl(
            SkitAdCallbackInboxMapper inboxMapper,
            SkitAdCallbackProcessor processor,
            SkitAdCallbackDrainObservation observation,
            PlatformTransactionManager transactionManager,
            Supplier<String> leaseOwnerSupplier,
            int batchSize,
            int leaseSeconds,
            int maxAttempts,
            int baseBackoffSeconds,
            int maxBackoffSeconds) {
        this.inboxMapper = Objects.requireNonNull(inboxMapper, "inboxMapper");
        this.processor = Objects.requireNonNull(processor, "processor");
        this.observation = Objects.requireNonNull(observation, "observation");
        this.transactionTemplate = new TransactionTemplate(
                Objects.requireNonNull(transactionManager, "transactionManager"));
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.transactionTemplate.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.leaseOwnerSupplier = Objects.requireNonNull(leaseOwnerSupplier, "leaseOwnerSupplier");
        this.batchSize = requireRange("batchSize", batchSize, 1, 500);
        this.leaseSeconds = requireRange("leaseSeconds", leaseSeconds, 1, 3600);
        this.maxAttempts = requireRange("maxAttempts", maxAttempts, 1, 30);
        this.baseBackoffSeconds = requireRange("baseBackoffSeconds", baseBackoffSeconds, 1, 86400);
        this.maxBackoffSeconds = requireRange("maxBackoffSeconds", maxBackoffSeconds,
                this.baseBackoffSeconds, 86400);
    }

    @Override
    public int drainOnce() {
        String leaseOwner = requireLeaseOwner(leaseOwnerSupplier.get());
        ClaimBatch batch = claimBatch(leaseOwner);
        for (SkitAdCallbackClaimDO deadLetter : batch.unalertedDeadLetters) {
            alertDeadLetter(deadLetter);
        }
        for (SkitAdCallbackClaimDO claim : batch.claims) {
            processClaim(claim, leaseOwner);
        }
        return batch.claims.size();
    }

    private ClaimBatch claimBatch(String leaseOwner) {
        return TenantUtils.executeIgnore(() -> transactionTemplate.execute(status -> {
            List<SkitAdCallbackClaimDO> candidates = inboxMapper.selectReadyClaimsForUpdate(batchSize);
            List<SkitAdCallbackClaimDO> claimed = new ArrayList<>(candidates.size());
            for (SkitAdCallbackClaimDO candidate : candidates) {
                validateRoute(candidate);
                int exhausted = inboxMapper.markExpiredProcessingDeadLetterCas(
                        candidate.getTenantId(), candidate.getAdAccountId(), candidate.getId(),
                        EXHAUSTED_LEASE_ERROR_CODE, maxAttempts);
                if (exhausted == 1) {
                    continue;
                }
                if (exhausted != 0) {
                    throw new IllegalStateException("Expired callback lease CAS changed multiple rows");
                }
                int changed = inboxMapper.claimForProcessingCas(candidate.getTenantId(),
                        candidate.getAdAccountId(), candidate.getId(), leaseOwner, leaseSeconds);
                if (changed == 1) {
                    claimed.add(candidate);
                } else if (changed != 0) {
                    throw new IllegalStateException("Callback claim CAS changed multiple rows");
                }
            }
            List<SkitAdCallbackClaimDO> unalerted =
                    inboxMapper.selectUnalertedDeadLetterClaims(batchSize);
            for (SkitAdCallbackClaimDO deadLetter : unalerted) {
                validateRoute(deadLetter);
            }
            return new ClaimBatch(claimed, unalerted);
        }));
    }

    private void processClaim(SkitAdCallbackClaimDO claim, String leaseOwner) {
        try {
            TenantUtils.execute(claim.getTenantId(), (Runnable) () ->
                    transactionTemplate.executeWithoutResult(status -> processor.process(
                            claim.getTenantId(), claim.getAdAccountId(), claim.getId(), leaseOwner)));
        } catch (RuntimeException unexpected) {
            log.warn("[processClaim][unexpected callback processor failure; applying lease-bound retry. "
                            + "tenantId={}, adAccountId={}, inboxId={}, exceptionType={}]",
                    claim.getTenantId(), claim.getAdAccountId(), claim.getId(),
                    unexpected.getClass().getSimpleName());
            if (transitionUnexpectedFailure(claim, leaseOwner)) {
                alertDeadLetter(claim);
            }
        }
    }

    private boolean transitionUnexpectedFailure(SkitAdCallbackClaimDO claim, String leaseOwner) {
        Boolean transitionedToDeadLetter = TenantUtils.execute(claim.getTenantId(), () ->
                transactionTemplate.execute(status -> {
                    int deadLettered = inboxMapper.markDeadLetterCas(claim.getTenantId(),
                            claim.getAdAccountId(), claim.getId(), leaseOwner,
                            UNEXPECTED_ERROR_CODE, maxAttempts);
                    if (deadLettered == 1) {
                        return true;
                    }
                    if (deadLettered != 0) {
                        throw new IllegalStateException("Callback dead-letter CAS changed multiple rows");
                    }
                    int retryScheduled = inboxMapper.markRetryWaitCas(claim.getTenantId(),
                            claim.getAdAccountId(), claim.getId(), leaseOwner,
                            UNEXPECTED_ERROR_CODE, maxAttempts,
                            baseBackoffSeconds, maxBackoffSeconds);
                    if (retryScheduled != 0 && retryScheduled != 1) {
                        throw new IllegalStateException("Callback retry CAS changed multiple rows");
                    }
                    return false;
                }));
        return Boolean.TRUE.equals(transitionedToDeadLetter);
    }

    private void alertDeadLetter(SkitAdCallbackClaimDO claim) {
        Boolean alertWon = TenantUtils.execute(claim.getTenantId(), () ->
                transactionTemplate.execute(status -> {
                    int alerted = inboxMapper.markDeadLetterAlertedCas(claim.getTenantId(),
                            claim.getAdAccountId(), claim.getId());
                    if (alerted != 0 && alerted != 1) {
                        throw new IllegalStateException(
                                "Callback dead-letter alert CAS changed multiple rows");
                    }
                    return alerted == 1;
                }));
        if (Boolean.TRUE.equals(alertWon)) {
            observation.recordDeadLetter(claim.getTenantId(), claim.getAdAccountId(), claim.getId());
        }
    }

    private static void validateRoute(SkitAdCallbackClaimDO claim) {
        if (claim == null || claim.getTenantId() == null || claim.getTenantId() <= 0
                || claim.getAdAccountId() == null || claim.getAdAccountId() <= 0
                || claim.getId() == null || claim.getId() <= 0) {
            throw new IllegalStateException("Callback claim routing projection is invalid");
        }
    }

    private static String requireLeaseOwner(String value) {
        if (value == null || value.isEmpty() || value.length() > 64) {
            throw new IllegalStateException("Callback lease owner is invalid");
        }
        return value;
    }

    private static int requireRange(String name, int value, int minimum, int maximum) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(name + " must be between " + minimum + " and " + maximum);
        }
        return value;
    }

    private static String newLeaseOwner() {
        return "callback-" + UUID.randomUUID().toString();
    }

    private static final class ClaimBatch {
        private final List<SkitAdCallbackClaimDO> claims;
        private final List<SkitAdCallbackClaimDO> unalertedDeadLetters;

        private ClaimBatch(List<SkitAdCallbackClaimDO> claims,
                           List<SkitAdCallbackClaimDO> unalertedDeadLetters) {
            this.claims = claims;
            this.unalertedDeadLetters = unalertedDeadLetters;
        }
    }

}
