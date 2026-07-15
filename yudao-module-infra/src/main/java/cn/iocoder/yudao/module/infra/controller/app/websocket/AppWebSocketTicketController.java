package cn.iocoder.yudao.module.infra.controller.app.websocket;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.enums.UserTypeEnum;
import cn.iocoder.yudao.framework.apilog.core.annotation.ApiAccessLog;
import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.framework.websocket.core.security.WebSocketTicket;
import cn.iocoder.yudao.framework.websocket.core.security.WebSocketTicketService;
import cn.iocoder.yudao.module.infra.controller.app.websocket.vo.AppWebSocketTicketRespVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@Tag(name = "用户 App - WebSocket 认证")
@RestController
@RequestMapping("/infra/websocket-tickets")
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "yudao.websocket", name = "enable", matchIfMissing = true)
public class AppWebSocketTicketController {

    private final WebSocketTicketService ticketService;

    @PostMapping
    @Operation(summary = "签发一次性 WebSocket 握手票据")
    @PreAuthorize("isAuthenticated()")
    @ApiAccessLog(requestEnable = false, responseEnable = false)
    public ResponseEntity<CommonResult<AppWebSocketTicketRespVO>> issue(
            @AuthenticationPrincipal LoginUser loginUser) {
        if (loginUser == null || !UserTypeEnum.MEMBER.getValue().equals(loginUser.getUserType())) {
            throw new AccessDeniedException("Only authenticated App members may issue App WebSocket tickets");
        }
        WebSocketTicket ticket = ticketService.issue(loginUser);
        CommonResult<AppWebSocketTicketRespVO> body = success(
                new AppWebSocketTicketRespVO(ticket.getTicket(), ticket.getExpiresInSeconds()));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header("Pragma", "no-cache").body(body);
    }

}
