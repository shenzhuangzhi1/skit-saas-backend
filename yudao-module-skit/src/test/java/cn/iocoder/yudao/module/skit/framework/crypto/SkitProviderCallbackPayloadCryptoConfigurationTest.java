package cn.iocoder.yudao.module.skit.framework.crypto;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SkitProviderCallbackPayloadCryptoConfigurationTest {

    private static final byte[] CORRELATION = sequence(16, 0);
    private static final byte[] WIRE_HASH = sequence(32, 32);

    @Test
    void buildsOnlyTheDedicatedProviderWrapperAndRetainsHistoricalKeys() {
        SkitProviderCallbackPayloadCryptoProperties oldProperties = properties(
                "old", "abcdef0123456789abcdef0123456789");
        SkitProviderCallbackPayloadCryptoService oldWriter = configuration(oldProperties);
        SkitProviderCallbackPayloadCryptoService.Context context = context();
        byte[] plaintext = "provider-only".getBytes(StandardCharsets.US_ASCII);
        SkitProviderCallbackPayloadCryptoService.PayloadEnvelope oldEnvelope =
                oldWriter.encrypt(context, plaintext);

        SkitProviderCallbackPayloadCryptoProperties currentProperties = properties(
                "current", "0123456789abcdef0123456789abcdef");
        currentProperties.setKeys(Collections.singletonMap(
                "old", "abcdef0123456789abcdef0123456789"));
        SkitProviderCallbackPayloadCryptoService reader = configuration(currentProperties);

        assertArrayEquals(plaintext, reader.decrypt(context, oldEnvelope));
        context.close();
        oldEnvelope.close();
    }

    @Test
    void failsClosedForMissingConflictingNonAsciiOrWrongLengthProviderKeys() {
        SkitProviderCallbackPayloadCryptoProperties missing = new SkitProviderCallbackPayloadCryptoProperties();
        SkitProviderCallbackPayloadCryptoService missingService = configuration(missing);
        assertThrows(IllegalStateException.class,
                () -> missingService.encrypt(context(), new byte[0]));

        SkitProviderCallbackPayloadCryptoProperties conflict = properties(
                "current", "0123456789abcdef0123456789abcdef");
        conflict.setKeys(Collections.singletonMap(
                "current", "abcdef0123456789abcdef0123456789"));
        assertThrows(IllegalArgumentException.class, () -> configuration(conflict));

        assertThrows(IllegalArgumentException.class,
                () -> configuration(properties("current", "0123456789abcdef0123456789abc中")));
        assertThrows(IllegalArgumentException.class,
                () -> configuration(properties("current", "too-short")));
    }

    @Test
    void providerKeyringCannotReadTenantEnvelopeEvenWithTheSameKeyId() {
        SkitProviderCallbackPayloadCryptoProperties providerProperties = properties(
                "current", "0123456789abcdef0123456789abcdef");
        SkitProviderCallbackPayloadCryptoService provider = configuration(providerProperties);
        SkitAdCredentialCryptoService tenantCore = new SkitAesGcmCredentialCryptoService(
                "current", Collections.singletonMap("current",
                "abcdef0123456789abcdef0123456789".getBytes(StandardCharsets.US_ASCII)));
        SkitAdCredentialCryptoService.Context tenantContext =
                SkitAdCredentialCryptoService.Context.callbackPayload(71, 93, "TAKU_REWARD",
                        "reward:t-1", sequence(32, 11), 1);
        SkitAdCredentialCryptoService.EncryptedSecret tenantEnvelope = tenantCore.encrypt(
                tenantContext, "tenant".getBytes(StandardCharsets.US_ASCII));
        SkitProviderCallbackPayloadCryptoService.PayloadEnvelope rewritten =
                new SkitProviderCallbackPayloadCryptoService.PayloadEnvelope(
                        tenantEnvelope.getCiphertext(), tenantEnvelope.getNonce(),
                        tenantEnvelope.getKeyId(), tenantEnvelope.getEnvelopeVersion(),
                        SkitProviderCallbackPayloadCryptoService.PURPOSE);

        assertThrows(IllegalStateException.class, () -> provider.decrypt(context(), rewritten));
        tenantContext.close();
        rewritten.close();
    }

    @Test
    void propertiesNeverPrintKeyMaterial() {
        SkitProviderCallbackPayloadCryptoProperties properties = properties(
                "safe-id", "0123456789abcdef0123456789abcdef");
        properties.setKeys(Collections.singletonMap(
                "old", "abcdef0123456789abcdef0123456789"));
        assertFalse(properties.toString().contains("0123456789abcdef"));
        assertFalse(properties.toString().contains("abcdef0123456789"));
    }

    private static SkitProviderCallbackPayloadCryptoService configuration(
            SkitProviderCallbackPayloadCryptoProperties properties) {
        return new SkitProviderCallbackPayloadCryptoConfiguration()
                .skitProviderCallbackPayloadCryptoService(properties);
    }

    private static SkitProviderCallbackPayloadCryptoProperties properties(
            String keyId, String key) {
        SkitProviderCallbackPayloadCryptoProperties result =
                new SkitProviderCallbackPayloadCryptoProperties();
        result.setCurrentKeyId(keyId);
        result.setCurrentKey(key);
        return result;
    }

    private static SkitProviderCallbackPayloadCryptoService.Context context() {
        return SkitProviderCallbackPayloadCryptoService.Context.providerCallbackPayload(
                8811, CORRELATION, WIRE_HASH, 1);
    }

    private static byte[] sequence(int length, int seed) {
        byte[] result = new byte[length];
        for (int index = 0; index < result.length; index++) {
            result[index] = (byte) (seed + index);
        }
        return result;
    }
}
