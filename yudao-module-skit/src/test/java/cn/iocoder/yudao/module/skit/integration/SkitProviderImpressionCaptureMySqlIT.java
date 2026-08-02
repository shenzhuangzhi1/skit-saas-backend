package cn.iocoder.yudao.module.skit.integration;

import cn.iocoder.yudao.framework.tenant.config.TenantProperties;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.framework.tenant.core.db.TenantDatabaseInterceptor;
import cn.iocoder.yudao.module.skit.dal.mysql.provider.SkitProviderCallbackAttemptMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.provider.SkitProviderImpressionInboxMapper;
import cn.iocoder.yudao.module.skit.framework.crypto.SkitAesGcmCredentialCryptoService;
import cn.iocoder.yudao.module.skit.framework.crypto.SkitProviderCallbackPayloadCryptoService;
import cn.iocoder.yudao.module.skit.service.ad.callback.SkitProviderImpressionCaptureService;
import cn.iocoder.yudao.module.skit.service.ad.callback.SkitProviderImpressionCaptureServiceImpl;
import cn.iocoder.yudao.module.skit.service.ad.callback.SkitProviderImpressionWireParser;
import cn.iocoder.yudao.module.skit.service.provider.SkitProviderConnectionService.ProviderRouteResolution;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.mybatis.spring.mapper.MapperFactoryBean;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.TransactionSystemException;

import javax.sql.DataSource;
import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static cn.iocoder.yudao.module.skit.service.ad.callback.SkitProviderImpressionCaptureService.CaptureDecision.ACK_200;
import static cn.iocoder.yudao.module.skit.service.ad.callback.SkitProviderImpressionCaptureService.CaptureDecision.PERSISTENCE_FAILURE_503;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SkitProviderImpressionCaptureMySqlIT extends SkitMySqlIntegrationTestBase {

    private static final LocalDateTime RECEIVED_AT =
            LocalDateTime.of(2026, 8, 3, 6, 0, 0);
    private static final String MATERIAL_A = query("race-req", "3.24");
    private static final String MATERIAL_B = query("race-req", "3.25");

    private AnnotationConfigApplicationContext context;
    private SkitProviderImpressionCaptureService capture;
    private SkitProviderImpressionWireParser parser;
    private SkitProviderCallbackPayloadCryptoService crypto;
    private SkitProviderImpressionInboxMapper inboxMapper;
    private SkitProviderCallbackAttemptMapper attemptMapper;
    private long connectionId;

    @BeforeAll
    void startCaptureBoundary() {
        connectionId = installProviderConnection();
        context = new AnnotationConfigApplicationContext();
        context.registerBean("dataSource", DataSource.class, this::dataSource);
        context.register(RealCaptureConfiguration.class);
        context.refresh();
        capture = context.getBean(SkitProviderImpressionCaptureService.class);
        parser = context.getBean(SkitProviderImpressionWireParser.class);
        crypto = context.getBean(SkitProviderCallbackPayloadCryptoService.class);
        inboxMapper = context.getBean(SkitProviderImpressionInboxMapper.class);
        attemptMapper = context.getBean(SkitProviderCallbackAttemptMapper.class);
    }

    @AfterAll
    void closeCaptureBoundary() {
        TenantContextHolder.clear();
        if (context != null) {
            context.close();
        }
    }

    @Test
    @Order(1)
    void twentyConcurrentDuplicateAndConflictDeliveriesProduceOneInboxAndEveryAttempt()
            throws Exception {
        List<SkitProviderImpressionCaptureService.CaptureDecision> decisions =
                concurrentCaptures(20);

        assertEquals(20, decisions.size());
        for (SkitProviderImpressionCaptureService.CaptureDecision decision : decisions) {
            assertEquals(ACK_200, decision);
        }
        Map<String, Object> inbox = jdbc().queryForMap(
                "SELECT id,canonical_attempt_id,integrity_status,integrity_revision,"
                        + "integrity_conflict_at,processing_status FROM skit_provider_impression_inbox "
                        + "WHERE provider_connection_id=? AND dedupe_scheme='OFFICIAL_V1' "
                        + "AND provider_request_id_lexical='race-req' AND adsource_id_lexical='42'",
                connectionId);
        long inboxId = ((Number) inbox.get("id")).longValue();
        assertNotNull(inbox.get("canonical_attempt_id"));
        assertEquals("PAYLOAD_CONFLICT", inbox.get("integrity_status"));
        assertEquals(1L, ((Number) inbox.get("integrity_revision")).longValue());
        assertNotNull(inbox.get("integrity_conflict_at"));
        assertEquals("QUARANTINED", inbox.get("processing_status"));
        assertEquals(1, count("skit_provider_impression_inbox",
                "provider_connection_id=? AND provider_request_id_lexical='race-req'", connectionId));
        assertEquals(20, count("skit_provider_callback_attempt", "inbox_id=?", inboxId));
        assertEquals(1, attemptStatusCount(inboxId, "CANONICAL"));
        assertEquals(9, attemptStatusCount(inboxId, "EQUIVALENT_DUPLICATE"));
        assertEquals(10, attemptStatusCount(inboxId, "PAYLOAD_CONFLICT"));
        assertEquals(1, jdbc().queryForObject(
                "SELECT COUNT(*) FROM skit_provider_callback_attempt a "
                        + "JOIN skit_provider_impression_inbox i ON i.canonical_attempt_id=a.id "
                        + "WHERE i.id=? AND a.inbox_id=i.id "
                        + "AND a.delivery_integrity_status='CANONICAL'",
                Integer.class, inboxId));
        assertEquals(20, jdbc().queryForObject(
                "SELECT COUNT(*) FROM skit_provider_callback_attempt "
                        + "WHERE inbox_id=? AND payload_ciphertext IS NOT NULL "
                        + "AND payload_nonce IS NOT NULL AND payload_expires_at=received_at+INTERVAL 7 DAY",
                Integer.class, inboxId));
    }

    @Test
    @Order(2)
    void nullAndEmptyFallbackAreEncryptedGroupedAndPermanentlyQuarantined() {
        TenantContextHolder.setTenantId(773L);
        TenantContextHolder.setIgnore(false);
        try {
            assertEquals(ACK_200, capture.capture(route(), (String) null,
                    evidence(101), RECEIVED_AT.plusMinutes(1)));
            assertEquals(773L, TenantContextHolder.getTenantId());
            assertTrue(!TenantContextHolder.isIgnore());
            assertEquals(ACK_200, capture.capture(route(), "",
                    evidence(102), RECEIVED_AT.plusMinutes(1)));
            assertEquals(773L, TenantContextHolder.getTenantId());
            assertTrue(!TenantContextHolder.isIgnore());
        } finally {
            TenantContextHolder.clear();
        }

        Map<String, Object> inbox = jdbc().queryForMap(
                "SELECT id,canonical_attempt_id,dedupe_key_hash,processing_status,quarantine_reason "
                        + "FROM skit_provider_impression_inbox WHERE provider_connection_id=? "
                        + "AND dedupe_scheme='FALLBACK_WIRE_V1'",
                connectionId);
        long inboxId = ((Number) inbox.get("id")).longValue();
        assertNotNull(inbox.get("canonical_attempt_id"));
        assertNotNull(inbox.get("dedupe_key_hash"));
        assertEquals("QUARANTINED", inbox.get("processing_status"));
        assertEquals("OFFICIAL_FIELD_MISSING", inbox.get("quarantine_reason"));
        assertEquals(2, count("skit_provider_callback_attempt", "inbox_id=?", inboxId));
        assertEquals(2, attemptStatusCount(inboxId, "FALLBACK_QUARANTINED"));
        assertEquals(2, jdbc().queryForObject(
                "SELECT COUNT(*) FROM skit_provider_callback_attempt WHERE inbox_id=? "
                        + "AND wire_size_bytes=0 AND parameter_count=0 "
                        + "AND payload_ciphertext IS NOT NULL AND wire_payload_hash IS NOT NULL",
                Integer.class, inboxId));
    }

    @Test
    @Order(3)
    void injectedCommitFailureRollsBackInboxAndAttemptAndReturns503() {
        PlatformTransactionManager failing = new RollbackThenFailCommitTransactionManager(
                new DataSourceTransactionManager(dataSource()));
        SkitProviderImpressionCaptureService failingCapture =
                new SkitProviderImpressionCaptureServiceImpl(
                        parser, crypto, inboxMapper, attemptMapper, failing);

        assertEquals(PERSISTENCE_FAILURE_503, failingCapture.capture(
                route(), query("commit-failure-req", "7.77"),
                evidence(201), RECEIVED_AT.plusMinutes(2)));

        assertEquals(0, count("skit_provider_impression_inbox",
                "provider_connection_id=? AND provider_request_id_lexical='commit-failure-req'",
                connectionId));
        assertEquals(0, jdbc().queryForObject(
                "SELECT COUNT(*) FROM skit_provider_callback_attempt a "
                        + "JOIN skit_provider_impression_inbox i ON i.id=a.inbox_id "
                        + "WHERE i.provider_connection_id=? "
                        + "AND i.provider_request_id_lexical='commit-failure-req'",
                Integer.class, connectionId));
    }

    @Test
    @Order(4)
    void databaseChecksForeignKeysAndImmutabilityTriggersRejectDirectInvalidWrites() {
        long inboxId = jdbc().queryForObject(
                "SELECT id FROM skit_provider_impression_inbox WHERE provider_connection_id=? "
                        + "AND provider_request_id_lexical='race-req'",
                Long.class, connectionId);
        long attemptId = jdbc().queryForObject(
                "SELECT MIN(id) FROM skit_provider_callback_attempt WHERE inbox_id=?",
                Long.class, inboxId);

        assertThrows(DataAccessException.class, () -> jdbc().update(
                "UPDATE skit_provider_callback_attempt SET trace_id=CONCAT(trace_id,'x') WHERE id=?",
                attemptId));
        assertThrows(DataAccessException.class, () -> jdbc().update(
                "UPDATE skit_provider_impression_inbox SET material_integrity_hash=UNHEX(REPEAT('01',32)) "
                        + "WHERE id=?", inboxId));
        assertThrows(DataAccessException.class, () -> jdbc().update(
                "INSERT INTO skit_provider_callback_attempt (correlation_id,provider_connection_id,"
                        + "inbox_id,dedupe_scheme,wire_payload_hash,material_integrity_hash,"
                        + "delivery_integrity_status,response_decision,payload_ciphertext,payload_nonce,"
                        + "payload_key_id,payload_purpose,payload_envelope_version,payload_expires_at,"
                        + "wire_size_bytes,parameter_count,remote_address_hash,user_agent_hash,"
                        + "request_header_fingerprint,trace_id,received_at) "
                        + "SELECT UNHEX('ffffffffffffffffffffffffffffffff'),provider_connection_id,"
                        + "inbox_id,dedupe_scheme,wire_payload_hash,material_integrity_hash,"
                        + "delivery_integrity_status,response_decision,payload_ciphertext,payload_nonce,"
                        + "payload_key_id,payload_purpose,payload_envelope_version,payload_expires_at,"
                        + "32769,parameter_count,remote_address_hash,user_agent_hash,"
                        + "request_header_fingerprint,'pci-ffffffffffffffffffffffffffffffff',received_at "
                        + "FROM skit_provider_callback_attempt WHERE id=?",
                attemptId));
        assertThrows(DataAccessException.class, () -> jdbc().update(
                "INSERT INTO skit_provider_impression_inbox (provider_connection_id,dedupe_scheme,"
                        + "dedupe_key_hash,provider_request_id_lexical,adsource_id_lexical,"
                        + "material_integrity_hash,authentication_level,integrity_status,integrity_revision,"
                        + "integrity_conflict_at,processing_status,quarantine_reason,"
                        + "processing_attempt_count,first_received_at,last_received_at) "
                        + "SELECT 999999,dedupe_scheme,dedupe_key_hash,provider_request_id_lexical,"
                        + "adsource_id_lexical,material_integrity_hash,authentication_level,"
                        + "integrity_status,integrity_revision,integrity_conflict_at,processing_status,"
                        + "quarantine_reason,processing_attempt_count,first_received_at,last_received_at "
                        + "FROM skit_provider_impression_inbox WHERE id=?",
                inboxId));
    }

    private List<SkitProviderImpressionCaptureService.CaptureDecision> concurrentCaptures(int count)
            throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(count);
        CountDownLatch ready = new CountDownLatch(count);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<SkitProviderImpressionCaptureService.CaptureDecision>> futures =
                new ArrayList<>();
        try {
            for (int index = 0; index < count; index++) {
                final int current = index;
                futures.add(workers.submit(() -> {
                    ready.countDown();
                    if (!start.await(20, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Capture start latch timed out");
                    }
                    return capture.capture(route(), current < count / 2 ? MATERIAL_A : MATERIAL_B,
                            evidence(current + 1), RECEIVED_AT);
                }));
            }
            assertTrue(ready.await(20, TimeUnit.SECONDS));
            start.countDown();
            List<SkitProviderImpressionCaptureService.CaptureDecision> result = new ArrayList<>();
            for (Future<SkitProviderImpressionCaptureService.CaptureDecision> future : futures) {
                result.add(future.get(30, TimeUnit.SECONDS));
            }
            return result;
        } finally {
            start.countDown();
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(20, TimeUnit.SECONDS));
        }
    }

    private int attemptStatusCount(long inboxId, String status) {
        return jdbc().queryForObject(
                "SELECT COUNT(*) FROM skit_provider_callback_attempt "
                        + "WHERE inbox_id=? AND delivery_integrity_status=?",
                Integer.class, inboxId, status);
    }

    private int count(String table, String predicate, Object... arguments) {
        return jdbc().queryForObject("SELECT COUNT(*) FROM " + table + " WHERE " + predicate,
                Integer.class, arguments);
    }

    private long installProviderConnection() {
        LocalDateTime now = LocalDateTime.of(2026, 8, 3, 5, 59, 0);
        jdbc().update("INSERT INTO skit_ad_provider_connection (connection_code,provider,account_mode,"
                        + "external_account_ref_hash,state,created_by_user_id,created_at,"
                        + "updated_by_user_id,updated_at) VALUES ('capture-it','TAKU','SHARED_MASTER',"
                        + "UNHEX(REPEAT('11',32)),'CONFIGURING',7,?,7,?)",
                now, now);
        return jdbc().queryForObject(
                "SELECT id FROM skit_ad_provider_connection WHERE connection_code='capture-it'",
                Long.class);
    }

    private ProviderRouteResolution route() {
        try {
            Constructor<ProviderRouteResolution> constructor = ProviderRouteResolution.class
                    .getDeclaredConstructor(long.class, long.class, boolean.class);
            constructor.setAccessible(true);
            return constructor.newInstance(connectionId, 9911L, true);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    private static SkitProviderImpressionCaptureService.ProviderIngressEvidence evidence(int seed) {
        byte[] correlation = sequence(16, 0);
        correlation[12] = (byte) (seed >>> 24);
        correlation[13] = (byte) (seed >>> 16);
        correlation[14] = (byte) (seed >>> 8);
        correlation[15] = (byte) seed;
        return SkitProviderImpressionCaptureService.ProviderIngressEvidence.of(
                correlation, sequence(32, 32), sequence(32, 64), sequence(32, 96));
    }

    private static String query(String requestId, String price) {
        return "req_id=" + requestId + "&adsource_id=42&package_name=com.skit.capture"
                + "&adformat=1&placement_id=slot-1&nw_firm_id=66&adsource_price=" + price
                + "&currency=USD&timestamp=1783987200123&show_custom_ext=session-1";
    }

    private static byte[] sequence(int length, int seed) {
        byte[] result = new byte[length];
        for (int index = 0; index < result.length; index++) {
            result[index] = (byte) (seed + index);
        }
        return result;
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

        private static <T> MapperFactoryBean<T> mapperFactory(
                Class<T> mapperType, SqlSessionFactory sqlSessionFactory) {
            MapperFactoryBean<T> factory = new MapperFactoryBean<>(mapperType);
            factory.setSqlSessionFactory(sqlSessionFactory);
            return factory;
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        SkitProviderImpressionWireParser providerWireParser() {
            return new SkitProviderImpressionWireParser();
        }

        @Bean
        SkitProviderCallbackPayloadCryptoService providerPayloadCrypto() {
            return new SkitProviderCallbackPayloadCryptoService(
                    new SkitAesGcmCredentialCryptoService("capture-it",
                            Collections.singletonMap("capture-it",
                                    "0123456789abcdef0123456789abcdef"
                                            .getBytes(StandardCharsets.US_ASCII))));
        }

        @Bean
        SkitProviderImpressionCaptureService providerCapture(
                SkitProviderImpressionWireParser parser,
                SkitProviderCallbackPayloadCryptoService crypto,
                SkitProviderImpressionInboxMapper inboxMapper,
                SkitProviderCallbackAttemptMapper attemptMapper,
                PlatformTransactionManager transactionManager) {
            return new SkitProviderImpressionCaptureServiceImpl(
                    parser, crypto, inboxMapper, attemptMapper, transactionManager);
        }
    }

    private static final class RollbackThenFailCommitTransactionManager
            implements PlatformTransactionManager {

        private final PlatformTransactionManager delegate;

        private RollbackThenFailCommitTransactionManager(PlatformTransactionManager delegate) {
            this.delegate = delegate;
        }

        @Override
        public TransactionStatus getTransaction(org.springframework.transaction.TransactionDefinition definition) {
            return delegate.getTransaction(definition);
        }

        @Override
        public void commit(TransactionStatus status) {
            delegate.rollback(status);
            throw new TransactionSystemException("Injected provider capture commit failure");
        }

        @Override
        public void rollback(TransactionStatus status) {
            delegate.rollback(status);
        }
    }
}
