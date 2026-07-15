package cn.iocoder.yudao.module.skit.controller.admin.tenant.vo;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.springframework.format.annotation.DateTimeFormat;

import javax.validation.constraints.Pattern;
import javax.validation.constraints.Positive;
import javax.validation.constraints.Size;
import java.time.LocalDate;

import static cn.iocoder.yudao.module.skit.enums.SkitDomainConstants.MANAGEMENT_TIMEZONE_DEFAULT;
import static cn.iocoder.yudao.module.skit.enums.SkitDomainConstants.MANAGEMENT_TIMEZONE_PATTERN;

@Data
@EqualsAndHashCode(callSuper = true)
public class SkitReconciliationPageReqVO extends PageParam {

    @Positive(message = "租户编号必须大于 0")
    private Long tenantId;
    @Positive(message = "广告账号编号必须大于 0")
    private Long adAccountId;
    @Size(max = 32)
    @Pattern(regexp = "OPEN|RECONCILED|PARTIAL|SUSPENSE", message = "对账桶状态不合法")
    private String status;
    @Pattern(regexp = "[A-Z]{3}", message = "币种必须是 ISO 4217 大写三字码")
    private String currency;
    @DateTimeFormat(pattern = "yyyy-MM-dd")
    private LocalDate reportDateStart;
    @DateTimeFormat(pattern = "yyyy-MM-dd")
    private LocalDate reportDateEnd;
    @Pattern(regexp = MANAGEMENT_TIMEZONE_PATTERN,
            message = "时区仅支持 UTC-8、UTC+8 或 UTC+0")
    private String timezone = MANAGEMENT_TIMEZONE_DEFAULT;

}
