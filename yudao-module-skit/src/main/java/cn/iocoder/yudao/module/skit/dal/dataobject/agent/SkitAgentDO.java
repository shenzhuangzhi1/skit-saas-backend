package cn.iocoder.yudao.module.skit.dal.dataobject.agent;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import cn.iocoder.yudao.framework.tenant.core.aop.TenantIgnore;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

@TableName("skit_agent")
@KeySequence("skit_agent_seq")
@TenantIgnore
@Data
@EqualsAndHashCode(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SkitAgentDO extends BaseDO {

    @TableId
    private Long id;
    private Long tenantId;
    private String tenantCode;
    private String rootInviteCode;
    private Integer status;
    private String remark;

}
