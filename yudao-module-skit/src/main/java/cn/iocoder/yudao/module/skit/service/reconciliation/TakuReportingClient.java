package cn.iocoder.yudao.module.skit.service.reconciliation;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public interface TakuReportingClient {

    ReportResponse fetch(ReportRequest request, byte[] publisherKey);

    final class ReportRequest {
        private final LocalDate startDate;
        private final LocalDate endDate;
        private final String appId;
        private final String placementId;
        private final String adFormat;
        private final String reportTimezone;
        private final String currency;
        private final int amountScale;

        public ReportRequest(LocalDate startDate, LocalDate endDate, String appId,
                             String placementId, String reportTimezone, String currency,
                             int amountScale) {
            this(startDate, endDate, appId, placementId, "rewarded_video",
                    reportTimezone, currency, amountScale);
        }

        public ReportRequest(LocalDate startDate, LocalDate endDate, String appId,
                             String placementId, String adFormat, String reportTimezone,
                             String currency, int amountScale) {
            this.startDate = Objects.requireNonNull(startDate, "startDate");
            this.endDate = Objects.requireNonNull(endDate, "endDate");
            if (endDate.isBefore(startDate) || ChronoUnit.DAYS.between(startDate, endDate) > 1L) {
                throw new IllegalArgumentException("Taku report range must contain at most two dates");
            }
            this.appId = canonical(appId, "appId", 128);
            this.placementId = canonical(placementId, "placementId", 128);
            this.adFormat = canonical(adFormat, "adFormat", 32);
            if (!"rewarded_video".equals(this.adFormat)) {
                throw new IllegalArgumentException("Only the dedicated rewarded-video format is settleable");
            }
            this.reportTimezone = canonical(reportTimezone, "reportTimezone", 64);
            if (!java.util.Arrays.asList("UTC-8", "UTC+8", "UTC+0").contains(reportTimezone)) {
                throw new IllegalArgumentException("reportTimezone is not supported by Taku fullreport");
            }
            if (currency == null || !currency.matches("[A-Z]{3}")) {
                throw new IllegalArgumentException("currency must be a three-letter uppercase code");
            }
            if (amountScale < 0 || amountScale > 18) {
                throw new IllegalArgumentException("amountScale must be between 0 and 18");
            }
            this.currency = currency;
            this.amountScale = amountScale;
        }

        public LocalDate getStartDate() { return startDate; }
        public LocalDate getEndDate() { return endDate; }
        public String getAppId() { return appId; }
        public String getPlacementId() { return placementId; }
        public String getAdFormat() { return adFormat; }
        public String getReportTimezone() { return reportTimezone; }
        public String getCurrency() { return currency; }
        public int getAmountScale() { return amountScale; }

        private static String canonical(String value, String name, int maximum) {
            if (value == null || value.isEmpty() || value.length() > maximum
                    || !value.equals(value.trim())) {
                throw new IllegalArgumentException(name + " is not canonical");
            }
            return value;
        }
    }

    final class ReportRecord {
        private final LocalDate reportDate;
        private final String appId;
        private final String placementId;
        private final String adFormat;
        private final int networkFirmId;
        private final String networkAccountId;
        private final String adsourceId;
        private final String revenue;
        private final Long impressionApi;

        public ReportRecord(LocalDate reportDate, String appId, String placementId,
                            String adFormat, int networkFirmId, String networkAccountId,
                            String adsourceId, String revenue, Long impressionApi) {
            this.reportDate = reportDate;
            this.appId = appId;
            this.placementId = placementId;
            this.adFormat = adFormat;
            this.networkFirmId = networkFirmId;
            this.networkAccountId = networkAccountId;
            this.adsourceId = adsourceId;
            this.revenue = revenue;
            this.impressionApi = impressionApi;
        }

        public LocalDate getReportDate() { return reportDate; }
        public String getAppId() { return appId; }
        public String getPlacementId() { return placementId; }
        public String getAdFormat() { return adFormat; }
        public int getNetworkFirmId() { return networkFirmId; }
        public String getNetworkAccountId() { return networkAccountId; }
        public String getAdsourceId() { return adsourceId; }
        public String getRevenue() { return revenue; }
        public Long getImpressionApi() { return impressionApi; }
    }

    final class ReportResponse {
        private final String reportTimezone;
        private final String currency;
        private final List<ReportRecord> records;

        public ReportResponse(String reportTimezone, String currency, List<ReportRecord> records) {
            this.reportTimezone = reportTimezone;
            this.currency = currency;
            this.records = Collections.unmodifiableList(new ArrayList<>(records));
        }

        public String getReportTimezone() { return reportTimezone; }
        public String getCurrency() { return currency; }
        public List<ReportRecord> getRecords() { return records; }
    }
}
