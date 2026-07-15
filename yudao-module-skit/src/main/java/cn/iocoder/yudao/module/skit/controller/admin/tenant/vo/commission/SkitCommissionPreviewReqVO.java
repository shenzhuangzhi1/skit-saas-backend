package cn.iocoder.yudao.module.skit.controller.admin.tenant.vo.commission;

import lombok.Data;

import javax.validation.Valid;
import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Pattern;
import javax.validation.constraints.Positive;
import javax.validation.constraints.Size;
import java.util.List;

import static cn.iocoder.yudao.module.skit.enums.SkitDomainConstants.MANAGEMENT_TIMEZONE_DEFAULT;
import static cn.iocoder.yudao.module.skit.enums.SkitDomainConstants.MANAGEMENT_TIMEZONE_PATTERN;

@Data
public class SkitCommissionPreviewReqVO {

    @Positive(message = "租户编号必须大于 0")
    private Long tenantId;

    @NotNull(message = "预览金额最小单位不能为空")
    @Min(value = 0, message = "预览金额不能小于 0")
    private Long amountUnits;

    @NotNull(message = "金额精度不能为空")
    @Min(value = 0, message = "金额精度不能小于 0")
    @Max(value = 18, message = "金额精度不能大于 18")
    private Integer amountScale;

    @NotBlank(message = "币种不能为空")
    @Pattern(regexp = "[A-Z]{3}", message = "币种必须是 ISO 4217 大写三字码")
    private String currency;

    @Pattern(regexp = MANAGEMENT_TIMEZONE_PATTERN,
            message = "时区仅支持 UTC-8、UTC+8 或 UTC+0")
    private String timezone = MANAGEMENT_TIMEZONE_DEFAULT;

    @Valid
    @NotEmpty(message = "分成规则不能为空")
    @Size(max = 100, message = "分成层级不能超过 100 层")
    private List<SkitCommissionRuleVO> rules;
}
