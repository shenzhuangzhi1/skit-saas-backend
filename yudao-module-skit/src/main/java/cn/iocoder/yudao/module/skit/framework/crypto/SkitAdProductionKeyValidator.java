package cn.iocoder.yudao.module.skit.framework.crypto;

import org.springframework.core.env.Environment;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Validates only the current production key material. Retained keys have their own keyring
 * validation and are deliberately excluded from error messages together with all key values.
 */
final class SkitAdProductionKeyValidator {

    private static final String FIELD_KEY_PROPERTY = "mybatis-plus.encryptor.password";
    private static final String CREDENTIAL_KEY_ID_PROPERTY =
            "skit.ad.credential-encryption.current-key-id";
    private static final String CREDENTIAL_KEY_PROPERTY =
            "skit.ad.credential-encryption.current-key";
    private static final String SESSION_KEY_VERSION_PROPERTY =
            "skit.ad.session-token.current-key-version";
    private static final String SESSION_KEY_PROPERTY = "skit.ad.session-token.current-key";

    private static final Pattern SAFE_KEY = Pattern.compile("[A-Za-z0-9._+/=-]+");
    private static final Pattern SAFE_KEY_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    private static final Pattern POSITIVE_INTEGER = Pattern.compile("[1-9][0-9]{0,9}");

    private final Environment environment;

    SkitAdProductionKeyValidator(Environment environment) {
        this.environment = Objects.requireNonNull(environment, "environment");
    }

    void validate() {
        String fieldKey = environment.getProperty(FIELD_KEY_PROPERTY, "");
        String credentialKeyId = environment.getProperty(CREDENTIAL_KEY_ID_PROPERTY, "");
        String credentialKey = environment.getProperty(CREDENTIAL_KEY_PROPERTY, "");
        String sessionVersion = environment.getProperty(SESSION_KEY_VERSION_PROPERTY, "");
        String sessionKey = environment.getProperty(SESSION_KEY_PROPERTY, "");

        List<String> violations = new ArrayList<>();
        validateAesKey(FIELD_KEY_PROPERTY, fieldKey, violations);
        validateAesKey(CREDENTIAL_KEY_PROPERTY, credentialKey, violations);
        validateSessionKey(sessionKey, violations);
        if (!SAFE_KEY_ID.matcher(credentialKeyId).matches()) {
            violations.add(CREDENTIAL_KEY_ID_PROPERTY + " must be a safe 1-64 character id");
        }
        validateSessionVersion(sessionVersion, violations);
        if (!fieldKey.isEmpty() && fieldKey.equals(credentialKey)) {
            violations.add(FIELD_KEY_PROPERTY + " and " + CREDENTIAL_KEY_PROPERTY + " must be distinct");
        }
        if (!sessionKey.isEmpty() && (sessionKey.equals(fieldKey) || sessionKey.equals(credentialKey))) {
            violations.add(SESSION_KEY_PROPERTY + " must be distinct from encryption keys");
        }
        if (!violations.isEmpty()) {
            throw new IllegalStateException("Invalid production advertising key configuration: "
                    + String.join("; ", violations));
        }
    }

    private static void validateAesKey(String property, String value, List<String> violations) {
        int length = value.length();
        if ((length != 16 && length != 24 && length != 32) || !SAFE_KEY.matcher(value).matches()) {
            violations.add(property + " must be a safe ASCII AES key of 16, 24, or 32 bytes");
        }
    }

    private static void validateSessionKey(String value, List<String> violations) {
        if (value.length() < 32 || !SAFE_KEY.matcher(value).matches()) {
            violations.add(SESSION_KEY_PROPERTY + " must contain at least 32 safe ASCII bytes");
        }
    }

    private static void validateSessionVersion(String value, List<String> violations) {
        if (!POSITIVE_INTEGER.matcher(value).matches()) {
            violations.add(SESSION_KEY_VERSION_PROPERTY + " must be a positive 32-bit integer");
            return;
        }
        try {
            if (Integer.parseInt(value) <= 0) {
                violations.add(SESSION_KEY_VERSION_PROPERTY + " must be a positive 32-bit integer");
            }
        } catch (NumberFormatException ignored) {
            violations.add(SESSION_KEY_VERSION_PROPERTY + " must be a positive 32-bit integer");
        }
    }

}
