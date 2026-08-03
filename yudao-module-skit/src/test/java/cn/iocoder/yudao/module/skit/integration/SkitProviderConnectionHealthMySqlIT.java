package cn.iocoder.yudao.module.skit.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import cn.iocoder.yudao.framework.tenant.config.TenantProperties;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.framework.tenant.core.db.TenantDatabaseInterceptor;
import cn.iocoder.yudao.module.skit.dal.mysql.provider.SkitProviderConnectionHealthMapper;
import cn.iocoder.yudao.module.skit.service.provider.SkitProviderConnectionHealthService;
import cn.iocoder.yudao.module.skit.service.provider.SkitProviderConnectionHealthServiceImpl;
import cn.iocoder.yudao.module.skit.service.provider.SkitProviderConnectionHealthView;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.mapper.MapperFactoryBean;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class SkitProviderConnectionHealthMySqlIT extends SkitMySqlIntegrationTestBase {

  private static final LocalDateTime FIRST = LocalDateTime.of(2026, 8, 3, 8, 0, 0);
  private static final LocalDateTime LAST = LocalDateTime.of(2026, 8, 3, 8, 11, 0);

  private AnnotationConfigApplicationContext context;
  private SkitProviderConnectionHealthService service;
  private long populatedConnectionId;
  private long emptyConnectionId;

  @BeforeAll
  void startHealthProjection() {
    populatedConnectionId = insertConnection("provider-health-populated", (byte) 0x31);
    emptyConnectionId = insertConnection("provider-health-empty", (byte) 0x32);
    insertEvidence();
    context = new AnnotationConfigApplicationContext();
    context.registerBean("dataSource", DataSource.class, this::dataSource);
    context.register(HealthConfiguration.class);
    context.refresh();
    service = context.getBean(SkitProviderConnectionHealthService.class);
  }

  @AfterAll
  void closeHealthProjection() {
    TenantContextHolder.clear();
    if (context != null) {
      context.close();
    }
  }

  @Test
  void realMySqlReturnsOneSafeAggregateWithExactAttemptAndInboxCounts() {
    TenantContextHolder.setTenantId(773L);
    TenantContextHolder.setIgnore(false);

    SkitProviderConnectionHealthView health = service.getSafeHealth(populatedConnectionId);

    assertEquals(FIRST, health.getFirstReceivedAt());
    assertEquals(LAST, health.getLastReceivedAt());
    assertEquals(5L, health.getAcceptedAttempts());
    assertEquals(1L, health.getDuplicates());
    assertEquals(1L, health.getConflicts());
    assertEquals(2L, health.getFallback());
    assertEquals(2L, health.getQuarantined());
    assertNull(health.getDbFailures());
    assertNull(health.getDbFailureAt());
    assertEquals(773L, TenantContextHolder.getTenantId());
    assertFalse(TenantContextHolder.isIgnore());
  }

  @Test
  void realMySqlEmptyConnectionReturnsZerosWithoutMaterializingEvidenceRows() {
    SkitProviderConnectionHealthView health = service.getSafeHealth(emptyConnectionId);

    assertNull(health.getFirstReceivedAt());
    assertNull(health.getLastReceivedAt());
    assertEquals(0L, health.getAcceptedAttempts());
    assertEquals(0L, health.getDuplicates());
    assertEquals(0L, health.getConflicts());
    assertEquals(0L, health.getFallback());
    assertEquals(0L, health.getQuarantined());
    assertNull(health.getDbFailures());
    assertNull(health.getDbFailureAt());
  }

  private void insertEvidence() {
    long officialInbox =
        insertInbox(
            "OFFICIAL_V1",
            "sentinel-request-do-not-export",
            "42",
            "PAYLOAD_CONFLICT",
            1L,
            FIRST,
            FIRST.plusMinutes(5),
            (byte) 0x41);
    long fallbackInbox =
        insertInbox(
            "FALLBACK_WIRE_V1",
            null,
            null,
            "CANONICAL",
            0L,
            FIRST.plusMinutes(10),
            LAST,
            (byte) 0x42);

    insertAttempt(officialInbox, "OFFICIAL_V1", "CANONICAL", FIRST, 1, (byte) 0x41);
    insertAttempt(
        officialInbox,
        "OFFICIAL_V1",
        "EQUIVALENT_DUPLICATE",
        FIRST.plusMinutes(1),
        2,
        (byte) 0x41);
    insertAttempt(
        officialInbox,
        "OFFICIAL_V1",
        "PAYLOAD_CONFLICT",
        FIRST.plusMinutes(5),
        3,
        (byte) 0x43);
    insertAttempt(
        fallbackInbox,
        "FALLBACK_WIRE_V1",
        "FALLBACK_QUARANTINED",
        FIRST.plusMinutes(10),
        4,
        (byte) 0x42);
    insertAttempt(
        fallbackInbox,
        "FALLBACK_WIRE_V1",
        "FALLBACK_QUARANTINED",
        LAST,
        5,
        (byte) 0x42);
  }

  private long insertConnection(String code, byte hashByte) {
    byte[] hash = repeated(32, hashByte);
    jdbc()
        .update(
            "INSERT INTO skit_ad_provider_connection (connection_code,provider,account_mode,"
                + "owner_tenant_id,external_account_ref_hash,state,created_by_user_id,created_at,"
                + "updated_by_user_id,updated_at) VALUES (?,'TAKU','TENANT_OWNED',?,?,"
                + "'CONFIGURING',7,?,7,?)",
            code,
            900L + (hashByte & 0xff),
            hash,
            FIRST.minusMinutes(1),
            FIRST.minusMinutes(1));
    return jdbc()
        .queryForObject(
            "SELECT id FROM skit_ad_provider_connection WHERE connection_code=?",
            Long.class,
            code);
  }

  private long insertInbox(
      String scheme,
      String requestId,
      String adsourceId,
      String integrityStatus,
      long integrityRevision,
      LocalDateTime firstReceivedAt,
      LocalDateTime lastReceivedAt,
      byte materialByte) {
    byte[] dedupe = repeated(32, (byte) (materialByte + 16));
    byte[] material = "OFFICIAL_V1".equals(scheme) ? repeated(32, materialByte) : null;
    LocalDateTime conflictAt = "PAYLOAD_CONFLICT".equals(integrityStatus) ? lastReceivedAt : null;
    jdbc()
        .update(
            "INSERT INTO skit_provider_impression_inbox (provider_connection_id,dedupe_scheme,"
                + "dedupe_key_hash,provider_request_id_lexical,adsource_id_lexical,"
                + "material_integrity_hash,authentication_level,integrity_status,"
                + "integrity_revision,integrity_conflict_at,processing_status,quarantine_reason,"
                + "processing_attempt_count,first_received_at,last_received_at) VALUES "
                + "(?,?,?,?,?,?,'UNSIGNED_PROVIDER_OBSERVATION',?,?,?,'QUARANTINED',?,0,?,?)",
            populatedConnectionId,
            scheme,
            dedupe,
            requestId,
            adsourceId,
            material,
            integrityStatus,
            integrityRevision,
            conflictAt,
            "sentinel-quarantine-reason",
            firstReceivedAt,
            lastReceivedAt);
    return jdbc()
        .queryForObject(
            "SELECT id FROM skit_provider_impression_inbox WHERE provider_connection_id=? "
                + "AND dedupe_key_hash=?",
            Long.class,
            populatedConnectionId,
            dedupe);
  }

  private void insertAttempt(
      long inboxId,
      String scheme,
      String deliveryStatus,
      LocalDateTime receivedAt,
      int seed,
      byte materialByte) {
    byte[] correlation = repeated(16, (byte) 0);
    correlation[15] = (byte) seed;
    byte[] wireHash = repeated(32, (byte) (seed + 80));
    byte[] material = "OFFICIAL_V1".equals(scheme) ? repeated(32, materialByte) : null;
    byte[] sentinelCiphertext =
        "sentinel-query-package-placement-device-do-not-export"
            .getBytes(StandardCharsets.US_ASCII);
    jdbc()
        .update(
            "INSERT INTO skit_provider_callback_attempt (correlation_id,provider_connection_id,"
                + "inbox_id,dedupe_scheme,wire_payload_hash,material_integrity_hash,"
                + "delivery_integrity_status,response_decision,payload_ciphertext,payload_nonce,"
                + "payload_key_id,payload_purpose,payload_envelope_version,payload_expires_at,"
                + "wire_size_bytes,parameter_count,remote_address_hash,user_agent_hash,"
                + "request_header_fingerprint,trace_id,received_at) VALUES "
                + "(?,?,?,?,?,? ,?,'ACK_200',?,?,?,?,1,?,128,9,?,NULL,?,?,?)",
            correlation,
            populatedConnectionId,
            inboxId,
            scheme,
            wireHash,
            material,
            deliveryStatus,
            sentinelCiphertext,
            repeated(12, (byte) (seed + 10)),
            "sentinel-key-id",
            "PROVIDER_CALLBACK_PAYLOAD",
            receivedAt.plusDays(7),
            repeated(32, (byte) (seed + 20)),
            repeated(32, (byte) (seed + 30)),
            String.format("pci-%032x", seed),
            receivedAt);
  }

  private static byte[] repeated(int length, byte value) {
    byte[] result = new byte[length];
    java.util.Arrays.fill(result, value);
    return result;
  }

  @Configuration(proxyBeanMethods = false)
  static class HealthConfiguration {

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
      MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
      factory.setDataSource(dataSource);
      factory.setConfiguration(configuration);
      factory.setPlugins(interceptor);
      return factory;
    }

    @Bean
    MapperFactoryBean<SkitProviderConnectionHealthMapper> providerConnectionHealthMapper(
        SqlSessionFactory sqlSessionFactory) {
      MapperFactoryBean<SkitProviderConnectionHealthMapper> factory =
          new MapperFactoryBean<>(SkitProviderConnectionHealthMapper.class);
      factory.setSqlSessionFactory(sqlSessionFactory);
      return factory;
    }

    @Bean
    SkitProviderConnectionHealthService providerConnectionHealthService(
        SkitProviderConnectionHealthMapper mapper) {
      return new SkitProviderConnectionHealthServiceImpl(mapper);
    }
  }
}
