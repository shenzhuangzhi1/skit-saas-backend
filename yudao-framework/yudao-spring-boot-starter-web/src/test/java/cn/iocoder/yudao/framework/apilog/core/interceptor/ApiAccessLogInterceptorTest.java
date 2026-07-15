package cn.iocoder.yudao.framework.apilog.core.interceptor;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import cn.iocoder.yudao.framework.apilog.core.annotation.ApiAccessLog;
import cn.iocoder.yudao.framework.common.util.spring.SpringUtils;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mockStatic;

class ApiAccessLogInterceptorTest {

    @Test
    void requestDisabledAnnotationAlsoSuppressesDevelopmentConsolePayload() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(ApiAccessLogInterceptor.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try (MockedStatic<SpringUtils> springUtils = mockStatic(SpringUtils.class)) {
            springUtils.when(SpringUtils::isProd).thenReturn(false);
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/legacy");
            request.setContentType(MediaType.APPLICATION_JSON_VALUE);
            request.setContent("{\"sessionId\":\"attacker-secret-session\"}"
                    .getBytes(StandardCharsets.UTF_8));
            HandlerMethod handler = new HandlerMethod(new SensitiveController(),
                    SensitiveController.class.getMethod("report"));

            new ApiAccessLogInterceptor().preHandle(request, new MockHttpServletResponse(), handler);

            assertTrue(appender.list.stream().anyMatch(event -> event.getFormattedMessage().contains("参数日志已关闭")));
            assertFalse(appender.list.stream().anyMatch(event -> event.getFormattedMessage()
                    .contains("attacker-secret-session")));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void sanitizeKeysAlsoRedactNestedDevelopmentConsolePayload() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(ApiAccessLogInterceptor.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try (MockedStatic<SpringUtils> springUtils = mockStatic(SpringUtils.class)) {
            springUtils.when(SpringUtils::isProd).thenReturn(false);
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/reward-secret");
            request.setContentType(MediaType.APPLICATION_JSON_VALUE);
            request.addParameter("rewardSecret", "query-secret-never-log");
            request.setContent(("{\"rewardSecret\":\"body-secret-never-log\","
                    + "\"nested\":{\"rewardSecret\":\"nested-secret-never-log\"},"
                    + "\"reason\":\"safe audit reason\"}")
                    .getBytes(StandardCharsets.UTF_8));
            HandlerMethod handler = new HandlerMethod(new SensitiveController(),
                    SensitiveController.class.getMethod("rotateRewardSecret"));

            new ApiAccessLogInterceptor().preHandle(request, new MockHttpServletResponse(), handler);

            assertTrue(appender.list.stream().anyMatch(event -> event.getFormattedMessage()
                    .contains("safe audit reason")));
            assertFalse(appender.list.stream().anyMatch(event -> {
                String message = event.getFormattedMessage();
                return message.contains("query-secret-never-log")
                        || message.contains("body-secret-never-log")
                        || message.contains("nested-secret-never-log");
            }));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    static class SensitiveController {

        @ApiAccessLog(requestEnable = false, responseEnable = false)
        public void report() {
        }

        @ApiAccessLog(sanitizeKeys = "rewardSecret")
        public void rotateRewardSecret() {
        }

    }

}
