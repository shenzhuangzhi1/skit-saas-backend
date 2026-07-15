package cn.iocoder.yudao.module.skit.dal.dataobject.management;

import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;
import lombok.ToString;

import java.time.LocalDateTime;

/** Append-only security/finance management command fact. */
@TableName("skit_management_command_audit")
@KeySequence("skit_management_command_audit_seq")
@Data
public class SkitManagementCommandAuditDO {

    @TableId
    private Long id;
    private Long tenantId;
    private String commandId;
    private Long operatorUserId;
    private Long originalTenantId;
    private Long targetTenantId;
    private String commandType;
    private String resourceType;
    private String resourceId;
    private String reason;
    @JsonIgnore
    @ToString.Exclude
    private byte[] beforeStateHash;
    @JsonIgnore
    @ToString.Exclude
    private byte[] afterStateHash;
    @JsonIgnore
    @ToString.Exclude
    private byte[] requestFingerprint;
    private String traceId;
    private String resultStatus;
    private LocalDateTime createdAt;

}
