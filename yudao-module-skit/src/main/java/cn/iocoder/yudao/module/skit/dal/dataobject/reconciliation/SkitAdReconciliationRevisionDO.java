package cn.iocoder.yudao.module.skit.dal.dataobject.reconciliation;

import cn.iocoder.yudao.framework.tenant.core.db.TenantBaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;
import java.time.LocalDateTime;

@TableName("skit_ad_reconciliation_revision")
@KeySequence("skit_ad_reconciliation_revision_seq")
@Data
@EqualsAndHashCode(callSuper = true)
public class SkitAdReconciliationRevisionDO extends TenantBaseDO {

    @TableId
    private Long id;
    private Long adAccountId;
    private Long reconciliationBucketId;
    private Long reportPullId;
    private String bucketKey;
    private LocalDate reportDate;
    private byte[] revisionHash;
    private Integer revisionNo;
    private Long targetActualUnits;
    private Long unmatchedActualUnits;
    private Integer amountScale;
    private String currency;
    private Boolean finalRevision;
    private Long sourceReportImpressions;
    private Boolean sourceReportImpressionsAvailable;
    private Long matchedEventCount;
    private String status;
    private LocalDateTime reconciledAt;

}
