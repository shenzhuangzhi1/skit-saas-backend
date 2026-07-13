package cn.iocoder.yudao.module.skit.controller.admin.tenant;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.apilog.core.annotation.ApiAccessLog;
import cn.iocoder.yudao.module.skit.controller.admin.tenant.vo.*;
import cn.iocoder.yudao.module.skit.service.agent.SkitAgentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import javax.validation.Valid;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@Tag(name = "管理后台 - 短剧代理商")
@RestController
@RequestMapping("/skit/tenant/agent")
@Validated
@PreAuthorize("@ss.hasRole('super_admin')")
public class SkitAgentController {

    @Resource
    private SkitAgentService agentService;

    @GetMapping("/page")
    @Operation(summary = "分页查询代理商")
    public CommonResult<PageResult<SkitAgentRespVO>> page(@Valid SkitAgentPageReqVO reqVO) {
        return success(agentService.getAgentPage(reqVO));
    }

    @GetMapping("/get")
    @Operation(summary = "获得代理商详情")
    public CommonResult<SkitAgentRespVO> get(@RequestParam("tenantId") Long tenantId) {
        return success(agentService.getAgent(tenantId));
    }

    @PostMapping("/create")
    @ApiAccessLog(sanitizeKeys = {"password", "pangleAppSecret", "takuAppKey", "takuAppSecret"})
    @Operation(summary = "创建代理商")
    public CommonResult<Long> create(@Valid @RequestBody SkitAgentCreateReqVO reqVO) {
        return success(agentService.createAgent(reqVO));
    }

    @PutMapping("/update")
    @ApiAccessLog(sanitizeKeys = {"pangleAppSecret", "takuAppKey", "takuAppSecret"})
    @Operation(summary = "更新代理商")
    public CommonResult<Boolean> update(@Valid @RequestBody SkitAgentUpdateReqVO reqVO) {
        agentService.updateAgent(reqVO);
        return success(true);
    }

    @PutMapping("/update-mobile")
    @Operation(summary = "换绑代理商管理员手机号")
    public CommonResult<Boolean> updateMobile(@Valid @RequestBody SkitAgentMobileUpdateReqVO reqVO) {
        agentService.updateAgentMobile(reqVO);
        return success(true);
    }

    @PutMapping("/reset-password")
    @ApiAccessLog(sanitizeKeys = {"password"})
    @Operation(summary = "重置代理商管理员密码")
    public CommonResult<Boolean> resetPassword(@Valid @RequestBody SkitAgentPasswordResetReqVO reqVO) {
        agentService.resetAgentPassword(reqVO);
        return success(true);
    }

    @PutMapping("/archive")
    @Operation(summary = "归档代理商")
    public CommonResult<Boolean> archive(@RequestParam("tenantId") Long tenantId) {
        agentService.archiveAgent(tenantId);
        return success(true);
    }

    @PutMapping("/restore")
    @Operation(summary = "恢复代理商")
    public CommonResult<Boolean> restore(@RequestParam("tenantId") Long tenantId) {
        agentService.restoreAgent(tenantId);
        return success(true);
    }

    @PutMapping("/rotate-root-invite")
    @Operation(summary = "轮换代理商根邀请码")
    public CommonResult<String> rotateRootInvite(@RequestParam("tenantId") Long tenantId) {
        return success(agentService.rotateRootInviteCode(tenantId));
    }
}
