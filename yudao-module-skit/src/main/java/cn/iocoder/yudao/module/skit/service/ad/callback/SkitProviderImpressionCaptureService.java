package cn.iocoder.yudao.module.skit.service.ad.callback;

import cn.iocoder.yudao.module.skit.service.provider.SkitProviderConnectionService.ProviderRouteResolution;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Objects;

public interface SkitProviderImpressionCaptureService {

    CaptureDecision capture(ProviderRouteResolution route,
                            SkitProviderImpressionWireParser.WirePayload wirePayload,
                            ProviderIngressEvidence evidence,
                            LocalDateTime receivedAt);

    CaptureDecision capture(ProviderRouteResolution route, String rawQuery,
                            ProviderIngressEvidence evidence, LocalDateTime receivedAt);

    enum CaptureDecision {
        ACK_200,
        REJECT_602,
        PERSISTENCE_FAILURE_503
    }

    final class ProviderIngressEvidence implements AutoCloseable {

        private final byte[] correlationId;
        private final byte[] remoteAddressHash;
        private final byte[] userAgentHash;
        private final byte[] requestHeaderFingerprint;
        private final String traceId;
        private boolean closed;

        private ProviderIngressEvidence(byte[] correlationId, byte[] remoteAddressHash,
                                        byte[] userAgentHash, byte[] requestHeaderFingerprint) {
            this.correlationId = fixed(correlationId, 16, "correlationId");
            this.remoteAddressHash = fixed(remoteAddressHash, 32, "remoteAddressHash");
            this.userAgentHash = userAgentHash == null ? null
                    : fixed(userAgentHash, 32, "userAgentHash");
            this.requestHeaderFingerprint = fixed(requestHeaderFingerprint, 32,
                    "requestHeaderFingerprint");
            this.traceId = "pci-" + lowerHex(this.correlationId);
        }

        public static ProviderIngressEvidence of(byte[] correlationId, byte[] remoteAddressHash,
                                                 byte[] userAgentHash,
                                                 byte[] requestHeaderFingerprint) {
            return new ProviderIngressEvidence(correlationId, remoteAddressHash,
                    userAgentHash, requestHeaderFingerprint);
        }

        public byte[] getCorrelationId() {
            requireOpen();
            return correlationId.clone();
        }

        public byte[] getRemoteAddressHash() {
            requireOpen();
            return remoteAddressHash.clone();
        }

        public byte[] getUserAgentHash() {
            requireOpen();
            return userAgentHash == null ? null : userAgentHash.clone();
        }

        public byte[] getRequestHeaderFingerprint() {
            requireOpen();
            return requestHeaderFingerprint.clone();
        }

        public String getTraceId() {
            return traceId;
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
            Arrays.fill(remoteAddressHash, (byte) 0);
            if (userAgentHash != null) {
                Arrays.fill(userAgentHash, (byte) 0);
            }
            Arrays.fill(requestHeaderFingerprint, (byte) 0);
            closed = true;
        }

        @Override
        public String toString() {
            return "ProviderIngressEvidence{correlationId=<redacted>, remoteAddressHash=<redacted>, "
                    + "userAgentHash=" + (userAgentHash == null ? "<absent>" : "<redacted>")
                    + ", requestHeaderFingerprint=<redacted>, traceId='" + traceId + "'}";
        }

        private static byte[] fixed(byte[] value, int length, String field) {
            byte[] copy = Objects.requireNonNull(value, field).clone();
            if (copy.length != length) {
                throw new IllegalArgumentException(field + " must contain " + length + " bytes");
            }
            return copy;
        }

        private static String lowerHex(byte[] bytes) {
            char[] result = new char[bytes.length * 2];
            char[] alphabet = "0123456789abcdef".toCharArray();
            for (int index = 0; index < bytes.length; index++) {
                int value = bytes[index] & 0xff;
                result[index * 2] = alphabet[value >>> 4];
                result[index * 2 + 1] = alphabet[value & 0x0f];
            }
            return new String(result);
        }

        private void requireOpen() {
            if (closed) {
                throw new IllegalStateException("Provider ingress evidence has been closed");
            }
        }
    }
}
