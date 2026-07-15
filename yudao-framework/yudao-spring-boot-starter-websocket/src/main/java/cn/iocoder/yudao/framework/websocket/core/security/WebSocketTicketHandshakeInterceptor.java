package cn.iocoder.yudao.framework.websocket.core.security;

import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.framework.websocket.config.WebSocketProperties;
import cn.iocoder.yudao.framework.websocket.core.util.WebSocketFrameworkUtils;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 * Authenticates only the configured WebSocket path with one short-lived ticket.
 * Long-lived {@code ?token=} authentication is intentionally unsupported.
 */
public class WebSocketTicketHandshakeInterceptor implements HandshakeInterceptor {

    private static final String QUERY_PREFIX = "ticket=";
    private static final String TENANT_HEADER = "tenant-id";

    private final WebSocketTicketService ticketService;
    private final String exactPath;

    public WebSocketTicketHandshakeInterceptor(WebSocketTicketService ticketService,
                                               WebSocketProperties properties) {
        this.ticketService = ticketService;
        this.exactPath = properties.getPath();
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        URI uri = request.getURI();
        if (!exactPath.equals(uri.getPath())) {
            return false;
        }
        String rawQuery = uri.getRawQuery();
        if (rawQuery == null || !rawQuery.startsWith(QUERY_PREFIX)
                || rawQuery.indexOf('&') >= 0 || rawQuery.indexOf(';') >= 0) {
            return false;
        }
        String ticket = rawQuery.substring(QUERY_PREFIX.length());
        TenantHeader tenantHeader = readTenantHeader(request);
        if (!tenantHeader.valid) {
            return false;
        }
        LoginUser loginUser = ticketService.consume(ticket, tenantHeader.tenantId);
        if (loginUser == null) {
            return false;
        }
        WebSocketFrameworkUtils.setLoginUser(loginUser, attributes);
        return true;
    }

    private static TenantHeader readTenantHeader(ServerHttpRequest request) {
        List<String> values = request.getHeaders().get(TENANT_HEADER);
        if (values == null || values.isEmpty()) {
            return TenantHeader.absent();
        }
        if (values.size() != 1) {
            return TenantHeader.invalid();
        }
        String value = values.get(0);
        if (value == null || value.isEmpty()) {
            return TenantHeader.invalid();
        }
        for (int index = 0; index < value.length(); index++) {
            if (!Character.isDigit(value.charAt(index))) {
                return TenantHeader.invalid();
            }
        }
        try {
            long tenantId = Long.parseLong(value);
            return tenantId > 0 ? TenantHeader.present(tenantId) : TenantHeader.invalid();
        } catch (NumberFormatException invalidTenant) {
            return TenantHeader.invalid();
        }
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
        // No state is retained outside the WebSocket session attributes.
    }

    private static final class TenantHeader {
        private final boolean valid;
        private final Long tenantId;

        private TenantHeader(boolean valid, Long tenantId) {
            this.valid = valid;
            this.tenantId = tenantId;
        }

        private static TenantHeader absent() {
            return new TenantHeader(true, null);
        }

        private static TenantHeader present(long tenantId) {
            return new TenantHeader(true, tenantId);
        }

        private static TenantHeader invalid() {
            return new TenantHeader(false, null);
        }
    }

}
