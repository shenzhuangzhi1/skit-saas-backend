package cn.iocoder.yudao.module.skit.dal.dataobject.commission;

import cn.iocoder.yudao.framework.tenant.core.db.TenantBaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

@TableName("skit_commission_rule")
@KeySequence("skit_commission_rule_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SkitCommissionRuleDO extends TenantBaseDO {

    @TableId
    private Long id;
    private Long planId;
    private Integer levelNo;
    private Integer rateBps;

}
