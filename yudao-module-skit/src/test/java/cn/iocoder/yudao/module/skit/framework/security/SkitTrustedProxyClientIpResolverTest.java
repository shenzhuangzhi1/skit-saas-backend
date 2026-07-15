package cn.iocoder.yudao.module.skit.framework.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SkitTrustedProxyClientIpResolverTest {

    @Test
    void trustedDockerBridgeUsesStrictSingleRealIp() {
        SkitTrustedProxyClientIpResolver resolver = resolver("172.16.0.0/12");
        MockHttpServletRequest request = request("172.20.0.5");
        request.addHeader("X-Real-IP", "203.0.113.8");
        request.addHeader("X-Forwarded-For", "198.51.100.44, 203.0.113.8");

        assertEquals("203.0.113.8", resolver.resolve(request));
    }

    @Test
    void defaultRejectsForwardedTrustAndUntrustedPeerCannotSpoofHeaders() {
        SkitTrustedProxyClientIpResolver defaultResolver = resolver();
        MockHttpServletRequest defaultRequest = request("172.20.0.5");
        defaultRequest.addHeader("X-Real-IP", "203.0.113.8");
        assertEquals("172.20.0.5", defaultResolver.resolve(defaultRequest));

        SkitTrustedProxyClientIpResolver configured = resolver("172.16.0.0/12");
        MockHttpServletRequest direct = request("198.51.100.23");
        direct.addHeader("X-Real-IP", "203.0.113.99");
        direct.addHeader("X-Forwarded-For", "192.0.2.1");
        assertEquals("198.51.100.23", configured.resolve(direct));
    }

    @Test
    void trustedProxyRejectsCommaChainDuplicateHeaderAndInvalidIp() {
        SkitTrustedProxyClientIpResolver resolver = resolver("172.16.0.0/12");

        MockHttpServletRequest chain = request("172.20.0.5");
        chain.addHeader("X-Real-IP", "203.0.113.8, 198.51.100.2");
        assertThrows(IllegalArgumentException.class, () -> resolver.resolve(chain));

        MockHttpServletRequest duplicate = request("172.20.0.5");
        duplicate.addHeader("X-Real-IP", "203.0.113.8");
        duplicate.addHeader("X-Real-IP", "203.0.113.9");
        assertThrows(IllegalArgumentException.class, () -> resolver.resolve(duplicate));

        MockHttpServletRequest invalid = request("172.20.0.5");
        invalid.addHeader("X-Real-IP", "example.com");
        assertThrows(IllegalArgumentException.class, () -> resolver.resolve(invalid));
    }

    @Test
    void invalidConfiguredCidrFailsClosedAtConstruction() {
        SkitTrustedProxyProperties properties = new SkitTrustedProxyProperties();
        properties.setTrustedProxyCidrs(Collections.singletonList("172.16.0.0/not-a-prefix"));
        assertThrows(IllegalArgumentException.class,
                () -> new SkitTrustedProxyClientIpResolver(properties));
    }

    private SkitTrustedProxyClientIpResolver resolver(String... cidrs) {
        SkitTrustedProxyProperties properties = new SkitTrustedProxyProperties();
        properties.setTrustedProxyCidrs(Arrays.asList(cidrs));
        return new SkitTrustedProxyClientIpResolver(properties);
    }

    private MockHttpServletRequest request(String remoteAddress) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(remoteAddress);
        return request;
    }
}
