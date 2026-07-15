package cn.iocoder.yudao.module.skit.dal.dataobject.reconciliation;

import cn.iocoder.yudao.framework.tenant.core.db.TenantBaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

@TableName("skit_ad_reconciliation_allocation")
@KeySequence("skit_ad_reconciliation_allocation_seq")
@Data
@EqualsAndHashCode(callSuper = true)
public class SkitAdReconciliationAllocationDO extends TenantBaseDO {

    @TableId
    private Long id;
    private Long reconciliationBucketId;
    private Long reconciliationRevisionId;
    private Integer revisionNo;
    private Long eventId;
    private Integer beneficiaryType;
    private Long beneficiaryMemberId;
    private Integer levelNo;
    private Long policySnapshotId;
    private String currency;
    private Integer amountScale;
    private Long cumulativeTargetUnits;

}
