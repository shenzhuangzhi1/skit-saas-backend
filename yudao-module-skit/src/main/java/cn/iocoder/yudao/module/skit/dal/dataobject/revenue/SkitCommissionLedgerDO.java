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

}
