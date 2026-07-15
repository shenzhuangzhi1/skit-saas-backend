package cn.iocoder.yudao.module.skit.service.commission;

import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.skit.dal.dataobject.revenue.SkitAdRevenueEventDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.revenue.SkitCommissionLedgerDO;
import cn.iocoder.yudao.module.skit.dal.mysql.revenue.SkitCommissionLedgerMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.dao.DuplicateKeyException;

import javax.annotation.Resource;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static cn.iocoder.yudao.module.skit.enums.SkitDomainConstants.AGENT_BENEFICIARY_ID;
import static cn.iocoder.yudao.module.skit.enums.SkitDomainConstants.AGENT_LEDGER_LEVEL;
import static cn.iocoder.yudao.module.skit.enums.SkitDomainConstants.BENEFICIARY_AGENT;
import static cn.iocoder.yudao.module.skit.enums.SkitDomainConstants.BENEFICIARY_MEMBER;
import static cn.iocoder.yudao.module.skit.enums.SkitDomainConstants.LEDGER_BALANCE_FROZEN;
import static cn.iocoder.yudao.module.skit.enums.SkitDomainConstants.LEDGER_ENTRY_ESTIMATE;
import static cn.iocoder.yudao.module.skit.enums.SkitDomainConstants.LEDGER_ESTIMATED;
import static cn.iocoder.yudao.module.skit.enums.SkitDomainConstants.RATE_BASE;

@Service
public class SkitFrozenCommissionProjectionServiceImpl implements SkitFrozenCommissionProjectionService {

    private static final int INITIAL_REVISION = 0;
    private static final int LEGACY_DECIMAL_SCALE = 8;
    private static final BigDecimal LEGACY_DECIMAL_MAX = new BigDecimal("999999999999.99999999");

    @Resource
    private SkitCommissionLedgerMapper ledgerMapper;
    @Resource
    private SkitPolicySnapshotService snapshotService;
    @Resource
    private SkitMoneyAllocator moneyAllocator;

    public SkitFrozenCommissionProjectionServiceImpl() {
    }

    SkitFrozenCommissionProjectionServiceImpl(SkitCommissionLedgerMapper ledgerMapper,
                                               SkitPolicySnapshotService snapshotService,
                                               SkitMoneyAllocator moneyAllocator) {
        this.ledgerMapper = ledgerMapper;
        this.snapshotService = snapshotService;
        this.moneyAllocator = moneyAllocator;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ProjectionResult projectRewardedEstimate(SkitAdRevenueEventDO event) {
        validateEvent(event, "REWARDED");
        SkitPolicySnapshotService.PolicySnapshot snapshot = requireBoundSnapshot(event);
        SkitMoneyAllocator.Result allocation = moneyAllocator.allocate(event.getSourceCurrency(),
                event.getAmountScale(), event.getEstimatedAmountUnits(), snapshot);
        List<SkitCommissionLedgerDO> entries = new ArrayList<>();
        for (SkitMoneyAllocator.MemberAllocation member : allocation.getMemberAllocations()) {
            if (member.getAmountUnits() > 0L) {
                entries.add(entry(event, BENEFICIARY_MEMBER, member.getMemberId(), member.getLevelNo(),
                        member.getRateBps(), member.getAmountUnits()));
            }
        }
        if (allocation.getAgentRetentionUnits() > 0L) {
            entries.add(entry(event, BENEFICIARY_AGENT, AGENT_BENEFICIARY_ID, AGENT_LEDGER_LEVEL,
                    RATE_BASE - snapshot.getEligibleRateBps(), allocation.getAgentRetentionUnits()));
        }
        return appendCanonical(event, entries);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ProjectionResult projectNonRewardedEstimate(SkitAdRevenueEventDO event) {
        validateEvent(event, "NON_REWARDED");
        requireBoundSnapshot(event);
        List<SkitCommissionLedgerDO> entries = new ArrayList<>();
        if (event.getEstimatedAmountUnits() > 0L) {
            entries.add(entry(event, BENEFICIARY_AGENT, AGENT_BENEFICIARY_ID, AGENT_LEDGER_LEVEL,
                    RATE_BASE, event.getEstimatedAmountUnits()));
        }
        return appendCanonical(event, entries);
    }

    private ProjectionResult appendCanonical(SkitAdRevenueEventDO event,
                                             List<SkitCommissionLedgerDO> entries) {
        long projected = 0L;
        for (SkitCommissionLedgerDO candidate : entries) {
            try {
                if (ledgerMapper.insertCanonicalEstimate(candidate) != 1) {
                    throw new IllegalStateException("Frozen commission entry was not appended");
                }
            } catch (DuplicateKeyException ignored) {
                // The immutable canonical row is selected and compared below. Deliberately avoid
                // ON DUPLICATE KEY UPDATE because the ledger has an append-only UPDATE trigger.
            }
            SkitCommissionLedgerDO canonical = ledgerMapper.selectCanonicalEntryForUpdate(
                    event.getTenantId(), event.getId(), candidate.getBeneficiaryType(),
                    candidate.getBeneficiaryMemberId(), candidate.getLevelNo(), LEDGER_ENTRY_ESTIMATE,
                    INITIAL_REVISION);
            assertCanonical(candidate, canonical);
            projected = addExact(projected, canonical.getAmountUnits());
        }
        if (projected != event.getEstimatedAmountUnits()) {
            throw new IllegalStateException("Frozen commission projection does not conserve event amount");
        }
        return new ProjectionResult(entries.size(), projected);
    }

    private void validateEvent(SkitAdRevenueEventDO event, String expectedRewardStatus) {
        long tenantId = TenantContextHolder.getRequiredTenantId();
        if (event == null || event.getId() == null || event.getId() <= 0
                || event.getTenantId() == null || event.getTenantId() != tenantId
                || event.getAdAccountId() == null || event.getAdAccountId() <= 0
                || event.getAdSessionId() == null || event.getAdSessionId() <= 0
                || event.getCallbackInboxId() == null || event.getCallbackInboxId() <= 0
                || event.getSourceMemberId() == null || event.getSourceMemberId() <= 0
                || event.getPolicySnapshotId() == null || event.getPolicySnapshotId() <= 0
                || event.getRuleVersion() == null || event.getRuleVersion() <= 0
                || event.getEstimatedAmountUnits() == null || event.getEstimatedAmountUnits() < 0
                || event.getAmountScale() == null || event.getAmountScale() < 0
                || event.getAmountScale() > 18
                || event.getSourceCurrency() == null
                || !event.getSourceCurrency().matches("[A-Z]{3}")
                || !Boolean.FALSE.equals(event.getLegacyUnverified())
                || !"TAKU".equals(event.getProvider())
                || !"TAKU_IMPRESSION".equals(event.getSourceType())
                || !"MATCHED".equals(event.getMatchStatus())
                || !"UNSIGNED_OBSERVATION".equals(event.getSourceVerificationStatus())
                || !expectedRewardStatus.equals(event.getRewardQualificationStatus())
                || !"FROZEN".equals(event.getReconciliationStatus())) {
            throw new IllegalStateException("Revenue event is not eligible for frozen projection");
        }
    }

    private SkitPolicySnapshotService.PolicySnapshot requireBoundSnapshot(SkitAdRevenueEventDO event) {
        SkitPolicySnapshotService.PolicySnapshot snapshot =
                snapshotService.getRequired(event.getPolicySnapshotId());
        if (snapshot == null || !Objects.equals(event.getTenantId(), snapshot.getTenantId())
                || !Objects.equals(event.getPolicySnapshotId(), snapshot.getId())
                || !Objects.equals(event.getSourceMemberId(), snapshot.getSourceMemberId())
                || !Objects.equals(event.getRuleVersion(), snapshot.getRuleVersion())) {
            throw new IllegalStateException("Revenue event policy snapshot envelope is invalid");
        }
        return snapshot;
    }

    private SkitCommissionLedgerDO entry(SkitAdRevenueEventDO event, int beneficiaryType,
                                         long beneficiaryMemberId, int levelNo, int rateBps,
                                         long amountUnits) {
        SkitCommissionLedgerDO row = SkitCommissionLedgerDO.builder()
                .eventId(event.getId()).beneficiaryType(beneficiaryType)
                .beneficiaryMemberId(beneficiaryMemberId).levelNo(levelNo)
                .grossAmount(legacyDecimal(event.getEstimatedAmountUnits(), event.getAmountScale()))
                .rateBps(rateBps).amount(legacyDecimal(amountUnits, event.getAmountScale()))
                .ruleVersion(event.getRuleVersion()).status(LEDGER_ESTIMATED)
                .entryType(LEDGER_ENTRY_ESTIMATE).balanceBucket(LEDGER_BALANCE_FROZEN)
                .currency(event.getSourceCurrency()).grossAmountUnits(event.getEstimatedAmountUnits())
                .amountUnits(amountUnits).amountScale(event.getAmountScale())
                .policySnapshotId(event.getPolicySnapshotId()).revisionNo(INITIAL_REVISION)
                .legacyUnverified(false).build();
        row.setTenantId(event.getTenantId());
        return row;
    }

    private BigDecimal legacyDecimal(long units, int scale) {
        BigDecimal value = BigDecimal.valueOf(units, scale).setScale(LEGACY_DECIMAL_SCALE,
                RoundingMode.DOWN);
        if (value.abs().compareTo(LEGACY_DECIMAL_MAX) > 0) {
            throw new IllegalStateException("Authoritative amount exceeds the legacy display mirror");
        }
        return value;
    }

    private void assertCanonical(SkitCommissionLedgerDO expected, SkitCommissionLedgerDO actual) {
        if (actual == null || actual.getId() == null || actual.getId() <= 0
                || !Objects.equals(expected.getTenantId(), actual.getTenantId())
                || !Objects.equals(expected.getEventId(), actual.getEventId())
                || !Objects.equals(expected.getBeneficiaryType(), actual.getBeneficiaryType())
                || !Objects.equals(expected.getBeneficiaryMemberId(), actual.getBeneficiaryMemberId())
                || !Objects.equals(expected.getLevelNo(), actual.getLevelNo())
                || !sameDecimal(expected.getGrossAmount(), actual.getGrossAmount())
                || !Objects.equals(expected.getRateBps(), actual.getRateBps())
                || !sameDecimal(expected.getAmount(), actual.getAmount())
                || !Objects.equals(expected.getRuleVersion(), actual.getRuleVersion())
                || !Objects.equals(expected.getStatus(), actual.getStatus())
                || !Objects.equals(expected.getEntryType(), actual.getEntryType())
                || !Objects.equals(expected.getBalanceBucket(), actual.getBalanceBucket())
                || !Objects.equals(expected.getCurrency(), actual.getCurrency())
                || !Objects.equals(expected.getGrossAmountUnits(), actual.getGrossAmountUnits())
                || !Objects.equals(expected.getAmountUnits(), actual.getAmountUnits())
                || !Objects.equals(expected.getAmountScale(), actual.getAmountScale())
                || !Objects.equals(expected.getReversalOfId(), actual.getReversalOfId())
                || !Objects.equals(expected.getReconciliationRevisionId(),
                actual.getReconciliationRevisionId())
                || !Objects.equals(expected.getPolicySnapshotId(), actual.getPolicySnapshotId())
                || !Objects.equals(expected.getRevisionNo(), actual.getRevisionNo())
                || !Objects.equals(expected.getLegacyUnverified(), actual.getLegacyUnverified())) {
            throw new IllegalStateException("Existing frozen commission entry conflicts with canonical projection");
        }
    }

    private boolean sameDecimal(BigDecimal expected, BigDecimal actual) {
        return expected == null ? actual == null : actual != null && expected.compareTo(actual) == 0;
    }

    private long addExact(long left, Long right) {
        try {
            return Math.addExact(left, Objects.requireNonNull(right, "ledger amountUnits"));
        } catch (ArithmeticException ex) {
            throw new IllegalStateException("Frozen commission projection total overflow", ex);
        }
    }

}
