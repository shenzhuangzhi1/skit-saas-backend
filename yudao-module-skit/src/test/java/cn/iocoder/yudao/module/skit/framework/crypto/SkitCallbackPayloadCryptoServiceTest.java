package cn.iocoder.yudao.module.skit.framework.crypto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
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

class SkitCallbackPayloadCryptoServiceTest {

    private static final long TENANT_ID = 71L;
    private static final long ACCOUNT_ID = 93L;
    private static final String CALLBACK_TYPE = "TAKU_REWARD";
    private static final String IDEMPOTENCY_KEY = "reward:transaction-20260714";
    private static final byte[] PAYLOAD_HASH = sequence(32, 11);
    private static final byte[] CURRENT_KEY = "0123456789abcdef0123456789abcdef"
            .getBytes(StandardCharsets.US_ASCII);
    private static final byte[] OLD_KEY = "abcdef0123456789abcdef0123456789"
            .getBytes(StandardCharsets.US_ASCII);

    private SkitAdCredentialCryptoService credentialCrypto;
    private SkitCallbackPayloadCryptoService payloadCrypto;

    @BeforeEach
    void setUp() {
        Map<String, byte[]> keys = new HashMap<>();
        keys.put("current", CURRENT_KEY);
        keys.put("old", OLD_KEY);
        credentialCrypto = new SkitAesGcmCredentialCryptoService("current", keys);
        payloadCrypto = new SkitCallbackPayloadCryptoService(credentialCrypto);
    }

    @Test
    void roundTripsInboxEnvelopeWithoutSerializingOrLoggingSensitiveBytes() throws Exception {
        byte[] plaintext = "trans_id=t-1&reward_amount=3&private=value"
                .getBytes(StandardCharsets.UTF_8);
        SkitCallbackPayloadCryptoService.Context context = context(
                TENANT_ID, ACCOUNT_ID, CALLBACK_TYPE, IDEMPOTENCY_KEY, PAYLOAD_HASH, 1);

        SkitCallbackPayloadCryptoService.PayloadEnvelope envelope = payloadCrypto.encrypt(context, plaintext);

        assertArrayEquals(plaintext, payloadCrypto.decrypt(context, envelope));
        assertTrue(envelope.getCiphertext().length > plaintext.length,
                "the inbox ciphertext includes the GCM authentication tag");
        assertEquals(12, envelope.getNonce().length, "the inbox nonce must fit binary(12)");
        assertEquals("current", envelope.getKeyId());
        assertEquals(1, envelope.getEnvelopeVersion());

        String json = new ObjectMapper().writeValueAsString(envelope);
        assertFalse(json.contains("ciphertext"));
        assertFalse(json.contains("nonce"));
        assertFalse(json.contains("private=value"));
        assertFalse(envelope.toString().contains("private=value"));
        assertFalse(envelope.toString().contains(Base64.getEncoder()
                .encodeToString(envelope.getCiphertext())));

        byte[] exposedCiphertext = envelope.getCiphertext();
        byte[] exposedNonce = envelope.getNonce();
        exposedCiphertext[0] ^= 1;
        exposedNonce[0] ^= 1;
        assertArrayEquals(plaintext, payloadCrypto.decrypt(context, envelope),
                "inbox envelope accessors must return defensive copies");
    }

    @Test
    void authenticatesEveryCallbackRoutingAndIdempotencyDimension() {
        byte[] plaintext = "canonical callback evidence".getBytes(StandardCharsets.UTF_8);
        SkitCallbackPayloadCryptoService.Context original = context(
                TENANT_ID, ACCOUNT_ID, CALLBACK_TYPE, IDEMPOTENCY_KEY, PAYLOAD_HASH, 1);
        SkitCallbackPayloadCryptoService.PayloadEnvelope envelope = payloadCrypto.encrypt(original, plaintext);

        assertAuthenticationFailure(context(TENANT_ID + 1, ACCOUNT_ID, CALLBACK_TYPE,
                IDEMPOTENCY_KEY, PAYLOAD_HASH, 1), envelope);
        assertAuthenticationFailure(context(TENANT_ID, ACCOUNT_ID + 1, CALLBACK_TYPE,
                IDEMPOTENCY_KEY, PAYLOAD_HASH, 1), envelope);
        assertAuthenticationFailure(context(TENANT_ID, ACCOUNT_ID, "TAKU_IMPRESSION",
                IDEMPOTENCY_KEY, PAYLOAD_HASH, 1), envelope);
        assertAuthenticationFailure(context(TENANT_ID, ACCOUNT_ID, CALLBACK_TYPE,
                IDEMPOTENCY_KEY + ":other", PAYLOAD_HASH, 1), envelope);

        byte[] wrongHash = PAYLOAD_HASH.clone();
        wrongHash[0] ^= 1;
        assertAuthenticationFailure(context(TENANT_ID, ACCOUNT_ID, CALLBACK_TYPE,
                IDEMPOTENCY_KEY, wrongHash, 1), envelope);

        SkitCallbackPayloadCryptoService.PayloadEnvelope rewrittenVersion =
                new SkitCallbackPayloadCryptoService.PayloadEnvelope(envelope.getCiphertext(), envelope.getNonce(),
                        envelope.getKeyId(), 2);
        assertAuthenticationFailure(context(TENANT_ID, ACCOUNT_ID, CALLBACK_TYPE,
                IDEMPOTENCY_KEY, PAYLOAD_HASH, 2), rewrittenVersion);
    }

    @Test
    void callbackPurposeCannotBeSubstitutedForRewardSecretPurpose() {
        byte[] plaintext = "callback-only".getBytes(StandardCharsets.UTF_8);
        SkitCallbackPayloadCryptoService.Context callbackContext = context(
                TENANT_ID, ACCOUNT_ID, CALLBACK_TYPE, IDEMPOTENCY_KEY, PAYLOAD_HASH, 1);
        SkitCallbackPayloadCryptoService.PayloadEnvelope callbackEnvelope =
                payloadCrypto.encrypt(callbackContext, plaintext);
        SkitAdCredentialCryptoService.EncryptedSecret credentialEnvelope =
                new SkitAdCredentialCryptoService.EncryptedSecret(callbackEnvelope.getCiphertext(),
                        callbackEnvelope.getNonce(), callbackEnvelope.getKeyId(),
                        callbackEnvelope.getEnvelopeVersion());

        assertThrows(IllegalStateException.class, () -> credentialCrypto.decrypt(
                SkitAdCredentialCryptoService.Context.rewardSecret(TENANT_ID, ACCOUNT_ID, 1, 1),
                credentialEnvelope));

        SkitAdCredentialCryptoService.Context rewardContext =
                SkitAdCredentialCryptoService.Context.rewardSecret(TENANT_ID, ACCOUNT_ID, 1, 1);
        SkitAdCredentialCryptoService.EncryptedSecret rewardEnvelope =
                credentialCrypto.encrypt(rewardContext, plaintext);
        SkitCallbackPayloadCryptoService.PayloadEnvelope payloadEnvelope =
                new SkitCallbackPayloadCryptoService.PayloadEnvelope(rewardEnvelope.getCiphertext(),
                        rewardEnvelope.getNonce(), rewardEnvelope.getKeyId(), rewardEnvelope.getEnvelopeVersion());
        assertAuthenticationFailure(callbackContext, payloadEnvelope);
    }

    @Test
    void decryptsRewardSecretEnvelopeWrittenWithLegacyAadBytes() {
        byte[] legacyCiphertext = Base64.getDecoder().decode(
                "QYDcHbph/97NqxDMFeK0apBtb+hM2SmFhHb0YM3UjaEJkVrq0EA=");
        byte[] fixedNonce = Base64.getDecoder().decode("AAECAwQFBgcICQoL");
        SkitAdCredentialCryptoService.EncryptedSecret legacyEnvelope =
                new SkitAdCredentialCryptoService.EncryptedSecret(
                        legacyCiphertext, fixedNonce, "current", 1);

        assertArrayEquals("legacy-reward-envelope".getBytes(StandardCharsets.UTF_8),
                credentialCrypto.decrypt(SkitAdCredentialCryptoService.Context.rewardSecret(
                        TENANT_ID, ACCOUNT_ID, 7, 1), legacyEnvelope),
                "adding CALLBACK_PAYLOAD must not change the persisted reward-secret AAD format");
    }

    @Test
    void decryptsRetainedOldKeyButRejectsTamperedKeyIdEvenWhenAliasHasSameKeyBytes() {
        SkitCallbackPayloadCryptoService.Context context = context(
                TENANT_ID, ACCOUNT_ID, CALLBACK_TYPE, IDEMPOTENCY_KEY, PAYLOAD_HASH, 1);
        byte[] plaintext = "received-before-rotation".getBytes(StandardCharsets.UTF_8);
        SkitCallbackPayloadCryptoService oldWriter = new SkitCallbackPayloadCryptoService(
                new SkitAesGcmCredentialCryptoService("old", Collections.singletonMap("old", OLD_KEY)));
        SkitCallbackPayloadCryptoService.PayloadEnvelope oldEnvelope = oldWriter.encrypt(context, plaintext);

        assertArrayEquals(plaintext, payloadCrypto.decrypt(context, oldEnvelope),
                "the configured keyring must retain old key ids for delayed inbox work");

        Map<String, byte[]> aliasedKeys = new HashMap<>();
        aliasedKeys.put("current", CURRENT_KEY);
        aliasedKeys.put("same-key-alias", CURRENT_KEY);
        SkitCallbackPayloadCryptoService aliasReader = new SkitCallbackPayloadCryptoService(
                new SkitAesGcmCredentialCryptoService("current", aliasedKeys));
        SkitCallbackPayloadCryptoService.PayloadEnvelope currentEnvelope = aliasReader.encrypt(context, plaintext);
        SkitCallbackPayloadCryptoService.PayloadEnvelope tamperedKeyId =
                new SkitCallbackPayloadCryptoService.PayloadEnvelope(currentEnvelope.getCiphertext(),
                        currentEnvelope.getNonce(), "same-key-alias", currentEnvelope.getEnvelopeVersion());

        assertThrows(IllegalStateException.class, () -> aliasReader.decrypt(context, tamperedKeyId),
                "the key id itself must be authenticated, not merely used to choose equivalent key bytes");
    }

    @Test
    void validatesCanonicalInboxContextAndDefensivelyCopiesPayloadHash() {
        assertThrows(IllegalArgumentException.class, () -> context(
                0, ACCOUNT_ID, CALLBACK_TYPE, IDEMPOTENCY_KEY, PAYLOAD_HASH, 1));
        assertThrows(IllegalArgumentException.class, () -> context(
                TENANT_ID, 0, CALLBACK_TYPE, IDEMPOTENCY_KEY, PAYLOAD_HASH, 1));
        assertThrows(IllegalArgumentException.class, () -> context(
                TENANT_ID, ACCOUNT_ID, " reward ", IDEMPOTENCY_KEY, PAYLOAD_HASH, 1));
        assertThrows(IllegalArgumentException.class, () -> context(
                TENANT_ID, ACCOUNT_ID, CALLBACK_TYPE, " idem ", PAYLOAD_HASH, 1));
        assertThrows(IllegalArgumentException.class, () -> context(
                TENANT_ID, ACCOUNT_ID, CALLBACK_TYPE, IDEMPOTENCY_KEY, new byte[31], 1));
        assertThrows(IllegalArgumentException.class, () -> context(
                TENANT_ID, ACCOUNT_ID, CALLBACK_TYPE, IDEMPOTENCY_KEY, PAYLOAD_HASH, 0));
        assertThrows(IllegalArgumentException.class, () -> context(
                TENANT_ID, ACCOUNT_ID, CALLBACK_TYPE, IDEMPOTENCY_KEY, PAYLOAD_HASH,
                Short.MAX_VALUE + 1));

        byte[] suppliedHash = PAYLOAD_HASH.clone();
        SkitCallbackPayloadCryptoService.Context copied = context(
                TENANT_ID, ACCOUNT_ID, CALLBACK_TYPE, IDEMPOTENCY_KEY, suppliedHash, 1);
        SkitCallbackPayloadCryptoService.PayloadEnvelope envelope = payloadCrypto.encrypt(
                copied, "payload".getBytes(StandardCharsets.UTF_8));
        Arrays.fill(suppliedHash, (byte) 0);

        assertArrayEquals("payload".getBytes(StandardCharsets.UTF_8), payloadCrypto.decrypt(
                context(TENANT_ID, ACCOUNT_ID, CALLBACK_TYPE, IDEMPOTENCY_KEY, PAYLOAD_HASH, 1), envelope));
        assertFalse(copied.toString().contains(IDEMPOTENCY_KEY));
        assertFalse(copied.toString().contains(Base64.getEncoder().encodeToString(PAYLOAD_HASH)));
    }

    private void assertAuthenticationFailure(SkitCallbackPayloadCryptoService.Context context,
                                             SkitCallbackPayloadCryptoService.PayloadEnvelope envelope) {
        assertThrows(IllegalStateException.class, () -> payloadCrypto.decrypt(context, envelope));
    }

    private static SkitCallbackPayloadCryptoService.Context context(
            long tenantId, long accountId, String callbackType, String idempotencyKey,
            byte[] payloadHash, int envelopeVersion) {
        return SkitCallbackPayloadCryptoService.Context.callbackPayload(
                tenantId, accountId, callbackType, idempotencyKey, payloadHash, envelopeVersion);
    }

    private static byte[] sequence(int length, int seed) {
        byte[] result = new byte[length];
        for (int index = 0; index < length; index++) {
            result[index] = (byte) (seed + index);
        }
        return result;
    }
}
