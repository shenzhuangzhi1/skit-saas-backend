package cn.iocoder.yudao.framework.websocket.core.security;

import cn.iocoder.yudao.framework.security.core.LoginUser;
import org.springframework.lang.Nullable;

/** Issues and atomically consumes single-use WebSocket authentication tickets. */
public interface WebSocketTicketService {

    WebSocketTicket issue(LoginUser loginUser);

    /**
     * Atomically consumes a ticket. A non-null requested tenant must match the ticket principal.
     * Invalid, expired, replayed, or cross-tenant tickets return {@code null}.
     */
    @Nullable
    LoginUser consume(String ticket, @Nullable Long requestedTenantId);

}
