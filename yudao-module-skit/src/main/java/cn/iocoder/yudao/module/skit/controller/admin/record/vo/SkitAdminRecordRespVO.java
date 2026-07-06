package cn.iocoder.yudao.module.skit.controller.admin.record.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

@Schema(description = "管理后台 - 短剧 SaaS 通用记录 Response VO")
@Data
public class SkitAdminRecordRespVO {

    @Schema(description = "编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "23267")
    private Long id;

    @Schema(description = "页面键", requiredMode = Schema.RequiredMode.REQUIRED, example = "adRecord")
    private String pageKey;

    @Schema(description = "业务行键", requiredMode = Schema.RequiredMode.REQUIRED)
    private String rowKey;

    @Schema(description = "页面字段 JSON")
    private Map<String, Object> recordData;

    @Schema(description = "状态", example = "0")
    private Integer status;

    @Schema(description = "排序", example = "10")
    private Integer sort;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

    @Schema(description = "更新时间")
    private LocalDateTime updateTime;

}
