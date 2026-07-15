package cn.iocoder.yudao.module.skit.service.reconciliation;

import java.time.LocalDateTime;
import java.util.function.Function;
import java.util.function.BiFunction;

public interface SkitReportingCredentialService {

    Metadata configure(long tenantId, long adAccountId, byte[] publisherKey);

    Metadata getMetadata(long tenantId, long adAccountId);

    <T> T withActivePublisherKey(long tenantId, long adAccountId, Function<byte[], T> consumer);

    <T> T withActivePublisherKeyVersion(long tenantId, long adAccountId,
                                        BiFunction<Integer, byte[], T> consumer);

    void markPermissionVerified(long tenantId, long adAccountId, int credentialVersion);

    final class Metadata {
        private final long tenantId;
        private final long adAccountId;
        private final int version;
        private final boolean active;
        private final LocalDateTime permissionVerifiedAt;

        public Metadata(long tenantId, long adAccountId, int version, boolean active,
                        LocalDateTime permissionVerifiedAt) {
            this.tenantId = tenantId;
            this.adAccountId = adAccountId;
            this.version = version;
            this.active = active;
            this.permissionVerifiedAt = permissionVerifiedAt;
        }

        public long getTenantId() { return tenantId; }
        public long getAdAccountId() { return adAccountId; }
        public int getVersion() { return version; }
        public boolean isActive() { return active; }
        public LocalDateTime getPermissionVerifiedAt() { return permissionVerifiedAt; }

        @Override
        public String toString() {
            return "Metadata{tenantId=" + tenantId + ", adAccountId=" + adAccountId
                    + ", version=" + version + ", active=" + active
                    + ", permissionVerifiedAt=" + permissionVerifiedAt + '}';
        }
    }
}
