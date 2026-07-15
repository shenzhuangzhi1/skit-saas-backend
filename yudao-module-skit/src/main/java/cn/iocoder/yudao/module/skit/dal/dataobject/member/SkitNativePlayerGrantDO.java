package cn.iocoder.yudao.module.skit.dal.dataobject.member;

import cn.iocoder.yudao.framework.tenant.core.db.TenantBaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

import java.time.LocalDateTime;

@TableName("skit_native_player_grant")
@KeySequence("skit_native_player_grant_seq")
@Data
@EqualsAndHashCode(callSuper = true)
public class SkitNativePlayerGrantDO extends TenantBaseDO {

    @TableId
    private Long id;
    private Long memberId;
    private Long dramaId;
    @JsonIgnore
    @ToString.Exclude
    private byte[] grantTokenHash;
    private String status;
    private LocalDateTime expiresAt;
    private LocalDateTime revokedAt;
    private Integer version;

}
