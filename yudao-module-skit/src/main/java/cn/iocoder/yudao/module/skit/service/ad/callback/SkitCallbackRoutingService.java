package cn.iocoder.yudao.module.skit.service.ad.callback;

import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Objects;

/** Resolves a public callback key to the only tenant/account boundary allowed to process it. */
@Service
public class SkitCallbackRoutingService {

    private final SkitCallbackRouteRegistryService registryService;

    public SkitCallbackRoutingService(SkitCallbackRouteRegistryService registryService) {
        this.registryService = Objects.requireNonNull(registryService, "registryService");
    }

    /** Backward-compatible tenant callback entry used by the existing reward and tenant-impression flows. */
    public CallbackRoute resolve(String callbackKey, LocalDateTime authoritativeReceivedAt) {
        try {
            return resolveTenantReward(callbackKey, authoritativeReceivedAt);
        } catch (SkitCallbackRouteRegistryService.CallbackRouteRejectedException rejected) {
            // Existing ingress consumers already map this stable credential exception to provider rejection.
            throw new cn.iocoder.yudao.module.skit.service.ad.SkitAdCredentialVersionService
                    .CredentialUnavailableException();
        }
    }

    /** Resolves only the tenant registry owner; provider callback routes can never enter reward handling. */
    public CallbackRoute resolveTenantReward(String rawKey, LocalDateTime authoritativeReceivedAt) {
        if (rawKey == null || !rawKey.matches("^[A-Za-z0-9_-]{43}$")
                || authoritativeReceivedAt == null) {
            throw new SkitCallbackRouteRegistryService.CallbackRouteRejectedException();
        }
        byte[] keyHash = sha256(rawKey);
        SkitCallbackRouteRegistryService.RouteLookup resolved;
        try {
            resolved = registryService.lookupTenantReward(keyHash, authoritativeReceivedAt);
        } finally {
            Arrays.fill(keyHash, (byte) 0);
        }
        if (resolved == null
                || resolved.getRouteType()
                != SkitCallbackRouteRegistryService.RouteType.TENANT_CALLBACK_KEY
                || resolved.getTenantId() <= 0 || resolved.getAdAccountId() <= 0
                || resolved.getKeyVersion() <= 0) {
            throw new SkitCallbackRouteRegistryService.CallbackRouteRejectedException();
        }
        return new CallbackRoute(resolved.getTenantId(), resolved.getAdAccountId(),
                resolved.getKeyVersion(), resolved.isActive(), resolved.getAcceptUntil());
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.US_ASCII));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public static final class CallbackRoute {

        private final long tenantId;
        private final long adAccountId;
        private final int callbackKeyVersion;
        private final boolean active;
        private final LocalDateTime acceptUntil;

        public CallbackRoute(long tenantId, long adAccountId, int callbackKeyVersion,
                             boolean active, LocalDateTime acceptUntil) {
            this.tenantId = tenantId;
            this.adAccountId = adAccountId;
            this.callbackKeyVersion = callbackKeyVersion;
            this.active = active;
            this.acceptUntil = acceptUntil;
        }

        public long getTenantId() {
            return tenantId;
        }

        public long getAdAccountId() {
            return adAccountId;
        }

        public int getCallbackKeyVersion() {
            return callbackKeyVersion;
        }

        public boolean isActive() {
            return active;
        }

        public LocalDateTime getAcceptUntil() {
            return acceptUntil;
        }

        @Override
        public String toString() {
            return "CallbackRoute{tenantId=" + tenantId + ", adAccountId=" + adAccountId
                    + ", callbackKeyVersion=" + callbackKeyVersion + ", active=" + active
                    + ", acceptUntil=" + acceptUntil + '}';
        }
    }

}
