package cn.iocoder.yudao.module.skit.service.ad.callback;

import cn.iocoder.yudao.framework.tenant.core.aop.TenantIgnore;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.skit.dal.dataobject.provider.SkitProviderCallbackAttemptDO;
import cn.iocoder.yudao.module.skit.dal.mysql.provider.SkitProviderCallbackAttemptMapper;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SkitProviderImpressionRetentionServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 3, 8, 30, 0);

    @AfterEach
    void clearTenantContext() {
        TenantContextHolder.clear();
    }

    @Test
    void defaultsToSevenDaysAndRejectsEveryOutOfRangeSetting() {
        SkitProviderImpressionRetentionProperties properties =
                new SkitProviderImpressionRetentionProperties();

        assertEquals(7, properties.getDays());
        assertEquals(NOW.plusDays(7), properties.expiresAt(NOW));
        properties.setDays(1);
        assertEquals(NOW.plusDays(1), properties.expiresAt(NOW));
        properties.setDays(30);
        assertEquals(NOW.plusDays(30), properties.expiresAt(NOW));
        assertThrows(IllegalArgumentException.class, () -> properties.setDays(0));
        assertThrows(IllegalArgumentException.class, () -> properties.setDays(-1));
        assertThrows(IllegalArgumentException.class, () -> properties.setDays(31));
    }

    @Test
    void invalidExternalRetentionConfigurationFailsApplicationStartup() {
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        ConfigurationPropertiesAutoConfiguration.class))
                .withUserConfiguration(SkitProviderImpressionRetentionProperties.class);

        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(SkitProviderImpressionRetentionProperties.class)
                    .getDays()).isEqualTo(7);
        });
        for (int invalid : new int[]{-1, 0, 31}) {
            runner.withPropertyValues(
                            "skit.ad.provider-impression.retention.days=" + invalid)
                    .run(context -> assertThat(context).hasFailed());
        }
    }

    @Test
    void batchSizeIsBoundedBeforeAnyDatabaseWorkCanStart() {
        SkitProviderImpressionRetentionProperties properties =
                new SkitProviderImpressionRetentionProperties();

        assertEquals(200, properties.getBatchSize());
        assertEquals(120, properties.getMaxBatchesPerRun());
        properties.setBatchSize(1);
        properties.setBatchSize(1000);
        assertThrows(IllegalArgumentException.class, () -> properties.setBatchSize(0));
        assertThrows(IllegalArgumentException.class, () -> properties.setBatchSize(1001));
        properties.setMaxBatchesPerRun(1);
        properties.setMaxBatchesPerRun(300);
        assertThrows(IllegalArgumentException.class, () -> properties.setMaxBatchesPerRun(0));
        assertThrows(IllegalArgumentException.class, () -> properties.setMaxBatchesPerRun(301));
    }

    @Test
    @SuppressWarnings("unchecked")
    void oneReadCommittedTransactionLocksOneBoundedBatchAndPurgesEverySelectedRowOnce() {
        SkitProviderCallbackAttemptMapper mapper = mock(SkitProviderCallbackAttemptMapper.class);
        TransactionOperations transactions = mock(TransactionOperations.class);
        SkitProviderImpressionRetentionProperties properties =
                new SkitProviderImpressionRetentionProperties();
        properties.setBatchSize(2);
        SkitProviderCallbackAttemptDO first = claim(11L, 101L, 1001L);
        SkitProviderCallbackAttemptDO second = claim(12L, 102L, 1002L);
        when(mapper.selectEligiblePayloadsForPurge(NOW, 2))
                .thenReturn(Arrays.asList(first, second));
        when(mapper.purgeEligiblePayload(11L, 101L, 1001L, NOW)).thenReturn(1);
        when(mapper.purgeEligiblePayload(12L, 102L, 1002L, NOW)).thenReturn(1);
        when(transactions.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<Integer> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        });
        SkitProviderImpressionRetentionService service =
                new SkitProviderImpressionRetentionServiceImpl(mapper, transactions, properties);

        assertEquals(2, service.purgeExpiredCiphertexts("node-a", NOW));

        verify(mapper).selectEligiblePayloadsForPurge(NOW, 2);
        verify(mapper).purgeEligiblePayload(11L, 101L, 1001L, NOW);
        verify(mapper).purgeEligiblePayload(12L, 102L, 1002L, NOW);
    }

    @Test
    @SuppressWarnings("unchecked")
    void anyUnexpectedAffectedCountFailsTheWholeBatch() {
        SkitProviderCallbackAttemptMapper mapper = mock(SkitProviderCallbackAttemptMapper.class);
        TransactionOperations transactions = mock(TransactionOperations.class);
        SkitProviderCallbackAttemptDO selected = claim(11L, 101L, 1001L);
        when(mapper.selectEligiblePayloadsForPurge(NOW, 200))
                .thenReturn(Collections.singletonList(selected));
        when(mapper.purgeEligiblePayload(11L, 101L, 1001L, NOW)).thenReturn(0);
        when(transactions.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<Integer> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        });
        SkitProviderImpressionRetentionService service =
                new SkitProviderImpressionRetentionServiceImpl(mapper, transactions,
                        new SkitProviderImpressionRetentionProperties());

        assertThrows(IllegalStateException.class,
                () -> service.purgeExpiredCiphertexts("node-a", NOW));
    }

    @Test
    void validatesBoundedNodeIdentityAndUtcSecondPrecisionBeforeDatabaseAccess() {
        SkitProviderCallbackAttemptMapper mapper = mock(SkitProviderCallbackAttemptMapper.class);
        SkitProviderImpressionRetentionService service = service(mapper);

        assertThrows(IllegalArgumentException.class,
                () -> service.purgeExpiredCiphertexts(null, NOW));
        assertThrows(IllegalArgumentException.class,
                () -> service.purgeExpiredCiphertexts("", NOW));
        assertThrows(IllegalArgumentException.class,
                () -> service.purgeExpiredCiphertexts("node with spaces", NOW));
        assertThrows(IllegalArgumentException.class,
                () -> service.purgeExpiredCiphertexts(repeat('a', 65), NOW));
        assertThrows(IllegalArgumentException.class,
                () -> service.purgeExpiredCiphertexts("node-a", null));
        assertThrows(IllegalArgumentException.class,
                () -> service.purgeExpiredCiphertexts("node-a", NOW.withNano(1)));
    }

    @Test
    void arbitraryCallerTenantContextIsNeverChanged() {
        SkitProviderCallbackAttemptMapper mapper = mock(SkitProviderCallbackAttemptMapper.class);
        when(mapper.selectEligiblePayloadsForPurge(NOW, 200))
                .thenReturn(Collections.emptyList());
        TenantContextHolder.setTenantId(773L);
        TenantContextHolder.setIgnore(false);

        assertEquals(0, service(mapper).purgeExpiredCiphertexts("node-a", NOW));

        assertEquals(773L, TenantContextHolder.getTenantId());
        assertFalse(TenantContextHolder.isIgnore());
    }

    @Test
    void serviceAndMapperDeclareGlobalTenantBypassAtTypeAndMethodBoundaries()
            throws Exception {
        Method serviceMethod = SkitProviderImpressionRetentionService.class.getMethod(
                "purgeExpiredCiphertexts", String.class, LocalDateTime.class);
        Method selectMethod = SkitProviderCallbackAttemptMapper.class.getMethod(
                "selectEligiblePayloadsForPurge", LocalDateTime.class, int.class);
        Method purgeMethod = SkitProviderCallbackAttemptMapper.class.getMethod(
                "purgeEligiblePayload", long.class, long.class, long.class,
                LocalDateTime.class);

        assertNotNull(SkitProviderImpressionRetentionService.class
                .getAnnotation(TenantIgnore.class));
        assertNotNull(serviceMethod.getAnnotation(TenantIgnore.class));
        assertNotNull(SkitProviderCallbackAttemptMapper.class.getAnnotation(TenantIgnore.class));
        assertNotNull(selectMethod.getAnnotation(TenantIgnore.class));
        assertNotNull(selectMethod.getAnnotation(InterceptorIgnore.class));
        assertNotNull(purgeMethod.getAnnotation(TenantIgnore.class));
        assertNotNull(purgeMethod.getAnnotation(InterceptorIgnore.class));
    }

    @Test
    void mapperSqlMatchesTheImmutableTriggerEligibilityAndAtomicEnvelopePurge()
            throws Exception {
        Method selectMethod = SkitProviderCallbackAttemptMapper.class.getMethod(
                "selectEligiblePayloadsForPurge", LocalDateTime.class, int.class);
        String select = String.join(" ", selectMethod.getAnnotation(Select.class).value())
                .toLowerCase();
        Method purgeMethod = SkitProviderCallbackAttemptMapper.class.getMethod(
                "purgeEligiblePayload", long.class, long.class, long.class,
                LocalDateTime.class);
        String update = String.join(" ", purgeMethod.getAnnotation(Update.class).value())
                .toLowerCase();

        assertTrue(select.startsWith("select a.id,a.provider_connection_id,a.inbox_id "));
        assertFalse(select.contains("a.*"));
        assertFalse(select.startsWith("select a.payload_"));
        assertTrue(select.contains("payload_ciphertext is not null"));
        assertTrue(select.contains("payload_expires_at<=least(#{now},utc_timestamp())"));
        assertTrue(select.contains("processing_status='succeeded'"));
        assertTrue(select.contains("processing_status='quarantined'"));
        assertTrue(select.contains("(i.processing_status='succeeded' or "
                + "i.processing_status='quarantined') and i.processed_at is not null"));
        assertTrue(select.contains("processed_at is not null"));
        assertTrue(select.contains("processing_status='dead_letter'"));
        assertTrue(select.contains("dead_letter_alerted_at is not null"));
        assertTrue(select.contains("order by a.payload_expires_at,a.id"));
        assertTrue(select.contains("limit #{limit} for update skip locked"));
        assertTrue(update.contains("payload_ciphertext=null"));
        assertTrue(update.contains("payload_nonce=null"));
        assertTrue(update.contains("payload_key_id=null"));
        assertTrue(update.contains("payload_purpose=null"));
        assertTrue(update.contains("payload_envelope_version=null"));
        assertTrue(update.contains("payload_expires_at=null"));
        assertTrue(update.contains("payload_purged_at=least(#{now},utc_timestamp())"));
        assertTrue(update.contains("payload_expires_at<=least(#{now},utc_timestamp())"));
        assertFalse(update.contains("delete"));
    }

    @Test
    void productionTransactionIsRequiresNewReadCommittedAndTimeBounded() {
        SkitProviderCallbackAttemptMapper mapper = mock(SkitProviderCallbackAttemptMapper.class);
        when(mapper.selectEligiblePayloadsForPurge(NOW, 200))
                .thenReturn(Collections.emptyList());
        AtomicReference<TransactionDefinition> definition = new AtomicReference<>();
        RecordingTransactionManager manager = new RecordingTransactionManager(definition);
        SkitProviderImpressionRetentionService service =
                new SkitProviderImpressionRetentionServiceImpl(mapper, manager,
                        new SkitProviderImpressionRetentionProperties());

        assertEquals(0, service.purgeExpiredCiphertexts("node-a", NOW));

        assertEquals(TransactionDefinition.PROPAGATION_REQUIRES_NEW,
                definition.get().getPropagationBehavior());
        assertEquals(TransactionDefinition.ISOLATION_READ_COMMITTED,
                definition.get().getIsolationLevel());
        assertEquals(2, definition.get().getTimeout());
    }

    private static SkitProviderImpressionRetentionService service(
            SkitProviderCallbackAttemptMapper mapper) {
        TransactionOperations transactions = mock(TransactionOperations.class);
        when(transactions.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<Integer> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        });
        return new SkitProviderImpressionRetentionServiceImpl(mapper, transactions,
                new SkitProviderImpressionRetentionProperties());
    }

    private static SkitProviderCallbackAttemptDO claim(long id, long connectionId, long inboxId) {
        return new SkitProviderCallbackAttemptDO()
                .setId(id)
                .setProviderConnectionId(connectionId)
                .setInboxId(inboxId);
    }

    private static String repeat(char value, int count) {
        char[] result = new char[count];
        java.util.Arrays.fill(result, value);
        return new String(result);
    }

    private static final class RecordingTransactionManager
            extends AbstractPlatformTransactionManager {

        private final AtomicReference<TransactionDefinition> definition;

        private RecordingTransactionManager(AtomicReference<TransactionDefinition> definition) {
            this.definition = definition;
        }

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            this.definition.set(definition);
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
        }
    }
}
