package cn.iocoder.yudao.module.skit.dal.dataobject.member;

import cn.iocoder.yudao.framework.tenant.core.db.TenantBaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@TableName("skit_content_entitlement")
@KeySequence("skit_content_entitlement_seq")
@Data
@EqualsAndHashCode(callSuper = true)
public class SkitContentEntitlementDO extends TenantBaseDO {

    @TableId
    private Long id;
    private Long memberId;
    private Long dramaId;
    private Integer episodeNo;
    private String status;
    private LocalDateTime grantedAt;
    private Integer version;

}
