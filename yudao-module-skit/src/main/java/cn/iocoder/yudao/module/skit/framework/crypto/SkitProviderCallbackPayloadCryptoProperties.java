package cn.iocoder.yudao.module.skit.framework.crypto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

@ConfigurationProperties(prefix = "skit.ad.provider-callback-payload-encryption")
public class SkitProviderCallbackPayloadCryptoProperties {

    private String currentKeyId = "primary";
    private String currentKey = "";
    private Map<String, String> keys = new LinkedHashMap<>();

    public String getCurrentKeyId() {
        return currentKeyId;
    }

    public void setCurrentKeyId(String currentKeyId) {
        this.currentKeyId = currentKeyId;
    }

    @JsonIgnore
    public String getCurrentKey() {
        return currentKey;
    }

    public void setCurrentKey(String currentKey) {
        this.currentKey = currentKey == null ? "" : currentKey;
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
        return "SkitProviderCallbackPayloadCryptoProperties{currentKeyId='" + currentKeyId
                + "', currentKeyConfigured=" + !currentKey.isEmpty()
                + ", retainedKeyIds=" + keys.keySet() + '}';
    }
}
