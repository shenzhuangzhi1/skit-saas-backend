package cn.iocoder.yudao.module.skit.controller.admin.tenant.vo.commission;

import lombok.Data;
import org.hibernate.validator.constraints.Length;

import javax.validation.Valid;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Positive;
import javax.validation.constraints.Size;
import java.util.List;

@Data
public class SkitCommissionPublishReqVO {

    @Positive(message = "租户编号必须大于 0")
    private Long tenantId;

    @NotNull(message = "预期版本不能为空")
    @Min(value = 0, message = "预期版本不能小于 0")
    private Integer expectedVersion;

    @Valid
    @NotEmpty(message = "分成规则不能为空")
    @Size(max = 100, message = "分成层级不能超过 100 层")
    private List<SkitCommissionRuleVO> rules;

    @NotBlank(message = "发布原因不能为空")
    @Length(min = 10, max = 500, message = "发布原因长度必须为 10 到 500 个字符")
    private String reason;
}
