package cn.iocoder.yudao.module.skit.service.ad.callback;

import cn.iocoder.yudao.module.skit.dal.dataobject.ad.SkitAdCallbackKeyDO;
import cn.iocoder.yudao.module.skit.dal.mysql.ad.SkitAdCallbackKeyMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SkitLegacyImpressionAliasResolverTest {

    private static final String SOURCE_KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
    private static final String TARGET_KEY = "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB";
    private static final LocalDateTime RECEIVED_AT = LocalDateTime.of(2026, 8, 4, 10, 0);

    @Test
    void configuredExpiredSourceResolvesExactRegisteredActiveTarget() {
        SkitAdCallbackKeyMapper mapper = mock(SkitAdCallbackKeyMapper.class);
        SkitCallbackRouteRegistryService registry =
                mock(SkitCallbackRouteRegistryService.class);
        byte[] sourceHash = sha256(SOURCE_KEY);
        byte[] targetHash = sha256(TARGET_KEY);
        SkitAdCallbackKeyDO sourceRow = key(11L, 163L, 71L, 1, false,
                RECEIVED_AT.minusMinutes(1), null).setCallbackKeyHash(sourceHash.clone());
        SkitAdCallbackKeyDO targetRow = key(12L, 163L, 71L, 2, true,
                null, null).setCallbackKeyHash(targetHash.clone());
        when(mapper.selectByHash(argThat(value -> same(value, sourceHash))))
                .thenReturn(sourceRow);
        when(mapper.selectByHash(argThat(value -> same(value, targetHash))))
                .thenReturn(targetRow);
        SkitCallbackRouteRegistryService.RouteLookup expected =
                SkitCallbackRouteRegistryService.RouteLookup.tenant(
                        163L, 71L, 2, true, null);
        when(registry.lookupTenantReward(argThat(value -> same(value, targetHash)),
                org.mockito.ArgumentMatchers.eq(RECEIVED_AT))).thenReturn(expected);
        SkitLegacyImpressionAliasResolver resolver = new SkitLegacyImpressionAliasResolver(
                mapper, registry, hex(sourceHash) + "=" + hex(targetHash));

        SkitCallbackRouteRegistryService.RouteLookup resolved =
                resolver.resolve(sourceHash, RECEIVED_AT);

        assertSame(expected, resolved);
        assertEquals(SkitCallbackRouteRegistryService.RouteType.TENANT_CALLBACK_KEY,
                resolved.getRouteType());
        assertEquals(163L, resolved.getTenantId());
        assertEquals(71L, resolved.getAdAccountId());
        assertEquals(2, resolved.getKeyVersion());
        assertArrayEquals(new byte[32], sourceRow.getCallbackKeyHash());
        assertArrayEquals(new byte[32], targetRow.getCallbackKeyHash());
    }

    @Test
    void unconfiguredSourceIsRejectedWithoutDatabaseLookup() {
        SkitAdCallbackKeyMapper mapper = mock(SkitAdCallbackKeyMapper.class);
        SkitCallbackRouteRegistryService registry =
                mock(SkitCallbackRouteRegistryService.class);
        SkitLegacyImpressionAliasResolver resolver = new SkitLegacyImpressionAliasResolver(
                mapper, registry,
                hex(sha256(SOURCE_KEY)) + "=" + hex(sha256(TARGET_KEY)));

        assertThrows(SkitCallbackRouteRegistryService.CallbackRouteRejectedException.class,
                () -> resolver.resolve(sha256("CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC"),
                        RECEIVED_AT));
        verify(mapper, never()).selectByHash(org.mockito.ArgumentMatchers.any());
        verify(registry, never()).lookupTenantReward(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void activeNonExpiredOrRevokedSourceIsRejected() {
        SkitAdCallbackKeyDO target = key(12L, 163L, 71L, 2, true, null, null);
        SkitCallbackRouteRegistryService.RouteLookup route =
                SkitCallbackRouteRegistryService.RouteLookup.tenant(
                        163L, 71L, 2, true, null);

        assertRejected(key(11L, 163L, 71L, 1, true, null, null), target, route);
        assertRejected(key(11L, 163L, 71L, 1, false,
                RECEIVED_AT.plusMinutes(1), null), target, route);
        assertRejected(key(11L, 163L, 71L, 1, false,
                RECEIVED_AT.minusMinutes(1), RECEIVED_AT.minusSeconds(1)), target, route);
    }

    @Test
    void invalidOwnershipLifecycleOrRegistryTargetIsRejected() {
        SkitAdCallbackKeyDO source = key(11L, 163L, 71L, 1, false,
                RECEIVED_AT.minusMinutes(1), null);
        SkitCallbackRouteRegistryService.RouteLookup validRoute =
                SkitCallbackRouteRegistryService.RouteLookup.tenant(
                        163L, 71L, 2, true, null);

        assertRejected(source,
                key(12L, 163L, 71L, 2, false, RECEIVED_AT.plusDays(1), null),
                validRoute);
        assertRejected(source,
                key(12L, 163L, 71L, 2, true, null, RECEIVED_AT.minusSeconds(1)),
                validRoute);
        assertRejected(source, key(12L, 163L, 72L, 2, true, null, null), validRoute);
        assertRejected(source, key(12L, 164L, 71L, 2, true, null, null), validRoute);
        assertRejected(source, key(12L, 163L, 71L, 2, true, null, null),
                SkitCallbackRouteRegistryService.RouteLookup.tenant(
                        163L, 71L, 3, true, null));
    }

    @Test
    void malformedConfigurationFailsFast() {
        SkitAdCallbackKeyMapper mapper = mock(SkitAdCallbackKeyMapper.class);
        SkitCallbackRouteRegistryService registry =
                mock(SkitCallbackRouteRegistryService.class);

        assertThrows(IllegalArgumentException.class,
                () -> new SkitLegacyImpressionAliasResolver(mapper, registry, "not-a-hash"));
        assertThrows(IllegalArgumentException.class,
                () -> new SkitLegacyImpressionAliasResolver(mapper, registry,
                        hex(sha256(SOURCE_KEY)) + "=" + hex(sha256(SOURCE_KEY))));
        assertThrows(IllegalArgumentException.class,
                () -> new SkitLegacyImpressionAliasResolver(mapper, registry,
                        hex(sha256(SOURCE_KEY)) + "=" + hex(sha256(TARGET_KEY)) + ","
                                + hex(sha256(TARGET_KEY)) + "=" + hex(sha256(SOURCE_KEY))));
    }

    private static void assertRejected(
            SkitAdCallbackKeyDO sourceRow,
            SkitAdCallbackKeyDO targetRow,
            SkitCallbackRouteRegistryService.RouteLookup targetRoute) {
        byte[] sourceHash = sha256(SOURCE_KEY);
        byte[] targetHash = sha256(TARGET_KEY);
        SkitAdCallbackKeyMapper mapper = mock(SkitAdCallbackKeyMapper.class);
        SkitCallbackRouteRegistryService registry =
                mock(SkitCallbackRouteRegistryService.class);
        when(mapper.selectByHash(argThat(value -> same(value, sourceHash))))
                .thenReturn(sourceRow);
        when(mapper.selectByHash(argThat(value -> same(value, targetHash))))
                .thenReturn(targetRow);
        when(registry.lookupTenantReward(argThat(value -> same(value, targetHash)),
                org.mockito.ArgumentMatchers.eq(RECEIVED_AT))).thenReturn(targetRoute);
        SkitLegacyImpressionAliasResolver resolver = new SkitLegacyImpressionAliasResolver(
                mapper, registry, hex(sourceHash) + "=" + hex(targetHash));

        assertThrows(SkitCallbackRouteRegistryService.CallbackRouteRejectedException.class,
                () -> resolver.resolve(sourceHash, RECEIVED_AT));
    }

    private static SkitAdCallbackKeyDO key(long id, long tenantId, long accountId, int version,
                                            boolean active, LocalDateTime acceptUntil,
                                            LocalDateTime revokedAt) {
        SkitAdCallbackKeyDO row = new SkitAdCallbackKeyDO()
                .setId(id).setAdAccountId(accountId).setKeyVersion(version)
                .setActive(active).setAcceptUntil(acceptUntil).setRevokedAt(revokedAt);
        row.setTenantId(tenantId);
        return row;
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.US_ASCII));
        } catch (Exception unavailable) {
            throw new IllegalStateException(unavailable);
        }
    }

    private static boolean same(byte[] left, byte[] right) {
        return java.util.Arrays.equals(left, right);
    }

    private static String hex(byte[] value) {
        StringBuilder result = new StringBuilder(value.length * 2);
        for (byte item : value) {
            result.append(String.format("%02x", item & 0xff));
        }
        return result.toString();
    }
}
