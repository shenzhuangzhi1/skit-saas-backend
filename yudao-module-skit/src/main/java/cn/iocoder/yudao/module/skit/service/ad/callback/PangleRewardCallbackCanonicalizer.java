package cn.iocoder.yudao.module.skit.service.ad.callback;

import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Strictly parses an untouched Pangle reward callback query exactly once. */
@Component
public class PangleRewardCallbackCanonicalizer {

    public static final int MAX_RAW_QUERY_LENGTH = 8 * 1024;

    private static final int MAX_PARAMETER_COUNT = 16;
    private static final int MAX_VALUE_BYTES = 1024;
    private static final long MAX_REWARD_AMOUNT = 100_000_000L;
    private static final Pattern REWARD_AMOUNT = Pattern.compile("[0-9]{1,9}");
    private static final Pattern SIGNATURE = Pattern.compile("[0-9a-fA-F]{64}");
    private static final List<String> FIELDS = Collections.unmodifiableList(Arrays.asList(
            "user_id", "trans_id", "reward_name", "reward_amount", "extra", "sign"));
    private static final Set<String> ALLOW_LIST =
            Collections.unmodifiableSet(new HashSet<>(FIELDS));

    public PangleRewardCallback canonicalize(String rawQuery) {
        Map<String, String> values = parseRawQuery(rawQuery);
        validateRequiredNonEmpty(values);
        validateOpaque(values.get("user_id"));
        validateAsciiOpaque(values.get("trans_id"));
        validateOpaque(values.get("extra"));

        String rewardAmount = normalizeRewardAmount(values.get("reward_amount"));
        String signature = normalizeSignature(values.get("sign"));
        values.put("reward_amount", rewardAmount);
        values.put("sign", signature);

        return new PangleRewardCallback(values.get("user_id"), values.get("trans_id"),
                values.get("reward_name"), rewardAmount, values.get("extra"), signature,
                canonicalHash(values));
    }

    private static Map<String, String> parseRawQuery(String rawQuery) {
        if (rawQuery == null || rawQuery.isEmpty()) {
            throw failure(ErrorCode.MISSING_PARAMETER);
        }
        if (rawQuery.length() > MAX_RAW_QUERY_LENGTH) {
            throw failure(ErrorCode.QUERY_TOO_LONG);
        }
        for (int index = 0; index < rawQuery.length(); index++) {
            char current = rawQuery.charAt(index);
            if (current < 0x21 || current > 0x7e) {
                throw failure(ErrorCode.INVALID_ENCODING);
            }
        }

        Map<String, String> values = new LinkedHashMap<>();
        int pairStart = 0;
        int parameterCount = 0;
        while (pairStart <= rawQuery.length()) {
            if (++parameterCount > MAX_PARAMETER_COUNT) {
                throw failure(ErrorCode.QUERY_TOO_LONG);
            }
            int separatorIndex = rawQuery.indexOf('&', pairStart);
            int pairEnd = separatorIndex < 0 ? rawQuery.length() : separatorIndex;
            String pair = rawQuery.substring(pairStart, pairEnd);
            int equalsIndex = pair.indexOf('=');
            if (equalsIndex <= 0) {
                throw failure(ErrorCode.INVALID_ENCODING);
            }
            String name = decodeComponent(pair.substring(0, equalsIndex));
            if (!ALLOW_LIST.contains(name)) {
                throw failure(ErrorCode.UNKNOWN_PARAMETER);
            }
            if (values.containsKey(name)) {
                throw failure(ErrorCode.DUPLICATE_PARAMETER);
            }
            String value = decodeComponent(pair.substring(equalsIndex + 1));
            if (utf8Length(value) > MAX_VALUE_BYTES) {
                throw failure(ErrorCode.VALUE_TOO_LONG);
            }
            validateNoControls(value);
            values.put(name, value);
            if (separatorIndex < 0) {
                break;
            }
            pairStart = pairEnd + 1;
        }
        return values;
    }

    private static String decodeComponent(String raw) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(raw.length());
        for (int index = 0; index < raw.length(); index++) {
            char current = raw.charAt(index);
            if (current == '%') {
                if (index + 2 >= raw.length()) {
                    throw failure(ErrorCode.INVALID_ENCODING);
                }
                int high = Character.digit(raw.charAt(index + 1), 16);
                int low = Character.digit(raw.charAt(index + 2), 16);
                if (high < 0 || low < 0) {
                    throw failure(ErrorCode.INVALID_ENCODING);
                }
                bytes.write((high << 4) | low);
                index += 2;
            } else if (current == '+') {
                bytes.write(' ');
            } else {
                bytes.write((byte) current);
            }
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes.toByteArray())).toString();
        } catch (CharacterCodingException ex) {
            throw failure(ErrorCode.INVALID_ENCODING);
        }
    }

    private static void validateRequiredNonEmpty(Map<String, String> values) {
        for (String field : FIELDS) {
            if (!values.containsKey(field)) {
                throw failure(ErrorCode.MISSING_PARAMETER);
            }
            if (values.get(field).isEmpty()) {
                throw failure(ErrorCode.INVALID_VALUE);
            }
        }
    }

    private static void validateOpaque(String value) {
        for (int index = 0; index < value.length(); index++) {
            if (Character.isWhitespace(value.charAt(index))) {
                throw failure(ErrorCode.INVALID_VALUE);
            }
        }
    }

    private static void validateAsciiOpaque(String value) {
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (current < 0x21 || current > 0x7e) {
                throw failure(ErrorCode.INVALID_VALUE);
            }
        }
    }

    private static void validateNoControls(String value) {
        for (int index = 0; index < value.length(); index++) {
            if (Character.isISOControl(value.charAt(index)) || value.charAt(index) == '\u2028'
                    || value.charAt(index) == '\u2029') {
                throw failure(ErrorCode.INVALID_VALUE);
            }
        }
    }

    private static String normalizeRewardAmount(String value) {
        if (!REWARD_AMOUNT.matcher(value).matches()) {
            throw failure(ErrorCode.INVALID_VALUE);
        }
        try {
            long parsed = Long.parseLong(value);
            if (parsed <= 0 || parsed > MAX_REWARD_AMOUNT) {
                throw failure(ErrorCode.INVALID_VALUE);
            }
            return Long.toString(parsed);
        } catch (NumberFormatException ex) {
            throw failure(ErrorCode.INVALID_VALUE);
        }
    }

    private static String normalizeSignature(String value) {
        if (!SIGNATURE.matcher(value).matches()) {
            throw failure(ErrorCode.INVALID_SIGNATURE);
        }
        return value.toLowerCase(Locale.ROOT);
    }

    private static byte[] canonicalHash(Map<String, String> values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            updateLengthPrefixed(digest, "PANGLE_REWARD");
            for (String field : FIELDS) {
                updateLengthPrefixed(digest, field);
                updateLengthPrefixed(digest, values.get(field));
            }
            return digest.digest();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private static void updateLengthPrefixed(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update((byte) (bytes.length >>> 24));
        digest.update((byte) (bytes.length >>> 16));
        digest.update((byte) (bytes.length >>> 8));
        digest.update((byte) bytes.length);
        digest.update(bytes);
    }

    private static int utf8Length(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }

    private static CallbackFormatException failure(ErrorCode errorCode) {
        return new CallbackFormatException(errorCode);
    }

    public enum ErrorCode {
        QUERY_TOO_LONG,
        UNKNOWN_PARAMETER,
        DUPLICATE_PARAMETER,
        MISSING_PARAMETER,
        INVALID_ENCODING,
        INVALID_VALUE,
        VALUE_TOO_LONG,
        INVALID_SIGNATURE
    }

    /** The message deliberately excludes callback values and any routing/session data. */
    public static final class CallbackFormatException extends IllegalArgumentException {

        private final ErrorCode errorCode;

        private CallbackFormatException(ErrorCode errorCode) {
            super("invalid Pangle callback query: " + errorCode.name());
            this.errorCode = errorCode;
        }

        public ErrorCode getErrorCode() {
            return errorCode;
        }
    }
}
