package cn.iocoder.yudao.module.skit.service.reconciliation;

import cn.hutool.http.HttpRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import javax.net.ssl.HttpsURLConnection;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TakuReportingHttpClientTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final byte[] PUBLISHER_KEY = "publisher-key-test-only".getBytes(StandardCharsets.UTF_8);

    @Test
    void signsAndPostsBoundedFullReportPagesSeriallyWithoutPuttingKeyInBody() throws Exception {
        List<TakuReportingHttpClient.TransportRequest> requests = new ArrayList<>();
        TakuReportingHttpClient.Transport transport = request -> {
            requests.add(request);
            int call = requests.size();
            String record = call == 1
                    ? record("network-a", "101", "1.20", "3")
                    : record("network-b", "102", "2.30", "4");
            return new TakuReportingHttpClient.TransportResponse(200,
                    ("{\"records\":[" + record + "],\"count\":2,"
                            + "\"time_zone\":\"UTC+8\",\"currency\":\"USD\"}")
                            .getBytes(StandardCharsets.UTF_8));
        };
        Clock clock = Clock.fixed(Instant.ofEpochMilli(1_789_000_000_123L), ZoneOffset.UTC);
        TakuReportingClient client = new TakuReportingHttpClient(MAPPER, clock, transport);

        TakuReportingClient.ReportResponse response = client.fetch(request(), PUBLISHER_KEY);

        assertEquals(2, requests.size());
        assertEquals(2, response.getRecords().size());
        assertEquals("UTC+8", response.getReportTimezone());
        assertEquals("USD", response.getCurrency());
        assertEquals("network-a", response.getRecords().get(0).getNetworkAccountId());
        assertEquals("101", response.getRecords().get(0).getAdsourceId());
        assertEquals("1.20", response.getRecords().get(0).getRevenue());
        assertEquals(Long.valueOf(3L), response.getRecords().get(0).getImpressionApi());

        JsonNode firstBody = MAPPER.readTree(requests.get(0).getBody());
        JsonNode secondBody = MAPPER.readTree(requests.get(1).getBody());
        assertEquals(0, firstBody.get("start").asInt());
        assertEquals(1, secondBody.get("start").asInt());
        assertEquals(1000, firstBody.get("limit").asInt());
        assertTrue(firstBody.get("startdate").isIntegralNumber());
        assertTrue(firstBody.get("enddate").isIntegralNumber());
        assertEquals("20260713", firstBody.get("startdate").asText());
        assertEquals("20260713", firstBody.get("enddate").asText());
        assertEquals(1, firstBody.get("app_id_list").size());
        assertEquals("app-id", firstBody.get("app_id_list").get(0).asText());
        assertEquals(1, firstBody.get("placement_id_list").size());
        assertEquals("placement-id", firstBody.get("placement_id_list").get(0).asText());
        assertEquals("UTC+8", firstBody.get("time_zone").asText());
        assertFalse(firstBody.has("app_id"));
        assertFalse(firstBody.has("placement_id"));
        assertEquals(6, firstBody.get("group_by").size());
        assertEquals("date", firstBody.get("group_by").get(0).asText());
        assertEquals("network_firm_id", firstBody.get("group_by").get(3).asText());
        assertEquals("adsource", firstBody.get("group_by").get(5).asText());
        assertEquals("revenue", firstBody.get("metric").get(0).asText());
        assertEquals("impression_api", firstBody.get("metric").get(1).asText());
        assertFalse(new String(requests.get(0).getBody(), StandardCharsets.UTF_8)
                .contains(new String(PUBLISHER_KEY, StandardCharsets.UTF_8)));

        Map<String, String> headers = requests.get(0).getHeaders();
        assertEquals("application/json", headers.get("Content-Type"));
        assertEquals("1789000000123", headers.get("X-Up-Timestamp"));
        assertArrayEquals(PUBLISHER_KEY,
                headers.get("X-Up-Key").getBytes(StandardCharsets.UTF_8));
        assertEquals(TakuReportingHttpClient.sign("POST", "/v2/fullreport",
                requests.get(0).getBody(), headers.get("X-Up-Key"),
                headers.get("X-Up-Timestamp")), headers.get("X-Up-Signature"));
        assertTrue(headers.get("X-Up-Signature").matches("[0-9A-F]{32}"));
        assertFalse(requests.get(0).toString().contains(headers.get("X-Up-Key")));
    }

    @Test
    void malformedOrCrossCurrencyResponseFailsClosedWithoutLeakingPublisherKey() {
        TakuReportingHttpClient.Transport transport = request ->
                new TakuReportingHttpClient.TransportResponse(200,
                        "{\"records\":[],\"count\":0,\"time_zone\":\"UTC+0\",\"currency\":\"CNY\"}"
                                .getBytes(StandardCharsets.UTF_8));
        TakuReportingClient client = new TakuReportingHttpClient(MAPPER, Clock.systemUTC(), transport);

        RuntimeException exception = assertThrows(RuntimeException.class,
                () -> client.fetch(request(), PUBLISHER_KEY));

        assertFalse(exception.toString().contains(new String(PUBLISHER_KEY, StandardCharsets.UTF_8)));
    }

    @Test
    void missingImpressionMetricRemainsUnavailableSoReconciliationCanFailClosedToSuspense() {
        TakuReportingHttpClient.Transport transport = request ->
                new TakuReportingHttpClient.TransportResponse(200,
                        ("{\"records\":[{\"date\":\"20260713\","
                                + "\"app\":{\"id\":\"app-id\"},"
                                + "\"placement\":{\"id\":\"placement-id\"},"
                                + "\"network_firm_id\":\"7\",\"network\":\"network-a\","
                                + "\"adsource\":{\"adsource_id\":\"101\"},\"revenue\":\"1.20\"}],"
                                + "\"count\":1,\"time_zone\":\"UTC+8\",\"currency\":\"USD\"}")
                                .getBytes(StandardCharsets.UTF_8));
        TakuReportingClient client = new TakuReportingHttpClient(
                MAPPER, Clock.systemUTC(), transport);

        TakuReportingClient.ReportResponse response = client.fetch(request(), PUBLISHER_KEY);

        assertEquals(1, response.getRecords().size());
        assertNull(response.getRecords().get(0).getImpressionApi());
    }

    @Test
    void documentedEmptyDayNormalizesMissingNullOrEmptyCountToZero() {
        for (String countField : Arrays.asList("", ",\"count\":null", ",\"count\":\"\"")) {
            TakuReportingHttpClient.Transport transport = request ->
                    new TakuReportingHttpClient.TransportResponse(200,
                            ("{\"records\":[],\"time_zone\":\"UTC+8\",\"currency\":\"USD\""
                                    + countField + "}").getBytes(StandardCharsets.UTF_8));
            TakuReportingClient client = new TakuReportingHttpClient(
                    MAPPER, Clock.systemUTC(), transport);

            assertTrue(client.fetch(request(), PUBLISHER_KEY).getRecords().isEmpty());
        }
    }

    @Test
    void nonEmptyRecordsWithoutAValidDeclaredCountAreRejected() {
        TakuReportingHttpClient.Transport transport = request ->
                new TakuReportingHttpClient.TransportResponse(200,
                        ("{\"records\":[" + record("network-a", "101", "1.20", "3") + "],"
                                + "\"time_zone\":\"UTC+8\",\"currency\":\"USD\"}")
                                .getBytes(StandardCharsets.UTF_8));
        TakuReportingClient client = new TakuReportingHttpClient(
                MAPPER, Clock.systemUTC(), transport);

        assertThrows(IllegalStateException.class,
                () -> client.fetch(request(), PUBLISHER_KEY));
    }

    @Test
    void reportRequestRejectsUnboundedDatesOrMixedTrafficSelectors() {
        assertThrows(IllegalArgumentException.class, () -> new TakuReportingClient.ReportRequest(
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 4), "app", "placement",
                "UTC+8", "USD", 8));
        assertThrows(IllegalArgumentException.class, () -> new TakuReportingClient.ReportRequest(
                LocalDate.of(2026, 7, 13), LocalDate.of(2026, 7, 13), "", "placement",
                "UTC+8", "USD", 8));
    }

    @Test
    void reportRequestUsesOnlyOfficialFullReportTimezones() {
        for (String unsupported : Arrays.asList("UTC+1", "UTC-7", "UTC+14", "GMT+8")) {
            assertThrows(IllegalArgumentException.class, () -> new TakuReportingClient.ReportRequest(
                    LocalDate.of(2026, 7, 13), LocalDate.of(2026, 7, 13),
                    "app", "placement", unsupported, "USD", 8));
        }
        for (String supported : Arrays.asList("UTC-8", "UTC+8", "UTC+0")) {
            assertEquals(supported, new TakuReportingClient.ReportRequest(
                    LocalDate.of(2026, 7, 13), LocalDate.of(2026, 7, 13),
                    "app", "placement", supported, "USD", 8).getReportTimezone());
        }
    }

    @Test
    void publisherKeyTransportPinsJdkCertificateAndHostnameValidationWithoutRedirects() throws Exception {
        TakuReportingHttpClient.TransportRequest transportRequest =
                new TakuReportingHttpClient.TransportRequest("POST",
                        TakuReportingHttpClient.ENDPOINT, java.util.Collections.emptyMap(),
                        "{}".getBytes(StandardCharsets.UTF_8));

        HttpRequest request = TakuReportingHttpClient.secureHttpRequest(transportRequest);

        Field configField = HttpRequest.class.getDeclaredField("config");
        configField.setAccessible(true);
        Object config = configField.get(request);
        assertEquals(HttpsURLConnection.getDefaultHostnameVerifier(), field(config, "hostnameVerifier"));
        assertEquals(HttpsURLConnection.getDefaultSSLSocketFactory(), field(config, "ssf"));
        assertEquals(0, field(config, "maxRedirectCount"));
    }

    private static Object field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private TakuReportingClient.ReportRequest request() {
        return new TakuReportingClient.ReportRequest(LocalDate.of(2026, 7, 13),
                LocalDate.of(2026, 7, 13), "app-id", "placement-id", "UTC+8", "USD", 8);
    }

    private String record(String network, String adsourceId, String revenue, String impressions) {
        return "{\"date\":\"20260713\",\"app\":{\"id\":\"app-id\"},"
                + "\"placement\":{\"id\":\"placement-id\"},\"adformat\":\"Rewarded Video\","
                + "\"network_firm_id\":\"7\",\"network\":\"" + network + "\","
                + "\"adsource\":{\"adsource_id\":\"" + adsourceId + "\"},"
                + "\"revenue\":\"" + revenue + "\",\"impression_api\":\"" + impressions + "\"}";
    }
}
