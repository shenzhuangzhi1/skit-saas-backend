package cn.iocoder.yudao.module.skit.controller.admin.tenant.vo;

import lombok.Data;
import org.hibernate.validator.constraints.Length;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Positive;
import javax.validation.constraints.PositiveOrZero;
import javax.validation.constraints.Pattern;

@Data
public class SkitAdNetworkCapabilityVerifyReqVO {

    @NotNull
    @Positive
    private Long tenantId;
    @NotNull
    @Positive
    private Long adAccountId;
    @NotNull
    @Positive
    private Integer networkFirmId;
    @NotBlank
    @Pattern(regexp = "SIGNED_REWARD|NONE")
    private String rewardAuthority;
    @NotNull
    private Boolean enabled;
    private Boolean supportsUserId;
    private Boolean supportsCustomData;
    private Boolean supportsStableTransaction;
    private Boolean supportsImpressionRevenue;
    private Boolean supportsReporting;
    @NotNull
    @PositiveOrZero
    private Integer expectedReadinessVersion;
    @NotBlank
    @Length(min = 10, max = 500)
    private String reason;

}
