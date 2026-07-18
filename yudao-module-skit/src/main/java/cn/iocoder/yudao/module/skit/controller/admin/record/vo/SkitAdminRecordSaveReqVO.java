package cn.iocoder.yudao.module.skit.controller.admin.record.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Positive;
import javax.validation.constraints.Size;
import java.util.Map;

@Schema(description = "管理后台 - 短剧 SaaS 通用记录新增/修改 Request VO")
@Data
public class SkitAdminRecordSaveReqVO {

    @Schema(description = "平台管理员显式选择的代理商租户", example = "162")
    @Positive(message = "租户编号必须大于 0")
    private Long tenantId;

    @Schema(description = "跨租户管理操作原因")
    @Size(min = 10, max = 500, message = "管理操作原因长度必须为 10 到 500 个字符")
    private String reason;

    @Schema(description = "编号", example = "23267")
    private Long id;

    @Schema(description = "页面键", requiredMode = Schema.RequiredMode.REQUIRED, example = "adRecord")
    @NotEmpty(message = "页面键不能为空")
    private String pageKey;

    @Schema(description = "业务行键", example = "adRecord-1")
    private String rowKey;

    @Schema(description = "页面字段 JSON", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "页面字段不能为空")
    private Map<String, Object> recordData;

    @Schema(description = "状态", example = "0")
    private Integer status;

    @Schema(description = "排序", example = "10")
    private Integer sort;

}
