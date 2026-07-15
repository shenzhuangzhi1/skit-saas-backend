package cn.iocoder.yudao.framework.websocket.core.security;

import lombok.AllArgsConstructor;
import lombok.Getter;

/** One-time, short-lived bearer used only for an exact WebSocket handshake. */
@Getter
@AllArgsConstructor
public final class WebSocketTicket {

    private final String ticket;
    private final int expiresInSeconds;

    @Override
    public String toString() {
        return "WebSocketTicket(ticket=<redacted>, expiresInSeconds=" + expiresInSeconds + ")";
    }

}
