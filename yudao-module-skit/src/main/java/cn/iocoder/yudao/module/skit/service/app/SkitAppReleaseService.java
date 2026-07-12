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
        private String bundleUrl;
        private String sha256;
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
        private String nativeVersion;
        private String nativePackage;
        private Integer status;
    }

}
