package cn.iocoder.yudao.framework.xss.core.filter;

import cn.iocoder.yudao.framework.apilog.core.ApiRequestUrlResolver;
import cn.iocoder.yudao.framework.xss.config.XssProperties;
import cn.iocoder.yudao.framework.xss.core.clean.XssCleaner;
import java.io.IOException;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import lombok.AllArgsConstructor;
import org.springframework.util.PathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Xss 过滤器
 *
 * @author 芋道源码
 */
@AllArgsConstructor
public class XssFilter extends OncePerRequestFilter {

  /** 属性 */
  private final XssProperties properties;

  /** 路径匹配器 */
  private final PathMatcher pathMatcher;

  private final XssCleaner xssCleaner;

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws IOException, ServletException {
    filterChain.doFilter(new XssRequestWrapper(request, xssCleaner), response);
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    // 如果关闭，则不过滤
    if (!properties.isEnable()) {
      return true;
    }

    // The earlier exact-route sanitizer owns configurable callback-prefix recognition.
    if (ApiRequestUrlResolver.shouldSuppressParameters(request)) {
      return true;
    }

    // 如果匹配到无需过滤，则不过滤
    String uri = withoutContextPath(request);
    return properties.getExcludeUrls().stream()
        .anyMatch(excludeUrl -> pathMatcher.match(excludeUrl, uri));
  }

  private static String withoutContextPath(HttpServletRequest request) {
    String uri = request.getRequestURI();
    String contextPath = request.getContextPath();
    if (contextPath != null
        && !contextPath.isEmpty()
        && (uri.equals(contextPath) || uri.startsWith(contextPath + "/"))) {
      return uri.substring(contextPath.length());
    }
    return uri;
  }
}
