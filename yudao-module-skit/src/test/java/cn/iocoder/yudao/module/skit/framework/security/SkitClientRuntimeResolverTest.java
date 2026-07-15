package cn.iocoder.yudao.module.skit.framework.security;

import cn.iocoder.yudao.module.skit.service.ad.SkitTenantAdCapabilityService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static cn.iocoder.yudao.framework.test.core.util.AssertUtils.assertServiceException;
import static cn.iocoder.yudao.module.skit.enums.ErrorCodeConstants.AD_ROLLOUT_NOT_READY;
import static org.junit.jupiter.api.Assertions.assertEquals;

class SkitClientRuntimeResolverTest {

    private final SkitClientRuntimeResolver resolver = new SkitClientRuntimeResolver();

    @AfterEach
    void resetRequest() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void requiresCanonicalNativeAndProtocolHeaders() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Skit-Native-Version", "2.4.0");
        request.addHeader("X-Skit-Ad-Protocol-Version", "1");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        SkitTenantAdCapabilityService.ClientRuntime runtime = resolver.resolve();

        assertEquals("2.4.0", runtime.getNativeVersion());
        assertEquals(1, runtime.getProtocolVersion());
    }

    @Test
    void rejectsMissingAliasOrMalformedHeadersFailClosed() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-App-Version", "2.4.0");
        request.addHeader("X-Skit-Ad-Protocol-Version", "01");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        assertServiceException(resolver::resolve, AD_ROLLOUT_NOT_READY,
                "CLIENT_RUNTIME_HEADERS_INVALID");
    }

}
