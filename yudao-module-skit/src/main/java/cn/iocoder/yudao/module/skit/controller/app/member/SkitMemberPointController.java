package cn.iocoder.yudao.module.skit.controller.app.member;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.ratelimiter.core.annotation.RateLimiter;
import cn.iocoder.yudao.module.skit.controller.app.member.vo.point.SkitMemberCheckInRespVO;
import cn.iocoder.yudao.module.skit.controller.app.member.vo.point.SkitMemberCheckInSummaryRespVO;
import cn.iocoder.yudao.module.skit.controller.app.member.vo.point.SkitMemberPointRecordPageReqVO;
import cn.iocoder.yudao.module.skit.controller.app.member.vo.point.SkitMemberPointRecordRespVO;
import cn.iocoder.yudao.module.skit.dal.dataobject.member.SkitMemberPointRecordDO;
import cn.iocoder.yudao.module.skit.framework.security.SkitMemberRateLimiterKeyResolver;
import cn.iocoder.yudao.module.skit.service.member.SkitMemberPointService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import javax.validation.Valid;
import java.util.List;
import java.util.stream.Collectors;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;
import static cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils.getLoginUserId;

@Tag(name = "用户 APP - 短剧会员签到与积分")
@RestController
@RequestMapping("/skit/member")
@Validated
@PreAuthorize("@ss.hasScope('skit_member') and @skitMemberSecurityGuard.isSkitMember()")
public class SkitMemberPointController {

    @Resource
    private SkitMemberPointService pointService;

    @PostMapping("/check-ins")
    @RateLimiter(time = 60, count = 10, keyResolver = SkitMemberRateLimiterKeyResolver.class)
    @Operation(summary = "当前会员签到并固定获得 1 积分")
    public CommonResult<SkitMemberCheckInRespVO> checkIn() {
        return success(SkitMemberCheckInRespVO.from(
                pointService.checkIn(getLoginUserId())));
    }

    @GetMapping("/check-ins/summary")
    @RateLimiter(time = 60, count = 60, keyResolver = SkitMemberRateLimiterKeyResolver.class)
    @Operation(summary = "查询当前会员签到汇总")
    public CommonResult<SkitMemberCheckInSummaryRespVO> getCheckInSummary() {
        return success(SkitMemberCheckInSummaryRespVO.from(
                pointService.getCheckInSummary(getLoginUserId())));
    }

    @GetMapping("/point-records")
    @RateLimiter(time = 60, count = 60, keyResolver = SkitMemberRateLimiterKeyResolver.class)
    @Operation(summary = "分页查询当前会员自己的积分记录")
    public CommonResult<PageResult<SkitMemberPointRecordRespVO>> getPointRecords(
            @Valid SkitMemberPointRecordPageReqVO request) {
        PageResult<SkitMemberPointRecordDO> page =
                pointService.getPointRecordPage(getLoginUserId(), request);
        List<SkitMemberPointRecordRespVO> records = page.getList().stream()
                .map(SkitMemberPointRecordRespVO::from)
                .collect(Collectors.toList());
        return success(new PageResult<>(records, page.getTotal()));
    }

}
