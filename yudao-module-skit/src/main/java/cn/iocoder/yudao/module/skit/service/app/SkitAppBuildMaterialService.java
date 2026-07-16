package cn.iocoder.yudao.module.skit.service.app;

import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

public interface SkitAppBuildMaterialService {

    MaterialView getMaterial(Long tenantId);

    MaterialView saveMaterial(MaterialCommand command);

    @Data
    @Accessors(chain = true)
    class MaterialCommand {
        private Long tenantId;
        private String apiBaseUrl;
        private String appName;
        private Long nativeVersionCode;
        private String nativeVersionName;
        private Long runtimeReleaseNo;
        private String pangleSettingsJson;
        private String releaseKeystoreBase64;
        private String storePassword;
        private String keyAlias;
        private String keyPassword;
        private String reason;
    }

    @Data
    @Accessors(chain = true)
    class MaterialView {
        private Long tenantId;
        private Integer materialVersion;
        private String apiBaseUrl;
        private String appName;
        private Long nativeVersionCode;
        private String nativeVersionName;
        private Long runtimeReleaseNo;
        private Boolean pangleSettingsConfigured;
        private Boolean signingConfigured;
        private Boolean takuAppKeyConfigured;
        private Boolean takuAccountConfigured;
        private Boolean appReleaseProfileConfigured;
        private LocalDateTime verifiedAt;

        public String auditCanonical() {
            return canonical(tenantId, materialVersion, apiBaseUrl, appName, nativeVersionCode,
                    nativeVersionName, runtimeReleaseNo, pangleSettingsConfigured, signingConfigured,
                    takuAppKeyConfigured, takuAccountConfigured, appReleaseProfileConfigured, verifiedAt);
        }
    }

    static String canonical(Object... values) {
        StringBuilder result = new StringBuilder();
        for (Object value : values) {
            String text = String.valueOf(value);
            result.append(text.length()).append(':').append(text);
        }
        return result.toString();
    }
}
