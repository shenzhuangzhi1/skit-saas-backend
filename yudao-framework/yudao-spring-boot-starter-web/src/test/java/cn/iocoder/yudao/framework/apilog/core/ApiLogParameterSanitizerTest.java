package cn.iocoder.yudao.framework.apilog.core;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ApiLogParameterSanitizerTest {

    @Test
    void defaultKeysAreCaseAndSeparatorInsensitiveAndRetainSafeFields() {
        Map<String, Object> query = new LinkedHashMap<>();
        query.put("REWARD_SECRET", "reward-SENTINEL");
        query.put("publisher-key", "publisher-SENTINEL");
        query.put("Pangle_App_Secret", "pangle-SENTINEL");
        query.put("takuAppSecret", "taku-SENTINEL");
        query.put("api-token", "token-SENTINEL");
        query.put("ticket", "websocket-ticket-SENTINEL");
        query.put("reason", "safe reason");

        String sanitizedQuery = ApiLogParameterSanitizer.sanitizeMap(query, null);
        String sanitizedBody = ApiLogParameterSanitizer.sanitizeJson("{"
                + "\"Reward-Secret\":\"reward-SENTINEL\","
                + "\"nested\":{\"PUBLISHER_KEY\":\"publisher-SENTINEL\","
                + "\"client-secret\":\"client-SENTINEL\"},"
                + "\"reason\":\"safe reason\"}", null);

        assertThat(sanitizedQuery).contains("safe reason")
                .doesNotContain("reward-SENTINEL", "publisher-SENTINEL", "pangle-SENTINEL",
                        "taku-SENTINEL", "token-SENTINEL", "websocket-ticket-SENTINEL");
        assertThat(sanitizedBody).contains("safe reason")
                .doesNotContain("reward-SENTINEL", "publisher-SENTINEL", "client-SENTINEL");
    }

    @Test
    void annotationKeysUseTheSameNormalizedMatchingRule() {
        String sanitized = ApiLogParameterSanitizer.sanitizeJson(
                "{\"provider_opaque\":\"opaque-SENTINEL\",\"reason\":\"safe\"}",
                new String[]{"providerOpaque"});

        assertThat(sanitized).contains("safe").doesNotContain("opaque-SENTINEL");
    }

    @Test
    void secretMetadataFieldsRemainObservable() {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("credentialVersion", 7);
        metadata.put("rewardSecretVersion", 8);
        metadata.put("tokenCount", 9);
        metadata.put("callbackKeyId", 10);
        metadata.put("rewardSecretStatus", "ACTIVE");
        metadata.put("privateKeyFingerprint", "sha256:safe-fingerprint");
        metadata.put("publisherKeyFingerprint", "sha256:safe-publisher-fingerprint");

        String sanitized = ApiLogParameterSanitizer.sanitizeMap(metadata, null);
        String sanitizedJson = ApiLogParameterSanitizer.sanitizeJson("{"
                + "\"credentialVersion\":7,\"rewardSecretVersion\":8,\"tokenCount\":9,"
                + "\"callbackKeyId\":10,\"rewardSecretStatus\":\"ACTIVE\","
                + "\"privateKeyFingerprint\":\"sha256:safe-fingerprint\"}", null);

        assertThat(sanitized)
                .contains("credentialVersion", "7")
                .contains("rewardSecretVersion", "8")
                .contains("tokenCount", "9")
                .contains("callbackKeyId", "10")
                .contains("rewardSecretStatus", "ACTIVE")
                .contains("privateKeyFingerprint", "sha256:safe-fingerprint")
                .contains("publisherKeyFingerprint", "sha256:safe-publisher-fingerprint");
        assertThat(sanitizedJson)
                .contains("credentialVersion", "rewardSecretVersion", "tokenCount", "callbackKeyId")
                .contains("rewardSecretStatus", "privateKeyFingerprint", "sha256:safe-fingerprint");
    }

    @Test
    void responseLoggingSuppressesAttackerControlledResultMessage() {
        String secret = "result-message-SENTINEL-never-log";

        String sanitized = ApiLogParameterSanitizer.sanitizeResult(
                CommonResult.error(400, secret), null);

        assertThat(sanitized).contains("\"code\":400")
                .doesNotContain(secret);
    }

    @Test
    void websocketTicketIsRemovedFromRequestAndResponseLogs() {
        String ticket = "websocket-ticket-SENTINEL-never-log";
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ticket", ticket);
        payload.put("expiresInSeconds", 30);

        String sanitizedRequest = ApiLogParameterSanitizer.sanitizeMap(payload, null);
        String sanitizedResponse = ApiLogParameterSanitizer.sanitizeResult(
                CommonResult.success(payload), null);

        assertThat(sanitizedRequest).contains("expiresInSeconds").doesNotContain(ticket);
        assertThat(sanitizedResponse).contains("expiresInSeconds").doesNotContain(ticket);
    }

}
