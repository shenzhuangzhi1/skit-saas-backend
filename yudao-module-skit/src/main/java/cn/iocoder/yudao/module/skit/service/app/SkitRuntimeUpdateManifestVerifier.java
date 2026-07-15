package cn.iocoder.yudao.module.skit.service.app;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Locale;
import java.util.regex.Pattern;

/** Verifies a tenant manifest against the trust root embedded in that tenant's APK. */
@Component
public class SkitRuntimeUpdateManifestVerifier {

    private static final Pattern TENANT_CODE = Pattern.compile("[A-Z0-9_-]{3,32}");
    private static final Pattern APPLICATION_ID = Pattern.compile(
            "[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern PUBLIC_KEY_BASE64 = Pattern.compile("[A-Za-z0-9+/]+={0,2}");
    private static final Pattern SIGNATURE_BASE64 = Pattern.compile("[A-Za-z0-9+/]+={0,2}");

    public String validateAndFingerprint(String encodedPublicKey) {
        ParsedTrustRoot trustRoot = parseTrustRoot(encodedPublicKey);
        try {
            return toHex(MessageDigest.getInstance("SHA-256").digest(trustRoot.encoded));
        } catch (Exception unavailableDigest) {
            throw new SecurityException("Runtime update public key fingerprint could not be calculated",
                    unavailableDigest);
        }
    }

    public void verify(String encodedPublicKey, String tenantId, String applicationId, String bundleSha256,
                       int protocolVersion, long releaseNo, String encodedSignature) {
        RSAPublicKey publicKey = parseTrustRoot(encodedPublicKey).publicKey;
        validateFields(tenantId, applicationId, bundleSha256, protocolVersion, releaseNo);
        if (encodedSignature == null || encodedSignature.length() < 344
                || encodedSignature.length() > 1024 || encodedSignature.length() % 4 != 0
                || !SIGNATURE_BASE64.matcher(encodedSignature).matches()) {
            throw new SecurityException("Runtime update signature encoding is invalid");
        }
        try {
            byte[] signatureBytes = Base64.getDecoder().decode(encodedSignature);
            int expectedSignatureBytes = (publicKey.getModulus().bitLength() + 7) / 8;
            if (signatureBytes.length != expectedSignatureBytes) {
                throw new SecurityException("Runtime update signature size is invalid");
            }
            Signature verifier = Signature.getInstance("SHA256withRSA");
            verifier.initVerify(publicKey);
            verifier.update(canonical(tenantId, applicationId, bundleSha256,
                    protocolVersion, releaseNo).getBytes(StandardCharsets.UTF_8));
            if (!verifier.verify(signatureBytes)) {
                throw new SecurityException("Runtime update signature is invalid");
            }
        } catch (SecurityException invalidSignature) {
            throw invalidSignature;
        } catch (Exception verificationFailure) {
            throw new SecurityException("Runtime update signature could not be verified",
                    verificationFailure);
        }
    }

    private static ParsedTrustRoot parseTrustRoot(String encodedPublicKey) {
        String normalized = encodedPublicKey == null ? "" : encodedPublicKey.trim();
        if (normalized.length() < 128 || normalized.length() > 4096
                || normalized.length() % 4 != 0
                || !PUBLIC_KEY_BASE64.matcher(normalized).matches()) {
            throw new SecurityException("Runtime update trust root encoding is invalid");
        }
        try {
            byte[] encoded = Base64.getDecoder().decode(normalized);
            PublicKey candidate = KeyFactory.getInstance("RSA")
                    .generatePublic(new X509EncodedKeySpec(encoded));
            if (!(candidate instanceof RSAPublicKey)
                    || ((RSAPublicKey) candidate).getModulus().bitLength() < 2048) {
                throw new SecurityException("Runtime update RSA public key must be at least 2048 bits");
            }
            return new ParsedTrustRoot((RSAPublicKey) candidate, encoded);
        } catch (SecurityException invalidKey) {
            throw invalidKey;
        } catch (Exception invalidKey) {
            throw new SecurityException("Runtime update trust root is not a valid X.509 RSA public key",
                    invalidKey);
        }
    }

    public static String canonical(String tenantId, String applicationId, String bundleSha256,
                                   int protocolVersion, long releaseNo) {
        return "SKIT_RUNTIME_UPDATE_V1\n"
                + "tenantId=" + tenantId + '\n'
                + "applicationId=" + applicationId + '\n'
                + "bundleSha256=" + bundleSha256 + '\n'
                + "protocolVersion=" + protocolVersion + '\n'
                + "releaseNo=" + releaseNo + '\n';
    }

    private static void validateFields(String tenantId, String applicationId, String bundleSha256,
                                       int protocolVersion, long releaseNo) {
        if (tenantId == null || !TENANT_CODE.matcher(tenantId).matches()
                || applicationId == null || !APPLICATION_ID.matcher(applicationId).matches()
                || bundleSha256 == null || !SHA256.matcher(bundleSha256).matches()
                || protocolVersion <= 0 || releaseNo <= 0) {
            throw new SecurityException("Runtime update signed scope is invalid");
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        }
        return result.toString();
    }

    private static final class ParsedTrustRoot {

        private final RSAPublicKey publicKey;
        private final byte[] encoded;

        private ParsedTrustRoot(RSAPublicKey publicKey, byte[] encoded) {
            this.publicKey = publicKey;
            this.encoded = encoded;
        }
    }
}
