package cn.iocoder.yudao.module.skit.controller.admin.tenant.vo;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Pattern;
import javax.validation.constraints.Positive;
import javax.validation.constraints.PositiveOrZero;
import org.hibernate.validator.constraints.Length;

@Data
public class SkitTenantAdRolloutReqVO {

    private Long tenantId;
    @NotBlank
    @Pattern(regexp = "OFF|SHADOW_TEST_USERS|ENFORCED")
    private String targetState;
    @NotBlank
    @Pattern(regexp = "[0-9]{1,9}(\\.[0-9]{1,9}){1,3}")
    private String minNativeVersion;
    @NotNull
    @Positive
    private Integer minProtocolVersion;
    @NotNull
    @PositiveOrZero
    private Integer expectedReadinessVersion;
    @NotBlank
    @Length(min = 10, max = 500)
    private String reason;

}
