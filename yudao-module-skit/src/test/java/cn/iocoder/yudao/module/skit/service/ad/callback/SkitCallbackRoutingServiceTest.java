package cn.iocoder.yudao.module.skit.service.ad.callback;

import cn.iocoder.yudao.module.skit.service.ad.SkitAdCredentialVersionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SkitCallbackRoutingServiceTest {

    private SkitAdCredentialVersionService credentialService;
    private SkitCallbackRoutingService routingService;

    @BeforeEach
    void setUp() {
        credentialService = mock(SkitAdCredentialVersionService.class);
        routingService = new SkitCallbackRoutingService(credentialService);
    }

    @Test
    void derivesExactlyOneImmutableTenantRouteWithoutRetainingRawKey() {
        String rawKey = repeat('A', 43);
        LocalDateTime receivedAt = LocalDateTime.of(2026, 7, 14, 23, 10);
        when(credentialService.resolveCallbackKey(rawKey, receivedAt)).thenReturn(
                new SkitAdCredentialVersionService.CallbackKeyResolution(
                        17L, 29L, 4, false, receivedAt.plusMinutes(5)));

        SkitCallbackRoutingService.CallbackRoute route = routingService.resolve(rawKey, receivedAt);

        assertEquals(17L, route.getTenantId());
        assertEquals(29L, route.getAdAccountId());
        assertEquals(4, route.getCallbackKeyVersion());
        assertFalse(route.toString().contains(rawKey));
    }

    @Test
    void rejectsCrossBoundaryOrMalformedCredentialResolution() {
        String rawKey = repeat('B', 43);
        LocalDateTime receivedAt = LocalDateTime.of(2026, 7, 14, 23, 10);
        when(credentialService.resolveCallbackKey(rawKey, receivedAt)).thenReturn(
                new SkitAdCredentialVersionService.CallbackKeyResolution(
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
