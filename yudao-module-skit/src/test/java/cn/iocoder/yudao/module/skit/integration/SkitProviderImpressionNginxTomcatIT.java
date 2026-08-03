package cn.iocoder.yudao.module.skit.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import cn.iocoder.yudao.framework.apilog.core.filter.ApiAccessLogFilter;
import cn.iocoder.yudao.framework.common.biz.infra.logger.dto.ApiAccessLogCreateReqDTO;
import cn.iocoder.yudao.framework.common.biz.infra.logger.dto.ApiErrorLogCreateReqDTO;
import cn.iocoder.yudao.framework.tenant.config.TenantProperties;
import cn.iocoder.yudao.framework.tenant.core.db.TenantDatabaseInterceptor;
import cn.iocoder.yudao.framework.web.config.WebProperties;
import cn.iocoder.yudao.framework.web.core.util.WebFrameworkUtils;
import cn.iocoder.yudao.framework.xss.config.XssProperties;
import cn.iocoder.yudao.framework.xss.core.filter.XssFilter;
import cn.iocoder.yudao.module.skit.controller.app.ad.SkitTakuCallbackController;
import cn.iocoder.yudao.module.skit.dal.mysql.provider.SkitProviderCallbackAttemptMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.provider.SkitProviderImpressionInboxMapper;
import cn.iocoder.yudao.module.skit.framework.crypto.SkitAesGcmCredentialCryptoService;
import cn.iocoder.yudao.module.skit.framework.crypto.SkitProviderCallbackPayloadCryptoService;
import cn.iocoder.yudao.module.skit.framework.observability.SkitProviderImpressionCaptureObservation;
import cn.iocoder.yudao.module.skit.framework.security.SkitTrustedProxyClientIpResolver;
import cn.iocoder.yudao.module.skit.framework.security.SkitTrustedProxyProperties;
import cn.iocoder.yudao.module.skit.framework.web.SkitCallbackSecretSanitizingFilter;
import cn.iocoder.yudao.module.skit.service.ad.callback.SkitCallbackIngressService;
import cn.iocoder.yudao.module.skit.service.ad.callback.SkitCallbackRateLimiter;
import cn.iocoder.yudao.module.skit.service.ad.callback.SkitCallbackRouteRegistryService;
import cn.iocoder.yudao.module.skit.service.ad.callback.SkitCallbackRoutingService;
import cn.iocoder.yudao.module.skit.service.ad.callback.SkitProviderConnectionCapacityGuard;
import cn.iocoder.yudao.module.skit.service.ad.callback.SkitProviderConnectionCapacityProperties;
import cn.iocoder.yudao.module.skit.service.ad.callback.SkitProviderImpressionCaptureService;
import cn.iocoder.yudao.module.skit.service.ad.callback.SkitProviderImpressionCaptureServiceImpl;
import cn.iocoder.yudao.module.skit.service.ad.callback.SkitProviderImpressionWireParser;
import cn.iocoder.yudao.module.skit.service.ad.callback.SkitTakuCallbackIngressDispatcher;
import cn.iocoder.yudao.module.skit.service.ad.callback.SkitTakuCallbackIngressDispatcherImpl;
import cn.iocoder.yudao.module.skit.service.provider.SkitProviderConnectionService;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.Servlet;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.sql.DataSource;
import org.apache.catalina.Context;
import org.apache.catalina.Wrapper;
import org.apache.catalina.connector.Connector;
import org.apache.catalina.startup.Tomcat;
import org.apache.ibatis.logging.Log;
import org.apache.ibatis.logging.LogFactory;
import org.apache.ibatis.logging.nologging.NoLoggingImpl;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.tomcat.util.descriptor.web.FilterDef;
import org.apache.tomcat.util.descriptor.web.FilterMap;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.mybatis.spring.mapper.MapperFactoryBean;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.util.AntPathMatcher;
import org.testcontainers.Testcontainers;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.utility.DockerImageName;

/**
 * Real edge transport proof for provider-impression ingress.
 *
 * <p>The harness drives the production controller and dispatcher through real servlet filters,
 * Tomcat and Nginx. Only external route-owner collaborators are deterministic fixtures; provider
 * parsing, audit construction, capacity admission and MySQL capture remain real.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SkitProviderImpressionNginxTomcatIT extends SkitMySqlIntegrationTestBase {

  static final String NGINX_IMAGE =
      "nginx@sha256:65645c7bb6a0661892a8b03b89d0743208a18dd2f3f17a54ef4b76fb8e2f2a10";
  static final int HTTP_REQUEST_CEILING_BYTES = 64 * 1024;
  static final int APPLICATION_QUERY_CEILING_BYTES = 32 * 1024;
  static final String UPSTREAM_STATUS_HEADER = "x-observed-upstream-status";
  // The official image links /var/log/nginx logs to live stdout/stderr streams.
  static final String NGINX_ACCESS_LOG_PATH = "/tmp/skit-nginx-access.log";
  static final String NGINX_ERROR_LOG_PATH = "/tmp/skit-nginx-error.log";
  static final String CALLBACK_SERVLET_MAPPING = "/app-api/*";
  private static final String VALID_CALLBACK_KEY = "acct_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
  private static final String UNKNOWN_CALLBACK_KEY = "acct_UUUUUUUUUUUUUUUUUUUUUUUUUUUUUUUUUUUUUU";
  private static final String INVALID_CALLBACK_KEY = "acct_invalid_callback_key";
  private static final String CALLBACK_PREFIX = "/app-api/skit/ad-callback/taku/";
  private static final String USER_AGENT_SENTINEL = "ua-SENTINEL-never-log";
  private static final String ACCEPT_SENTINEL = "accept-SENTINEL-never-log";
  private static final String ACCEPT_ENCODING_SENTINEL = "accept-encoding-SENTINEL-never-log";
  private static final String CONTENT_TYPE_SENTINEL = "application/sentinel-never-log";
  private static final String IP_SENTINEL = "203.0.113.77";
  private static final String QUERY_SENTINEL = "query-SENTINEL-never-log";
  private static final int LOAD_TOTAL_REQUESTS = 8;
  private static final int LOAD_CONCURRENCY = 2;

  private final List<ApiAccessLogCreateReqDTO> accessLogs =
      Collections.synchronizedList(new ArrayList<>());
  private final List<ApiErrorLogCreateReqDTO> errorLogs =
      Collections.synchronizedList(new ArrayList<>());
  private final AtomicLong controllerInvocations = new AtomicLong();
  private final AtomicLong xssCleanerInvocations = new AtomicLong();
  private final WebProperties webProperties = new WebProperties();

  private RealCaptureBoundary captureBoundary;
  private RealTransportHarness transport;
  private SafeLogCapture safeLogCapture;
  private long connectionId;

  @BeforeAll
  void startRealIngressBoundary() throws Exception {
    new WebFrameworkUtils(webProperties);
    connectionId = installProviderConnection();
    captureBoundary = RealCaptureBoundary.start(dataSource());
    SkitTakuCallbackIngressDispatcher dispatcher = createProductionDispatcher(captureBoundary);
    Servlet controllerServlet = createProductionControllerServlet(dispatcher);
    List<Filter> filters =
        Arrays.asList(
            new SkitCallbackSecretSanitizingFilter(webProperties),
            new ApiAccessLogFilter(webProperties, "skit-nginx-tomcat-it", accessLogs::add),
            new XssFilter(
                new XssProperties(),
                new AntPathMatcher(),
                value -> {
                  xssCleanerInvocations.incrementAndGet();
                  return "rewritten-by-xss";
                }));
    transport = RealTransportHarness.start(controllerServlet, filters);
    safeLogCapture = SafeLogCapture.start();
  }

  @AfterAll
  void stopRealIngressBoundary() {
    if (transport != null) {
      transport.close();
    }
    if (safeLogCapture != null) {
      safeLogCapture.close();
    }
    if (captureBoundary != null) {
      captureBoundary.close();
    }
  }

  @Test
  @Order(1)
  void realEdgeRecordsEveryRequiredBoundaryAndPreservesInvalidValuesForCapture() throws Exception {
    assertAccepted(wireSizedQuery(APPLICATION_QUERY_CEILING_BYTES));
    assertRejected(
        "wire-32769",
        "GET",
        providerTarget(
            VALID_CALLBACK_KEY, "impression", wireSizedQuery(APPLICATION_QUERY_CEILING_BYTES + 1)),
        602,
        RejectionLayer.DISPATCHER);
    assertAccepted(queryWithParameterCount(64));
    assertRejected(
        "parameters-65",
        "GET",
        providerTarget(VALID_CALLBACK_KEY, "impression", queryWithParameterCount(65)),
        602,
        RejectionLayer.DISPATCHER);
    assertAccepted(queryWithValueBytes(24 * 1024));
    assertRejected(
        "value-24577",
        "GET",
        providerTarget(VALID_CALLBACK_KEY, "impression", queryWithValueBytes(24 * 1024 + 1)),
        602,
        RejectionLayer.DISPATCHER);
    assertAccepted(validQuery("repeat-a") + "&req_id=repeat-b");
    assertAccepted(validQuery("invalid-value-percent") + "&future=%ZZ");
    assertAccepted(validQuery("invalid-value-utf8") + "&future=%C3%28");
    assertRejected(
        "invalid-name-percent",
        "GET",
        providerTarget(
            VALID_CALLBACK_KEY, "impression", validQuery("invalid-name-percent") + "&bad%ZZ=value"),
        602,
        RejectionLayer.DISPATCHER);
    assertRejected(
        "unsafe-name",
        "GET",
        providerTarget(
            VALID_CALLBACK_KEY, "impression", validQuery("unsafe-name") + "&bad%3C=value"),
        602,
        RejectionLayer.DISPATCHER);
    assertRejected(
        "post",
        "POST",
        providerTarget(VALID_CALLBACK_KEY, "impression", validQuery("post")),
        602,
        RejectionLayer.FILTER);
    assertRejected(
        "bad-key-grammar",
        "GET",
        providerTarget(INVALID_CALLBACK_KEY, "impression", validQuery("bad-key")),
        602,
        RejectionLayer.FILTER);
    assertRejected(
        "unknown-key",
        "GET",
        providerTarget(UNKNOWN_CALLBACK_KEY, "impression", validQuery("unknown-key")),
        602,
        RejectionLayer.DISPATCHER);
    assertRejected(
        "wrong-path",
        "GET",
        providerTarget(VALID_CALLBACK_KEY, "unexpected", validQuery("wrong-path")),
        602,
        RejectionLayer.FILTER);

    byte[] malformed =
        ("GET "
                + providerTarget(VALID_CALLBACK_KEY, "impression", validQuery("malformed-line"))
                + " HTTX/1.1\r\n"
                + "Host: callback.invalid\r\nConnection: close\r\n\r\n")
            .getBytes(StandardCharsets.ISO_8859_1);
    assertRejected("malformed-request-line", malformed, 400, RejectionLayer.NGINX);

    Map<String, String> oversizedHeaders = sentinelHeaders();
    oversizedHeaders.put("X-Bounded-Filler", repeat('h', 65_400));
    assertRejected(
        "tomcat-64k-header-ceiling",
        rawRequest(
            "GET",
            providerTarget(VALID_CALLBACK_KEY, "impression", validQuery("tomcat-ceiling")),
            oversizedHeaders),
        400,
        RejectionLayer.TOMCAT);
  }

  @Test
  @Order(2)
  void ackWaitsForCommitAndInjectedCommitFailureReturns503WithoutRows() throws Exception {
    captureBoundary.transactions.armBlockingCommit();
    ExecutorService executor = Executors.newSingleThreadExecutor();
    Future<RawHttpResponse> response =
        executor.submit(
            () ->
                exchange(
                    "GET",
                    providerTarget(
                        VALID_CALLBACK_KEY, "impression", validQuery("commit-blocked"))));
    try {
      assertTrue(captureBoundary.transactions.awaitCommitEntered(10, TimeUnit.SECONDS));
      assertFalse(response.isDone(), "ACK escaped before the persistence commit completed");
      captureBoundary.transactions.releaseBlockingCommit();
      assertFixedResponse(response.get(10, TimeUnit.SECONDS), 200);
    } finally {
      captureBoundary.transactions.releaseBlockingCommit();
      executor.shutdownNow();
      assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
    }
    assertEquals(1, attemptCount("commit-blocked"));

    captureBoundary.transactions.armFailingCommit();
    RawHttpResponse failed =
        exchange(
            "GET", providerTarget(VALID_CALLBACK_KEY, "impression", validQuery("commit-failure")));
    assertFixedResponse(failed, 503);
    assertEquals(0, inboxCount("commit-failure"));
    assertEquals(0, attemptCount("commit-failure"));
  }

  @Test
  @Order(3)
  void controlledConcurrentGateTestLoadPersistsEveryAttemptAndWritesSafeArtifact()
      throws Exception {
    String requestId = "load-" + UUID.randomUUID().toString().replace("-", "");
    String query = validQuery(requestId);
    ExecutorService workers = Executors.newFixedThreadPool(LOAD_CONCURRENCY);
    CountDownLatch ready = new CountDownLatch(LOAD_CONCURRENCY);
    CountDownLatch start = new CountDownLatch(1);
    List<Future<Long>> futures = new ArrayList<>();
    try {
      for (int index = 0; index < LOAD_TOTAL_REQUESTS; index++) {
        futures.add(
            workers.submit(
                () -> {
                  ready.countDown();
                  if (!start.await(10, TimeUnit.SECONDS)) {
                    throw new TimeoutException("GATE_TEST load start timed out");
                  }
                  long started = System.nanoTime();
                  assertFixedResponse(
                      exchange("GET", providerTarget(VALID_CALLBACK_KEY, "impression", query)),
                      200);
                  return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
                }));
      }
      assertTrue(ready.await(10, TimeUnit.SECONDS));
      start.countDown();
      List<Long> latencies = new ArrayList<>();
      for (Future<Long> future : futures) {
        latencies.add(future.get(30, TimeUnit.SECONDS));
      }
      latencies.sort(Comparator.naturalOrder());
      long p99Millis = latencies.get(Math.max(0, (int) Math.ceil(latencies.size() * 0.99d) - 1));
      int committedInboxes = inboxCount(requestId);
      int committedAttempts = attemptCount(requestId);
      assertTrue(p99Millis < 250, "GATE_TEST p99 must remain below 250ms");
      assertEquals(1, committedInboxes);
      assertEquals(LOAD_TOTAL_REQUESTS, committedAttempts);
      new GateTestLoadResult(
              "nginx-tomcat-" + requestId,
              gitRevision(),
              LOAD_TOTAL_REQUESTS,
              LOAD_CONCURRENCY,
              p99Millis,
              committedInboxes,
              committedAttempts)
          .write(GateTestLoadResult.defaultDestination());
    } finally {
      start.countDown();
      workers.shutdownNow();
      assertTrue(workers.awaitTermination(10, TimeUnit.SECONDS));
    }
  }

  @Test
  @Order(4)
  void nginxApplicationAccessAndErrorLogsContainNoRequestIdentitySentinels() throws Exception {
    RawHttpResponse response =
        exchange(
            "GET", providerTarget(VALID_CALLBACK_KEY, "impression", validQuery(QUERY_SENTINEL)));
    assertFixedResponse(response, 200);
    assertEquals(
        0,
        xssCleanerInvocations.get(),
        "Callback query text passed through the XSS rewriting wrapper");
    assertEquals(
        1,
        inboxCount(QUERY_SENTINEL),
        "The log sentinel must reach the real MySQL capture boundary");

    List<String> surfaces =
        Arrays.asList(
            transport.nginxAccessLog(),
            transport.nginxErrorLog(),
            transport.nginxContainerLog(),
            safeLogCapture.snapshot(),
            accessLogs.toString(),
            errorLogs.toString(),
            new String(response.getBody(), StandardCharsets.ISO_8859_1));
    for (String surface : surfaces) {
      assertNoSensitiveMaterial(surface);
    }
  }

  private SkitTakuCallbackIngressDispatcher createProductionDispatcher(
      RealCaptureBoundary capture) {
    try {
      SkitCallbackRouteRegistryService registryService =
          mock(SkitCallbackRouteRegistryService.class);
      SkitCallbackRouteRegistryService.RouteLookup providerLookup =
          mock(SkitCallbackRouteRegistryService.RouteLookup.class);
      when(providerLookup.getRouteType())
          .thenReturn(SkitCallbackRouteRegistryService.RouteType.PROVIDER_CALLBACK_ROUTE);
      when(providerLookup.getProviderCallbackRouteId()).thenReturn(9_911L);
      byte[] validKeyHash =
          MessageDigest.getInstance("SHA-256")
              .digest(VALID_CALLBACK_KEY.getBytes(StandardCharsets.US_ASCII));
      when(registryService.lookup(
              any(byte[].class), any(LocalDateTime.class), any(LocalDateTime.class)))
          .thenAnswer(
              invocation -> {
                byte[] candidate = invocation.getArgument(0);
                if (MessageDigest.isEqual(validKeyHash, candidate)) {
                  return providerLookup;
                }
                throw new SkitCallbackRouteRegistryService.CallbackRouteRejectedException();
              });

      SkitProviderConnectionService providerConnectionService =
          mock(SkitProviderConnectionService.class);
      SkitProviderConnectionService.ProviderRouteResolution providerRoute =
          mock(SkitProviderConnectionService.ProviderRouteResolution.class);
      when(providerRoute.getProviderConnectionId()).thenReturn(connectionId);
      when(providerRoute.getProviderRouteId()).thenReturn(9_911L);
      when(providerRoute.isAccepting()).thenReturn(true);
      when(providerConnectionService.resolveProviderImpression(
              any(SkitCallbackRouteRegistryService.RouteLookup.class), any(LocalDateTime.class)))
          .thenReturn(providerRoute);

      SkitCallbackRateLimiter rateLimiter =
          new SkitCallbackRateLimiter() {
            @Override
            public void check(
                String provider, String callbackKey, String clientIp, String callbackType) {}

            @Override
            public void checkHashed(
                String provider,
                byte[] callbackKeyHash,
                byte[] packedClientAddress,
                String callbackType) {}

            @Override
            public void checkGlobalAddressHashed(byte[] packedClientAddress) {}

            @Override
            public void checkBusinessKeyHashed(
                String provider, byte[] callbackKeyHash, String callbackType) {}
          };
      SkitProviderConnectionCapacityProperties capacityProperties =
          new SkitProviderConnectionCapacityProperties();
      capacityProperties.setMaximumConcurrentPerConnection(LOAD_CONCURRENCY + 2);
      capacityProperties.setPeakPermitsPerSecond(10_000);
      capacityProperties.setBurstPermits(10_000);
      SkitProviderConnectionCapacityGuard capacityGuard =
          new SkitProviderConnectionCapacityGuard(capacityProperties);

      String auditTypeName =
          "cn.iocoder.yudao.module.skit.service.ad.callback." + "SkitProviderCallbackAuditFactory";
      Class<?> auditType = Class.forName(auditTypeName);
      Constructor<?> auditConstructor =
          auditType.getDeclaredConstructor(String.class, SecureRandom.class, Clock.class);
      auditConstructor.setAccessible(true);
      Object auditFactory =
          auditConstructor.newInstance(
              "0123456789abcdef0123456789abcdef" + "0123456789abcdef0123456789abcdef",
              new SecureRandom(),
              Clock.systemUTC());
      Constructor<SkitTakuCallbackIngressDispatcherImpl> dispatcherConstructor =
          SkitTakuCallbackIngressDispatcherImpl.class.getConstructor(
              SkitCallbackRouteRegistryService.class,
              SkitCallbackRoutingService.class,
              SkitCallbackIngressService.class,
              SkitProviderConnectionService.class,
              SkitProviderImpressionWireParser.class,
              SkitProviderImpressionCaptureService.class,
              SkitCallbackRateLimiter.class,
              SkitProviderConnectionCapacityGuard.class,
              auditType,
              SkitProviderImpressionCaptureObservation.class);
      return dispatcherConstructor.newInstance(
          registryService,
          mock(SkitCallbackRoutingService.class),
          mock(SkitCallbackIngressService.class),
          providerConnectionService,
          Objects.requireNonNull(capture, "capture").getWireParser(),
          capture.getCapture(),
          rateLimiter,
          capacityGuard,
          auditFactory,
          new SkitProviderImpressionCaptureObservation(
              new io.micrometer.core.instrument.simple.SimpleMeterRegistry()));
    } catch (ReflectiveOperationException | java.security.NoSuchAlgorithmException failure) {
      throw new IllegalStateException("Unable to wire the production callback dispatcher", failure);
    }
  }

  private Servlet createProductionControllerServlet(SkitTakuCallbackIngressDispatcher dispatcher) {
    SkitTrustedProxyProperties properties = new SkitTrustedProxyProperties();
    properties.setTrustedProxyCidrs(Arrays.asList("0.0.0.0/0", "::/0"));
    SkitTakuCallbackController controller =
        new SkitTakuCallbackController(
            Objects.requireNonNull(dispatcher, "dispatcher"),
            new SkitTrustedProxyClientIpResolver(properties));
    return new HttpServlet() {
      @Override
      protected void service(HttpServletRequest request, HttpServletResponse response)
          throws IOException {
        String path = request.getPathInfo();
        String prefix = "/skit/ad-callback/taku/";
        if (path == null || !path.startsWith(prefix)) {
          response.setStatus(HttpServletResponse.SC_NOT_FOUND);
          return;
        }
        String keyAndEndpoint = path.substring(prefix.length());
        int separator = keyAndEndpoint.lastIndexOf('/');
        if (separator <= 0 || separator == keyAndEndpoint.length() - 1) {
          response.setStatus(HttpServletResponse.SC_NOT_FOUND);
          return;
        }
        String callbackKey = keyAndEndpoint.substring(0, separator);
        String endpoint = keyAndEndpoint.substring(separator + 1);
        if ("impression".equals(endpoint)) {
          controllerInvocations.incrementAndGet();
          controller.impression(callbackKey, request, response);
        } else if ("reward".equals(endpoint)) {
          controllerInvocations.incrementAndGet();
          controller.reward(callbackKey, request, response);
        } else {
          response.setStatus(HttpServletResponse.SC_NOT_FOUND);
        }
      }
    };
  }

  private long installProviderConnection() {
    LocalDateTime now = LocalDateTime.of(2026, 8, 3, 8, 0, 0);
    jdbc()
        .update(
            "INSERT INTO skit_ad_provider_connection "
                + "(connection_code,provider,account_mode,external_account_ref_hash,state,"
                + "created_by_user_id,created_at,updated_by_user_id,updated_at) "
                + "VALUES ('nginx-tomcat-it','TAKU','SHARED_MASTER',"
                + "UNHEX(REPEAT('51',32)),'CONFIGURING',7,?,7,?)",
            now,
            now);
    return jdbc()
        .queryForObject(
            "SELECT id FROM skit_ad_provider_connection "
                + "WHERE connection_code='nginx-tomcat-it'",
            Long.class);
  }

  private RawHttpResponse exchange(String method, String target) throws IOException {
    return transport.exchange(rawRequest(method, target, sentinelHeaders()));
  }

  private void assertAccepted(String query) throws Exception {
    assertFixedResponse(
        exchange("GET", providerTarget(VALID_CALLBACK_KEY, "impression", query)), 200);
  }

  private void assertRejected(
      String label, String method, String target, int expectedStatus, RejectionLayer expectedLayer)
      throws Exception {
    assertRejected(
        label, rawRequest(method, target, sentinelHeaders()), expectedStatus, expectedLayer);
  }

  private void assertRejected(
      String label, byte[] request, int expectedStatus, RejectionLayer expectedLayer)
      throws Exception {
    LayerObservation observation =
        new LayerObservation(
            transport.getTomcatAcceptedRequestCount(), controllerInvocations.get());
    RawHttpResponse response = transport.exchange(request);
    assertFixedResponse(response, expectedStatus);
    assertEquals(
        expectedLayer,
        observation.classify(
            response, transport.getTomcatAcceptedRequestCount(), controllerInvocations.get()),
        label);
  }

  private static void assertFixedResponse(RawHttpResponse response, int expectedStatus) {
    assertEquals(expectedStatus, response.getStatus());
    String cacheControl = response.getHeader("cache-control");
    assertTrue(
        cacheControl != null && cacheControl.contains("no-store"),
        "Callback response is cacheable");
    assertNoSensitiveMaterial(new String(response.getBody(), StandardCharsets.ISO_8859_1));
  }

  private int inboxCount(String requestId) {
    return jdbc()
        .queryForObject(
            "SELECT COUNT(*) FROM skit_provider_impression_inbox "
                + "WHERE provider_connection_id=? AND provider_request_id_lexical=?",
            Integer.class,
            connectionId,
            requestId);
  }

  private int attemptCount(String requestId) {
    return jdbc()
        .queryForObject(
            "SELECT COUNT(*) FROM skit_provider_callback_attempt a "
                + "JOIN skit_provider_impression_inbox i ON i.id=a.inbox_id "
                + "WHERE i.provider_connection_id=? AND i.provider_request_id_lexical=?",
            Integer.class,
            connectionId,
            requestId);
  }

  private static String providerTarget(String callbackKey, String endpoint, String query) {
    return CALLBACK_PREFIX + callbackKey + "/" + endpoint + (query == null ? "" : "?" + query);
  }

  private static String validQuery(String requestId) {
    return "req_id="
        + requestId
        + "&adsource_id=42&package_name=com.skit.edge"
        + "&adformat=1&placement_id=slot-1&nw_firm_id=66&adsource_price=3.24"
        + "&currency=USD&timestamp=1783987200123&show_custom_ext=session-1";
  }

  private static String wireSizedQuery(int expectedBytes) {
    StringBuilder query = new StringBuilder(validQuery("wire-boundary"));
    int index = 0;
    while (query.toString().getBytes(StandardCharsets.UTF_8).length < expectedBytes) {
      String prefix = "&pad" + index++ + "=";
      int current = query.toString().getBytes(StandardCharsets.UTF_8).length;
      int valueLength = Math.min(12_000, expectedBytes - current - prefix.length());
      if (valueLength < 0) {
        throw new IllegalArgumentException("Unable to construct exact wire boundary");
      }
      query.append(prefix).append(repeat('x', valueLength));
    }
    assertEquals(expectedBytes, query.toString().getBytes(StandardCharsets.UTF_8).length);
    return query.toString();
  }

  private static String queryWithParameterCount(int parameterCount) {
    StringBuilder query = new StringBuilder(validQuery("parameter-boundary"));
    for (int index = 10; index < parameterCount; index++) {
      query.append("&future").append(index).append("=x");
    }
    return query.toString();
  }

  private static String queryWithValueBytes(int valueBytes) {
    return validQuery("value-boundary") + "&future=" + repeat('v', valueBytes);
  }

  private static String repeat(char value, int count) {
    char[] result = new char[count];
    Arrays.fill(result, value);
    return new String(result);
  }

  private static Map<String, String> sentinelHeaders() {
    Map<String, String> headers = new LinkedHashMap<>();
    headers.put("User-Agent", USER_AGENT_SENTINEL);
    headers.put("Accept", ACCEPT_SENTINEL);
    headers.put("Accept-Encoding", ACCEPT_ENCODING_SENTINEL);
    headers.put("Content-Type", CONTENT_TYPE_SENTINEL);
    return headers;
  }

  private static String gitRevision() {
    String revision = System.getenv("GITHUB_SHA");
    return revision == null || revision.trim().isEmpty() ? "unknown" : revision.trim();
  }

  private static void assertNoSensitiveMaterial(String value) {
    String safeValue = value == null ? "" : value;
    for (String secret :
        Arrays.asList(
            VALID_CALLBACK_KEY,
            UNKNOWN_CALLBACK_KEY,
            QUERY_SENTINEL,
            IP_SENTINEL,
            USER_AGENT_SENTINEL,
            ACCEPT_SENTINEL,
            ACCEPT_ENCODING_SENTINEL,
            CONTENT_TYPE_SENTINEL)) {
      assertFalse(
          safeValue.contains(secret),
          "A callback request identity sentinel reached an observable surface");
    }
  }

  enum RejectionLayer {
    NGINX,
    TOMCAT,
    FILTER,
    DISPATCHER
  }

  static final class RealCaptureBoundary implements AutoCloseable {

    private final AnnotationConfigApplicationContext context;
    private final SkitProviderImpressionCaptureService capture;
    private final SkitProviderImpressionWireParser wireParser;
    private final FailpointTransactionManager transactions;

    private RealCaptureBoundary(
        AnnotationConfigApplicationContext context,
        SkitProviderImpressionCaptureService capture,
        SkitProviderImpressionWireParser wireParser,
        FailpointTransactionManager transactions) {
      this.context = context;
      this.capture = capture;
      this.wireParser = wireParser;
      this.transactions = transactions;
    }

    static RealCaptureBoundary start(DataSource dataSource) {
      AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
      context.registerBean("dataSource", DataSource.class, () -> dataSource);
      context.register(RealCaptureConfiguration.class);
      Class<? extends Log> previousLogImplementation =
          LogFactory.getLog(RealCaptureBoundary.class).getClass().asSubclass(Log.class);
      try {
        context.refresh();
        return new RealCaptureBoundary(
            context,
            context.getBean(SkitProviderImpressionCaptureService.class),
            context.getBean(SkitProviderImpressionWireParser.class),
            context.getBean(FailpointTransactionManager.class));
      } finally {
        LogFactory.useCustomLogging(previousLogImplementation);
      }
    }

    SkitProviderImpressionCaptureService getCapture() {
      return capture;
    }

    SkitProviderImpressionWireParser getWireParser() {
      return wireParser;
    }

    @Override
    public void close() {
      context.close();
    }
  }

  static final class FailpointTransactionManager implements PlatformTransactionManager {

    private enum CommitMode {
      NORMAL,
      BLOCK,
      FAIL
    }

    private final PlatformTransactionManager delegate;
    private final AtomicReference<CommitMode> nextCommit = new AtomicReference<>(CommitMode.NORMAL);
    private volatile CountDownLatch commitEntered = new CountDownLatch(0);
    private volatile CountDownLatch commitRelease = new CountDownLatch(0);

    FailpointTransactionManager(DataSource dataSource) {
      this.delegate = new DataSourceTransactionManager(dataSource);
    }

    void armBlockingCommit() {
      commitEntered = new CountDownLatch(1);
      commitRelease = new CountDownLatch(1);
      if (!nextCommit.compareAndSet(CommitMode.NORMAL, CommitMode.BLOCK)) {
        throw new IllegalStateException("A provider commit failpoint is already armed");
      }
    }

    boolean awaitCommitEntered(long timeout, TimeUnit unit) throws InterruptedException {
      return commitEntered.await(timeout, unit);
    }

    void releaseBlockingCommit() {
      commitRelease.countDown();
    }

    void armFailingCommit() {
      if (!nextCommit.compareAndSet(CommitMode.NORMAL, CommitMode.FAIL)) {
        throw new IllegalStateException("A provider commit failpoint is already armed");
      }
    }

    @Override
    public TransactionStatus getTransaction(TransactionDefinition definition) {
      return delegate.getTransaction(definition);
    }

    @Override
    public void commit(TransactionStatus status) {
      CommitMode mode = nextCommit.getAndSet(CommitMode.NORMAL);
      if (mode == CommitMode.BLOCK) {
        commitEntered.countDown();
        try {
          if (!commitRelease.await(10, TimeUnit.SECONDS)) {
            delegate.rollback(status);
            throw new TransactionSystemException("Injected provider commit gate timed out");
          }
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          delegate.rollback(status);
          throw new TransactionSystemException(
              "Injected provider commit gate interrupted", interrupted);
        }
      } else if (mode == CommitMode.FAIL) {
        delegate.rollback(status);
        throw new TransactionSystemException("Injected provider capture commit failure");
      }
      delegate.commit(status);
    }

    @Override
    public void rollback(TransactionStatus status) {
      delegate.rollback(status);
    }
  }

  @Configuration(proxyBeanMethods = false)
  static class RealCaptureConfiguration {

    @Bean
    TenantProperties tenantProperties() {
      return new TenantProperties();
    }

    @Bean
    MybatisPlusInterceptor mybatisPlusInterceptor(TenantProperties tenantProperties) {
      MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
      interceptor.addInnerInterceptor(
          new TenantLineInnerInterceptor(new TenantDatabaseInterceptor(tenantProperties)));
      return interceptor;
    }

    @Bean
    MybatisSqlSessionFactoryBean sqlSessionFactory(
        DataSource dataSource, MybatisPlusInterceptor interceptor) {
      MybatisConfiguration configuration = new MybatisConfiguration();
      configuration.setMapUnderscoreToCamelCase(true);
      configuration.setLogImpl(NoLoggingImpl.class);
      MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
      factory.setDataSource(dataSource);
      factory.setConfiguration(configuration);
      factory.setPlugins(interceptor);
      return factory;
    }

    @Bean
    MapperFactoryBean<SkitProviderImpressionInboxMapper> providerImpressionInboxMapper(
        SqlSessionFactory sqlSessionFactory) {
      return mapperFactory(SkitProviderImpressionInboxMapper.class, sqlSessionFactory);
    }

    @Bean
    MapperFactoryBean<SkitProviderCallbackAttemptMapper> providerCallbackAttemptMapper(
        SqlSessionFactory sqlSessionFactory) {
      return mapperFactory(SkitProviderCallbackAttemptMapper.class, sqlSessionFactory);
    }

    @Bean
    FailpointTransactionManager transactionManager(DataSource dataSource) {
      return new FailpointTransactionManager(dataSource);
    }

    @Bean
    SkitProviderImpressionWireParser providerWireParser() {
      return new SkitProviderImpressionWireParser();
    }

    @Bean
    SkitProviderCallbackPayloadCryptoService providerPayloadCrypto() {
      return new SkitProviderCallbackPayloadCryptoService(
          new SkitAesGcmCredentialCryptoService(
              "nginx-tomcat-it",
              Collections.singletonMap(
                  "nginx-tomcat-it",
                  "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.US_ASCII))));
    }

    @Bean
    SkitProviderImpressionCaptureService providerCapture(
        SkitProviderImpressionWireParser parser,
        SkitProviderCallbackPayloadCryptoService crypto,
        SkitProviderImpressionInboxMapper inboxMapper,
        SkitProviderCallbackAttemptMapper attemptMapper,
        FailpointTransactionManager transactionManager) {
      return new SkitProviderImpressionCaptureServiceImpl(
          parser, crypto, inboxMapper, attemptMapper, transactionManager);
    }

    private static <T> MapperFactoryBean<T> mapperFactory(
        Class<T> mapperType, SqlSessionFactory sqlSessionFactory) {
      MapperFactoryBean<T> factory = new MapperFactoryBean<>(mapperType);
      factory.setSqlSessionFactory(sqlSessionFactory);
      return factory;
    }
  }

  static final class SafeLogCapture implements AutoCloseable {

    private final ch.qos.logback.classic.Logger rootLogger;
    private final ch.qos.logback.classic.Logger inboxMapperLogger;
    private final ch.qos.logback.classic.Logger attemptMapperLogger;
    private final Level previousInboxMapperLevel;
    private final Level previousAttemptMapperLevel;
    private final ListAppender<ILoggingEvent> logbackAppender;
    private final java.util.logging.Logger julRootLogger;
    private final CollectingJulHandler julHandler;

    private SafeLogCapture(
        ch.qos.logback.classic.Logger rootLogger,
        ch.qos.logback.classic.Logger inboxMapperLogger,
        ch.qos.logback.classic.Logger attemptMapperLogger,
        Level previousInboxMapperLevel,
        Level previousAttemptMapperLevel,
        ListAppender<ILoggingEvent> logbackAppender,
        java.util.logging.Logger julRootLogger,
        CollectingJulHandler julHandler) {
      this.rootLogger = rootLogger;
      this.inboxMapperLogger = inboxMapperLogger;
      this.attemptMapperLogger = attemptMapperLogger;
      this.previousInboxMapperLevel = previousInboxMapperLevel;
      this.previousAttemptMapperLevel = previousAttemptMapperLevel;
      this.logbackAppender = logbackAppender;
      this.julRootLogger = julRootLogger;
      this.julHandler = julHandler;
    }

    static SafeLogCapture start() {
      LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
      ch.qos.logback.classic.Logger root = context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
      ch.qos.logback.classic.Logger inboxMapper =
          context.getLogger(SkitProviderImpressionInboxMapper.class.getName());
      ch.qos.logback.classic.Logger attemptMapper =
          context.getLogger(SkitProviderCallbackAttemptMapper.class.getName());
      Level previousInboxLevel = inboxMapper.getLevel();
      Level previousAttemptLevel = attemptMapper.getLevel();
      inboxMapper.setLevel(Level.DEBUG);
      attemptMapper.setLevel(Level.DEBUG);
      ListAppender<ILoggingEvent> appender = new ListAppender<>();
      appender.setContext(context);
      appender.start();
      root.addAppender(appender);
      java.util.logging.Logger julRoot = java.util.logging.Logger.getLogger("");
      CollectingJulHandler handler = new CollectingJulHandler();
      julRoot.addHandler(handler);
      return new SafeLogCapture(
          root,
          inboxMapper,
          attemptMapper,
          previousInboxLevel,
          previousAttemptLevel,
          appender,
          julRoot,
          handler);
    }

    String snapshot() {
      StringBuilder result = new StringBuilder();
      synchronized (logbackAppender.list) {
        for (ILoggingEvent event : logbackAppender.list) {
          result.append(event.getFormattedMessage()).append('\n');
          if (event.getThrowableProxy() != null) {
            result.append(event.getThrowableProxy().getMessage()).append('\n');
          }
        }
      }
      for (LogRecord record : julHandler.snapshot()) {
        result.append(record.getMessage()).append('\n');
        if (record.getThrown() != null) {
          result.append(record.getThrown().getMessage()).append('\n');
        }
      }
      return result.toString();
    }

    @Override
    public void close() {
      rootLogger.detachAppender(logbackAppender);
      logbackAppender.stop();
      inboxMapperLogger.setLevel(previousInboxMapperLevel);
      attemptMapperLogger.setLevel(previousAttemptMapperLevel);
      julRootLogger.removeHandler(julHandler);
    }
  }

  static final class CollectingJulHandler extends Handler {

    private final List<LogRecord> records = Collections.synchronizedList(new ArrayList<>());

    @Override
    public void publish(LogRecord record) {
      if (record != null) {
        records.add(record);
      }
    }

    List<LogRecord> snapshot() {
      synchronized (records) {
        return new ArrayList<>(records);
      }
    }

    @Override
    public void flush() {}

    @Override
    public void close() {
      records.clear();
    }
  }

  static final class RealTransportHarness implements AutoCloseable {

    private final Tomcat tomcat;
    private final GenericContainer<?> nginx;
    private final AtomicLong tomcatAcceptedRequests;
    private final Path tomcatBaseDirectory;

    private RealTransportHarness(
        Tomcat tomcat,
        GenericContainer<?> nginx,
        AtomicLong tomcatAcceptedRequests,
        Path tomcatBaseDirectory) {
      this.tomcat = tomcat;
      this.nginx = nginx;
      this.tomcatAcceptedRequests = tomcatAcceptedRequests;
      this.tomcatBaseDirectory = tomcatBaseDirectory;
    }

    static RealTransportHarness start(Servlet callbackServlet, List<Filter> filters)
        throws Exception {
      Objects.requireNonNull(callbackServlet, "callbackServlet");
      List<Filter> applicationFilters =
          filters == null ? Collections.emptyList() : new ArrayList<>(filters);
      Path baseDirectory = Files.createTempDirectory("skit-nginx-tomcat-it-");
      Path webApplication = Files.createDirectories(baseDirectory.resolve("webapp"));
      Tomcat tomcat = new Tomcat();
      tomcat.setBaseDir(baseDirectory.toString());
      tomcat.setPort(0);
      Connector connector = tomcat.getConnector();
      connector.setProperty("maxHttpHeaderSize", Integer.toString(HTTP_REQUEST_CEILING_BYTES));
      connector.setProperty(
          "maxHttpRequestHeaderSize", Integer.toString(HTTP_REQUEST_CEILING_BYTES));
      connector.setProperty("rejectIllegalHeader", "true");
      Context context = tomcat.addContext("", webApplication.toString());
      context.setParentClassLoader(Thread.currentThread().getContextClassLoader());

      AtomicLong tomcatAccepted = new AtomicLong();
      addFilter(
          context,
          "tomcatAcceptedObservation",
          new TomcatAcceptedObservationFilter(tomcatAccepted));
      for (int index = 0; index < applicationFilters.size(); index++) {
        addFilter(context, "applicationFilter" + index, applicationFilters.get(index));
      }
      Wrapper wrapper = Tomcat.addServlet(context, "callbackServlet", callbackServlet);
      wrapper.setLoadOnStartup(1);
      wrapper.setAsyncSupported(false);
      context.addServletMappingDecoded(CALLBACK_SERVLET_MAPPING, "callbackServlet");

      GenericContainer<?> nginx = null;
      try {
        tomcat.start();
        int tomcatPort = connector.getLocalPort();
        if (tomcatPort <= 0) {
          throw new IllegalStateException("Embedded Tomcat did not bind a port");
        }
        Testcontainers.exposeHostPorts(tomcatPort);
        nginx =
            new GenericContainer<>(DockerImageName.parse(NGINX_IMAGE))
                .withAccessToHost(true)
                .withExposedPorts(8080)
                .withCopyToContainer(
                    Transferable.of(nginxConfiguration(tomcatPort), 0644), "/etc/nginx/nginx.conf")
                .waitingFor(Wait.forListeningPort());
        nginx.start();
        return new RealTransportHarness(tomcat, nginx, tomcatAccepted, baseDirectory);
      } catch (Exception failure) {
        if (nginx != null) {
          nginx.stop();
        }
        stopTomcat(tomcat);
        throw failure;
      }
    }

    long getTomcatAcceptedRequestCount() {
      return tomcatAcceptedRequests.get();
    }

    RawHttpResponse exchange(byte[] rawRequest) throws IOException {
      Objects.requireNonNull(rawRequest, "rawRequest");
      try (Socket socket = new Socket()) {
        socket.connect(new InetSocketAddress(nginx.getHost(), nginx.getMappedPort(8080)), 10_000);
        socket.setSoTimeout(30_000);
        OutputStream output = socket.getOutputStream();
        output.write(rawRequest);
        output.flush();
        return RawHttpResponse.parse(readAll(socket.getInputStream()));
      }
    }

    String nginxAccessLog() throws IOException, InterruptedException {
      return containerFile(NGINX_ACCESS_LOG_PATH);
    }

    String nginxErrorLog() throws IOException, InterruptedException {
      return containerFile(NGINX_ERROR_LOG_PATH);
    }

    String nginxContainerLog() {
      return nginx.getLogs();
    }

    Path getTomcatBaseDirectory() {
      return tomcatBaseDirectory;
    }

    @Override
    public void close() {
      nginx.stop();
      stopTomcat(tomcat);
    }

    private String containerFile(String path) throws IOException, InterruptedException {
      Container.ExecResult result = nginx.execInContainer("cat", path);
      if (result.getExitCode() != 0) {
        throw new IOException("Unable to read redacted Nginx test log");
      }
      return result.getStdout();
    }
  }

  static final class RawHttpResponse {

    private final int status;
    private final Map<String, String> headers;
    private final byte[] body;

    private RawHttpResponse(int status, Map<String, String> headers, byte[] body) {
      this.status = status;
      this.headers = Collections.unmodifiableMap(new LinkedHashMap<>(headers));
      this.body = body.clone();
    }

    static RawHttpResponse parse(byte[] wireBytes) {
      String wire = new String(wireBytes, StandardCharsets.ISO_8859_1);
      int headerEnd = wire.indexOf("\r\n\r\n");
      String headerBlock = headerEnd < 0 ? wire : wire.substring(0, headerEnd);
      String[] lines = headerBlock.split("\r\n", -1);
      int parsedStatus = 0;
      if (lines.length > 0) {
        String[] statusLine = lines[0].split(" ", 3);
        if (statusLine.length >= 2) {
          try {
            parsedStatus = Integer.parseInt(statusLine[1]);
          } catch (NumberFormatException ignored) {
            parsedStatus = 0;
          }
        }
      }
      Map<String, String> parsedHeaders = new LinkedHashMap<>();
      for (int index = 1; index < lines.length; index++) {
        int separator = lines[index].indexOf(':');
        if (separator > 0) {
          parsedHeaders.put(
              lines[index].substring(0, separator).trim().toLowerCase(Locale.ROOT),
              lines[index].substring(separator + 1).trim());
        }
      }
      byte[] parsedBody =
          headerEnd < 0
              ? new byte[0]
              : wire.substring(headerEnd + 4).getBytes(StandardCharsets.ISO_8859_1);
      return new RawHttpResponse(parsedStatus, parsedHeaders, parsedBody);
    }

    int getStatus() {
      return status;
    }

    String getHeader(String name) {
      return headers.get(name.toLowerCase(Locale.ROOT));
    }

    byte[] getBody() {
      return body.clone();
    }
  }

  static final class LayerObservation {

    private final long tomcatAcceptedBefore;
    private final long dispatcherInvocationsBefore;

    LayerObservation(long tomcatAcceptedBefore, long dispatcherInvocationsBefore) {
      this.tomcatAcceptedBefore = tomcatAcceptedBefore;
      this.dispatcherInvocationsBefore = dispatcherInvocationsBefore;
    }

    RejectionLayer classify(
        RawHttpResponse response, long tomcatAcceptedAfter, long dispatcherInvocationsAfter) {
      String upstreamStatus = response.getHeader(UPSTREAM_STATUS_HEADER);
      if (upstreamStatus == null || upstreamStatus.isEmpty() || "-".equals(upstreamStatus)) {
        return RejectionLayer.NGINX;
      }
      if (tomcatAcceptedAfter == tomcatAcceptedBefore) {
        return RejectionLayer.TOMCAT;
      }
      if (dispatcherInvocationsAfter == dispatcherInvocationsBefore) {
        return RejectionLayer.FILTER;
      }
      return RejectionLayer.DISPATCHER;
    }
  }

  static final class GateTestLoadResult {

    private final String fixtureId;
    private final String gitRevision;
    private final int totalRequests;
    private final int concurrency;
    private final long p99Millis;
    private final int committedInboxCount;
    private final int committedAttemptCount;

    GateTestLoadResult(
        String fixtureId,
        String gitRevision,
        int totalRequests,
        int concurrency,
        long p99Millis,
        int committedInboxCount,
        int committedAttemptCount) {
      this.fixtureId = requireBounded(fixtureId, "fixtureId");
      this.gitRevision = requireBounded(gitRevision, "gitRevision");
      this.totalRequests = requireNonNegative(totalRequests, "totalRequests");
      this.concurrency = requireNonNegative(concurrency, "concurrency");
      this.p99Millis = requireNonNegative(p99Millis, "p99Millis");
      this.committedInboxCount = requireNonNegative(committedInboxCount, "committedInboxCount");
      this.committedAttemptCount =
          requireNonNegative(committedAttemptCount, "committedAttemptCount");
    }

    void write(Path destination) throws IOException {
      Objects.requireNonNull(destination, "destination");
      Path parent = destination.getParent();
      if (parent != null) {
        Files.createDirectories(parent);
      }
      Map<String, Object> artifact = new LinkedHashMap<>();
      artifact.put("schema_version", 1);
      artifact.put("purpose", "GATE_TEST");
      artifact.put("fixture_id", fixtureId);
      artifact.put("git_revision", gitRevision);
      artifact.put("total_requests", totalRequests);
      artifact.put("concurrency", concurrency);
      artifact.put("p99_ms", p99Millis);
      artifact.put("committed_inbox_count", committedInboxCount);
      artifact.put("committed_attempt_count", committedAttemptCount);
      artifact.put(
          "missing_committed_attempts", Math.max(0, totalRequests - committedAttemptCount));
      artifact.put("production_eligible", false);
      new ObjectMapper().writeValue(destination.toFile(), artifact);
    }

    static Path defaultDestination() {
      Path workingDirectory = Paths.get(System.getProperty("user.dir"));
      if (workingDirectory.getFileName() != null
          && "yudao-module-skit".equals(workingDirectory.getFileName().toString())) {
        return workingDirectory.resolve("target/provider-impression-load-result.json");
      }
      return workingDirectory.resolve(
          "yudao-module-skit/target/provider-impression-load-result.json");
    }

    private static String requireBounded(String value, String field) {
      if (value == null || value.isEmpty() || value.length() > 128) {
        throw new IllegalArgumentException(field + " is invalid");
      }
      return value;
    }

    private static int requireNonNegative(int value, String field) {
      if (value < 0) {
        throw new IllegalArgumentException(field + " is invalid");
      }
      return value;
    }

    private static long requireNonNegative(long value, String field) {
      if (value < 0) {
        throw new IllegalArgumentException(field + " is invalid");
      }
      return value;
    }
  }

  static byte[] rawRequest(String method, String requestTarget, Map<String, String> headers) {
    StringBuilder request = new StringBuilder();
    request
        .append(method)
        .append(' ')
        .append(requestTarget)
        .append(" HTTP/1.1\r\n")
        .append("Host: callback.invalid\r\n")
        .append("Connection: close\r\n");
    if (headers != null) {
      for (Map.Entry<String, String> header : headers.entrySet()) {
        request.append(header.getKey()).append(": ").append(header.getValue()).append("\r\n");
      }
    }
    request.append("\r\n");
    return request.toString().getBytes(StandardCharsets.ISO_8859_1);
  }

  private static void addFilter(Context context, String name, Filter filter) {
    FilterDef definition = new FilterDef();
    definition.setFilterName(name);
    definition.setFilter(filter);
    definition.setAsyncSupported("false");
    context.addFilterDef(definition);
    FilterMap mapping = new FilterMap();
    mapping.setFilterName(name);
    mapping.addURLPattern("/*");
    mapping.setDispatcher("REQUEST");
    context.addFilterMap(mapping);
  }

  private static String nginxConfiguration(int tomcatPort) {
    return "worker_processes 1;\n"
        + "error_log "
        + NGINX_ERROR_LOG_PATH
        + " crit;\n"
        + "pid /tmp/nginx.pid;\n"
        + "events { worker_connections 512; }\n"
        + "http {\n"
        + "  log_format redacted '$status $upstream_status';\n"
        + "  access_log "
        + NGINX_ACCESS_LOG_PATH
        + " redacted;\n"
        + "  client_header_buffer_size 64k;\n"
        + "  large_client_header_buffers 4 64k;\n"
        + "  client_body_temp_path /tmp/client-body;\n"
        + "  proxy_temp_path /tmp/proxy;\n"
        + "  server {\n"
        + "    listen 8080;\n"
        + "    server_tokens off;\n"
        + "    add_header Cache-Control 'no-store' always;\n"
        + "    location / {\n"
        + "      proxy_http_version 1.1;\n"
        + "      proxy_set_header Connection '';\n"
        + "      proxy_set_header Host callback.invalid;\n"
        + "      proxy_set_header X-Real-IP 203.0.113.77;\n"
        + "      proxy_set_header X-Forwarded-For '';\n"
        + "      proxy_pass http://host.testcontainers.internal:"
        + tomcatPort
        + ";\n"
        + "      add_header Cache-Control 'no-store' always;\n"
        + "      add_header X-Observed-Upstream-Status $upstream_status always;\n"
        + "    }\n"
        + "  }\n"
        + "}\n";
  }

  private static byte[] readAll(InputStream input) throws IOException {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    byte[] buffer = new byte[8192];
    int read;
    while ((read = input.read(buffer)) >= 0) {
      output.write(buffer, 0, read);
    }
    return output.toByteArray();
  }

  private static void stopTomcat(Tomcat tomcat) {
    try {
      tomcat.stop();
    } catch (Exception ignored) {
      // Best-effort cleanup after test failure.
    }
    try {
      tomcat.destroy();
    } catch (Exception ignored) {
      // Best-effort cleanup after test failure.
    }
  }

  private static final class TomcatAcceptedObservationFilter implements Filter {

    private final AtomicLong acceptedRequests;

    private TomcatAcceptedObservationFilter(AtomicLong acceptedRequests) {
      this.acceptedRequests = acceptedRequests;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
        throws IOException, ServletException {
      acceptedRequests.incrementAndGet();
      chain.doFilter(request, response);
    }
  }
}
