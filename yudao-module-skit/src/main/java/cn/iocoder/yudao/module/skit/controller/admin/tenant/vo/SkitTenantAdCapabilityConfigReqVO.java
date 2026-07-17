package cn.iocoder.yudao.module.skit.controller.admin.tenant.vo;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Pattern;
import javax.validation.constraints.Positive;
import javax.validation.constraints.PositiveOrZero;
import javax.validation.constraints.Size;
import org.hibernate.validator.constraints.Length;
import java.util.LinkedHashSet;
import java.util.Set;

@Data
public class SkitTenantAdCapabilityConfigReqVO {

    private Long tenantId;
    @NotNull
    @Positive
    private Long adAccountId;
    private String dedicatedUnlockPlacementId;
    @NotNull
    private Boolean dedicatedPlacementVerified;
    @NotNull
    private Boolean rewardCallbackTemplateVerified;
    @NotNull
    private Boolean impressionCallbackTemplateVerified;
    @Size(max = 16)
    private Set<@Positive Integer> unlockNetworkFirmIds = new LinkedHashSet<>();
    @NotNull
    @Size(max = 100)
    private Set<@Positive Long> shadowTestMemberIds = new LinkedHashSet<>();
    @NotBlank
    @Pattern(regexp = "[0-9]{1,9}(\\.[0-9]{1,9}){1,3}")
    private String minNativeVersion;
    private Integer minProtocolVersion;
    @NotNull
    @PositiveOrZero
    private Integer expectedReadinessVersion;
    @NotBlank
    @Length(min = 10, max = 500)
    private String reason;

}
