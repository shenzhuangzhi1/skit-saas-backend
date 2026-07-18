package cn.iocoder.yudao.module.skit.controller.admin.record.vo;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.Positive;

@Schema(description = "管理后台 - 短剧 SaaS 通用记录分页 Request VO")
@Data
public class SkitAdminRecordPageReqVO extends PageParam {

    @Schema(description = "平台管理员显式选择的代理商租户", example = "162")
    @Positive(message = "租户编号必须大于 0")
    private Long tenantId;

    @Schema(description = "页面键", requiredMode = Schema.RequiredMode.REQUIRED, example = "adRecord")
    @NotEmpty(message = "页面键不能为空")
    private String pageKey;

    @Schema(description = "关键字", example = "gdt")
    private String keyword;

    @Schema(description = "状态", example = "0")
    private Integer status;

}
