package cn.iocoder.yudao.module.skit.dal.dataobject.member;

import cn.iocoder.yudao.framework.tenant.core.db.TenantBaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

import java.time.LocalDateTime;

@TableName("skit_member")
@KeySequence("skit_member_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SkitMemberDO extends TenantBaseDO {

    @TableId
    private Long id;
    private String mobile;
    private String password;
    private String nickname;
    private Long inviterId;
    private String inviteCode;
    private Integer depth;
    private Integer status;
    private String registerIp;
    private String loginIp;
    private LocalDateTime loginTime;

}
