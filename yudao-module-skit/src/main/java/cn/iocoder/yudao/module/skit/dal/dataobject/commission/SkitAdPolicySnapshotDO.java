package cn.iocoder.yudao.module.skit.dal.dataobject.commission;

import cn.iocoder.yudao.framework.tenant.core.db.TenantBaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

import java.time.LocalDateTime;

@TableName("skit_ad_policy_snapshot")
@KeySequence("skit_ad_policy_snapshot_seq")
@Data
@EqualsAndHashCode(callSuper = true)
public class SkitAdPolicySnapshotDO extends TenantBaseDO {

    @TableId
    private Long id;
    private Long planId;
    private Long sourceMemberId;
    private Integer ruleVersion;
    private Integer snapshotSchemaVersion;
    private String snapshotJson;
    @JsonIgnore
    @ToString.Exclude
    private byte[] snapshotHash;
    private LocalDateTime policySnapshotAt;

}
