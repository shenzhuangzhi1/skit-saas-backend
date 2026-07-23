package cn.iocoder.yudao.module.skit.controller.app.member;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.ratelimiter.core.annotation.RateLimiter;
import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.module.skit.controller.app.member.vo.point.SkitMemberCheckInRespVO;
import cn.iocoder.yudao.module.skit.controller.app.member.vo.point.SkitMemberCheckInSummaryRespVO;
import cn.iocoder.yudao.module.skit.controller.app.member.vo.point.SkitMemberPointRecordPageReqVO;
import cn.iocoder.yudao.module.skit.controller.app.member.vo.point.SkitMemberPointRecordRespVO;
import cn.iocoder.yudao.module.skit.dal.dataobject.member.SkitMemberPointRecordDO;
import cn.iocoder.yudao.module.skit.framework.security.SkitMemberRateLimiterKeyResolver;
import cn.iocoder.yudao.module.skit.service.member.SkitMemberPointService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SkitMemberPointControllerTest {

    private static final long MEMBER_ID = 81L;
    private static final String MEMBER_GUARD =
            "@ss.hasScope('skit_member') and @skitMemberSecurityGuard.isSkitMember()";

    @InjectMocks
    private SkitMemberPointController controller;
    @Mock
    private SkitMemberPointService pointService;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void routesAreMemberGuardedAndDoNotAcceptMemberIdentity() throws Exception {
        RequestMapping root = SkitMemberPointController.class.getAnnotation(RequestMapping.class);
        assertNotNull(root);
        assertEquals(Collections.singletonList("/skit/member"),
                java.util.Arrays.asList(root.value()));
        PreAuthorize guard = SkitMemberPointController.class.getAnnotation(PreAuthorize.class);
        assertNotNull(guard);
        assertEquals(MEMBER_GUARD, guard.value());

        Method checkIn = SkitMemberPointController.class.getMethod("checkIn");
        assertEquals(Collections.singletonList("/check-ins"),
                java.util.Arrays.asList(checkIn.getAnnotation(PostMapping.class).value()));
        RateLimiter limiter = checkIn.getAnnotation(RateLimiter.class);
        assertNotNull(limiter);
        assertEquals(SkitMemberRateLimiterKeyResolver.class, limiter.keyResolver());

        Method summary = SkitMemberPointController.class.getMethod("getCheckInSummary");
        assertEquals(Collections.singletonList("/check-ins/summary"),
                java.util.Arrays.asList(summary.getAnnotation(GetMapping.class).value()));
        Method records = SkitMemberPointController.class.getMethod(
                "getPointRecords", SkitMemberPointRecordPageReqVO.class);
        assertEquals(Collections.singletonList("/point-records"),
                java.util.Arrays.asList(records.getAnnotation(GetMapping.class).value()));
    }

    @Test
    void endpointsDeriveTheMemberFromTheAuthenticatedPrincipal() {
        authenticate();
        SkitMemberPointService.CheckInResult checkIn = new SkitMemberPointService.CheckInResult();
        checkIn.setSignInDate(LocalDate.of(2026, 7, 24));
        checkIn.setAwardedPoints(1);
        checkIn.setPointBalance(6);
        checkIn.setContinuousDay(3);
        checkIn.setTotalDay(9);
        when(pointService.checkIn(MEMBER_ID)).thenReturn(checkIn);
        SkitMemberPointService.CheckInSummary summary = new SkitMemberPointService.CheckInSummary();
        summary.setTodaySignIn(true);
        summary.setContinuousDay(3);
        summary.setTotalDay(9);
        summary.setPointBalance(6);
        when(pointService.getCheckInSummary(MEMBER_ID)).thenReturn(summary);

        CommonResult<SkitMemberCheckInRespVO> checkInResult = controller.checkIn();
        CommonResult<SkitMemberCheckInSummaryRespVO> summaryResult =
                controller.getCheckInSummary();

        assertEquals(LocalDate.of(2026, 7, 24), checkInResult.getData().getSignInDate());
        assertEquals(1, checkInResult.getData().getAwardedPoints());
        assertEquals(6, checkInResult.getData().getPointBalance());
        assertEquals(3, checkInResult.getData().getContinuousDay());
        assertEquals(9, checkInResult.getData().getTotalDay());
        assertEquals(true, summaryResult.getData().getTodaySignIn());
        assertEquals(6, summaryResult.getData().getPointBalance());
        verify(pointService).checkIn(MEMBER_ID);
        verify(pointService).getCheckInSummary(MEMBER_ID);
    }

    @Test
    void pointRecordsReturnOnlyTheAuthenticatedMembersPageWithRequiredFields() {
        authenticate();
        SkitMemberPointRecordPageReqVO request = new SkitMemberPointRecordPageReqVO();
        request.setAddStatus(true);
        request.setCreateTime(new LocalDateTime[]{
                LocalDateTime.of(2026, 7, 1, 0, 0),
                LocalDateTime.of(2026, 7, 31, 23, 59, 59)
        });
        SkitMemberPointRecordDO record = SkitMemberPointRecordDO.builder()
                .id(501L)
                .memberId(MEMBER_ID)
                .title("签到")
                .description("签到获得 1 积分")
                .pointDelta(1)
                .balanceAfter(6)
                .build();
        record.setCreateTime(LocalDateTime.of(2026, 7, 24, 9, 0));
        when(pointService.getPointRecordPage(MEMBER_ID, request))
                .thenReturn(new PageResult<>(Collections.singletonList(record), 1L));

        CommonResult<PageResult<SkitMemberPointRecordRespVO>> result =
                controller.getPointRecords(request);

        assertEquals(1L, result.getData().getTotal());
        SkitMemberPointRecordRespVO item = result.getData().getList().get(0);
        assertEquals(501L, item.getId());
        assertEquals("签到", item.getTitle());
        assertEquals("签到获得 1 积分", item.getDescription());
        assertEquals(1, item.getPointDelta());
        assertEquals(6, item.getBalanceAfter());
        assertEquals(LocalDateTime.of(2026, 7, 24, 9, 0), item.getCreateTime());
        verify(pointService).getPointRecordPage(MEMBER_ID, request);
    }

    private static void authenticate() {
        LoginUser principal = new LoginUser();
        principal.setId(MEMBER_ID);
        principal.setTenantId(42L);
        principal.setScopes(Collections.singletonList("skit_member"));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        principal, null, Collections.emptyList()));
    }

}
