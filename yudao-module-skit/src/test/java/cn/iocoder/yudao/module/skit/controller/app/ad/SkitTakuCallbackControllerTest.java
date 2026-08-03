package cn.iocoder.yudao.module.skit.controller.app.ad;

import cn.iocoder.yudao.framework.tenant.core.aop.TenantIgnore;
import cn.iocoder.yudao.module.skit.framework.security.SkitTrustedProxyClientIpResolver;
import cn.iocoder.yudao.module.skit.framework.security.SkitTrustedProxyProperties;
import cn.iocoder.yudao.module.skit.service.ad.callback.SkitCallbackRequestMetadata;
import cn.iocoder.yudao.module.skit.service.ad.callback.SkitTakuCallbackIngressDispatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import javax.annotation.security.PermitAll;
import java.lang.reflect.Method;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SkitTakuCallbackControllerTest {

    private SkitTakuCallbackIngressDispatcher dispatcher;
    private SkitTakuCallbackController controller;

    @BeforeEach
    void setUp() {
        dispatcher = mock(SkitTakuCallbackIngressDispatcher.class);
        SkitTrustedProxyProperties properties = new SkitTrustedProxyProperties();
        properties.setTrustedProxyCidrs(Arrays.asList("127.0.0.1/32", "172.16.0.0/12"));
        controller = new SkitTakuCallbackController(dispatcher,
                new SkitTrustedProxyClientIpResolver(properties));
    }

    @Test
    void rewardPassesUntouchedRawQueryAndReturnsTakuHttpStatus() {
        String key = repeat('A', 43);
        String rawQuery = "trans_id=show%2B1&ilrd=%7B%22network_firm_id%22%3A66%7D&sign="
                + "0123456789abcdef0123456789abcdef";
        MockHttpServletRequest request = request(rawQuery);
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(dispatcher.dispatch(eq(SkitTakuCallbackIngressDispatcher.CallbackType.REWARD),
                eq(key), eq(rawQuery), any(SkitCallbackRequestMetadata.class)))
                .thenReturn(SkitTakuCallbackIngressDispatcher.DispatchResponse.INVALID_SIGNATURE_601);

        controller.reward(key, request, response);

        assertEquals(601, response.getStatus());
        assertEquals("no-store", response.getHeader("Cache-Control"));
        verify(dispatcher).dispatch(eq(SkitTakuCallbackIngressDispatcher.CallbackType.REWARD),
                eq(key), eq(rawQuery), any(SkitCallbackRequestMetadata.class));
    }

    @Test
    void impressionReturnsOnlyAfterDurableIngressResult() {
        String key = repeat('B', 43);
        String rawQuery = "req_id=r1&show_custom_ext=session%2B1";
        MockHttpServletRequest request = request(rawQuery);
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(dispatcher.dispatch(eq(SkitTakuCallbackIngressDispatcher.CallbackType.IMPRESSION),
                eq(key), eq(rawQuery), any(SkitCallbackRequestMetadata.class)))
                .thenReturn(SkitTakuCallbackIngressDispatcher.DispatchResponse.ACK_200);

        controller.impression(key, request, response);

        assertEquals(200, response.getStatus());
        verify(dispatcher).dispatch(eq(SkitTakuCallbackIngressDispatcher.CallbackType.IMPRESSION),
                eq(key), eq(rawQuery), any(SkitCallbackRequestMetadata.class));
    }

    @Test
    void trustedDockerBridgeUsesItsOverwrittenRealIpHeader() {
        String key = repeat('D', 43);
        String rawQuery = "req_id=r1";
        MockHttpServletRequest request = request(rawQuery);
        request.setRemoteAddr("172.20.0.5");
        request.addHeader("X-Real-IP", "203.0.113.8");
        request.addHeader("X-Forwarded-For", "198.51.100.200, 203.0.113.8");
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(dispatcher.dispatch(eq(SkitTakuCallbackIngressDispatcher.CallbackType.IMPRESSION),
                eq(key), eq(rawQuery), any(SkitCallbackRequestMetadata.class)))
                .thenReturn(SkitTakuCallbackIngressDispatcher.DispatchResponse.ACK_200);

        controller.impression(key, request, response);

        assertEquals(200, response.getStatus());
        assertPackedClientAddress("203.0.113.8");
    }

    @Test
    void directClientCannotSpoofTrustedProxyIpHeader() {
        String key = repeat('E', 43);
        String rawQuery = "req_id=r1";
        MockHttpServletRequest request = request(rawQuery);
        request.setRemoteAddr("198.51.100.23");
        request.addHeader("X-Real-IP", "203.0.113.99");
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(dispatcher.dispatch(eq(SkitTakuCallbackIngressDispatcher.CallbackType.IMPRESSION),
                eq(key), eq(rawQuery), any(SkitCallbackRequestMetadata.class)))
                .thenReturn(SkitTakuCallbackIngressDispatcher.DispatchResponse.ACK_200);

        controller.impression(key, request, response);

        assertEquals(200, response.getStatus());
        assertPackedClientAddress("198.51.100.23");
    }

    @Test
    void trustedBridgeKeepsDistinctProviderClientAddressesDistinct() {
        String key = repeat('F', 43);
        String rawQuery = "req_id=r1";
        MockHttpServletRequest first = request(rawQuery);
        first.setRemoteAddr("172.20.0.5");
        first.addHeader("X-Real-IP", "203.0.113.8");
        MockHttpServletRequest second = request(rawQuery);
        second.setRemoteAddr("172.20.0.5");
        second.addHeader("X-Real-IP", "203.0.113.9");
        when(dispatcher.dispatch(eq(SkitTakuCallbackIngressDispatcher.CallbackType.IMPRESSION),
                eq(key), eq(rawQuery), any(SkitCallbackRequestMetadata.class)))
                .thenReturn(SkitTakuCallbackIngressDispatcher.DispatchResponse.ACK_200);

        controller.impression(key, first, new MockHttpServletResponse());
        controller.impression(key, second, new MockHttpServletResponse());

        ArgumentCaptor<SkitCallbackRequestMetadata> metadata =
                ArgumentCaptor.forClass(SkitCallbackRequestMetadata.class);
        verify(dispatcher, org.mockito.Mockito.times(2)).dispatch(
                eq(SkitTakuCallbackIngressDispatcher.CallbackType.IMPRESSION),
                eq(key), eq(rawQuery), metadata.capture());
        assertArrayEquals(address("203.0.113.8"), metadata.getAllValues().get(0)
                .getPackedClientAddress());
        assertArrayEquals(address("203.0.113.9"), metadata.getAllValues().get(1)
                .getPackedClientAddress());
        metadata.getAllValues().forEach(SkitCallbackRequestMetadata::close);
    }

    @Test
    void transientFailureMapsToFixedServerFailureInsteadOfFalseAcknowledgement() {
        String key = repeat('C', 43);
        MockHttpServletRequest request = request("req_id=r1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(dispatcher.dispatch(eq(SkitTakuCallbackIngressDispatcher.CallbackType.IMPRESSION),
                eq(key), eq("req_id=r1"), any(SkitCallbackRequestMetadata.class)))
                .thenReturn(SkitTakuCallbackIngressDispatcher.DispatchResponse.FAILURE_503);

        controller.impression(key, request, response);

        assertEquals(503, response.getStatus());
    }

    @Test
    void routesArePublicTenantIgnoredAndGetOnly() throws Exception {
        assertNotNull(SkitTakuCallbackController.class.getAnnotation(PermitAll.class));
        assertNotNull(SkitTakuCallbackController.class.getAnnotation(TenantIgnore.class));
        RequestMapping root = SkitTakuCallbackController.class.getAnnotation(RequestMapping.class);
        assertArrayEquals(new String[]{"/skit/ad-callback/taku/{callbackKey}"}, root.value());

        Method reward = SkitTakuCallbackController.class.getMethod("reward", String.class,
                javax.servlet.http.HttpServletRequest.class, javax.servlet.http.HttpServletResponse.class);
        Method impression = SkitTakuCallbackController.class.getMethod("impression", String.class,
                javax.servlet.http.HttpServletRequest.class, javax.servlet.http.HttpServletResponse.class);
        assertArrayEquals(new String[]{"/reward"}, reward.getAnnotation(GetMapping.class).value());
        assertArrayEquals(new String[]{"/impression"}, impression.getAnnotation(GetMapping.class).value());
    }

    private static MockHttpServletRequest request(String rawQuery) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/callback");
        request.setQueryString(rawQuery);
        request.setRemoteAddr("127.0.0.1");
        return request;
    }

    private void assertPackedClientAddress(String expected) {
        ArgumentCaptor<SkitCallbackRequestMetadata> metadata =
                ArgumentCaptor.forClass(SkitCallbackRequestMetadata.class);
        verify(dispatcher).dispatch(eq(SkitTakuCallbackIngressDispatcher.CallbackType.IMPRESSION),
                any(String.class), any(String.class), metadata.capture());
        assertArrayEquals(address(expected), metadata.getValue().getPackedClientAddress());
        metadata.getValue().close();
    }

    private static byte[] address(String value) {
        try {
            return java.net.InetAddress.getByName(value).getAddress();
        } catch (java.net.UnknownHostException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String repeat(char value, int count) {
        char[] result = new char[count];
        java.util.Arrays.fill(result, value);
        return new String(result);
    }

}
