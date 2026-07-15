package cn.iocoder.yudao.module.skit.service.app;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkitRuntimeUpdateManifestVerifierTest {

    private KeyPair tenantAKeyPair;
    private KeyPair tenantBKeyPair;
    private String tenantAPublicKey;
    private String tenantBPublicKey;
    private SkitRuntimeUpdateManifestVerifier verifier;

    @BeforeEach
    void setUp() throws Exception {
        tenantAKeyPair = generateRsaKeyPair(2048);
        tenantBKeyPair = generateRsaKeyPair(2048);
        tenantAPublicKey = encodePublicKey(tenantAKeyPair);
        tenantBPublicKey = encodePublicKey(tenantBKeyPair);
        verifier = new SkitRuntimeUpdateManifestVerifier();
    }

    @Test
    void tenantTrustRootsVerifyOnlyTheirOwnSignedManifest() throws Exception {
        String tenantASignature = sign(tenantAKeyPair, "AGENT42", "top.neoshen.agent42",
                repeat('a', 64), 1, 42L);
        String tenantBSignature = sign(tenantBKeyPair, "AGENT43", "top.neoshen.agent43",
                repeat('b', 64), 2, 43L);

        assertDoesNotThrow(() -> verifier.verify(tenantAPublicKey,
                "AGENT42", "top.neoshen.agent42", repeat('a', 64), 1, 42L,
                tenantASignature));
        assertDoesNotThrow(() -> verifier.verify(tenantBPublicKey,
                "AGENT43", "top.neoshen.agent43", repeat('b', 64), 2, 43L,
                tenantBSignature));
        assertThrows(SecurityException.class, () -> verifier.verify(tenantBPublicKey,
                "AGENT42", "top.neoshen.agent42", repeat('a', 64), 1, 42L,
                tenantASignature));
        assertThrows(SecurityException.class, () -> verifier.verify(tenantAPublicKey,
                "AGENT43", "top.neoshen.agent43", repeat('b', 64), 2, 43L,
                tenantBSignature));

        String tenantAFingerprint = verifier.validateAndFingerprint(tenantAPublicKey);
        String tenantBFingerprint = verifier.validateAndFingerprint(tenantBPublicKey);
        assertTrue(tenantAFingerprint.matches("[0-9a-f]{64}"));
        assertTrue(tenantBFingerprint.matches("[0-9a-f]{64}"));
        assertNotEquals(tenantAFingerprint, tenantBFingerprint);
    }

    @Test
    void rejectsMissingMalformedAndWeakTenantTrustRoots() throws Exception {
        assertThrows(SecurityException.class, () -> verifier.validateAndFingerprint(""));
        assertThrows(SecurityException.class, () -> verifier.validateAndFingerprint("not-base64"));
        assertThrows(SecurityException.class, () -> verifier.validateAndFingerprint(
                encodePublicKey(generateRsaKeyPair(1024))));
    }

    @Test
    void rejectsSignatureReplayAcrossEverySignedScopeField() throws Exception {
        String signature = sign(tenantAKeyPair, "AGENT42", "top.neoshen.agent42",
                repeat('a', 64), 1, 42L);

        assertThrows(SecurityException.class, () -> verifier.verify(tenantAPublicKey,
                "AGENT43", "top.neoshen.agent42", repeat('a', 64), 1, 42L, signature));
        assertThrows(SecurityException.class, () -> verifier.verify(tenantAPublicKey,
                "AGENT42", "top.neoshen.other", repeat('a', 64), 1, 42L, signature));
        assertThrows(SecurityException.class, () -> verifier.verify(tenantAPublicKey,
                "AGENT42", "top.neoshen.agent42", repeat('b', 64), 1, 42L, signature));
        assertThrows(SecurityException.class, () -> verifier.verify(tenantAPublicKey,
                "AGENT42", "top.neoshen.agent42", repeat('a', 64), 2, 42L, signature));
        assertThrows(SecurityException.class, () -> verifier.verify(tenantAPublicKey,
                "AGENT42", "top.neoshen.agent42", repeat('a', 64), 1, 43L, signature));
    }

    private static KeyPair generateRsaKeyPair(int bits) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(bits);
        return generator.generateKeyPair();
    }

    private static String encodePublicKey(KeyPair keyPair) {
        return Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());
    }

    private static String sign(KeyPair keyPair, String tenantId, String applicationId,
                               String sha256, int protocolVersion, long releaseNo) throws Exception {
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(keyPair.getPrivate());
        signer.update(SkitRuntimeUpdateManifestVerifier.canonical(
                tenantId, applicationId, sha256, protocolVersion, releaseNo)
                .getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(signer.sign());
    }

    private static String repeat(char value, int count) {
        char[] characters = new char[count];
        java.util.Arrays.fill(characters, value);
        return new String(characters);
    }
}
