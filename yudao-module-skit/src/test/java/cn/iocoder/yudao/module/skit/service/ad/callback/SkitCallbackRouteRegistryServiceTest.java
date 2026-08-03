package cn.iocoder.yudao.module.skit.service.ad.callback;

import cn.iocoder.yudao.module.skit.dal.dataobject.provider.SkitAdCallbackRouteRegistryDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.provider.SkitAdCallbackRouteRegistryMigrationDO;
import cn.iocoder.yudao.module.skit.dal.mysql.ad.SkitAdAccountMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.ad.SkitAdCallbackKeyMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.ad.SkitAdRewardSecretVersionMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.provider.SkitAdCallbackRouteRegistryMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.provider.SkitAdCallbackRouteRegistryMigrationMapper;
import cn.iocoder.yudao.module.skit.framework.crypto.SkitAdCredentialCryptoService;
import cn.iocoder.yudao.module.skit.service.ad.SkitAdCredentialVersionService;
import cn.iocoder.yudao.module.skit.service.ad.SkitAdCredentialVersionServiceImpl;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionOperations;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SkitCallbackRouteRegistryServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-03T01:02:03Z");

    @Test
    void hashFirstLookupNeverFallsBackFromProviderToTenantReward() throws Exception {
        String rawKey = repeat('A', 43);
        byte[] keyHash = MessageDigest.getInstance("SHA-256")
                .digest(rawKey.getBytes(StandardCharsets.US_ASCII));
        SkitAdCallbackRouteRegistryMapper registryMapper =
                mock(SkitAdCallbackRouteRegistryMapper.class);
        SkitAdCallbackRouteRegistryMigrationMapper migrationMapper =
                mock(SkitAdCallbackRouteRegistryMigrationMapper.class);
        SkitAdCallbackKeyMapper legacyMapper = mock(SkitAdCallbackKeyMapper.class);
        when(migrationMapper.selectSingleton()).thenReturn(migration("HASH_FIRST"));
        when(registryMapper.selectLookupByKeyHash(any(byte[].class))).thenReturn(
                new SkitAdCallbackRouteRegistryDO().setId(9L)
                        .setRouteType("PROVIDER_CALLBACK_ROUTE")
                        .setProviderCallbackRouteId(17L)
                        .setRegisteredAt(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC)));
        SkitCallbackRouteRegistryService registryService = new SkitCallbackRouteRegistryService(
                registryMapper, migrationMapper, legacyMapper, new SimpleMeterRegistry(),
                Clock.fixed(NOW, ZoneOffset.UTC));
        SkitCallbackRoutingService routingService = new SkitCallbackRoutingService(registryService);

        assertEquals(SkitCallbackRouteRegistryService.RouteType.PROVIDER_CALLBACK_ROUTE,
                registryService.lookup(keyHash, LocalDateTime.ofInstant(NOW, ZoneOffset.UTC)).getRouteType());
        assertThrows(SkitCallbackRouteRegistryService.CallbackRouteRejectedException.class,
                () -> routingService.resolveTenantReward(
                        rawKey, LocalDateTime.ofInstant(NOW, ZoneOffset.UTC)));
        verify(legacyMapper, never()).selectByHash(any(byte[].class));
    }

    @Test
    void oneLookupUsesProviderUtcAndTenantDatabaseLocalBoundaries() {
        byte[] keyHash = new byte[32];
        Arrays.fill(keyHash, (byte) 21);
        LocalDateTime providerUtc = LocalDateTime.of(2026, 8, 3, 1, 2, 3);
        LocalDateTime tenantDatabaseLocal = LocalDateTime.of(2026, 8, 3, 9, 2, 3);
        LocalDateTime boundary = LocalDateTime.of(2026, 8, 3, 5, 0, 0);
        SkitAdCallbackRouteRegistryMapper registryMapper =
                mock(SkitAdCallbackRouteRegistryMapper.class);
        SkitAdCallbackRouteRegistryMigrationMapper migrationMapper =
                mock(SkitAdCallbackRouteRegistryMigrationMapper.class);
        SkitAdCallbackKeyMapper legacyMapper = mock(SkitAdCallbackKeyMapper.class);
        when(migrationMapper.selectSingleton()).thenReturn(migration("ENFORCED"));
        SkitCallbackRouteRegistryService service = new SkitCallbackRouteRegistryService(
                registryMapper, migrationMapper, legacyMapper, new SimpleMeterRegistry(),
                Clock.fixed(NOW, ZoneOffset.UTC));

        when(registryMapper.selectLookupByKeyHash(keyHash)).thenReturn(
                new SkitAdCallbackRouteRegistryDO().setId(12L)
                        .setRouteType("PROVIDER_CALLBACK_ROUTE")
                        .setProviderCallbackRouteId(20L)
                        .setTombstonedAt(boundary));
        assertEquals(SkitCallbackRouteRegistryService.RouteType.PROVIDER_CALLBACK_ROUTE,
                service.lookup(keyHash, providerUtc, tenantDatabaseLocal).getRouteType());

        when(registryMapper.selectLookupByKeyHash(keyHash)).thenReturn(
                new SkitAdCallbackRouteRegistryDO().setId(13L)
                        .setRouteType("TENANT_CALLBACK_KEY")
                        .setTenantCallbackKeyId(81L).setTenantId(41L).setAdAccountId(51L)
                        .setKeyVersion(1).setActive(false).setAcceptUntil(boundary)
                        .setRevokedAt(boundary).setTombstonedAt(boundary));
        assertThrows(SkitCallbackRouteRegistryService.CallbackRouteRejectedException.class,
                () -> service.lookup(keyHash, providerUtc, tenantDatabaseLocal));
    }

    @Test
    void preCutoverProviderOwnerRejectsWithoutLegacyFallback() {
        for (String phase : Arrays.asList("DUAL_WRITE", "BACKFILL", "VERIFY")) {
            byte[] keyHash = new byte[32];
            Arrays.fill(keyHash, (byte) 23);
            LocalDateTime receivedAt = LocalDateTime.ofInstant(NOW, ZoneOffset.UTC);
            SkitAdCallbackRouteRegistryMapper registryMapper =
                    mock(SkitAdCallbackRouteRegistryMapper.class);
            SkitAdCallbackRouteRegistryMigrationMapper migrationMapper =
                    mock(SkitAdCallbackRouteRegistryMigrationMapper.class);
            SkitAdCallbackKeyMapper legacyMapper = mock(SkitAdCallbackKeyMapper.class);
            when(migrationMapper.selectSingleton()).thenReturn(migration(phase));
            when(registryMapper.selectLookupByKeyHash(any(byte[].class))).thenReturn(
                    new SkitAdCallbackRouteRegistryDO().setId(10L)
                            .setRouteType("PROVIDER_CALLBACK_ROUTE")
                            .setProviderCallbackRouteId(18L).setRegisteredAt(receivedAt));
            cn.iocoder.yudao.module.skit.dal.dataobject.ad.SkitAdCallbackKeyDO legacy =
                    new cn.iocoder.yudao.module.skit.dal.dataobject.ad.SkitAdCallbackKeyDO()
                            .setId(72L).setAdAccountId(52L).setKeyVersion(1).setActive(true);
            legacy.setTenantId(42L);
            when(legacyMapper.selectByHash(any(byte[].class))).thenReturn(legacy);
            SkitCallbackRouteRegistryService service = new SkitCallbackRouteRegistryService(
                    registryMapper, migrationMapper, legacyMapper, new SimpleMeterRegistry(),
                    Clock.fixed(NOW, ZoneOffset.UTC));

            assertThrows(SkitCallbackRouteRegistryService.CallbackRouteRejectedException.class,
                    () -> service.lookupTenantReward(keyHash, receivedAt), phase);
            verify(registryMapper).selectLookupByKeyHash(keyHash);
            verify(legacyMapper, never()).selectByHash(any(byte[].class));
        }
    }

    @Test
    void earlyPhasePublicLookupUsesLegacyOnlyAfterOneRegistryMiss() {
        for (String phase : Arrays.asList("DUAL_WRITE", "BACKFILL", "VERIFY")) {
            byte[] keyHash = new byte[32];
            Arrays.fill(keyHash, (byte) 29);
            LocalDateTime receivedAt = LocalDateTime.ofInstant(NOW, ZoneOffset.UTC);
            SkitAdCallbackRouteRegistryMapper registryMapper =
                    mock(SkitAdCallbackRouteRegistryMapper.class);
            SkitAdCallbackRouteRegistryMigrationMapper migrationMapper =
                    mock(SkitAdCallbackRouteRegistryMigrationMapper.class);
            SkitAdCallbackKeyMapper legacyMapper = mock(SkitAdCallbackKeyMapper.class);
            when(migrationMapper.selectSingleton()).thenReturn(migration(phase));
            when(registryMapper.selectLookupByKeyHash(any(byte[].class))).thenReturn(null);
            cn.iocoder.yudao.module.skit.dal.dataobject.ad.SkitAdCallbackKeyDO legacy =
                    new cn.iocoder.yudao.module.skit.dal.dataobject.ad.SkitAdCallbackKeyDO()
                            .setId(73L).setAdAccountId(53L).setKeyVersion(2).setActive(true);
            legacy.setTenantId(43L);
            when(legacyMapper.selectByHash(any(byte[].class))).thenReturn(legacy);
            SkitCallbackRouteRegistryService service = new SkitCallbackRouteRegistryService(
                    registryMapper, migrationMapper, legacyMapper, new SimpleMeterRegistry(),
                    Clock.fixed(NOW, ZoneOffset.UTC));

            SkitCallbackRouteRegistryService.RouteLookup result =
                    service.lookup(keyHash, receivedAt);

            assertEquals(SkitCallbackRouteRegistryService.RouteType.TENANT_CALLBACK_KEY,
                    result.getRouteType(), phase);
            assertEquals(43L, result.getTenantId(), phase);
            verify(registryMapper).selectLookupByKeyHash(keyHash);
            verify(legacyMapper).selectByHash(keyHash);
        }
    }

    @Test
    void publicLookupReturnsProviderWithoutLegacyLookupInEveryPhase() {
        for (String phase : Arrays.asList(
                "DUAL_WRITE", "BACKFILL", "VERIFY", "SHADOW_READ", "HASH_FIRST", "ENFORCED")) {
            byte[] keyHash = new byte[32];
            Arrays.fill(keyHash, (byte) 31);
            LocalDateTime receivedAt = LocalDateTime.ofInstant(NOW, ZoneOffset.UTC);
            SkitAdCallbackRouteRegistryMapper registryMapper =
                    mock(SkitAdCallbackRouteRegistryMapper.class);
            SkitAdCallbackRouteRegistryMigrationMapper migrationMapper =
                    mock(SkitAdCallbackRouteRegistryMigrationMapper.class);
            SkitAdCallbackKeyMapper legacyMapper = mock(SkitAdCallbackKeyMapper.class);
            when(migrationMapper.selectSingleton()).thenReturn(migration(phase));
            when(registryMapper.selectLookupByKeyHash(any(byte[].class))).thenReturn(
                    new SkitAdCallbackRouteRegistryDO().setId(11L)
                            .setRouteType("PROVIDER_CALLBACK_ROUTE")
                            .setProviderCallbackRouteId(19L).setRegisteredAt(receivedAt));
            SkitCallbackRouteRegistryService service = new SkitCallbackRouteRegistryService(
                    registryMapper, migrationMapper, legacyMapper, new SimpleMeterRegistry(),
                    Clock.fixed(NOW, ZoneOffset.UTC));

            SkitCallbackRouteRegistryService.RouteLookup result =
                    service.lookup(keyHash, receivedAt);

            assertEquals(SkitCallbackRouteRegistryService.RouteType.PROVIDER_CALLBACK_ROUTE,
                    result.getRouteType(), phase);
            assertEquals(Long.valueOf(19L), result.getProviderCallbackRouteId(), phase);
            verify(registryMapper).selectLookupByKeyHash(keyHash);
            verify(legacyMapper, never()).selectByHash(any(byte[].class));
        }
    }

    @Test
    void hashFirstAndEnforcedMissFailClosedWithoutLegacyLookup() {
        for (String phase : Arrays.asList("HASH_FIRST", "ENFORCED")) {
            byte[] keyHash = new byte[32];
            Arrays.fill(keyHash, (byte) 37);
            LocalDateTime receivedAt = LocalDateTime.ofInstant(NOW, ZoneOffset.UTC);
            SkitAdCallbackRouteRegistryMapper registryMapper =
                    mock(SkitAdCallbackRouteRegistryMapper.class);
            SkitAdCallbackRouteRegistryMigrationMapper migrationMapper =
                    mock(SkitAdCallbackRouteRegistryMigrationMapper.class);
            SkitAdCallbackKeyMapper legacyMapper = mock(SkitAdCallbackKeyMapper.class);
            when(migrationMapper.selectSingleton()).thenReturn(migration(phase));
            SkitCallbackRouteRegistryService service = new SkitCallbackRouteRegistryService(
                    registryMapper, migrationMapper, legacyMapper, new SimpleMeterRegistry(),
                    Clock.fixed(NOW, ZoneOffset.UTC));

            assertThrows(SkitCallbackRouteRegistryService.CallbackRouteRejectedException.class,
                    () -> service.lookup(keyHash, receivedAt), phase);
            verify(registryMapper).selectLookupByKeyHash(keyHash);
            verify(legacyMapper, never()).selectByHash(any(byte[].class));
        }
    }

    @Test
    void issuingTenantKeyRegistersTheSameHashAndOwnerInsideRotation() throws Exception {
        SkitAdAccountMapper accountMapper = mock(SkitAdAccountMapper.class);
        SkitAdCallbackKeyMapper callbackMapper = mock(SkitAdCallbackKeyMapper.class);
        SkitAdRewardSecretVersionMapper rewardMapper = mock(SkitAdRewardSecretVersionMapper.class);
        SkitCallbackRouteRegistryService registryService = mock(SkitCallbackRouteRegistryService.class);
        when(accountMapper.lockByTenantAndId(41L, 51L)).thenReturn(51L);
        when(callbackMapper.selectMaxVersion(41L, 51L)).thenReturn(null);
        when(callbackMapper.selectActiveForUpdate(41L, 51L)).thenReturn(null);
        doAnswer(invocation -> {
            cn.iocoder.yudao.module.skit.dal.dataobject.ad.SkitAdCallbackKeyDO row =
                    invocation.getArgument(0);
            row.setId(61L);
            return 1;
        }).when(callbackMapper).insert(any());
        byte[] random = new byte[32];
        Arrays.fill(random, (byte) 7);
        SkitAdCredentialVersionServiceImpl credentialService = new SkitAdCredentialVersionServiceImpl(
                accountMapper, callbackMapper, rewardMapper,
                mock(SkitAdCredentialCryptoService.class), registryService,
                Clock.fixed(NOW, ZoneOffset.UTC), new FixedSecureRandom(random));

        SkitAdCredentialVersionService.CallbackKeyIssue issued = credentialService.rotateCallbackKey(
                41L, 51L, Duration.ZERO);
        String raw = issued.consumeCallbackKey();
        byte[] expectedHash = MessageDigest.getInstance("SHA-256")
                .digest(raw.getBytes(StandardCharsets.US_ASCII));

        org.mockito.ArgumentCaptor<SkitCallbackRouteRegistryService.TenantCallbackKeyRegistration> captured =
                org.mockito.ArgumentCaptor.forClass(
                        SkitCallbackRouteRegistryService.TenantCallbackKeyRegistration.class);
        verify(registryService).registerTenantKey(org.mockito.ArgumentMatchers.isNull(), captured.capture());
        assertEquals(61L, captured.getValue().getTenantCallbackKeyId());
        assertEquals("TenantCallbackKeyRegistration{tenantCallbackKeyId=61, tenantId=41, "
                        + "adAccountId=51, keyVersion=1}", captured.getValue().toString());
        verify(callbackMapper).insert(org.mockito.ArgumentMatchers.argThat(
                row -> MessageDigest.isEqual(expectedHash, row.getCallbackKeyHash())));
    }

    @Test
    void shadowReadUsesTheSameHashAndEmitsOnlyLowCardinalityMismatchTags() {
        byte[] keyHash = new byte[32];
        Arrays.fill(keyHash, (byte) 19);
        LocalDateTime receivedAt = LocalDateTime.ofInstant(NOW, ZoneOffset.UTC);
        SkitAdCallbackRouteRegistryMapper registryMapper =
                mock(SkitAdCallbackRouteRegistryMapper.class);
        SkitAdCallbackRouteRegistryMigrationMapper migrationMapper =
                mock(SkitAdCallbackRouteRegistryMigrationMapper.class);
        SkitAdCallbackKeyMapper legacyMapper = mock(SkitAdCallbackKeyMapper.class);
        when(migrationMapper.selectSingleton()).thenReturn(migration("SHADOW_READ"));
        when(registryMapper.selectLookupByKeyHash(any(byte[].class))).thenReturn(
                new SkitAdCallbackRouteRegistryDO().setId(8L)
                        .setRouteType("TENANT_CALLBACK_KEY").setTenantCallbackKeyId(71L)
                        .setTenantId(42L).setAdAccountId(51L).setKeyVersion(1)
                        .setActive(true).setRegisteredAt(receivedAt));
        cn.iocoder.yudao.module.skit.dal.dataobject.ad.SkitAdCallbackKeyDO legacy =
                new cn.iocoder.yudao.module.skit.dal.dataobject.ad.SkitAdCallbackKeyDO()
                        .setId(71L).setAdAccountId(51L).setKeyVersion(1).setActive(true);
        legacy.setTenantId(41L);
        when(legacyMapper.selectByHash(any(byte[].class))).thenReturn(legacy);
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        SkitCallbackRouteRegistryService service = new SkitCallbackRouteRegistryService(
                registryMapper, migrationMapper, legacyMapper, meters,
                Clock.fixed(NOW, ZoneOffset.UTC));

        assertThrows(SkitCallbackRouteRegistryService.CallbackRouteRejectedException.class,
                () -> service.lookup(keyHash, receivedAt));
        org.mockito.ArgumentCaptor<byte[]> registryHash =
                org.mockito.ArgumentCaptor.forClass(byte[].class);
        org.mockito.ArgumentCaptor<byte[]> legacyHash =
                org.mockito.ArgumentCaptor.forClass(byte[].class);
        verify(registryMapper).selectLookupByKeyHash(registryHash.capture());
        verify(legacyMapper).selectByHash(legacyHash.capture());
        assertSame(keyHash, registryHash.getValue());
        assertSame(keyHash, legacyHash.getValue());
        assertEquals(1D, meters.get(SkitCallbackRouteRegistryService.SHADOW_MISMATCH_METRIC)
                .tag("phase", "SHADOW_READ").tag("outcome", "OWNER_OR_STATE")
                .counter().count());
        assertEquals(2, meters.getMeters().get(0).getId().getTags().size());
    }

    @Test
    void verificationMismatchCommitsBlockedEvidenceBeforeTheCallerSeesFailure() {
        SkitAdCallbackRouteRegistryMapper registryMapper =
                mock(SkitAdCallbackRouteRegistryMapper.class);
        SkitAdCallbackRouteRegistryMigrationMapper migrationMapper =
                mock(SkitAdCallbackRouteRegistryMigrationMapper.class);
        SkitAdCallbackKeyMapper legacyMapper = mock(SkitAdCallbackKeyMapper.class);
        SkitAdCallbackRouteRegistryMigrationDO verifying = migration("VERIFY")
                .setPhaseRevision(2L).setCredentialMutationEpoch(3L)
                .setVerificationRunId(1L).setVerificationSnapshotEpoch(3L)
                .setVerificationCursorCallbackKeyId(7L)
                .setVerificationExpectedProgressCount(1L)
                .setVerificationActualProgressCount(0L)
                .setVerificationProgressMismatchCount(1L)
                .setVerificationExpectedRollingHash(new byte[32])
                .setVerificationActualRollingHash(new byte[] {1, 0, 0, 0, 0, 0, 0, 0,
                        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                        0, 0, 0, 0, 0, 0, 0, 0});
        when(migrationMapper.selectSingletonForUpdate()).thenReturn(verifying);
        when(registryMapper.selectVerificationPairsAfterId(7L,
                SkitCallbackRouteRegistryService.BACKFILL_BATCH_SIZE))
                .thenReturn(Collections.emptyList());
        when(migrationMapper.recordBlocked(eq(2L), any(byte[].class),
                any(LocalDateTime.class))).thenReturn(1);
        AtomicBoolean committed = new AtomicBoolean();
        TransactionOperations transactions = new TransactionOperations() {
            @Override
            public <T> T execute(org.springframework.transaction.support.TransactionCallback<T> action) {
                T result = action.doInTransaction(mock(TransactionStatus.class));
                committed.set(true);
                return result;
            }
        };
        SkitCallbackRouteRegistryService service = new SkitCallbackRouteRegistryService(
                registryMapper, migrationMapper, legacyMapper, new SimpleMeterRegistry(),
                transactions, Clock.fixed(NOW, ZoneOffset.UTC));

        assertThrows(SkitCallbackRouteRegistryService.RegistryMigrationBlockedException.class,
                service::backfillAndVerifyTenantKeys);
        assertEquals(true, committed.get());
    }

    @Test
    void backfillOwnershipConflictCommitsBlockedEvidenceBeforeTheCallerSeesFailure() {
        SkitAdCallbackRouteRegistryMapper registryMapper =
                mock(SkitAdCallbackRouteRegistryMapper.class);
        SkitAdCallbackRouteRegistryMigrationMapper migrationMapper =
                mock(SkitAdCallbackRouteRegistryMigrationMapper.class);
        SkitAdCallbackKeyMapper legacyMapper = mock(SkitAdCallbackKeyMapper.class);
        SkitAdCallbackRouteRegistryMigrationDO backfilling = migration("BACKFILL")
                .setPhaseRevision(7L);
        SkitAdCallbackRouteRegistryMigrationDO blocked = migration("BACKFILL")
                .setPhaseRevision(8L).setBlockedAt(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC));
        when(migrationMapper.selectSingletonForUpdate()).thenReturn(backfilling, blocked);
        SkitAdCallbackRouteRegistryDO legacy = new SkitAdCallbackRouteRegistryDO()
                .setTenantCallbackKeyId(7L).setTenantId(41L).setAdAccountId(51L)
                .setKeyVersion(1).setKeyHash(new byte[32]).setActive(true)
                .setRegisteredAt(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC));
        when(registryMapper.selectLegacyTenantKeysAfterId(0L,
                SkitCallbackRouteRegistryService.BACKFILL_BATCH_SIZE))
                .thenReturn(Collections.singletonList(legacy));
        when(registryMapper.selectLookupByKeyHash(any(byte[].class))).thenReturn(
                new SkitAdCallbackRouteRegistryDO().setRouteType("PROVIDER_CALLBACK_ROUTE")
                        .setProviderCallbackRouteId(99L));
        when(migrationMapper.recordBlocked(eq(7L), any(byte[].class),
                any(LocalDateTime.class))).thenReturn(1);
        AtomicBoolean committed = new AtomicBoolean();
        TransactionOperations transactions = new TransactionOperations() {
            @Override
            public <T> T execute(org.springframework.transaction.support.TransactionCallback<T> action) {
                T result = action.doInTransaction(mock(TransactionStatus.class));
                committed.set(true);
                return result;
            }
        };
        SkitCallbackRouteRegistryService service = new SkitCallbackRouteRegistryService(
                registryMapper, migrationMapper, legacyMapper, new SimpleMeterRegistry(),
                transactions, Clock.fixed(NOW, ZoneOffset.UTC));

        assertThrows(SkitCallbackRouteRegistryService.RegistryMigrationBlockedException.class,
                service::backfillAndVerifyTenantKeys);
        assertEquals(true, committed.get());
        org.mockito.InOrder order = inOrder(registryMapper, migrationMapper);
        order.verify(registryMapper).selectLookupByKeyHash(any(byte[].class));
        order.verify(migrationMapper).recordBlocked(eq(7L), any(byte[].class),
                any(LocalDateTime.class));
    }

    @Test
    void standaloneRegistrationCannotBypassTheProductionTransactionGate() {
        SkitCallbackRouteRegistryService service = new SkitCallbackRouteRegistryService(
                mock(SkitAdCallbackRouteRegistryMapper.class),
                mock(SkitAdCallbackRouteRegistryMigrationMapper.class),
                mock(SkitAdCallbackKeyMapper.class), new SimpleMeterRegistry(),
                Clock.fixed(NOW, ZoneOffset.UTC));
        SkitCallbackRouteRegistryService.TenantCallbackKeyRegistration registration =
                new SkitCallbackRouteRegistryService.TenantCallbackKeyRegistration(
                        71L, new byte[32], 41L, 51L, 1,
                        LocalDateTime.ofInstant(NOW, ZoneOffset.UTC), null);

        assertThrows(IllegalStateException.class,
                () -> service.registerTenantKey(registration));
    }

    @Test
    void opaqueMutationCapabilityIsScopedToOneActiveTransaction() {
        SkitAdCallbackRouteRegistryMapper registryMapper =
                mock(SkitAdCallbackRouteRegistryMapper.class);
        SkitAdCallbackRouteRegistryMigrationMapper migrationMapper =
                mock(SkitAdCallbackRouteRegistryMigrationMapper.class);
        SkitAdCallbackRouteRegistryMigrationDO dualWrite =
                migration("DUAL_WRITE").setCredentialMutationEpoch(0L);
        when(migrationMapper.selectSingleton()).thenReturn(dualWrite);
        when(migrationMapper.selectSingletonForUpdate()).thenReturn(dualWrite);
        when(migrationMapper.incrementCredentialMutationEpoch(eq(4L),
                any(LocalDateTime.class))).thenReturn(1);
        AtomicReference<SkitAdCallbackRouteRegistryDO> inserted = new AtomicReference<>();
        doAnswer(invocation -> {
            SkitAdCallbackRouteRegistryDO row = invocation.getArgument(0);
            row.setId(81L);
            inserted.set(row);
            return 1;
        }).when(registryMapper).insert(any(SkitAdCallbackRouteRegistryDO.class));
        when(registryMapper.selectLookupByKeyHash(any(byte[].class)))
                .thenAnswer(invocation -> inserted.get());
        when(registryMapper.selectByTenantCallbackKeyId(71L))
                .thenAnswer(invocation -> inserted.get());
        SkitCallbackRouteRegistryService service = new SkitCallbackRouteRegistryService(
                registryMapper, migrationMapper, mock(SkitAdCallbackKeyMapper.class),
                new SimpleMeterRegistry(), Clock.fixed(NOW, ZoneOffset.UTC));
        SkitCallbackRouteRegistryService.TenantCallbackKeyRegistration registration =
                new SkitCallbackRouteRegistryService.TenantCallbackKeyRegistration(
                        71L, new byte[32], 41L, 51L, 1,
                        LocalDateTime.ofInstant(NOW, ZoneOffset.UTC), null);

        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
        try {
            SkitCallbackRouteRegistryService.TenantKeyMutation mutation =
                    service.beginTenantKeyMutation(41L, 51L);
            service.registerTenantKey(mutation, registration);
            for (TransactionSynchronization synchronization
                    : TransactionSynchronizationManager.getSynchronizations()) {
                synchronization.afterCompletion(TransactionSynchronization.STATUS_COMMITTED);
            }
            assertThrows(IllegalStateException.class,
                    () -> service.registerTenantKey(mutation, registration));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
        verify(migrationMapper).incrementCredentialMutationEpoch(eq(4L),
                any(LocalDateTime.class));
        verify(registryMapper).insert(any(SkitAdCallbackRouteRegistryDO.class));
    }

    private static SkitAdCallbackRouteRegistryMigrationDO migration(String phase) {
        return new SkitAdCallbackRouteRegistryMigrationDO().setSingletonId(1)
                .setMigrationPhase(phase).setPhaseRevision(4L).setLastCallbackKeyId(0L);
    }

    private static String repeat(char value, int count) {
        char[] result = new char[count];
        java.util.Arrays.fill(result, value);
        return new String(result);
    }

    private static final class FixedSecureRandom extends SecureRandom {
        private final byte[] value;

        private FixedSecureRandom(byte[] value) {
            this.value = value.clone();
        }

        @Override
        public void nextBytes(byte[] bytes) {
            System.arraycopy(value, 0, bytes, 0, bytes.length);
        }
    }

}
