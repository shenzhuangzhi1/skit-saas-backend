package cn.iocoder.yudao.module.skit.framework.crypto;

import cn.iocoder.yudao.module.skit.service.ad.SkitAdSessionTokenService;
import cn.iocoder.yudao.module.skit.service.ad.SkitHmacAdSessionTokenService;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SkitAdSessionTokenProperties.class)
public class SkitAdSessionTokenConfiguration {

    @Bean
    public SkitAdSessionTokenService skitAdSessionTokenService(SkitAdSessionTokenProperties properties) {
        Map<Integer, String> configured = new LinkedHashMap<>(properties.getKeys());
        if (!properties.getCurrentKey().isEmpty()) {
            String retained = configured.putIfAbsent(properties.getCurrentKeyVersion(), properties.getCurrentKey());
            if (retained != null && !retained.equals(properties.getCurrentKey())) {
                throw new IllegalArgumentException("Session token key version "
                        + properties.getCurrentKeyVersion() + " has conflicting key material");
            }
        }
        if (configured.isEmpty() || !configured.containsKey(properties.getCurrentKeyVersion())) {
            return new MissingSessionTokenKeyService(properties.getCurrentKeyVersion());
        }
        Map<Integer, byte[]> keys = new LinkedHashMap<>();
        configured.forEach((version, value) -> {
            if (value == null) {
                return;
            }
            for (int index = 0; index < value.length(); index++) {
                if (value.charAt(index) > 0x7f) {
                    throw new IllegalArgumentException("Session token key " + version
                            + " must contain ASCII characters only");
                }
            }
            keys.put(version, value.getBytes(StandardCharsets.US_ASCII));
        });
        return new SkitHmacAdSessionTokenService(properties.getCurrentKeyVersion(), keys);
    }

    private static final class MissingSessionTokenKeyService implements SkitAdSessionTokenService {

        private final int currentKeyVersion;

        private MissingSessionTokenKeyService(int currentKeyVersion) {
            this.currentKeyVersion = currentKeyVersion;
        }

        @Override
        public IssuedToken issue(String sessionId) {
            throw unavailable();
        }

        @Override
        public IssuedToken restore(String sessionId, int keyVersion) {
            throw unavailable();
        }

        @Override
        public String pseudonymousUserId(long tenantId, long memberId) {
            throw unavailable();
        }

        @Override
        public boolean matches(String customData, byte[] expectedHash) {
            throw unavailable();
        }

        private IllegalStateException unavailable() {
            return new IllegalStateException("SKIT_AD_SESSION_TOKEN_KEY for version "
                    + currentKeyVersion + " is not configured");
        }
    }

}
