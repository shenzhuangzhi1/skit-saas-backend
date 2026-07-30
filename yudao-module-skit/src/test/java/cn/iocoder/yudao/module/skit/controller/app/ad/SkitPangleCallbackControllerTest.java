package cn.iocoder.yudao.module.skit.controller.app.ad;

import cn.iocoder.yudao.framework.common.biz.infra.logger.ApiErrorLogCommonApi;
import cn.iocoder.yudao.framework.tenant.core.aop.TenantIgnore;
import cn.iocoder.yudao.framework.web.core.handler.GlobalExceptionHandler;
import cn.iocoder.yudao.module.skit.framework.security.SkitTrustedProxyClientIpResolver;
import cn.iocoder.yudao.module.skit.framework.security.SkitTrustedProxyProperties;
import cn.iocoder.yudao.module.skit.service.ad.callback.SkitPangleCallbackIngressService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.test.web.servlet.MockMvc;

import javax.annotation.security.PermitAll;
import java.lang.reflect.Method;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class SkitPangleCallbackControllerTest {

    private SkitPangleCallbackIngressService ingressService;
    private SkitPangleCallbackController controller;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        ingressService = mock(SkitPangleCallbackIngressService.class);
        SkitTrustedProxyProperties properties = new SkitTrustedProxyProperties();
        properties.setTrustedProxyCidrs(Collections.singletonList("127.0.0.1/32"));
        controller = new SkitPangleCallbackController(ingressService,
                new SkitTrustedProxyClientIpResolver(properties));
        mockMvc = standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler(
                        "skit-test", mock(ApiErrorLogCommonApi.class)))
                .build();
    }

    @Test
    void rewardReturnsOnlyLiteralTrueJsonAfterDurableAttestation() throws Exception {
        String callbackKey = repeat('A', 43);
        String rawQuery = "user_id=u1&trans_id=t1";
        MockHttpServletRequest request = request(rawQuery);
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(ingressService.receiveReward(callbackKey, rawQuery, "127.0.0.1"))
                .thenReturn(true);

        controller.reward(callbackKey, request, response);

        assertEquals(200, response.getStatus());
        assertEquals("application/json;charset=UTF-8", response.getContentType());
        assertEquals("no-store", response.getHeader("Cache-Control"));
        assertEquals("{\"isValid\":true}", response.getContentAsString());
        verify(ingressService).receiveReward(callbackKey, rawQuery, "127.0.0.1");
    }

    @Test
    void deterministicRejectionReturnsOnlyLiteralFalseJson() throws Exception {
        String callbackKey = repeat('B', 43);
        when(ingressService.receiveReward(callbackKey, "invalid=query", "127.0.0.1"))
                .thenReturn(false);

        mockMvc.perform(get("/skit/ad-callback/pangle/{callbackKey}/reward", callbackKey)
                        .queryParam("invalid", "query")
                        .with(request -> {
                            request.setRemoteAddr("127.0.0.1");
                            return request;
                        }))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().contentType("application/json;charset=UTF-8"))
                .andExpect(content().string("{\"isValid\":false}"));
    }

    @Test
    void infrastructureFailureIsHttp5xxEvenWithGlobalExceptionHandler() throws Exception {
        String callbackKey = repeat('C', 43);
        when(ingressService.receiveReward(callbackKey, "user_id=u1", "127.0.0.1"))
                .thenThrow(new IllegalStateException("database unavailable"));

        mockMvc.perform(get("/skit/ad-callback/pangle/{callbackKey}/reward", callbackKey)
                        .queryParam("user_id", "u1")
                        .with(request -> {
                            request.setRemoteAddr("127.0.0.1");
                            return request;
                        }))
                .andExpect(status().is5xxServerError())
                .andExpect(result -> assertEquals(false,
                        "{\"isValid\":false}".equals(result.getResponse().getContentAsString())));
    }

    @Test
    void routeIsPublicTenantIgnoredAndGetOnly() throws Exception {
        assertNotNull(SkitPangleCallbackController.class.getAnnotation(PermitAll.class));
        assertNotNull(SkitPangleCallbackController.class.getAnnotation(TenantIgnore.class));
        RequestMapping root = SkitPangleCallbackController.class.getAnnotation(RequestMapping.class);
        assertArrayEquals(new String[]{"/skit/ad-callback/pangle/{callbackKey}"}, root.value());
        Method reward = SkitPangleCallbackController.class.getMethod("reward", String.class,
                javax.servlet.http.HttpServletRequest.class,
                javax.servlet.http.HttpServletResponse.class);
        assertArrayEquals(new String[]{"/reward"}, reward.getAnnotation(GetMapping.class).value());
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
