package cn.iocoder.yudao.framework.websocket.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;

/**
 * WebSocket 配置项
 *
 * @author xingyu4j
 */
@ConfigurationProperties("yudao.websocket")
@Data
@Validated
public class WebSocketProperties {

    /**
     * WebSocket 的连接路径
     */
    @NotEmpty(message = "WebSocket 的连接路径不能为空")
    private String path = "/ws";

    /**
     * 一次性 WebSocket 握手票据的有效期，单位：秒。
     */
    @Min(value = 1, message = "WebSocket 票据有效期不能小于 1 秒")
    @Max(value = 30, message = "WebSocket 票据有效期不能大于 30 秒")
    private int ticketTtlSeconds = 30;

    /**
     * 消息发送器的类型
     *
     * 可选值：local、redis、rocketmq、kafka、rabbitmq
     */
    @NotNull(message = "WebSocket 的消息发送者不能为空")
    private String senderType = "local";

}
