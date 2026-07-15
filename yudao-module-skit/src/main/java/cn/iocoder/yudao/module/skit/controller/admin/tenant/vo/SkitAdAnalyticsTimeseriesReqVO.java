package cn.iocoder.yudao.module.skit.controller.admin.tenant.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import javax.validation.constraints.Pattern;

@Schema(description = "管理后台 - 广告趋势查询 Request VO")
@Data
@EqualsAndHashCode(callSuper = true)
public class SkitAdAnalyticsTimeseriesReqVO extends SkitAdAnalyticsQueryReqVO {

    @Schema(description = "聚合粒度", allowableValues = {"HOUR", "DAY"}, defaultValue = "HOUR")
    @Pattern(regexp = "HOUR|DAY", message = "聚合粒度必须是 HOUR 或 DAY")
    private String granularity = "HOUR";

}
