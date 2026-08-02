package cn.iocoder.yudao.module.skit.service.provider;

import cn.iocoder.yudao.module.skit.service.ad.SkitCallbackPublicUrlService;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Verifies short-lived, route-bound operations evidence for every production lifecycle action. */
@Component
public final class SkitSignedProviderImpressionProductionGate
    implements SkitProviderImpressionProductionGate {

  private static final String DENIED_MESSAGE = "Production provider callback issuance is gated";
  private static final int MAX_MANIFEST_BYTES = 8 * 1024;
  private static final int MAX_FIELD_VALUE_LENGTH = 256;
  private static final int MAX_PUBLIC_KEY_BASE64_LENGTH = 4 * 1024;
  private static final int MAX_SIGNATURE_BASE64_LENGTH = 2 * 1024;
  private static final Duration MAX_EVIDENCE_LIFETIME = Duration.ofMinutes(15);
  private static final Duration MAX_FUTURE_SKEW = Duration.ofMinutes(1);
  private static final Pattern DIGEST = Pattern.compile("[0-9a-f]{64}");
  private static final Pattern POSITIVE_ID = Pattern.compile("[1-9][0-9]{0,18}");
  private static final Pattern SAFE_EVIDENCE_ID =
      Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{7,127}");
  private static final Pattern CANONICAL_INSTANT =
      Pattern.compile("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z");
  private static final Pattern CANONICAL_BASE64 =
      Pattern.compile("(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?");
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
  private static final List<String> EXPECTED_FIELDS = expectedFields();

  private final String environmentFingerprint;
  private final String operationsPublicKeyBase64;
  private final String configuredManifest;
  private final boolean configuredManifestIsBase64;
  private final String signatureBase64;
  private final SkitCallbackPublicUrlService callbackPublicUrlService;
  private final Clock clock;

  @Autowired
  public SkitSignedProviderImpressionProductionGate(
      @Value("${skit.ad.provider-impression-production-gate.environment-fingerprint:}")
          String environmentFingerprint,
      @Value("${skit.ad.provider-impression-production-gate.operations-public-key:}")
          String operationsPublicKeyBase64,
      @Value("${skit.ad.provider-impression-production-gate.manifest-base64:}")
          String manifestBase64,
      @Value("${skit.ad.provider-impression-production-gate.signature:}") String signatureBase64,
      SkitCallbackPublicUrlService callbackPublicUrlService) {
    this(
        environmentFingerprint,
        operationsPublicKeyBase64,
        manifestBase64,
        signatureBase64,
        callbackPublicUrlService,
        Clock.systemUTC(),
        true);
  }

  SkitSignedProviderImpressionProductionGate(
      String environmentFingerprint,
      String operationsPublicKeyBase64,
      String manifest,
      String signatureBase64,
      SkitCallbackPublicUrlService callbackPublicUrlService,
      Clock clock) {
    this(
        environmentFingerprint,
        operationsPublicKeyBase64,
        manifest,
        signatureBase64,
        callbackPublicUrlService,
        clock,
        false);
  }

  private SkitSignedProviderImpressionProductionGate(
      String environmentFingerprint,
      String operationsPublicKeyBase64,
      String configuredManifest,
      String signatureBase64,
      SkitCallbackPublicUrlService callbackPublicUrlService,
      Clock clock,
      boolean configuredManifestIsBase64) {
    this.environmentFingerprint = nullToEmpty(environmentFingerprint);
    this.operationsPublicKeyBase64 = nullToEmpty(operationsPublicKeyBase64);
    this.configuredManifest = nullToEmpty(configuredManifest);
    this.configuredManifestIsBase64 = configuredManifestIsBase64;
    this.signatureBase64 = nullToEmpty(signatureBase64);
    this.callbackPublicUrlService = callbackPublicUrlService;
    this.clock = clock;
  }

  @Override
  public void assertProductionIssueAllowed(
      long providerConnectionId, long providerRouteId, long actorUserId) {
    try {
      verify(providerConnectionId, providerRouteId, actorUserId);
    } catch (RuntimeException | GeneralSecurityException denied) {
      throw new IllegalStateException(DENIED_MESSAGE);
    }
  }

  private void verify(long providerConnectionId, long providerRouteId, long actorUserId)
      throws GeneralSecurityException {
    if (providerConnectionId <= 0
        || providerRouteId <= 0
        || actorUserId <= 0
        || callbackPublicUrlService == null
        || clock == null
        || !DIGEST.matcher(environmentFingerprint).matches()
        || operationsPublicKeyBase64.isEmpty()
        || configuredManifest.isEmpty()
        || signatureBase64.isEmpty()) {
      throw new IllegalArgumentException("Production gate configuration is incomplete");
    }
    if (!configuredManifestIsBase64
        && !StandardCharsets.US_ASCII.newEncoder().canEncode(configuredManifest)) {
      throw new IllegalArgumentException("Production gate manifest is not canonical");
    }
    byte[] manifestBytes =
        configuredManifestIsBase64
            ? decodeCanonicalBase64(configuredManifest, 12 * 1024)
            : configuredManifest.getBytes(StandardCharsets.US_ASCII);
    requireCanonicalManifestBytes(manifestBytes);
    String manifest = new String(manifestBytes, StandardCharsets.US_ASCII);
    if (manifestBytes.length == 0
        || manifestBytes.length > MAX_MANIFEST_BYTES
        || !manifest.endsWith("\n")) {
      throw new IllegalArgumentException("Production gate manifest is not canonical");
    }

    List<String> values = parseCanonicalManifest(manifest);
    requireValue(values, 0, "1");
    requireValue(values, 1, "RSA-SHA256");
    requireDigestEqual(values.get(2), environmentFingerprint);
    requireValue(values, 3, "PRODUCTION");
    requireId(values.get(4), providerConnectionId);
    requireId(values.get(5), providerRouteId);
    requireValue(values, 6, callbackPublicUrlService.getPublicBaseUrl());
    if (!callbackPublicUrlService.isHttps()) {
      throw new IllegalArgumentException("Production callback origin is not HTTPS");
    }
    requireValue(
        values, 7, Integer.toString(callbackPublicUrlService.providerImpressionPathVersion()));
    requireValue(
        values, 8, Integer.toString(callbackPublicUrlService.providerImpressionTemplateVersion()));

    byte[] expectedContract =
        callbackPublicUrlService.providerImpressionDeploymentContractFingerprint();
    byte[] suppliedContract = decodeHexDigest(values.get(9));
    try {
      if (!MessageDigest.isEqual(expectedContract, suppliedContract)) {
        throw new IllegalArgumentException("Callback deployment contract differs");
      }
    } finally {
      Arrays.fill(expectedContract, (byte) 0);
      Arrays.fill(suppliedContract, (byte) 0);
    }

    Instant issuedAt = parseInstant(values.get(10));
    Instant expiresAt = parseInstant(values.get(11));
    Instant now = clock.instant();
    if (!expiresAt.isAfter(issuedAt)
        || Duration.between(issuedAt, expiresAt).compareTo(MAX_EVIDENCE_LIFETIME) > 0
        || issuedAt.isAfter(now.plus(MAX_FUTURE_SKEW))
        || !expiresAt.isAfter(now)) {
      throw new IllegalArgumentException("Production gate evidence is outside its validity window");
    }
    if (!SAFE_EVIDENCE_ID.matcher(values.get(12)).matches()) {
      throw new IllegalArgumentException("Evidence id is invalid");
    }
    for (int index = 13; index < values.size(); index++) {
      decodeHexDigest(values.get(index));
    }

    PublicKey operationsPublicKey = decodePublicKey(operationsPublicKeyBase64);
    byte[] suppliedSignature = decodeCanonicalBase64(signatureBase64, MAX_SIGNATURE_BASE64_LENGTH);
    try {
      Signature verifier = Signature.getInstance("SHA256withRSA");
      verifier.initVerify(operationsPublicKey);
      verifier.update(manifestBytes);
      if (!verifier.verify(suppliedSignature)) {
        throw new IllegalArgumentException("Production gate signature is invalid");
      }
    } finally {
      Arrays.fill(manifestBytes, (byte) 0);
      Arrays.fill(suppliedSignature, (byte) 0);
    }
  }

  private static List<String> parseCanonicalManifest(String value) {
    String[] lines = value.split("\n", -1);
    if (lines.length != EXPECTED_FIELDS.size() + 1 || !lines[lines.length - 1].isEmpty()) {
      throw new IllegalArgumentException("Production gate manifest field count is invalid");
    }
    List<String> values = new ArrayList<>(EXPECTED_FIELDS.size());
    for (int index = 0; index < EXPECTED_FIELDS.size(); index++) {
      String prefix = EXPECTED_FIELDS.get(index) + "=";
      String line = lines[index];
      if (!line.startsWith(prefix)) {
        throw new IllegalArgumentException("Production gate manifest field order is invalid");
      }
      String fieldValue = line.substring(prefix.length());
      if (fieldValue.isEmpty()
          || fieldValue.length() > MAX_FIELD_VALUE_LENGTH
          || !isSafeAscii(fieldValue)) {
        throw new IllegalArgumentException("Production gate manifest field is unsafe");
      }
      values.add(fieldValue);
    }
    return values;
  }

  private static PublicKey decodePublicKey(String encoded) throws GeneralSecurityException {
    byte[] keyBytes = decodeCanonicalBase64(encoded, MAX_PUBLIC_KEY_BASE64_LENGTH);
    try {
      PublicKey key =
          KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(keyBytes));
      if (!(key instanceof RSAPublicKey) || ((RSAPublicKey) key).getModulus().bitLength() < 2048) {
        throw new IllegalArgumentException("Operations key is not an acceptable RSA key");
      }
      return key;
    } finally {
      Arrays.fill(keyBytes, (byte) 0);
    }
  }

  private static byte[] decodeCanonicalBase64(String encoded, int maximumLength) {
    if (encoded.isEmpty()
        || encoded.length() > maximumLength
        || !CANONICAL_BASE64.matcher(encoded).matches()) {
      throw new IllegalArgumentException("Base64 is not canonical");
    }
    byte[] decoded = Base64.getDecoder().decode(encoded);
    if (!MessageDigest.isEqual(
        encoded.getBytes(StandardCharsets.US_ASCII), Base64.getEncoder().encode(decoded))) {
      Arrays.fill(decoded, (byte) 0);
      throw new IllegalArgumentException("Base64 is not canonical");
    }
    return decoded;
  }

  private static void requireCanonicalManifestBytes(byte[] manifestBytes) {
    for (byte manifestByte : manifestBytes) {
      int unsigned = manifestByte & 0xff;
      if (unsigned != '\n' && (unsigned < 0x21 || unsigned > 0x7e)) {
        throw new IllegalArgumentException("Production gate manifest is not canonical");
      }
    }
  }

  private static byte[] decodeHexDigest(String value) {
    if (!DIGEST.matcher(value).matches()) {
      throw new IllegalArgumentException("Evidence digest is invalid");
    }
    byte[] decoded = new byte[32];
    for (int index = 0; index < decoded.length; index++) {
      decoded[index] = (byte) Integer.parseInt(value.substring(index * 2, index * 2 + 2), 16);
    }
    return decoded;
  }

  private static void requireDigestEqual(String actual, String expected) {
    byte[] actualBytes = decodeHexDigest(actual);
    byte[] expectedBytes = decodeHexDigest(expected);
    try {
      if (!MessageDigest.isEqual(actualBytes, expectedBytes)) {
        throw new IllegalArgumentException("Environment fingerprint differs");
      }
    } finally {
      Arrays.fill(actualBytes, (byte) 0);
      Arrays.fill(expectedBytes, (byte) 0);
    }
  }

  private static void requireId(String value, long expected) {
    if (!POSITIVE_ID.matcher(value).matches() || Long.parseLong(value) != expected) {
      throw new IllegalArgumentException("Route binding differs");
    }
  }

  private static Instant parseInstant(String value) {
    if (!CANONICAL_INSTANT.matcher(value).matches()) {
      throw new IllegalArgumentException("Evidence timestamp is not canonical");
    }
    return Instant.parse(value);
  }

  private static void requireValue(List<String> values, int index, String expected) {
    if (!values.get(index).equals(expected)) {
      throw new IllegalArgumentException("Production gate field differs");
    }
  }

  private static boolean isSafeAscii(String value) {
    for (int index = 0; index < value.length(); index++) {
      char character = value.charAt(index);
      if (character < 0x21 || character > 0x7e || character == '=') {
        return false;
      }
    }
    return true;
  }

  private static List<String> expectedFields() {
    List<String> result = new ArrayList<>();
    result.add("manifest_version");
    result.add("algorithm");
    result.add("environment_fingerprint");
    result.add("purpose");
    result.add("provider_connection_id");
    result.add("provider_route_id");
    result.add("accepted_origin");
    result.add("callback_path_version");
    result.add("callback_template_version");
    result.add("callback_contract_fingerprint");
    result.add("issued_at");
    result.add("expires_at");
    result.add("evidence_id");
    for (String check : REQUIRED_CHECKS) {
      result.add("check." + check);
    }
    return result;
  }

  private static String nullToEmpty(String value) {
    return value == null ? "" : value;
  }
}
