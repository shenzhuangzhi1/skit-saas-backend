package cn.iocoder.yudao.module.skit.dal.dataobject.revenue;

import cn.iocoder.yudao.framework.tenant.core.db.TenantBaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@TableName("skit_ad_revenue_event")
@KeySequence("skit_ad_revenue_event_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SkitAdRevenueEventDO extends TenantBaseDO {

    @TableId
    private Long id;
    private Long adAccountId;
    private String provider;
    private String placementId;
    private String externalEventId;
    private Long sourceMemberId;
    private BigDecimal grossAmount;
    private LocalDateTime occurredTime;
    private Boolean completed;
    private Boolean mock;
    private Integer status;
    private Integer ruleVersion;
    private String rawData;

    /** Task 2 canonical provenance and reconciliation fields. */
    private Long adSessionId;
    private Long callbackInboxId;
    private Long policySnapshotId;
    private Long reconciliationBucketId;
    private Long reconciliationRevisionId;
    private String sourceType;
    private String providerTransactionId;
    private String providerShowId;
    private String sdkRequestId;
    private String adsourceId;
    private Long sourceAmountUnits;
    private Long estimatedAmountUnits;
    private Long reconciledAmountUnits;
    private Integer amountScale;
    private String sourceCurrency;
    private String matchStatus;
    private String sourceVerificationStatus;
    private String rewardQualificationStatus;
    private String reconciliationStatus;
    private LocalDateTime reconciledAt;
    private LocalDateTime verifiedAt;
    private byte[] payloadHash;
    private Integer version;
    private Boolean legacyUnverified;

}
