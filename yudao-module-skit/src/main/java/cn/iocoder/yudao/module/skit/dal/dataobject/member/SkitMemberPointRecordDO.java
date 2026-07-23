package cn.iocoder.yudao.module.skit.dal.dataobject.member;

import cn.iocoder.yudao.framework.tenant.core.db.TenantBaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@TableName("skit_member_point_record")
@KeySequence("skit_member_point_record_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SkitMemberPointRecordDO extends TenantBaseDO {

    @TableId
    private Long id;
    private Long memberId;
    private String bizType;
    private String bizId;
    private String title;
    private String description;
    private Integer pointDelta;
    private Integer balanceAfter;

}
