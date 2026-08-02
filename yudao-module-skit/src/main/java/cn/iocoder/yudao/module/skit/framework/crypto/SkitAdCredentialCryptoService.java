package cn.iocoder.yudao.module.skit.framework.crypto;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.Arrays;
import java.util.Objects;

public interface SkitAdCredentialCryptoService {

    int CURRENT_ENVELOPE_VERSION = 1;

    EncryptedSecret encrypt(Context context, byte[] plaintext);

    byte[] decrypt(Context context, EncryptedSecret encryptedSecret);

    final class Context implements AutoCloseable {

        private static final String REWARD_SECRET_PURPOSE = "TAKU_REWARD_SECRET";
        private static final String PUBLISHER_KEY_PURPOSE = "TAKU_PUBLISHER_KEY";
        private static final String APP_BUILD_MATERIAL_PURPOSE = "APP_BUILD_MATERIAL";
        private static final String CALLBACK_PAYLOAD_PURPOSE = "CALLBACK_PAYLOAD";
        private static final String PROVIDER_CALLBACK_PAYLOAD_PURPOSE = "PROVIDER_CALLBACK_PAYLOAD";

        private final String purpose;
        private final long tenantId;
        private final long adAccountId;
        private final int credentialVersion;
        private final int envelopeVersion;
        private final String callbackType;
        private final String idempotencyKey;
        private final byte[] canonicalPayloadHash;
        private final long providerConnectionId;
        private final byte[] correlationId;
        private final byte[] wirePayloadHash;
        private boolean closed;

        private Context(String purpose, long tenantId, long adAccountId,
                        int credentialVersion, int envelopeVersion) {
            if (tenantId <= 0 || credentialVersion <= 0 || envelopeVersion <= 0
                    || (!APP_BUILD_MATERIAL_PURPOSE.equals(purpose) && adAccountId <= 0)) {
                throw new IllegalArgumentException("Credential encryption context identifiers must be positive");
            }
            this.purpose = Objects.requireNonNull(purpose, "purpose");
            this.tenantId = tenantId;
            this.adAccountId = adAccountId;
            this.credentialVersion = credentialVersion;
            this.envelopeVersion = envelopeVersion;
            this.callbackType = null;
            this.idempotencyKey = null;
            this.canonicalPayloadHash = null;
            this.providerConnectionId = 0L;
            this.correlationId = null;
            this.wirePayloadHash = null;
        }

        private Context(long tenantId, long adAccountId, String callbackType,
                        String idempotencyKey, byte[] canonicalPayloadHash,
                        int envelopeVersion) {
            if (tenantId <= 0 || adAccountId <= 0 || envelopeVersion <= 0
                    || envelopeVersion > Short.MAX_VALUE) {
                throw new IllegalArgumentException("Callback payload encryption identifiers are invalid");
            }
            this.purpose = CALLBACK_PAYLOAD_PURPOSE;
            this.tenantId = tenantId;
            this.adAccountId = adAccountId;
            this.credentialVersion = 0;
            this.envelopeVersion = envelopeVersion;
            this.callbackType = requireCanonicalText(callbackType, "callbackType", 32);
            this.idempotencyKey = requireCanonicalText(idempotencyKey, "idempotencyKey", 255);
            byte[] copiedHash = Objects.requireNonNull(canonicalPayloadHash, "canonicalPayloadHash").clone();
            if (copiedHash.length != 32) {
                throw new IllegalArgumentException("Callback canonical payload hash must contain 32 bytes");
            }
            this.canonicalPayloadHash = copiedHash;
            this.providerConnectionId = 0L;
            this.correlationId = null;
            this.wirePayloadHash = null;
        }

        private Context(long providerConnectionId, byte[] correlationId,
                        byte[] wirePayloadHash, int envelopeVersion) {
            if (providerConnectionId <= 0 || envelopeVersion <= 0
                    || envelopeVersion > Short.MAX_VALUE) {
                throw new IllegalArgumentException("Provider callback encryption identifiers are invalid");
            }
            this.purpose = PROVIDER_CALLBACK_PAYLOAD_PURPOSE;
            this.tenantId = 0L;
            this.adAccountId = 0L;
            this.credentialVersion = 0;
            this.envelopeVersion = envelopeVersion;
            this.callbackType = null;
            this.idempotencyKey = null;
            this.canonicalPayloadHash = null;
            this.providerConnectionId = providerConnectionId;
            this.correlationId = requireFixedBytes(correlationId, 16,
                    "Provider callback correlation id");
            this.wirePayloadHash = requireFixedBytes(wirePayloadHash, 32,
                    "Provider callback wire payload hash");
        }

        public static Context rewardSecret(long tenantId, long adAccountId,
                                           int credentialVersion, int envelopeVersion) {
            return new Context(REWARD_SECRET_PURPOSE, tenantId, adAccountId,
                    credentialVersion, envelopeVersion);
        }

        public static Context publisherKey(long tenantId, long adAccountId,
                                           int credentialVersion, int envelopeVersion) {
            return new Context(PUBLISHER_KEY_PURPOSE, tenantId, adAccountId,
                    credentialVersion, envelopeVersion);
        }

        public static Context appBuildMaterial(long tenantId, int materialVersion,
                                               int envelopeVersion) {
            return new Context(APP_BUILD_MATERIAL_PURPOSE, tenantId, 0L,
                    materialVersion, envelopeVersion);
        }

        static Context callbackPayload(long tenantId, long adAccountId, String callbackType,
                                       String idempotencyKey, byte[] canonicalPayloadHash,
                                       int envelopeVersion) {
            return new Context(tenantId, adAccountId, callbackType, idempotencyKey,
                    canonicalPayloadHash, envelopeVersion);
        }

        static Context providerCallbackPayload(long providerConnectionId, byte[] correlationId,
                                               byte[] wirePayloadHash, int envelopeVersion) {
            return new Context(providerConnectionId, correlationId, wirePayloadHash, envelopeVersion);
        }

        public String getPurpose() {
            return purpose;
        }

        public long getTenantId() {
            return tenantId;
        }

        public long getAdAccountId() {
            return adAccountId;
        }

        public int getCredentialVersion() {
            return credentialVersion;
        }

        public int getEnvelopeVersion() {
            return envelopeVersion;
        }

        String getCallbackType() {
            return callbackType;
        }

        String getIdempotencyKey() {
            return idempotencyKey;
        }

        byte[] getCanonicalPayloadHash() {
            requireOpen();
            return canonicalPayloadHash == null ? null : canonicalPayloadHash.clone();
        }

        long getProviderConnectionId() {
            return providerConnectionId;
        }

        byte[] getCorrelationId() {
            requireOpen();
            return correlationId == null ? null : correlationId.clone();
        }

        byte[] getWirePayloadHash() {
            requireOpen();
            return wirePayloadHash == null ? null : wirePayloadHash.clone();
        }

        boolean isCallbackPayload() {
            return CALLBACK_PAYLOAD_PURPOSE.equals(purpose);
        }

        boolean isProviderCallbackPayload() {
            return PROVIDER_CALLBACK_PAYLOAD_PURPOSE.equals(purpose);
        }

        boolean isAppBuildMaterial() {
            return APP_BUILD_MATERIAL_PURPOSE.equals(purpose);
        }

        public boolean isClosed() {
            return closed;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            wipe(canonicalPayloadHash);
            wipe(correlationId);
            wipe(wirePayloadHash);
            closed = true;
        }

        @Override
        public String toString() {
            if (isProviderCallbackPayload()) {
                return "Context{purpose='" + purpose + "', providerConnectionId="
                        + providerConnectionId + ", correlationId=<redacted>, wirePayloadHash=<redacted>"
                        + ", envelopeVersion=" + envelopeVersion + '}';
            }
            if (isCallbackPayload()) {
                return "Context{purpose='" + purpose + "', tenantId=" + tenantId
                        + ", adAccountId=" + adAccountId + ", callbackType='" + callbackType
                        + "', idempotencyKey=<redacted>, canonicalPayloadHash=<redacted>"
                        + ", envelopeVersion=" + envelopeVersion + '}';
            }
            if (isAppBuildMaterial()) {
                return "Context{purpose='" + purpose + "', tenantId=" + tenantId
                        + ", materialVersion=" + credentialVersion
                        + ", envelopeVersion=" + envelopeVersion + '}';
            }
            return "Context{purpose='" + purpose + "', tenantId=" + tenantId
                    + ", adAccountId=" + adAccountId + ", credentialVersion=" + credentialVersion
                    + ", envelopeVersion=" + envelopeVersion + '}';
        }

        private static String requireCanonicalText(String value, String fieldName, int maxLength) {
            String required = Objects.requireNonNull(value, fieldName);
            if (required.isEmpty() || !required.equals(required.trim()) || required.length() > maxLength) {
                throw new IllegalArgumentException("Callback " + fieldName + " is not canonical");
            }
            return required;
        }

        private static byte[] requireFixedBytes(byte[] value, int expectedLength, String fieldName) {
            byte[] copied = Objects.requireNonNull(value, fieldName).clone();
            if (copied.length != expectedLength) {
                throw new IllegalArgumentException(fieldName + " must contain "
                        + expectedLength + " bytes");
            }
            return copied;
        }

        private void requireOpen() {
            if (closed) {
                throw new IllegalStateException("Credential encryption context has been closed");
            }
        }

        private static void wipe(byte[] value) {
            if (value != null) {
                Arrays.fill(value, (byte) 0);
            }
        }
    }

    final class EncryptedSecret {

        @JsonIgnore
        private final byte[] ciphertext;
        @JsonIgnore
        private final byte[] nonce;
        private final String keyId;
        private final int envelopeVersion;

        public EncryptedSecret(byte[] ciphertext, byte[] nonce, String keyId, int envelopeVersion) {
            this.ciphertext = Objects.requireNonNull(ciphertext, "ciphertext").clone();
            this.nonce = Objects.requireNonNull(nonce, "nonce").clone();
            this.keyId = Objects.requireNonNull(keyId, "keyId");
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
        public boolean equals(Object object) {
            if (this == object) {
                return true;
            }
            if (!(object instanceof EncryptedSecret)) {
                return false;
            }
            EncryptedSecret that = (EncryptedSecret) object;
            return envelopeVersion == that.envelopeVersion && Arrays.equals(ciphertext, that.ciphertext)
                    && Arrays.equals(nonce, that.nonce) && keyId.equals(that.keyId);
        }

        @Override
        public int hashCode() {
            int result = Objects.hash(keyId, envelopeVersion);
            result = 31 * result + Arrays.hashCode(ciphertext);
            return 31 * result + Arrays.hashCode(nonce);
        }

        @Override
        public String toString() {
            return "EncryptedSecret{ciphertext=<redacted>, nonce=<redacted>, keyId='"
                    + keyId + "', envelopeVersion=" + envelopeVersion + '}';
        }
    }

}
