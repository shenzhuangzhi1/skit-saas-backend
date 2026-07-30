package cn.iocoder.yudao.module.skit.framework.web;

import cn.iocoder.yudao.framework.apilog.core.ApiRequestUrlResolver;
import cn.iocoder.yudao.framework.web.config.WebProperties;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.regex.Pattern;

/** Marks provider callbacks as parameter-suppressed before any logging filter can inspect them. */
public class SkitCallbackSecretSanitizingFilter extends OncePerRequestFilter {

    static final String TAKU_CALLBACK_PATH = "/skit/ad-callback/taku/";
    static final String PANGLE_CALLBACK_PATH = "/skit/ad-callback/pangle/";
    static final String REDACTED_KEY = "{callback-key}";
    private static final int DETERMINISTIC_REJECTION_STATUS = 602;
    private static final String PANGLE_FALSE_JSON = "{\"isValid\":false}";

    private final ProviderRoute takuRoute;
    private final ProviderRoute pangleRoute;

    public SkitCallbackSecretSanitizingFilter(WebProperties webProperties) {
        String appPrefix = webProperties.getAppApi().getPrefix();
        String normalizedPrefix = stripTrailingSlash(appPrefix);
        this.takuRoute = new ProviderRoute(
                normalizedPrefix + TAKU_CALLBACK_PATH, "(?:reward|impression)", false);
        this.pangleRoute = new ProviderRoute(
                normalizedPrefix + PANGLE_CALLBACK_PATH, "reward", true);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String apiUri = withoutContextPath(request);
        ProviderRoute route = route(apiUri);
        if (route == null) {
            filterChain.doFilter(request, response);
            return;
        }
        String endpoint = resolveEndpoint(apiUri.substring(route.callbackPrefix.length()));
        ApiRequestUrlResolver.setSafeRequestUrl(request,
                request.getContextPath() + route.callbackPrefix + REDACTED_KEY + "/" + endpoint);
        ApiRequestUrlResolver.suppressParameters(request);
        response.setHeader("Cache-Control", "no-store");
        if (!"GET".equals(request.getMethod()) || !route.allowedCallbackPath.matcher(apiUri).matches()) {
            reject(route, response);
            return;
        }
        filterChain.doFilter(request, response);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return route(withoutContextPath(request)) == null;
    }

    private ProviderRoute route(String apiUri) {
        if (apiUri.startsWith(takuRoute.callbackPrefix)) {
            return takuRoute;
        }
        if (apiUri.startsWith(pangleRoute.callbackPrefix)) {
            return pangleRoute;
        }
        return null;
    }

    private static void reject(ProviderRoute route, HttpServletResponse response)
            throws IOException {
        if (route.pangle) {
            response.setStatus(HttpServletResponse.SC_OK);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(PANGLE_FALSE_JSON);
            return;
        }
        response.setStatus(DETERMINISTIC_REJECTION_STATUS);
        response.setContentType("text/plain;charset=UTF-8");
        response.getWriter().write(Integer.toString(DETERMINISTIC_REJECTION_STATUS));
    }

    private static String withoutContextPath(HttpServletRequest request) {
        String requestUri = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && requestUri.startsWith(contextPath)) {
            return requestUri.substring(contextPath.length());
        }
        return requestUri;
    }

    private static String resolveEndpoint(String keyAndEndpoint) {
        int delimiter = keyAndEndpoint.indexOf('/');
        if (delimiter < 0 || delimiter == keyAndEndpoint.length() - 1) {
            return "unknown";
        }
        String endpoint = keyAndEndpoint.substring(delimiter + 1);
        if (endpoint.endsWith("/")) {
            endpoint = endpoint.substring(0, endpoint.length() - 1);
        }
        if ("reward".equals(endpoint) || "impression".equals(endpoint)) {
            return endpoint;
        }
        return "unknown";
    }

    private static String stripTrailingSlash(String value) {
        if (value.endsWith("/")) {
            return value.substring(0, value.length() - 1);
        }
        return value;
    }

    private static final class ProviderRoute {

        private final String callbackPrefix;
        private final Pattern allowedCallbackPath;
        private final boolean pangle;

        private ProviderRoute(String callbackPrefix, String endpointPattern, boolean pangle) {
            this.callbackPrefix = callbackPrefix;
            this.allowedCallbackPath = Pattern.compile("^" + Pattern.quote(callbackPrefix)
                    + "[A-Za-z0-9_-]{43}/" + endpointPattern + "$");
            this.pangle = pangle;
        }
    }
}
