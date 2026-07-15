package cn.iocoder.yudao.module.infra.controller.admin.websocket;

import cn.iocoder.yudao.framework.apilog.core.annotation.ApiAccessLog;
import cn.iocoder.yudao.framework.common.enums.UserTypeEnum;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.framework.websocket.core.security.WebSocketTicket;
import cn.iocoder.yudao.framework.websocket.core.security.WebSocketTicketService;
import cn.iocoder.yudao.module.infra.controller.admin.websocket.vo.AdminWebSocketTicketRespVO;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import javax.annotation.security.PermitAll;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminWebSocketTicketControllerTest {

    @Test
    void authenticatedAdminPostIssuesTenantAndUserTypeBoundTicket() throws Exception {
        WebSocketTicketService ticketService = mock(WebSocketTicketService.class);
        LoginUser loginUser = new LoginUser().setTenantId(7L).setId(8L)
                .setUserType(UserTypeEnum.ADMIN.getValue());
        when(ticketService.issue(loginUser)).thenReturn(new WebSocketTicket("one-time-admin-ticket", 30));
        AdminWebSocketTicketController controller = new AdminWebSocketTicketController(ticketService);

        ResponseEntity<CommonResult<AdminWebSocketTicketRespVO>> response = controller.issue(loginUser);
        CommonResult<AdminWebSocketTicketRespVO> result = response.getBody();

        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(response.getHeaders().getFirst("Pragma")).isEqualTo("no-cache");
        assertThat(result).isNotNull();
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getData().getTicket()).isEqualTo("one-time-admin-ticket");
        assertThat(result.getData().getExpiresInSeconds()).isEqualTo(30);
        assertThat(result.getData().toString()).doesNotContain("one-time-admin-ticket");
        verify(ticketService).issue(loginUser);

        RequestMapping requestMapping = AnnotatedElementUtils.findMergedAnnotation(
                AdminWebSocketTicketController.class, RequestMapping.class);
        assertThat(requestMapping).isNotNull();
        assertThat(requestMapping.value()).containsExactly("/infra/websocket-tickets");
        ConditionalOnProperty conditional = AnnotatedElementUtils.findMergedAnnotation(
                AdminWebSocketTicketController.class, ConditionalOnProperty.class);
        assertThat(conditional).isNotNull();
        assertThat(conditional.prefix()).isEqualTo("yudao.websocket");
        assertThat(conditional.name()).containsExactly("enable");
        Method method = AdminWebSocketTicketController.class.getDeclaredMethod("issue", LoginUser.class);
        assertThat(AnnotatedElementUtils.findMergedAnnotation(method, PostMapping.class)).isNotNull();
        assertThat(AnnotatedElementUtils.findMergedAnnotation(method, PermitAll.class)).isNull();
        assertThat(hasParameterAnnotation(method, 0, AuthenticationPrincipal.class)).isTrue();
        ApiAccessLog apiAccessLog = AnnotatedElementUtils.findMergedAnnotation(method, ApiAccessLog.class);
        assertThat(apiAccessLog).isNotNull();
        assertThat(apiAccessLog.requestEnable()).isFalse();
        assertThat(apiAccessLog.responseEnable()).isFalse();
        PreAuthorize preAuthorize = AnnotatedElementUtils.findMergedAnnotation(method, PreAuthorize.class);
        assertThat(preAuthorize).isNotNull();
        assertThat(preAuthorize.value()).isEqualTo("isAuthenticated()");
    }

    @Test
    void unauthenticatedOrMemberPrincipalCannotUseAdminIssuer() {
        WebSocketTicketService ticketService = mock(WebSocketTicketService.class);
        AdminWebSocketTicketController controller = new AdminWebSocketTicketController(ticketService);
        LoginUser member = new LoginUser().setTenantId(7L).setId(8L)
                .setUserType(UserTypeEnum.MEMBER.getValue());

        assertThatThrownBy(() -> controller.issue(null)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.issue(member)).isInstanceOf(AccessDeniedException.class);
        verify(ticketService, never()).issue(org.mockito.ArgumentMatchers.any());
    }

    private static boolean hasParameterAnnotation(Method method, int parameterIndex,
                                                  Class<? extends Annotation> type) {
        for (Annotation annotation : method.getParameterAnnotations()[parameterIndex]) {
            if (annotation.annotationType().equals(type)) {
                return true;
            }
        }
        return false;
    }

}
