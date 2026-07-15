package cn.iocoder.yudao.framework.apilog.core;

import cn.iocoder.yudao.framework.apilog.core.filter.ApiAccessLogFilter;
import cn.iocoder.yudao.framework.apilog.core.interceptor.ApiAccessLogInterceptor;
import cn.iocoder.yudao.framework.common.biz.infra.logger.ApiAccessLogCommonApi;
import cn.iocoder.yudao.framework.common.biz.infra.logger.ApiErrorLogCommonApi;
import cn.iocoder.yudao.framework.common.biz.infra.logger.dto.ApiAccessLogCreateReqDTO;
import cn.iocoder.yudao.framework.common.biz.infra.logger.dto.ApiErrorLogCreateReqDTO;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.util.servlet.ServletUtils;
import cn.iocoder.yudao.framework.common.util.spring.SpringUtils;
import cn.iocoder.yudao.framework.web.config.WebProperties;
import cn.iocoder.yudao.framework.web.core.handler.GlobalExceptionHandler;
import cn.iocoder.yudao.framework.web.core.util.WebFrameworkUtils;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedStatic;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.ServletInputStream;
import javax.servlet.http.HttpServletRequest;
import javax.validation.ValidationException;
import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mockStatic;

@ExtendWith(OutputCaptureExtension.class)
class CallbackSecretRedactionTest {

    private static final String CALLBACK_KEY = "callback-key-SENTINEL-never-log";
    private static final String SIGN = "sign-SENTINEL-never-log";
    private static final String EXTRA_DATA = "extra-data-SENTINEL-never-log";
    private static final String ILRD = "ilrd-SENTINEL-never-log";
    private static final String RAW_URI = "/app-api/skit/ad-callback/taku/" + CALLBACK_KEY + "/reward";
    private static final String SAFE_URI = "/app-api/skit/ad-callback/taku/{callback-key}/reward";
    private static final String RAW_QUERY = "sign=" + SIGN + "&extra_data=" + EXTRA_DATA + "&ilrd=" + ILRD;

    @Test
    void accessLogAndDevelopmentConsoleNeverReadOrRecordSuppressedParameters(CapturedOutput output)
            throws Exception {
        ExplosiveSensitiveRequest request = sensitiveRequest();
        ApiRequestUrlResolver.setSafeRequestUrl(request, SAFE_URI);
        ApiRequestUrlResolver.suppressParameters(request);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<ApiAccessLogCreateReqDTO> recorded = new AtomicReference<>();
        ApiAccessLogCommonApi api = recorded::set;
        ApiAccessLogFilter filter = new ApiAccessLogFilter(new WebProperties(), "test-app", api);

        FilterChain chain = (rawRequest, rawResponse) -> {
            HttpServletRequest routed = (HttpServletRequest) rawRequest;
            assertThat(routed.getRequestURI()).isEqualTo(RAW_URI);
            assertThat(routed.getQueryString()).isEqualTo(RAW_QUERY);
        };
        filter.doFilter(request, response, chain);

        try (MockedStatic<SpringUtils> springUtils = mockStatic(SpringUtils.class)) {
            springUtils.when(SpringUtils::isProd).thenReturn(false);
            ApiAccessLogInterceptor interceptor = new ApiAccessLogInterceptor();
            interceptor.preHandle(request, response, new Object());
            interceptor.afterCompletion(request, response, new Object(), null);
        }

        assertThat(recorded.get()).isNotNull();
        assertThat(recorded.get().getRequestUrl()).isEqualTo(SAFE_URI);
        assertThat(recorded.get().getRequestParams()).isNull();
        assertThat(recorded.get().getResponseBody()).isNull();
        assertNoSentinel(recorded.get().toString());
        assertNoSentinel(output.getAll());
    }

    @Test
    void exceptionLogsAndErrorRecordsSuppressSensitiveMessagesAndParameters(CapturedOutput output) {
        ExplosiveSensitiveRequest request = sensitiveRequest();
        ApiRequestUrlResolver.setSafeRequestUrl(request, SAFE_URI);
        ApiRequestUrlResolver.suppressParameters(request);
        AtomicReference<ApiErrorLogCreateReqDTO> recorded = new AtomicReference<>();
        ApiErrorLogCommonApi api = recorded::set;
        GlobalExceptionHandler handler = new GlobalExceptionHandler("test-app", api);

        handler.defaultExceptionHandler(request,
                new IllegalStateException("provider rejected " + SIGN + " " + EXTRA_DATA + " " + ILRD));

        assertThat(recorded.get()).isNotNull();
        assertThat(recorded.get().getRequestUrl()).isEqualTo(SAFE_URI);
        assertThat(recorded.get().getRequestParams()).isEqualTo("{}");
        assertNoSentinel(recorded.get().toString());
        assertNoSentinel(output.getAll());
    }

    @Test
    void accessLogFailureDoesNotEchoSensitiveExceptionMessage(CapturedOutput output) {
        ExplosiveSensitiveRequest request = sensitiveRequest();
        ApiRequestUrlResolver.setSafeRequestUrl(request, SAFE_URI);
        ApiRequestUrlResolver.suppressParameters(request);
        AtomicReference<ApiAccessLogCreateReqDTO> recorded = new AtomicReference<>();
        ApiAccessLogFilter filter = new ApiAccessLogFilter(new WebProperties(), "test-app", recorded::set);

        assertThatThrownBy(() -> filter.doFilter(request, new MockHttpServletResponse(),
                (rawRequest, rawResponse) -> {
                    throw new ServletException("failed " + SIGN + " " + ILRD);
                })).isInstanceOf(ServletException.class);

        assertThat(recorded.get()).isNotNull();
        assertThat(recorded.get().getRequestUrl()).isEqualTo(SAFE_URI);
        assertThat(recorded.get().getRequestParams()).isNull();
        assertThat(recorded.get().getResultMsg()).doesNotContain(SIGN, ILRD);
        assertNoSentinel(recorded.get().toString());
        assertNoSentinel(output.getAll());
    }

    @Test
    void malformedJsonIsReplacedWithFixedRedactionWithoutLoggingItsContents(CapturedOutput output)
            throws Exception {
        String malformedSecret = "malformed-secret-SENTINEL-never-log";
        new WebFrameworkUtils(new WebProperties());
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/app-api/member/profile");
        request.setContentType("application/json");
        request.setContent(("{\"password\":\"" + malformedSecret + "\"").getBytes());
        request.setRemoteAddr("127.0.0.1");
        AtomicReference<ApiAccessLogCreateReqDTO> recorded = new AtomicReference<>();
        ApiAccessLogFilter filter = new ApiAccessLogFilter(new WebProperties(), "test-app", recorded::set);

        filter.doFilter(request, new MockHttpServletResponse(), (rawRequest, rawResponse) -> { });

        assertThat(recorded.get()).isNotNull();
        assertThat(recorded.get().getRequestParams()).contains("<redacted-unparseable-json>");
        assertThat(recorded.get().getRequestParams()).doesNotContain(malformedSecret);
        assertThat(output.getAll()).doesNotContain(malformedSecret);
    }

    @Test
    void controller500ErrorRecordUsesDefaultRedactionWithoutEndpointAnnotation(CapturedOutput output) {
        String rewardSecret = "reward-SENTINEL-controller-500";
        String publisherKey = "publisher-SENTINEL-controller-500";
        String pangleAppSecret = "pangle-SENTINEL-controller-500";
        String takuAppSecret = "taku-SENTINEL-controller-500";
        new WebFrameworkUtils(new WebProperties());
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/admin-api/skit/configure");
        request.addParameter("REWARD_SECRET", rewardSecret);
        request.addParameter("publisher-key", publisherKey);
        request.setContentType("application/json");
        request.setContent(("{\"Pangle_App_Secret\":\"" + pangleAppSecret + "\","
                + "\"nested\":{\"taku-app-secret\":\"" + takuAppSecret + "\"},"
                + "\"reason\":\"safe audit reason\"}").getBytes(StandardCharsets.UTF_8));
        request.addHeader("User-Agent", "redaction-test");
        request.setRemoteAddr("127.0.0.1");
        AtomicReference<ApiErrorLogCreateReqDTO> recorded = new AtomicReference<>();
        GlobalExceptionHandler handler = new GlobalExceptionHandler("test-app", recorded::set);

        handler.defaultExceptionHandler(request, new IllegalStateException("synthetic controller failure"));

        assertThat(recorded.get()).isNotNull();
        assertThat(recorded.get().getRequestParams()).contains("safe audit reason");
        assertNoPlaintextSecrets(recorded.get().toString(), rewardSecret, publisherKey,
                pangleAppSecret, takuAppSecret);
        assertNoPlaintextSecrets(output.getAll(), rewardSecret, publisherKey,
                pangleAppSecret, takuAppSecret);
    }

    @Test
    void preMvcSecurityRejectionUsesDefaultRedactionWithoutHandlerMethod(CapturedOutput output)
            throws Exception {
        String rewardSecret = "reward-SENTINEL-pre-mvc";
        String publisherKey = "publisher-SENTINEL-pre-mvc";
        String pangleAppSecret = "pangle-SENTINEL-pre-mvc";
        String takuAppSecret = "taku-SENTINEL-pre-mvc";
        new WebFrameworkUtils(new WebProperties());
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/admin-api/skit/configure");
        request.addParameter("reward_secret", rewardSecret);
        request.addParameter("PUBLISHER-KEY", publisherKey);
        request.setContentType("application/json");
        request.setContent(("{\"pangle-app-secret\":\"" + pangleAppSecret + "\","
                + "\"nested\":{\"TAKU_APP_SECRET\":\"" + takuAppSecret + "\"},"
                + "\"reason\":\"safe pre-mvc reason\"}").getBytes(StandardCharsets.UTF_8));
        request.addHeader("User-Agent", "redaction-test");
        request.setRemoteAddr("127.0.0.1");
        AtomicReference<ApiAccessLogCreateReqDTO> recorded = new AtomicReference<>();
        ApiAccessLogFilter filter = new ApiAccessLogFilter(new WebProperties(), "test-app", recorded::set);

        filter.doFilter(request, new MockHttpServletResponse(), (rawRequest, rawResponse) ->
                ((MockHttpServletResponse) rawResponse).setStatus(401));

        assertThat(request.getAttribute(ApiAccessLogInterceptor.ATTRIBUTE_HANDLER_METHOD)).isNull();
        assertThat(recorded.get()).isNotNull();
        assertThat(recorded.get().getRequestParams()).contains("safe pre-mvc reason");
        assertNoPlaintextSecrets(recorded.get().toString(), rewardSecret, publisherKey,
                pangleAppSecret, takuAppSecret);
        assertNoPlaintextSecrets(output.getAll(), rewardSecret, publisherKey,
                pangleAppSecret, takuAppSecret);
    }

    @Test
    void rejectedValueNeverLeaksFromTypeMismatchHandler(CapturedOutput output) {
        String rejectedValue = "rejected-value-SENTINEL-never-log";
        AtomicReference<ApiErrorLogCreateReqDTO> recorded = new AtomicReference<>();
        GlobalExceptionHandler handler = new GlobalExceptionHandler("test-app", recorded::set);
        MethodArgumentTypeMismatchException exception = new MethodArgumentTypeMismatchException(
                rejectedValue, Integer.class, "pageNo", null,
                new NumberFormatException("invalid number " + rejectedValue));

        CommonResult<?> result = handler.methodArgumentTypeMismatchExceptionHandler(exception);

        assertThat(result.getMsg()).doesNotContain(rejectedValue);
        assertThat(recorded.get()).isNull();
        assertThat(output.getAll()).doesNotContain(rejectedValue);
    }

    @Test
    void invalidFormatRejectedValueNeverLeaksFromMalformedJsonHandler(CapturedOutput output) {
        String rejectedValue = "invalid-format-SENTINEL-never-log";
        AtomicReference<ApiErrorLogCreateReqDTO> recorded = new AtomicReference<>();
        GlobalExceptionHandler handler = new GlobalExceptionHandler("test-app", recorded::set);
        InvalidFormatException invalidFormat = InvalidFormatException.from(
                null, "invalid integer " + rejectedValue, rejectedValue, Integer.class);
        HttpMessageNotReadableException exception = new HttpMessageNotReadableException(
                "Malformed JSON " + rejectedValue, invalidFormat);

        CommonResult<?> result = handler.methodArgumentTypeInvalidFormatExceptionHandler(exception);

        assertThat(result.getMsg()).doesNotContain(rejectedValue);
        assertThat(recorded.get()).isNull();
        assertThat(output.getAll()).doesNotContain(rejectedValue);
    }

    @Test
    void malformedJsonRootCausePersistsOnlyExceptionTypesAndSafeStructure(CapturedOutput output) {
        String secret = "malformed-root-cause-SENTINEL-never-log";
        new WebFrameworkUtils(new WebProperties());
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/admin-api/skit/configure");
        request.setContentType("application/json");
        request.setContent("{\"reason\":\"safe malformed-json structure\"}"
                .getBytes(StandardCharsets.UTF_8));
        request.setRemoteAddr("127.0.0.1");
        AtomicReference<ApiErrorLogCreateReqDTO> recorded = new AtomicReference<>();
        GlobalExceptionHandler handler = new GlobalExceptionHandler("test-app", recorded::set);
        HttpMessageNotReadableException exception = new HttpMessageNotReadableException(
                "Malformed JSON " + secret, new IllegalArgumentException("root cause " + secret));

        CommonResult<?> result;
        try (MockedStatic<ServletUtils> servletUtils = mockStatic(ServletUtils.class)) {
            servletUtils.when(ServletUtils::getRequest).thenReturn(request);
            servletUtils.when(() -> ServletUtils.getParamMap(request)).thenReturn(Collections.emptyMap());
            servletUtils.when(() -> ServletUtils.getBody(request))
                    .thenReturn("{\"reason\":\"safe malformed-json structure\"}");
            servletUtils.when(() -> ServletUtils.getUserAgent(request)).thenReturn("redaction-test");
            servletUtils.when(() -> ServletUtils.getClientIP(request)).thenReturn("127.0.0.1");
            result = handler.methodArgumentTypeInvalidFormatExceptionHandler(exception);
        }

        assertThat(result.getMsg()).doesNotContain(secret);
        assertThat(recorded.get()).isNotNull();
        assertThat(recorded.get().getExceptionName())
                .isEqualTo(HttpMessageNotReadableException.class.getName());
        assertThat(recorded.get().getExceptionMessage())
                .isEqualTo(HttpMessageNotReadableException.class.getName());
        assertThat(recorded.get().getExceptionRootCauseMessage())
                .isEqualTo(IllegalArgumentException.class.getName());
        assertThat(recorded.get().getExceptionStackTrace()).isEqualTo("<suppressed>");
        assertThat(recorded.get().getRequestParams()).contains("safe malformed-json structure");
        assertThat(recorded.get().toString()).doesNotContain(secret);
        assertThat(output.getAll()).doesNotContain(secret);
    }

    @Test
    void accessLogResultMessagePersistsRootCauseTypeInsteadOfSecretMessage(CapturedOutput output) {
        String secret = "access-log-root-cause-SENTINEL-never-log";
        new WebFrameworkUtils(new WebProperties());
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/admin-api/skit/configure");
        request.setRemoteAddr("127.0.0.1");
        AtomicReference<ApiAccessLogCreateReqDTO> recorded = new AtomicReference<>();
        ApiAccessLogFilter filter = new ApiAccessLogFilter(new WebProperties(), "test-app", recorded::set);

        assertThatThrownBy(() -> filter.doFilter(request, new MockHttpServletResponse(),
                (rawRequest, rawResponse) -> {
                    throw new ServletException("outer failure", new IllegalStateException("root cause " + secret));
                })).isInstanceOf(ServletException.class);

        assertThat(recorded.get()).isNotNull();
        assertThat(recorded.get().getResultMsg()).isEqualTo(IllegalStateException.class.getName());
        assertThat(recorded.get().toString()).doesNotContain(secret);
        assertThat(output.getAll()).doesNotContain(secret);
    }

    @Test
    void accessLogSuppressesAttackerControlledCommonResultMessage(CapturedOutput output) throws Exception {
        String secret = "common-result-message-SENTINEL-never-log";
        new WebFrameworkUtils(new WebProperties());
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/admin-api/skit/configure");
        request.setRemoteAddr("127.0.0.1");
        AtomicReference<ApiAccessLogCreateReqDTO> recorded = new AtomicReference<>();
        ApiAccessLogFilter filter = new ApiAccessLogFilter(new WebProperties(), "test-app", recorded::set);

        filter.doFilter(request, new MockHttpServletResponse(), (rawRequest, rawResponse) ->
                WebFrameworkUtils.setCommonResult(rawRequest, CommonResult.error(400, secret)));

        assertThat(recorded.get()).isNotNull();
        assertThat(recorded.get().getResultCode()).isEqualTo(400);
        assertThat(recorded.get().getResultMsg()).isEmpty();
        assertThat(recorded.get().toString()).doesNotContain(secret);
        assertThat(output.getAll()).doesNotContain(secret);
    }

    @Test
    void bindingRejectedValueNeverLeaksFromValidationLogging(CapturedOutput output) {
        String rejectedValue = "binding-rejected-value-SENTINEL-never-log";
        AtomicReference<ApiErrorLogCreateReqDTO> recorded = new AtomicReference<>();
        GlobalExceptionHandler handler = new GlobalExceptionHandler("test-app", recorded::set);
        BindException exception = new BindException(new Object(), "request");
        exception.addError(new FieldError("request", "rewardSecret", rejectedValue,
                false, null, null, "invalid field"));

        CommonResult<?> result = handler.bindExceptionHandler(exception);

        assertThat(result.getMsg()).doesNotContain(rejectedValue);
        assertThat(recorded.get()).isNull();
        assertThat(output.getAll()).doesNotContain(rejectedValue);
    }

    @Test
    void validationExceptionMessageNeverLeaksFromValidationLogging(CapturedOutput output) {
        String secret = "validation-message-SENTINEL-never-log";
        AtomicReference<ApiErrorLogCreateReqDTO> recorded = new AtomicReference<>();
        GlobalExceptionHandler handler = new GlobalExceptionHandler("test-app", recorded::set);

        CommonResult<?> result = handler.validationException(new ValidationException(secret));

        assertThat(result.getMsg()).doesNotContain(secret);
        assertThat(recorded.get()).isNull();
        assertThat(output.getAll()).doesNotContain(secret);
    }

    @Test
    void accessDeniedExceptionMessageNeverLeaksFromSecurityLogging(CapturedOutput output) {
        String secret = "access-denied-SENTINEL-never-log";
        new WebFrameworkUtils(new WebProperties());
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/admin-api/skit/configure");
        request.setRemoteAddr("127.0.0.1");
        AtomicReference<ApiErrorLogCreateReqDTO> recorded = new AtomicReference<>();
        GlobalExceptionHandler handler = new GlobalExceptionHandler("test-app", recorded::set);

        CommonResult<?> result = handler.accessDeniedExceptionHandler(request, new AccessDeniedException(secret));

        assertThat(result.getMsg()).doesNotContain(secret);
        assertThat(recorded.get()).isNull();
        assertThat(output.getAll()).doesNotContain(secret);
    }

    private static ExplosiveSensitiveRequest sensitiveRequest() {
        new WebFrameworkUtils(new WebProperties());
        ExplosiveSensitiveRequest request = new ExplosiveSensitiveRequest("GET", RAW_URI);
        request.setQueryString(RAW_QUERY);
        request.setContentType("application/json");
        request.setContent(("{\"extra_data\":\"" + EXTRA_DATA + "\"}").getBytes());
        request.addHeader("User-Agent", "redaction-test");
        request.setRemoteAddr("127.0.0.1");
        return request;
    }

    private static void assertNoSentinel(String value) {
        assertThat(value).doesNotContain(CALLBACK_KEY, SIGN, EXTRA_DATA, ILRD);
    }

    private static void assertNoPlaintextSecrets(String value, String... secrets) {
        assertThat(value).doesNotContain(secrets);
    }

    /** Fails the test immediately if a logging path tries to materialize callback parameters or body. */
    private static final class ExplosiveSensitiveRequest extends MockHttpServletRequest {

        private ExplosiveSensitiveRequest(String method, String requestURI) {
            super(method, requestURI);
        }

        @Override
        public Map<String, String[]> getParameterMap() {
            throw new AssertionError("sensitive callback parameters must not be read by logging");
        }

        @Override
        public BufferedReader getReader() {
            throw new AssertionError("sensitive callback body must not be read by logging");
        }

        @Override
        public ServletInputStream getInputStream() {
            throw new AssertionError("sensitive callback body must not be read by logging");
        }
    }

}
