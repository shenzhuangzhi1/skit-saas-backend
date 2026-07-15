package cn.iocoder.yudao.module.skit.framework.crypto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * Encrypts the canonical callback evidence retained by the durable callback inbox.
 *
 * <p>This deliberately reuses the ad-credential keyring while using an independent purpose and
 * callback-specific authenticated context. The service never retains a plaintext buffer.</p>
 */
@Component
public final class SkitCallbackPayloadCryptoService {

    public static final int CURRENT_ENVELOPE_VERSION =
            SkitAdCredentialCryptoService.CURRENT_ENVELOPE_VERSION;
    private static final int NONCE_BYTES = 12;

    private final SkitAdCredentialCryptoService credentialCryptoService;

    public SkitCallbackPayloadCryptoService(SkitAdCredentialCryptoService credentialCryptoService) {
        this.credentialCryptoService = Objects.requireNonNull(credentialCryptoService,
                "credentialCryptoService");
    }

    public PayloadEnvelope encrypt(Context context, byte[] plaintext) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(plaintext, "plaintext");
        if (plaintext.length == 0) {
            throw new IllegalArgumentException("Callback payload must not be empty");
        }
        SkitAdCredentialCryptoService.EncryptedSecret encrypted = credentialCryptoService.encrypt(
                context.toCredentialContext(), plaintext);
        return new PayloadEnvelope(encrypted.getCiphertext(), encrypted.getNonce(), encrypted.getKeyId(),
                encrypted.getEnvelopeVersion());
    }

    public byte[] decrypt(Context context, PayloadEnvelope envelope) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(envelope, "envelope");
        return credentialCryptoService.decrypt(context.toCredentialContext(),
                new SkitAdCredentialCryptoService.EncryptedSecret(envelope.getCiphertext(),
                        envelope.getNonce(), envelope.getKeyId(), envelope.getEnvelopeVersion()));
    }

    /** Callback dimensions that are authenticated but never stored in an encrypted envelope. */
    public static final class Context {

        private final long tenantId;
        private final long adAccountId;
        private final String callbackType;
        private final String idempotencyKey;
        private final byte[] canonicalPayloadHash;
        private final int envelopeVersion;

        private Context(long tenantId, long adAccountId, String callbackType,
                        String idempotencyKey, byte[] canonicalPayloadHash,
                        int envelopeVersion) {
            SkitAdCredentialCryptoService.Context validated =
                    SkitAdCredentialCryptoService.Context.callbackPayload(tenantId, adAccountId,
                            callbackType, idempotencyKey, canonicalPayloadHash, envelopeVersion);
            this.tenantId = validated.getTenantId();
            this.adAccountId = validated.getAdAccountId();
            this.callbackType = validated.getCallbackType();
            this.idempotencyKey = validated.getIdempotencyKey();
            this.canonicalPayloadHash = validated.getCanonicalPayloadHash();
            this.envelopeVersion = validated.getEnvelopeVersion();
        }

        public static Context callbackPayload(long tenantId, long adAccountId, String callbackType,
                                              String idempotencyKey, byte[] canonicalPayloadHash,
                                              int envelopeVersion) {
            return new Context(tenantId, adAccountId, callbackType, idempotencyKey,
                    canonicalPayloadHash, envelopeVersion);
        }

        SkitAdCredentialCryptoService.Context toCredentialContext() {
            return SkitAdCredentialCryptoService.Context.callbackPayload(tenantId, adAccountId,
                    callbackType, idempotencyKey, canonicalPayloadHash, envelopeVersion);
        }

        @Override
        public String toString() {
            return "Context{purpose='CALLBACK_PAYLOAD', tenantId=" + tenantId
                    + ", adAccountId=" + adAccountId + ", callbackType='" + callbackType
                    + "', idempotencyKey=<redacted>, canonicalPayloadHash=<redacted>"
                    + ", envelopeVersion=" + envelopeVersion + '}';
        }
    }

    /** Persistence-shaped encrypted value for payload_ciphertext/nonce/key_id/envelope_version. */
    public static final class PayloadEnvelope {

        @JsonIgnore
        private final byte[] ciphertext;
        @JsonIgnore
        private final byte[] nonce;
        private final String keyId;
        private final int envelopeVersion;

        public PayloadEnvelope(byte[] ciphertext, byte[] nonce, String keyId, int envelopeVersion) {
            byte[] copiedCiphertext = Objects.requireNonNull(ciphertext, "ciphertext").clone();
            byte[] copiedNonce = Objects.requireNonNull(nonce, "nonce").clone();
            String requiredKeyId = Objects.requireNonNull(keyId, "keyId");
            if (copiedCiphertext.length == 0) {
                throw new IllegalArgumentException("Callback payload ciphertext must not be empty");
            }
            if (copiedNonce.length != NONCE_BYTES) {
                throw new IllegalArgumentException("Callback payload nonce must contain 12 bytes");
            }
            if (requiredKeyId.isEmpty() || !requiredKeyId.equals(requiredKeyId.trim())
                    || requiredKeyId.length() > 64) {
                throw new IllegalArgumentException("Callback payload key id is not canonical");
            }
            if (envelopeVersion <= 0 || envelopeVersion > Short.MAX_VALUE) {
                throw new IllegalArgumentException("Callback payload envelope version is invalid");
            }
            this.ciphertext = copiedCiphertext;
            this.nonce = copiedNonce;
            this.keyId = requiredKeyId;
            this.envelopeVersion = envelopeVersion;
        }

        @JsonIgnore
        public byte[] getCiphertext() {
            return ciphertext.clone();
        }

        @JsonIgnore
        public byte[] getNonce() {
            return nonce.clone();
        }

        public String getKeyId() {
            return keyId;
        }

        public int getEnvelopeVersion() {
            return envelopeVersion;
        }

        @Override
        public String toString() {
            return "PayloadEnvelope{ciphertext=<redacted>, nonce=<redacted>, keyId='"
                    + keyId + "', envelopeVersion=" + envelopeVersion + '}';
        }
    }
}
