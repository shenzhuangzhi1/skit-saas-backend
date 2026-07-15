package cn.iocoder.yudao.module.skit.service.reconciliation;

import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.skit.dal.dataobject.reconciliation.SkitAdReconciliationAllocationDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.revenue.SkitAdRevenueEventDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.revenue.SkitCommissionLedgerDO;
import cn.iocoder.yudao.module.skit.dal.mysql.reconciliation.SkitAdReconciliationAllocationMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.revenue.SkitCommissionLedgerMapper;
import cn.iocoder.yudao.module.skit.service.commission.SkitMoneyAllocator;
import cn.iocoder.yudao.module.skit.service.commission.SkitPolicySnapshotService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.dao.DuplicateKeyException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static cn.iocoder.yudao.module.skit.enums.SkitDomainConstants.AGENT_BENEFICIARY_ID;
import static cn.iocoder.yudao.module.skit.enums.SkitDomainConstants.AGENT_LEDGER_LEVEL;
import static cn.iocoder.yudao.module.skit.enums.SkitDomainConstants.BENEFICIARY_AGENT;
import static cn.iocoder.yudao.module.skit.enums.SkitDomainConstants.BENEFICIARY_MEMBER;
import static cn.iocoder.yudao.module.skit.enums.SkitDomainConstants.LEDGER_AVAILABLE;
import static cn.iocoder.yudao.module.skit.enums.SkitDomainConstants.LEDGER_BALANCE_AVAILABLE;
import static cn.iocoder.yudao.module.skit.enums.SkitDomainConstants.LEDGER_BALANCE_FROZEN;
import static cn.iocoder.yudao.module.skit.enums.SkitDomainConstants.LEDGER_ENTRY_ADJUSTMENT;
import static cn.iocoder.yudao.module.skit.enums.SkitDomainConstants.LEDGER_ENTRY_ESTIMATE;
import static cn.iocoder.yudao.module.skit.enums.SkitDomainConstants.LEDGER_ENTRY_ESTIMATE_RELEASE;
import static cn.iocoder.yudao.module.skit.enums.SkitDomainConstants.LEDGER_ENTRY_SETTLEMENT;
import static cn.iocoder.yudao.module.skit.enums.SkitDomainConstants.RATE_BASE;

@Service
public class SkitLedgerProjectionServiceImpl implements SkitLedgerProjectionService {

    private final SkitCommissionLedgerMapper ledgerMapper;
    private final SkitAdReconciliationAllocationMapper allocationMapper;
    private final SkitPolicySnapshotService snapshotService;
    private final SkitMoneyAllocator moneyAllocator;

    public SkitLedgerProjectionServiceImpl(SkitCommissionLedgerMapper ledgerMapper,
                                           SkitAdReconciliationAllocationMapper allocationMapper,
                                           SkitPolicySnapshotService snapshotService,
                                           SkitMoneyAllocator moneyAllocator) {
        this.ledgerMapper = Objects.requireNonNull(ledgerMapper, "ledgerMapper");
        this.allocationMapper = Objects.requireNonNull(allocationMapper, "allocationMapper");
        this.snapshotService = Objects.requireNonNull(snapshotService, "snapshotService");
        this.moneyAllocator = Objects.requireNonNull(moneyAllocator, "moneyAllocator");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ProjectionResult project(ProjectionCommand command) {
        SkitAdRevenueEventDO event = validate(command);
        SkitPolicySnapshotService.PolicySnapshot snapshot =
                snapshotService.getRequired(event.getPolicySnapshotId());
        validateSnapshot(snapshot, event, command.getTenantId());

        List<Target> targets = targets(event, snapshot, command.getCumulativeActualUnits());
        List<PersistedTarget> persisted = new ArrayList<>(targets.size());
        boolean hasPriorRevision = false;
        long previousGrossUnits = 0L;

        // Validate and append every cumulative target before touching money. This makes a duplicate
        // response, a partial retry, and a conflicting replay distinguishable without mutation.
        for (Target target : targets) {
            SkitAdReconciliationAllocationDO previous = allocationMapper
                    .selectLatestBeforeRevisionForUpdate(command.getTenantId(), event.getId(),
                            target.beneficiaryType, target.beneficiaryMemberId,
                            target.levelNo, command.getRevisionNo());
            if (previous != null) {
                validatePrior(previous, command, event, target);
                hasPriorRevision = true;
                previousGrossUnits = addExact(previousGrossUnits,
                        previous.getCumulativeTargetUnits(), "previous allocation total overflow");
            }
            SkitAdReconciliationAllocationDO row = allocation(command, event, target);
            try {
                if (allocationMapper.insertCanonical(row) != 1) {
                    throw new IllegalStateException("Reconciliation allocation was not appended");
                }
            } catch (DuplicateKeyException ignored) {
                // Immutable canonical row is compared below. No UPDATE statement is emitted,
                // so a duplicate retry cannot trip the append-only trigger.
            }
            SkitAdReconciliationAllocationDO canonical = allocationMapper.selectCanonicalForUpdate(
                    command.getTenantId(), event.getId(), command.getReconciliationRevisionId(),
                    command.getRevisionNo(), target.beneficiaryType, target.beneficiaryMemberId,
                    target.levelNo, event.getPolicySnapshotId());
            requireSameAllocation(row, canonical);
            persisted.add(new PersistedTarget(target, previous));
        }

        int ledgerCount = 0;
        if (!hasPriorRevision) {
            List<SkitCommissionLedgerDO> estimates = ledgerMapper
                    .selectEntriesForEventAndTypeForUpdate(command.getTenantId(), event.getId(),
                            LEDGER_ENTRY_ESTIMATE);
            if (estimates == null || estimates.isEmpty()) {
                throw new IllegalStateException("Frozen estimate ledger is unavailable for first reconciliation");
            }
            for (SkitCommissionLedgerDO estimate : estimates) {
                validateEstimate(estimate, event, command.getTenantId());
                if (estimate.getAmountUnits() == 0L) {
                    continue;
                }
                SkitCommissionLedgerDO release = ledger(command, event,
                        estimate.getBeneficiaryType(), estimate.getBeneficiaryMemberId(),
                        estimate.getLevelNo(), estimate.getRateBps(),
                        estimate.getGrossAmountUnits(), negateExact(estimate.getAmountUnits()),
                        LEDGER_ENTRY_ESTIMATE_RELEASE, LEDGER_BALANCE_FROZEN, 0, estimate.getId());
                appendCanonical(release);
                ledgerCount++;
            }
        }

        String entryType = hasPriorRevision ? LEDGER_ENTRY_ADJUSTMENT : LEDGER_ENTRY_SETTLEMENT;
        long grossUnits = hasPriorRevision
                ? subtractExact(command.getCumulativeActualUnits(), previousGrossUnits,
                        "reconciliation gross delta overflow")
                : command.getCumulativeActualUnits();
        for (PersistedTarget persistedTarget : persisted) {
            long previousUnits = persistedTarget.previous == null ? 0L
                    : persistedTarget.previous.getCumulativeTargetUnits();
            long delta = subtractExact(persistedTarget.target.cumulativeTargetUnits, previousUnits,
                    "beneficiary reconciliation delta overflow");
            if (delta == 0L) {
                continue;
            }
            SkitCommissionLedgerDO row = ledger(command, event,
                    persistedTarget.target.beneficiaryType,
                    persistedTarget.target.beneficiaryMemberId,
                    persistedTarget.target.levelNo, persistedTarget.target.rateBps,
                    grossUnits, delta, entryType, LEDGER_BALANCE_AVAILABLE,
                    command.getRevisionNo(), null);
            appendCanonical(row);
            ledgerCount++;
        }
        return new ProjectionResult(ledgerCount, persisted.size());
    }

    private List<Target> targets(SkitAdRevenueEventDO event,
                                 SkitPolicySnapshotService.PolicySnapshot snapshot,
                                 long actualUnits) {
        List<Target> result = new ArrayList<>();
        if ("NON_REWARDED".equals(event.getRewardQualificationStatus())) {
            result.add(new Target(BENEFICIARY_AGENT, AGENT_BENEFICIARY_ID,
                    AGENT_LEDGER_LEVEL, RATE_BASE, actualUnits));
            return result;
        }
        if (!"REWARDED".equals(event.getRewardQualificationStatus())) {
            throw new IllegalStateException("Only finalized reward qualification can be reconciled");
        }
        SkitMoneyAllocator.Result allocated = moneyAllocator.allocate(event.getSourceCurrency(),
                event.getAmountScale(), actualUnits, snapshot);
        for (SkitMoneyAllocator.MemberAllocation member : allocated.getMemberAllocations()) {
            result.add(new Target(BENEFICIARY_MEMBER, member.getMemberId(),
                    member.getLevelNo(), member.getRateBps(), member.getAmountUnits()));
        }
        result.add(new Target(BENEFICIARY_AGENT, AGENT_BENEFICIARY_ID, AGENT_LEDGER_LEVEL,
                RATE_BASE - snapshot.getEligibleRateBps(), allocated.getAgentRetentionUnits()));
        return result;
    }

    private SkitAdReconciliationAllocationDO allocation(ProjectionCommand command,
                                                         SkitAdRevenueEventDO event,
                                                         Target target) {
        SkitAdReconciliationAllocationDO row = new SkitAdReconciliationAllocationDO()
                .setReconciliationBucketId(command.getReconciliationBucketId())
                .setReconciliationRevisionId(command.getReconciliationRevisionId())
                .setRevisionNo(command.getRevisionNo()).setEventId(event.getId())
                .setBeneficiaryType(target.beneficiaryType)
                .setBeneficiaryMemberId(target.beneficiaryMemberId).setLevelNo(target.levelNo)
                .setPolicySnapshotId(event.getPolicySnapshotId())
                .setCurrency(event.getSourceCurrency()).setAmountScale(event.getAmountScale())
                .setCumulativeTargetUnits(target.cumulativeTargetUnits);
        row.setTenantId(command.getTenantId());
        return row;
    }

    private SkitCommissionLedgerDO ledger(ProjectionCommand command, SkitAdRevenueEventDO event,
                                           int beneficiaryType, long beneficiaryMemberId,
                                           int levelNo, int rateBps, long grossUnits, long amountUnits,
                                           String entryType, String balanceBucket, int revisionNo,
                                           Long reversalOfId) {
        SkitCommissionLedgerDO row = SkitCommissionLedgerDO.builder()
                .eventId(event.getId()).beneficiaryType(beneficiaryType)
                .beneficiaryMemberId(beneficiaryMemberId).levelNo(levelNo)
                .grossAmount(decimalMirror(grossUnits, event.getAmountScale())).rateBps(rateBps)
                .amount(decimalMirror(amountUnits, event.getAmountScale()))
                .ruleVersion(event.getRuleVersion()).status(LEDGER_AVAILABLE)
                .entryType(entryType).balanceBucket(balanceBucket)
                .currency(event.getSourceCurrency()).grossAmountUnits(grossUnits)
                .amountUnits(amountUnits).amountScale(event.getAmountScale())
                .reversalOfId(reversalOfId)
                .reconciliationRevisionId(command.getReconciliationRevisionId())
                .policySnapshotId(event.getPolicySnapshotId()).revisionNo(revisionNo)
                .legacyUnverified(false).build();
        row.setTenantId(command.getTenantId());
        return row;
    }

    private void appendCanonical(SkitCommissionLedgerDO row) {
        try {
            if (ledgerMapper.insertCanonicalReconciliationEntry(row) != 1) {
                throw new IllegalStateException("Reconciliation ledger entry was not appended");
            }
        } catch (DuplicateKeyException ignored) {
            // Select-and-compare below turns a duplicate into an idempotent retry and rejects a
            // same-key/different-payload conflict without issuing UPDATE against the ledger.
        }
        SkitCommissionLedgerDO canonical = ledgerMapper.selectCanonicalEntryForUpdate(
                row.getTenantId(), row.getEventId(), row.getBeneficiaryType(),
                row.getBeneficiaryMemberId(), row.getLevelNo(), row.getEntryType(),
                row.getRevisionNo());
        requireSameLedger(row, canonical);
    }

    private SkitAdRevenueEventDO validate(ProjectionCommand command) {
        if (command == null || command.getTenantId() <= 0L
                || command.getReconciliationBucketId() <= 0L
                || command.getReconciliationRevisionId() <= 0L || command.getRevisionNo() <= 0
                || command.getCumulativeActualUnits() < 0L || command.getEvent() == null) {
            throw new IllegalArgumentException("Reconciliation projection command is invalid");
        }
        Long currentTenant = TenantContextHolder.getRequiredTenantId();
        SkitAdRevenueEventDO event = command.getEvent();
        if (!Objects.equals(currentTenant, command.getTenantId())
                || !Objects.equals(event.getTenantId(), command.getTenantId())
                || event.getId() == null || event.getId() <= 0L
                || event.getPolicySnapshotId() == null || event.getPolicySnapshotId() <= 0L
                || event.getAdSessionId() == null || event.getCallbackInboxId() == null
                || Boolean.TRUE.equals(event.getLegacyUnverified())
                || !"MATCHED".equals(event.getMatchStatus())
                || event.getSourceCurrency() == null || !event.getSourceCurrency().matches("[A-Z]{3}")
                || event.getAmountScale() == null || event.getAmountScale() < 0
                || event.getAmountScale() > 18) {
            throw new IllegalStateException("Revenue event is outside the verified tenant envelope");
        }
        return event;
    }

    private static void validateSnapshot(SkitPolicySnapshotService.PolicySnapshot snapshot,
                                         SkitAdRevenueEventDO event, long tenantId) {
        if (snapshot == null || !Objects.equals(snapshot.getId(), event.getPolicySnapshotId())
                || !Objects.equals(snapshot.getTenantId(), tenantId)
                || !Objects.equals(snapshot.getSourceMemberId(), event.getSourceMemberId())
                || !Objects.equals(snapshot.getRuleVersion(), event.getRuleVersion())
                || snapshot.getEligibleRateBps() < 0 || snapshot.getEligibleRateBps() > RATE_BASE) {
            throw new IllegalStateException("Policy snapshot does not bind the reconciled event");
        }
    }

    private static void validatePrior(SkitAdReconciliationAllocationDO row,
                                      ProjectionCommand command, SkitAdRevenueEventDO event,
                                      Target target) {
        if (!Objects.equals(row.getTenantId(), command.getTenantId())
                || !Objects.equals(row.getEventId(), event.getId())
                || !Objects.equals(row.getBeneficiaryType(), target.beneficiaryType)
                || !Objects.equals(row.getBeneficiaryMemberId(), target.beneficiaryMemberId)
                || !Objects.equals(row.getLevelNo(), target.levelNo)
                || !Objects.equals(row.getPolicySnapshotId(), event.getPolicySnapshotId())
                || !Objects.equals(row.getCurrency(), event.getSourceCurrency())
                || !Objects.equals(row.getAmountScale(), event.getAmountScale())
                || row.getRevisionNo() == null || row.getRevisionNo() >= command.getRevisionNo()
                || row.getCumulativeTargetUnits() == null || row.getCumulativeTargetUnits() < 0L) {
            throw new IllegalStateException("Prior reconciliation allocation crossed its immutable envelope");
        }
    }

    private static void validateEstimate(SkitCommissionLedgerDO row,
                                         SkitAdRevenueEventDO event, long tenantId) {
        if (row == null || !Objects.equals(row.getTenantId(), tenantId)
                || !Objects.equals(row.getEventId(), event.getId())
                || !LEDGER_ENTRY_ESTIMATE.equals(row.getEntryType())
                || !LEDGER_BALANCE_FROZEN.equals(row.getBalanceBucket())
                || !Objects.equals(row.getCurrency(), event.getSourceCurrency())
                || !Objects.equals(row.getAmountScale(), event.getAmountScale())
                || !Objects.equals(row.getPolicySnapshotId(), event.getPolicySnapshotId())
                || Boolean.TRUE.equals(row.getLegacyUnverified())
                || row.getAmountUnits() == null || row.getAmountUnits() < 0L
                || row.getGrossAmountUnits() == null || row.getGrossAmountUnits() < 0L) {
            throw new IllegalStateException("Frozen estimate ledger crossed its immutable event envelope");
        }
    }

    private static void requireSameAllocation(SkitAdReconciliationAllocationDO expected,
                                              SkitAdReconciliationAllocationDO actual) {
        if (actual == null || !Objects.equals(expected.getTenantId(), actual.getTenantId())
                || !Objects.equals(expected.getReconciliationBucketId(), actual.getReconciliationBucketId())
                || !Objects.equals(expected.getReconciliationRevisionId(), actual.getReconciliationRevisionId())
                || !Objects.equals(expected.getRevisionNo(), actual.getRevisionNo())
                || !Objects.equals(expected.getEventId(), actual.getEventId())
                || !Objects.equals(expected.getBeneficiaryType(), actual.getBeneficiaryType())
                || !Objects.equals(expected.getBeneficiaryMemberId(), actual.getBeneficiaryMemberId())
                || !Objects.equals(expected.getLevelNo(), actual.getLevelNo())
                || !Objects.equals(expected.getPolicySnapshotId(), actual.getPolicySnapshotId())
                || !Objects.equals(expected.getCurrency(), actual.getCurrency())
                || !Objects.equals(expected.getAmountScale(), actual.getAmountScale())
                || !Objects.equals(expected.getCumulativeTargetUnits(), actual.getCumulativeTargetUnits())) {
            throw new IllegalStateException("Conflicting canonical reconciliation allocation");
        }
    }

    private static void requireSameLedger(SkitCommissionLedgerDO expected,
                                          SkitCommissionLedgerDO actual) {
        if (actual == null || !Objects.equals(expected.getTenantId(), actual.getTenantId())
                || !Objects.equals(expected.getEventId(), actual.getEventId())
                || !Objects.equals(expected.getBeneficiaryType(), actual.getBeneficiaryType())
                || !Objects.equals(expected.getBeneficiaryMemberId(), actual.getBeneficiaryMemberId())
                || !Objects.equals(expected.getLevelNo(), actual.getLevelNo())
                || !Objects.equals(expected.getRateBps(), actual.getRateBps())
                || !Objects.equals(expected.getEntryType(), actual.getEntryType())
                || !Objects.equals(expected.getBalanceBucket(), actual.getBalanceBucket())
                || !Objects.equals(expected.getCurrency(), actual.getCurrency())
                || !Objects.equals(expected.getGrossAmountUnits(), actual.getGrossAmountUnits())
                || !Objects.equals(expected.getAmountUnits(), actual.getAmountUnits())
                || !Objects.equals(expected.getAmountScale(), actual.getAmountScale())
                || !Objects.equals(expected.getReversalOfId(), actual.getReversalOfId())
                || !Objects.equals(expected.getReconciliationRevisionId(), actual.getReconciliationRevisionId())
                || !Objects.equals(expected.getPolicySnapshotId(), actual.getPolicySnapshotId())
                || !Objects.equals(expected.getRevisionNo(), actual.getRevisionNo())
                || !Objects.equals(expected.getLegacyUnverified(), actual.getLegacyUnverified())) {
            throw new IllegalStateException("Conflicting canonical reconciliation ledger entry");
        }
    }

    private static BigDecimal decimalMirror(long units, int sourceScale) {
        return BigDecimal.valueOf(units, sourceScale).setScale(8, RoundingMode.HALF_EVEN);
    }

    private static long addExact(long left, long right, String message) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException exception) {
            throw new IllegalStateException(message, exception);
        }
    }

    private static long subtractExact(long left, long right, String message) {
        try {
            return Math.subtractExact(left, right);
        } catch (ArithmeticException exception) {
            throw new IllegalStateException(message, exception);
        }
    }

    private static long negateExact(long value) {
        try {
            return Math.negateExact(value);
        } catch (ArithmeticException exception) {
            throw new IllegalStateException("estimate release overflow", exception);
        }
    }

    private static final class Target {
        private final int beneficiaryType;
        private final long beneficiaryMemberId;
        private final int levelNo;
        private final int rateBps;
        private final long cumulativeTargetUnits;

        private Target(int beneficiaryType, long beneficiaryMemberId, int levelNo,
                       int rateBps, long cumulativeTargetUnits) {
            this.beneficiaryType = beneficiaryType;
            this.beneficiaryMemberId = beneficiaryMemberId;
            this.levelNo = levelNo;
            this.rateBps = rateBps;
            this.cumulativeTargetUnits = cumulativeTargetUnits;
        }
    }

    private static final class PersistedTarget {
        private final Target target;
        private final SkitAdReconciliationAllocationDO previous;

        private PersistedTarget(Target target, SkitAdReconciliationAllocationDO previous) {
            this.target = target;
            this.previous = previous;
        }
    }
}
