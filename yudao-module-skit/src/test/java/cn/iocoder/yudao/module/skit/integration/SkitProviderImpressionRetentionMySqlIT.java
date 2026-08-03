package cn.iocoder.yudao.module.skit.integration;

import cn.iocoder.yudao.framework.tenant.config.TenantProperties;
import cn.iocoder.yudao.framework.tenant.core.db.TenantDatabaseInterceptor;
import cn.iocoder.yudao.module.skit.dal.mysql.provider.SkitProviderCallbackAttemptMapper;
import cn.iocoder.yudao.module.skit.service.ad.callback.SkitProviderImpressionRetentionProperties;
import cn.iocoder.yudao.module.skit.service.ad.callback.SkitProviderImpressionRetentionService;
import cn.iocoder.yudao.module.skit.service.ad.callback.SkitProviderImpressionRetentionServiceImpl;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.mapper.MapperFactoryBean;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.TransactionSystemException;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkitProviderImpressionRetentionMySqlIT extends SkitMySqlIntegrationTestBase {

    private AnnotationConfigApplicationContext context;
    private SkitProviderCallbackAttemptMapper attemptMapper;
    private PlatformTransactionManager transactionManager;
    private long connectionId;
    private String previousGlobalTimeZone;

    @BeforeAll
    void startRetentionBoundary() {
        previousGlobalTimeZone = jdbc().queryForObject(
                "SELECT @@GLOBAL.time_zone", String.class);
        jdbc().execute("SET GLOBAL time_zone='+08:00'");
        assertEquals("+08:00", jdbc().queryForObject(
                "SELECT @@SESSION.time_zone", String.class));
        connectionId = installProviderConnection();
        context = new AnnotationConfigApplicationContext();
        context.registerBean("dataSource", DataSource.class, this::dataSource);
        context.register(RealRetentionConfiguration.class);
        context.refresh();
        attemptMapper = context.getBean(SkitProviderCallbackAttemptMapper.class);
        transactionManager = context.getBean(PlatformTransactionManager.class);
    }

    @AfterAll
    void closeRetentionBoundary() {
        try {
            if (context != null) {
                context.close();
            }
        } finally {
            restoreGlobalTimeZone();
        }
    }

    @BeforeEach
    void isolateRetentionFixture() {
        // Attempt rows are append-only by database trigger. Use the production retention path to
        // erase any eligible envelope left by a previously executed test, regardless of test order.
        service(1000, transactionManager).purgeExpiredCiphertexts(
                "retention-it-isolation", databaseNow());
    }

    @Test
    void exactExpiryBoundaryPurgesOnlyEveryTriggerEligibleInboxCombination() {
        LocalDateTime now = databaseNow();
        LocalDateTime expired = now.minusSeconds(1);
        LocalDateTime future = now.plusDays(1);
        LocalDateTime timezoneSkewFuture = now.plusHours(1);
        Map<String, Long> attempts = new LinkedHashMap<>();
        attempts.put("boundary-succeeded", fixture(
                "boundary-succeeded", "SUCCEEDED", true, false, now, false));
        attempts.put("processed-quarantine", fixture(
                "processed-quarantine", "QUARANTINED", true, false, expired, false));
        attempts.put("alerted-dead-letter", fixture(
                "alerted-dead-letter", "DEAD_LETTER", true, true, expired, false));
        attempts.put("pending", fixture(
                "pending", "PENDING", false, false, expired, false));
        attempts.put("processing", fixture(
                "processing", "PROCESSING", false, false, expired, false));
        attempts.put("retry-wait", fixture(
                "retry-wait", "RETRY_WAIT", false, false, expired, false));
        attempts.put("unprocessed-quarantine", fixture(
                "unprocessed-quarantine", "QUARANTINED", false, false, expired, false));
        attempts.put("unalerted-dead-letter", fixture(
                "unalerted-dead-letter", "DEAD_LETTER", true, false, expired, false));
        attempts.put("future-succeeded", fixture(
                "future-succeeded", "SUCCEEDED", true, false, future, false));
        attempts.put("timezone-skew-future", fixture(
                "timezone-skew-future", "SUCCEEDED", true, false,
                timezoneSkewFuture, false));
        attempts.put("already-purged", fixture(
                "already-purged", "SUCCEEDED", true, false, expired, true));
        String identityBefore = identity(attempts.get("boundary-succeeded"));
        Timestamp alreadyPurgedAt = payload(attempts.get("already-purged"),
                "payload_purged_at", Timestamp.class);

        int purged = service(100, transactionManager)
                .purgeExpiredCiphertexts("mysql-node-a", now);

        assertEquals(3, purged);
        assertPurged(attempts.get("boundary-succeeded"));
        assertPurged(attempts.get("processed-quarantine"));
        assertPurged(attempts.get("alerted-dead-letter"));
        assertEnvelopePresent(attempts.get("pending"));
        assertEnvelopePresent(attempts.get("processing"));
        assertEnvelopePresent(attempts.get("retry-wait"));
        assertEnvelopePresent(attempts.get("unprocessed-quarantine"));
        assertEnvelopePresent(attempts.get("unalerted-dead-letter"));
        assertEnvelopePresent(attempts.get("future-succeeded"));
        assertEnvelopePresent(attempts.get("timezone-skew-future"));
        assertEquals(alreadyPurgedAt, payload(attempts.get("already-purged"),
                "payload_purged_at", Timestamp.class));
        assertEquals(identityBefore, identity(attempts.get("boundary-succeeded")),
                "retention must not mutate identity or safe audit evidence");
    }

    @Test
    void twentyConcurrentWorkersPurgeEveryEligibleEnvelopeExactlyOnce() throws Exception {
        LocalDateTime now = databaseNow();
        List<Long> attemptIds = new ArrayList<>();
        for (int index = 0; index < 20; index++) {
            String fixture = "concurrent-" + index;
            attemptIds.add(fixture(fixture, "SUCCEEDED", true, false,
                    now.minusSeconds(1), false));
        }
        SkitProviderImpressionRetentionService service = service(1, transactionManager);
        ExecutorService executor = Executors.newFixedThreadPool(20);
        CountDownLatch ready = new CountDownLatch(20);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        try {
            for (int index = 0; index < 20; index++) {
                int worker = index;
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(10, TimeUnit.SECONDS));
                    return service.purgeExpiredCiphertexts("worker-" + worker, now);
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            int total = 0;
            for (Future<Integer> future : futures) {
                total += future.get(30, TimeUnit.SECONDS);
            }
            assertEquals(20, total, "a locked Attempt can contribute to only one worker count");
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }

        for (Long attemptId : attemptIds) {
            assertPurged(attemptId);
        }
        assertEquals(20, jdbc().queryForObject(
                "SELECT COUNT(*) FROM skit_provider_callback_attempt "
                        + "WHERE trace_id LIKE 'concurrent-%' AND payload_ciphertext IS NULL "
                        + "AND payload_purged_at IS NOT NULL",
                Integer.class));
    }

    @Test
    void commitFailureRollsBackTheWholeBatchAndLeavesEveryEnvelopeIntact() {
        LocalDateTime now = databaseNow();
        long first = fixture("rollback-first", "SUCCEEDED", true, false,
                now.minusSeconds(1), false);
        long second = fixture("rollback-second", "QUARANTINED", true, false,
                now.minusSeconds(1), false);
        PlatformTransactionManager rollbackThenFail =
                new RollbackThenFailCommitTransactionManager(transactionManager);

        assertThrows(TransactionSystemException.class, () -> service(10, rollbackThenFail)
                .purgeExpiredCiphertexts("rollback-node", now));

        assertEnvelopePresent(first);
        assertEnvelopePresent(second);
    }

    private SkitProviderImpressionRetentionService service(
            int batchSize, PlatformTransactionManager manager) {
        SkitProviderImpressionRetentionProperties properties =
                new SkitProviderImpressionRetentionProperties();
        properties.setBatchSize(batchSize);
        return new SkitProviderImpressionRetentionServiceImpl(
                attemptMapper, manager, properties);
    }

    private long fixture(String name, String status, boolean processed,
                         boolean alerted, LocalDateTime expiresAt, boolean alreadyPurged) {
        LocalDateTime receivedAt = expiresAt.minusDays(7);
        long inboxId = insertInbox(name, status, processed, alerted, receivedAt);
        return insertAttempt(name, inboxId, receivedAt, expiresAt, alreadyPurged);
    }

    private long insertInbox(String name, String status, boolean processed,
                             boolean alerted, LocalDateTime receivedAt) {
        String quarantineReason = "QUARANTINED".equals(status) ? "TEST_QUARANTINE" : null;
        String leaseOwner = "PROCESSING".equals(status) ? "fixture-node" : null;
        LocalDateTime leaseUntil = "PROCESSING".equals(status)
                ? receivedAt.plusMinutes(5) : null;
        LocalDateTime nextAttemptAt = "RETRY_WAIT".equals(status)
                ? receivedAt.plusMinutes(5) : null;
        LocalDateTime processedAt = processed ? receivedAt.plusSeconds(1) : null;
        LocalDateTime alertedAt = alerted ? receivedAt.plusSeconds(2) : null;
        jdbc().update("INSERT INTO skit_provider_impression_inbox "
                        + "(provider_connection_id,dedupe_scheme,dedupe_key_hash,"
                        + "provider_request_id_lexical,adsource_id_lexical,material_integrity_hash,"
                        + "authentication_level,integrity_status,integrity_revision,processing_status,"
                        + "quarantine_reason,lease_owner,lease_until,processing_attempt_count,"
                        + "next_attempt_at,first_received_at,last_received_at,processed_at,"
                        + "dead_letter_alerted_at) VALUES (?,'OFFICIAL_V1',UNHEX(SHA2(?,256)),?,"
                        + "'1',UNHEX(SHA2(CONCAT('material-',?),256)),"
                        + "'UNSIGNED_PROVIDER_OBSERVATION','CANONICAL',0,?,?,?,?,0,?,?,?,?,?)",
                connectionId, name, name, name, status, quarantineReason, leaseOwner,
                leaseUntil, nextAttemptAt, receivedAt, receivedAt, processedAt, alertedAt);
        return jdbc().queryForObject(
                "SELECT id FROM skit_provider_impression_inbox "
                        + "WHERE provider_connection_id=? AND provider_request_id_lexical=?",
                Long.class, connectionId, name);
    }

    private long insertAttempt(String name, long inboxId, LocalDateTime receivedAt,
                               LocalDateTime expiresAt, boolean alreadyPurged) {
        if (alreadyPurged) {
            jdbc().update("INSERT INTO skit_provider_callback_attempt "
                            + "(correlation_id,provider_connection_id,inbox_id,dedupe_scheme,"
                            + "wire_payload_hash,material_integrity_hash,delivery_integrity_status,"
                            + "response_decision,payload_purged_at,wire_size_bytes,parameter_count,"
                            + "remote_address_hash,user_agent_hash,request_header_fingerprint,"
                            + "trace_id,received_at) VALUES (UNHEX(MD5(?)),?,?,'OFFICIAL_V1',"
                            + "UNHEX(SHA2(CONCAT('wire-',?),256)),"
                            + "UNHEX(SHA2(CONCAT('material-',?),256)),'CANONICAL','ACK_200',?,"
                            + "128,2,UNHEX(REPEAT('77',32)),UNHEX(REPEAT('88',32)),"
                            + "UNHEX(REPEAT('99',32)),?,?)",
                    name, connectionId, inboxId, name, name, expiresAt, name, receivedAt);
        } else {
            jdbc().update("INSERT INTO skit_provider_callback_attempt "
                            + "(correlation_id,provider_connection_id,inbox_id,dedupe_scheme,"
                            + "wire_payload_hash,material_integrity_hash,delivery_integrity_status,"
                            + "response_decision,payload_ciphertext,payload_nonce,payload_key_id,"
                            + "payload_purpose,payload_envelope_version,payload_expires_at,"
                            + "wire_size_bytes,parameter_count,remote_address_hash,user_agent_hash,"
                            + "request_header_fingerprint,trace_id,received_at) VALUES "
                            + "(UNHEX(MD5(?)),?,?,'OFFICIAL_V1',"
                            + "UNHEX(SHA2(CONCAT('wire-',?),256)),"
                            + "UNHEX(SHA2(CONCAT('material-',?),256)),'CANONICAL','ACK_200',"
                            + "X'0102',UNHEX(REPEAT('66',12)),'retention-it',"
                            + "'PROVIDER_CALLBACK_PAYLOAD',1,?,128,2,UNHEX(REPEAT('77',32)),"
                            + "UNHEX(REPEAT('88',32)),UNHEX(REPEAT('99',32)),?,?)",
                    name, connectionId, inboxId, name, name, expiresAt, name, receivedAt);
        }
        return jdbc().queryForObject(
                "SELECT id FROM skit_provider_callback_attempt WHERE trace_id=?",
                Long.class, name);
    }

    private void assertPurged(long attemptId) {
        Map<String, Object> row = jdbc().queryForMap(
                "SELECT payload_ciphertext,payload_nonce,payload_key_id,payload_purpose,"
                        + "payload_envelope_version,payload_expires_at,payload_purged_at "
                        + "FROM skit_provider_callback_attempt WHERE id=?", attemptId);
        assertNull(row.get("payload_ciphertext"));
        assertNull(row.get("payload_nonce"));
        assertNull(row.get("payload_key_id"));
        assertNull(row.get("payload_purpose"));
        assertNull(row.get("payload_envelope_version"));
        assertNull(row.get("payload_expires_at"));
        assertNotNull(row.get("payload_purged_at"));
    }

    private void assertEnvelopePresent(long attemptId) {
        Map<String, Object> row = jdbc().queryForMap(
                "SELECT payload_ciphertext,payload_nonce,payload_key_id,payload_purpose,"
                        + "payload_envelope_version,payload_expires_at,payload_purged_at "
                        + "FROM skit_provider_callback_attempt WHERE id=?", attemptId);
        assertNotNull(row.get("payload_ciphertext"));
        assertNotNull(row.get("payload_nonce"));
        assertNotNull(row.get("payload_key_id"));
        assertNotNull(row.get("payload_purpose"));
        assertNotNull(row.get("payload_envelope_version"));
        assertNotNull(row.get("payload_expires_at"));
        assertNull(row.get("payload_purged_at"));
    }

    private String identity(long attemptId) {
        return jdbc().queryForObject(
                "SELECT CONCAT_WS('|',HEX(correlation_id),provider_connection_id,inbox_id,"
                        + "dedupe_scheme,HEX(wire_payload_hash),HEX(material_integrity_hash),"
                        + "delivery_integrity_status,response_decision,wire_size_bytes,"
                        + "parameter_count,HEX(remote_address_hash),HEX(user_agent_hash),"
                        + "HEX(request_header_fingerprint),trace_id,received_at) "
                        + "FROM skit_provider_callback_attempt WHERE id=?",
                String.class, attemptId);
    }

    private <T> T payload(long attemptId, String column, Class<T> type) {
        return jdbc().queryForObject("SELECT " + column
                + " FROM skit_provider_callback_attempt WHERE id=?", type, attemptId);
    }

    private LocalDateTime databaseNow() {
        return jdbc().queryForObject("SELECT UTC_TIMESTAMP", Timestamp.class)
                .toLocalDateTime().withNano(0);
    }

    private long installProviderConnection() {
        LocalDateTime now = databaseNow();
        jdbc().update("INSERT INTO skit_ad_provider_connection "
                        + "(connection_code,provider,account_mode,external_account_ref_hash,state,"
                        + "created_by_user_id,created_at,updated_by_user_id,updated_at) VALUES "
                        + "('retention-it','TAKU','SHARED_MASTER',UNHEX(REPEAT('31',32)),"
                        + "'CONFIGURING',7,?,7,?)",
                now, now);
        return jdbc().queryForObject(
                "SELECT id FROM skit_ad_provider_connection WHERE connection_code='retention-it'",
                Long.class);
    }

    private void restoreGlobalTimeZone() {
        if (previousGlobalTimeZone == null) {
            return;
        }
        if (!"SYSTEM".equals(previousGlobalTimeZone)
                && !previousGlobalTimeZone.matches("[+-]\\d{2}:\\d{2}")) {
            throw new IllegalStateException(
                    "Unexpected MySQL global time zone: " + previousGlobalTimeZone);
        }
        jdbc().execute("SET GLOBAL time_zone='" + previousGlobalTimeZone + "'");
    }

    @Configuration(proxyBeanMethods = false)
    static class RealRetentionConfiguration {

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
        MapperFactoryBean<SkitProviderCallbackAttemptMapper> providerCallbackAttemptMapper(
                SqlSessionFactory sqlSessionFactory) {
            MapperFactoryBean<SkitProviderCallbackAttemptMapper> factory =
                    new MapperFactoryBean<>(SkitProviderCallbackAttemptMapper.class);
            factory.setSqlSessionFactory(sqlSessionFactory);
            return factory;
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }
    }

    private static final class RollbackThenFailCommitTransactionManager
            implements PlatformTransactionManager {

        private final PlatformTransactionManager delegate;

        private RollbackThenFailCommitTransactionManager(PlatformTransactionManager delegate) {
            this.delegate = delegate;
        }

        @Override
        public TransactionStatus getTransaction(
                org.springframework.transaction.TransactionDefinition definition) {
            return delegate.getTransaction(definition);
        }

        @Override
        public void commit(TransactionStatus status) {
            delegate.rollback(status);
            throw new TransactionSystemException("Injected provider retention commit failure");
        }

        @Override
        public void rollback(TransactionStatus status) {
            delegate.rollback(status);
        }
    }
}
