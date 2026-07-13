package cn.iocoder.yudao.module.skit.service.ad;

import lombok.Data;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Collection;
import java.util.List;
import java.util.Map;

public interface SkitAdAccountService {

    Settings getSettings();

    /** Platform-only batch read. Returned settings contain configuration flags but never raw credentials. */
    Map<Long, Settings> getSettingsMapForPlatform(Collection<Long> tenantIds);

    Settings saveSettings(Settings settings);

    /** Deliberately clears one provider's credentials and forces it disabled. */
    void clearCredentials(String provider);

    List<PublicConfig> getEnabledPublicConfigs();

    PublicConfig getEnabledPublicConfig(String provider);

    void ensureDefaultAccounts();

    @Data
    class Settings {
        private String pangleUsername;
        private String pangleAppId;
        @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
        private String pangleAppSecret;
        private String panglePlacementId;
        private Boolean pangleEnabled;
        private Boolean pangleSecretConfigured;
        private String takuUsername;
        private String takuAppId;
        /** Taku 客户端 SDK App Key；会进入白标 APK，因此不能当服务端密钥使用，也永不回显。 */
        @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
        private String takuAppKey;
        private Boolean takuAppKeyConfigured;
        /** Taku 服务端凭证；不是客户端 SDK App Key，且永不回显。 */
        @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
        private String takuAppSecret;
        private String takuPlacementId;
        private Boolean takuEnabled;
        private Boolean takuSecretConfigured;
    }

    @Data
    class PublicConfig {
        private String provider;
        private String appId;
        private String placementId;
        private Boolean enabled;
        private Boolean whiteLabelRequired;
    }

}
