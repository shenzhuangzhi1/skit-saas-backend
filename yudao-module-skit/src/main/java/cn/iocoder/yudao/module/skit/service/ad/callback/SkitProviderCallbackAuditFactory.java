package cn.iocoder.yudao.module.skit.service.ad.callback;

import cn.iocoder.yudao.module.skit.service.ad.callback.SkitProviderImpressionCaptureService.ProviderIngressEvidence;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Objects;

/** Creates bounded, unlinkable provider-ingress audit evidence without retaining raw metadata. */
@Component
public class SkitProviderCallbackAuditFactory {

    static final String DAY_DOMAIN = "skit-provider-callback-audit-day-v1";
    static final String REMOTE_ADDRESS_DOMAIN = "skit-provider-callback-audit-remote-address-v1";
    static final String USER_AGENT_DOMAIN = "skit-provider-callback-audit-user-agent-v1";
    static final String HEADER_DOMAIN = "skit-provider-callback-audit-headers-v1";
    private static final int MINIMUM_MASTER_KEY_BYTES = 32;
    private static final int MAXIMUM_MASTER_KEY_BYTES = 512;
    private static final DateTimeFormatter UTC_DAY = DateTimeFormatter.BASIC_ISO_DATE;

    private final byte[] masterKey;
    private final SecureRandom random;
    private final Clock clock;

    @Autowired
    public SkitProviderCallbackAuditFactory(
            @Value("${skit.ad.provider-callback-audit.hmac-key:}") String configuredKey) {
        this(configuredKey, new SecureRandom(), Clock.systemUTC());
    }

    SkitProviderCallbackAuditFactory(String configuredKey, SecureRandom random, Clock clock) {
        this.masterKey = copyConfiguredKey(configuredKey);
        this.random = Objects.requireNonNull(random, "random");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public ProviderIngressEvidence create(SkitCallbackRequestMetadata metadata,
                                          LocalDateTime authoritativeReceivedAt) {
        Objects.requireNonNull(metadata, "metadata");
        if (authoritativeReceivedAt == null || masterKey == null) {
            throw new IllegalStateException("Provider callback audit key is unavailable");
        }
        LocalDateTime clockNow = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)
                .withNano(0);
        if (Math.abs(java.time.Duration.between(clockNow,
                authoritativeReceivedAt).getSeconds()) > 120) {
            throw new IllegalArgumentException("Provider callback audit time is invalid");
        }

        byte[] packedAddress = null;
        byte[] accept = null;
        byte[] acceptEncoding = null;
        byte[] contentType = null;
        byte[] userAgent = null;
        byte[] dayBytes = null;
        byte[] dayKey = null;
        byte[] remoteAddressHash = null;
        byte[] userAgentHash = null;
        byte[] headerFingerprint = null;
        byte[] correlationId = new byte[16];
        try {
            packedAddress = metadata.getPackedClientAddress();
            accept = metadata.getAccept();
            acceptEncoding = metadata.getAcceptEncoding();
            contentType = metadata.getContentType();
            userAgent = metadata.getUserAgent();
            String day = authoritativeReceivedAt.toLocalDate().format(UTC_DAY);
            dayBytes = day.getBytes(StandardCharsets.US_ASCII);
            dayKey = hmacFramed(masterKey, DAY_DOMAIN, dayBytes);
            remoteAddressHash = hmacFramed(dayKey, REMOTE_ADDRESS_DOMAIN, packedAddress);
            if (userAgent != null) {
                userAgentHash = hmacFramed(dayKey, USER_AGENT_DOMAIN, userAgent);
            }
            headerFingerprint = hmacHeaders(dayKey,
                    accept, acceptEncoding, contentType, userAgent);
            random.nextBytes(correlationId);
            return ProviderIngressEvidence.of(correlationId, remoteAddressHash,
                    userAgentHash, headerFingerprint);
        } finally {
            wipe(packedAddress);
            wipe(accept);
            wipe(acceptEncoding);
            wipe(contentType);
            wipe(userAgent);
            wipe(dayBytes);
            wipe(dayKey);
            wipe(remoteAddressHash);
            wipe(userAgentHash);
            wipe(headerFingerprint);
            wipe(correlationId);
        }
    }

    @Override
    public String toString() {
        return "SkitProviderCallbackAuditFactory{masterKey=<redacted>}";
    }

    private static byte[] hmacFramed(byte[] key, String domain, byte[] value) {
        byte[] domainBytes = domain.getBytes(StandardCharsets.US_ASCII);
        try {
            Mac mac = initializedMac(key);
            updateFrame(mac, domainBytes);
            updateFrame(mac, value);
            return mac.doFinal();
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HmacSHA256 is unavailable", exception);
        } finally {
            wipe(domainBytes);
        }
    }

    private static byte[] hmacHeaders(byte[] key, byte[] accept, byte[] acceptEncoding,
                                      byte[] contentType, byte[] userAgent) {
        byte[] domain = HEADER_DOMAIN.getBytes(StandardCharsets.US_ASCII);
        try {
            Mac mac = initializedMac(key);
            updateFrame(mac, domain);
            updateHeader(mac, "accept", accept);
            updateHeader(mac, "accept-encoding", acceptEncoding);
            updateHeader(mac, "content-type", contentType);
            updateHeader(mac, "user-agent", userAgent);
            return mac.doFinal();
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HmacSHA256 is unavailable", exception);
        } finally {
            wipe(domain);
        }
    }

    private static Mac initializedMac(byte[] key) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac;
    }

    private static void updateHeader(Mac mac, String name, byte[] value) {
        byte[] nameBytes = name.getBytes(StandardCharsets.US_ASCII);
        try {
            updateFrame(mac, nameBytes);
            updateFrame(mac, value);
        } finally {
            wipe(nameBytes);
        }
    }

    private static void updateFrame(Mac mac, byte[] value) {
        int length = value == null ? -1 : value.length;
        byte[] lengthBytes = new byte[] {
                (byte) (length >>> 24), (byte) (length >>> 16),
                (byte) (length >>> 8), (byte) length};
        try {
            mac.update(lengthBytes);
            if (value != null) {
                mac.update(value);
            }
        } finally {
            wipe(lengthBytes);
        }
    }

    private static byte[] copyConfiguredKey(String configuredKey) {
        if (configuredKey == null || configuredKey.isEmpty()) {
            return null;
        }
        byte[] value = configuredKey.getBytes(StandardCharsets.UTF_8);
        if (value.length < MINIMUM_MASTER_KEY_BYTES || value.length > MAXIMUM_MASTER_KEY_BYTES
                || !isSafeConfiguredKey(value)) {
            wipe(value);
            return null;
        }
        return value;
    }

    private static boolean isSafeConfiguredKey(byte[] value) {
        for (byte item : value) {
            int current = item & 0xff;
            if (current < 0x21 || current > 0x7e) {
                return false;
            }
        }
        return true;
    }

    private static void wipe(byte[] value) {
        if (value != null) {
            Arrays.fill(value, (byte) 0);
        }
    }
}
