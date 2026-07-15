package cn.iocoder.yudao.module.skit.controller.admin.tenant.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import javax.validation.constraints.Pattern;
import javax.validation.constraints.Positive;
import java.time.LocalDateTime;

import static cn.iocoder.yudao.module.skit.enums.SkitDomainConstants.MANAGEMENT_TIMEZONE_DEFAULT;
import static cn.iocoder.yudao.module.skit.enums.SkitDomainConstants.MANAGEMENT_TIMEZONE_PATTERN;

@Schema(description = "管理后台 - 广告分析查询 Request VO")
@Data
public class SkitAdAnalyticsQueryReqVO {

    @Schema(description = "目标租户；super_admin 省略时查询全部租户，tenant_admin 省略时查询登录租户")
    @Positive(message = "租户编号必须大于 0")
    private Long tenantId;

    @Schema(description = "广告账号编号")
    @Positive(message = "广告账号编号必须大于 0")
    private Long adAccountId;

    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime startTime;

    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime endTime;

    @Schema(description = "展示时区", allowableValues = {"UTC-8", "UTC+8", "UTC+0"},
            defaultValue = MANAGEMENT_TIMEZONE_DEFAULT)
    @Pattern(regexp = MANAGEMENT_TIMEZONE_PATTERN,
            message = "时区仅支持 UTC-8、UTC+8 或 UTC+0")
    private String timezone = MANAGEMENT_TIMEZONE_DEFAULT;

    @Pattern(regexp = "[A-Z]{3}", message = "币种必须是 ISO 4217 大写三字码")
    private String currency;

}
