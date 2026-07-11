package cn.iocoder.yudao.module.skit.controller.admin.tenant;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.apilog.core.annotation.ApiAccessLog;
import cn.iocoder.yudao.module.skit.controller.admin.tenant.vo.SkitAgentPageReqVO;
import cn.iocoder.yudao.module.skit.controller.admin.tenant.vo.SkitAgentRespVO;
import cn.iocoder.yudao.module.skit.controller.admin.tenant.vo.SkitAgentSaveReqVO;
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
    @ApiAccessLog(sanitizeKeys = {"pangleAppSecret", "takuAppKey", "takuAppSecret"})
    @Operation(summary = "创建代理商")
    public CommonResult<Long> create(@Valid @RequestBody SkitAgentSaveReqVO reqVO) {
        return success(agentService.createAgent(reqVO));
    }

    @PutMapping("/update")
    @ApiAccessLog(sanitizeKeys = {"pangleAppSecret", "takuAppKey", "takuAppSecret"})
    @Operation(summary = "更新代理商")
    public CommonResult<Boolean> update(@Valid @RequestBody SkitAgentSaveReqVO reqVO) {
        agentService.updateAgent(reqVO);
        return success(true);
    }
}
