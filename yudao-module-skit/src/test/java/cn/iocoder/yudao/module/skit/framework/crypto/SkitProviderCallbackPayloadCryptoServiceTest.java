package cn.iocoder.yudao.module.skit.framework.crypto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkitProviderCallbackPayloadCryptoServiceTest {

    private static final long CONNECTION_ID = 8811L;
    private static final byte[] CORRELATION_ID = sequence(16, 0);
    private static final byte[] WIRE_HASH = sequence(32, 32);
    private static final byte[] CURRENT_KEY = "0123456789abcdef0123456789abcdef"
            .getBytes(StandardCharsets.US_ASCII);
    private static final byte[] OLD_KEY = "abcdef0123456789abcdef0123456789"
            .getBytes(StandardCharsets.US_ASCII);
    private static final byte[] FIXED_NONCE = sequence(12, 0);

    private SkitAdCredentialCryptoService credentialCrypto;
    private SkitProviderCallbackPayloadCryptoService payloadCrypto;

    @BeforeEach
    void setUp() {
        Map<String, byte[]> keys = new HashMap<>();
        keys.put("current", CURRENT_KEY);
        keys.put("old", OLD_KEY);
        credentialCrypto = new SkitAesGcmCredentialCryptoService("current", keys,
                new FixedSecureRandom(FIXED_NONCE));
        payloadCrypto = new SkitProviderCallbackPayloadCryptoService(credentialCrypto);
    }

    @Test
    void encryptsTheProviderAadFixedVectorWithASeparatePurpose() {
        byte[] plaintext = "req_id=r-1&adsource_id=42".getBytes(StandardCharsets.US_ASCII);

        SkitProviderCallbackPayloadCryptoService.PayloadEnvelope envelope =
                payloadCrypto.encrypt(context(CONNECTION_ID, CORRELATION_ID, WIRE_HASH, 1), plaintext);

        assertEquals("X4DKI7B8796F7VffFby+cZRrZthV2Em/EnBss4wpLOc33LmybBs9Gts=",
                Base64.getEncoder().encodeToString(envelope.getCiphertext()));
        assertArrayEquals(FIXED_NONCE, envelope.getNonce());
        assertEquals("current", envelope.getKeyId());
        assertEquals(1, envelope.getEnvelopeVersion());
        assertEquals("PROVIDER_CALLBACK_PAYLOAD", envelope.getPurpose());
        assertArrayEquals(plaintext, payloadCrypto.decrypt(
                context(CONNECTION_ID, CORRELATION_ID, WIRE_HASH, 1), envelope));
    }

    @Test
    void acceptsEmptyProviderWireAndKeepsEnvelopeAndContextRedacted() throws Exception {
        SkitProviderCallbackPayloadCryptoService.Context context =
                context(CONNECTION_ID, CORRELATION_ID, WIRE_HASH, 1);

        SkitProviderCallbackPayloadCryptoService.PayloadEnvelope envelope =
                payloadCrypto.encrypt(context, new byte[0]);

        assertEquals(16, envelope.getCiphertext().length,
                "empty plaintext still produces the GCM authentication tag");
        assertArrayEquals(new byte[0], payloadCrypto.decrypt(context, envelope));
        assertEquals(12, envelope.getNonce().length);
        assertFalse(context.toString().contains(Base64.getEncoder().encodeToString(CORRELATION_ID)));
        assertFalse(context.toString().contains(Base64.getEncoder().encodeToString(WIRE_HASH)));
        assertFalse(envelope.toString().contains(Base64.getEncoder()
                .encodeToString(envelope.getCiphertext())));
        String json = new ObjectMapper().writeValueAsString(envelope);
        assertFalse(json.contains("ciphertext"));
        assertFalse(json.contains("nonce"));

        byte[] exposedCiphertext = envelope.getCiphertext();
        byte[] exposedNonce = envelope.getNonce();
        exposedCiphertext[0] ^= 1;
        exposedNonce[0] ^= 1;
        assertArrayEquals(new byte[0], payloadCrypto.decrypt(context, envelope));
    }

    @Test
    void authenticatesEveryProviderDimensionAndTheActualKeyId() {
        byte[] plaintext = "provider-wire".getBytes(StandardCharsets.US_ASCII);
        SkitProviderCallbackPayloadCryptoService.Context original =
                context(CONNECTION_ID, CORRELATION_ID, WIRE_HASH, 1);
        SkitProviderCallbackPayloadCryptoService.PayloadEnvelope envelope =
                payloadCrypto.encrypt(original, plaintext);

        assertAuthenticationFailure(context(CONNECTION_ID + 1, CORRELATION_ID, WIRE_HASH, 1), envelope);

        byte[] wrongCorrelation = CORRELATION_ID.clone();
        wrongCorrelation[0] ^= 1;
        assertAuthenticationFailure(context(CONNECTION_ID, wrongCorrelation, WIRE_HASH, 1), envelope);

        byte[] wrongWireHash = WIRE_HASH.clone();
        wrongWireHash[0] ^= 1;
        assertAuthenticationFailure(context(CONNECTION_ID, CORRELATION_ID, wrongWireHash, 1), envelope);

        SkitProviderCallbackPayloadCryptoService.PayloadEnvelope rewrittenVersion =
                new SkitProviderCallbackPayloadCryptoService.PayloadEnvelope(
                        envelope.getCiphertext(), envelope.getNonce(), envelope.getKeyId(),
                        2, envelope.getPurpose());
        assertAuthenticationFailure(context(CONNECTION_ID, CORRELATION_ID, WIRE_HASH, 2), rewrittenVersion);

        Map<String, byte[]> aliases = new HashMap<>();
        aliases.put("current", CURRENT_KEY);
        aliases.put("same-key-alias", CURRENT_KEY);
        SkitProviderCallbackPayloadCryptoService aliasReader =
                new SkitProviderCallbackPayloadCryptoService(
                        new SkitAesGcmCredentialCryptoService("current", aliases,
                                new FixedSecureRandom(FIXED_NONCE)));
        SkitProviderCallbackPayloadCryptoService.PayloadEnvelope tamperedKeyId =
                new SkitProviderCallbackPayloadCryptoService.PayloadEnvelope(
                        envelope.getCiphertext(), envelope.getNonce(), "same-key-alias",
                        envelope.getEnvelopeVersion(), envelope.getPurpose());
        assertThrows(IllegalStateException.class,
                () -> aliasReader.decrypt(original, tamperedKeyId));
    }

    @Test
    void providerPurposeCannotBeSubstitutedForTenantCallbackOrCredentialPurpose() {
        byte[] plaintext = "purpose-bound".getBytes(StandardCharsets.US_ASCII);
        SkitProviderCallbackPayloadCryptoService.Context providerContext =
                context(CONNECTION_ID, CORRELATION_ID, WIRE_HASH, 1);
        SkitProviderCallbackPayloadCryptoService.PayloadEnvelope providerEnvelope =
                payloadCrypto.encrypt(providerContext, plaintext);
        SkitAdCredentialCryptoService.EncryptedSecret coreProviderEnvelope =
                new SkitAdCredentialCryptoService.EncryptedSecret(providerEnvelope.getCiphertext(),
                        providerEnvelope.getNonce(), providerEnvelope.getKeyId(),
                        providerEnvelope.getEnvelopeVersion());

        assertThrows(IllegalStateException.class, () -> credentialCrypto.decrypt(
                SkitAdCredentialCryptoService.Context.callbackPayload(71, 93, "TAKU_REWARD",
                        "reward:t-1", sequence(32, 11), 1), coreProviderEnvelope));
        assertThrows(IllegalStateException.class, () -> credentialCrypto.decrypt(
                SkitAdCredentialCryptoService.Context.rewardSecret(71, 93, 1, 1),
                coreProviderEnvelope));

        SkitAdCredentialCryptoService.Context tenantContext =
                SkitAdCredentialCryptoService.Context.callbackPayload(71, 93, "TAKU_REWARD",
                        "reward:t-1", sequence(32, 11), 1);
        SkitAdCredentialCryptoService.EncryptedSecret tenantEnvelope =
                credentialCrypto.encrypt(tenantContext, plaintext);
        assertAuthenticationFailure(providerContext,
                new SkitProviderCallbackPayloadCryptoService.PayloadEnvelope(
                        tenantEnvelope.getCiphertext(), tenantEnvelope.getNonce(),
                        tenantEnvelope.getKeyId(), tenantEnvelope.getEnvelopeVersion(),
                        "PROVIDER_CALLBACK_PAYLOAD"));
    }

    @Test
    void decryptsRetainedProviderKeyAndRejectsInvalidOrMutatedContext() {
        SkitProviderCallbackPayloadCryptoService.Context context =
                context(CONNECTION_ID, CORRELATION_ID, WIRE_HASH, 1);
        byte[] plaintext = "before-provider-key-rotation".getBytes(StandardCharsets.US_ASCII);
        SkitProviderCallbackPayloadCryptoService oldWriter =
                new SkitProviderCallbackPayloadCryptoService(
                        new SkitAesGcmCredentialCryptoService("old",
                                Collections.singletonMap("old", OLD_KEY),
                                new FixedSecureRandom(FIXED_NONCE)));
        SkitProviderCallbackPayloadCryptoService.PayloadEnvelope oldEnvelope =
                oldWriter.encrypt(context, plaintext);

        assertArrayEquals(plaintext, payloadCrypto.decrypt(context, oldEnvelope));
        assertThrows(IllegalArgumentException.class,
                () -> context(0, CORRELATION_ID, WIRE_HASH, 1));
        assertThrows(IllegalArgumentException.class,
                () -> context(CONNECTION_ID, new byte[15], WIRE_HASH, 1));
        assertThrows(IllegalArgumentException.class,
                () -> context(CONNECTION_ID, CORRELATION_ID, new byte[31], 1));
        assertThrows(IllegalArgumentException.class,
                () -> context(CONNECTION_ID, CORRELATION_ID, WIRE_HASH, 0));
        assertThrows(IllegalArgumentException.class,
                () -> context(CONNECTION_ID, CORRELATION_ID, WIRE_HASH, Short.MAX_VALUE + 1));

        byte[] mutableCorrelation = CORRELATION_ID.clone();
        byte[] mutableWireHash = WIRE_HASH.clone();
        SkitProviderCallbackPayloadCryptoService.Context copied =
                context(CONNECTION_ID, mutableCorrelation, mutableWireHash, 1);
        SkitProviderCallbackPayloadCryptoService.PayloadEnvelope copiedEnvelope =
                payloadCrypto.encrypt(copied, plaintext);
        Arrays.fill(mutableCorrelation, (byte) 0);
        Arrays.fill(mutableWireHash, (byte) 0);
        assertArrayEquals(plaintext, payloadCrypto.decrypt(
                context(CONNECTION_ID, CORRELATION_ID, WIRE_HASH, 1), copiedEnvelope));
    }

    @Test
    void legacyCredentialAndTenantCallbackWrappersStillRejectEmptyPlaintext() {
        assertThrows(IllegalArgumentException.class, () -> credentialCrypto.encrypt(
                SkitAdCredentialCryptoService.Context.rewardSecret(71, 93, 1, 1), new byte[0]));
        SkitCallbackPayloadCryptoService tenantPayload =
                new SkitCallbackPayloadCryptoService(credentialCrypto);
        assertThrows(IllegalArgumentException.class, () -> tenantPayload.encrypt(
                SkitCallbackPayloadCryptoService.Context.callbackPayload(71, 93, "TAKU_REWARD",
                        "reward:t-1", sequence(32, 11), 1), new byte[0]));
    }

    @Test
    void explicitlyClosesProviderContextAndEnvelopeScratchBytes() {
        SkitProviderCallbackPayloadCryptoService.Context context =
                context(CONNECTION_ID, CORRELATION_ID, WIRE_HASH, 1);
        SkitProviderCallbackPayloadCryptoService.PayloadEnvelope envelope =
                payloadCrypto.encrypt(context, "wipe-me".getBytes(StandardCharsets.US_ASCII));

        context.close();
        envelope.close();

        assertTrue(context.isClosed());
        assertTrue(envelope.isClosed());
        assertThrows(IllegalStateException.class,
                () -> payloadCrypto.encrypt(context, new byte[0]));
        assertThrows(IllegalStateException.class, envelope::getCiphertext);
        assertThrows(IllegalStateException.class, envelope::getNonce);
    }

    private void assertAuthenticationFailure(
            SkitProviderCallbackPayloadCryptoService.Context context,
            SkitProviderCallbackPayloadCryptoService.PayloadEnvelope envelope) {
        assertThrows(IllegalStateException.class, () -> payloadCrypto.decrypt(context, envelope));
    }

    private static SkitProviderCallbackPayloadCryptoService.Context context(
            long connectionId, byte[] correlationId, byte[] wireHash, int envelopeVersion) {
        return SkitProviderCallbackPayloadCryptoService.Context.providerCallbackPayload(
                connectionId, correlationId, wireHash, envelopeVersion);
    }

    private static byte[] sequence(int length, int seed) {
        byte[] result = new byte[length];
        for (int index = 0; index < result.length; index++) {
            result[index] = (byte) (seed + index);
        }
        return result;
    }

    private static final class FixedSecureRandom extends SecureRandom {

        private final byte[] fixed;

        private FixedSecureRandom(byte[] fixed) {
            this.fixed = fixed.clone();
        }

        @Override
        public void nextBytes(byte[] bytes) {
            if (bytes.length != fixed.length) {
                throw new IllegalArgumentException("Unexpected nonce length");
            }
            System.arraycopy(fixed, 0, bytes, 0, bytes.length);
        }
    }
}
