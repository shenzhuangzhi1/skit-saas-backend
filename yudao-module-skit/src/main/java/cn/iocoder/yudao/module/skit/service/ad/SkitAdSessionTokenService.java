package cn.iocoder.yudao.module.skit.service.ad;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.Arrays;

/**
 * Generates callback correlation material without persisting the bearer value.
 */
public interface SkitAdSessionTokenService {

    IssuedToken issue(String sessionId);

    IssuedToken restore(String sessionId, int keyVersion);

    String pseudonymousUserId(long tenantId, long memberId);

    boolean matches(String customData, byte[] expectedHash);

    final class IssuedToken {

        private final int keyVersion;
        private final byte[] tokenHash;
        @JsonIgnore
        private String customData;

        IssuedToken(int keyVersion, byte[] tokenHash, String customData) {
            this.keyVersion = keyVersion;
            this.tokenHash = tokenHash.clone();
            this.customData = customData;
        }

        public int getKeyVersion() {
            return keyVersion;
        }

        public byte[] getTokenHash() {
            return tokenHash.clone();
        }

        @JsonIgnore
        public synchronized String consumeCustomData() {
            if (customData == null) {
                throw new IllegalStateException("Session custom data has already been consumed");
            }
            String value = customData;
            customData = null;
            return value;
        }

        @Override
        public synchronized String toString() {
            return "IssuedToken{keyVersion=" + keyVersion + ", tokenHash="
                    + Arrays.toString(Arrays.copyOf(tokenHash, Math.min(4, tokenHash.length)))
                    + "..., customData=<write-only>}";
        }
    }

}
