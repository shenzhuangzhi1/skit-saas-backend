package cn.iocoder.yudao.module.skit.dal.dataobject.reconciliation;

import cn.iocoder.yudao.framework.tenant.core.db.TenantBaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

@TableName("skit_ad_reconciliation_event_link")
@KeySequence("skit_ad_reconciliation_event_link_seq")
@Data
@EqualsAndHashCode(callSuper = true)
public class SkitAdReconciliationEventLinkDO extends TenantBaseDO {

    @TableId
    private Long id;
    private Long reconciliationBucketId;
    private Long reconciliationRevisionId;
    private Integer revisionNo;
    private Long eventId;
    private Long policySnapshotId;
    private String associationStatus;
    private Long actualUnits;
}
