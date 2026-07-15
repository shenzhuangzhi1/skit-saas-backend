package cn.iocoder.yudao.module.skit.controller.app.ad;

import cn.iocoder.yudao.framework.tenant.core.aop.TenantIgnore;
import cn.iocoder.yudao.module.skit.framework.security.SkitTrustedProxyClientIpResolver;
import cn.iocoder.yudao.module.skit.framework.security.SkitTrustedProxyProperties;
import cn.iocoder.yudao.module.skit.service.ad.callback.SkitCallbackIngressService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SkitTakuCallbackControllerTest {

    private SkitCallbackIngressService ingressService;
    private SkitTakuCallbackController controller;

    @BeforeEach
    void setUp() {
        ingressService = mock(SkitCallbackIngressService.class);
        SkitTrustedProxyProperties properties = new SkitTrustedProxyProperties();
        properties.setTrustedProxyCidrs(Arrays.asList("127.0.0.1/32", "172.16.0.0/12"));
        controller = new SkitTakuCallbackController(ingressService,
                new SkitTrustedProxyClientIpResolver(properties));
    }

    @Test
    void rewardPassesUntouchedRawQueryAndReturnsTakuHttpStatus() {
        String key = repeat('A', 43);
        String rawQuery = "trans_id=show%2B1&ilrd=%7B%22network_firm_id%22%3A66%7D&sign="
                + "0123456789abcdef0123456789abcdef";
        MockHttpServletRequest request = request(rawQuery);
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(ingressService.receiveReward(key, rawQuery, "127.0.0.1"))
                .thenReturn(SkitCallbackIngressService.IngressResponse.INVALID_SIGNATURE);

        controller.reward(key, request, response);

        assertEquals(601, response.getStatus());
        assertEquals("no-store", response.getHeader("Cache-Control"));
        verify(ingressService).receiveReward(key, rawQuery, "127.0.0.1");
    }

    @Test
    void impressionReturnsOnlyAfterDurableIngressResult() {
        String key = repeat('B', 43);
        String rawQuery = "req_id=r1&show_custom_ext=session%2B1";
        MockHttpServletRequest request = request(rawQuery);
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(ingressService.receiveImpression(key, rawQuery, "127.0.0.1"))
                .thenReturn(SkitCallbackIngressService.IngressResponse.OK);

        controller.impression(key, request, response);

        assertEquals(200, response.getStatus());
        verify(ingressService).receiveImpression(key, rawQuery, "127.0.0.1");
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
        when(ingressService.receiveImpression(key, rawQuery, "203.0.113.8"))
                .thenReturn(SkitCallbackIngressService.IngressResponse.OK);

        controller.impression(key, request, response);

        assertEquals(200, response.getStatus());
        verify(ingressService).receiveImpression(key, rawQuery, "203.0.113.8");
    }

    @Test
    void directClientCannotSpoofTrustedProxyIpHeader() {
        String key = repeat('E', 43);
        String rawQuery = "req_id=r1";
        MockHttpServletRequest request = request(rawQuery);
        request.setRemoteAddr("198.51.100.23");
        request.addHeader("X-Real-IP", "203.0.113.99");
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(ingressService.receiveImpression(key, rawQuery, "198.51.100.23"))
                .thenReturn(SkitCallbackIngressService.IngressResponse.OK);

        controller.impression(key, request, response);

        assertEquals(200, response.getStatus());
        verify(ingressService).receiveImpression(key, rawQuery, "198.51.100.23");
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
        when(ingressService.receiveImpression(key, rawQuery, "203.0.113.8"))
                .thenReturn(SkitCallbackIngressService.IngressResponse.OK);
        when(ingressService.receiveImpression(key, rawQuery, "203.0.113.9"))
                .thenReturn(SkitCallbackIngressService.IngressResponse.OK);

        controller.impression(key, first, new MockHttpServletResponse());
        controller.impression(key, second, new MockHttpServletResponse());

        verify(ingressService).receiveImpression(key, rawQuery, "203.0.113.8");
        verify(ingressService).receiveImpression(key, rawQuery, "203.0.113.9");
    }

    @Test
    void transientFailurePropagatesAsServerFailureInsteadOfFalseAcknowledgement() {
        String key = repeat('C', 43);
        MockHttpServletRequest request = request("req_id=r1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(ingressService.receiveImpression(key, "req_id=r1", "127.0.0.1"))
                .thenThrow(new IllegalStateException("database unavailable"));

        assertThrows(IllegalStateException.class,
                () -> controller.impression(key, request, response));
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

    private static String repeat(char value, int count) {
        char[] result = new char[count];
        java.util.Arrays.fill(result, value);
        return new String(result);
    }

}
