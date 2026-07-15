package cn.iocoder.yudao.module.skit.dal.dataobject.invite;

import cn.iocoder.yudao.framework.tenant.core.db.TenantBaseDO;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@TableName("skit_invite_code_registry")
@KeySequence("skit_invite_code_registry_seq")
@Data
@EqualsAndHashCode(callSuper = true)
public class SkitInviteCodeRegistryDO extends TenantBaseDO {

    @TableId
    private Long id;
    private String code;
    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private String normalizedCode;
    private String ownerType;
    private Long agentId;
    private Long memberId;
    private String status;
    private LocalDateTime rotatedAt;
    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private Long activeAgentId;
    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private Long activeMemberId;

}
