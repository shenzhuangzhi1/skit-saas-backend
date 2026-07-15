package cn.iocoder.yudao.module.skit.service.ad;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkitCallbackPublicUrlServiceTest {

    @Test
    void usesOnlyConfiguredAbsoluteBaseAndAppendsFixedCallbackRoutes() {
        SkitCallbackPublicUrlService service =
                new SkitCallbackPublicUrlService("https://ads.example.com/app-api/");
        String callbackKey = "abcdefghijklmnopqrstuvwxyzABCDEFGH123456789";

        assertTrue(service.isHttps());
        assertEquals("https://ads.example.com/app-api", service.getPublicBaseUrl());
        assertTrue(service.rewardCallbackUrl(callbackKey).startsWith(
                "https://ads.example.com/app-api/skit/ad-callback/taku/" + callbackKey + "/reward?"));
        assertTrue(service.impressionCallbackUrl(callbackKey).startsWith(
                "https://ads.example.com/app-api/skit/ad-callback/taku/" + callbackKey + "/impression?"));
        assertFalse(service.rewardCallbackUrl(callbackKey).contains("Host"));
    }

    @Test
    void allowsHttpForOffAndShadowButMarksItNonHttps() {
        SkitCallbackPublicUrlService service =
                new SkitCallbackPublicUrlService("http://124.221.50.30/app-api");

        assertFalse(service.isHttps());
    }

    @Test
    void rejectsAmbiguousOrAttackerControlledBaseUrls() {
        assertThrows(IllegalArgumentException.class,
                () -> new SkitCallbackPublicUrlService("/app-api"));
        assertThrows(IllegalArgumentException.class,
                () -> new SkitCallbackPublicUrlService("https://user:pass@example.com/app-api"));
        assertThrows(IllegalArgumentException.class,
                () -> new SkitCallbackPublicUrlService("https://example.com/app-api?tenant=42"));
        assertThrows(IllegalArgumentException.class,
                () -> new SkitCallbackPublicUrlService("https://example.com/app-api#fragment"));
        assertThrows(IllegalArgumentException.class,
                () -> new SkitCallbackPublicUrlService("ftp://example.com/app-api"));
        assertThrows(IllegalArgumentException.class,
                () -> new SkitCallbackPublicUrlService("https://example.com/not-app-api"));
    }

}
