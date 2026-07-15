package cn.iocoder.yudao.module.skit.service.reconciliation;

import lombok.Data;
import lombok.ToString;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/** Tenant-scoped, secret-free management contract for one Taku reporting account. */
public interface SkitReportingConfigurationService {

    View getConfiguration();

    View configure(Command command);

    @Data
    @Accessors(chain = true)
    class Command {
        private Integer credentialVersion;
        @ToString.Exclude
        private String publisherKey;
        private String reportTimezone;
        private String currency;
        private Integer amountScale;
        private String adFormat;

        public String auditCanonical() {
            return canonical(credentialVersion, publisherKey != null, reportTimezone,
                    currency, amountScale, adFormat);
        }
    }

    @Data
    @Accessors(chain = true)
    class View {
        private Long tenantId;
        private Long adAccountId;
        private String appId;
        private String placementId;
        private String reportTimezone;
        private String currency;
        private Integer amountScale;
        private String adFormat;
        private Boolean credentialConfigured;
        private Integer credentialVersion;
        private LocalDateTime permissionVerifiedAt;

        public String auditCanonical() {
            return canonical(tenantId, adAccountId, appId, placementId, reportTimezone,
                    currency, amountScale, adFormat, credentialConfigured,
                    credentialVersion, permissionVerifiedAt);
        }
    }

    static String canonical(Object... values) {
        StringBuilder result = new StringBuilder();
        for (Object value : values) {
            String text = String.valueOf(value);
            result.append(text.length()).append(':').append(text);
        }
        return result.toString();
    }
}
