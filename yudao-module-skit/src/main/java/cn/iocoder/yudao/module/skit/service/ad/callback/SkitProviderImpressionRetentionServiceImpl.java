package cn.iocoder.yudao.module.skit.service.ad.callback;

import cn.iocoder.yudao.framework.tenant.core.aop.TenantIgnore;
import cn.iocoder.yudao.module.skit.dal.dataobject.provider.SkitProviderCallbackAttemptDO;
import cn.iocoder.yudao.module.skit.dal.mysql.provider.SkitProviderCallbackAttemptMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

@Service
@TenantIgnore
public class SkitProviderImpressionRetentionServiceImpl
        implements SkitProviderImpressionRetentionService {

    private static final Pattern LEASE_OWNER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,63}");
    private static final int TRANSACTION_TIMEOUT_SECONDS = 2;

    private final SkitProviderCallbackAttemptMapper attemptMapper;
    private final TransactionOperations transactions;
    private final SkitProviderImpressionRetentionProperties properties;

    @Autowired
    public SkitProviderImpressionRetentionServiceImpl(
            SkitProviderCallbackAttemptMapper attemptMapper,
            PlatformTransactionManager transactionManager,
            SkitProviderImpressionRetentionProperties properties) {
        this(attemptMapper,
                shortTransaction(Objects.requireNonNull(transactionManager, "transactionManager")),
                properties);
    }

    SkitProviderImpressionRetentionServiceImpl(
            SkitProviderCallbackAttemptMapper attemptMapper,
            TransactionOperations transactions,
            SkitProviderImpressionRetentionProperties properties) {
        this.attemptMapper = Objects.requireNonNull(attemptMapper, "attemptMapper");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.properties = Objects.requireNonNull(properties, "properties");
    }

    @Override
    @TenantIgnore
    public int purgeExpiredCiphertexts(String leaseOwner, LocalDateTime now) {
        requireLeaseOwner(leaseOwner);
        LocalDateTime purgeAt = requireUtcSecond(now);
        Integer purged = transactions.execute(status -> purgeLockedBatch(purgeAt));
        if (purged == null) {
            throw new IllegalStateException("Provider impression retention transaction returned no result");
        }
        return purged;
    }

    private int purgeLockedBatch(LocalDateTime purgeAt) {
        int batchSize = properties.getBatchSize();
        List<SkitProviderCallbackAttemptDO> selected =
                attemptMapper.selectEligiblePayloadsForPurge(purgeAt, batchSize);
        if (selected == null || selected.size() > batchSize) {
            throw new IllegalStateException("Provider impression retention selection is invalid");
        }
        Set<Long> selectedIds = new HashSet<>(selected.size());
        for (SkitProviderCallbackAttemptDO attempt : selected) {
            validateSelectedAttempt(attempt, selectedIds);
            int changed = attemptMapper.purgeEligiblePayload(
                    attempt.getId(), attempt.getProviderConnectionId(), attempt.getInboxId(), purgeAt);
            if (changed != 1) {
                throw new IllegalStateException(
                        "Provider impression retention changed an unexpected number of rows");
            }
        }
        return selected.size();
    }

    private static void validateSelectedAttempt(SkitProviderCallbackAttemptDO attempt,
                                                Set<Long> selectedIds) {
        if (attempt == null || attempt.getId() == null || attempt.getId() <= 0
                || attempt.getProviderConnectionId() == null
                || attempt.getProviderConnectionId() <= 0
                || attempt.getInboxId() == null || attempt.getInboxId() <= 0
                || !selectedIds.add(attempt.getId())) {
            throw new IllegalStateException("Provider impression retention selection identity is invalid");
        }
    }

    private static void requireLeaseOwner(String leaseOwner) {
        if (leaseOwner == null || !LEASE_OWNER.matcher(leaseOwner).matches()) {
            throw new IllegalArgumentException("Provider impression retention node id is invalid");
        }
    }

    private static LocalDateTime requireUtcSecond(LocalDateTime now) {
        if (now == null || now.getNano() != 0) {
            throw new IllegalArgumentException(
                    "Provider impression retention time must be UTC second precision");
        }
        return now;
    }

    private static TransactionTemplate shortTransaction(PlatformTransactionManager manager) {
        TransactionTemplate template = new TransactionTemplate(manager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        template.setTimeout(TRANSACTION_TIMEOUT_SECONDS);
        return template;
    }
}
