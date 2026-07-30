package cn.iocoder.yudao.module.skit.service.ad.callback;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PangleRewardSignatureVerifierTest {

    private static final byte[] SECURITY_KEY = "security-key".getBytes(StandardCharsets.UTF_8);

    private final PangleRewardCallbackCanonicalizer canonicalizer =
            new PangleRewardCallbackCanonicalizer();
    private final PangleRewardSignatureVerifier verifier = new PangleRewardSignatureVerifier();

    @Test
    void verifiesTheDocumentedSha256SecurityKeyColonTransactionExpression() {
        PangleRewardCallback callback = callback("tx-1", sha256Hex("security-key:tx-1"));

        assertTrue(verifier.verify(callback, SECURITY_KEY.clone()));
    }

    @Test
    void decodesUpperCaseHexAndRejectsTampering() {
        PangleRewardCallback upperCase = callback("tx-1",
                sha256Hex("security-key:tx-1").toUpperCase(Locale.ROOT));
        PangleRewardCallback changedTransaction = callback("tx-2", sha256Hex("security-key:tx-1"));
        PangleRewardCallback changedSignature = callback("tx-1", sha256Hex("other-key:tx-1"));

        assertTrue(verifier.verify(upperCase, SECURITY_KEY.clone()));
        assertFalse(verifier.verify(changedTransaction, SECURITY_KEY.clone()));
        assertFalse(verifier.verify(changedSignature, SECURITY_KEY.clone()));
    }

    @Test
    void implementationUsesDecodedDigestConstantTimeComparisonWithoutSecretStrings() throws Exception {
        Path source = locateVerifierSource();
        String java = new String(Files.readAllBytes(source), StandardCharsets.UTF_8);

        assertTrue(java.contains("MessageDigest.isEqual"));
        assertFalse(java.contains("new String(securityKey"));
        assertFalse(java.contains("securityKey.toString()"));
    }

    private PangleRewardCallback callback(String transactionId, String signature) {
        return canonicalizer.canonicalize("user_id=u1&trans_id=" + transactionId
                + "&reward_name=coin&reward_amount=1&extra=session&sign=" + signature);
    }

    private static String sha256Hex(String input) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte item : digest) {
                hex.append(String.format("%02x", item & 0xff));
            }
            return hex.toString();
        } catch (Exception ex) {
            throw new AssertionError(ex);
        }
    }

    private static Path locateVerifierSource() {
        Path workingDirectory = Paths.get("").toAbsolutePath();
        Path moduleRelative = workingDirectory.resolve("src/main/java/cn/iocoder/yudao/module/skit/"
                + "service/ad/callback/PangleRewardSignatureVerifier.java");
        if (Files.isRegularFile(moduleRelative)) {
            return moduleRelative;
        }
        return workingDirectory.resolve("yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/"
                + "service/ad/callback/PangleRewardSignatureVerifier.java");
    }
}
