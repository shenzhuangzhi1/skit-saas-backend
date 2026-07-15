package cn.iocoder.yudao.framework.apilog.core;

import javax.servlet.http.HttpServletRequest;

/**
 * Resolves the URL that observability code is allowed to persist or print.
 *
 * <p>The request URI itself is never wrapped or changed: an early, trusted filter may attach a
 * safe URL and suppress-parameters marker while Spring MVC continues routing with the original
 * path and raw query string.</p>
 */
public final class ApiRequestUrlResolver {

    private static final String ATTRIBUTE_SAFE_REQUEST_URL =
            ApiRequestUrlResolver.class.getName() + ".safeRequestUrl";
    private static final String ATTRIBUTE_SUPPRESS_PARAMETERS =
            ApiRequestUrlResolver.class.getName() + ".suppressParameters";
    private static final int MAX_SAFE_URL_LENGTH = 2048;

    private ApiRequestUrlResolver() {
    }

    public static void setSafeRequestUrl(HttpServletRequest request, String safeRequestUrl) {
        if (!isSafeRequestUrl(safeRequestUrl)) {
            throw new IllegalArgumentException("safe request URL must be an absolute path without query or fragment");
        }
        request.setAttribute(ATTRIBUTE_SAFE_REQUEST_URL, safeRequestUrl);
    }

    public static String resolve(HttpServletRequest request) {
        Object candidate = request.getAttribute(ATTRIBUTE_SAFE_REQUEST_URL);
        if (candidate instanceof String && isSafeRequestUrl((String) candidate)) {
            return (String) candidate;
        }
        return request.getRequestURI();
    }

    public static void suppressParameters(HttpServletRequest request) {
        request.setAttribute(ATTRIBUTE_SUPPRESS_PARAMETERS, Boolean.TRUE);
    }

    public static boolean shouldSuppressParameters(HttpServletRequest request) {
        return Boolean.TRUE.equals(request.getAttribute(ATTRIBUTE_SUPPRESS_PARAMETERS));
    }

    private static boolean isSafeRequestUrl(String value) {
        return value != null && !value.isEmpty() && value.length() <= MAX_SAFE_URL_LENGTH
                && value.charAt(0) == '/' && value.indexOf('?') < 0 && value.indexOf('#') < 0
                && value.indexOf('\r') < 0 && value.indexOf('\n') < 0;
    }

}
