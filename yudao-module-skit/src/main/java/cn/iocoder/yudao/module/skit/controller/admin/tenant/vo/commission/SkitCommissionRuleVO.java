package cn.iocoder.yudao.module.skit.controller.admin.tenant.vo.commission;

import lombok.Data;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;

@Data
public class SkitCommissionRuleVO {

    @NotNull(message = "分成层级不能为空")
    @Min(value = 0, message = "分成层级不能小于 0")
    private Integer levelNo;

    @NotNull(message = "分成比例不能为空")
    @Min(value = 0, message = "分成比例不能小于 0")
    @Max(value = 10000, message = "分成比例不能大于 10000 个基点")
    private Integer rateBps;
}
