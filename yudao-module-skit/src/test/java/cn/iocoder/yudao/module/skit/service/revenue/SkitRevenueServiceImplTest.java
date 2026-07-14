package cn.iocoder.yudao.module.skit.service.revenue;

import cn.iocoder.yudao.framework.common.enums.CommonStatusEnum;
import cn.iocoder.yudao.framework.common.exception.ErrorCode;
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
import cn.iocoder.yudao.module.skit.service.commission.SkitCommissionService;
import cn.iocoder.yudao.module.system.service.tenant.TenantService;
import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static cn.iocoder.yudao.framework.test.core.util.AssertUtils.assertServiceException;
import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.skit.enums.ErrorCodeConstants.REVENUE_EVENT_CONFLICT;
import static cn.iocoder.yudao.module.system.enums.ErrorCodeConstants.TENANT_DISABLE;
import static cn.iocoder.yudao.module.system.enums.ErrorCodeConstants.TENANT_EXPIRE;
import static cn.iocoder.yudao.module.system.enums.ErrorCodeConstants.TENANT_NOT_EXISTS;
import static cn.iocoder.yudao.module.skit.enums.SkitDomainConstants.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SkitRevenueServiceImplTest {

    private static final long TENANT_ID = 42L;
    private static final long SOURCE_MEMBER_ID = 8L;
    private static final long PARENT_MEMBER_ID = 7L;
    private static final long ACCOUNT_ID = 10L;
    private static final long EVENT_ID = 100L;
    private static final OffsetDateTime OCCURRED_AT = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1).withNano(0);
    private static final LocalDateTime OCCURRED_TIME = OCCURRED_AT.toLocalDateTime();

    @InjectMocks
    private SkitRevenueServiceImpl revenueService;

    @Mock
    private SkitAdRevenueEventMapper eventMapper;
    @Mock
    private SkitCommissionLedgerMapper ledgerMapper;
    @Mock
    private SkitAdAccountMapper accountMapper;
    @Mock
    private SkitMemberMapper memberMapper;
    @Mock
    private SkitMemberClosureMapper closureMapper;
    @Mock
    private SkitAdAccountService adAccountService;
    @Mock
    private SkitCommissionService commissionService;
    @Mock
    private TenantService tenantService;

    @BeforeEach
    void setTenantContext() {
        TenantContextHolder.setTenantId(TENANT_ID);
    }

    @AfterEach
    void clearTenantContext() {
        TenantContextHolder.clear();
    }

    @Test
    void reportCreatesVersionedMemberAndAgentLedgers() {
        mockCurrentMemberAndAccount();
        when(eventMapper.selectByAccountSourceAndExternalEventId(
                ACCOUNT_ID, REVENUE_SOURCE_LEGACY_CLIENT, "event-100")).thenReturn(null);
        when(adAccountService.getEnabledPublicConfig(PROVIDER_PANGLE)).thenReturn(publicConfig());
        when(commissionService.getActiveSnapshot()).thenReturn(new SkitCommissionService.PlanSnapshot(
                3L, 5, Arrays.asList(new SkitCommissionService.RuleSnapshot(0, 2500),
                new SkitCommissionService.RuleSnapshot(1, 1000))));
        when(closureMapper.selectAncestors(SOURCE_MEMBER_ID)).thenReturn(Arrays.asList(
                SkitMemberClosureDO.builder().ancestorId(SOURCE_MEMBER_ID).descendantId(SOURCE_MEMBER_ID)
                        .distance(0).build(),
                SkitMemberClosureDO.builder().ancestorId(PARENT_MEMBER_ID).descendantId(SOURCE_MEMBER_ID)
                        .distance(1).build()));
        when(eventMapper.insert(any(SkitAdRevenueEventDO.class))).thenAnswer(invocation -> {
            SkitAdRevenueEventDO event = invocation.getArgument(0);
            assertEquals(TENANT_ID, TenantContextHolder.getTenantId());
            event.setId(EVENT_ID);
            return 1;
        });
        List<SkitCommissionLedgerDO> insertedLedgers = new ArrayList<>();
        when(ledgerMapper.insert(any(SkitCommissionLedgerDO.class))).thenAnswer(invocation -> {
            SkitCommissionLedgerDO ledger = invocation.getArgument(0);
            insertedLedgers.add(ledger);
            return 1;
        });
        doAnswer(invocation -> insertedLedgers).when(ledgerMapper)
                .selectList(any(SFunction.class), eq(EVENT_ID));

        SkitRevenueService.ReportResult result = revenueService.report(SOURCE_MEMBER_ID,
                reportCommand("event-100"));

        assertEquals(EVENT_ID, result.getEventId());
        assertEquals("ESTIMATED", result.getStatus());
        assertFalse(result.getIdempotent());
        assertMoney("2.50000000", result.getEstimatedCommissionAmount());
        assertEquals(3, insertedLedgers.size());
        ArgumentCaptor<SkitAdRevenueEventDO> eventCaptor = ArgumentCaptor.forClass(SkitAdRevenueEventDO.class);
        verify(eventMapper).insert(eventCaptor.capture());
        assertLegacyEventQuarantine(eventCaptor.getValue(), 1_000_000_000L);
        assertLedger(insertedLedgers.get(0), BENEFICIARY_MEMBER, SOURCE_MEMBER_ID, 0,
                2500, "2.50000000", 5);
        assertLedger(insertedLedgers.get(1), BENEFICIARY_MEMBER, PARENT_MEMBER_ID, 1,
                1000, "1.00000000", 5);
        assertLedger(insertedLedgers.get(2), BENEFICIARY_AGENT, AGENT_BENEFICIARY_ID, AGENT_LEDGER_LEVEL,
                6500, "6.50000000", 5);
    }

    @Test
    void legacyIgnoredRowIsAlsoUnverifiedAndPermanentlyNonsettleable() {
        mockCurrentMemberAndAccount();
        when(eventMapper.selectByAccountSourceAndExternalEventId(
                ACCOUNT_ID, REVENUE_SOURCE_LEGACY_CLIENT, "event-ignored")).thenReturn(null);
        when(adAccountService.getEnabledPublicConfig(PROVIDER_PANGLE)).thenReturn(publicConfig());
        when(eventMapper.insert(any(SkitAdRevenueEventDO.class))).thenAnswer(invocation -> {
            SkitAdRevenueEventDO event = invocation.getArgument(0);
            event.setId(EVENT_ID);
            return 1;
        });
        SkitRevenueService.ReportCommand command = reportCommand("event-ignored");
        command.setCompleted(false);

        SkitRevenueService.ReportResult result = revenueService.report(SOURCE_MEMBER_ID, command);

        assertEquals("IGNORED", result.getStatus());
        ArgumentCaptor<SkitAdRevenueEventDO> eventCaptor = ArgumentCaptor.forClass(SkitAdRevenueEventDO.class);
        verify(eventMapper).insert(eventCaptor.capture());
        assertLegacyEventQuarantine(eventCaptor.getValue(), 1_000_000_000L);
        verifyNoInteractions(ledgerMapper, closureMapper, commissionService);
    }

    @Test
    void replayOfSameEventReturnsExistingLedgerWithoutCreatingAnotherDistribution() {
        mockCurrentMemberAndAccount();
        SkitAdRevenueEventDO existing = existingEvent(ACCOUNT_ID);
        when(eventMapper.selectByAccountSourceAndExternalEventId(
                ACCOUNT_ID, REVENUE_SOURCE_LEGACY_CLIENT, "event-100"))
                .thenReturn(existing);
        when(ledgerMapper.selectList(any(SFunction.class), eq(EVENT_ID))).thenReturn(Collections.singletonList(
                SkitCommissionLedgerDO.builder().eventId(EVENT_ID).beneficiaryType(BENEFICIARY_MEMBER)
                        .beneficiaryMemberId(SOURCE_MEMBER_ID).amount(new BigDecimal("2.50000000")).build()));

        SkitRevenueService.ReportResult result = revenueService.report(SOURCE_MEMBER_ID,
                reportCommand("event-100"));

        assertTrue(result.getIdempotent());
        assertEquals(EVENT_ID, result.getEventId());
        assertMoney("2.50000000", result.getEstimatedCommissionAmount());
        verify(eventMapper, never()).insert(any(SkitAdRevenueEventDO.class));
        verify(ledgerMapper, never()).insert(any(SkitCommissionLedgerDO.class));
        verifyNoInteractions(closureMapper, adAccountService, commissionService);
    }

    @Test
    void accountScopedLookupReturningAnotherAccountFailsClosedAsCorruptData() {
        mockCurrentMemberAndAccount();
        when(eventMapper.selectByAccountSourceAndExternalEventId(
                ACCOUNT_ID, REVENUE_SOURCE_LEGACY_CLIENT, "event-100"))
                .thenReturn(existingEvent(999L));

        assertServiceException(() -> revenueService.report(SOURCE_MEMBER_ID, reportCommand("event-100")),
                REVENUE_EVENT_CONFLICT);

        verify(eventMapper, never()).insert(any(SkitAdRevenueEventDO.class));
        verifyNoInteractions(ledgerMapper, closureMapper, adAccountService, commissionService);
    }

    @Test
    void reportRejectsDisabledExpiredAndDeletedTenantAsFirstBusinessOperationWithZeroWrites() {
        doThrow(exception(TENANT_DISABLE, "agent"),
                exception(TENANT_EXPIRE, "agent"),
                exception(TENANT_NOT_EXISTS)).when(tenantService).validTenant(TENANT_ID);

        for (ErrorCode errorCode : Arrays.asList(TENANT_DISABLE, TENANT_EXPIRE, TENANT_NOT_EXISTS)) {
            if (errorCode == TENANT_NOT_EXISTS) {
                assertServiceException(() -> revenueService.report(SOURCE_MEMBER_ID, reportCommand("event-100")),
                        errorCode);
            } else {
                assertServiceException(() -> revenueService.report(SOURCE_MEMBER_ID, reportCommand("event-100")),
                        errorCode, "agent");
            }
        }

        verify(tenantService, times(3)).validTenant(TENANT_ID);
        verifyNoInteractions(memberMapper, accountMapper, eventMapper, ledgerMapper, closureMapper,
                adAccountService, commissionService);
    }

    private void mockCurrentMemberAndAccount() {
        SkitMemberDO member = SkitMemberDO.builder().id(SOURCE_MEMBER_ID)
                .status(CommonStatusEnum.ENABLE.getStatus()).build();
        member.setTenantId(TENANT_ID);
        when(memberMapper.selectById(SOURCE_MEMBER_ID)).thenAnswer(invocation -> {
            assertEquals(TENANT_ID, TenantContextHolder.getTenantId());
            return member;
        });
        SkitAdAccountDO account = SkitAdAccountDO.builder().id(ACCOUNT_ID).provider(PROVIDER_PANGLE)
                .status(CommonStatusEnum.ENABLE.getStatus()).build();
        account.setTenantId(TENANT_ID);
        when(accountMapper.selectByProvider(PROVIDER_PANGLE)).thenAnswer(invocation -> {
            assertEquals(TENANT_ID, TenantContextHolder.getTenantId());
            return account;
        });
    }

    private SkitAdAccountService.PublicConfig publicConfig() {
        SkitAdAccountService.PublicConfig config = new SkitAdAccountService.PublicConfig();
        config.setProvider(PROVIDER_PANGLE);
        config.setPlacementId("placement-42");
        config.setEnabled(true);
        return config;
    }

    private SkitRevenueService.ReportCommand reportCommand(String eventId) {
        SkitRevenueService.ReportCommand command = new SkitRevenueService.ReportCommand();
        command.setProvider("pangle");
        command.setExternalEventId(eventId);
        command.setPlacementId("placement-42");
        command.setGrossAmount(new BigDecimal("10"));
        command.setOccurredTime(OCCURRED_AT);
        command.setCompleted(true);
        command.setMock(false);
        return command;
    }

    private SkitAdRevenueEventDO existingEvent(Long accountId) {
        return SkitAdRevenueEventDO.builder().id(EVENT_ID).adAccountId(accountId).provider(PROVIDER_PANGLE)
                .placementId("placement-42").externalEventId("event-100").sourceMemberId(SOURCE_MEMBER_ID)
                .grossAmount(new BigDecimal("10.00000000")).occurredTime(OCCURRED_TIME)
                .completed(true).mock(false).status(REVENUE_EVENT_ESTIMATED).ruleVersion(5).build();
    }

    private void assertLedger(SkitCommissionLedgerDO ledger, int beneficiaryType, Long beneficiaryId,
                              int level, int rateBps, String amount, int version) {
        assertEquals(EVENT_ID, ledger.getEventId());
        assertEquals(beneficiaryType, ledger.getBeneficiaryType());
        assertEquals(beneficiaryId, ledger.getBeneficiaryMemberId());
        assertEquals(level, ledger.getLevelNo());
        assertEquals(rateBps, ledger.getRateBps());
        assertMoney(amount, ledger.getAmount());
        assertEquals(version, ledger.getRuleVersion());
        assertEquals(LEDGER_ESTIMATED, ledger.getStatus());
        assertEquals(LEDGER_ENTRY_LEGACY_ESTIMATE, ledger.getEntryType());
        assertEquals(LEDGER_BALANCE_NON_SETTLEABLE, ledger.getBalanceBucket());
        assertEquals(LEGACY_CURRENCY_CNY, ledger.getCurrency());
        assertEquals(1_000_000_000L, ledger.getGrossAmountUnits());
        assertEquals(new BigDecimal(amount).movePointRight(MONEY_SCALE).longValueExact(),
                ledger.getAmountUnits());
        assertEquals(MONEY_SCALE, ledger.getAmountScale());
        assertEquals(0, ledger.getRevisionNo());
        assertTrue(ledger.getLegacyUnverified());
    }

    private void assertLegacyEventQuarantine(SkitAdRevenueEventDO event, long amountUnits) {
        assertEquals(REVENUE_SOURCE_LEGACY_CLIENT, event.getSourceType());
        assertEquals(amountUnits, event.getSourceAmountUnits());
        assertEquals(amountUnits, event.getEstimatedAmountUnits());
        assertEquals(0L, event.getReconciledAmountUnits());
        assertEquals(MONEY_SCALE, event.getAmountScale());
        assertEquals(LEGACY_CURRENCY_CNY, event.getSourceCurrency());
        assertEquals(REVENUE_MATCH_LEGACY_UNMATCHED, event.getMatchStatus());
        assertEquals(REVENUE_VERIFICATION_LEGACY_UNVERIFIED, event.getSourceVerificationStatus());
        assertEquals(REWARD_QUALIFICATION_NOT_APPLICABLE, event.getRewardQualificationStatus());
        assertEquals(REVENUE_RECONCILIATION_NON_SETTLEABLE, event.getReconciliationStatus());
        assertEquals(0, event.getVersion());
        assertTrue(event.getLegacyUnverified());
    }

    private void assertMoney(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual));
    }
}
