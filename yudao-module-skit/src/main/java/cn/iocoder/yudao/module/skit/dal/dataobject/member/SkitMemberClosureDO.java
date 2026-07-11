package cn.iocoder.yudao.module.skit.dal.dataobject.member;

import cn.iocoder.yudao.framework.tenant.core.db.TenantBaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

@TableName("skit_member_closure")
@KeySequence("skit_member_closure_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SkitMemberClosureDO extends TenantBaseDO {

    @TableId
    private Long id;
    private Long ancestorId;
    private Long descendantId;
    private Integer distance;

}
