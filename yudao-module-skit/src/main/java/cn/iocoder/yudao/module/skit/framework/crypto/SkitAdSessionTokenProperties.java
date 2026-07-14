package cn.iocoder.yudao.module.skit.framework.crypto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

@ConfigurationProperties(prefix = "skit.ad.session-token")
public class SkitAdSessionTokenProperties {

    private int currentKeyVersion = 1;
    private String currentKey = "";
    private Map<Integer, String> keys = new LinkedHashMap<>();

    public int getCurrentKeyVersion() {
        return currentKeyVersion;
    }

    public void setCurrentKeyVersion(int currentKeyVersion) {
        this.currentKeyVersion = currentKeyVersion;
    }

    @JsonIgnore
    public String getCurrentKey() {
        return currentKey;
    }

    public void setCurrentKey(String currentKey) {
        this.currentKey = currentKey == null ? "" : currentKey;
    }

    @JsonIgnore
    public Map<Integer, String> getKeys() {
        return keys;
    }

    public void setKeys(Map<Integer, String> keys) {
        this.keys = keys == null ? new LinkedHashMap<Integer, String>() : new LinkedHashMap<>(keys);
    }

    @Override
    public String toString() {
        return "SkitAdSessionTokenProperties{currentKeyVersion=" + currentKeyVersion
                + ", currentKeyConfigured=" + !currentKey.isEmpty()
                + ", retainedKeyVersions=" + keys.keySet() + '}';
    }

}
