package cn.iocoder.yudao.module.skit.controller.admin.config;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.tenant.core.aop.TenantIgnore;
import cn.iocoder.yudao.module.skit.service.config.SkitSystemConfigService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import javax.annotation.security.PermitAll;
import java.util.Map;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@Tag(name = "管理后台 - 短剧 SaaS 系统配置")
@RestController
@RequestMapping("/skit/general/config")
@Validated
@TenantIgnore
public class SkitSystemConfigController {

    @Resource
    private SkitSystemConfigService skitSystemConfigService;

    @GetMapping
    @Operation(summary = "获得短剧系统配置")
    @PermitAll
    public CommonResult<Map<String, Object>> getConfig() {
        return success(skitSystemConfigService.getConfig());
    }

    @PutMapping
    @Operation(summary = "更新短剧系统配置")
    @PermitAll
    public CommonResult<Boolean> updateConfig(@RequestBody Map<String, Object> config) {
        skitSystemConfigService.updateConfig(config);
        return success(true);
    }

    @PostMapping("/reset")
    @Operation(summary = "重置短剧系统配置")
    @PermitAll
    public CommonResult<Map<String, Object>> resetConfig() {
        return success(skitSystemConfigService.resetConfig());
    }

}
