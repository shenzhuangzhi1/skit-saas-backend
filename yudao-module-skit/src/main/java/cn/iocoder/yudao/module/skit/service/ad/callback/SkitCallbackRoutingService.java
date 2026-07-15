package cn.iocoder.yudao.module.skit.service.ad.callback;

import cn.iocoder.yudao.module.skit.service.ad.SkitAdCredentialVersionService;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Objects;

/** Resolves a public callback key to the only tenant/account boundary allowed to process it. */
@Service
public class SkitCallbackRoutingService {

    private final SkitAdCredentialVersionService credentialService;

    public SkitCallbackRoutingService(SkitAdCredentialVersionService credentialService) {
        this.credentialService = Objects.requireNonNull(credentialService, "credentialService");
    }

    public CallbackRoute resolve(String callbackKey, LocalDateTime authoritativeReceivedAt) {
        SkitAdCredentialVersionService.CallbackKeyResolution resolved =
                credentialService.resolveCallbackKey(callbackKey, authoritativeReceivedAt);
        if (resolved == null || resolved.getTenantId() <= 0 || resolved.getAdAccountId() <= 0
                || resolved.getVersion() <= 0) {
            throw new IllegalStateException("Callback credential resolved outside a valid tenant boundary");
        }
        return new CallbackRoute(resolved.getTenantId(), resolved.getAdAccountId(),
                resolved.getVersion(), resolved.isActive(), resolved.getAcceptUntil());
    }

    public static final class CallbackRoute {

        private final long tenantId;
        private final long adAccountId;
        private final int callbackKeyVersion;
        private final boolean active;
        private final LocalDateTime acceptUntil;

        CallbackRoute(long tenantId, long adAccountId, int callbackKeyVersion,
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
