package cn.iocoder.yudao.module.skit.service.reconciliation;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import javax.net.ssl.HttpsURLConnection;

@Component
public class TakuReportingHttpClient implements TakuReportingClient {

    static final String ENDPOINT = "https://openapi.toponad.com/v2/fullreport";
    private static final String PATH = "/v2/fullreport";
    private static final int PAGE_SIZE = 1_000;
    private static final int MAX_PAGES = 100;
    private static final int MAX_RESPONSE_BYTES = 4 * 1024 * 1024;
    private static final DateTimeFormatter DATE = DateTimeFormatter.BASIC_ISO_DATE;

    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final Transport transport;

    @Autowired
    public TakuReportingHttpClient(ObjectMapper objectMapper) {
        this(objectMapper, Clock.systemUTC(), new HutoolTransport());
    }

    public TakuReportingHttpClient(ObjectMapper objectMapper, Clock clock, Transport transport) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.transport = Objects.requireNonNull(transport, "transport");
    }

    @Override
    public ReportResponse fetch(ReportRequest request, byte[] publisherKey) {
        Objects.requireNonNull(request, "request");
        String key = publisherKey(publisherKey);
        List<ReportRecord> records = new ArrayList<>();
        Long expectedCount = null;
        for (int page = 0; page < MAX_PAGES; page++) {
            byte[] body = body(request, records.size());
            String timestamp = String.valueOf(clock.millis());
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("Content-Type", "application/json");
            headers.put("X-Up-Key", key);
            headers.put("X-Up-Timestamp", timestamp);
            headers.put("X-Up-Signature", sign("POST", PATH, body, key, timestamp));

            TransportResponse response;
            try {
                response = transport.execute(new TransportRequest("POST", ENDPOINT, headers, body));
            } catch (Exception ignored) {
                throw new IllegalStateException("Taku reporting request failed");
            }
            JsonNode root = response(request, response);
            JsonNode pageRecords = root.get("records");
            if (pageRecords == null || !pageRecords.isArray()) {
                throw new IllegalStateException("Taku report records are malformed");
            }
            Long declaredCount = optionalDeclaredCount(root, "count");
            if (declaredCount == null && pageRecords.size() != 0) {
                throw new IllegalStateException("Taku report count is required for non-empty records");
            }
            long count = declaredCount == null ? 0L : declaredCount;
            if (expectedCount == null) {
                expectedCount = count;
            } else if (expectedCount.longValue() != count) {
                throw new IllegalStateException("Taku report count changed while paging");
            }
            for (JsonNode record : pageRecords) {
                records.add(record(request, record));
                if (records.size() > expectedCount) {
                    throw new IllegalStateException("Taku report returned more records than declared");
                }
            }
            if (records.size() == expectedCount) {
                return new ReportResponse(request.getReportTimezone(), request.getCurrency(), records);
            }
            if (pageRecords.size() == 0) {
                throw new IllegalStateException("Taku report pagination stopped before declared count");
            }
        }
        throw new IllegalStateException("Taku report exceeded the bounded page limit");
    }

    private byte[] body(ReportRequest request, int start) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("startdate", Integer.parseInt(DATE.format(request.getStartDate())));
        root.put("enddate", Integer.parseInt(DATE.format(request.getEndDate())));
        root.putArray("app_id_list").add(request.getAppId());
        root.putArray("placement_id_list").add(request.getPlacementId());
        root.put("time_zone", request.getReportTimezone());
        ArrayNode groupBy = root.putArray("group_by");
        for (String dimension : new String[]{"date", "app", "placement", "network_firm_id",
                "network", "adsource"}) {
            groupBy.add(dimension);
        }
        ArrayNode metric = root.putArray("metric");
        metric.add("revenue");
        metric.add("impression_api");
        root.put("start", start);
        root.put("limit", PAGE_SIZE);
        try {
            return objectMapper.writeValueAsBytes(root);
        } catch (Exception exception) {
            throw new IllegalStateException("Could not encode Taku report request");
        }
    }

    private JsonNode response(ReportRequest request, TransportResponse response) {
        if (response == null || response.getStatusCode() < 200 || response.getStatusCode() >= 300) {
            throw new IllegalStateException("Taku reporting HTTP status was not successful");
        }
        byte[] body = response.getBody();
        if (body.length == 0 || body.length > MAX_RESPONSE_BYTES) {
            throw new IllegalStateException("Taku reporting response size is invalid");
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            if (root == null || !root.isObject()
                    || !request.getReportTimezone().equals(text(root, "time_zone"))
                    || !request.getCurrency().equals(text(root, "currency"))) {
                throw new IllegalStateException("Taku report timezone or currency does not match account configuration");
            }
            return root;
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("Taku reporting response is malformed");
        }
    }

    private ReportRecord record(ReportRequest request, JsonNode node) {
        try {
            LocalDate reportDate = LocalDate.parse(text(node, "date"), DATE);
            String appId = nestedId(node, "app", "id");
            String placementId = nestedId(node, "placement", "id");
            int networkFirmId = Integer.parseInt(text(node, "network_firm_id"));
            String networkAccountId = scalar(node.get("network"));
            String adsourceId = nestedId(node, "adsource", "adsource_id");
            String revenue = text(node, "revenue");
            Long impressions = optionalNonNegativeLong(node, "impression_api");
            BigDecimal parsedRevenue = new BigDecimal(revenue);
            if (parsedRevenue.signum() < 0 || parsedRevenue.scale() > 18 || networkFirmId <= 0
                    || reportDate.isBefore(request.getStartDate()) || reportDate.isAfter(request.getEndDate())
                    || !request.getAppId().equals(appId)
                    || !request.getPlacementId().equals(placementId)) {
                throw new IllegalStateException("Taku report record crossed the requested traffic boundary");
            }
            return new ReportRecord(reportDate, appId, placementId, request.getAdFormat(), networkFirmId,
                    canonical(networkAccountId), canonical(adsourceId), revenue, impressions);
        } catch (DateTimeParseException | NumberFormatException exception) {
            throw new IllegalStateException("Taku report record is malformed");
        }
    }

    static String sign(String method, String path, byte[] body, String key, String timestamp) {
        if (!"POST".equals(method) || !PATH.equals(path)) {
            throw new IllegalArgumentException("Unsupported Taku reporting signature target");
        }
        String canonical = "POST\n" + md5(body) + "\napplication/json\n"
                + "X-Up-Key:" + key + "\nX-Up-Timestamp:" + timestamp + "\n" + PATH;
        return md5(canonical.getBytes(StandardCharsets.UTF_8));
    }

    private static String md5(byte[] value) {
        try {
            byte[] digest = MessageDigest.getInstance("MD5").digest(value);
            StringBuilder result = new StringBuilder(32);
            for (byte item : digest) {
                result.append(String.format("%02X", item & 0xff));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("MD5 is unavailable");
        }
    }

    private static String publisherKey(byte[] value) {
        if (value == null || value.length == 0 || value.length > 512) {
            throw new IllegalArgumentException("Taku publisher key is unavailable");
        }
        for (byte item : value) {
            if (item < 0x21 || item > 0x7e) {
                throw new IllegalArgumentException("Taku publisher key encoding is invalid");
            }
        }
        return new String(value, StandardCharsets.US_ASCII);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return canonical(scalar(value));
    }

    private static String nestedId(JsonNode node, String field, String idField) {
        JsonNode value = node == null ? null : node.get(field);
        return value != null && value.isObject() ? text(value, idField) : canonical(scalar(value));
    }

    private static String scalar(JsonNode value) {
        if (value == null || value.isNull() || (!value.isTextual() && !value.isNumber())) {
            throw new IllegalStateException("Taku report scalar is missing");
        }
        return value.asText();
    }

    private static String canonical(String value) {
        if (value == null || value.isEmpty() || !value.equals(value.trim()) || value.length() > 128) {
            throw new IllegalStateException("Taku report text is not canonical");
        }
        return value;
    }

    private static long requiredNonNegativeLong(JsonNode node, String field) {
        String raw = scalar(node == null ? null : node.get(field));
        try {
            long result = Long.parseLong(raw);
            if (result < 0L) {
                throw new IllegalStateException("Taku report count is negative");
            }
            return result;
        } catch (NumberFormatException exception) {
            throw new IllegalStateException("Taku report count is malformed");
        }
    }

    private static Long optionalNonNegativeLong(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        return requiredNonNegativeLong(node, field);
    }

    private static Long optionalDeclaredCount(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || value.isNull() || (value.isTextual() && value.asText().isEmpty())) {
            return null;
        }
        return requiredNonNegativeLong(node, field);
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
            HttpRequest http = secureHttpRequest(request);
            for (Map.Entry<String, String> header : request.getHeaders().entrySet()) {
                http.header(header.getKey(), header.getValue());
            }
            try (HttpResponse response = http.execute()) {
                return new TransportResponse(response.getStatus(), response.bodyBytes());
            }
        }
    }

    static HttpRequest secureHttpRequest(TransportRequest request) {
        return HttpRequest.post(request.getUrl())
                .setHostnameVerifier(HttpsURLConnection.getDefaultHostnameVerifier())
                .setSSLSocketFactory(HttpsURLConnection.getDefaultSSLSocketFactory())
                .setFollowRedirects(false)
                .timeout(20_000)
                .body(request.getBody());
    }
}
