package cn.iocoder.yudao.module.skit.controller.app.member;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageParam;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.tenant.core.aop.TenantIgnore;
import cn.iocoder.yudao.framework.ratelimiter.core.annotation.RateLimiter;
import cn.iocoder.yudao.module.skit.framework.security.SkitClientIpRateLimiterKeyResolver;
import cn.iocoder.yudao.module.skit.service.member.SkitMemberService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.validation.annotation.Validated;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import javax.annotation.security.PermitAll;
import javax.validation.Valid;
import javax.validation.constraints.NotBlank;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;
import static cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils.getLoginUserId;

@Tag(name = "用户 APP - 短剧会员与邀请")
@RestController
@RequestMapping("/skit/member")
@Validated
public class SkitMemberUserController {

    @Resource
    private SkitMemberService memberService;

    @GetMapping("/user/profile")
    @PreAuthorize("@ss.hasScope('skit_member') and @skitMemberSecurityGuard.isSkitMember()")
    @Operation(summary = "获得当前会员资料")
    public CommonResult<SkitMemberService.ProfileView> profile() {
        return success(memberService.getProfile(getLoginUserId()));
    }

    @GetMapping("/invitation/resolve")
    @PermitAll
    @TenantIgnore
    @RateLimiter(time = 60, count = 60, keyResolver = SkitClientIpRateLimiterKeyResolver.class)
    @Operation(summary = "公开解析邀请码")
    public CommonResult<SkitMemberService.InvitationView> resolveInvitation(
            @RequestParam("code") @NotBlank String code) {
        return success(memberService.resolveInvitation(code));
    }

    @GetMapping("/invitation/children")
    @PreAuthorize("@ss.hasScope('skit_member') and @skitMemberSecurityGuard.isSkitMember()")
    @Operation(summary = "获得直属下级")
    public CommonResult<PageResult<SkitMemberService.MemberView>> children(@Valid PageParam pageParam) {
        return success(memberService.getChildren(getLoginUserId(), pageParam));
    }
}
