package cn.iocoder.yudao.module.skit.service.member;

import cn.iocoder.yudao.framework.common.enums.CommonStatusEnum;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.skit.controller.app.member.vo.point.SkitMemberPointRecordPageReqVO;
import cn.iocoder.yudao.module.skit.dal.dataobject.member.SkitMemberDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.member.SkitMemberPointRecordDO;
import cn.iocoder.yudao.module.skit.dal.mysql.member.SkitMemberMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.member.SkitMemberPointRecordMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Collections;

import static cn.iocoder.yudao.framework.test.core.util.AssertUtils.assertServiceException;
import static cn.iocoder.yudao.module.skit.enums.ErrorCodeConstants.CHECK_IN_ALREADY_EXISTS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SkitMemberPointServiceImplTest {

    private static final long TENANT_ID = 42L;
    private static final long MEMBER_ID = 81L;
    private static final ZoneId BEIJING = ZoneId.of("Asia/Shanghai");

    private final SkitMemberMapper memberMapper = mock(SkitMemberMapper.class);
    private final SkitMemberPointRecordMapper pointRecordMapper = mock(SkitMemberPointRecordMapper.class);

    @AfterEach
    void clearTenantContext() {
        TenantContextHolder.clear();
    }

    @Test
    void firstCheckInAwardsExactlyOnePoint() {
        TenantContextHolder.setTenantId(TENANT_ID);
        SkitMemberPointServiceImpl service = serviceAt("2026-07-24T00:15:00+08:00");
        when(memberMapper.selectByTenantAndIdForUpdate(TENANT_ID, MEMBER_ID))
                .thenReturn(enabledMember(0));
        when(pointRecordMapper.selectByBusiness(
                TENANT_ID, MEMBER_ID, "CHECK_IN", "2026-07-24")).thenReturn(null);
        when(memberMapper.updatePointBalance(TENANT_ID, MEMBER_ID, 1)).thenReturn(1);
        when(pointRecordMapper.insert(any(SkitMemberPointRecordDO.class))).thenAnswer(invocation -> {
            invocation.<SkitMemberPointRecordDO>getArgument(0).setId(501L);
            return 1;
        });
        when(pointRecordMapper.selectCountByBizType(TENANT_ID, MEMBER_ID, "CHECK_IN"))
                .thenReturn(1L);
        when(pointRecordMapper.selectBizIdsByType(TENANT_ID, MEMBER_ID, "CHECK_IN"))
                .thenReturn(Collections.singletonList("2026-07-24"));

        SkitMemberPointService.CheckInResult result = service.checkIn(MEMBER_ID);

        assertEquals("2026-07-24", result.getSignInDate().toString());
        assertEquals(1, result.getAwardedPoints());
        assertEquals(1, result.getPointBalance());
        assertEquals(1, result.getContinuousDay());
        assertEquals(1, result.getTotalDay());
        ArgumentCaptor<SkitMemberPointRecordDO> captor =
                ArgumentCaptor.forClass(SkitMemberPointRecordDO.class);
        verify(pointRecordMapper).insert(captor.capture());
        SkitMemberPointRecordDO record = captor.getValue();
        assertEquals(TENANT_ID, record.getTenantId());
        assertEquals(MEMBER_ID, record.getMemberId());
        assertEquals("CHECK_IN", record.getBizType());
        assertEquals("2026-07-24", record.getBizId());
        assertEquals("签到", record.getTitle());
        assertEquals("签到获得 1 积分", record.getDescription());
        assertEquals(1, record.getPointDelta());
        assertEquals(1, record.getBalanceAfter());
    }

    @Test
    void duplicateCheckInDoesNotAwardAgain() {
        TenantContextHolder.setTenantId(TENANT_ID);
        SkitMemberPointServiceImpl service = serviceAt("2026-07-24T21:00:00+08:00");
        when(memberMapper.selectByTenantAndIdForUpdate(TENANT_ID, MEMBER_ID))
                .thenReturn(enabledMember(7));
        when(pointRecordMapper.selectByBusiness(
                TENANT_ID, MEMBER_ID, "CHECK_IN", "2026-07-24"))
                .thenReturn(pointRecord("2026-07-24", 7));

        assertServiceException(() -> service.checkIn(MEMBER_ID), CHECK_IN_ALREADY_EXISTS);

        verify(memberMapper, never()).updatePointBalance(TENANT_ID, MEMBER_ID, 8);
        verify(pointRecordMapper, never()).insert(any(SkitMemberPointRecordDO.class));
    }

    @Test
    void nextBeijingDayAwardsOneMorePointAndExtendsTheStreak() {
        TenantContextHolder.setTenantId(TENANT_ID);
        SkitMemberPointServiceImpl service = serviceAt("2026-07-25T00:01:00+08:00");
        when(memberMapper.selectByTenantAndIdForUpdate(TENANT_ID, MEMBER_ID))
                .thenReturn(enabledMember(1));
        when(pointRecordMapper.selectByBusiness(
                TENANT_ID, MEMBER_ID, "CHECK_IN", "2026-07-25")).thenReturn(null);
        when(memberMapper.updatePointBalance(TENANT_ID, MEMBER_ID, 2)).thenReturn(1);
        when(pointRecordMapper.insert(any(SkitMemberPointRecordDO.class))).thenReturn(1);
        when(pointRecordMapper.selectCountByBizType(TENANT_ID, MEMBER_ID, "CHECK_IN"))
                .thenReturn(2L);
        when(pointRecordMapper.selectBizIdsByType(TENANT_ID, MEMBER_ID, "CHECK_IN"))
                .thenReturn(Arrays.asList("2026-07-25", "2026-07-24"));

        SkitMemberPointService.CheckInResult result = service.checkIn(MEMBER_ID);

        assertEquals("2026-07-25", result.getSignInDate().toString());
        assertEquals(1, result.getAwardedPoints());
        assertEquals(2, result.getPointBalance());
        assertEquals(2, result.getContinuousDay());
        assertEquals(2, result.getTotalDay());
    }

    @Test
    void summaryUsesTheBeijingCalendarAndCurrentMemberBalance() {
        TenantContextHolder.setTenantId(TENANT_ID);
        SkitMemberPointServiceImpl service = serviceAt("2026-07-24T00:01:00+08:00");
        when(memberMapper.selectByTenantAndId(TENANT_ID, MEMBER_ID)).thenReturn(enabledMember(9));
        when(pointRecordMapper.selectCountByBizType(TENANT_ID, MEMBER_ID, "CHECK_IN"))
                .thenReturn(3L);
        when(pointRecordMapper.selectBizIdsByType(TENANT_ID, MEMBER_ID, "CHECK_IN"))
                .thenReturn(Arrays.asList("2026-07-24", "2026-07-23", "2026-07-21"));

        SkitMemberPointService.CheckInSummary result = service.getCheckInSummary(MEMBER_ID);

        assertTrue(result.getTodaySignIn());
        assertEquals(2, result.getContinuousDay());
        assertEquals(3, result.getTotalDay());
        assertEquals(9, result.getPointBalance());
    }

    @Test
    void pointRecordPageIsAlwaysScopedToTheAuthenticatedMemberArgument() {
        TenantContextHolder.setTenantId(TENANT_ID);
        SkitMemberPointServiceImpl service = serviceAt("2026-07-24T12:00:00+08:00");
        SkitMemberPointRecordPageReqVO pageParam = new SkitMemberPointRecordPageReqVO();
        pageParam.setPageNo(2);
        pageParam.setPageSize(5);
        pageParam.setAddStatus(true);
        pageParam.setCreateTime(new java.time.LocalDateTime[]{
                java.time.LocalDateTime.of(2026, 7, 1, 0, 0),
                java.time.LocalDateTime.of(2026, 7, 31, 23, 59, 59)
        });
        SkitMemberPointRecordDO record = pointRecord("2026-07-24", 4);
        when(pointRecordMapper.selectPage(MEMBER_ID, pageParam))
                .thenReturn(new PageResult<>(Collections.singletonList(record), 1L));

        PageResult<SkitMemberPointRecordDO> result =
                service.getPointRecordPage(MEMBER_ID, pageParam);

        assertEquals(1L, result.getTotal());
        assertEquals(Collections.singletonList(record), result.getList());
        verify(pointRecordMapper).selectPage(MEMBER_ID, pageParam);
    }

    private SkitMemberPointServiceImpl serviceAt(String timestamp) {
        Clock clock = Clock.fixed(
                Instant.from(java.time.OffsetDateTime.parse(timestamp)), BEIJING);
        return new SkitMemberPointServiceImpl(memberMapper, pointRecordMapper, clock);
    }

    private SkitMemberDO enabledMember(int pointBalance) {
        SkitMemberDO member = SkitMemberDO.builder()
                .id(MEMBER_ID)
                .mobile("13800000000")
                .password("encoded")
                .nickname("member")
                .inviteCode("INVITE81")
                .depth(0)
                .status(CommonStatusEnum.ENABLE.getStatus())
                .pointBalance(pointBalance)
                .build();
        member.setTenantId(TENANT_ID);
        return member;
    }

    private SkitMemberPointRecordDO pointRecord(String bizId, int balanceAfter) {
        SkitMemberPointRecordDO record = SkitMemberPointRecordDO.builder()
                .id(501L)
                .memberId(MEMBER_ID)
                .bizType("CHECK_IN")
                .bizId(bizId)
                .title("签到")
                .description("签到获得 1 积分")
                .pointDelta(1)
                .balanceAfter(balanceAfter)
                .build();
        record.setTenantId(TENANT_ID);
        return record;
    }

}
