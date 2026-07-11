package cn.iocoder.yudao.module.skit.controller.app.member;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.apilog.core.annotation.ApiAccessLog;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.util.servlet.ServletUtils;
import cn.iocoder.yudao.framework.security.config.SecurityProperties;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.framework.tenant.core.aop.TenantIgnore;
import cn.iocoder.yudao.framework.ratelimiter.core.annotation.RateLimiter;
import cn.iocoder.yudao.module.skit.framework.security.SkitClientIpRateLimiterKeyResolver;
import cn.iocoder.yudao.module.skit.service.member.SkitMemberService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import org.hibernate.validator.constraints.Length;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import javax.annotation.security.PermitAll;
import javax.servlet.http.HttpServletRequest;
import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Pattern;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@Tag(name = "用户 APP - 短剧会员认证")
@RestController
@RequestMapping("/skit/member/auth")
@Validated
@TenantIgnore
public class SkitMemberAuthController {

    @Resource
    private SkitMemberService memberService;
    @Resource
    private SecurityProperties securityProperties;

    @PostMapping("/register")
    @ApiAccessLog(sanitizeKeys = {"password"})
    @PermitAll
    @RateLimiter(time = 60, count = 20, keyResolver = SkitClientIpRateLimiterKeyResolver.class,
            message = "注册请求过于频繁，请稍后再试")
    @Operation(summary = "使用邀请码注册并登录")
    public CommonResult<SkitMemberService.AuthResult> register(@Valid @RequestBody RegisterReqVO reqVO) {
        SkitMemberService.RegisterCommand command = new SkitMemberService.RegisterCommand();
        command.setMobile(reqVO.getMobile());
        command.setPassword(reqVO.getPassword());
        command.setNickname(reqVO.getNickname());
        command.setInviteCode(reqVO.getInviteCode());
        command.setRegisterIp(ServletUtils.getClientIP());
        return success(memberService.register(command));
    }

    @PostMapping("/login")
    @ApiAccessLog(sanitizeKeys = {"password"})
    @PermitAll
    @RateLimiter(time = 60, count = 10, keyResolver = SkitClientIpRateLimiterKeyResolver.class,
            message = "登录请求过于频繁，请稍后再试")
    @Operation(summary = "手机号密码登录")
    public CommonResult<SkitMemberService.AuthResult> login(@Valid @RequestBody LoginReqVO reqVO) {
        SkitMemberService.LoginCommand command = new SkitMemberService.LoginCommand();
        command.setMobile(reqVO.getMobile());
        command.setPassword(reqVO.getPassword());
        command.setTenantCode(reqVO.getTenantCode());
        command.setLoginIp(ServletUtils.getClientIP());
        return success(memberService.login(command));
    }

    @PostMapping("/refresh-token")
    @ApiAccessLog(sanitizeKeys = {"refreshToken"})
    @PermitAll
    @RateLimiter(time = 60, count = 30, keyResolver = SkitClientIpRateLimiterKeyResolver.class)
    @Operation(summary = "刷新会员令牌")
    public CommonResult<SkitMemberService.AuthResult> refreshToken(
            @RequestParam("refreshToken") String refreshToken) {
        return success(memberService.refreshToken(refreshToken));
    }

    @PostMapping("/logout")
    @PermitAll
    @Operation(summary = "退出登录")
    public CommonResult<Boolean> logout(HttpServletRequest request) {
        String token = SecurityFrameworkUtils.obtainAuthorization(request,
                securityProperties.getTokenHeader(), securityProperties.getTokenParameter());
        if (StrUtil.isNotBlank(token)) {
            memberService.logout(token);
        }
        return success(true);
    }

    @Data
    public static class RegisterReqVO {
        @NotBlank
        @Pattern(regexp = "^1\\d{10}$", message = "手机号格式不正确")
        private String mobile;
        @NotBlank
        @Length(min = 6, max = 32, message = "密码长度为 6 到 32 位")
        private String password;
        @NotBlank
        @Length(max = 64)
        private String nickname;
        @NotBlank
        @Length(max = 32)
        private String inviteCode;
    }

    @Data
    public static class LoginReqVO {
        @NotBlank
        @Pattern(regexp = "^1\\d{10}$", message = "手机号格式不正确")
        private String mobile;
        @NotBlank
        private String password;
        @Length(max = 32)
        private String tenantCode;
    }
}
