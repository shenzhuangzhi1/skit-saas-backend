package cn.iocoder.yudao.module.skit.service.content;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PangleShortPlayHttpClientTest {

    private static final String RESPONSE = "{\"ret\":0,\"sub_ret\":\"\","
            + "\"msg\":\"success\",\"request_id\":\"request-1\",\"data\":{"
            + "\"total\":1,\"has_more\":false,\"list\":[{\"shortplay_id\":1631,"
            + "\"title\":\"人间至味是\",\"desc\":\"简介\","
            + "\"cover_image\":\"https://example.test/cover.jpg\","
            + "\"category_id\":12,\"category_name\":\"都市\","
            + "\"total\":88,\"create_time\":1760000000,\"status\":0}]}}";

    @Test
    void signsTheExactBodyAndReturnsOnlyTheRequestedDrama() {
        AtomicReference<PangleShortPlayHttpClient.TransportRequest> captured =
                new AtomicReference<>();
        PangleShortPlayHttpClient client = new PangleShortPlayHttpClient(
                new ObjectMapper(),
                Clock.fixed(Instant.ofEpochSecond(1_761_033_600L), ZoneOffset.UTC),
                () -> "0123456789abcdef",
                request -> {
                    captured.set(request);
                    return new PangleShortPlayHttpClient.TransportResponse(
                            200, RESPONSE.getBytes(StandardCharsets.UTF_8));
                });

        PangleShortPlayClient.Drama result =
                client.fetchDrama("5850994", "server-key-1", 1631L);

        assertEquals(1631L, result.getDramaId());
        assertEquals("人间至味是", result.getTitle());
        assertEquals(88, result.getTotalEpisodes());
        assertEquals(0, result.getCompletionStatus());
        assertEquals("POST", captured.get().getMethod());
        assertEquals("https://csj-sp.csjdeveloper.com/csj_sp/openapi/v1/shortplay/get_sp_list",
                captured.get().getUrl());
        assertEquals("{\"uid\":0,\"page_size\":1,\"page\":1,\"order\":0,"
                        + "\"shortplay_ids\":[1631],\"category_id\":[],"
                        + "\"query_type\":\"shortplay\"}",
                new String(captured.get().getBody(), StandardCharsets.UTF_8));
        assertEquals("5850994", captured.get().getHeaders().get("x-csj-sp-site-id"));
        assertEquals("1761033600", captured.get().getHeaders().get("x-csj-sp-timestamp"));
        assertEquals("0123456789abcdef", captured.get().getHeaders().get("x-csj-sp-nonce"));
        assertEquals("1.0", captured.get().getHeaders().get("x-csj-sp-version"));
        assertEquals("7c9713b5c5e59cee1eea796dfeb78824760c0d5f8f6c12a573ee61ad3e7be744",
                captured.get().getHeaders().get("x-csj-sp-sign"));
    }

    @Test
    void rejectsAResponseThatDoesNotContainExactlyTheRequestedDrama() {
        String wrong = RESPONSE.replace("\"shortplay_id\":1631", "\"shortplay_id\":1286");
        PangleShortPlayHttpClient client = new PangleShortPlayHttpClient(
                new ObjectMapper(),
                Clock.fixed(Instant.ofEpochSecond(1_761_033_600L), ZoneOffset.UTC),
                () -> "0123456789abcdef",
                request -> new PangleShortPlayHttpClient.TransportResponse(
                        200, wrong.getBytes(StandardCharsets.UTF_8)));

        assertThrows(IllegalStateException.class,
                () -> client.fetchDrama("5850994", "server-key-1", 1631L));
    }
}
