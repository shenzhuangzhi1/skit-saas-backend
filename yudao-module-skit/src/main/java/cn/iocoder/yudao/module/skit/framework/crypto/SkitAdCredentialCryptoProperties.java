package cn.iocoder.yudao.module.skit.framework.crypto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

@ConfigurationProperties(prefix = "skit.ad.credential-encryption")
public class SkitAdCredentialCryptoProperties {

    private String currentKeyId = "primary";
    private Map<String, String> keys = new LinkedHashMap<>();

    public String getCurrentKeyId() {
        return currentKeyId;
    }

    public void setCurrentKeyId(String currentKeyId) {
        this.currentKeyId = currentKeyId;
    }

    @JsonIgnore
    public Map<String, String> getKeys() {
        return keys;
    }

    public void setKeys(Map<String, String> keys) {
        this.keys = keys == null ? new LinkedHashMap<>() : new LinkedHashMap<>(keys);
    }

    @Override
    public String toString() {
        return "SkitAdCredentialCryptoProperties{currentKeyId='" + currentKeyId
                + "', configuredKeyIds=" + keys.keySet() + '}';
    }

}
