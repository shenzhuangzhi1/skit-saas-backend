package cn.iocoder.yudao.module.skit.framework.crypto;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public final class SkitAesGcmCredentialCryptoService implements SkitAdCredentialCryptoService {

    private static final String CIPHER_ALGORITHM = "AES/GCM/NoPadding";
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final String currentKeyId;
    private final Map<String, byte[]> keys;
    private final SecureRandom secureRandom;

    public SkitAesGcmCredentialCryptoService(String currentKeyId, Map<String, byte[]> keys) {
        this(currentKeyId, keys, new SecureRandom());
    }

    SkitAesGcmCredentialCryptoService(String currentKeyId, Map<String, byte[]> keys,
                                      SecureRandom secureRandom) {
        this.currentKeyId = requireKeyId(currentKeyId);
        this.keys = immutableKeys(keys);
        this.secureRandom = Objects.requireNonNull(secureRandom, "secureRandom");
    }

    @Override
    public EncryptedSecret encrypt(Context context, byte[] plaintext) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(plaintext, "plaintext");
        if (plaintext.length == 0 && !context.isProviderCallbackPayload()) {
            throw new IllegalArgumentException("Reward secret must not be empty");
        }
        byte[] nonce = new byte[NONCE_BYTES];
        secureRandom.nextBytes(nonce);
        byte[] ciphertext = null;
        try {
            ciphertext = crypt(Cipher.ENCRYPT_MODE, context, plaintext, nonce, currentKeyId);
            return new EncryptedSecret(ciphertext, nonce, currentKeyId, context.getEnvelopeVersion());
        } finally {
            Arrays.fill(nonce, (byte) 0);
            if (ciphertext != null) {
                Arrays.fill(ciphertext, (byte) 0);
            }
        }
    }

    @Override
    public byte[] decrypt(Context context, EncryptedSecret encryptedSecret) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(encryptedSecret, "encryptedSecret");
        if (encryptedSecret.getEnvelopeVersion() != context.getEnvelopeVersion()) {
            throw new IllegalStateException("Credential envelope version does not match its bound context");
        }
        byte[] nonce = encryptedSecret.getNonce();
        byte[] ciphertext = encryptedSecret.getCiphertext();
        try {
            if (nonce.length != NONCE_BYTES) {
                throw new IllegalStateException("Credential nonce has an invalid length");
            }
            return crypt(Cipher.DECRYPT_MODE, context, ciphertext, nonce,
                    encryptedSecret.getKeyId());
        } finally {
            Arrays.fill(nonce, (byte) 0);
            Arrays.fill(ciphertext, (byte) 0);
        }
    }

    private byte[] crypt(int mode, Context context, byte[] input, byte[] nonce, String keyId) {
        byte[] key = keys.get(keyId);
        if (key == null) {
            throw new IllegalStateException("Credential encryption key is unavailable for key id " + keyId);
        }
        try {
            Cipher cipher = Cipher.getInstance(CIPHER_ALGORITHM);
            cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, nonce));
            byte[] aad = aad(context, keyId);
            try {
                cipher.updateAAD(aad);
                return cipher.doFinal(input);
            } finally {
                Arrays.fill(aad, (byte) 0);
            }
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Credential authentication failed", exception);
        }
    }

    private static byte[] aad(Context context, String keyId) {
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            DataOutputStream output = new DataOutputStream(buffer);
            output.writeUTF(context.getPurpose());
            if (context.isProviderCallbackPayload()) {
                output.writeLong(context.getProviderConnectionId());
                byte[] correlationId = context.getCorrelationId();
                byte[] wirePayloadHash = context.getWirePayloadHash();
                try {
                    output.writeInt(correlationId.length);
                    output.write(correlationId);
                    output.writeInt(wirePayloadHash.length);
                    output.write(wirePayloadHash);
                    output.writeInt(context.getEnvelopeVersion());
                    output.writeUTF(keyId);
                    output.flush();
                    return buffer.toByteArray();
                } finally {
                    Arrays.fill(correlationId, (byte) 0);
                    Arrays.fill(wirePayloadHash, (byte) 0);
                }
            }
            output.writeLong(context.getTenantId());
            output.writeLong(context.getAdAccountId());
            if (context.isCallbackPayload()) {
                output.writeUTF(context.getCallbackType());
                output.writeUTF(context.getIdempotencyKey());
                byte[] canonicalPayloadHash = context.getCanonicalPayloadHash();
                output.writeInt(canonicalPayloadHash.length);
                output.write(canonicalPayloadHash);
            } else {
                // Keep the original reward-secret AAD byte-for-byte compatible with stored envelopes.
                output.writeInt(context.getCredentialVersion());
            }
            output.writeInt(context.getEnvelopeVersion());
            if (context.isCallbackPayload()) {
                output.writeUTF(keyId);
            }
            output.flush();
            return buffer.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("Could not build credential authentication context", exception);
        }
    }

    private static Map<String, byte[]> immutableKeys(Map<String, byte[]> suppliedKeys) {
        if (suppliedKeys == null || suppliedKeys.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, byte[]> result = new HashMap<>();
        suppliedKeys.forEach((keyId, key) -> {
            String normalizedKeyId = requireKeyId(keyId);
            byte[] copiedKey = Objects.requireNonNull(key, "key").clone();
            if (copiedKey.length != 16 && copiedKey.length != 24 && copiedKey.length != 32) {
                throw new IllegalArgumentException("AES key " + normalizedKeyId + " must be 16, 24, or 32 bytes");
            }
            result.put(normalizedKeyId, copiedKey);
        });
        return Collections.unmodifiableMap(result);
    }

    private static String requireKeyId(String keyId) {
        if (keyId == null || keyId.trim().isEmpty()) {
            throw new IllegalArgumentException("Credential encryption key id must not be blank");
        }
        return keyId.trim();
    }

}
