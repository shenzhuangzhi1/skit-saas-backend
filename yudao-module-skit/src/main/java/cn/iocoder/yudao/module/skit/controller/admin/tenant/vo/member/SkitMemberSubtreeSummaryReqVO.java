package cn.iocoder.yudao.module.skit.controller.admin.tenant.vo.member;

import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Pattern;
import javax.validation.constraints.Positive;
import java.time.LocalDateTime;

import static cn.iocoder.yudao.module.skit.enums.SkitDomainConstants.MANAGEMENT_TIMEZONE_DEFAULT;
import static cn.iocoder.yudao.module.skit.enums.SkitDomainConstants.MANAGEMENT_TIMEZONE_PATTERN;

@Data
public class SkitMemberSubtreeSummaryReqVO {

    @Positive(message = "租户编号必须大于 0")
    private Long tenantId;
    @NotNull(message = "统计开始时间不能为空")
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime startTime;
    @NotNull(message = "统计结束时间不能为空")
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime endTime;
    @NotBlank(message = "统计币种不能为空")
    @Pattern(regexp = "[A-Z]{3}", message = "币种必须是 ISO 4217 大写三字码")
    private String currency;
    @Pattern(regexp = "PANGLE|TAKU", message = "广告平台仅支持 PANGLE 或 TAKU")
    private String provider;
    @NotBlank(message = "统计口径不能为空")
    @Pattern(regexp = "RECONCILED_LEDGER", message = "统计口径仅支持 RECONCILED_LEDGER")
    private String statisticBasis = "RECONCILED_LEDGER";
    @Pattern(regexp = MANAGEMENT_TIMEZONE_PATTERN,
            message = "时区仅支持 UTC-8、UTC+8 或 UTC+0")
    private String timezone = MANAGEMENT_TIMEZONE_DEFAULT;
}
