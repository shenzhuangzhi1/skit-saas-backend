package cn.iocoder.yudao.module.skit.framework.web;

import cn.iocoder.yudao.framework.apilog.core.ApiRequestUrlResolver;
import cn.iocoder.yudao.framework.common.enums.WebFilterOrderEnum;
import cn.iocoder.yudao.framework.web.config.WebProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import javax.servlet.http.HttpServletRequest;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class SkitCallbackSecretSanitizingFilterTest {

    private static final String CALLBACK_KEY = "callbackKeySentinel_01234567890123456789012";
    private static final String RAW_URI = "/app-api/skit/ad-callback/taku/" + CALLBACK_KEY + "/reward";
    private static final String RAW_QUERY = "sign=signSentinel&extra_data=extraSentinel&ilrd=ilrdSentinel";

    @Test
    void callbackRouteGetsSafeLoggingAttributesWithoutMutatingRoutingInputs() throws Exception {
        WebProperties properties = new WebProperties();
        SkitCallbackSecretSanitizingFilter filter = new SkitCallbackSecretSanitizingFilter(properties);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", RAW_URI);
        request.setQueryString(RAW_QUERY);
        AtomicBoolean invoked = new AtomicBoolean();

        filter.doFilter(request, new MockHttpServletResponse(), (rawRequest, rawResponse) -> {
            invoked.set(true);
            HttpServletRequest routed = (HttpServletRequest) rawRequest;
            assertThat(routed.getRequestURI()).isEqualTo(RAW_URI);
            assertThat(routed.getQueryString()).isEqualTo(RAW_QUERY);
            assertThat(ApiRequestUrlResolver.resolve(routed))
                    .isEqualTo("/app-api/skit/ad-callback/taku/{callback-key}/reward");
            assertThat(ApiRequestUrlResolver.shouldSuppressParameters(routed)).isTrue();
        });

        assertThat(invoked).isTrue();
    }

    @Test
    void malformedCallbackSuffixIsRejectedBeforeDispatcherWithoutReenteringTheSafeLogUrl() throws Exception {
        String rawUri = "/app-api/skit/ad-callback/taku/" + CALLBACK_KEY
                + "/unknown/path-contains-private-value";
        MockHttpServletRequest request = new MockHttpServletRequest("GET", rawUri);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean invoked = new AtomicBoolean();

        new SkitCallbackSecretSanitizingFilter(new WebProperties()).doFilter(request,
                response, (rawRequest, rawResponse) -> invoked.set(true));

        assertThat(invoked).isFalse();
        assertThat(response.getStatus()).isEqualTo(602);
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(response.getContentAsString()).isEqualTo("602");
        assertThat(ApiRequestUrlResolver.resolve(request))
                .isEqualTo("/app-api/skit/ad-callback/taku/{callback-key}/unknown");
        assertThat(ApiRequestUrlResolver.resolve(request))
                .doesNotContain(CALLBACK_KEY, "path-contains-private-value");
    }

    @Test
    void nonGetCallbackIsRejectedBeforeSpringCanLogTheRawRoute() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", RAW_URI);
        request.setQueryString(RAW_QUERY);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean invoked = new AtomicBoolean();

        new SkitCallbackSecretSanitizingFilter(new WebProperties()).doFilter(request,
                response, (rawRequest, rawResponse) -> invoked.set(true));

        assertThat(invoked).isFalse();
        assertThat(response.getStatus()).isEqualTo(602);
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(ApiRequestUrlResolver.resolve(request))
                .isEqualTo("/app-api/skit/ad-callback/taku/{callback-key}/reward");
    }

    @Test
    void wrongLengthOrEncodedCallbackKeyIsRejectedBeforeDispatcher() throws Exception {
        String[] invalidKeys = {"short", CALLBACK_KEY + "x", "encoded%2Fcallback-key"};
        for (String invalidKey : invalidKeys) {
            MockHttpServletRequest request = new MockHttpServletRequest("GET",
                    "/app-api/skit/ad-callback/taku/" + invalidKey + "/impression");
            MockHttpServletResponse response = new MockHttpServletResponse();
            AtomicBoolean invoked = new AtomicBoolean();

            new SkitCallbackSecretSanitizingFilter(new WebProperties()).doFilter(request,
                    response, (rawRequest, rawResponse) -> invoked.set(true));

            assertThat(invoked).as(invalidKey).isFalse();
            assertThat(response.getStatus()).as(invalidKey).isEqualTo(602);
            assertThat(ApiRequestUrlResolver.resolve(request)).as(invalidKey)
                    .isEqualTo("/app-api/skit/ad-callback/taku/{callback-key}/impression");
        }
    }

    @Test
    void contextPathAndConfiguredAppPrefixAreHandledWithoutChangingTheRequest() throws Exception {
        WebProperties properties = new WebProperties();
        properties.getAppApi().setPrefix("/mobile-api/");
        String rawUri = "/gateway/mobile-api/skit/ad-callback/taku/" + CALLBACK_KEY + "/impression";
        MockHttpServletRequest request = new MockHttpServletRequest("GET", rawUri);
        request.setContextPath("/gateway");
        request.setQueryString(RAW_QUERY);

        new SkitCallbackSecretSanitizingFilter(properties).doFilter(request,
                new MockHttpServletResponse(), (rawRequest, rawResponse) -> {
                    HttpServletRequest routed = (HttpServletRequest) rawRequest;
                    assertThat(routed.getRequestURI()).isEqualTo(rawUri);
                    assertThat(routed.getQueryString()).isEqualTo(RAW_QUERY);
                    assertThat(ApiRequestUrlResolver.resolve(routed))
                            .isEqualTo("/gateway/mobile-api/skit/ad-callback/taku/{callback-key}/impression");
                });
    }

    @Test
    void nonCallbackRouteIsUntouched() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/app-api/skit/member/profile");

        new SkitCallbackSecretSanitizingFilter(new WebProperties()).doFilter(request,
                new MockHttpServletResponse(), (rawRequest, rawResponse) -> {
                    HttpServletRequest routed = (HttpServletRequest) rawRequest;
                    assertThat(ApiRequestUrlResolver.resolve(routed)).isEqualTo(routed.getRequestURI());
                    assertThat(ApiRequestUrlResolver.shouldSuppressParameters(routed)).isFalse();
                });
    }

    @Test
    void sanitizerIsRegisteredBeforeEveryRequestBodyAndAccessLoggingFilter() {
        FilterRegistrationBean<SkitCallbackSecretSanitizingFilter> registration =
                new SkitCallbackLogSafetyConfiguration().skitCallbackSecretSanitizingFilter(new WebProperties());

        assertThat(registration.getOrder()).isEqualTo(WebFilterOrderEnum.SENSITIVE_REQUEST_SANITIZER_FILTER);
        assertThat(registration.getOrder()).isLessThan(WebFilterOrderEnum.REQUEST_BODY_CACHE_FILTER);
        assertThat(registration.getOrder()).isLessThan(WebFilterOrderEnum.API_ACCESS_LOG_FILTER);
    }

}
