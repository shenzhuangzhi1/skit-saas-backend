package cn.iocoder.yudao.module.skit.controller.app.release;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.ratelimiter.core.annotation.RateLimiter;
import cn.iocoder.yudao.framework.tenant.core.aop.TenantIgnore;
import cn.iocoder.yudao.module.skit.framework.security.SkitClientIpRateLimiterKeyResolver;
import cn.iocoder.yudao.module.skit.service.app.SkitAppReleaseService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.hibernate.validator.constraints.Length;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import javax.annotation.security.PermitAll;
import javax.validation.constraints.NotBlank;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@Tag(name = "用户 APP - 热更新发布清单")
@RestController
@RequestMapping("/skit/app/release")
@Validated
@TenantIgnore
public class SkitAppReleaseController {

    @Resource
    private SkitAppReleaseService releaseService;

    @GetMapping("/current")
    @PermitAll
    @RateLimiter(time = 60, count = 30, keyResolver = SkitClientIpRateLimiterKeyResolver.class)
    @Operation(summary = "获得当前代理商白标 App 的兼容热更新清单")
    public CommonResult<SkitAppReleaseService.Manifest> current(
            @RequestParam("profileCode") @NotBlank @Length(max = 32) String profileCode,
            @RequestParam("nativeVersion") @NotBlank @Length(max = 32) String nativeVersion) {
        return success(releaseService.current(profileCode, nativeVersion));
    }

}
