package cn.iocoder.yudao.module.skit.service.revenue;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.enums.CommonStatusEnum;
import cn.iocoder.yudao.framework.common.pojo.PageParam;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.skit.dal.dataobject.ad.SkitAdAccountDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.member.SkitMemberClosureDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.member.SkitMemberDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.revenue.SkitAdRevenueEventDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.revenue.SkitCommissionLedgerDO;
import cn.iocoder.yudao.module.skit.dal.mysql.ad.SkitAdAccountMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.member.SkitMemberClosureMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.member.SkitMemberMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.revenue.SkitAdRevenueEventMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.revenue.SkitCommissionLedgerMapper;
import cn.iocoder.yudao.module.skit.service.ad.SkitAdAccountService;
import cn.iocoder.yudao.module.skit.service.commission.SkitCommissionCalculator;
import cn.iocoder.yudao.module.skit.service.commission.SkitCommissionService;
import cn.iocoder.yudao.module.system.service.tenant.TenantService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.dao.DuplicateKeyException;

import javax.annotation.Resource;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.skit.enums.ErrorCodeConstants.*;
import static cn.iocoder.yudao.module.skit.enums.SkitDomainConstants.*;

@Service
public class SkitRevenueServiceImpl implements SkitRevenueService {

    @Resource
    private SkitAdRevenueEventMapper eventMapper;
    @Resource
    private SkitCommissionLedgerMapper ledgerMapper;
    @Resource
    private SkitAdAccountMapper accountMapper;
    @Resource
    private SkitMemberMapper memberMapper;
    @Resource
    private SkitMemberClosureMapper closureMapper;
    @Resource
    private SkitAdAccountService adAccountService;
    @Resource
    private SkitCommissionService commissionService;
    @Resource
    private TenantService tenantService;

    private final SkitCommissionCalculator calculator = new SkitCommissionCalculator();
    private final SkitRevenueReportValidator reportValidator = new SkitRevenueReportValidator();

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ReportResult report(Long sourceMemberId, ReportCommand command) {
        tenantService.validTenant(TenantContextHolder.getRequiredTenantId());
        SkitMemberDO sourceMember = memberMapper.selectById(sourceMemberId);
        if (sourceMember == null || CommonStatusEnum.isDisable(sourceMember.getStatus())) {
            throw exception(MEMBER_NOT_EXISTS);
        }
        validateCommand(command);
        String provider = normalizeProvider(command.getProvider());
        SkitAdAccountDO account = accountMapper.selectByProvider(provider);
        if (account == null) {
            throw exception(AD_ACCOUNT_NOT_EXISTS);
        }

        BigDecimal gross = normalizeMoney(command.getGrossAmount());
        long grossAmountUnits = toMoneyUnits(gross);
        SkitAdRevenueEventDO existing = eventMapper.selectByAccountSourceAndExternalEventId(
                account.getId(), REVENUE_SOURCE_LEGACY_CLIENT, command.getExternalEventId());
        if (existing != null) {
            validateIdempotentEvent(existing, sourceMemberId, account.getId(), command, gross);
            return buildReportResult(existing, true);
        }
        validateNewEventCommand(command);

        SkitAdAccountService.PublicConfig publicConfig = adAccountService.getEnabledPublicConfig(provider);
        if (publicConfig == null || StrUtil.isBlank(publicConfig.getPlacementId())
                || !publicConfig.getPlacementId().equals(command.getPlacementId())) {
            throw exception(REVENUE_EVENT_INVALID, "广告位不属于当前代理商或未启用");
        }

        boolean ignored = !Boolean.TRUE.equals(command.getCompleted()) || Boolean.TRUE.equals(command.getMock())
                || gross.signum() <= 0;
        SkitAdRevenueEventDO event = SkitAdRevenueEventDO.builder()
                .adAccountId(account.getId()).provider(provider).placementId(command.getPlacementId())
                .externalEventId(command.getExternalEventId())
                .sourceMemberId(sourceMemberId).grossAmount(gross)
                .occurredTime(normalizeOccurredTime(command))
                .completed(Boolean.TRUE.equals(command.getCompleted())).mock(Boolean.TRUE.equals(command.getMock()))
                .status(ignored ? REVENUE_EVENT_IGNORED : REVENUE_EVENT_ESTIMATED)
                .sourceType(REVENUE_SOURCE_LEGACY_CLIENT)
                .sourceAmountUnits(grossAmountUnits).estimatedAmountUnits(grossAmountUnits)
                .reconciledAmountUnits(0L).amountScale(MONEY_SCALE).sourceCurrency(LEGACY_CURRENCY_CNY)
                .matchStatus(REVENUE_MATCH_LEGACY_UNMATCHED)
                .sourceVerificationStatus(REVENUE_VERIFICATION_LEGACY_UNVERIFIED)
                .rewardQualificationStatus(REWARD_QUALIFICATION_NOT_APPLICABLE)
                .reconciliationStatus(REVENUE_RECONCILIATION_NON_SETTLEABLE)
                .version(0).legacyUnverified(true)
                .rawData(command.getRawData()).build();
        if (ignored) {
            if (!insertEvent(event)) {
                SkitAdRevenueEventDO concurrent = eventMapper.selectByAccountSourceAndExternalEventId(
                        account.getId(), REVENUE_SOURCE_LEGACY_CLIENT, command.getExternalEventId());
                validateIdempotentEvent(concurrent, sourceMemberId, account.getId(), command, gross);
                return buildReportResult(concurrent, true);
            }
            return buildReportResult(event, false);
        }

        SkitCommissionService.PlanSnapshot snapshot = commissionService.getActiveSnapshot();
        event.setRuleVersion(snapshot.getVersion());
        if (!insertEvent(event)) {
            SkitAdRevenueEventDO concurrent = eventMapper.selectByAccountSourceAndExternalEventId(
                    account.getId(), REVENUE_SOURCE_LEGACY_CLIENT, command.getExternalEventId());
            validateIdempotentEvent(concurrent, sourceMemberId, account.getId(), command, gross);
            return buildReportResult(concurrent, true);
        }

        Map<Integer, Long> memberByLevel = new HashMap<>();
        for (SkitMemberClosureDO closure : closureMapper.selectAncestors(sourceMemberId)) {
            memberByLevel.put(closure.getDistance(), closure.getAncestorId());
        }
        List<SkitCommissionCalculator.Rule> rules = new ArrayList<>();
        for (SkitCommissionService.RuleSnapshot rule : snapshot.getRules()) {
            rules.add(new SkitCommissionCalculator.Rule(rule.getLevel(), rule.getRateBps()));
        }
        SkitCommissionCalculator.Result distribution = calculator.calculate(gross, memberByLevel, rules);
        for (SkitCommissionCalculator.Allocation allocation : distribution.getAllocations()) {
            ledgerMapper.insert(SkitCommissionLedgerDO.builder().eventId(event.getId())
                    .beneficiaryType(BENEFICIARY_MEMBER).beneficiaryMemberId(allocation.getMemberId())
                    .levelNo(allocation.getLevelNo()).grossAmount(gross).rateBps(allocation.getRateBps())
                    .amount(allocation.getAmount()).ruleVersion(snapshot.getVersion()).status(LEDGER_ESTIMATED)
                    .entryType(LEDGER_ENTRY_LEGACY_ESTIMATE).balanceBucket(LEDGER_BALANCE_NON_SETTLEABLE)
                    .currency(LEGACY_CURRENCY_CNY).grossAmountUnits(grossAmountUnits)
                    .amountUnits(toMoneyUnits(allocation.getAmount())).amountScale(MONEY_SCALE)
                    .revisionNo(0).legacyUnverified(true).build());
        }
        ledgerMapper.insert(SkitCommissionLedgerDO.builder().eventId(event.getId())
                .beneficiaryType(BENEFICIARY_AGENT).beneficiaryMemberId(AGENT_BENEFICIARY_ID)
                .levelNo(AGENT_LEDGER_LEVEL).grossAmount(gross).rateBps(distribution.getAgentRateBps())
                .amount(distribution.getAgentAmount()).ruleVersion(snapshot.getVersion()).status(LEDGER_ESTIMATED)
                .entryType(LEDGER_ENTRY_LEGACY_ESTIMATE).balanceBucket(LEDGER_BALANCE_NON_SETTLEABLE)
                .currency(LEGACY_CURRENCY_CNY).grossAmountUnits(grossAmountUnits)
                .amountUnits(toMoneyUnits(distribution.getAgentAmount())).amountScale(MONEY_SCALE)
                .revisionNo(0).legacyUnverified(true).build());
        return buildReportResult(event, false);
    }

    @Override
    public PageResult<LedgerView> getLedgerPage(PageParam pageParam, Long beneficiaryUserId, Integer beneficiaryType,
                                                 java.time.LocalDateTime[] createTime) {
        PageResult<SkitCommissionLedgerDO> page = ledgerMapper.selectPage(
                pageParam, beneficiaryUserId, beneficiaryType, createTime);
        List<LedgerView> views = new ArrayList<>();
        for (SkitCommissionLedgerDO ledger : page.getList()) {
            LedgerView view = new LedgerView();
            view.setId(ledger.getId());
            view.setEventId(ledger.getEventId());
            view.setAdRecordId(ledger.getEventId());
            SkitAdRevenueEventDO event = eventMapper.selectById(ledger.getEventId());
            if (event != null) {
                view.setSourceMemberId(event.getSourceMemberId());
                view.setSourceUserId(event.getSourceMemberId());
                SkitMemberDO source = memberMapper.selectById(event.getSourceMemberId());
                view.setSourceNickname(source == null ? null : source.getNickname());
                view.setSourceMemberName(source == null ? null : source.getNickname());
            }
            view.setBeneficiaryType(ledger.getBeneficiaryType());
            view.setBeneficiaryUserId(ledger.getBeneficiaryMemberId());
            if (BENEFICIARY_MEMBER == ledger.getBeneficiaryType()) {
                SkitMemberDO member = memberMapper.selectById(ledger.getBeneficiaryMemberId());
                view.setBeneficiaryNickname(member == null ? null : member.getNickname());
            }
            view.setLevel(ledger.getLevelNo());
            view.setRevenueAmount(ledger.getGrossAmount());
            view.setRate(BigDecimal.valueOf(ledger.getRateBps(), 2));
            view.setCommissionAmount(ledger.getAmount());
            view.setRuleVersion(ledger.getRuleVersion());
            view.setStatus(ledger.getStatus() == LEDGER_ESTIMATED ? "ESTIMATED" : "AVAILABLE");
            view.setCreateTime(ledger.getCreateTime());
            views.add(view);
        }
        return new PageResult<>(views, page.getTotal());
    }

    private boolean insertEvent(SkitAdRevenueEventDO event) {
        try {
            eventMapper.insert(event);
            return true;
        } catch (DuplicateKeyException ex) {
            SkitAdRevenueEventDO existing = eventMapper.selectByAccountSourceAndExternalEventId(
                    event.getAdAccountId(), event.getSourceType(), event.getExternalEventId());
            if (existing == null) {
                throw ex;
            }
            return false;
        }
    }

    private void validateIdempotentEvent(SkitAdRevenueEventDO existing, Long sourceMemberId, Long adAccountId,
                                         ReportCommand command, BigDecimal gross) {
        if (existing == null
                || !Objects.equals(existing.getSourceMemberId(), sourceMemberId)
                || !Objects.equals(existing.getAdAccountId(), adAccountId)
                || !Objects.equals(existing.getPlacementId(), command.getPlacementId())
                || existing.getGrossAmount().compareTo(gross) != 0
                || !Objects.equals(existing.getCompleted(), Boolean.TRUE.equals(command.getCompleted()))
                || !Objects.equals(existing.getMock(), Boolean.TRUE.equals(command.getMock()))
                || !Objects.equals(existing.getOccurredTime(), normalizeOccurredTime(command))) {
            throw exception(REVENUE_EVENT_CONFLICT);
        }
    }

    private ReportResult buildReportResult(SkitAdRevenueEventDO event, boolean idempotent) {
        ReportResult result = new ReportResult();
        result.setEventId(event.getId());
        result.setAdRecordId(event.getId());
        result.setStatus(event.getStatus() == REVENUE_EVENT_IGNORED ? "IGNORED" : "ESTIMATED");
        result.setIdempotent(idempotent);
        if (event.getStatus() == REVENUE_EVENT_ESTIMATED) {
            BigDecimal amount = BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.DOWN);
            for (SkitCommissionLedgerDO ledger : ledgerMapper.selectList(
                    SkitCommissionLedgerDO::getEventId, event.getId())) {
                if (BENEFICIARY_MEMBER == ledger.getBeneficiaryType()
                        && Objects.equals(ledger.getBeneficiaryMemberId(), event.getSourceMemberId())) {
                    amount = amount.add(ledger.getAmount());
                }
            }
            result.setEstimatedCommissionAmount(amount);
        }
        return result;
    }

    private void validateCommand(ReportCommand command) {
        if (command == null || StrUtil.isBlank(command.getProvider()) || StrUtil.isBlank(command.getExternalEventId())
                || StrUtil.isBlank(command.getPlacementId()) || command.getOccurredTime() == null
                || command.getGrossAmount() == null || command.getCompleted() == null) {
            throw exception(REVENUE_EVENT_INVALID, "provider、externalEventId、placementId、grossAmount、occurredTime、completed 必填");
        }
        if (command.getExternalEventId().length() > 128) {
            throw exception(REVENUE_EVENT_INVALID, "externalEventId 最长 128 个字符");
        }
        if (command.getProvider().length() > 16 || command.getPlacementId().length() > 128) {
            throw exception(REVENUE_EVENT_INVALID, "provider 或 placementId 超出长度限制");
        }
        try {
            reportValidator.validateRawData(command.getRawData());
        } catch (IllegalArgumentException ex) {
            throw exception(REVENUE_EVENT_INVALID, ex.getMessage());
        }
    }

    private void validateNewEventCommand(ReportCommand command) {
        try {
            reportValidator.validateOccurredTime(command.getOccurredTime(), java.time.Instant.now());
        } catch (IllegalArgumentException ex) {
            throw exception(REVENUE_EVENT_INVALID, ex.getMessage());
        }
    }

    private String normalizeProvider(String provider) {
        if (!isSupportedProvider(provider)) {
            throw exception(AD_PROVIDER_INVALID);
        }
        return provider.trim().toUpperCase(Locale.ROOT);
    }

    private BigDecimal normalizeMoney(BigDecimal amount) {
        try {
            return reportValidator.normalizeMoney(amount);
        } catch (IllegalArgumentException ex) {
            throw exception(REVENUE_EVENT_INVALID, ex.getMessage());
        }
    }

    private LocalDateTime normalizeOccurredTime(ReportCommand command) {
        return reportValidator.normalizeOccurredTime(command.getOccurredTime());
    }

    private static long toMoneyUnits(BigDecimal amount) {
        return amount.movePointRight(MONEY_SCALE).longValueExact();
    }
}
