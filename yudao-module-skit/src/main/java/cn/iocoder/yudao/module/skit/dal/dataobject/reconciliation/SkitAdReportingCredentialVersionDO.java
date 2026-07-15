package cn.iocoder.yudao.module.skit.dal.dataobject.reconciliation;

import cn.iocoder.yudao.framework.tenant.core.db.TenantBaseDO;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

import java.time.LocalDateTime;

@TableName("skit_ad_reporting_credential_version")
@KeySequence("skit_ad_reporting_credential_version_seq")
@Data
@EqualsAndHashCode(callSuper = true)
public class SkitAdReportingCredentialVersionDO extends TenantBaseDO {

    @TableId
    private Long id;
    private Long adAccountId;
    private Integer credentialVersion;
    @JsonIgnore
    @ToString.Exclude
    private byte[] ciphertext;
    @JsonIgnore
    @ToString.Exclude
    private byte[] nonce;
    private String encryptionKeyId;
    private Integer envelopeVersion;
    private Boolean active;
    private LocalDateTime permissionVerifiedAt;
    private LocalDateTime revokedAt;
    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private Long activeAccountId;

}
