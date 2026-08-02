package cn.iocoder.yudao.module.skit.service.ad.callback;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SkitCallbackRoutingServiceTest {

    private SkitCallbackRouteRegistryService registryService;
    private SkitCallbackRoutingService routingService;

    @BeforeEach
    void setUp() {
        registryService = mock(SkitCallbackRouteRegistryService.class);
        routingService = new SkitCallbackRoutingService(registryService);
    }

    @Test
    void derivesExactlyOneImmutableTenantRouteWithoutRetainingRawKey() {
        String rawKey = repeat('A', 43);
        LocalDateTime receivedAt = LocalDateTime.of(2026, 7, 14, 23, 10);
        when(registryService.lookupTenantReward(
                org.mockito.ArgumentMatchers.any(byte[].class),
                org.mockito.ArgumentMatchers.eq(receivedAt))).thenReturn(
                SkitCallbackRouteRegistryService.RouteLookup.tenant(
                        17L, 29L, 4, false, receivedAt.plusMinutes(5)));

        SkitCallbackRoutingService.CallbackRoute route = routingService.resolve(rawKey, receivedAt);

        assertEquals(17L, route.getTenantId());
        assertEquals(29L, route.getAdAccountId());
        assertEquals(4, route.getCallbackKeyVersion());
        assertFalse(route.toString().contains(rawKey));
        assertEquals("CallbackRoute{tenantId=17, adAccountId=29, callbackKeyVersion=4}",
                route.toString());
    }

    @Test
    void rejectsCrossBoundaryOrMalformedCredentialResolution() {
        String rawKey = repeat('B', 43);
        LocalDateTime receivedAt = LocalDateTime.of(2026, 7, 14, 23, 10);
        when(registryService.lookupTenantReward(
                org.mockito.ArgumentMatchers.any(byte[].class),
                org.mockito.ArgumentMatchers.eq(receivedAt))).thenReturn(
                SkitCallbackRouteRegistryService.RouteLookup.tenant(
                        0L, 29L, 4, true, null));

        assertThrows(IllegalStateException.class,
                () -> routingService.resolve(rawKey, receivedAt));
    }

    private static String repeat(char value, int count) {
        char[] result = new char[count];
        java.util.Arrays.fill(result, value);
        return new String(result);
    }

}
