package cn.iocoder.yudao.module.infra.controller.app.websocket.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.ToString;

@Schema(description = "用户 App - 一次性 WebSocket 票据 Response VO")
@Data
@AllArgsConstructor
public class AppWebSocketTicketRespVO {

    @Schema(description = "仅可用于一次握手的 256-bit 票据", requiredMode = Schema.RequiredMode.REQUIRED)
    @ToString.Exclude
    private String ticket;

    @Schema(description = "有效期（秒）", requiredMode = Schema.RequiredMode.REQUIRED, example = "30")
    private Integer expiresInSeconds;

}
