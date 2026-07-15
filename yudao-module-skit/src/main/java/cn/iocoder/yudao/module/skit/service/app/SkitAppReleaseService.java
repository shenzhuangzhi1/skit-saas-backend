package cn.iocoder.yudao.module.skit.service.app;

import lombok.Data;

public interface SkitAppReleaseService {

    Manifest current(String profileCode, String nativeVersion);

    ProfileView getProfile(Long tenantId);

    ProfileView saveProfile(ProfileView profile);

    void ensureProfile(Long tenantId, String profileCode);

    void renameProfile(Long tenantId, String profileCode);

    @Data
    class Manifest {
        private boolean updateAvailable;
        private String hotVersion;
        private String tenantId;
        private String applicationId;
        private String bundleUrl;
        private String bundleSha256;
        private Integer protocolVersion;
        private Long releaseNo;
        private String signature;
        private String minNativeVersion;
    }

    @Data
    class ProfileView {
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

}
