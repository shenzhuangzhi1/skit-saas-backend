package cn.iocoder.yudao.module.skit.service.ad.callback;

import cn.iocoder.yudao.framework.ratelimiter.core.redis.RateLimiterRedisDAO;
import com.google.common.net.InetAddresses;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/** Applies a per-callback-key business limit plus a much higher global IP DDoS gate. */
@Component
public class RedisSkitCallbackRateLimiter implements SkitCallbackRateLimiter {

    private static final int GLOBAL_IP_REQUESTS_PER_MINUTE = 12000;
    private static final int CALLBACK_KEY_REQUESTS_PER_MINUTE = 120;

    private final RateLimiterRedisDAO redis;

    public RedisSkitCallbackRateLimiter(RateLimiterRedisDAO redis) {
        this.redis = Objects.requireNonNull(redis, "redis");
    }

    @Override
    public void check(String provider, String callbackKey, String clientIp, String callbackType) {
        String providerNamespace = requireProvider(provider);
        String type = requireType(callbackType);
        String ipGateKey = "skit:ad-callback:ddos:ip:"
                + sha256Hex("client-ip\0" + requireValue(clientIp));
        String businessKey = "skit:ad-callback:" + providerNamespace + ":" + type + ":key:"
                + sha256Hex("callback-key\0" + requireValue(callbackKey));
        acquire(ipGateKey, businessKey);
    }

    @Override
    public void checkHashed(String provider, byte[] callbackKeyHash,
                            byte[] packedClientAddress, String callbackType) {
        String providerNamespace = requireProvider(provider);
        String type = requireType(callbackType);
        byte[] keyHash = requireFixed(callbackKeyHash, 32, "callback key hash");
        byte[] clientAddress = requireAddress(packedClientAddress);
        try {
            String ipGateKey = "skit:ad-callback:ddos:ip:"
                    + legacyAddressHash(clientAddress);
            String businessKey = "skit:ad-callback:" + providerNamespace + ":" + type + ":key:"
                    + lowerHex(keyHash);
            acquire(ipGateKey, businessKey);
        } finally {
            Arrays.fill(keyHash, (byte) 0);
            Arrays.fill(clientAddress, (byte) 0);
        }
    }

    @Override
    public void checkGlobalAddressHashed(byte[] packedClientAddress) {
        byte[] clientAddress = requireAddress(packedClientAddress);
        try {
            String ipGateKey = "skit:ad-callback:ddos:ip:"
                    + legacyAddressHash(clientAddress);
            acquireGlobal(ipGateKey);
        } finally {
            Arrays.fill(clientAddress, (byte) 0);
        }
    }

    @Override
    public void checkBusinessKeyHashed(String provider, byte[] callbackKeyHash,
                                       String callbackType) {
        String providerNamespace = requireProvider(provider);
        String type = requireType(callbackType);
        byte[] keyHash = requireFixed(callbackKeyHash, 32, "callback key hash");
        try {
            String businessKey = "skit:ad-callback:" + providerNamespace + ":" + type + ":key:"
                    + lowerHex(keyHash);
            acquireBusiness(businessKey);
        } finally {
            Arrays.fill(keyHash, (byte) 0);
        }
    }

    private void acquire(String ipGateKey, String businessKey) {
        acquireGlobal(ipGateKey);
        acquireBusiness(businessKey);
    }

    private void acquireGlobal(String ipGateKey) {
        if (!Boolean.TRUE.equals(redis.tryAcquire(ipGateKey, GLOBAL_IP_REQUESTS_PER_MINUTE,
                60, TimeUnit.SECONDS))) {
            throw new RateLimitExceededException();
        }
    }

    private void acquireBusiness(String businessKey) {
        if (!Boolean.TRUE.equals(redis.tryAcquire(businessKey, CALLBACK_KEY_REQUESTS_PER_MINUTE,
                60, TimeUnit.SECONDS))) {
            throw new RateLimitExceededException();
        }
    }

    private static String requireProvider(String value) {
        String normalized = requireValue(value).toUpperCase(Locale.ROOT);
        if (!"TAKU".equals(normalized) && !"PANGLE".equals(normalized)) {
            throw new IllegalArgumentException("Unsupported callback provider");
        }
        return normalized.toLowerCase(Locale.ROOT);
    }

    private static String requireType(String value) {
        String normalized = requireValue(value).toUpperCase(Locale.ROOT);
        if (!"REWARD".equals(normalized) && !"IMPRESSION".equals(normalized)) {
            throw new IllegalArgumentException("Unsupported callback type");
        }
        return normalized.toLowerCase(Locale.ROOT);
    }

    private static String requireValue(String value) {
        if (value == null || value.isEmpty() || value.length() > 512) {
            throw new IllegalArgumentException("Callback rate-limit identity is invalid");
        }
        return value;
    }

    private static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte item : digest) {
                result.append(String.format("%02x", item & 0xff));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private static String sha256Hex(byte[] domain, byte[] value) {
        byte[] hashed = null;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(domain);
            hashed = digest.digest(value);
            return lowerHex(hashed);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        } finally {
            if (hashed != null) {
                Arrays.fill(hashed, (byte) 0);
            }
        }
    }

    private static String legacyAddressHash(byte[] packedClientAddress) {
        try {
            InetAddress address = InetAddress.getByAddress(packedClientAddress);
            return sha256Hex("client-ip\0" + InetAddresses.toAddrString(address));
        } catch (UnknownHostException invalidAddress) {
            throw new IllegalArgumentException("Callback client address is invalid", invalidAddress);
        }
    }

    private static byte[] requireFixed(byte[] value, int length, String field) {
        if (value == null || value.length != length) {
            throw new IllegalArgumentException("Callback " + field + " is invalid");
        }
        return value.clone();
    }

    private static byte[] requireAddress(byte[] value) {
        if (value == null || (value.length != 4 && value.length != 16)) {
            throw new IllegalArgumentException("Callback client address is invalid");
        }
        return value.clone();
    }

    private static String lowerHex(byte[] value) {
        char[] result = new char[value.length * 2];
        char[] alphabet = "0123456789abcdef".toCharArray();
        for (int index = 0; index < value.length; index++) {
            int current = value[index] & 0xff;
            result[index * 2] = alphabet[current >>> 4];
            result[index * 2 + 1] = alphabet[current & 0x0f];
        }
        return new String(result);
    }

}
