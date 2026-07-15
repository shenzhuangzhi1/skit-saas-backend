package cn.iocoder.yudao.module.skit.framework.security;

import cn.iocoder.yudao.framework.common.util.servlet.ServletUtils;
import com.google.common.net.InetAddresses;
import org.springframework.stereotype.Component;

import javax.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Objects;

/**
 * Resolves a client IP from the immediate peer and one strict X-Real-IP value. X-Forwarded-For is
 * intentionally ignored: the edge proxy overwrites it only for downstream compatibility.
 */
@Component
public class SkitTrustedProxyClientIpResolver {

    private static final String REAL_IP_HEADER = "X-Real-IP";
    private static final int MAX_CIDR_COUNT = 128;
    private static final int MAX_IP_TEXT_LENGTH = 64;

    private final List<CidrBlock> trustedProxies;

    public SkitTrustedProxyClientIpResolver(SkitTrustedProxyProperties properties) {
        Objects.requireNonNull(properties, "properties");
        List<String> configured = properties.getTrustedProxyCidrs();
        if (configured == null || configured.isEmpty()) {
            this.trustedProxies = Collections.emptyList();
            return;
        }
        if (configured.size() > MAX_CIDR_COUNT) {
            throw new IllegalArgumentException("Too many trusted proxy CIDRs");
        }
        List<CidrBlock> parsed = new ArrayList<>(configured.size());
        for (String value : configured) parsed.add(CidrBlock.parse(value));
        this.trustedProxies = Collections.unmodifiableList(parsed);
    }

    public String resolveCurrentRequest() {
        HttpServletRequest request = ServletUtils.getRequest();
        if (request == null) throw new IllegalStateException("HTTP request is unavailable");
        return resolve(request);
    }

    public String resolve(HttpServletRequest request) {
        Objects.requireNonNull(request, "request");
        InetAddress remote = parseIp(request.getRemoteAddr(), "remote peer");
        String canonicalRemote = InetAddresses.toAddrString(remote);
        if (!isTrusted(remote)) return canonicalRemote;

        Enumeration<String> values = request.getHeaders(REAL_IP_HEADER);
        if (values == null || !values.hasMoreElements()) return canonicalRemote;
        String forwarded = values.nextElement();
        if (values.hasMoreElements()) {
            throw new IllegalArgumentException("X-Real-IP must contain exactly one value");
        }
        if (forwarded == null || forwarded.length() == 0
                || forwarded.length() > MAX_IP_TEXT_LENGTH
                || !forwarded.equals(forwarded.trim()) || forwarded.indexOf(',') >= 0) {
            throw new IllegalArgumentException("X-Real-IP is invalid");
        }
        return InetAddresses.toAddrString(parseIp(forwarded, REAL_IP_HEADER));
    }

    private boolean isTrusted(InetAddress address) {
        for (CidrBlock block : trustedProxies) {
            if (block.contains(address)) return true;
        }
        return false;
    }

    private static InetAddress parseIp(String value, String field) {
        if (value == null || value.isEmpty() || value.length() > MAX_IP_TEXT_LENGTH
                || !InetAddresses.isInetAddress(value)) {
            throw new IllegalArgumentException(field + " IP is invalid");
        }
        return InetAddresses.forString(value);
    }

    private static final class CidrBlock {
        private final byte[] network;
        private final int prefixLength;

        private CidrBlock(byte[] network, int prefixLength) {
            this.network = network;
            this.prefixLength = prefixLength;
        }

        private static CidrBlock parse(String value) {
            if (value == null || value.length() > 128 || !value.equals(value.trim())) {
                throw new IllegalArgumentException("Trusted proxy CIDR is invalid");
            }
            String[] parts = value.split("/", -1);
            if (parts.length != 2 || !InetAddresses.isInetAddress(parts[0])) {
                throw new IllegalArgumentException("Trusted proxy CIDR is invalid");
            }
            InetAddress address = InetAddresses.forString(parts[0]);
            int prefix;
            try {
                prefix = Integer.parseInt(parts[1]);
            } catch (NumberFormatException invalidPrefix) {
                throw new IllegalArgumentException("Trusted proxy CIDR is invalid", invalidPrefix);
            }
            byte[] network = address.getAddress();
            if (prefix < 0 || prefix > network.length * 8) {
                throw new IllegalArgumentException("Trusted proxy CIDR is invalid");
            }
            return new CidrBlock(network, prefix);
        }

        private boolean contains(InetAddress candidate) {
            byte[] address = candidate.getAddress();
            if (address.length != network.length) return false;
            int wholeBytes = prefixLength / 8;
            int remainingBits = prefixLength % 8;
            for (int index = 0; index < wholeBytes; index++) {
                if (address[index] != network[index]) return false;
            }
            if (remainingBits == 0) return true;
            int mask = 0xff & (0xff << (8 - remainingBits));
            return (address[wholeBytes] & mask) == (network[wholeBytes] & mask);
        }
    }
}
