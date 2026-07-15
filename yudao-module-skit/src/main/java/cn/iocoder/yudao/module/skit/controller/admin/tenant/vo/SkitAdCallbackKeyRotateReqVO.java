package cn.iocoder.yudao.module.skit.controller.admin.tenant.vo;

import lombok.Data;
import org.hibernate.validator.constraints.Length;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Positive;
import javax.validation.constraints.PositiveOrZero;

@Data
public class SkitAdCallbackKeyRotateReqVO {

    private Long tenantId;
    @NotNull
    @Positive
    private Long adAccountId;
    @NotNull
    @PositiveOrZero
    private Integer expectedReadinessVersion;
    @NotNull
    @Min(0)
    @Max(1440)
    private Integer priorAcceptanceMinutes;
    @NotBlank
    @Length(min = 10, max = 500)
    private String reason;

}
