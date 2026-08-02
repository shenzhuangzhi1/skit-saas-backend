package cn.iocoder.yudao.module.skit.framework.crypto;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.Arrays;
import java.util.Objects;

/**
 * Compile-time Task 4 contract for the independently keyed provider payload envelope.
 */
public class SkitProviderCallbackPayloadCryptoService {

    public static final int CURRENT_ENVELOPE_VERSION =
            SkitAdCredentialCryptoService.CURRENT_ENVELOPE_VERSION;
    public static final String PURPOSE = "PROVIDER_CALLBACK_PAYLOAD";
    private static final int NONCE_BYTES = 12;

    private final SkitAdCredentialCryptoService credentialCryptoService;

    public SkitProviderCallbackPayloadCryptoService(
            SkitAdCredentialCryptoService credentialCryptoService) {
        this.credentialCryptoService = Objects.requireNonNull(credentialCryptoService,
                "credentialCryptoService");
    }

    public PayloadEnvelope encrypt(Context context, byte[] plaintext) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(plaintext, "plaintext");
        try (SkitAdCredentialCryptoService.Context core = context.toCredentialContext()) {
            SkitAdCredentialCryptoService.EncryptedSecret encrypted =
                    credentialCryptoService.encrypt(core, plaintext);
            byte[] ciphertext = encrypted.getCiphertext();
            byte[] nonce = encrypted.getNonce();
            try {
                return new PayloadEnvelope(ciphertext, nonce, encrypted.getKeyId(),
                        encrypted.getEnvelopeVersion(), PURPOSE);
            } finally {
                Arrays.fill(ciphertext, (byte) 0);
                Arrays.fill(nonce, (byte) 0);
            }
        }
    }

    public byte[] decrypt(Context context, PayloadEnvelope envelope) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(envelope, "envelope");
        if (!PURPOSE.equals(envelope.getPurpose())) {
            throw new IllegalStateException("Provider callback payload purpose is invalid");
        }
        byte[] ciphertext = envelope.getCiphertext();
        byte[] nonce = envelope.getNonce();
        try (SkitAdCredentialCryptoService.Context core = context.toCredentialContext()) {
            return credentialCryptoService.decrypt(core,
                    new SkitAdCredentialCryptoService.EncryptedSecret(ciphertext, nonce,
                            envelope.getKeyId(), envelope.getEnvelopeVersion()));
        } finally {
            Arrays.fill(ciphertext, (byte) 0);
            Arrays.fill(nonce, (byte) 0);
        }
    }

    public static final class Context implements AutoCloseable {

        private final long providerConnectionId;
        private final byte[] correlationId;
        private final byte[] wirePayloadHash;
        private final int envelopeVersion;
        private boolean closed;

        private Context(long providerConnectionId, byte[] correlationId,
                        byte[] wirePayloadHash, int envelopeVersion) {
            try (SkitAdCredentialCryptoService.Context validated =
                         SkitAdCredentialCryptoService.Context.providerCallbackPayload(
                                 providerConnectionId, correlationId, wirePayloadHash,
                                 envelopeVersion)) {
                this.providerConnectionId = validated.getProviderConnectionId();
                this.correlationId = validated.getCorrelationId();
                this.wirePayloadHash = validated.getWirePayloadHash();
                this.envelopeVersion = validated.getEnvelopeVersion();
            }
        }

        public static Context providerCallbackPayload(long providerConnectionId,
                                                      byte[] correlationId,
                                                      byte[] wirePayloadHash,
                                                      int envelopeVersion) {
            return new Context(providerConnectionId, correlationId, wirePayloadHash, envelopeVersion);
        }

        SkitAdCredentialCryptoService.Context toCredentialContext() {
            requireOpen();
            return SkitAdCredentialCryptoService.Context.providerCallbackPayload(
                    providerConnectionId, correlationId, wirePayloadHash, envelopeVersion);
        }

        public boolean isClosed() {
            return closed;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            Arrays.fill(correlationId, (byte) 0);
            Arrays.fill(wirePayloadHash, (byte) 0);
            closed = true;
        }

        @Override
        public String toString() {
            return "Context{purpose='" + PURPOSE + "', providerConnectionId="
                    + providerConnectionId + ", correlationId=<redacted>, wirePayloadHash=<redacted>"
                    + ", envelopeVersion=" + envelopeVersion + '}';
        }

        private void requireOpen() {
            if (closed) {
                throw new IllegalStateException("Provider callback payload context has been closed");
            }
        }
    }

    public static final class PayloadEnvelope implements AutoCloseable {

        @JsonIgnore
        private final byte[] ciphertext;
        @JsonIgnore
        private final byte[] nonce;
        private final String keyId;
        private final int envelopeVersion;
        private final String purpose;
        private boolean closed;

        public PayloadEnvelope(byte[] ciphertext, byte[] nonce, String keyId,
                               int envelopeVersion, String purpose) {
            byte[] copiedCiphertext = Objects.requireNonNull(ciphertext, "ciphertext").clone();
            byte[] copiedNonce = Objects.requireNonNull(nonce, "nonce").clone();
            String requiredKeyId = Objects.requireNonNull(keyId, "keyId");
            String requiredPurpose = Objects.requireNonNull(purpose, "purpose");
            if (copiedCiphertext.length == 0) {
                throw new IllegalArgumentException("Provider callback payload ciphertext must not be empty");
            }
            if (copiedNonce.length != NONCE_BYTES) {
                throw new IllegalArgumentException("Provider callback payload nonce must contain 12 bytes");
            }
            if (requiredKeyId.isEmpty() || !requiredKeyId.equals(requiredKeyId.trim())
                    || requiredKeyId.length() > 64) {
                throw new IllegalArgumentException("Provider callback payload key id is not canonical");
            }
            if (envelopeVersion <= 0 || envelopeVersion > Short.MAX_VALUE) {
                throw new IllegalArgumentException("Provider callback payload envelope version is invalid");
            }
            if (!PURPOSE.equals(requiredPurpose)) {
                throw new IllegalArgumentException("Provider callback payload purpose is invalid");
            }
            this.ciphertext = copiedCiphertext;
            this.nonce = copiedNonce;
            this.keyId = requiredKeyId;
            this.envelopeVersion = envelopeVersion;
            this.purpose = requiredPurpose;
        }

        @JsonIgnore
        public byte[] getCiphertext() {
            requireOpen();
            return ciphertext.clone();
        }

        @JsonIgnore
        public byte[] getNonce() {
            requireOpen();
            return nonce.clone();
        }

        public String getKeyId() {
            return keyId;
        }

        public int getEnvelopeVersion() {
            return envelopeVersion;
        }

        public String getPurpose() {
            return purpose;
        }

        public boolean isClosed() {
            return closed;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            Arrays.fill(ciphertext, (byte) 0);
            Arrays.fill(nonce, (byte) 0);
            closed = true;
        }

        @Override
        public String toString() {
            return "PayloadEnvelope{ciphertext=<redacted>, nonce=<redacted>, keyId='"
                    + keyId + "', envelopeVersion=" + envelopeVersion
                    + ", purpose='" + purpose + "'}";
        }

        private void requireOpen() {
            if (closed) {
                throw new IllegalStateException("Provider callback payload envelope has been closed");
            }
        }
    }
}
