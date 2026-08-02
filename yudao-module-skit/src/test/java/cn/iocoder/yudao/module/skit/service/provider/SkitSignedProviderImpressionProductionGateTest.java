package cn.iocoder.yudao.module.skit.service.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.iocoder.yudao.module.skit.service.ad.SkitCallbackPublicUrlService;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.stereotype.Component;

class SkitSignedProviderImpressionProductionGateTest {

  private static final Instant NOW = Instant.parse("2026-08-03T00:05:00Z");
  private static final String ENVIRONMENT_FINGERPRINT =
      "1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef";
  private static final long CONNECTION_ID = 41L;
  private static final long ROUTE_ID = 42L;
  private static final long ACTOR_ID = 43L;
  private static final String ORIGIN = "https://ads.example.com/app-api";
  private static final String FIXTURE_PUBLIC_KEY_BASE64 =
      "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAy+RP87RXn6QxjM8MEM/Z"
          + "U3kXtwwaMrfigDMBHmlsCCXbpGvHumqX3FIlCTYPLmaimeQ4gmIDLjMLxZhiyRoG"
          + "59vszhxdUyXQSegFi22WzO3+u/9Bde1qH7ylkZUaOO9irJtqYg7S5J1jvdGYOMMt"
          + "aHfvOXAn1csO3dtcr0wZ0cgulOFNnlxkI7+6JyxWP0c+ccwRDbB4N5HEheTfScRI"
          + "u8oHtVU6H8WjH+1qCmpE2kcEprG99vaKaGMLPjY5bUGHIwnebL4Y0rsToL2RKE5x"
          + "wnf1XiyjE+z1b61ZnX7/rJ8WL9d81+FmW68jpguoTcZnqg15tvCAFT3GU5ydeog6"
          + "TwIDAQAB";
  private static final String FIXTURE_PRIVATE_KEY_BASE64 =
      "MIIEvgIBADANBgkqhkiG9w0BAQEFAASCBKgwggSkAgEAAoIBAQDL5E/ztFefpDGM"
          + "zwwQz9lTeRe3DBoyt+KAMwEeaWwIJduka8e6apfcUiUJNg8uZqKZ5DiCYgMuMwvF"
          + "mGLJGgbn2+zOHF1TJdBJ6AWLbZbM7f67/0F17WofvKWRlRo472Ksm2piDtLknWO9"
          + "0Zg4wy1od+85cCfVyw7d21yvTBnRyC6U4U2eXGQjv7onLFY/Rz5xzBENsHg3kcSF"
          + "5N9JxEi7yge1VTofxaMf7WoKakTaRwSmsb329opoYws+NjltQYcjCd5svhjSuxOg"
          + "vZEoTnHCd/VeLKMT7PVvrVmdfv+snxYv13zX4WZbryOmC6hNxmeqDXm28IAVPcZT"
          + "nJ16iDpPAgMBAAECggEACPzqjVqw1ie9y3BBHuiasvROmFIfQYcbo/0bmoSZL47D"
          + "rO7XYh9azGqOmWZZqvj5SAFogE7jxwCXD0HVcPoFvejS+o8DiivLj8Z7oBbXo/cF"
          + "2LG66ADsQbPmNJG3EzVySxuax/HRWCScBf0cUbxA3y8SS5znFKSb5inKXyvio320"
          + "W5F5ipzniNd7gtYPp7UZYREtvLHNe443aplZkkOsz61TP1VLcv+WTDvnnbo0M1HD"
          + "9mybEfP0rzTFwqFqOurmA/JVMF6999bP7bFsEHOT7PTljitOMgO9hrsWUyTKybyV"
          + "lQiTFWxVj/XTCiwA+5S0QYfX1niGyJnPc6t42TYUKQKBgQD7iPm0ha8Za7ss4SJu"
          + "L3J3npwZJGsRGD9H1I8wGnoI1YSUTC3VqBlOTa9q65xXfnaBqcGtwTYl7oT3Xfr2"
          + "mKZNOA2Kkn/lB4g+4OC9APDWIfqSGT9QzmuiwI98LUONie5gjVss9RR+8c1mVpXF"
          + "F32YJFKMVaFVioCdYctVMm1j1QKBgQDPgtY4abelet+/IkcBVjX58HfwTUQlhIsz"
          + "g/xv3IEurXV8Nq+FDPsrdI+1yD8XUHmlnlah1qsoYf2JsbHWEvpQFapwJWRdNnBC"
          + "frOlSgtuYy+hNAO1OQQ+WaPuE7YAk6M2VUppwsZCZFqEgqyi0/PvUDMkHU/DkETR"
          + "+USFRlbLkwKBgFadERcHXYcMYLSQhZGZhvoHxMqnjrKhPdEXMYMn6tO16mRCYxoK"
          + "O0SKXZgcr4Y1RstZUBbrzCB9pI2lb/CQGdvHLGqGDcIouNKmDt0mQMgLhUPfq7zA"
          + "h7HQSthAod1QHKRkqHuvnLIlw0u1DveCIj5Ma5xlNBvHiIgpD6IzrhVNAoGBALef"
          + "BwCUS7VarXLsWfiM2OZ6Kyewt4SbbLIBMPerH4S2aSepHFeXzQn+8svgjjOLOpmb"
          + "Kv26f2oUi820N7E+ydDj2xWxgMYazeuJQl4Yz1S24Aa9iCpscZzapXyeZIbOHbaO"
          + "cnJzsAw/0PlAyJKtC0XQqfBjH5nlp4BGGqP0QgmRAoGBAL0G5quviZqBUDGpJJhI"
          + "z3cFUbMGoLarfE6MKgjC6zoFdRuKMofUkWolXrfDG3t1PqPgkInDEV5YOLLX1ewF"
          + "j9/gOtGzLFOkEdf7IcFVzrSKAVCu1+cDfyX/gFfx3PKI5vhH9XBu1UV2j+1PLIDs"
          + "FUQcg9QmU88UawL0dJYACXeg";
  private static final String FIXTURE_VALID_SIGNATURE =
      "kts91gOtIHOeZJxKuNcFIFu5s3Ri7u1IDYaHVT3wQ0qnrQLo0dGV70I4Mco6/+UO8"
          + "rfZjHSYNIFfRYSZCs20uPkHCuIKxvq3J1WNAVr0BEofRxlXz63DOPvNaXDlMq1ML"
          + "tkEIGjH0l/Cokvk5uAdwfuwrA4zg27THJJwQmIwh9nrCq4TnBn234vrc7Ucv2ETO"
          + "/mcnLD1kriro6EuIY+nD3TW7hs9QMlntfG9IbDJbZtVHBdVbuuHdliKjuylJYOnG"
          + "bKf/4gEm1uHsbAsC/vtanbzLJoEMgJe+t/P1lAD0sYk7+nJOOTWpOnv983SqtUND"
          + "9hr/gEWzIVzCV0vgDpuBw==";
  private static final List<String> REQUIRED_CHECKS =
      Arrays.asList(
          "https_route",
          "inbox_attempt_200",
          "unknown_key_602",
          "log_redaction",
          "db_failpoint_503",
          "load_p99",
          "accepted_origin_contract",
          "dual_entry",
          "two_backend_instances",
          "mysql_ha",
          "redis_degradation",
          "dns_cert_backup",
          "key_custody");

  private static KeyPair operationsKeyPair;

  @BeforeAll
  static void loadFixedFixtureOperationsKey() throws Exception {
    KeyFactory factory = KeyFactory.getInstance("RSA");
    operationsKeyPair =
        new KeyPair(
            factory.generatePublic(
                new X509EncodedKeySpec(Base64.getDecoder().decode(FIXTURE_PUBLIC_KEY_BASE64))),
            factory.generatePrivate(
                new PKCS8EncodedKeySpec(Base64.getDecoder().decode(FIXTURE_PRIVATE_KEY_BASE64))));
  }

  @Test
  void missingEvidenceDoesNotPreventBeanConstructionButDeniesProductionUse() {
    SkitSignedProviderImpressionProductionGate gate =
        new SkitSignedProviderImpressionProductionGate(
            ENVIRONMENT_FINGERPRINT,
            "",
            "",
            "",
            new SkitCallbackPublicUrlService(ORIGIN),
            fixedClock());

    assertThatThrownBy(() -> gate.assertProductionIssueAllowed(CONNECTION_ID, ROUTE_ID, ACTOR_ID))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Production provider callback issuance is gated");
  }

  @Test
  void validCanonicalSignedEvidenceAllowsOnlyItsBoundRoute() throws Exception {
    String manifest = validManifest();
    assertThat(sign(manifest)).isEqualTo(FIXTURE_VALID_SIGNATURE);
    SkitSignedProviderImpressionProductionGate gate = gate(manifest, FIXTURE_VALID_SIGNATURE);

    assertThatCode(() -> gate.assertProductionIssueAllowed(CONNECTION_ID, ROUTE_ID, ACTOR_ID))
        .doesNotThrowAnyException();
    assertThatThrownBy(
            () -> gate.assertProductionIssueAllowed(CONNECTION_ID, ROUTE_ID + 1, ACTOR_ID))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Production provider callback issuance is gated");
  }

  @Test
  void springConstructorDecodesCanonicalManifestBase64() throws Exception {
    Instant wallClockNow = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    String manifest =
        replace(
            replace(
                validManifest(),
                "issued_at=2026-08-03T00:00:00Z",
                "issued_at=" + wallClockNow.minusSeconds(30)),
            "expires_at=2026-08-03T00:10:00Z",
            "expires_at=" + wallClockNow.plusSeconds(300));
    SkitSignedProviderImpressionProductionGate gate =
        new SkitSignedProviderImpressionProductionGate(
            ENVIRONMENT_FINGERPRINT,
            FIXTURE_PUBLIC_KEY_BASE64,
            Base64.getEncoder().encodeToString(manifest.getBytes(StandardCharsets.US_ASCII)),
            sign(manifest),
            new SkitCallbackPublicUrlService(ORIGIN));

    assertThatCode(() -> gate.assertProductionIssueAllowed(CONNECTION_ID, ROUTE_ID, ACTOR_ID))
        .doesNotThrowAnyException();
  }

  @Test
  void theSameGateRechecksExpiryOnEveryLifecycleCall() {
    AtomicReference<Instant> currentTime = new AtomicReference<>(NOW);
    Clock mutableClock =
        new Clock() {
          @Override
          public ZoneId getZone() {
            return ZoneOffset.UTC;
          }

          @Override
          public Clock withZone(ZoneId zone) {
            return this;
          }

          @Override
          public Instant instant() {
            return currentTime.get();
          }
        };
    SkitSignedProviderImpressionProductionGate gate =
        gate(validManifest(), FIXTURE_VALID_SIGNATURE, mutableClock);

    assertThatCode(() -> gate.assertProductionIssueAllowed(CONNECTION_ID, ROUTE_ID, ACTOR_ID))
        .doesNotThrowAnyException();
    currentTime.set(Instant.parse("2026-08-03T00:10:00Z"));
    assertDenied(gate);
  }

  @Test
  void signedSemanticTamperingOfEveryBoundFieldIsRejected() throws Exception {
    List<String> invalidManifests =
        Arrays.asList(
            replace(validManifest(), "manifest_version=1", "manifest_version=2"),
            replace(validManifest(), "algorithm=RSA-SHA256", "algorithm=RSA-SHA512"),
            replace(
                validManifest(),
                "environment_fingerprint=" + ENVIRONMENT_FINGERPRINT,
                "environment_fingerprint=" + repeat('a', 64)),
            replace(validManifest(), "purpose=PRODUCTION", "purpose=GATE_TEST"),
            replace(
                validManifest(),
                "provider_connection_id=" + CONNECTION_ID,
                "provider_connection_id=99"),
            replace(validManifest(), "provider_route_id=" + ROUTE_ID, "provider_route_id=99"),
            replace(
                validManifest(),
                "accepted_origin=" + ORIGIN,
                "accepted_origin=https://other.test/app-api"),
            replace(validManifest(), "callback_path_version=1", "callback_path_version=2"),
            replace(validManifest(), "callback_template_version=1", "callback_template_version=2"),
            replace(
                validManifest(),
                "callback_contract_fingerprint=" + deploymentContractFingerprint(),
                "callback_contract_fingerprint=" + repeat('b', 64)),
            replace(validManifest(), "evidence_id=evidence-00000001", "evidence_id=bad id"));

    for (String invalidManifest : invalidManifests) {
      assertDenied(gate(invalidManifest, sign(invalidManifest)));
    }
  }

  @Test
  void expiryFutureIssueAndExcessiveLifetimeAreRejectedEvenWhenSigned() throws Exception {
    String expired =
        replace(
            validManifest(), "expires_at=2026-08-03T00:10:00Z", "expires_at=2026-08-03T00:04:59Z");
    String future =
        replace(
            validManifest(), "issued_at=2026-08-03T00:00:00Z", "issued_at=2026-08-03T00:06:01Z");
    String excessive =
        replace(
            validManifest(), "expires_at=2026-08-03T00:10:00Z", "expires_at=2026-08-03T00:20:01Z");

    assertDenied(gate(expired, sign(expired)));
    assertDenied(gate(future, sign(future)));
    assertDenied(gate(excessive, sign(excessive)));
  }

  @Test
  void missingDuplicateUnknownOrUnsafeFieldsAndChecksAreRejectedWhenSigned() throws Exception {
    String missingCheck = removeLine(validManifest(), "check.mysql_ha=");
    String duplicateCheck =
        validManifest() + "\ncheck.mysql_ha=" + evidenceDigest("duplicate") + "\n";
    String unknownField = validManifest() + "\nforce=true\n";
    String unsafeText =
        replace(validManifest(), "evidence_id=evidence-00000001", "evidence_id=unsafe\rvalue");

    assertDenied(gate(missingCheck, sign(missingCheck)));
    assertDenied(gate(duplicateCheck, sign(duplicateCheck)));
    assertDenied(gate(unknownField, sign(unknownField)));
    assertDenied(gate(unsafeText, sign(unsafeText)));
  }

  @Test
  void nonCanonicalOrTamperedSignatureIsRejected() throws Exception {
    String manifest = validManifest();
    String signature = sign(manifest);
    char replacement = signature.charAt(0) == 'A' ? 'B' : 'A';
    String tampered = replacement + signature.substring(1);

    assertDenied(gate(manifest, tampered));
    assertDenied(gate(manifest, " " + signature));
    assertDenied(gate(manifest, signature.replace("=", "")));
  }

  @Test
  void decodedManifestMustRemainStrictAscii() {
    String invalidManifestBase64 =
        Base64.getEncoder().encodeToString(new byte[] {(byte) 0xff, (byte) '\n'});
    SkitSignedProviderImpressionProductionGate gate =
        new SkitSignedProviderImpressionProductionGate(
            ENVIRONMENT_FINGERPRINT,
            FIXTURE_PUBLIC_KEY_BASE64,
            invalidManifestBase64,
            FIXTURE_VALID_SIGNATURE,
            new SkitCallbackPublicUrlService(ORIGIN));

    assertDenied(gate);
  }

  @Test
  void tamperingAnyCanonicalFieldOrCheckWithoutResigningIsRejected() {
    String manifest = validManifest();
    String[] lines = manifest.split("\\n");
    for (String line : lines) {
      int separator = line.indexOf('=');
      String value = line.substring(separator + 1);
      char replacement = value.charAt(0) == 'a' ? 'b' : 'a';
      String tamperedLine = line.substring(0, separator + 1) + replacement + value.substring(1);
      assertDenied(gate(replace(manifest, line, tamperedLine), FIXTURE_VALID_SIGNATURE));
    }
  }

  @Test
  void signedGateIsTheOnlyProductionComponentForTheInterface() {
    assertThat(
            SkitSignedProviderImpressionProductionGate.class.isAnnotationPresent(Component.class))
        .isTrue();
    assertThat(
            DefaultSkitProviderImpressionProductionGate.class.isAnnotationPresent(Component.class))
        .isFalse();
  }

  private static SkitSignedProviderImpressionProductionGate gate(
      String manifest, String signature) {
    return gate(manifest, signature, fixedClock());
  }

  private static SkitSignedProviderImpressionProductionGate gate(
      String manifest, String signature, Clock clock) {
    return new SkitSignedProviderImpressionProductionGate(
        ENVIRONMENT_FINGERPRINT,
        Base64.getEncoder().encodeToString(operationsKeyPair.getPublic().getEncoded()),
        manifest,
        signature,
        new SkitCallbackPublicUrlService(ORIGIN),
        clock);
  }

  private static void assertDenied(SkitSignedProviderImpressionProductionGate gate) {
    assertThatThrownBy(() -> gate.assertProductionIssueAllowed(CONNECTION_ID, ROUTE_ID, ACTOR_ID))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Production provider callback issuance is gated");
  }

  private static String validManifest() {
    List<String> lines = new ArrayList<>();
    lines.add("manifest_version=1");
    lines.add("algorithm=RSA-SHA256");
    lines.add("environment_fingerprint=" + ENVIRONMENT_FINGERPRINT);
    lines.add("purpose=PRODUCTION");
    lines.add("provider_connection_id=" + CONNECTION_ID);
    lines.add("provider_route_id=" + ROUTE_ID);
    lines.add("accepted_origin=" + ORIGIN);
    lines.add("callback_path_version=1");
    lines.add("callback_template_version=1");
    lines.add("callback_contract_fingerprint=" + deploymentContractFingerprint());
    lines.add("issued_at=2026-08-03T00:00:00Z");
    lines.add("expires_at=2026-08-03T00:10:00Z");
    lines.add("evidence_id=evidence-00000001");
    for (String check : REQUIRED_CHECKS) {
      lines.add("check." + check + "=" + evidenceDigest(check));
    }
    return String.join("\n", lines) + "\n";
  }

  private static String deploymentContractFingerprint() {
    byte[] value =
        new SkitCallbackPublicUrlService(ORIGIN).providerImpressionDeploymentContractFingerprint();
    try {
      return hex(value);
    } finally {
      Arrays.fill(value, (byte) 0);
    }
  }

  private static String sign(String manifest) throws Exception {
    return sign(manifest, operationsKeyPair.getPrivate());
  }

  private static String sign(String manifest, PrivateKey privateKey) throws Exception {
    Signature signer = Signature.getInstance("SHA256withRSA");
    signer.initSign(privateKey);
    signer.update(manifest.getBytes(StandardCharsets.UTF_8));
    return Base64.getEncoder().encodeToString(signer.sign());
  }

  private static String evidenceDigest(String value) {
    try {
      return hex(
          MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.US_ASCII)));
    } catch (Exception unavailable) {
      throw new IllegalStateException(unavailable);
    }
  }

  private static String hex(byte[] value) {
    StringBuilder result = new StringBuilder(value.length * 2);
    for (byte current : value) {
      result.append(String.format("%02x", current & 0xff));
    }
    return result.toString();
  }

  private static String replace(String source, String expected, String replacement) {
    if (!source.contains(expected)) {
      throw new IllegalArgumentException("Fixture field is missing: " + expected);
    }
    return source.replace(expected, replacement);
  }

  private static String removeLine(String source, String prefix) {
    return Arrays.stream(source.split("\\n"))
            .filter(line -> !line.startsWith(prefix))
            .collect(java.util.stream.Collectors.joining("\n"))
        + "\n";
  }

  private static String repeat(char value, int count) {
    char[] result = new char[count];
    Arrays.fill(result, value);
    return new String(result);
  }

  private static Clock fixedClock() {
    return Clock.fixed(NOW, ZoneOffset.UTC);
  }
}
