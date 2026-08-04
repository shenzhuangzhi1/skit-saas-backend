package cn.iocoder.yudao.module.skit.service.ad.callback;

import cn.iocoder.yudao.module.skit.service.ad.callback.SkitCallbackRouteRegistryService.RouteLookup;
import cn.iocoder.yudao.module.skit.framework.observability.SkitProviderImpressionCaptureObservation;
import cn.iocoder.yudao.module.skit.service.provider.SkitProviderConnectionService;
import cn.iocoder.yudao.module.skit.service.provider.SkitProviderConnectionService.ProviderRouteResolution;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SkitTakuCallbackIngressDispatcherImplTest {

    private static final String KEY = "acct_abcdefghijklmnopqrstuvwxyzABCDEFGHIJKL";
    private static final Instant NOW = Instant.parse("2026-08-02T12:34:56Z");
    private static final LocalDateTime RECEIVED_AT =
            LocalDateTime.ofInstant(NOW, ZoneOffset.UTC);
    private static final String RAW_QUERY =
            "req_id=request-1&adsource_id=66&adsource_price=1.25&currency=CNY";
    private static final String AUDIT_KEY =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Test
    void taskFiveIngressContractsExist() {
        assertEquals(200, SkitTakuCallbackIngressDispatcher.DispatchResponse.ACK_200.getHttpStatus());
        assertEquals(601, SkitTakuCallbackIngressDispatcher.DispatchResponse
                .INVALID_SIGNATURE_601.getHttpStatus());
        assertEquals(602, SkitTakuCallbackIngressDispatcher.DispatchResponse.REJECT_602.getHttpStatus());
        assertEquals(503, SkitTakuCallbackIngressDispatcher.DispatchResponse.FAILURE_503.getHttpStatus());
    }

    @Test
    void requestMetadataIsBoundedDefensiveRedactedAndClearable() throws Exception {
        String sentinelIp = "203.0.113.7";
        String sentinelAccept = "application/sentinel";
        String sentinelUserAgent = "sentinel-agent";
        SkitCallbackRequestMetadata metadata = SkitCallbackRequestMetadata.of(
                sentinelIp, sentinelAccept, "gzip", "text/plain", sentinelUserAgent);

        byte[] firstAccept = metadata.getAccept();
        firstAccept[0] = 0;
        assertArrayEquals(sentinelAccept.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                metadata.getAccept());
        assertEquals(4, metadata.getPackedClientAddress().length);
        assertFalse(metadata.toString().contains(sentinelIp));
        assertFalse(metadata.toString().contains(sentinelAccept));
        assertFalse(metadata.toString().contains(sentinelUserAgent));
        assertEquals("\"<redacted>\"",
                new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(metadata));

        metadata.close();
        metadata.close();
        assertThrows(IllegalStateException.class, metadata::getPackedClientAddress);
        assertThrows(IllegalStateException.class, metadata::getUserAgent);
        assertThrows(IllegalArgumentException.class, () -> SkitCallbackRequestMetadata.of(
                sentinelIp, repeat('h', SkitCallbackRequestMetadata.MAX_HEADER_VALUE_BYTES + 1),
                null, null, null));
    }

    @Test
    void malformedKeyIsRejectedBeforeRateLimitRegistryOrPersistence() {
        Fixture fixture = fixture(AUDIT_KEY);
        SkitCallbackRequestMetadata metadata = metadata();

        assertEquals(SkitTakuCallbackIngressDispatcher.DispatchResponse.REJECT_602,
                fixture.dispatcher.dispatch(
                        SkitTakuCallbackIngressDispatcher.CallbackType.IMPRESSION,
                        "bad-key", RAW_QUERY, metadata));

        assertTrue(metadata.isClosed());
        verify(fixture.rateLimiter, never()).checkGlobalAddressHashed(any());
        verify(fixture.rateLimiter, never()).checkBusinessKeyHashed(any(), any(), any());
        verify(fixture.registryService, never()).lookup(any(), any(), any());
        verify(fixture.captureService, never()).capture(any(),
                any(SkitProviderImpressionWireParser.WirePayload.class), any(), any());
    }

    @Test
    void providerRewardUsesOneRegistryLookupAndNeverTouchesProviderLifecycleOrTenantReward() {
        Fixture fixture = fixture(AUDIT_KEY);
        AtomicReference<byte[]> lookedUpHash = new AtomicReference<>();
        when(fixture.registryService.lookup(any(byte[].class), eq(RECEIVED_AT), eq(RECEIVED_AT)))
                .thenAnswer(invocation -> {
                    lookedUpHash.set(((byte[]) invocation.getArgument(0)).clone());
                    return RouteLookup.provider(71L);
                });
        SkitCallbackRequestMetadata metadata = metadata();

        assertEquals(SkitTakuCallbackIngressDispatcher.DispatchResponse.REJECT_602,
                fixture.dispatcher.dispatch(
                        SkitTakuCallbackIngressDispatcher.CallbackType.REWARD,
                        KEY, RAW_QUERY, metadata));

        assertArrayEquals(sha256(KEY), lookedUpHash.get());
        assertTrue(metadata.isClosed());
        verify(fixture.registryService, times(1)).lookup(any(byte[].class), eq(RECEIVED_AT), eq(RECEIVED_AT));
        verify(fixture.providerConnectionService, never())
                .resolveProviderImpression(any(RouteLookup.class), any(LocalDateTime.class));
        verify(fixture.tenantIngressService, never()).receiveReward(any(), any(), any(), any());
        verify(fixture.captureService, never()).capture(any(),
                any(SkitProviderImpressionWireParser.WirePayload.class), any(), any());
    }

    @Test
    void acceptingProviderImpressionUsesRedisDegradationBoundAndMapsCommittedCaptureTo200() {
        Fixture fixture = fixture(AUDIT_KEY);
        RouteLookup lookup = RouteLookup.provider(72L);
        when(fixture.registryService.lookup(any(byte[].class), eq(RECEIVED_AT), eq(RECEIVED_AT)))
                .thenReturn(lookup);
        when(fixture.providerConnectionService.resolveProviderImpression(lookup, RECEIVED_AT))
                .thenReturn(providerRoute(41L, 72L, true));
        doThrow(new IllegalStateException("redis unavailable"))
                .when(fixture.rateLimiter).checkGlobalAddressHashed(any());
        doAnswer(invocation -> {
            SkitProviderImpressionWireParser.WirePayload wire = invocation.getArgument(1);
            SkitProviderImpressionCaptureService.ProviderIngressEvidence evidence =
                    invocation.getArgument(2);
            assertEquals(0D, metricCount(fixture.meters,
                    SkitProviderImpressionCaptureObservation.RESPONSE_COUNTER_NAME, "200"));
            assertFalse(wire.isClosed());
            assertFalse(evidence.isClosed());
            assertEquals("request-1", wire.getProviderRequestIdLexical());
            return SkitProviderImpressionCaptureService.CaptureDecision.ACK_200;
        }).when(fixture.captureService).capture(any(ProviderRouteResolution.class),
                any(SkitProviderImpressionWireParser.WirePayload.class), any(), eq(RECEIVED_AT));
        SkitCallbackRequestMetadata metadata = metadata();

        assertEquals(SkitTakuCallbackIngressDispatcher.DispatchResponse.ACK_200,
                fixture.dispatcher.dispatch(
                        SkitTakuCallbackIngressDispatcher.CallbackType.IMPRESSION,
                        KEY, RAW_QUERY, metadata));

        assertTrue(metadata.isClosed());
        verify(fixture.registryService, times(1)).lookup(any(byte[].class), eq(RECEIVED_AT), eq(RECEIVED_AT));
        verify(fixture.captureService, times(1)).capture(any(ProviderRouteResolution.class),
                any(SkitProviderImpressionWireParser.WirePayload.class), any(), eq(RECEIVED_AT));
        assertEquals(1D, metricCount(fixture.meters,
                SkitProviderImpressionCaptureObservation.RESPONSE_COUNTER_NAME, "200"));
        assertEquals(1D, metricCount(fixture.meters,
                SkitProviderImpressionCaptureObservation.EVENT_COUNTER_NAME, "redis_degraded"));
        assertEquals(RECEIVED_AT.toEpochSecond(ZoneOffset.UTC),
                lastAccepted(fixture.meters, "missing"));
        verify(fixture.rateLimiter, never()).checkBusinessKeyHashed(any(), any(), any());
    }

    @Test
    void localCapacityRejectIsA503AndEmitsOnlyTheBoundedCapacityDecision() {
        Fixture fixture = acceptingProviderFixture(AUDIT_KEY, 76L, 45L);
        SkitProviderConnectionCapacityGuard.Permit[] occupied =
                new SkitProviderConnectionCapacityGuard.Permit[4];
        try {
            for (int index = 0; index < occupied.length; index++) {
                occupied[index] = fixture.capacityGuard.tryAcquire(45L);
                assertTrue(occupied[index] != null);
            }

            assertEquals(SkitTakuCallbackIngressDispatcher.DispatchResponse.FAILURE_503,
                    fixture.dispatcher.dispatch(
                            SkitTakuCallbackIngressDispatcher.CallbackType.IMPRESSION,
                            KEY, RAW_QUERY + "&adformat=4", metadata()));

            verify(fixture.captureService, never()).capture(any(),
                    any(SkitProviderImpressionWireParser.WirePayload.class), any(), any());
            assertEquals(1D, metricCount(fixture.meters,
                    SkitProviderImpressionCaptureObservation.EVENT_COUNTER_NAME,
                    "capacity_reject"));
            assertEquals(1D, metricCount(fixture.meters,
                    SkitProviderImpressionCaptureObservation.RESPONSE_COUNTER_NAME, "503"));
        } finally {
            for (SkitProviderConnectionCapacityGuard.Permit permit : occupied) {
                if (permit != null) {
                    permit.close();
                }
            }
        }
    }

    @Test
    void validProviderKeyAcquiresConnectionCapacityBeforeParsingMalformedQuery() {
        Fixture fixture = acceptingProviderFixture(AUDIT_KEY, 77L, 46L);
        SkitProviderConnectionCapacityGuard.Permit[] occupied =
                new SkitProviderConnectionCapacityGuard.Permit[4];
        try {
            for (int index = 0; index < occupied.length; index++) {
                occupied[index] = fixture.capacityGuard.tryAcquire(46L);
                assertTrue(occupied[index] != null);
            }

            assertEquals(SkitTakuCallbackIngressDispatcher.DispatchResponse.FAILURE_503,
                    fixture.dispatcher.dispatch(
                            SkitTakuCallbackIngressDispatcher.CallbackType.IMPRESSION,
                            KEY, "%ZZ=would-fail-if-parsed", metadata()));

            verify(fixture.captureService, never()).capture(any(),
                    any(SkitProviderImpressionWireParser.WirePayload.class), any(), any());
            assertEquals(1D, metricCount(fixture.meters,
                    SkitProviderImpressionCaptureObservation.EVENT_COUNTER_NAME,
                    "capacity_reject"));
        } finally {
            for (SkitProviderConnectionCapacityGuard.Permit permit : occupied) {
                if (permit != null) {
                    permit.close();
                }
            }
        }
    }

    @Test
    void globalIpExhaustionStopsBeforeRegistryAndRedisFailureFailsClosedForUnknownAndTenant() {
        Fixture exceeded = fixture(AUDIT_KEY);
        doThrow(new SkitCallbackRateLimiter.RateLimitExceededException())
                .when(exceeded.rateLimiter).checkGlobalAddressHashed(any());
        assertEquals(SkitTakuCallbackIngressDispatcher.DispatchResponse.FAILURE_503,
                exceeded.dispatcher.dispatch(
                        SkitTakuCallbackIngressDispatcher.CallbackType.IMPRESSION,
                        KEY, RAW_QUERY, metadata()));
        verify(exceeded.registryService, never()).lookup(any(), any(), any());
        verify(exceeded.providerConnectionService, never())
                .resolveProviderImpression(any(RouteLookup.class), any(LocalDateTime.class));
        verify(exceeded.captureService, never()).capture(any(),
                any(SkitProviderImpressionWireParser.WirePayload.class), any(), any());

        Fixture unknown = fixture(AUDIT_KEY);
        doThrow(new IllegalStateException("redis unavailable"))
                .when(unknown.rateLimiter).checkGlobalAddressHashed(any());
        when(unknown.registryService.lookup(any(byte[].class), eq(RECEIVED_AT), eq(RECEIVED_AT)))
                .thenThrow(new SkitCallbackRouteRegistryService.CallbackRouteRejectedException());
        assertEquals(SkitTakuCallbackIngressDispatcher.DispatchResponse.FAILURE_503,
                unknown.dispatcher.dispatch(
                        SkitTakuCallbackIngressDispatcher.CallbackType.IMPRESSION,
                        KEY, RAW_QUERY, metadata()));

        Fixture tenant = fixture(AUDIT_KEY);
        doThrow(new IllegalStateException("redis unavailable"))
                .when(tenant.rateLimiter).checkBusinessKeyHashed(any(), any(), any());
        when(tenant.registryService.lookup(any(byte[].class), eq(RECEIVED_AT), eq(RECEIVED_AT)))
                .thenReturn(RouteLookup.tenant(3L, 4L, 5, true, null));
        assertEquals(SkitTakuCallbackIngressDispatcher.DispatchResponse.FAILURE_503,
                tenant.dispatcher.dispatch(
                        SkitTakuCallbackIngressDispatcher.CallbackType.REWARD,
                        KEY, RAW_QUERY, metadata()));
        verify(tenant.tenantIngressService, never()).receiveReward(any(), any(), any(), any());
    }

    @Test
    void redisFailureEmergencyCapacityStopsUnknownKeysBeforeRegistry() {
        Fixture fixture = fixture(AUDIT_KEY);
        doThrow(new IllegalStateException("redis unavailable"))
                .when(fixture.rateLimiter).checkGlobalAddressHashed(any());
        SkitProviderConnectionCapacityGuard.Permit[] occupied =
                new SkitProviderConnectionCapacityGuard.Permit[4];
        try {
            for (int index = 0; index < occupied.length; index++) {
                occupied[index] = fixture.capacityGuard.tryAcquireEmergency();
                assertTrue(occupied[index] != null);
            }

            assertEquals(SkitTakuCallbackIngressDispatcher.DispatchResponse.FAILURE_503,
                    fixture.dispatcher.dispatch(
                            SkitTakuCallbackIngressDispatcher.CallbackType.IMPRESSION,
                            KEY, RAW_QUERY, metadata()));

            verify(fixture.registryService, never()).lookup(any(), any(), any());
            assertEquals(1D, metricCount(fixture.meters,
                    SkitProviderImpressionCaptureObservation.EVENT_COUNTER_NAME,
                    "capacity_reject"));
        } finally {
            for (SkitProviderConnectionCapacityGuard.Permit permit : occupied) {
                if (permit != null) {
                    permit.close();
                }
            }
        }
    }

    @Test
    void validProviderImpressionUsesOnlyGlobalIpAndLocalAccountCapacity() {
        Fixture fixture = acceptingProviderFixture(AUDIT_KEY, 73L, 42L);
        when(fixture.captureService.capture(any(ProviderRouteResolution.class),
                any(SkitProviderImpressionWireParser.WirePayload.class), any(), eq(RECEIVED_AT)))
                .thenReturn(SkitProviderImpressionCaptureService.CaptureDecision.ACK_200);

        assertEquals(SkitTakuCallbackIngressDispatcher.DispatchResponse.ACK_200,
                fixture.dispatcher.dispatch(
                        SkitTakuCallbackIngressDispatcher.CallbackType.IMPRESSION,
                        KEY, RAW_QUERY, metadata()));

        verify(fixture.rateLimiter, times(1)).checkGlobalAddressHashed(any());
        verify(fixture.rateLimiter, never()).checkBusinessKeyHashed(any(), any(), any());
        verify(fixture.captureService, times(1)).capture(any(ProviderRouteResolution.class),
                any(SkitProviderImpressionWireParser.WirePayload.class), any(), eq(RECEIVED_AT));
    }

    @Test
    void preResolvedTenantRewardPreservesLiteral601WithoutASecondLookup() {
        Fixture fixture = fixture(AUDIT_KEY);
        RouteLookup lookup = RouteLookup.tenant(3L, 4L, 5, true, null);
        SkitCallbackRoutingService.CallbackRoute route =
                new SkitCallbackRoutingService.CallbackRoute(3L, 4L, 5, true, null);
        when(fixture.registryService.lookup(any(byte[].class), eq(RECEIVED_AT), eq(RECEIVED_AT)))
                .thenReturn(lookup);
        when(fixture.routingService.resolveTenantReward(lookup, RECEIVED_AT)).thenReturn(route);
        when(fixture.tenantIngressService.receiveReward(eq(route), eq(RAW_QUERY),
                any(SkitCallbackIngressService.TenantIngressEvidence.class), eq(RECEIVED_AT)))
                .thenReturn(SkitCallbackIngressService.IngressResponse.INVALID_SIGNATURE);

        assertEquals(SkitTakuCallbackIngressDispatcher.DispatchResponse.INVALID_SIGNATURE_601,
                fixture.dispatcher.dispatch(
                        SkitTakuCallbackIngressDispatcher.CallbackType.REWARD,
                        KEY, RAW_QUERY, metadata()));

        verify(fixture.registryService, times(1)).lookup(any(byte[].class), eq(RECEIVED_AT), eq(RECEIVED_AT));
        verify(fixture.rateLimiter, times(1))
                .checkBusinessKeyHashed(eq("TAKU"), any(byte[].class), eq("REWARD"));
        verify(fixture.routingService, times(1)).resolveTenantReward(lookup, RECEIVED_AT);
        verify(fixture.providerConnectionService, never())
                .resolveProviderImpression(any(RouteLookup.class), any(LocalDateTime.class));
    }

    @Test
    void tenantDispatchPreservesLegacyKeyAndIpv6AuditHashes() throws Exception {
        Fixture fixture = fixture(AUDIT_KEY);
        RouteLookup lookup = RouteLookup.tenant(3L, 4L, 5, true, null);
        SkitCallbackRoutingService.CallbackRoute route =
                new SkitCallbackRoutingService.CallbackRoute(3L, 4L, 5, true, null);
        when(fixture.registryService.lookup(any(byte[].class), eq(RECEIVED_AT), eq(RECEIVED_AT)))
                .thenReturn(lookup);
        when(fixture.routingService.resolveTenantReward(lookup, RECEIVED_AT)).thenReturn(route);
        AtomicReference<byte[]> actualKeyHash = new AtomicReference<>();
        AtomicReference<byte[]> actualIpHash = new AtomicReference<>();
        AtomicReference<byte[]> actualLimiterHash = new AtomicReference<>();
        doAnswer(invocation -> {
            byte[] hash = invocation.getArgument(1);
            actualLimiterHash.set(hash.clone());
            return null;
        }).when(fixture.rateLimiter).checkBusinessKeyHashed(
                eq("TAKU"), any(byte[].class), eq("REWARD"));
        when(fixture.tenantIngressService.receiveReward(eq(route), eq(RAW_QUERY), any(),
                eq(RECEIVED_AT))).thenAnswer(invocation -> {
                    SkitCallbackIngressService.TenantIngressEvidence evidence =
                            invocation.getArgument(2);
                    actualKeyHash.set(evidence.getCallbackKeyHash());
                    actualIpHash.set(evidence.getClientIpHash());
                    return SkitCallbackIngressService.IngressResponse.OK;
                });
        String canonicalIpv6 = "2001:db8::1";

        assertEquals(SkitTakuCallbackIngressDispatcher.DispatchResponse.ACK_200,
                fixture.dispatcher.dispatch(
                        SkitTakuCallbackIngressDispatcher.CallbackType.REWARD,
                        KEY, RAW_QUERY, metadata(canonicalIpv6)));

        assertArrayEquals(domainSha256("callback-key\0", KEY), actualKeyHash.get());
        assertArrayEquals(hmacSha256(KEY, "client-ip\0", canonicalIpv6), actualIpHash.get());
        assertArrayEquals(domainSha256("callback-key\0", KEY), actualLimiterHash.get());
        Arrays.fill(actualKeyHash.get(), (byte) 0);
        Arrays.fill(actualIpHash.get(), (byte) 0);
        Arrays.fill(actualLimiterHash.get(), (byte) 0);
    }

    @Test
    void tenantCompatibilityUsesDatabaseLocalTimeAtExpiryBoundaries() {
        ZoneId databaseZone = ZoneId.of("Asia/Shanghai");
        LocalDateTime databaseReceivedAt = LocalDateTime.ofInstant(NOW, databaseZone);
        Fixture fixture = fixture(AUDIT_KEY, databaseZone);
        when(fixture.registryService.lookup(
                any(byte[].class), eq(RECEIVED_AT), eq(databaseReceivedAt)))
                .thenThrow(new SkitCallbackRouteRegistryService.CallbackRouteRejectedException());

        assertEquals(SkitTakuCallbackIngressDispatcher.DispatchResponse.REJECT_602,
                fixture.dispatcher.dispatch(
                        SkitTakuCallbackIngressDispatcher.CallbackType.REWARD,
                        KEY, RAW_QUERY, metadata()));

        verify(fixture.registryService, times(1))
                .lookup(any(byte[].class), eq(RECEIVED_AT), eq(databaseReceivedAt));
        verify(fixture.registryService, never())
                .lookup(any(byte[].class), eq(RECEIVED_AT), eq(RECEIVED_AT));
        verify(fixture.tenantIngressService, never()).receiveReward(any(), any(), any(), any());
    }

    @Test
    void expiredOldKeyImpressionUsesPinnedAliasTargetVersion() {
        Fixture fixture = fixture(AUDIT_KEY);
        RouteLookup aliased = RouteLookup.tenant(163L, 71L, 2, true, null);
        AtomicReference<byte[]> aliasedHash = new AtomicReference<>();
        SkitCallbackRoutingService.CallbackRoute route =
                new SkitCallbackRoutingService.CallbackRoute(163L, 71L, 2, true, null);
        when(fixture.registryService.lookup(any(byte[].class), eq(RECEIVED_AT), eq(RECEIVED_AT)))
                .thenThrow(new SkitCallbackRouteRegistryService.CallbackRouteRejectedException());
        when(fixture.aliasResolver.resolve(any(byte[].class), eq(RECEIVED_AT)))
                .thenAnswer(invocation -> {
                    aliasedHash.set(((byte[]) invocation.getArgument(0)).clone());
                    return aliased;
                });
        when(fixture.routingService.resolveTenantReward(aliased, RECEIVED_AT)).thenReturn(route);
        when(fixture.tenantIngressService.receiveImpression(eq(route), eq(RAW_QUERY), any(),
                eq(RECEIVED_AT))).thenReturn(SkitCallbackIngressService.IngressResponse.OK);

        assertEquals(SkitTakuCallbackIngressDispatcher.DispatchResponse.ACK_200,
                fixture.dispatcher.dispatch(
                        SkitTakuCallbackIngressDispatcher.CallbackType.IMPRESSION,
                        KEY, RAW_QUERY, metadata()));

        assertArrayEquals(sha256(KEY), aliasedHash.get());
        verify(fixture.aliasResolver).resolve(any(byte[].class), eq(RECEIVED_AT));
        verify(fixture.tenantIngressService).receiveImpression(eq(route), eq(RAW_QUERY), any(),
                eq(RECEIVED_AT));
        verify(fixture.providerConnectionService, never())
                .resolveProviderImpression(any(RouteLookup.class), any(LocalDateTime.class));
    }

    @Test
    void expiredOldKeyRewardNeverUsesImpressionAlias() {
        Fixture fixture = fixture(AUDIT_KEY);
        when(fixture.registryService.lookup(any(byte[].class), eq(RECEIVED_AT), eq(RECEIVED_AT)))
                .thenThrow(new SkitCallbackRouteRegistryService.CallbackRouteRejectedException());

        assertEquals(SkitTakuCallbackIngressDispatcher.DispatchResponse.REJECT_602,
                fixture.dispatcher.dispatch(
                        SkitTakuCallbackIngressDispatcher.CallbackType.REWARD,
                        KEY, RAW_QUERY, metadata()));

        verify(fixture.aliasResolver, never()).resolve(any(byte[].class), any(LocalDateTime.class));
        verify(fixture.tenantIngressService, never()).receiveReward(any(), any(), any(), any());
    }

    @Test
    void currentTenantKeyBypassesImpressionAlias() {
        Fixture fixture = fixture(AUDIT_KEY);
        RouteLookup current = RouteLookup.tenant(163L, 71L, 2, true, null);
        SkitCallbackRoutingService.CallbackRoute route =
                new SkitCallbackRoutingService.CallbackRoute(163L, 71L, 2, true, null);
        when(fixture.registryService.lookup(any(byte[].class), eq(RECEIVED_AT), eq(RECEIVED_AT)))
                .thenReturn(current);
        when(fixture.routingService.resolveTenantReward(current, RECEIVED_AT)).thenReturn(route);
        when(fixture.tenantIngressService.receiveImpression(eq(route), eq(RAW_QUERY), any(),
                eq(RECEIVED_AT))).thenReturn(SkitCallbackIngressService.IngressResponse.OK);

        assertEquals(SkitTakuCallbackIngressDispatcher.DispatchResponse.ACK_200,
                fixture.dispatcher.dispatch(
                        SkitTakuCallbackIngressDispatcher.CallbackType.IMPRESSION,
                        KEY, RAW_QUERY, metadata()));

        verify(fixture.aliasResolver, never()).resolve(any(byte[].class), any(LocalDateTime.class));
        verify(fixture.tenantIngressService).receiveImpression(eq(route), eq(RAW_QUERY), any(),
                eq(RECEIVED_AT));
    }

    @Test
    void providerBoundaryFailureIs602AndMissingAuditKeyIs503WithoutCapture() {
        Fixture boundary = acceptingProviderFixture(AUDIT_KEY, 74L, 43L);
        assertEquals(SkitTakuCallbackIngressDispatcher.DispatchResponse.REJECT_602,
                boundary.dispatcher.dispatch(
                        SkitTakuCallbackIngressDispatcher.CallbackType.IMPRESSION,
                        KEY, "%ZZ=value", metadata()));
        verify(boundary.captureService, never()).capture(any(),
                any(SkitProviderImpressionWireParser.WirePayload.class), any(), any());

        Fixture missingAudit = acceptingProviderFixture("", 75L, 44L);
        assertEquals(SkitTakuCallbackIngressDispatcher.DispatchResponse.FAILURE_503,
                missingAudit.dispatcher.dispatch(
                        SkitTakuCallbackIngressDispatcher.CallbackType.IMPRESSION,
                        KEY, RAW_QUERY, metadata()));
        verify(missingAudit.captureService, never()).capture(any(),
                any(SkitProviderImpressionWireParser.WirePayload.class), any(), any());
    }

    private static Fixture acceptingProviderFixture(String auditKey, long routeId,
                                                     long connectionId) {
        Fixture fixture = fixture(auditKey);
        RouteLookup lookup = RouteLookup.provider(routeId);
        when(fixture.registryService.lookup(any(byte[].class), eq(RECEIVED_AT), eq(RECEIVED_AT)))
                .thenReturn(lookup);
        when(fixture.providerConnectionService.resolveProviderImpression(lookup, RECEIVED_AT))
                .thenReturn(providerRoute(connectionId, routeId, true));
        return fixture;
    }

    private static Fixture fixture(String auditKey) {
        return fixture(auditKey, ZoneOffset.UTC);
    }

    private static Fixture fixture(String auditKey, ZoneId tenantDatabaseZone) {
        SkitCallbackRouteRegistryService registry = mock(SkitCallbackRouteRegistryService.class);
        SkitLegacyImpressionAliasResolver aliasResolver =
                mock(SkitLegacyImpressionAliasResolver.class);
        SkitCallbackRoutingService routing = mock(SkitCallbackRoutingService.class);
        SkitCallbackIngressService tenantIngress = mock(SkitCallbackIngressService.class);
        SkitProviderConnectionService provider = mock(SkitProviderConnectionService.class);
        SkitProviderImpressionCaptureService capture =
                mock(SkitProviderImpressionCaptureService.class);
        SkitCallbackRateLimiter rateLimiter = mock(SkitCallbackRateLimiter.class);
        SkitProviderConnectionCapacityGuard capacity =
                new SkitProviderConnectionCapacityGuard(16, 4, 1000, 1000, System::nanoTime);
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        SkitProviderImpressionCaptureObservation observation =
                new SkitProviderImpressionCaptureObservation(meters);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        SkitProviderCallbackAuditFactory audit = new SkitProviderCallbackAuditFactory(
                auditKey, new FixedSecureRandom(), clock);
        SkitTakuCallbackIngressDispatcherImpl dispatcher =
                new SkitTakuCallbackIngressDispatcherImpl(registry, routing, tenantIngress,
                        provider, new SkitProviderImpressionWireParser(), capture, rateLimiter,
                        capacity, audit, observation, aliasResolver, clock, tenantDatabaseZone);
        return new Fixture(dispatcher, registry, aliasResolver, routing, tenantIngress,
                provider, capture, rateLimiter, capacity, meters);
    }

    private static SkitCallbackRequestMetadata metadata() {
        return metadata("203.0.113.77");
    }

    private static SkitCallbackRequestMetadata metadata(String clientIp) {
        return SkitCallbackRequestMetadata.of(clientIp,
                "application/json", "gzip", "text/plain", "provider-agent");
    }

    private static ProviderRouteResolution providerRoute(
            long connectionId, long routeId, boolean accepting) {
        try {
            Constructor<ProviderRouteResolution> constructor =
                    ProviderRouteResolution.class.getDeclaredConstructor(
                            long.class, long.class, boolean.class);
            constructor.setAccessible(true);
            return constructor.newInstance(connectionId, routeId, accepting);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.US_ASCII));
        } catch (Exception unavailable) {
            throw new IllegalStateException(unavailable);
        }
    }

    private static byte[] domainSha256(String domain, String value) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update(domain.getBytes(StandardCharsets.US_ASCII));
        return digest.digest(value.getBytes(StandardCharsets.US_ASCII));
    }

    private static byte[] hmacSha256(String key, String domain, String value) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.US_ASCII), "HmacSHA256"));
        mac.update(domain.getBytes(StandardCharsets.US_ASCII));
        return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String repeat(char value, int count) {
        char[] result = new char[count];
        java.util.Arrays.fill(result, value);
        return new String(result);
    }

    private static double metricCount(SimpleMeterRegistry registry, String name, String decision) {
        Counter counter = registry.find(name).tag("decision", decision).counter();
        return counter == null ? 0D : counter.count();
    }

    private static double lastAccepted(SimpleMeterRegistry registry, String format) {
        Gauge gauge = registry.find(SkitProviderImpressionCaptureObservation
                        .LAST_ACCEPTED_GAUGE_NAME)
                .tag("decision", "200").tag("format", format).gauge();
        return gauge == null ? 0D : gauge.value();
    }

    private static final class FixedSecureRandom extends SecureRandom {
        @Override
        public void nextBytes(byte[] bytes) {
            Arrays.fill(bytes, (byte) 7);
        }
    }

    private static final class Fixture {
        private final SkitTakuCallbackIngressDispatcherImpl dispatcher;
        private final SkitCallbackRouteRegistryService registryService;
        private final SkitLegacyImpressionAliasResolver aliasResolver;
        private final SkitCallbackRoutingService routingService;
        private final SkitCallbackIngressService tenantIngressService;
        private final SkitProviderConnectionService providerConnectionService;
        private final SkitProviderImpressionCaptureService captureService;
        private final SkitCallbackRateLimiter rateLimiter;
        private final SkitProviderConnectionCapacityGuard capacityGuard;
        private final SimpleMeterRegistry meters;

        private Fixture(SkitTakuCallbackIngressDispatcherImpl dispatcher,
                        SkitCallbackRouteRegistryService registryService,
                        SkitLegacyImpressionAliasResolver aliasResolver,
                        SkitCallbackRoutingService routingService,
                        SkitCallbackIngressService tenantIngressService,
                        SkitProviderConnectionService providerConnectionService,
                        SkitProviderImpressionCaptureService captureService,
                        SkitCallbackRateLimiter rateLimiter,
                        SkitProviderConnectionCapacityGuard capacityGuard,
                        SimpleMeterRegistry meters) {
            this.dispatcher = dispatcher;
            this.registryService = registryService;
            this.aliasResolver = aliasResolver;
            this.routingService = routingService;
            this.tenantIngressService = tenantIngressService;
            this.providerConnectionService = providerConnectionService;
            this.captureService = captureService;
            this.rateLimiter = rateLimiter;
            this.capacityGuard = capacityGuard;
            this.meters = meters;
        }
    }
}
