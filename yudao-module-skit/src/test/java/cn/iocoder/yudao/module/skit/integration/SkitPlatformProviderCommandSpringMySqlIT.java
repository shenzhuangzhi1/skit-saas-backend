package cn.iocoder.yudao.module.skit.integration;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.skit.dal.mysql.provider.SkitAdProviderCallbackRouteMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.provider.SkitAdProviderConnectionMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.provider.SkitPlatformProviderCommandAuditMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.provider.SkitProviderConnectionReadMapper;
import cn.iocoder.yudao.module.skit.service.ad.callback.SkitCallbackRouteRegistryService;
import cn.iocoder.yudao.module.skit.service.provider.SkitCurrentAdminPasswordReauthService;
import cn.iocoder.yudao.module.skit.service.provider.SkitPlatformProviderCommandExecutor;
import cn.iocoder.yudao.module.skit.service.provider.SkitProviderConnectionService;
import java.lang.reflect.Field;
import java.nio.CharBuffer;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataAccessException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/** Real Spring/MySQL proof for the outer REQUIRED lifecycle-plus-audit boundary. */
class SkitPlatformProviderCommandSpringMySqlIT extends SkitMySqlIntegrationTestBase {

  private AnnotationConfigApplicationContext context;
  private SkitPlatformProviderCommandExecutor executor;
  private CapturingProviderConnectionService lifecycle;
  private AtomicReference<String> traceId;
  private AtomicInteger passwordMatches;

  @BeforeAll
  void startContext() {
    context = new AnnotationConfigApplicationContext();
    context.registerBean("dataSource", javax.sql.DataSource.class, this::dataSource);
    context.register(
        SkitProviderConnectionLifecycleSpringMySqlIT.ProviderLifecycleConfiguration.class,
        CommandConfiguration.class);
    context.refresh();
    executor = context.getBean(SkitPlatformProviderCommandExecutor.class);
    lifecycle = context.getBean(CapturingProviderConnectionService.class);
    traceId = context.getBean("commandTraceId", AtomicReference.class);
    passwordMatches = context.getBean("passwordMatches", AtomicInteger.class);
    assertTrue(AopUtils.isAopProxy(executor));
  }

  @BeforeEach
  void authenticate() {
    LoginUser loginUser = new LoginUser().setId(7L).setTenantId(1L).setVisitTenantId(42L);
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken(loginUser, null, Collections.emptyList()));
  }

  @AfterEach
  void clearThreadContexts() {
    SecurityContextHolder.clearContext();
    TenantContextHolder.clear();
  }

  @AfterAll
  void stopContext() {
    if (context != null) {
      context.close();
    }
  }

  @Test
  void outerTransactionCommitsLifecycleWithAuditAndRollsBackIssueWhenAuditFails() throws Exception {
    TenantContextHolder.setTenantId(773L);
    TenantContextHolder.setIgnore(false);
    traceId.set("task6_create_success");
    char[] createPassword = "current-password".toCharArray();
    char[] externalReference = "real-account-reference".toCharArray();

    SkitPlatformProviderCommandExecutor.ResourceView connection =
        executor.createSharedMaster(
            createPassword, externalReference, "create shared master after provider verification");

    assertCleared(createPassword);
    assertCleared(externalReference);
    assertEquals(773L, TenantContextHolder.getTenantId());
    assertFalse(TenantContextHolder.isIgnore());
    assertEquals(1, count("skit_ad_provider_connection"));
    assertEquals(1, count("skit_platform_provider_command_audit"));

    traceId.set("task6_draft_success");
    char[] draftPassword = "current-password".toCharArray();
    SkitPlatformProviderCommandExecutor.ResourceView draft =
        executor.createDraftRoute(
            connection.getConnectionId(),
            SkitProviderConnectionService.RoutePurpose.GATE_TEST,
            draftPassword,
            "create isolated gate test callback route");
    assertCleared(draftPassword);
    long routeId = draft.getRouteId();
    assertEquals(1, count("skit_ad_provider_callback_route"));
    assertEquals(2, count("skit_platform_provider_command_audit"));
    assertEquals(2, passwordMatches.get());
    assertEquals(
        1L,
        jdbc()
            .queryForObject(
                "SELECT original_login_tenant_id FROM skit_platform_provider_command_audit "
                    + "WHERE trace_id='task6_draft_success'",
                Long.class));
    assertEquals(
        "DRAFT",
        jdbc()
            .queryForObject(
                "SELECT state FROM skit_ad_provider_callback_route WHERE id=?",
                String.class,
                routeId));
    assertNotNull(executor.getConnection(connection.getConnectionId()));

    insertAuditWithTrace(connection.getConnectionId(), routeId, "task6_duplicate_trace");
    int auditRowsBeforeFailure = count("skit_platform_provider_command_audit");
    traceId.set("task6_duplicate_trace");
    lifecycle.resetCapturedIssued();
    TenantContextHolder.setIgnore(true);
    char[] issuePassword = "current-password".toCharArray();

    assertThrows(
        RuntimeException.class,
        () ->
            executor.issueOnce(
                routeId, issuePassword, "issue gate callback route for rollback proof"));

    assertCleared(issuePassword);
    assertEquals(773L, TenantContextHolder.getTenantId());
    assertTrue(TenantContextHolder.isIgnore());
    assertEquals(auditRowsBeforeFailure, count("skit_platform_provider_command_audit"));
    assertEquals(
        "DRAFT",
        jdbc()
            .queryForObject(
                "SELECT state FROM skit_ad_provider_callback_route WHERE id=?",
                String.class,
                routeId));
    assertNull(
        jdbc()
            .queryForObject(
                "SELECT callback_route_registry_id FROM skit_ad_provider_callback_route WHERE id=?",
                Long.class,
                routeId));
    assertEquals(
        0,
        jdbc()
            .queryForObject(
                "SELECT COUNT(*) FROM skit_ad_callback_route_registry "
                    + "WHERE provider_callback_route_id=?",
                Integer.class,
                routeId));
    SkitProviderConnectionService.IssuedRoute rolledBack = lifecycle.getCapturedIssued();
    assertNotNull(rolledBack);
    assertThrows(IllegalStateException.class, rolledBack::consumeCallbackUrl);
    Field url = rolledBack.getClass().getDeclaredField("url");
    url.setAccessible(true);
    assertNull(url.get(rolledBack), "rollback synchronization must destroy plaintext URL");

    traceId.set("task6_commit_draft");
    char[] committedDraftPassword = "current-password".toCharArray();
    SkitPlatformProviderCommandExecutor.ResourceView committedDraft =
        executor.createDraftRoute(
            connection.getConnectionId(),
            SkitProviderConnectionService.RoutePurpose.GATE_TEST,
            committedDraftPassword,
            "create route for real outer commit proof");
    assertCleared(committedDraftPassword);
    long committedRouteId = committedDraft.getRouteId();
    traceId.set("task6_commit_issue");
    char[] committedIssuePassword = "current-password".toCharArray();

    SkitProviderConnectionService.IssuedRoute committed =
        executor.issueOnce(
            committedRouteId,
            committedIssuePassword,
            "issue route after audit commit verification");

    assertCleared(committedIssuePassword);
    char[] committedUrl = committed.consumeCallbackUrl();
    try {
      assertTrue(contains(committedUrl, "/skit/ad-callback/taku/acct_".toCharArray()));
    } finally {
      Arrays.fill(committedUrl, '\0');
    }
    assertThrows(IllegalStateException.class, committed::consumeCallbackUrl);
    assertEquals(
        "ISSUED",
        jdbc()
            .queryForObject(
                "SELECT state FROM skit_ad_provider_callback_route WHERE id=?",
                String.class,
                committedRouteId));
    assertEquals(
        1,
        jdbc()
            .queryForObject(
                "SELECT COUNT(*) FROM skit_ad_callback_route_registry "
                    + "WHERE provider_callback_route_id=? AND tombstoned_at IS NULL",
                Integer.class,
                committedRouteId));
    assertEquals(
        1,
        jdbc()
            .queryForObject(
                "SELECT COUNT(*) FROM skit_platform_provider_command_audit "
                    + "WHERE trace_id='task6_commit_issue' AND provider_callback_route_id=?",
                Integer.class,
                committedRouteId));

    long immutableAuditId =
        jdbc()
            .queryForObject(
                "SELECT id FROM skit_platform_provider_command_audit "
                    + "WHERE trace_id='task6_create_success'",
                Long.class);
    assertThrows(
        DataAccessException.class,
        () ->
            jdbc()
                .update(
                    "UPDATE skit_platform_provider_command_audit SET result_code='CHANGED' WHERE"
                        + " id=?",
                    immutableAuditId));
    assertThrows(
        DataAccessException.class,
        () ->
            jdbc()
                .update(
                    "DELETE FROM skit_platform_provider_command_audit WHERE id=?",
                    immutableAuditId));
    assertEquals(
        "OK",
        jdbc()
            .queryForObject(
                "SELECT result_code FROM skit_platform_provider_command_audit WHERE id=?",
                String.class,
                immutableAuditId));
  }

  private int count(String table) {
    return jdbc().queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
  }

  private void insertAuditWithTrace(long connectionId, long routeId, String trace) {
    byte[] hash = new byte[32];
    Arrays.fill(hash, (byte) 7);
    jdbc()
        .update(
            "INSERT INTO skit_platform_provider_command_audit "
                + "(actor_user_id,original_login_tenant_id,action,provider_connection_id,"
                + "provider_callback_route_id,reason,reauthenticated_at,request_fingerprint,"
                + "before_state_hash,after_state_hash,trace_id,result_status,result_code,"
                + "occurred_at) VALUES (7,1,'ISSUE_ROUTE_ONCE',?,?,?,NOW(),?,?,?,?,'SUCCEEDED',"
                + "'OK',NOW())",
            connectionId,
            routeId,
            "reserve duplicate trace for rollback proof",
            hash,
            hash,
            hash,
            trace);
    Arrays.fill(hash, (byte) 0);
  }

  private static void assertCleared(char[] value) {
    assertArrayEquals(new char[value.length], value);
  }

  private static boolean contains(char[] value, char[] expected) {
    try {
      for (int start = 0; start <= value.length - expected.length; start++) {
        boolean match = true;
        for (int offset = 0; offset < expected.length; offset++) {
          if (value[start + offset] != expected[offset]) {
            match = false;
            break;
          }
        }
        if (match) {
          return true;
        }
      }
      return false;
    } finally {
      Arrays.fill(expected, '\0');
    }
  }

  @Configuration(proxyBeanMethods = false)
  @EnableTransactionManagement(proxyTargetClass = true)
  static class CommandConfiguration {

    @Bean("commandTraceId")
    AtomicReference<String> commandTraceId() {
      return new AtomicReference<>("task6_unset_trace");
    }

    @Bean("passwordMatches")
    AtomicInteger passwordMatches() {
      return new AtomicInteger();
    }

    @Bean
    SkitCurrentAdminPasswordReauthService reauthService(
        @Qualifier("passwordMatches") AtomicInteger matches) {
      return password -> {
        try {
          if (password == null || !"current-password".contentEquals(CharBuffer.wrap(password))) {
            throw new IllegalArgumentException("test password mismatch");
          }
          matches.incrementAndGet();
        } finally {
          if (password != null) {
            Arrays.fill(password, '\0');
          }
        }
      };
    }

    @Bean
    CapturingProviderConnectionService capturingProviderConnectionService(
        @Qualifier("skitProviderConnectionServiceImpl") SkitProviderConnectionService delegate) {
      return new CapturingProviderConnectionService(delegate);
    }

    @Bean
    SkitPlatformProviderCommandExecutor skitPlatformProviderCommandExecutor(
        CapturingProviderConnectionService lifecycle,
        SkitCurrentAdminPasswordReauthService reauthService,
        SkitAdProviderConnectionMapper connectionMapper,
        SkitAdProviderCallbackRouteMapper routeMapper,
        SkitPlatformProviderCommandAuditMapper auditMapper,
        SkitProviderConnectionReadMapper readMapper,
        @Qualifier("commandTraceId") AtomicReference<String> traceId) {
      Clock fixed = Clock.fixed(Instant.parse("2026-08-03T04:05:06Z"), ZoneOffset.UTC);
      return new SkitPlatformProviderCommandExecutor(
          lifecycle,
          reauthService,
          connectionMapper,
          routeMapper,
          auditMapper,
          readMapper,
          fixed,
          traceId::get);
    }
  }

  static final class CapturingProviderConnectionService implements SkitProviderConnectionService {

    private final SkitProviderConnectionService delegate;
    private volatile IssuedRoute capturedIssued;

    CapturingProviderConnectionService(SkitProviderConnectionService delegate) {
      this.delegate = delegate;
    }

    @Override
    public ConnectionView createSharedMaster(CreateSharedMasterCommand command) {
      return delegate.createSharedMaster(command);
    }

    @Override
    public RouteView createDraftRoute(
        long providerConnectionId, RoutePurpose purpose, String reason, long actorUserId) {
      return delegate.createDraftRoute(providerConnectionId, purpose, reason, actorUserId);
    }

    @Override
    public IssuedRoute issueOnce(IssueRouteCommand command) {
      IssuedRoute issued = delegate.issueOnce(command);
      capturedIssued = issued;
      return issued;
    }

    @Override
    public RouteView abandonNeverShared(AbandonRouteCommand command) {
      return delegate.abandonNeverShared(command);
    }

    @Override
    public RouteView markSubmitted(MarkSubmittedCommand command) {
      return delegate.markSubmitted(command);
    }

    @Override
    public ConnectionView block(BlockConnectionCommand command) {
      return delegate.block(command);
    }

    @Override
    public ProviderRouteResolution resolveProviderImpression(
        char[] callbackKey, LocalDateTime receivedAt) {
      return delegate.resolveProviderImpression(callbackKey, receivedAt);
    }

    @Override
    public ProviderRouteResolution resolveProviderImpression(
        SkitCallbackRouteRegistryService.RouteLookup resolvedRoute, LocalDateTime receivedAt) {
      return delegate.resolveProviderImpression(resolvedRoute, receivedAt);
    }

    void resetCapturedIssued() {
      capturedIssued = null;
    }

    IssuedRoute getCapturedIssued() {
      return capturedIssued;
    }
  }
}
