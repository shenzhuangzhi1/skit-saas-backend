package cn.iocoder.yudao.module.skit.service.content;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.HttpsURLConnection;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

@Component
public class PangleShortPlayHttpClient implements PangleShortPlayClient {

    static final String ENDPOINT =
            "https://csj-sp.csjdeveloper.com/csj_sp/openapi/v1/shortplay/get_sp_list";
    private static final String VERSION = "1.0";
    private static final int MAX_RESPONSE_BYTES = 1024 * 1024;
    private static final int MAX_TOTAL_EPISODES = 100_000;
    private static final char[] NONCE_ALPHABET =
            "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ".toCharArray();

    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final Supplier<String> nonceSupplier;
    private final Transport transport;

    @Autowired
    public PangleShortPlayHttpClient(ObjectMapper objectMapper) {
        SecureRandom random = new SecureRandom();
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.clock = Clock.systemUTC();
        this.nonceSupplier = () -> nonce(random);
        this.transport = new HutoolTransport();
    }

    PangleShortPlayHttpClient(ObjectMapper objectMapper, Clock clock,
                              Supplier<String> nonceSupplier, Transport transport) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.nonceSupplier = Objects.requireNonNull(nonceSupplier, "nonceSupplier");
        this.transport = Objects.requireNonNull(transport, "transport");
    }

    @Override
    public Drama fetchDrama(String siteIdInput, String serverKeyInput, long dramaId) {
        String siteId = siteId(siteIdInput);
        String serverKey = serverKey(serverKeyInput);
        if (dramaId <= 0 || dramaId > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Pangle drama id is invalid");
        }
        byte[] body = body(dramaId);
        String timestamp = Long.toString(clock.instant().getEpochSecond());
        String nonce = nonce(nonceSupplier.get());
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("x-csj-sp-site-id", siteId);
        headers.put("x-csj-sp-timestamp", timestamp);
        headers.put("x-csj-sp-nonce", nonce);
        headers.put("x-csj-sp-version", VERSION);
        headers.put("x-csj-sp-sign", sign(timestamp, siteId, VERSION, nonce, body, serverKey));
        TransportResponse response;
        try {
            response = transport.execute(new TransportRequest("POST", ENDPOINT, headers, body));
        } catch (Exception exception) {
            throw new IllegalStateException("Pangle short-play request failed");
        }
        return response(response, dramaId);
    }

    private byte[] body(long dramaId) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("uid", 0);
        root.put("page_size", 1);
        root.put("page", 1);
        root.put("order", 0);
        root.putArray("shortplay_ids").add(dramaId);
        root.putArray("category_id");
        root.put("query_type", "shortplay");
        try {
            return objectMapper.writeValueAsBytes(root);
        } catch (Exception exception) {
            throw new IllegalStateException("Could not encode Pangle short-play request");
        }
    }

    private Drama response(TransportResponse response, long requestedDramaId) {
        if (response == null || response.getStatusCode() < 200 || response.getStatusCode() >= 300) {
            throw new IllegalStateException("Pangle short-play HTTP status was not successful");
        }
        byte[] body = response.getBody();
        if (body.length == 0 || body.length > MAX_RESPONSE_BYTES) {
            throw new IllegalStateException("Pangle short-play response size is invalid");
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode data = root == null ? null : root.get("data");
            JsonNode list = data == null ? null : data.get("list");
            if (root == null || !root.isObject() || root.path("ret").asInt(Integer.MIN_VALUE) != 0
                    || data == null || !data.isObject() || data.path("total").asInt(-1) != 1
                    || data.path("has_more").asBoolean(true) || list == null || !list.isArray()
                    || list.size() != 1) {
                throw new IllegalStateException("Pangle short-play response is malformed");
            }
            JsonNode item = list.get(0);
            long dramaId = requiredPositiveLong(item, "shortplay_id");
            int totalEpisodes = requiredPositiveInt(item, "total");
            int completionStatus = item.path("status").asInt(-1);
            if (dramaId != requestedDramaId || totalEpisodes > MAX_TOTAL_EPISODES
                    || (completionStatus != 0 && completionStatus != 1)) {
                throw new IllegalStateException("Pangle short-play response crossed the requested scope");
            }
            return new Drama(dramaId,
                    requiredText(item, "title", 255),
                    optionalText(item, "desc", 4_000),
                    optionalText(item, "cover_image", 2_048),
                    nonNegativeLong(item, "category_id"),
                    optionalText(item, "category_name", 255),
                    totalEpisodes,
                    nonNegativeLong(item, "create_time"),
                    completionStatus);
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("Pangle short-play response is malformed");
        }
    }

    static String sign(String timestamp, String siteId, String version, String nonce,
                       byte[] body, String serverKey) {
        try {
            Mac hmac = Mac.getInstance("HmacSHA256");
            hmac.init(new SecretKeySpec(serverKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            hmac.update(timestamp.getBytes(StandardCharsets.UTF_8));
            hmac.update(siteId.getBytes(StandardCharsets.UTF_8));
            hmac.update(version.getBytes(StandardCharsets.UTF_8));
            hmac.update(nonce.getBytes(StandardCharsets.UTF_8));
            byte[] digest = hmac.doFinal(body);
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                hex.append(String.format("%02x", value & 0xff));
            }
            return hex.toString();
        } catch (Exception exception) {
            throw new IllegalStateException("Could not sign Pangle short-play request");
        }
    }

    private static String siteId(String value) {
        String required = value == null ? "" : value.trim();
        if (!required.matches("5\\d{6}")) {
            throw new IllegalArgumentException("Pangle site id is invalid");
        }
        return required;
    }

    private static String serverKey(String value) {
        String required = value == null ? "" : value;
        if (required.isEmpty() || !required.equals(required.trim()) || required.length() > 2_048) {
            throw new IllegalArgumentException("Pangle server key is unavailable");
        }
        return required;
    }

    private static String nonce(String value) {
        String required = value == null ? "" : value;
        if (!required.matches("[A-Za-z0-9]{16}")) {
            throw new IllegalArgumentException("Pangle request nonce is invalid");
        }
        return required;
    }

    private static String nonce(SecureRandom random) {
        char[] result = new char[16];
        for (int index = 0; index < result.length; index++) {
            result[index] = NONCE_ALPHABET[random.nextInt(NONCE_ALPHABET.length)];
        }
        return new String(result);
    }

    private static String requiredText(JsonNode node, String field, int maxLength) {
        String value = optionalText(node, field, maxLength);
        if (value.isEmpty()) {
            throw new IllegalStateException("Pangle short-play required text is missing");
        }
        return value;
    }

    private static String optionalText(JsonNode node, String field, int maxLength) {
        JsonNode value = node == null ? null : node.get(field);
        String text = value == null || value.isNull() ? "" : value.asText();
        if (!text.equals(text.trim()) || text.length() > maxLength) {
            throw new IllegalStateException("Pangle short-play text is invalid");
        }
        return text;
    }

    private static long requiredPositiveLong(JsonNode node, String field) {
        long value = nonNegativeLong(node, field);
        if (value <= 0) {
            throw new IllegalStateException("Pangle short-play positive number is missing");
        }
        return value;
    }

    private static int requiredPositiveInt(JsonNode node, String field) {
        long value = requiredPositiveLong(node, field);
        if (value > Integer.MAX_VALUE) {
            throw new IllegalStateException("Pangle short-play integer is too large");
        }
        return (int) value;
    }

    private static long nonNegativeLong(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || !value.canConvertToLong()) {
            throw new IllegalStateException("Pangle short-play number is malformed");
        }
        long parsed = value.longValue();
        if (parsed < 0) {
            throw new IllegalStateException("Pangle short-play number is negative");
        }
        return parsed;
    }

    public interface Transport {
        TransportResponse execute(TransportRequest request) throws Exception;
    }

    public static final class TransportRequest {
        private final String method;
        private final String url;
        private final Map<String, String> headers;
        private final byte[] body;

        public TransportRequest(String method, String url, Map<String, String> headers, byte[] body) {
            this.method = method;
            this.url = url;
            this.headers = Collections.unmodifiableMap(new LinkedHashMap<>(headers));
            this.body = body.clone();
        }

        public String getMethod() { return method; }
        public String getUrl() { return url; }
        public Map<String, String> getHeaders() { return headers; }
        public byte[] getBody() { return body.clone(); }

        @Override
        public String toString() {
            return "TransportRequest{method='" + method + "', url='" + url
                    + "', headers=<redacted>, bodyBytes=" + body.length + '}';
        }
    }

    public static final class TransportResponse {
        private final int statusCode;
        private final byte[] body;

        public TransportResponse(int statusCode, byte[] body) {
            this.statusCode = statusCode;
            this.body = Objects.requireNonNull(body, "body").clone();
        }

        public int getStatusCode() { return statusCode; }
        public byte[] getBody() { return body.clone(); }
    }

    private static final class HutoolTransport implements Transport {
        @Override
        public TransportResponse execute(TransportRequest request) {
            HttpRequest http = HttpRequest.post(request.getUrl())
                    .setHostnameVerifier(HttpsURLConnection.getDefaultHostnameVerifier())
                    .setSSLSocketFactory(HttpsURLConnection.getDefaultSSLSocketFactory())
                    .setFollowRedirects(false)
                    .timeout(20_000)
                    .body(request.getBody());
            for (Map.Entry<String, String> header : request.getHeaders().entrySet()) {
                http.header(header.getKey(), header.getValue());
            }
            try (HttpResponse response = http.execute()) {
                return new TransportResponse(response.getStatus(), response.bodyBytes());
            }
        }
    }
}
