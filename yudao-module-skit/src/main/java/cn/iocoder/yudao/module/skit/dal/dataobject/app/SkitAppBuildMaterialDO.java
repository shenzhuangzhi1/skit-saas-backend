package cn.iocoder.yudao.module.skit.dal.dataobject.app;

import cn.iocoder.yudao.framework.tenant.core.db.TenantBaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

import java.time.LocalDateTime;

@TableName("skit_app_build_material")
@KeySequence("skit_app_build_material_seq")
@Data
@EqualsAndHashCode(callSuper = true)
public class SkitAppBuildMaterialDO extends TenantBaseDO {

    @TableId
    private Long id;
    private Integer materialVersion;
    private String apiBaseUrl;
    private String appName;
    private Long nativeVersionCode;
    private String nativeVersionName;
    private Long runtimeReleaseNo;
    @JsonIgnore
    @ToString.Exclude
    private byte[] secretCiphertext;
    @JsonIgnore
    @ToString.Exclude
    private byte[] secretNonce;
    @JsonIgnore
    @ToString.Exclude
    private String encryptionKeyId;
    @JsonIgnore
    @ToString.Exclude
    private Integer envelopeVersion;
    private Boolean active;
    private LocalDateTime verifiedAt;
}
