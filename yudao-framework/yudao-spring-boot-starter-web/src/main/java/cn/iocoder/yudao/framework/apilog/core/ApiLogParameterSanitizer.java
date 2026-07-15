package cn.iocoder.yudao.framework.apilog.core;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import static cn.iocoder.yudao.framework.common.util.json.JsonUtils.toJsonString;

/** Shared, fail-closed request/response redaction for persisted and development access logs. */
@Slf4j
public final class ApiLogParameterSanitizer {

    private static final String[] DEFAULT_SANITIZE_KEYS = new String[]{
            "password", "passwd", "pwd",
            "token", "accessToken", "refreshToken", "idToken", "authorization", "ticket",
            "secret", "clientSecret", "appSecret", "rewardSecret", "pangleAppSecret", "takuAppSecret",
            "publisherKey", "callbackKey", "apiKey", "accessKey", "privateKey", "encryptionKey", "signingKey",
            "credential"
    };
    private static final String[] DEFAULT_SENSITIVE_KEY_SUFFIXES = new String[]{
            "password", "passwd", "pwd", "token", "secret", "credential",
            "privatekey", "apikey", "accesskey", "callbackkey", "publisherkey", "encryptionkey", "signingkey",
            "ticket"
    };
    private static final String UNPARSEABLE_JSON_REDACTION = "<redacted-unparseable-json>";
    private static final String SUPPRESSED_EXCEPTION_DETAIL = "<suppressed>";
    /** Parsing for redaction must not use JsonUtils.parseTree because that helper logs raw failures. */
    private static final ObjectMapper REDACTION_OBJECT_MAPPER = new ObjectMapper();

    private ApiLogParameterSanitizer() {
    }

    public static String sanitizeMap(Map<String, ?> map, String[] sanitizeKeys) {
        if (CollUtil.isEmpty(map)) {
            return null;
        }
        Map<String, Object> sanitized = new LinkedHashMap<>();
        for (Map.Entry<String, ?> entry : map.entrySet()) {
            if (!isSensitiveKey(entry.getKey(), sanitizeKeys)) {
                sanitized.put(entry.getKey(), entry.getValue());
            }
        }
        return JsonUtils.toJsonString(sanitized);
    }

    public static String sanitizeJson(String jsonString, String[] sanitizeKeys) {
        if (StrUtil.isEmpty(jsonString)) {
            return null;
        }
        try {
            JsonNode rootNode = REDACTION_OBJECT_MAPPER.readTree(jsonString);
            sanitizeJson(rootNode, sanitizeKeys);
            return JsonUtils.toJsonString(rootNode);
        } catch (Exception invalidJson) {
            log.warn("[sanitizeJson][JSON parse failed; content replaced with a fixed redaction]");
            return UNPARSEABLE_JSON_REDACTION;
        }
    }

    public static String sanitizeResult(CommonResult<?> commonResult, String[] sanitizeKeys) {
        if (commonResult == null) {
            return null;
        }
        try {
            JsonNode rootNode = REDACTION_OBJECT_MAPPER.readTree(toJsonString(commonResult));
            if (rootNode.isObject()) {
                // Result messages can include rejected values or provider text; the numeric code is sufficient.
                ((ObjectNode) rootNode).put("msg", "");
            }
            sanitizeJson(rootNode.get("data"), sanitizeKeys);
            return JsonUtils.toJsonString(rootNode);
        } catch (Exception invalidJson) {
            log.warn("[sanitizeJson][response JSON parse failed; content replaced with a fixed redaction]");
            return UNPARSEABLE_JSON_REDACTION;
        }
    }

    /** Exception text is attacker-controlled; logging retains types but never messages. */
    public static String safeExceptionType(Throwable throwable) {
        return throwable == null ? null : throwable.getClass().getName();
    }

    /** Resolve a bounded root cause without ever materializing its message. */
    public static String safeRootCauseType(Throwable throwable) {
        if (throwable == null) {
            return null;
        }
        Throwable rootCause = throwable;
        for (int depth = 0; depth < 32; depth++) {
            Throwable cause = rootCause.getCause();
            if (cause == null || cause == rootCause) {
                break;
            }
            rootCause = cause;
        }
        return safeExceptionType(rootCause);
    }

    public static String suppressedExceptionDetail() {
        return SUPPRESSED_EXCEPTION_DETAIL;
    }

    private static void sanitizeJson(JsonNode node, String[] sanitizeKeys) {
        if (node == null) {
            return;
        }
        if (node.isArray()) {
            for (JsonNode childNode : node) {
                sanitizeJson(childNode, sanitizeKeys);
            }
            return;
        }
        if (!node.isObject()) {
            return;
        }
        Iterator<Map.Entry<String, JsonNode>> iterator = node.fields();
        while (iterator.hasNext()) {
            Map.Entry<String, JsonNode> entry = iterator.next();
            if (isSensitiveKey(entry.getKey(), sanitizeKeys)) {
                iterator.remove();
            } else {
                sanitizeJson(entry.getValue(), sanitizeKeys);
            }
        }
    }

    private static boolean isSensitiveKey(String key, String[] sanitizeKeys) {
        if (key == null) {
            return false;
        }
        String normalized = normalizeKey(key);
        if (containsNormalized(DEFAULT_SANITIZE_KEYS, normalized)
                || hasSensitiveSuffix(normalized)) {
            return true;
        }
        return containsNormalized(sanitizeKeys, normalized);
    }

    private static boolean containsNormalized(String[] keys, String normalized) {
        if (keys == null) {
            return false;
        }
        for (String key : keys) {
            if (key != null && normalized.equals(normalizeKey(key))) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasSensitiveSuffix(String normalized) {
        for (String suffix : DEFAULT_SENSITIVE_KEY_SUFFIXES) {
            if (normalized.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }

    /** Treat camelCase, snake_case, kebab-case and case-only variants as the same key. */
    private static String normalizeKey(String key) {
        String lowerCase = key.toLowerCase(Locale.ROOT);
        StringBuilder normalized = new StringBuilder(lowerCase.length());
        for (int i = 0; i < lowerCase.length(); i++) {
            char character = lowerCase.charAt(i);
            if (Character.isLetterOrDigit(character)) {
                normalized.append(character);
            }
        }
        return normalized.toString();
    }

}
