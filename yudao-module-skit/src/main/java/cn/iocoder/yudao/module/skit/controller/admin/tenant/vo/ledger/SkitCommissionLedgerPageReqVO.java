package cn.iocoder.yudao.module.skit.controller.admin.tenant.vo.ledger;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.springframework.format.annotation.DateTimeFormat;

import javax.validation.constraints.Pattern;
import javax.validation.constraints.Positive;
import javax.validation.constraints.NotBlank;
import java.time.LocalDateTime;

import static cn.iocoder.yudao.module.skit.enums.SkitDomainConstants.MANAGEMENT_TIMEZONE_DEFAULT;
import static cn.iocoder.yudao.module.skit.enums.SkitDomainConstants.MANAGEMENT_TIMEZONE_PATTERN;

@Data
@EqualsAndHashCode(callSuper = true)
public class SkitCommissionLedgerPageReqVO extends PageParam {

    @Positive(message = "租户编号必须大于 0")
    private Long tenantId;
    @Positive(message = "账本分录编号必须大于 0")
    private Long id;
    @Positive(message = "广告事件编号必须大于 0")
    private Long eventId;
    @Positive(message = "来源会员编号必须大于 0")
    private Long sourceMemberId;
    @Positive(message = "受益会员编号必须大于 0")
    private Long beneficiaryMemberId;
    @Pattern(regexp = "MEMBER|AGENT", message = "受益方类型仅支持 MEMBER 或 AGENT")
    private String beneficiaryType;
    @Pattern(regexp = "PANGLE|TAKU", message = "广告平台仅支持 PANGLE 或 TAKU")
    private String provider;
    @Pattern(regexp = "ESTIMATE|ESTIMATE_RELEASE|SETTLEMENT|ADJUSTMENT|LEGACY_ESTIMATE",
            message = "账本分录类型不合法")
    private String entryType;
    @Pattern(regexp = "FROZEN|AVAILABLE|NON_SETTLEABLE", message = "账本余额桶不合法")
    private String balanceBucket;
    @NotBlank(message = "币种不能为空")
    @Pattern(regexp = "[A-Z]{3}", message = "币种必须是 ISO 4217 大写三字码")
    private String currency;
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime startTime;
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime endTime;
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime asOf;
    @Pattern(regexp = MANAGEMENT_TIMEZONE_PATTERN,
            message = "时区仅支持 UTC-8、UTC+8 或 UTC+0")
    private String timezone = MANAGEMENT_TIMEZONE_DEFAULT;
}
