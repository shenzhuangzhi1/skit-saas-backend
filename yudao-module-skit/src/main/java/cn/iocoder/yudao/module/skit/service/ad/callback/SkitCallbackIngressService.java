package cn.iocoder.yudao.module.skit.service.ad.callback;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonValue;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Objects;

/** Durable public callback ingress. Returning means the canonical result is committed. */
public interface SkitCallbackIngressService {

    IngressResponse receiveReward(String callbackKey, String rawQuery, String clientIp);

    IngressResponse receiveImpression(String callbackKey, String rawQuery, String clientIp);

    IngressResponse receiveReward(SkitCallbackRoutingService.CallbackRoute route,
                                  String rawQuery, TenantIngressEvidence evidence,
                                  LocalDateTime authoritativeReceivedAt);

    IngressResponse receiveImpression(SkitCallbackRoutingService.CallbackRoute route,
                                      String rawQuery, TenantIngressEvidence evidence,
                                      LocalDateTime authoritativeReceivedAt);

    /** Internal bridge for an account-level observation after server-owned tenant attribution. */
    IngressResponse receiveAttributedImpression(SkitCallbackRoutingService.CallbackRoute route,
                                                String rawQuery,
                                                LocalDateTime authoritativeReceivedAt);

    enum IngressResponse {
        OK(200),
        INVALID_SIGNATURE(601),
        REJECTED(602);

        private final int httpStatus;

        IngressResponse(int httpStatus) {
            this.httpStatus = httpStatus;
        }

        public int getHttpStatus() {
            return httpStatus;
        }
    }

    /** Raw-free edge evidence constructed before the dispatcher clears the callback key. */
    final class TenantIngressEvidence implements AutoCloseable {

        private final byte[] callbackKeyHash;
        private final byte[] clientIpHash;
        private boolean closed;

        private TenantIngressEvidence(byte[] callbackKeyHash, byte[] clientIpHash) {
            this.callbackKeyHash = fixed(callbackKeyHash, "callbackKeyHash");
            this.clientIpHash = fixed(clientIpHash, "clientIpHash");
        }

        public static TenantIngressEvidence of(byte[] callbackKeyHash, byte[] clientIpHash) {
            return new TenantIngressEvidence(callbackKeyHash, clientIpHash);
        }

        @JsonIgnore
        public synchronized byte[] getCallbackKeyHash() {
            requireOpen();
            return callbackKeyHash.clone();
        }

        @JsonIgnore
        public synchronized byte[] getClientIpHash() {
            requireOpen();
            return clientIpHash.clone();
        }

        @Override
        public synchronized void close() {
            if (closed) {
                return;
            }
            Arrays.fill(callbackKeyHash, (byte) 0);
            Arrays.fill(clientIpHash, (byte) 0);
            closed = true;
        }

        @Override
        public String toString() {
            return "TenantIngressEvidence{callbackKeyHash=<redacted>, clientIpHash=<redacted>}";
        }

        @JsonValue
        public String serializedForm() {
            return "<redacted>";
        }

        private static byte[] fixed(byte[] value, String field) {
            byte[] copy = Objects.requireNonNull(value, field).clone();
            if (copy.length != 32) {
                Arrays.fill(copy, (byte) 0);
                throw new IllegalArgumentException(field + " must contain 32 bytes");
            }
            return copy;
        }

        private void requireOpen() {
            if (closed) {
                throw new IllegalStateException("Tenant ingress evidence has been closed");
            }
        }
    }

}
