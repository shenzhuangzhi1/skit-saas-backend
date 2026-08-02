package cn.iocoder.yudao.module.skit.service.ad;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class SkitCallbackPublicUrlServiceTest {

  @Test
  void usesOnlyConfiguredAbsoluteBaseAndAppendsFixedCallbackRoutes() {
    SkitCallbackPublicUrlService service =
        new SkitCallbackPublicUrlService("https://ads.example.com/app-api/");
    String callbackKey = "abcdefghijklmnopqrstuvwxyzABCDEFGH123456789";

    assertTrue(service.isHttps());
    assertEquals("https://ads.example.com/app-api", service.getPublicBaseUrl());
    assertTrue(
        service
            .rewardCallbackUrl(callbackKey)
            .startsWith(
                "https://ads.example.com/app-api/skit/ad-callback/taku/"
                    + callbackKey
                    + "/reward?"));
    assertTrue(
        service
            .impressionCallbackUrl(callbackKey)
            .startsWith(
                "https://ads.example.com/app-api/skit/ad-callback/taku/"
                    + callbackKey
                    + "/impression?"));
    assertEquals(
        "https://ads.example.com/app-api/skit/ad-callback/pangle/" + callbackKey + "/reward",
        service.pangleRewardCallbackUrl(callbackKey));
    assertFalse(service.rewardCallbackUrl(callbackKey).contains("Host"));
  }

  @Test
  void allowsHttpForOffAndShadowButMarksItNonHttps() {
    SkitCallbackPublicUrlService service =
        new SkitCallbackPublicUrlService("http://124.221.50.30/app-api");

    assertFalse(service.isHttps());
  }

  @Test
  void providerImpressionUrlRequiresTheAccountKeyNamespace() {
    SkitCallbackPublicUrlService service =
        new SkitCallbackPublicUrlService("https://ads.example.com/app-api");
    char[] key = "acct_abcdefghijklmnopqrstuvwxyzABCDEFGHIJKL".toCharArray();
    char[] url = service.providerImpressionCallbackUrl(key);
    try {
      assertTrue(
          new String(url)
              .startsWith("https://ads.example.com/app-api/skit/ad-callback/taku/acct_"));
    } finally {
      Arrays.fill(key, '\0');
      Arrays.fill(url, '\0');
    }
    assertThrows(
        IllegalArgumentException.class,
        () ->
            service.providerImpressionCallbackUrl(
                "abcdeabcdefghijklmnopqrstuvwxyzABCDEFGHIJKL".toCharArray()));
  }

  @Test
  void providerContractFingerprintIsStableAndBindsOriginAndOrderedTemplate() {
    byte[] keyHash = new byte[32];
    Arrays.fill(keyHash, (byte) 7);
    byte[] first =
        new SkitCallbackPublicUrlService("https://ads.example.com/app-api")
            .providerImpressionContractFingerprint(keyHash);
    byte[] second =
        new SkitCallbackPublicUrlService("https://ads.example.com/app-api")
            .providerImpressionContractFingerprint(keyHash);
    byte[] changedOrigin =
        new SkitCallbackPublicUrlService("https://other.example.com/app-api")
            .providerImpressionContractFingerprint(keyHash);
    char[] origin = "https://ads.example.com/app-api".toCharArray();
    char[] template = SkitCallbackPublicUrlService.canonicalProviderImpressionTemplate();
    char[] reorderedTemplate = "?req_id={req_id}&user_id={user_id}".toCharArray();
    byte[] changedPathVersion =
        SkitCallbackPublicUrlService.providerContractFingerprint(keyHash, origin, 2, 1, template);
    byte[] changedTemplateVersion =
        SkitCallbackPublicUrlService.providerContractFingerprint(keyHash, origin, 1, 2, template);
    byte[] changedMacroOrder =
        SkitCallbackPublicUrlService.providerContractFingerprint(
            keyHash, origin, 1, 1, reorderedTemplate);
    try {
      assertArrayEquals(first, second);
      assertFalse(Arrays.equals(first, changedOrigin));
      assertFalse(Arrays.equals(first, changedPathVersion));
      assertFalse(Arrays.equals(first, changedTemplateVersion));
      assertFalse(Arrays.equals(first, changedMacroOrder));
    } finally {
      Arrays.fill(keyHash, (byte) 0);
      Arrays.fill(first, (byte) 0);
      Arrays.fill(second, (byte) 0);
      Arrays.fill(changedOrigin, (byte) 0);
      Arrays.fill(changedPathVersion, (byte) 0);
      Arrays.fill(changedTemplateVersion, (byte) 0);
      Arrays.fill(changedMacroOrder, (byte) 0);
      Arrays.fill(origin, '\0');
      Arrays.fill(template, '\0');
      Arrays.fill(reorderedTemplate, '\0');
    }
  }

  @Test
  void providerDeploymentContractFingerprintIsStableWithoutDependingOnAnUnissuedKey() {
    SkitCallbackPublicUrlService firstService =
        new SkitCallbackPublicUrlService("https://ads.example.com/app-api");
    SkitCallbackPublicUrlService sameService =
        new SkitCallbackPublicUrlService("https://ads.example.com/app-api");
    SkitCallbackPublicUrlService changedOriginService =
        new SkitCallbackPublicUrlService("https://other.example.com/app-api");

    byte[] first = firstService.providerImpressionDeploymentContractFingerprint();
    byte[] same = sameService.providerImpressionDeploymentContractFingerprint();
    byte[] changedOrigin = changedOriginService.providerImpressionDeploymentContractFingerprint();
    char[] origin = "https://ads.example.com/app-api".toCharArray();
    char[] template = SkitCallbackPublicUrlService.canonicalProviderImpressionTemplate();
    byte[] changedPathVersion =
        SkitCallbackPublicUrlService.providerDeploymentContractFingerprint(origin, 2, 1, template);
    byte[] changedTemplateVersion =
        SkitCallbackPublicUrlService.providerDeploymentContractFingerprint(origin, 1, 2, template);
    char[] reorderedTemplate = "?req_id={req_id}&user_id={user_id}".toCharArray();
    byte[] changedTemplate =
        SkitCallbackPublicUrlService.providerDeploymentContractFingerprint(
            origin, 1, 1, reorderedTemplate);
    try {
      assertArrayEquals(first, same);
      assertFalse(Arrays.equals(first, changedOrigin));
      assertFalse(Arrays.equals(first, changedPathVersion));
      assertFalse(Arrays.equals(first, changedTemplateVersion));
      assertFalse(Arrays.equals(first, changedTemplate));
    } finally {
      Arrays.fill(first, (byte) 0);
      Arrays.fill(same, (byte) 0);
      Arrays.fill(changedOrigin, (byte) 0);
      Arrays.fill(changedPathVersion, (byte) 0);
      Arrays.fill(changedTemplateVersion, (byte) 0);
      Arrays.fill(changedTemplate, (byte) 0);
      Arrays.fill(origin, '\0');
      Arrays.fill(template, '\0');
      Arrays.fill(reorderedTemplate, '\0');
    }
  }

  @Test
  void rejectsAmbiguousOrAttackerControlledBaseUrls() {
    assertThrows(
        IllegalArgumentException.class, () -> new SkitCallbackPublicUrlService("/app-api"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new SkitCallbackPublicUrlService("https://user:pass@example.com/app-api"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new SkitCallbackPublicUrlService("https://example.com/app-api?tenant=42"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new SkitCallbackPublicUrlService("https://example.com/app-api#fragment"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new SkitCallbackPublicUrlService("ftp://example.com/app-api"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new SkitCallbackPublicUrlService("https://example.com/not-app-api"));
  }
}
