package cn.iocoder.yudao.module.skit.framework.crypto;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.Arrays;
import java.util.Objects;

public interface SkitAdCredentialCryptoService {

    int CURRENT_ENVELOPE_VERSION = 1;

    EncryptedSecret encrypt(Context context, byte[] plaintext);

    byte[] decrypt(Context context, EncryptedSecret encryptedSecret);

    final class Context {

        private static final String REWARD_SECRET_PURPOSE = "TAKU_REWARD_SECRET";

        private final String purpose;
        private final long tenantId;
        private final long adAccountId;
        private final int credentialVersion;
        private final int envelopeVersion;

        private Context(String purpose, long tenantId, long adAccountId,
                        int credentialVersion, int envelopeVersion) {
            if (tenantId <= 0 || adAccountId <= 0 || credentialVersion <= 0 || envelopeVersion <= 0) {
                throw new IllegalArgumentException("Credential encryption context identifiers must be positive");
            }
            this.purpose = Objects.requireNonNull(purpose, "purpose");
            this.tenantId = tenantId;
            this.adAccountId = adAccountId;
            this.credentialVersion = credentialVersion;
            this.envelopeVersion = envelopeVersion;
        }

        public static Context rewardSecret(long tenantId, long adAccountId,
                                           int credentialVersion, int envelopeVersion) {
            return new Context(REWARD_SECRET_PURPOSE, tenantId, adAccountId,
                    credentialVersion, envelopeVersion);
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

        @Override
        public String toString() {
            return "Context{purpose='" + purpose + "', tenantId=" + tenantId
                    + ", adAccountId=" + adAccountId + ", credentialVersion=" + credentialVersion
                    + ", envelopeVersion=" + envelopeVersion + '}';
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
