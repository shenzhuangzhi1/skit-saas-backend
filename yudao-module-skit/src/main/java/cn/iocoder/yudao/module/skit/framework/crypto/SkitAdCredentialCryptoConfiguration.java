package cn.iocoder.yudao.module.skit.framework.crypto;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SkitAdCredentialCryptoProperties.class)
public class SkitAdCredentialCryptoConfiguration {

    @Bean
    public SkitAdCredentialCryptoService skitAdCredentialCryptoService(
            SkitAdCredentialCryptoProperties properties) {
        Map<String, String> configuredKeys = new LinkedHashMap<>(properties.getKeys());
        String currentKey = properties.getCurrentKey();
        if (currentKey != null && !currentKey.isEmpty()) {
            String retainedCurrentKey = configuredKeys.putIfAbsent(properties.getCurrentKeyId(), currentKey);
            if (retainedCurrentKey != null && !retainedCurrentKey.equals(currentKey)) {
                throw new IllegalArgumentException("Credential encryption key id "
                        + properties.getCurrentKeyId() + " is configured with conflicting key material");
            }
        }
        Map<String, byte[]> keys = new LinkedHashMap<>();
        configuredKeys.forEach((keyId, value) -> {
            if (value != null && !value.isEmpty()) {
                for (int index = 0; index < value.length(); index++) {
                    if (value.charAt(index) > 0x7f) {
                        throw new IllegalArgumentException(
                                "Credential encryption key " + keyId + " must contain ASCII characters only");
                    }
                }
                keys.put(keyId, value.getBytes(StandardCharsets.US_ASCII));
            }
        });
        return new SkitAesGcmCredentialCryptoService(properties.getCurrentKeyId(), keys);
    }

}
