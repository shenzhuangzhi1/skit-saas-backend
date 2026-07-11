package cn.iocoder.yudao.module.skit.framework.security;

import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.framework.web.config.WebProperties;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import static cn.iocoder.yudao.framework.common.exception.enums.GlobalErrorCodeConstants.FORBIDDEN;
import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception0;

/**
 * Prevents the dedicated Skit member token from being accepted by legacy member, mall, pay, or admin APIs.
 *
 * <p>The framework currently has one MEMBER user type. The dedicated {@code skit_member} OAuth scope therefore
 * also needs a URL allow-list so a numeric id collision can never impersonate a legacy member.</p>
 */
public class SkitMemberScopeInterceptor implements HandlerInterceptor {

    private static final String SKIT_MEMBER_SCOPE = "skit_member";

    private final String allowedPrefix;

    public SkitMemberScopeInterceptor(WebProperties webProperties) {
        this.allowedPrefix = webProperties.getAppApi().getPrefix() + "/skit/member/";
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        LoginUser loginUser = SecurityFrameworkUtils.getLoginUser();
        if (loginUser == null || loginUser.getScopes() == null
                || !loginUser.getScopes().contains(SKIT_MEMBER_SCOPE)) {
            return true;
        }
        String requestPath = request.getRequestURI().substring(request.getContextPath().length());
        if (!requestPath.startsWith(allowedPrefix)) {
            throw exception0(FORBIDDEN.getCode(), "短剧会员令牌只能访问短剧会员接口");
        }
        return true;
    }
}
