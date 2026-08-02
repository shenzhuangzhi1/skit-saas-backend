package cn.iocoder.yudao.module.skit.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.iocoder.yudao.framework.tenant.config.TenantProperties;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.framework.tenant.core.db.TenantDatabaseInterceptor;
import cn.iocoder.yudao.module.skit.dal.dataobject.ad.SkitAdCallbackKeyDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.provider.SkitAdCallbackRouteRegistryDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.provider.SkitAdCallbackRouteRegistryMigrationDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.provider.SkitAdProviderCallbackRouteDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.provider.SkitAdProviderConnectionDO;
import cn.iocoder.yudao.module.skit.dal.mysql.ad.SkitAdCallbackKeyMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.provider.SkitAdCallbackRouteRegistryMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.provider.SkitAdCallbackRouteRegistryMigrationMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.provider.SkitAdProviderCallbackRouteMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.provider.SkitAdProviderConnectionMapper;
import cn.iocoder.yudao.module.skit.service.ad.SkitCallbackPublicUrlService;
import cn.iocoder.yudao.module.skit.service.ad.callback.SkitCallbackRouteRegistryService;
import cn.iocoder.yudao.module.skit.service.provider.DefaultSkitProviderImpressionProductionGate;
import cn.iocoder.yudao.module.skit.service.provider.SkitProviderConnectionService;
import cn.iocoder.yudao.module.skit.service.provider.SkitProviderConnectionServiceImpl;
import cn.iocoder.yudao.module.skit.service.provider.SkitProviderImpressionProductionGate;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/** Real Spring/MyBatis/MySQL lifecycle proof; no service boundary is mocked. */
@TestMethodOrder(OrderAnnotation.class)
class SkitProviderConnectionLifecycleSpringMySqlIT extends SkitMySqlIntegrationTestBase {

  private AnnotationConfigApplicationContext context;
  private SkitProviderConnectionService service;
  private SkitProviderConnectionService allowingService;
  private SkitProviderConnectionService collidingKeyService;
  private SkitProviderConnectionService submitRejectingGateService;
  private SkitCallbackRouteRegistryService registryService;
  private long sharedMasterId;

  @BeforeAll
  void startContext() {
    context = new AnnotationConfigApplicationContext();
    context.registerBean("dataSource", DataSource.class, this::dataSource);
    context.register(ProviderLifecycleConfiguration.class);
    context.refresh();
    service =
        context.getBean("skitProviderConnectionServiceImpl", SkitProviderConnectionService.class);
    assertEquals(SkitProviderConnectionServiceImpl.class, AopUtils.getTargetClass(service));
    allowingService =
        context.getBean("allowingProviderConnectionService", SkitProviderConnectionService.class);
    collidingKeyService =
        context.getBean(
            "collidingKeyProviderConnectionService", SkitProviderConnectionService.class);
    submitRejectingGateService =
        context.getBean(
            "submitRejectingGateProviderConnectionService", SkitProviderConnectionService.class);
    registryService = context.getBean(SkitCallbackRouteRegistryService.class);
  }

  @AfterEach
  void clearTenant() {
    TenantContextHolder.clear();
  }

  @AfterAll
  void stopContext() {
    if (context != null) {
      context.close();
    }
  }

  @Test
  @Order(1)
  void concurrentSharedMasterCreationUsesTheRealUniqueConstraint() throws Exception {
    assertTrue(AopUtils.isAopProxy(service));
    assertTrue(
        Proxy.isProxyClass(context.getBean(SkitAdProviderConnectionMapper.class).getClass()));
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      List<Future<Boolean>> results =
          Arrays.asList(
              pool.submit(createSharedMaster(ready, start, "real-spring-account-a")),
              pool.submit(createSharedMaster(ready, start, "real-spring-account-b")));
      ready.await();
      start.countDown();
      int successes = 0;
      for (Future<Boolean> result : results) {
        if (result.get()) {
          successes++;
        }
      }
      assertEquals(1, successes);
    } finally {
      pool.shutdownNow();
    }
    assertEquals(
        1,
        jdbc()
            .queryForObject(
                "SELECT COUNT(*) FROM skit_ad_provider_connection "
                    + "WHERE provider='TAKU' AND account_mode='SHARED_MASTER'",
                Integer.class));
    sharedMasterId =
        jdbc()
            .queryForObject(
                "SELECT id FROM skit_ad_provider_connection "
                    + "WHERE provider='TAKU' AND account_mode='SHARED_MASTER'",
                Long.class);
  }

  @Test
  @Order(2)
  void concurrentIssueRegistersOneOwnerThenExposesOneConsumableUrl() throws Exception {
    long routeId = draft(SkitProviderConnectionService.RoutePurpose.GATE_TEST);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    char[] callbackKey = null;
    try {
      List<Future<SkitProviderConnectionService.IssuedRoute>> results =
          pool.invokeAll(Arrays.asList(() -> issue(routeId), () -> issue(routeId)));
      int successes = 0;
      SkitProviderConnectionService.IssuedRoute issued = null;
      for (Future<SkitProviderConnectionService.IssuedRoute> result : results) {
        try {
          issued = result.get();
          successes++;
        } catch (Exception expectedLoser) {
          // The loser observes the committed ISSUED state after the connection->route lock.
        }
      }
      assertEquals(1, successes);
      assertNotNull(issued);
      char[] url = issued.consumeCallbackUrl();
      callbackKey = callbackKey(url);
      try {
        assertTrue(new String(url).contains("/skit/ad-callback/taku/acct_"));
        SkitProviderConnectionService.ProviderRouteResolution issuedResolution =
            service.resolveProviderImpression(callbackKey, LocalDateTime.now());
        assertTrue(issuedResolution.isAccepting());
        assertEquals(sharedMasterId, issuedResolution.getProviderConnectionId());
        assertEquals(routeId, issuedResolution.getProviderRouteId());
        jdbc()
            .update(
                "UPDATE skit_ad_provider_callback_route SET accept_until=? WHERE id=?",
                LocalDateTime.now().minusSeconds(1),
                routeId);
        assertFalse(
            service.resolveProviderImpression(callbackKey, LocalDateTime.now()).isAccepting());
      } finally {
        Arrays.fill(url, '\0');
      }
      assertThrows(IllegalStateException.class, issued::consumeCallbackUrl);
    } finally {
      pool.shutdownNow();
    }
    assertEquals(
        "ISSUED",
        jdbc()
            .queryForObject(
                "SELECT state FROM skit_ad_provider_callback_route WHERE id=?",
                String.class,
                routeId));
    assertEquals(
        1,
        jdbc()
            .queryForObject(
                "SELECT COUNT(*) FROM skit_ad_callback_route_registry "
                    + "WHERE provider_callback_route_id=? AND tombstoned_at IS NULL",
                Integer.class,
                routeId));
    long competingRouteId = draft(SkitProviderConnectionService.RoutePurpose.GATE_TEST);
    assertThrows(DuplicateKeyException.class, () -> issue(competingRouteId));
    assertEquals(
        "DRAFT",
        jdbc()
            .queryForObject(
                "SELECT state FROM skit_ad_provider_callback_route WHERE id=?",
                String.class,
                competingRouteId));
    assertEquals(
        0,
        jdbc()
            .queryForObject(
                "SELECT COUNT(*) FROM skit_ad_callback_route_registry "
                    + "WHERE provider_callback_route_id=?",
                Integer.class,
                competingRouteId));
    service.abandonNeverShared(
        new SkitProviderConnectionService.AbandonRouteCommand(
            routeId, 7L, "test route was never shared"));
    try {
      assertFalse(
          service.resolveProviderImpression(callbackKey, LocalDateTime.now()).isAccepting());
    } finally {
      Arrays.fill(callbackKey, '\0');
    }
  }

  @Test
  @Order(3)
  void globalLifecycleIgnoresCallerTenantAndGateTestCannotSubmit() {
    TenantContextHolder.setTenantId(773L);
    TenantContextHolder.setIgnore(false);
    long routeId = draft(SkitProviderConnectionService.RoutePurpose.GATE_TEST);
    assertEquals(773L, TenantContextHolder.getTenantId());
    assertFalse(TenantContextHolder.isIgnore());
    SkitProviderConnectionService.IssuedRoute issued = issue(routeId);
    assertEquals(773L, TenantContextHolder.getTenantId());
    assertFalse(TenantContextHolder.isIgnore());
    char[] url = issued.consumeCallbackUrl();
    Arrays.fill(url, '\0');
    assertThrows(
        IllegalArgumentException.class,
        () ->
            service.markSubmitted(
                new SkitProviderConnectionService.MarkSubmittedCommand(
                    routeId, 7L, "ticket", "reference", "recipient")));
    assertEquals(773L, TenantContextHolder.getTenantId());
    assertFalse(TenantContextHolder.isIgnore());
    TenantContextHolder.setIgnore(true);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            service.markSubmitted(
                new SkitProviderConnectionService.MarkSubmittedCommand(
                    routeId, 7L, "ticket", "reference", "recipient")));
    assertEquals(773L, TenantContextHolder.getTenantId());
    assertTrue(TenantContextHolder.isIgnore());
    assertEquals(
        "ISSUED",
        jdbc()
            .queryForObject(
                "SELECT state FROM skit_ad_provider_callback_route WHERE id=?",
                String.class,
                routeId));
    service.abandonNeverShared(
        new SkitProviderConnectionService.AbandonRouteCommand(
            routeId, 7L, "test route was never shared"));
  }

  @Test
  @Order(4)
  void tenantProviderKeyHashCollisionRollsBackTheRealIssueTransaction() {
    long routeId = draft(SkitProviderConnectionService.RoutePurpose.GATE_TEST);
    jdbc()
        .update(
            "INSERT INTO skit_ad_account"
                + " (id,tenant_id,provider,account_name,account_id,app_id,app_key,status) VALUES"
                + " (8812,8811,'TAKU','collision','collision','collision','',1)");
    jdbc()
        .update(
            "INSERT INTO skit_ad_callback_key"
                + " (tenant_id,ad_account_id,key_version,callback_key_hash) VALUES"
                + " (8811,8812,1,UNHEX(SHA2('acct_AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGx',256)))");
    Long tenantKeyId =
        jdbc()
            .queryForObject(
                "SELECT id FROM skit_ad_callback_key WHERE tenant_id=8811 AND ad_account_id=8812",
                Long.class);
    jdbc()
        .update(
            "INSERT INTO skit_ad_callback_route_registry"
                + " (key_hash,route_type,tenant_callback_key_id,registered_at) VALUES"
                + " (UNHEX(SHA2('acct_AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGx',256)),'TENANT_CALLBACK_KEY',?,NOW())",
            tenantKeyId);
    int registryBefore =
        jdbc()
            .queryForObject("SELECT COUNT(*) FROM skit_ad_callback_route_registry", Integer.class);
    assertThrows(
        IllegalStateException.class,
        () ->
            collidingKeyService.issueOnce(
                new SkitProviderConnectionService.IssueRouteCommand(routeId, 7L)));
    assertEquals(
        "DRAFT",
        jdbc()
            .queryForObject(
                "SELECT state FROM skit_ad_provider_callback_route WHERE id=?",
                String.class,
                routeId));
    assertEquals(
        registryBefore,
        jdbc()
            .queryForObject("SELECT COUNT(*) FROM skit_ad_callback_route_registry", Integer.class));
    assertEquals(
        "TENANT_CALLBACK_KEY",
        jdbc()
            .queryForObject(
                "SELECT route_type FROM skit_ad_callback_route_registry WHERE"
                    + " tenant_callback_key_id=?",
                String.class,
                tenantKeyId));
    char[] tenantKey = "acct_AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGx".toCharArray();
    byte[] tenantKeyHash = sha256(tenantKey);
    try {
      SkitCallbackRouteRegistryService.RouteLookup tenantLookup =
          registryService.lookup(tenantKeyHash, LocalDateTime.now());
      assertEquals(
          SkitCallbackRouteRegistryService.RouteType.TENANT_CALLBACK_KEY,
          tenantLookup.getRouteType());
      assertFalse(
          collidingKeyService
              .resolveProviderImpression(tenantLookup, LocalDateTime.now())
              .isAccepting());
      assertFalse(
          collidingKeyService
              .resolveProviderImpression(tenantKey, LocalDateTime.now())
              .isAccepting());
    } finally {
      Arrays.fill(tenantKey, '\0');
      Arrays.fill(tenantKeyHash, (byte) 0);
    }
    assertEquals(
        0,
        jdbc()
            .queryForObject(
                "SELECT COUNT(*) FROM skit_ad_callback_route_registry WHERE"
                    + " provider_callback_route_id=?",
                Integer.class,
                routeId));
    assertEquals(
        0,
        jdbc()
            .queryForObject(
                "SELECT COUNT(*) FROM skit_ad_provider_callback_route WHERE id=? AND"
                    + " callback_route_registry_id IS NOT NULL",
                Integer.class,
                routeId));
  }

  @Test
  @Order(5)
  void productionGateDeniesBeforeAnyKeyOrRegistryWrite() {
    long routeId = draft(SkitProviderConnectionService.RoutePurpose.PRODUCTION);
    int registryBefore =
        jdbc()
            .queryForObject("SELECT COUNT(*) FROM skit_ad_callback_route_registry", Integer.class);
    assertThrows(IllegalStateException.class, () -> issue(routeId));
    assertEquals(
        "DRAFT",
        jdbc()
            .queryForObject(
                "SELECT state FROM skit_ad_provider_callback_route WHERE id=?",
                String.class,
                routeId));
    assertEquals(
        registryBefore,
        jdbc()
            .queryForObject("SELECT COUNT(*) FROM skit_ad_callback_route_registry", Integer.class));
  }

  @Test
  @Order(9)
  void productionSubmitRechecksGateAndLeavesIssuedRouteUntouchedOnDenial() {
    long connectionId = tenantConnection("submit-gate", 9031L);
    long routeId =
        service
            .createDraftRoute(
                connectionId,
                SkitProviderConnectionService.RoutePurpose.PRODUCTION,
                "submit gate",
                7L)
            .getId();
    clear(
        submitRejectingGateService.issueOnce(
            new SkitProviderConnectionService.IssueRouteCommand(routeId, 7L)));
    assertThrows(
        IllegalStateException.class,
        () ->
            submitRejectingGateService.markSubmitted(
                new SkitProviderConnectionService.MarkSubmittedCommand(
                    routeId, 7L, "ticket", "reference", "recipient")));
    assertEquals(
        "ISSUED",
        jdbc()
            .queryForObject(
                "SELECT state FROM skit_ad_provider_callback_route WHERE id=?",
                String.class,
                routeId));
    assertNull(
        jdbc()
            .queryForObject(
                "SELECT submission_ticket FROM skit_ad_provider_callback_route WHERE id=?",
                String.class,
                routeId));
    assertEquals(
        1,
        jdbc()
            .queryForObject(
                "SELECT COUNT(*) FROM skit_ad_callback_route_registry WHERE"
                    + " provider_callback_route_id=? AND tombstoned_at IS NULL",
                Integer.class,
                routeId));
    service.block(
        new SkitProviderConnectionService.BlockConnectionCommand(connectionId, 7L, "test cleanup"));
  }

  @Test
  @Order(10)
  void explicitTestGateCanSubmitProductionRouteWithSeparateAuditFields() {
    long routeId = draft(SkitProviderConnectionService.RoutePurpose.PRODUCTION);
    SkitProviderConnectionService.IssuedRoute issued =
        allowingService.issueOnce(new SkitProviderConnectionService.IssueRouteCommand(routeId, 7L));
    char[] callbackKey = callbackKey(issued.consumeCallbackUrl());
    allowingService.markSubmitted(
        new SkitProviderConnectionService.MarkSubmittedCommand(
            routeId, 7L, "provider-ticket", "provider-reference", "recipient@example.test"));
    assertEquals(
        "SUBMITTED",
        jdbc()
            .queryForObject(
                "SELECT state FROM skit_ad_provider_callback_route WHERE id=?",
                String.class,
                routeId));
    assertEquals(
        "provider-ticket",
        jdbc()
            .queryForObject(
                "SELECT submission_ticket FROM skit_ad_provider_callback_route WHERE id=?",
                String.class,
                routeId));
    assertEquals(
        "provider-reference",
        jdbc()
            .queryForObject(
                "SELECT submission_reference FROM skit_ad_provider_callback_route WHERE id=?",
                String.class,
                routeId));
    assertEquals(
        "recipient@example.test",
        jdbc()
            .queryForObject(
                "SELECT submission_recipient FROM skit_ad_provider_callback_route WHERE id=?",
                String.class,
                routeId));
    assertTrue(
        allowingService.resolveProviderImpression(callbackKey, LocalDateTime.now()).isAccepting());
    service.block(
        new SkitProviderConnectionService.BlockConnectionCommand(
            sharedMasterId, 7L, "release test cleanup"));
    assertEquals(
        "BLOCKED",
        jdbc()
            .queryForObject(
                "SELECT state FROM skit_ad_provider_connection WHERE id=?",
                String.class,
                sharedMasterId));
    assertEquals(
        "BLOCKED",
        jdbc()
            .queryForObject(
                "SELECT state FROM skit_ad_provider_callback_route WHERE id=?",
                String.class,
                routeId));
    assertNotNull(
        jdbc()
            .queryForObject(
                "SELECT tombstoned_at FROM skit_ad_callback_route_registry WHERE"
                    + " provider_callback_route_id=?",
                LocalDateTime.class,
                routeId));
    try {
      assertFalse(
          allowingService
              .resolveProviderImpression(callbackKey, LocalDateTime.now())
              .isAccepting());
    } finally {
      Arrays.fill(callbackKey, '\0');
    }
  }

  @Test
  @Order(7)
  void blockVersusIssueLeavesNoAcceptingRouteOrActiveRegistryEntry() throws Exception {
    long connectionId = tenantConnection("race-issue", 9011L);
    long routeId =
        service
            .createDraftRoute(
                connectionId, SkitProviderConnectionService.RoutePurpose.GATE_TEST, "race", 7L)
            .getId();
    race(
        () ->
            service.block(
                new SkitProviderConnectionService.BlockConnectionCommand(
                    connectionId, 7L, "race block")),
        () -> {
          try {
            clear(issue(routeId));
          } catch (RuntimeException ignored) {
            // The block winner makes issue reject before route mutation.
          }
        });
    assertEquals(
        "BLOCKED",
        jdbc()
            .queryForObject(
                "SELECT state FROM skit_ad_provider_connection WHERE id=?",
                String.class,
                connectionId));
    assertEquals(
        0,
        jdbc()
            .queryForObject(
                "SELECT COUNT(*) FROM skit_ad_provider_callback_route WHERE id=? AND"
                    + " state='ISSUED'",
                Integer.class,
                routeId));
    assertEquals(
        0,
        jdbc()
            .queryForObject(
                "SELECT COUNT(*) FROM skit_ad_callback_route_registry r JOIN"
                    + " skit_ad_provider_callback_route p ON p.id=r.provider_callback_route_id"
                    + " WHERE p.id=? AND r.tombstoned_at IS NULL",
                Integer.class,
                routeId));
  }

  @Test
  @Order(8)
  void blockVersusSubmitLeavesBlockedRouteAndTombstonedRegistry() throws Exception {
    long connectionId = tenantConnection("race-submit", 9021L);
    long routeId =
        service
            .createDraftRoute(
                connectionId, SkitProviderConnectionService.RoutePurpose.PRODUCTION, "race", 7L)
            .getId();
    clear(
        allowingService.issueOnce(
            new SkitProviderConnectionService.IssueRouteCommand(routeId, 7L)));
    race(
        () ->
            service.block(
                new SkitProviderConnectionService.BlockConnectionCommand(
                    connectionId, 7L, "race block")),
        () -> {
          try {
            allowingService.markSubmitted(
                new SkitProviderConnectionService.MarkSubmittedCommand(
                    routeId, 7L, "race-ticket", "race-reference", "race-recipient"));
          } catch (RuntimeException ignored) {
            // The block winner makes submission reject after its connection lock.
          }
        });
    assertEquals(
        "BLOCKED",
        jdbc()
            .queryForObject(
                "SELECT state FROM skit_ad_provider_connection WHERE id=?",
                String.class,
                connectionId));
    assertEquals(
        "BLOCKED",
        jdbc()
            .queryForObject(
                "SELECT state FROM skit_ad_provider_callback_route WHERE id=?",
                String.class,
                routeId));
    assertNotNull(
        jdbc()
            .queryForObject(
                "SELECT tombstoned_at FROM skit_ad_callback_route_registry WHERE"
                    + " provider_callback_route_id=?",
                LocalDateTime.class,
                routeId));
  }

  @Test
  @Order(6)
  void abandonAndBlockTombstoneRegisteredProviderRoutesAtomically() {
    long abandoned = draft(SkitProviderConnectionService.RoutePurpose.GATE_TEST);
    clear(issue(abandoned));
    service.abandonNeverShared(
        new SkitProviderConnectionService.AbandonRouteCommand(
            abandoned, 7L, "not shared with provider"));
    assertEquals(
        "ABANDONED",
        jdbc()
            .queryForObject(
                "SELECT state FROM skit_ad_provider_callback_route WHERE id=?",
                String.class,
                abandoned));
    assertNotNull(
        jdbc()
            .queryForObject(
                "SELECT tombstoned_at FROM skit_ad_callback_route_registry "
                    + "WHERE provider_callback_route_id=?",
                LocalDateTime.class,
                abandoned));

    assertEquals(
        "CONFIGURING",
        jdbc()
            .queryForObject(
                "SELECT state FROM skit_ad_provider_connection WHERE id=?",
                String.class,
                sharedMasterId));
    assertNull(
        jdbc()
            .queryForObject(
                "SELECT active_callback_route_id FROM skit_ad_provider_connection WHERE id=?",
                Long.class,
                sharedMasterId));
  }

  private Callable<Boolean> createSharedMaster(
      CountDownLatch ready, CountDownLatch start, String accountReference) {
    return () -> {
      char[] reference = accountReference.toCharArray();
      try {
        ready.countDown();
        start.await();
        service.createSharedMaster(
            new SkitProviderConnectionService.CreateSharedMasterCommand(reference, 7L));
        return true;
      } catch (RuntimeException expectedCollision) {
        return false;
      } finally {
        Arrays.fill(reference, '\0');
      }
    };
  }

  private long draft(SkitProviderConnectionService.RoutePurpose purpose) {
    return service
        .createDraftRoute(sharedMasterId, purpose, "real service lifecycle test", 7L)
        .getId();
  }

  private long tenantConnection(String code, long tenantId) {
    jdbc()
        .update(
            "INSERT INTO skit_ad_provider_connection"
                + " (connection_code,provider,account_mode,owner_tenant_id,external_account_ref_hash,state,created_by_user_id,created_at,updated_by_user_id,updated_at)"
                + " VALUES"
                + " (?,'TAKU','TENANT_OWNED',?,UNHEX(SHA2(?,256)),'CONFIGURING',7,NOW(),7,NOW())",
            code,
            tenantId,
            code);
    return jdbc()
        .queryForObject(
            "SELECT id FROM skit_ad_provider_connection WHERE connection_code=?", Long.class, code);
  }

  private static void race(Runnable first, Runnable second) throws Exception {
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<?> one =
          pool.submit(
              () -> {
                ready.countDown();
                await(start);
                first.run();
              });
      Future<?> two =
          pool.submit(
              () -> {
                ready.countDown();
                await(start);
                second.run();
              });
      ready.await();
      start.countDown();
      one.get();
      two.get();
    } finally {
      pool.shutdownNow();
    }
  }

  private static void await(CountDownLatch latch) {
    try {
      latch.await();
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(interrupted);
    }
  }

  private SkitProviderConnectionService.IssuedRoute issue(long routeId) {
    return service.issueOnce(new SkitProviderConnectionService.IssueRouteCommand(routeId, 7L));
  }

  private static void clear(SkitProviderConnectionService.IssuedRoute issued) {
    char[] url = issued.consumeCallbackUrl();
    Arrays.fill(url, '\0');
  }

  private static char[] callbackKey(char[] callbackUrl) {
    String url = new String(callbackUrl);
    int start = url.indexOf("acct_");
    if (start < 0 || start + 43 > url.length()) {
      throw new AssertionError("provider callback URL does not contain a key");
    }
    return url.substring(start, start + 43).toCharArray();
  }

  private static byte[] sha256(char[] key) {
    try {
      return MessageDigest.getInstance("SHA-256")
          .digest(new String(key).getBytes(StandardCharsets.US_ASCII));
    } catch (NoSuchAlgorithmException impossible) {
      throw new AssertionError(impossible);
    }
  }

  @Configuration(proxyBeanMethods = false)
  @EnableTransactionManagement(proxyTargetClass = true)
  @ComponentScan(
      basePackageClasses = SkitProviderConnectionServiceImpl.class,
      includeFilters =
          @ComponentScan.Filter(
              type = FilterType.ASSIGNABLE_TYPE,
              classes = SkitProviderConnectionServiceImpl.class),
      useDefaultFilters = false)
  @MapperScan(
      basePackages = {
        "cn.iocoder.yudao.module.skit.dal.mysql.provider",
        "cn.iocoder.yudao.module.skit.dal.mysql.ad"
      },
      annotationClass = Mapper.class)
  static class ProviderLifecycleConfiguration {

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
      MapperBuilderAssistant assistant =
          new MapperBuilderAssistant(
              configuration, "skit-provider-connection-lifecycle-spring-mysql-it");
      TableInfoHelper.initTableInfo(assistant, SkitAdProviderConnectionDO.class);
      TableInfoHelper.initTableInfo(assistant, SkitAdProviderCallbackRouteDO.class);
      TableInfoHelper.initTableInfo(assistant, SkitAdCallbackRouteRegistryDO.class);
      TableInfoHelper.initTableInfo(assistant, SkitAdCallbackRouteRegistryMigrationDO.class);
      TableInfoHelper.initTableInfo(assistant, SkitAdCallbackKeyDO.class);
      MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
      factory.setDataSource(dataSource);
      factory.setConfiguration(configuration);
      factory.setPlugins(interceptor);
      return factory;
    }

    @Bean
    PlatformTransactionManager transactionManager(DataSource dataSource) {
      return new DataSourceTransactionManager(dataSource);
    }

    @Bean
    SimpleMeterRegistry meterRegistry() {
      return new SimpleMeterRegistry();
    }

    @Bean
    SkitCallbackRouteRegistryService callbackRouteRegistryService(
        SkitAdCallbackRouteRegistryMapper registryMapper,
        SkitAdCallbackRouteRegistryMigrationMapper migrationMapper,
        SkitAdCallbackKeyMapper callbackKeyMapper,
        SimpleMeterRegistry meterRegistry,
        PlatformTransactionManager transactionManager) {
      return new SkitCallbackRouteRegistryService(
          registryMapper, migrationMapper, callbackKeyMapper, meterRegistry, transactionManager);
    }

    @Bean
    SkitCallbackPublicUrlService callbackPublicUrlService() {
      return new SkitCallbackPublicUrlService("https://ads.example.com/app-api");
    }

    @Bean
    SkitProviderImpressionProductionGate productionGate() {
      return new DefaultSkitProviderImpressionProductionGate();
    }

    @Bean("allowingProviderConnectionService")
    SkitProviderConnectionService allowingProviderConnectionService(
        SkitAdProviderConnectionMapper connectionMapper,
        SkitAdProviderCallbackRouteMapper routeMapper,
        SkitCallbackRouteRegistryService registryService,
        SkitCallbackPublicUrlService urlService) {
      return new SkitProviderConnectionServiceImpl(
          connectionMapper,
          routeMapper,
          registryService,
          urlService,
          (connectionId, routeId, actorId) -> {});
    }

    @Bean("collidingKeyProviderConnectionService")
    SkitProviderConnectionService collidingKeyProviderConnectionService(
        SkitAdProviderConnectionMapper connectionMapper,
        SkitAdProviderCallbackRouteMapper routeMapper,
        SkitCallbackRouteRegistryService registryService,
        SkitCallbackPublicUrlService urlService) {
      SecureRandom fixedEntropy =
          new SecureRandom() {
            @Override
            public void nextBytes(byte[] bytes) {
              for (int index = 0; index < bytes.length; index++) {
                bytes[index] = (byte) index;
              }
            }
          };
      return new SkitProviderConnectionServiceImpl(
          connectionMapper,
          routeMapper,
          registryService,
          urlService,
          (connectionId, routeId, actorId) -> {},
          fixedEntropy,
          Clock.systemUTC());
    }

    @Bean("submitRejectingGateProviderConnectionService")
    SkitProviderConnectionService submitRejectingGateProviderConnectionService(
        SkitAdProviderConnectionMapper connectionMapper,
        SkitAdProviderCallbackRouteMapper routeMapper,
        SkitCallbackRouteRegistryService registryService,
        SkitCallbackPublicUrlService urlService) {
      AtomicInteger calls = new AtomicInteger();
      SkitProviderImpressionProductionGate gate =
          (connectionId, routeId, actorId) -> {
            if (calls.incrementAndGet() > 1) {
              throw new IllegalStateException("submit gate rejected");
            }
          };
      return new SkitProviderConnectionServiceImpl(
          connectionMapper, routeMapper, registryService, urlService, gate);
    }
  }
}
