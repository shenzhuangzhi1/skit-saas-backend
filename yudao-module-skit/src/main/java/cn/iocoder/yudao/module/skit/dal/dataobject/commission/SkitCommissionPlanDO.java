package cn.iocoder.yudao.module.skit.dal.dataobject.commission;

import cn.iocoder.yudao.framework.tenant.core.db.TenantBaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

import java.time.LocalDateTime;

@TableName("skit_commission_plan")
@KeySequence("skit_commission_plan_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SkitCommissionPlanDO extends TenantBaseDO {

    @TableId
    private Long id;
    private Integer version;
    private Integer status;
    private LocalDateTime publishedTime;

}
