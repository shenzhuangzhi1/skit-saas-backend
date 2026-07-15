package cn.iocoder.yudao.module.skit.dal.dataobject.revenue;

import cn.iocoder.yudao.framework.tenant.core.db.TenantBaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

import java.math.BigDecimal;

@TableName("skit_commission_ledger")
@KeySequence("skit_commission_ledger_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SkitCommissionLedgerDO extends TenantBaseDO {

    @TableId
    private Long id;
    private Long eventId;
    private Integer beneficiaryType;
    private Long beneficiaryMemberId;
    private Integer levelNo;
    private BigDecimal grossAmount;
    private Integer rateBps;
    private BigDecimal amount;
    private Integer ruleVersion;
    private Integer status;

    /** Task 2 canonical append-only finance fields. */
    private String entryType;
    private String balanceBucket;
    private String currency;
    private Long grossAmountUnits;
    private Long amountUnits;
    private Integer amountScale;
    private Long reversalOfId;
    private Long reconciliationRevisionId;
    private Long policySnapshotId;
    private Integer revisionNo;
    private Boolean legacyUnverified;

}
