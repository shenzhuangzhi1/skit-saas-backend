package cn.iocoder.yudao.module.skit.service.revenue;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.skit.dal.dataobject.member.SkitMemberDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.revenue.SkitAdRevenueEventDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.revenue.SkitCommissionLedgerDO;
import cn.iocoder.yudao.module.skit.dal.mysql.member.SkitMemberMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.revenue.SkitAdRevenueEventMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.revenue.SkitCommissionLedgerMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;

import static cn.iocoder.yudao.module.skit.enums.SkitDomainConstants.BENEFICIARY_MEMBER;
import static cn.iocoder.yudao.module.skit.enums.SkitDomainConstants.LEDGER_ESTIMATED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SkitRevenueServiceImplTest {

    @InjectMocks
    private SkitRevenueServiceImpl revenueService;

    @Mock
    private SkitAdRevenueEventMapper eventMapper;
    @Mock
    private SkitCommissionLedgerMapper ledgerMapper;
    @Mock
    private SkitMemberMapper memberMapper;

    @Test
    void clientTrustedReportCapabilityIsAbsentFromServiceSurface() {
        assertFalse(Arrays.stream(SkitRevenueService.class.getDeclaredMethods())
                .anyMatch(method -> method.getName().equals("report")));
        assertFalse(Arrays.stream(SkitRevenueServiceImpl.class.getDeclaredMethods())
                .anyMatch(method -> method.getName().equals("report")));
        assertFalse(Arrays.stream(SkitRevenueService.class.getDeclaredClasses())
                .anyMatch(type -> type.getSimpleName().equals("ReportCommand")
                        || type.getSimpleName().equals("ReportResult")));
    }

    @Test
    void ledgerReadProjectionRemainsAvailableAfterLegacyWriterRemoval() throws Exception {
        PageParam pageParam = new PageParam();
        SkitCommissionLedgerDO ledger = SkitCommissionLedgerDO.builder()
                .id(11L).eventId(21L).beneficiaryType(BENEFICIARY_MEMBER).beneficiaryMemberId(31L)
                .levelNo(1).grossAmount(new BigDecimal("10.00000000")).rateBps(2500)
                .amount(new BigDecimal("2.50000000")).ruleVersion(7).status(LEDGER_ESTIMATED).build();
        SkitAdRevenueEventDO event = SkitAdRevenueEventDO.builder()
                .id(21L).sourceMemberId(41L).build();
        SkitMemberDO source = SkitMemberDO.builder().id(41L).nickname("source").build();
        SkitMemberDO beneficiary = SkitMemberDO.builder().id(31L).nickname("beneficiary").build();
        when(ledgerMapper.selectPage(pageParam, null, null, null))
                .thenReturn(new PageResult<>(Collections.singletonList(ledger), 1L));
        when(eventMapper.selectById(21L)).thenReturn(event);
        when(memberMapper.selectById(41L)).thenReturn(source);
        when(memberMapper.selectById(31L)).thenReturn(beneficiary);

        PageResult<SkitRevenueService.LedgerView> result = revenueService.getLedgerPage(pageParam, null, null, null);

        assertEquals(1L, result.getTotal());
        assertEquals(1, result.getList().size());
        SkitRevenueService.LedgerView view = result.getList().get(0);
        assertEquals(21L, view.getEventId());
        assertEquals(41L, view.getSourceMemberId());
        assertEquals("source", view.getSourceNickname());
        assertEquals(31L, view.getBeneficiaryUserId());
        assertEquals("beneficiary", view.getBeneficiaryNickname());
        assertEquals("ESTIMATED", view.getStatus());
        assertEquals(LocalDateTime.class, SkitRevenueService.LedgerView.class
                .getDeclaredField("createTime").getType());
    }

}
