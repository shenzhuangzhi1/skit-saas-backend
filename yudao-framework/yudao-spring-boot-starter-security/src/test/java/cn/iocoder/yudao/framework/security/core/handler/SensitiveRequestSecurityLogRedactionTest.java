package cn.iocoder.yudao.framework.security.core.handler;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import cn.iocoder.yudao.framework.apilog.core.ApiRequestUrlResolver;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SensitiveRequestSecurityLogRedactionTest {

    private static final String CALLBACK_KEY = "security-callback-key-SENTINEL";
    private static final String QUERY_SECRET = "security-query-SENTINEL";
    private static final String SAFE_URL = "/app-api/skit/ad-callback/taku/{callback-key}/reward";

    @Test
    void securityHandlersUseSafeUrlAndDoNotPrintSensitiveExceptionMessages() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET",
                "/app-api/skit/ad-callback/taku/" + CALLBACK_KEY + "/reward");
        request.setQueryString("sign=" + QUERY_SECRET);
        ApiRequestUrlResolver.setSafeRequestUrl(request, SAFE_URL);
        ApiRequestUrlResolver.suppressParameters(request);
        MockHttpServletResponse response = new MockHttpServletResponse();
        List<ILoggingEvent> events = new ArrayList<>();
        ListAppender<ILoggingEvent> deniedAppender = attach(AccessDeniedHandlerImpl.class, events);
        ListAppender<ILoggingEvent> authAppender = attach(AuthenticationEntryPointImpl.class, events);
        try {
            new AccessDeniedHandlerImpl().handle(request, response,
                    new AccessDeniedException("denied " + QUERY_SECRET));
            new AuthenticationEntryPointImpl().commence(request, response,
                    new BadCredentialsException("bad " + QUERY_SECRET));
        } finally {
            detach(AccessDeniedHandlerImpl.class, deniedAppender);
            detach(AuthenticationEntryPointImpl.class, authAppender);
        }

        StringBuilder output = new StringBuilder();
        for (ILoggingEvent event : events) {
            output.append(event.getFormattedMessage()).append('\n');
            if (event.getThrowableProxy() != null) {
                output.append(event.getThrowableProxy().getMessage()).append('\n');
            }
        }
        assertThat(output.toString()).contains(SAFE_URL);
        assertThat(output.toString()).doesNotContain(CALLBACK_KEY, QUERY_SECRET);
    }

    private static ListAppender<ILoggingEvent> attach(Class<?> owner, List<ILoggingEvent> sink) {
        Logger logger = (Logger) LoggerFactory.getLogger(owner);
        ListAppender<ILoggingEvent> appender = new ListAppender<ILoggingEvent>() {
            @Override
            protected void append(ILoggingEvent eventObject) {
                sink.add(eventObject);
            }
        };
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private static void detach(Class<?> owner, ListAppender<ILoggingEvent> appender) {
        ((Logger) LoggerFactory.getLogger(owner)).detachAppender(appender);
        appender.stop();
    }

}
