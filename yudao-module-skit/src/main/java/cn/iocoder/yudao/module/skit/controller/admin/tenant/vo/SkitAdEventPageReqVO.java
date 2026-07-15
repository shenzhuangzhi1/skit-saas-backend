package cn.iocoder.yudao.module.skit.controller.admin.tenant.vo;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.springframework.format.annotation.DateTimeFormat;

import javax.validation.constraints.Pattern;
import javax.validation.constraints.Positive;
import javax.validation.constraints.Size;
import java.time.LocalDateTime;

import static cn.iocoder.yudao.module.skit.enums.SkitDomainConstants.MANAGEMENT_TIMEZONE_DEFAULT;
import static cn.iocoder.yudao.module.skit.enums.SkitDomainConstants.MANAGEMENT_TIMEZONE_PATTERN;

@Data
@EqualsAndHashCode(callSuper = true)
public class SkitAdEventPageReqVO extends PageParam {

    @Positive(message = "租户编号必须大于 0")
    private Long tenantId;
    @Positive(message = "广告账号编号必须大于 0")
    private Long adAccountId;
    @Positive(message = "会员编号必须大于 0")
    private Long memberId;
    @Size(max = 32)
    @Pattern(regexp = "PANGLE|TAKU", message = "广告平台仅支持 PANGLE 或 TAKU")
    private String provider;
    @Size(max = 32)
    @Pattern(regexp = "MATCHED|LEGACY_UNMATCHED", message = "广告匹配状态不合法")
    private String matchStatus;
    @Size(max = 32)
    @Pattern(regexp = "UNSIGNED_OBSERVATION|REPORT_CONFIRMED|LEGACY_UNVERIFIED",
            message = "广告来源校验状态不合法")
    private String sourceVerificationStatus;
    @Size(max = 32)
    @Pattern(regexp = "FROZEN|SUSPENSE|RECONCILED|NON_SETTLEABLE",
            message = "广告对账状态不合法")
    private String reconciliationStatus;
    @Pattern(regexp = "[A-Z]{3}", message = "币种必须是 ISO 4217 大写三字码")
    private String currency;
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime startTime;
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime endTime;
    @Pattern(regexp = MANAGEMENT_TIMEZONE_PATTERN,
            message = "时区仅支持 UTC-8、UTC+8 或 UTC+0")
    private String timezone = MANAGEMENT_TIMEZONE_DEFAULT;

}
