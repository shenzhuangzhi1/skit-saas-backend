package cn.iocoder.yudao.framework.xss.core.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import cn.iocoder.yudao.framework.xss.config.XssProperties;
import cn.iocoder.yudao.framework.xss.core.clean.XssCleaner;
import cn.iocoder.yudao.framework.apilog.core.ApiRequestUrlResolver;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.util.AntPathMatcher;

class XssFilterCallbackExclusionTest {

  private static final String CALLBACK_KEY = "acct_AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGx";
  private static final String RAW_QUERY =
      "request_id=query-SENTINEL&show_custom_ext=%3Csentinel%3E";

  @Test
  void takuAndPangleCallbacksPermanentlyBypassTheRewritingWrapper() throws Exception {
    for (String uri :
        new String[] {
          "/app-api/skit/ad-callback/taku/" + CALLBACK_KEY + "/impression",
          "/app-api/skit/ad-callback/taku/" + CALLBACK_KEY + "/reward",
          "/app-api/skit/ad-callback/pangle/" + CALLBACK_KEY + "/reward"
        }) {
      XssCleaner cleaner = mock(XssCleaner.class);
      XssFilter filter = new XssFilter(new XssProperties(), new AntPathMatcher(), cleaner);
      MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
      request.setQueryString(RAW_QUERY);
      ApiRequestUrlResolver.suppressParameters(request);
      AtomicBoolean invoked = new AtomicBoolean();

      filter.doFilter(
          request,
          new MockHttpServletResponse(),
          (routed, response) -> {
            invoked.set(true);
            assertThat(routed).isSameAs(request);
            assertThat(((HttpServletRequest) routed).getQueryString()).isEqualTo(RAW_QUERY);
          });

      assertThat(invoked).as(uri).isTrue();
      verifyNoInteractions(cleaner);
    }
  }

  @Test
  void nearMissRouteStillUsesTheNormalXssWrapper() throws Exception {
    XssCleaner cleaner = value -> "cleaned:" + value;
    XssFilter filter = new XssFilter(new XssProperties(), new AntPathMatcher(), cleaner);
    MockHttpServletRequest request =
        new MockHttpServletRequest(
            "GET", "/app-api/skit/ad-callback/takuish/" + CALLBACK_KEY + "/impression");
    request.setQueryString(RAW_QUERY);
    AtomicBoolean invoked = new AtomicBoolean();

    filter.doFilter(
        request,
        new MockHttpServletResponse(),
        (routed, response) -> {
          invoked.set(true);
          assertThat(routed).isInstanceOf(XssRequestWrapper.class);
          assertThat(((HttpServletRequest) routed).getQueryString())
              .isEqualTo("cleaned:" + RAW_QUERY);
        });

    assertThat(invoked).isTrue();
  }

  @Test
  void attackerControlledPrefixesAndCallbackNearMissesRemainWrapped() throws Exception {
    for (String uri :
        new String[] {
          "/evil/app-api/skit/ad-callback/taku/" + CALLBACK_KEY + "/impression",
          "/app-api/evil/skit/ad-callback/taku/" + CALLBACK_KEY + "/impression",
          "/app-api/skit/ad-callback/taku/" + CALLBACK_KEY + "/impression/extra",
          "/app-api/skit/ad-callback/taku/short/impression",
          "/app-api/skit/ad-callback/pangle/" + CALLBACK_KEY + "/impression"
        }) {
      XssCleaner cleaner = value -> "cleaned:" + value;
      XssFilter filter = new XssFilter(new XssProperties(), new AntPathMatcher(), cleaner);
      MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
      request.setQueryString(RAW_QUERY);
      AtomicBoolean invoked = new AtomicBoolean();

      filter.doFilter(
          request,
          new MockHttpServletResponse(),
          (routed, response) -> {
            invoked.set(true);
            assertThat(routed).as(uri).isInstanceOf(XssRequestWrapper.class);
            assertThat(((HttpServletRequest) routed).getQueryString())
                .isEqualTo("cleaned:" + RAW_QUERY);
          });

      assertThat(invoked).as(uri).isTrue();
    }
  }

  @Test
  void servletContextPathDoesNotChangeTheExactCallbackContract() throws Exception {
    XssCleaner cleaner = mock(XssCleaner.class);
    XssFilter filter = new XssFilter(new XssProperties(), new AntPathMatcher(), cleaner);
    MockHttpServletRequest request =
        new MockHttpServletRequest(
            "GET", "/gateway/app-api/skit/ad-callback/taku/" + CALLBACK_KEY + "/impression");
    request.setContextPath("/gateway");
    request.setQueryString(RAW_QUERY);
    ApiRequestUrlResolver.suppressParameters(request);
    AtomicBoolean invoked = new AtomicBoolean();

    filter.doFilter(
        request,
        new MockHttpServletResponse(),
        (routed, response) -> {
          invoked.set(true);
          assertThat(routed).isSameAs(request);
          assertThat(((HttpServletRequest) routed).getQueryString()).isEqualTo(RAW_QUERY);
        });

    assertThat(invoked).isTrue();
    verifyNoInteractions(cleaner);
  }

  @Test
  void configurableCallbackPrefixUsesTheEarlySanitizerMarker() throws Exception {
    XssCleaner cleaner = mock(XssCleaner.class);
    XssFilter filter = new XssFilter(new XssProperties(), new AntPathMatcher(), cleaner);
    MockHttpServletRequest request =
        new MockHttpServletRequest(
            "GET", "/mobile-api/skit/ad-callback/taku/" + CALLBACK_KEY + "/impression");
    request.setQueryString(RAW_QUERY);
    ApiRequestUrlResolver.suppressParameters(request);
    AtomicBoolean invoked = new AtomicBoolean();

    filter.doFilter(
        request,
        new MockHttpServletResponse(),
        (routed, response) -> {
          invoked.set(true);
          assertThat(routed).isSameAs(request);
          assertThat(((HttpServletRequest) routed).getQueryString()).isEqualTo(RAW_QUERY);
        });

    assertThat(invoked).isTrue();
    verifyNoInteractions(cleaner);
  }
}
