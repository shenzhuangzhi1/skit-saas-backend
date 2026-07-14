package cn.iocoder.yudao.module.skit.service.ad;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class SkitHmacAdSessionTokenService implements SkitAdSessionTokenService {

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final byte[] SESSION_DOMAIN =
            "skit-ad-session-token-v1\0".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] MEMBER_DOMAIN =
            "skit-ad-pseudonymous-member-v1\0".getBytes(StandardCharsets.US_ASCII);

    private final int currentKeyVersion;
    private final Map<Integer, byte[]> keys;

    public SkitHmacAdSessionTokenService(int currentKeyVersion, Map<Integer, byte[]> configuredKeys) {
        if (currentKeyVersion <= 0) {
            throw new IllegalArgumentException("Session token key version must be positive");
        }
        Objects.requireNonNull(configuredKeys, "configuredKeys");
        this.keys = new LinkedHashMap<>();
        configuredKeys.forEach((version, key) -> {
            if (version == null || version <= 0 || key == null || key.length < 32) {
                throw new IllegalArgumentException("Every session token key must have a positive version and at least 32 bytes");
            }
            this.keys.put(version, key.clone());
        });
        if (!this.keys.containsKey(currentKeyVersion)) {
            throw new IllegalArgumentException("Current session token key version is not configured");
        }
        this.currentKeyVersion = currentKeyVersion;
    }

    @Override
    public IssuedToken issue(String sessionId) {
        return restore(sessionId, currentKeyVersion);
    }

    @Override
    public IssuedToken restore(String sessionId, int keyVersion) {
        byte[] key = keys.get(keyVersion);
        if (key == null) {
            throw new IllegalStateException("Session token key version " + keyVersion + " is unavailable");
        }
        byte[] sessionBytes = requireAscii(sessionId, "sessionId");
        byte[] raw = hmac(key, SESSION_DOMAIN, sessionBytes);
        String customData = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        return new IssuedToken(keyVersion, sha256(customData.getBytes(StandardCharsets.US_ASCII)), customData);
    }

    @Override
    public String pseudonymousUserId(long tenantId, long memberId) {
        if (tenantId <= 0 || memberId <= 0) {
            throw new IllegalArgumentException("tenantId and memberId must be positive");
        }
        byte[] scopedId = (tenantId + ":" + memberId).getBytes(StandardCharsets.US_ASCII);
        byte[] value = hmac(keys.get(currentKeyVersion), MEMBER_DOMAIN, scopedId);
        return "m_" + Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    @Override
    public boolean matches(String customData, byte[] expectedHash) {
        if (customData == null || customData.isEmpty() || expectedHash == null) {
            return false;
        }
        return MessageDigest.isEqual(sha256(customData.getBytes(StandardCharsets.US_ASCII)), expectedHash);
    }

    private static byte[] requireAscii(String value, String field) {
        if (value == null || value.isEmpty() || value.length() > 128) {
            throw new IllegalArgumentException(field + " must contain between 1 and 128 ASCII characters");
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character < 0x21 || character > 0x7e) {
                throw new IllegalArgumentException(field + " must contain printable ASCII characters only");
            }
        }
        return value.getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] hmac(byte[] key, byte[] domain, byte[] value) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(key, HMAC_ALGORITHM));
            mac.update(domain);
            return mac.doFinal(value);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("HMAC-SHA-256 is unavailable", ex);
        }
    }

    private static byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

}
