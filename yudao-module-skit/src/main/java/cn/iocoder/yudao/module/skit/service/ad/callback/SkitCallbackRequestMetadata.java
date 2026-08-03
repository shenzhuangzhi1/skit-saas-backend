package cn.iocoder.yudao.module.skit.service.ad.callback;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonValue;
import com.google.common.net.InetAddresses;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Bounded, clearable request metadata. It contains only the trusted packed client address and the
 * exact provider-audit header allowlist.
 */
public final class SkitCallbackRequestMetadata implements AutoCloseable {

    public static final int MAX_HEADER_VALUE_BYTES = 4096;
    public static final int MAX_ALLOWLIST_BYTES = 8192;

    private final byte[] packedClientAddress;
    private final byte[] accept;
    private final byte[] acceptEncoding;
    private final byte[] contentType;
    private final byte[] userAgent;
    private boolean closed;

    private SkitCallbackRequestMetadata(String trustedClientIp, String accept,
                                        String acceptEncoding, String contentType,
                                        String userAgent) {
        if (trustedClientIp == null || !InetAddresses.isInetAddress(trustedClientIp)) {
            throw new IllegalArgumentException("Trusted callback client address is invalid");
        }
        this.packedClientAddress = InetAddresses.forString(trustedClientIp).getAddress().clone();
        this.accept = header(accept, "accept");
        this.acceptEncoding = header(acceptEncoding, "accept-encoding");
        this.contentType = header(contentType, "content-type");
        this.userAgent = header(userAgent, "user-agent");
        int total = length(this.accept) + length(this.acceptEncoding)
                + length(this.contentType) + length(this.userAgent);
        if (total > MAX_ALLOWLIST_BYTES) {
            close();
            throw new IllegalArgumentException("Callback header allowlist is oversized");
        }
    }

    public static SkitCallbackRequestMetadata of(String trustedClientIp, String accept,
                                                 String acceptEncoding, String contentType,
                                                 String userAgent) {
        return new SkitCallbackRequestMetadata(trustedClientIp, accept, acceptEncoding,
                contentType, userAgent);
    }

    @JsonIgnore
    public synchronized byte[] getPackedClientAddress() {
        requireOpen();
        return packedClientAddress.clone();
    }

    @JsonIgnore
    public synchronized byte[] getAccept() {
        return copy(accept);
    }

    @JsonIgnore
    public synchronized byte[] getAcceptEncoding() {
        return copy(acceptEncoding);
    }

    @JsonIgnore
    public synchronized byte[] getContentType() {
        return copy(contentType);
    }

    @JsonIgnore
    public synchronized byte[] getUserAgent() {
        return copy(userAgent);
    }

    public synchronized boolean isClosed() {
        return closed;
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        wipe(packedClientAddress);
        wipe(accept);
        wipe(acceptEncoding);
        wipe(contentType);
        wipe(userAgent);
        closed = true;
    }

    @Override
    public String toString() {
        return "SkitCallbackRequestMetadata{packedClientAddress=<redacted>, "
                + "headers=<redacted>}";
    }

    @JsonValue
    public String serializedForm() {
        return "<redacted>";
    }

    private byte[] copy(byte[] value) {
        requireOpen();
        return value == null ? null : value.clone();
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("Callback request metadata has been closed");
        }
    }

    private static byte[] header(String value, String name) {
        if (value == null) {
            return null;
        }
        if (value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0 || value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Callback " + name + " header is invalid");
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_HEADER_VALUE_BYTES) {
            Arrays.fill(bytes, (byte) 0);
            throw new IllegalArgumentException("Callback " + name + " header is oversized");
        }
        return bytes;
    }

    private static int length(byte[] value) {
        return value == null ? 0 : value.length;
    }

    private static void wipe(byte[] value) {
        if (value != null) {
            Arrays.fill(value, (byte) 0);
        }
    }
}
