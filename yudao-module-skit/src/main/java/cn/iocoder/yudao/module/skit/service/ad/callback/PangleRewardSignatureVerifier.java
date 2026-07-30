package cn.iocoder.yudao.module.skit.service.ad.callback;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

/** Verifies Pangle's SHA-256(SecurityKey + ':' + trans_id) reward signature. */
@Component
public class PangleRewardSignatureVerifier {

    public boolean verify(PangleRewardCallback callback, byte[] securityKey) {
        if (callback == null) {
            throw new IllegalArgumentException("callback is required");
        }
        if (securityKey == null || securityKey.length == 0) {
            throw new IllegalArgumentException("security key is required");
        }

        byte[] expected = calculateDigest(callback.getTransactionId(), securityKey);
        byte[] provided = decodeHex(callback.getSignatureHex());
        try {
            return MessageDigest.isEqual(expected, provided);
        } finally {
            Arrays.fill(expected, (byte) 0);
            Arrays.fill(provided, (byte) 0);
        }
    }

    private static byte[] calculateDigest(String transactionId, byte[] securityKey) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(securityKey);
            digest.update((byte) ':');
            digest.update(transactionId.getBytes(StandardCharsets.UTF_8));
            return digest.digest();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private static byte[] decodeHex(String value) {
        if (value == null || value.length() != 64) {
            return new byte[0];
        }
        byte[] result = new byte[32];
        for (int index = 0; index < result.length; index++) {
            int high = Character.digit(value.charAt(index * 2), 16);
            int low = Character.digit(value.charAt(index * 2 + 1), 16);
            if (high < 0 || low < 0) {
                Arrays.fill(result, (byte) 0);
                return new byte[0];
            }
            result[index] = (byte) ((high << 4) | low);
        }
        return result;
    }
}
