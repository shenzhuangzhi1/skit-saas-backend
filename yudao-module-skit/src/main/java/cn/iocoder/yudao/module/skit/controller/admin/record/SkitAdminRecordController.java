package cn.iocoder.yudao.module.skit.controller.admin.record;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.skit.controller.admin.record.vo.SkitAdminRecordPageReqVO;
import cn.iocoder.yudao.module.skit.controller.admin.record.vo.SkitAdminRecordRespVO;
import cn.iocoder.yudao.module.skit.controller.admin.record.vo.SkitAdminRecordSaveReqVO;
import cn.iocoder.yudao.module.skit.controller.admin.record.vo.SkitDashboardSummaryRespVO;
import cn.iocoder.yudao.module.skit.service.record.SkitAdminRecordService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import javax.annotation.security.PermitAll;
import javax.validation.Valid;
import java.util.List;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@Tag(name = "管理后台 - 短剧 SaaS 通用记录")
@RestController
@RequestMapping("/skit/admin-record")
@Validated
public class SkitAdminRecordController {

    @Resource
    private SkitAdminRecordService skitAdminRecordService;

    @PostMapping("/create")
    @Operation(summary = "创建短剧后台记录")
    @PermitAll
    public CommonResult<Long> createRecord(@Valid @RequestBody SkitAdminRecordSaveReqVO createReqVO) {
        return success(skitAdminRecordService.createRecord(createReqVO));
    }

    @PutMapping("/update")
    @Operation(summary = "更新短剧后台记录")
    @PermitAll
    public CommonResult<Boolean> updateRecord(@Valid @RequestBody SkitAdminRecordSaveReqVO updateReqVO) {
        skitAdminRecordService.updateRecord(updateReqVO);
        return success(true);
    }

    @DeleteMapping("/delete")
    @Operation(summary = "删除短剧后台记录")
    @PermitAll
    public CommonResult<Boolean> deleteRecord(@RequestParam("id") Long id) {
        skitAdminRecordService.deleteRecord(id);
        return success(true);
    }

    @DeleteMapping("/delete-list")
    @Operation(summary = "批量删除短剧后台记录")
    @PermitAll
    public CommonResult<Boolean> deleteRecordList(@RequestParam("ids") List<Long> ids) {
        skitAdminRecordService.deleteRecordList(ids);
        return success(true);
    }

    @GetMapping("/get")
    @Operation(summary = "获得短剧后台记录")
    @PermitAll
    public CommonResult<SkitAdminRecordRespVO> getRecord(@RequestParam("id") Long id) {
        return success(skitAdminRecordService.getRecord(id));
    }

    @GetMapping("/page")
    @Operation(summary = "获得短剧后台记录分页")
    @PermitAll
    public CommonResult<PageResult<SkitAdminRecordRespVO>> getRecordPage(SkitAdminRecordPageReqVO pageReqVO) {
        return success(skitAdminRecordService.getRecordPage(pageReqVO));
    }

    @PostMapping("/seed")
    @Operation(summary = "初始化页面样例数据")
    @PermitAll
    public CommonResult<Integer> seedPage(@RequestParam("pageKey") String pageKey) {
        return success(skitAdminRecordService.seedPage(pageKey));
    }

    @GetMapping("/dashboard-summary")
    @Operation(summary = "获得短剧看板汇总")
    @PermitAll
    public CommonResult<SkitDashboardSummaryRespVO> getDashboardSummary() {
        return success(skitAdminRecordService.getDashboardSummary());
    }

}
