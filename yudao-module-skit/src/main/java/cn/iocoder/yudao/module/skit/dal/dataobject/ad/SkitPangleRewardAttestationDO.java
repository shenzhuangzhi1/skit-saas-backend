package cn.iocoder.yudao.module.skit.dal.dataobject.ad;

import cn.iocoder.yudao.framework.tenant.core.db.TenantBaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

import java.time.LocalDateTime;

@TableName("skit_pangle_reward_attestation")
@KeySequence("skit_pangle_reward_attestation_seq")
@Data
@EqualsAndHashCode(callSuper = true)
public class SkitPangleRewardAttestationDO extends TenantBaseDO {

    @TableId
    private Long id;
    private Long takuAdAccountId;
    private Long pangleAdAccountId;
    private Long adSessionId;
    private Integer callbackKeyVersion;
    private Integer pangleRewardSecretVersion;
    private String pangleRewardPlacementId;
    private String provider;
    private String providerTransactionId;
    private String providerUserId;
    @JsonIgnore
    @ToString.Exclude
    private byte[] extraDataHash;
    private String rewardName;
    private Integer rewardAmount;
    @JsonIgnore
    @ToString.Exclude
    private byte[] canonicalPayloadHash;
    @JsonIgnore
    @ToString.Exclude
    private byte[] credentialFingerprint;
    private LocalDateTime receivedAt;

}
