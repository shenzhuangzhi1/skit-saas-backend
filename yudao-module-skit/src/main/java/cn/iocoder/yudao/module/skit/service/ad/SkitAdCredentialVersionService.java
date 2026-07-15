package cn.iocoder.yudao.module.skit.service.ad;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Objects;
import java.util.function.Function;

public interface SkitAdCredentialVersionService {

    CallbackKeyIssue rotateCallbackKey(long tenantId, long adAccountId, Duration priorAcceptanceWindow);

    CredentialMetadata rotateRewardSecret(long tenantId, long adAccountId, byte[] rewardSecret,
                                          Duration priorAcceptanceWindow);

    CredentialMetadata getActiveCallbackKeyVersion(long tenantId, long adAccountId);

    CredentialMetadata getActiveRewardSecretVersion(long tenantId, long adAccountId);

    CallbackKeyResolution resolveCallbackKey(String callbackKey, LocalDateTime authoritativeReceivedAt);

    ResolvedRewardSecret resolveRewardSecret(long tenantId, long adAccountId, int secretVersion,
                                             LocalDateTime sessionRewardAcceptUntil,
                                             LocalDateTime authoritativeReceivedAt);

    /** Deterministic credential rejection. Infrastructure failures use their original exception. */
    final class CredentialUnavailableException extends IllegalStateException {

        public CredentialUnavailableException() {
            super("Credential is unavailable or outside its acceptance window");
        }
    }

    class CredentialMetadata {

        private final long tenantId;
        private final long adAccountId;
        private final int version;
        private final boolean active;
        private final LocalDateTime activatedAt;
        private final LocalDateTime acceptUntil;

        public CredentialMetadata(long tenantId, long adAccountId, int version,
                                  boolean active, LocalDateTime acceptUntil) {
            this(tenantId, adAccountId, version, active, null, acceptUntil);
        }

        public CredentialMetadata(long tenantId, long adAccountId, int version,
                                  boolean active, LocalDateTime activatedAt,
                                  LocalDateTime acceptUntil) {
            this.tenantId = tenantId;
            this.adAccountId = adAccountId;
            this.version = version;
            this.active = active;
            this.activatedAt = activatedAt;
            this.acceptUntil = acceptUntil;
        }

        public long getTenantId() {
            return tenantId;
        }

        public long getAdAccountId() {
            return adAccountId;
        }

        public int getVersion() {
            return version;
        }

        public boolean isActive() {
            return active;
        }

        public LocalDateTime getAcceptUntil() {
            return acceptUntil;
        }

        @JsonIgnore
        public LocalDateTime getActivatedAt() {
            return activatedAt;
        }

        @Override
        public String toString() {
            return "CredentialMetadata{tenantId=" + tenantId + ", adAccountId=" + adAccountId
                    + ", version=" + version + ", active=" + active + ", activatedAt=" + activatedAt
                    + ", acceptUntil=" + acceptUntil + '}';
        }
    }

    final class CallbackKeyIssue extends CredentialMetadata {

        @JsonIgnore
        private String callbackKey;

        public CallbackKeyIssue(long tenantId, long adAccountId, int version, String callbackKey) {
            this(tenantId, adAccountId, version, null, callbackKey);
        }

        public CallbackKeyIssue(long tenantId, long adAccountId, int version,
                                LocalDateTime activatedAt, String callbackKey) {
            super(tenantId, adAccountId, version, true, activatedAt, null);
            this.callbackKey = callbackKey;
        }

        @JsonIgnore
        public synchronized String consumeCallbackKey() {
            if (callbackKey == null) {
                throw new IllegalStateException("Callback key has already been consumed");
            }
            String result = callbackKey;
            callbackKey = null;
            return result;
        }

        @Override
        public String toString() {
            return "CallbackKeyIssue{tenantId=" + getTenantId() + ", adAccountId=" + getAdAccountId()
                    + ", version=" + getVersion() + ", callbackKey=<write-only>}";
        }
    }

    final class CallbackKeyResolution extends CredentialMetadata {

        public CallbackKeyResolution(long tenantId, long adAccountId, int version,
                                     boolean active, LocalDateTime acceptUntil) {
            super(tenantId, adAccountId, version, active, acceptUntil);
        }

        @Override
        public String toString() {
            return "CallbackKeyResolution{tenantId=" + getTenantId() + ", adAccountId=" + getAdAccountId()
                    + ", version=" + getVersion() + ", active=" + isActive()
                    + ", acceptUntil=" + getAcceptUntil() + '}';
        }
    }

    final class ResolvedRewardSecret extends CredentialMetadata implements AutoCloseable {

        @JsonIgnore
        private byte[] secret;

        public ResolvedRewardSecret(long tenantId, long adAccountId, int version,
                                    boolean active, LocalDateTime acceptUntil, byte[] secret) {
            super(tenantId, adAccountId, version, active, acceptUntil);
            this.secret = secret.clone();
        }

        @JsonIgnore
        public synchronized <T> T withSecret(Function<byte[], T> operation) {
            Objects.requireNonNull(operation, "operation");
            if (secret == null) {
                throw new IllegalStateException("Reward secret has already been consumed");
            }
            byte[] internal = secret;
            byte[] working = internal.clone();
            secret = null;
            try {
                return operation.apply(working);
            } finally {
                Arrays.fill(working, (byte) 0);
                Arrays.fill(internal, (byte) 0);
            }
        }

        @Override
        public synchronized void close() {
            if (secret != null) {
                Arrays.fill(secret, (byte) 0);
                secret = null;
            }
        }

        @Override
        public String toString() {
            return "ResolvedRewardSecret{tenantId=" + getTenantId() + ", adAccountId=" + getAdAccountId()
                    + ", version=" + getVersion() + ", secret=<redacted>}";
        }
    }

}
