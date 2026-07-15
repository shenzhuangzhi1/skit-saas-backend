package cn.iocoder.yudao.module.skit.dal.dataobject.app;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import cn.iocoder.yudao.framework.tenant.core.aop.TenantIgnore;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

@TableName("skit_app_release_profile")
@KeySequence("skit_app_release_profile_seq")
@TenantIgnore
@Data
@EqualsAndHashCode(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SkitAppReleaseProfileDO extends BaseDO {

    @TableId
    private Long id;
    private Long tenantId;
    private String profileCode;
    private String channel;
    private String minNativeVersion;
    private String hotVersion;
    private String hotBundleUrl;
    private String hotBundleSha256;
    private Long hotReleaseNo;
    private String hotManifestSignature;
    private String nativeVersion;
    private String nativePackage;
    private Integer nativeProtocolVersion;
    private String runtimeUpdatePublicKey;
    private String runtimeUpdateKeyFingerprint;
    private Integer status;

}
