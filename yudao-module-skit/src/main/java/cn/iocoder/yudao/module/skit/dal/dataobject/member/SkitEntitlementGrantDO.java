package cn.iocoder.yudao.module.skit.dal.dataobject.member;

import cn.iocoder.yudao.framework.tenant.core.db.TenantBaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@TableName("skit_entitlement_grant")
@KeySequence("skit_entitlement_grant_seq")
@Data
@EqualsAndHashCode(callSuper = true)
public class SkitEntitlementGrantDO extends TenantBaseDO {

    @TableId
    private Long id;
    private Long adSessionId;
    private Long entitlementId;
    private Long memberId;
    private Long dramaId;
    private Integer episodeNo;
    private String providerTransactionId;
    private String grantResult;
    private LocalDateTime grantedAt;

}
