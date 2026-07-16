package cn.iocoder.yudao.module.skit.framework.crypto;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SkitAdCredentialCryptoServiceTest {

    private static final byte[] KEY = "0123456789abcdef0123456789abcdef"
            .getBytes(StandardCharsets.US_ASCII);

    @Test
    void appBuildMaterialEnvelopeIsBoundToTenantAndMaterialVersion() {
        SkitAdCredentialCryptoService crypto = new SkitAesGcmCredentialCryptoService(
                "primary", Collections.singletonMap("primary", KEY));
        byte[] plaintext = "build-material-secret".getBytes(StandardCharsets.UTF_8);

        SkitAdCredentialCryptoService.Context context =
                SkitAdCredentialCryptoService.Context.appBuildMaterial(10L, 1, 1);
        SkitAdCredentialCryptoService.EncryptedSecret envelope = crypto.encrypt(context, plaintext);

        assertArrayEquals(plaintext, crypto.decrypt(context, envelope));
        assertThrows(IllegalStateException.class, () -> crypto.decrypt(
                SkitAdCredentialCryptoService.Context.appBuildMaterial(11L, 1, 1), envelope));
        assertThrows(IllegalStateException.class, () -> crypto.decrypt(
                SkitAdCredentialCryptoService.Context.appBuildMaterial(10L, 2, 1), envelope));
    }

    @Test
    void rewardSecretContextRemainsUsableAfterAddingBuildMaterialPurpose() {
        SkitAdCredentialCryptoService crypto = new SkitAesGcmCredentialCryptoService(
                "primary", Collections.singletonMap("primary", KEY));
        byte[] plaintext = "reward-secret".getBytes(StandardCharsets.UTF_8);
        SkitAdCredentialCryptoService.Context context =
                SkitAdCredentialCryptoService.Context.rewardSecret(10L, 20L, 3, 1);
        SkitAdCredentialCryptoService.EncryptedSecret envelope = crypto.encrypt(context, plaintext);

        assertArrayEquals(plaintext, crypto.decrypt(context, envelope));
    }
}
