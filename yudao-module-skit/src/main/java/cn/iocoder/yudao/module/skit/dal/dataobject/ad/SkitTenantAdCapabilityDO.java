package cn.iocoder.yudao.module.skit.dal.dataobject.ad;

import cn.iocoder.yudao.framework.tenant.core.db.TenantBaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@TableName("skit_tenant_ad_capability")
@KeySequence("skit_tenant_ad_capability_seq")
@Data
@EqualsAndHashCode(callSuper = true)
public class SkitTenantAdCapabilityDO extends TenantBaseDO {

    @TableId
    private Long id;
    private Long adAccountId;
    private String rolloutState;
    private String dedicatedUnlockPlacementId;
    private LocalDateTime dedicatedPlacementVerifiedAt;
    private LocalDateTime rewardCallbackTemplateVerifiedAt;
    private LocalDateTime impressionCallbackTemplateVerifiedAt;
    private String unlockNetworkFirmIdsJson;
    private String shadowTestMemberIdsJson;
    private String minNativeVersion;
    private Integer minProtocolVersion;
    private Integer readinessVersion;
    private LocalDateTime enforcedAt;

}
