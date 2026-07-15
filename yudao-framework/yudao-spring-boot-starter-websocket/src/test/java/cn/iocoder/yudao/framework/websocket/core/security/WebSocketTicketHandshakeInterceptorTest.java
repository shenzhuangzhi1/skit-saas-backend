package cn.iocoder.yudao.framework.websocket.core.security;

import cn.iocoder.yudao.framework.common.enums.UserTypeEnum;
import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.framework.websocket.config.WebSocketProperties;
import cn.iocoder.yudao.framework.websocket.core.util.WebSocketFrameworkUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.socket.WebSocketHandler;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WebSocketTicketHandshakeInterceptorTest {

    private static final String TICKET = "BwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwc";

    @Mock
    private WebSocketTicketService ticketService;
    @Mock
    private WebSocketHandler webSocketHandler;

    private WebSocketTicketHandshakeInterceptor interceptor;

    @BeforeEach
    void setUp() {
        WebSocketProperties properties = new WebSocketProperties();
        properties.setPath("/infra/ws");
        interceptor = new WebSocketTicketHandshakeInterceptor(ticketService, properties);
    }

    @Test
    void exactPathAndSingleTicketBuildMinimalAuthenticatedSession() {
        LoginUser loginUser = new LoginUser().setTenantId(11L).setId(22L)
                .setUserType(UserTypeEnum.MEMBER.getValue());
        when(ticketService.consume(TICKET, 11L)).thenReturn(loginUser);
        Map<String, Object> attributes = new HashMap<>();

        boolean accepted = beforeHandshake("/infra/ws", "ticket=" + TICKET, "11", attributes);

        assertThat(accepted).isTrue();
        assertThat(attributes.get(WebSocketFrameworkUtils.ATTRIBUTE_LOGIN_USER)).isSameAs(loginUser);
    }

    @Test
    void legacyTokenAndMixedTokenTicketAreRejectedWithoutConsumption() {
        assertThat(beforeHandshake("/infra/ws", "token=legacy-refresh-token", null,
                new HashMap<>())).isFalse();
        assertThat(beforeHandshake("/infra/ws", "ticket=" + TICKET + "&token=legacy-access-token", null,
                new HashMap<>())).isFalse();
        verify(ticketService, never()).consume(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.<Long>any());
    }

    @Test
    void nearMatchPathAndDuplicateOrMissingTicketAreRejected() {
        assertThat(beforeHandshake("/infra/ws/", "ticket=" + TICKET, null, new HashMap<>())).isFalse();
        assertThat(beforeHandshake("/infra/ws", "ticket=" + TICKET + "&ticket=" + TICKET,
                null, new HashMap<>())).isFalse();
        assertThat(beforeHandshake("/infra/ws", null, null, new HashMap<>())).isFalse();
        verify(ticketService, never()).consume(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.<Long>any());
    }

    @Test
    void expiredReplayMalformedTenantAndCrossTenantFailClosed() {
        when(ticketService.consume(TICKET, null)).thenReturn(null);
        assertThat(beforeHandshake("/infra/ws", "ticket=" + TICKET, null, new HashMap<>())).isFalse();
        verify(ticketService).consume(TICKET, null);

        assertThat(beforeHandshake("/infra/ws", "ticket=" + TICKET, "not-a-tenant", new HashMap<>()))
                .isFalse();
        when(ticketService.consume(TICKET, 12L)).thenReturn(null);
        assertThat(beforeHandshake("/infra/ws", "ticket=" + TICKET, "12", new HashMap<>())).isFalse();
        verify(ticketService).consume(TICKET, 12L);
    }

    private boolean beforeHandshake(String path, String query, String tenantId,
                                    Map<String, Object> attributes) {
        MockHttpServletRequest servletRequest = new MockHttpServletRequest("GET", path);
        servletRequest.setScheme("http");
        servletRequest.setServerName("localhost");
        servletRequest.setServerPort(8080);
        servletRequest.setQueryString(query);
        if (tenantId != null) {
            servletRequest.addHeader("tenant-id", tenantId);
        }
        return interceptor.beforeHandshake(
                new ServletServerHttpRequest(servletRequest),
                new ServletServerHttpResponse(new MockHttpServletResponse()),
                webSocketHandler,
                attributes);
    }

}
