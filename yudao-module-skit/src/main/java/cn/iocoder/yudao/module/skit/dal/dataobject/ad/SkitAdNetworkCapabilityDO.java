package cn.iocoder.yudao.module.skit.dal.dataobject.ad;

import cn.iocoder.yudao.framework.tenant.core.db.TenantBaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@TableName("skit_ad_network_capability")
@KeySequence("skit_ad_network_capability_seq")
@Data
@EqualsAndHashCode(callSuper = true)
public class SkitAdNetworkCapabilityDO extends TenantBaseDO {

    @TableId
    private Long id;
    private Long adAccountId;
    private Integer networkFirmId;
    private String rewardAuthority;
    private Boolean supportsUserId;
    private Boolean supportsCustomData;
    private Boolean supportsStableTransaction;
    private Boolean supportsImpressionRevenue;
    private Boolean supportsReporting;
    private Boolean enabled;
    private LocalDateTime verifiedAt;

}
