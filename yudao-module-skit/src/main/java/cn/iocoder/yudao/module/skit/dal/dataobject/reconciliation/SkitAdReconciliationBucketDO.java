package cn.iocoder.yudao.module.skit.dal.dataobject.reconciliation;

import cn.iocoder.yudao.framework.tenant.core.db.TenantBaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;

@TableName("skit_ad_reconciliation_bucket")
@KeySequence("skit_ad_reconciliation_bucket_seq")
@Data
@EqualsAndHashCode(callSuper = true)
public class SkitAdReconciliationBucketDO extends TenantBaseDO {

    @TableId
    private Long id;
    private Long adAccountId;
    private String bucketKey;
    private LocalDate reportDate;
    private String reportTimezone;
    private String appId;
    private String placementId;
    private String adFormat;
    private Integer networkFirmId;
    private String networkAccountId;
    private String adsourceId;
    private String currency;
    private Integer amountScale;
    private Long estimateUnits;
    private Long reportActualUnits;
    private Long reportImpressions;
    private Boolean reportImpressionsAvailable;
    private Long matchedImpressions;
    private Long attributableActualUnits;
    private Long suspenseUnits;
    private String status;

}
