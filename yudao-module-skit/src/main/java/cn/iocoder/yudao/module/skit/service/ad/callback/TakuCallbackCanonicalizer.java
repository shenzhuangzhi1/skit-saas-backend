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
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Strictly parses untouched Taku GET query strings exactly once.
 *
 * <p>The parser intentionally does not accept framework-decoded parameter maps: duplicate fields,
 * malformed percent escapes and exact ILRD lexical values must survive until verification.</p>
 */
@Component
public class TakuCallbackCanonicalizer {

    public static final int MAX_RAW_QUERY_LENGTH = 32 * 1024;

    private static final int MAX_PARAMETER_COUNT = 24;
    private static final int DEFAULT_MAX_VALUE_BYTES = 512;
    private static final int MAX_ILRD_BYTES = 24 * 1024;
    private static final int MAX_EXTRA_DATA_BYTES = 1024;

    private static final Pattern FIELD_NAME = Pattern.compile("[a-z0-9_]{1,64}");
    private static final Pattern POSITIVE_INTEGER = Pattern.compile("[1-9][0-9]{0,18}");
    private static final Pattern REWARD_AMOUNT = Pattern.compile("[1-9][0-9]{0,8}");
    private static final Pattern DECIMAL = Pattern.compile("(?:0|[1-9][0-9]{0,17})(?:\\.[0-9]{1,12})?");
    private static final Pattern SIGNATURE = Pattern.compile("[0-9a-fA-F]{32}");
    private static final Pattern CURRENCY = Pattern.compile("[A-Z]{3}");
    private static final Pattern UNSIGNED_INTEGER = Pattern.compile("[0-9]{1,19}");

    private static final List<String> REWARD_FIELDS = Collections.unmodifiableList(Arrays.asList(
            "user_id", "trans_id", "reward_amount", "reward_name", "placement_id", "extra_data",
            "network_firm_id", "adsource_id", "scenario_id", "package_name", "platform", "sign",
            "ilrd", "exch_rate_c2u", "is_test"));
    private static final Set<String> REWARD_ALLOW_LIST = immutableSet(REWARD_FIELDS);
    private static final List<String> REWARD_REQUIRED = Collections.unmodifiableList(Arrays.asList(
            "user_id", "trans_id", "reward_amount", "reward_name", "placement_id", "extra_data",
            "network_firm_id", "adsource_id", "sign"));
    private static final List<String> REWARD_PROBE_REQUIRED = Collections.unmodifiableList(Arrays.asList(
            "user_id", "trans_id", "reward_amount", "reward_name", "placement_id", "extra_data",
            "network_firm_id", "adsource_id", "sign", "ilrd"));

    private static final List<String> IMPRESSION_FIELDS = Collections.unmodifiableList(Arrays.asList(
            "user_id", "req_id", "geo_short", "package_name", "adformat", "placement_id",
            "nw_firm_id", "adsource_id", "adsource_price", "currency", "timestamp", "client_ip",
            "gaid", "oaid", "imei", "idfa", "idfv", "amazon_id", "show_custom_ext"));
    private static final Set<String> IMPRESSION_ALLOW_LIST = immutableSet(IMPRESSION_FIELDS);
    private static final List<String> IMPRESSION_REQUIRED = Collections.unmodifiableList(Arrays.asList(
            "user_id", "req_id", "package_name", "adformat", "placement_id", "adsource_id",
            "adsource_price", "currency", "timestamp"));

    public TakuRewardCallback canonicalizeReward(String rawQuery) {
        Map<String, String> values = parseRawQuery(rawQuery, REWARD_ALLOW_LIST);
        boolean probe = values.containsKey("is_test");
        if (probe) {
            validateProbe(values);
        } else {
            validateReward(values);
        }
        Integer observedNetworkFirmId = probe ? null : parsePositiveInt(values.get("network_firm_id"));
        return new TakuRewardCallback(values.get("user_id"), values.get("trans_id"),
                values.get("reward_amount"), values.get("reward_name"), values.get("placement_id"),
                values.get("extra_data"), observedNetworkFirmId, values.get("adsource_id"),
                values.get("scenario_id"), values.get("package_name"), values.get("platform"),
                values.get("sign"), values.get("ilrd"), values.get("exch_rate_c2u"), probe,
                canonicalHash("TAKU_REWARD", REWARD_FIELDS, values));
    }

    public TakuImpressionCallback canonicalizeImpression(String rawQuery) {
        Map<String, String> values = parseRawQuery(rawQuery, IMPRESSION_ALLOW_LIST);
        validateRequiredNonEmpty(values, IMPRESSION_REQUIRED);
        validateOpaque(values.get("user_id"));
        validateOpaque(values.get("req_id"));
        validateOpaque(values.get("package_name"));
        validateOpaque(values.get("placement_id"));
        values.put("adsource_id", normalizePositiveInteger(values.get("adsource_id")));
        if (!DECIMAL.matcher(values.get("adsource_price")).matches()) {
            throw failure(ErrorCode.INVALID_VALUE);
        }
        if (!CURRENCY.matcher(values.get("currency")).matches()) {
            throw failure(ErrorCode.INVALID_VALUE);
        }
        if (!POSITIVE_INTEGER.matcher(values.get("timestamp")).matches()) {
            throw failure(ErrorCode.INVALID_VALUE);
        }
        if (!"1".equals(values.get("adformat"))) {
            throw failure(ErrorCode.INVALID_VALUE);
        }
        Integer observedNetworkFirmId = hasValue(values, "nw_firm_id")
                ? parsePositiveInt(values.get("nw_firm_id")) : null;
        return new TakuImpressionCallback(values.get("user_id"), values.get("req_id"),
                values.get("package_name"), 1, values.get("placement_id"),
                observedNetworkFirmId, values.get("adsource_id"),
                values.get("adsource_price"), values.get("currency"), values.get("timestamp"),
                values.get("show_custom_ext"),
                canonicalHash("TAKU_IMPRESSION", IMPRESSION_FIELDS, values));
    }

    private static Map<String, String> parseRawQuery(String rawQuery, Set<String> allowList) {
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
            int separator = pair.indexOf('=');
            if (separator <= 0) {
                throw failure(ErrorCode.INVALID_ENCODING);
            }
            String name = decodeComponent(pair.substring(0, separator));
            if (!FIELD_NAME.matcher(name).matches()) {
                throw failure(ErrorCode.UNKNOWN_PARAMETER);
            }
            if (!allowList.contains(name)) {
                throw failure(ErrorCode.UNKNOWN_PARAMETER);
            }
            if (values.containsKey(name)) {
                throw failure(ErrorCode.DUPLICATE_PARAMETER);
            }
            String value = decodeComponent(pair.substring(separator + 1));
            if (utf8Length(value) > maxValueBytes(name)) {
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
                if (current > 0x7f) {
                    throw failure(ErrorCode.INVALID_ENCODING);
                }
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

    private static void validateReward(Map<String, String> values) {
        validateRequiredNonEmpty(values, REWARD_REQUIRED);
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (isExactKnownPlaceholder(entry.getKey(), entry.getValue())) {
                throw failure(ErrorCode.INVALID_PROBE);
            }
        }
        validateOpaque(values.get("user_id"));
        validateOpaque(values.get("trans_id"));
        validateOpaque(values.get("placement_id"));
        validateOpaque(values.get("extra_data"));
        validateOpaque(values.get("adsource_id"));
        if (!REWARD_AMOUNT.matcher(values.get("reward_amount")).matches()
                || Long.parseLong(values.get("reward_amount")) > 100_000_000L) {
            throw failure(ErrorCode.INVALID_VALUE);
        }
        if (!SIGNATURE.matcher(values.get("sign")).matches()) {
            throw failure(ErrorCode.INVALID_VALUE);
        }
        parsePositiveInt(values.get("network_firm_id"));
        if (hasValue(values, "platform") && !values.get("platform").matches("[12]")) {
            throw failure(ErrorCode.INVALID_VALUE);
        }
        if (hasValue(values, "exch_rate_c2u")
                && !DECIMAL.matcher(values.get("exch_rate_c2u")).matches()) {
            throw failure(ErrorCode.INVALID_VALUE);
        }
    }

    private static void validateProbe(Map<String, String> values) {
        if (!"1".equals(values.get("is_test"))) {
            throw failure(ErrorCode.INVALID_PROBE);
        }
        validateRequired(values, REWARD_PROBE_REQUIRED);
        for (String field : REWARD_PROBE_REQUIRED) {
            if (!("{" + field + "}").equals(values.get(field))) {
                throw failure(ErrorCode.INVALID_PROBE);
            }
        }
        for (Map.Entry<String, String> entry : values.entrySet()) {
            String field = entry.getKey();
            if ("is_test".equals(field) || "exch_rate_c2u".equals(field)
                    || REWARD_PROBE_REQUIRED.contains(field)) {
                continue;
            }
            if (!("{" + field + "}").equals(entry.getValue())) {
                throw failure(ErrorCode.INVALID_PROBE);
            }
        }
        if (values.containsKey("exch_rate_c2u")
                && !DECIMAL.matcher(values.get("exch_rate_c2u")).matches()) {
            throw failure(ErrorCode.INVALID_PROBE);
        }
    }

    private static void validateRequired(Map<String, String> values, List<String> required) {
        for (String field : required) {
            if (!values.containsKey(field)) {
                throw failure(ErrorCode.MISSING_PARAMETER);
            }
        }
    }

    private static void validateRequiredNonEmpty(Map<String, String> values, List<String> required) {
        validateRequired(values, required);
        for (String field : required) {
            if (values.get(field).isEmpty()) {
                throw failure(ErrorCode.INVALID_VALUE);
            }
        }
    }

    private static boolean hasValue(Map<String, String> values, String field) {
        return values.containsKey(field) && !values.get(field).isEmpty();
    }

    private static void validateOpaque(String value) {
        if (value == null || value.isEmpty()) {
            throw failure(ErrorCode.INVALID_VALUE);
        }
        for (int index = 0; index < value.length(); index++) {
            if (Character.isWhitespace(value.charAt(index))) {
                throw failure(ErrorCode.INVALID_VALUE);
            }
        }
    }

    private static void validateNoControls(String value) {
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (Character.isISOControl(current) || current == '\u2028' || current == '\u2029') {
                throw failure(ErrorCode.INVALID_VALUE);
            }
        }
    }

    private static boolean isExactKnownPlaceholder(String field, String value) {
        return ("{" + field + "}").equals(value);
    }

    private static int parsePositiveInt(String value) {
        if (value == null || !POSITIVE_INTEGER.matcher(value).matches()) {
            throw failure(ErrorCode.INVALID_VALUE);
        }
        try {
            int result = Integer.parseInt(value);
            if (result <= 0) {
                throw failure(ErrorCode.INVALID_VALUE);
            }
            return result;
        } catch (NumberFormatException ex) {
            throw failure(ErrorCode.INVALID_VALUE);
        }
    }

    private static String normalizePositiveInteger(String value) {
        if (value == null || !UNSIGNED_INTEGER.matcher(value).matches()) {
            throw failure(ErrorCode.INVALID_VALUE);
        }
        try {
            long result = Long.parseLong(value);
            if (result <= 0) {
                throw failure(ErrorCode.INVALID_VALUE);
            }
            return Long.toString(result);
        } catch (NumberFormatException ex) {
            throw failure(ErrorCode.INVALID_VALUE);
        }
    }

    private static byte[] canonicalHash(String domain, List<String> fieldOrder,
                                        Map<String, String> values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            updateLengthPrefixed(digest, domain);
            for (String field : fieldOrder) {
                if (values.containsKey(field)) {
                    updateLengthPrefixed(digest, field);
                    updateLengthPrefixed(digest, values.get(field));
                }
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

    private static int maxValueBytes(String field) {
        if ("ilrd".equals(field)) {
            return MAX_ILRD_BYTES;
        }
        if ("extra_data".equals(field) || "show_custom_ext".equals(field)) {
            return MAX_EXTRA_DATA_BYTES;
        }
        if ("sign".equals(field)) {
            return 32;
        }
        return DEFAULT_MAX_VALUE_BYTES;
    }

    private static int utf8Length(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }

    private static Set<String> immutableSet(List<String> values) {
        return Collections.unmodifiableSet(new HashSet<>(values));
    }

    private static CallbackParseException failure(ErrorCode errorCode) {
        return new CallbackParseException(errorCode);
    }

    public enum ErrorCode {
        QUERY_TOO_LONG,
        UNKNOWN_PARAMETER,
        DUPLICATE_PARAMETER,
        MISSING_PARAMETER,
        INVALID_ENCODING,
        INVALID_VALUE,
        VALUE_TOO_LONG,
        INVALID_PROBE
    }

    /** The message intentionally excludes callback values because they contain routing/session secrets. */
    public static final class CallbackParseException extends IllegalArgumentException {

        private final ErrorCode errorCode;

        private CallbackParseException(ErrorCode errorCode) {
            super("invalid Taku callback query: " + errorCode.name());
            this.errorCode = errorCode;
        }

        public ErrorCode getErrorCode() {
            return errorCode;
        }
    }

}
