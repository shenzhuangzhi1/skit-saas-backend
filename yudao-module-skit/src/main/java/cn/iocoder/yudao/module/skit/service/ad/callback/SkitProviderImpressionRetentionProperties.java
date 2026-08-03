package cn.iocoder.yudao.module.skit.service.ad.callback;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Objects;

/** Shared payload expiry and purge batch policy for provider impression evidence. */
@Component
@ConfigurationProperties(prefix = "skit.ad.provider-impression.retention")
public class SkitProviderImpressionRetentionProperties {

    private static final int MIN_DAYS = 1;
    private static final int MAX_DAYS = 30;
    private static final int MIN_BATCH_SIZE = 1;
    private static final int MAX_BATCH_SIZE = 1000;
    private static final int MIN_BATCHES_PER_RUN = 1;
    private static final int MAX_BATCHES_PER_RUN = 300;

    private int days = 7;
    private int batchSize = 200;
    private int maxBatchesPerRun = 120;

    public int getDays() {
        return days;
    }

    public void setDays(int days) {
        if (days < MIN_DAYS || days > MAX_DAYS) {
            throw new IllegalArgumentException("Provider impression retention days must be between 1 and 30");
        }
        this.days = days;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        if (batchSize < MIN_BATCH_SIZE || batchSize > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException("Provider impression retention batch size must be between 1 and 1000");
        }
        this.batchSize = batchSize;
    }

    public int getMaxBatchesPerRun() {
        return maxBatchesPerRun;
    }

    public void setMaxBatchesPerRun(int maxBatchesPerRun) {
        if (maxBatchesPerRun < MIN_BATCHES_PER_RUN
                || maxBatchesPerRun > MAX_BATCHES_PER_RUN) {
            throw new IllegalArgumentException(
                    "Provider impression retention batches per run must be between 1 and 300");
        }
        this.maxBatchesPerRun = maxBatchesPerRun;
    }

    public LocalDateTime expiresAt(LocalDateTime receivedAt) {
        return Objects.requireNonNull(receivedAt, "receivedAt").plusDays(days);
    }

    @Override
    public String toString() {
        return "SkitProviderImpressionRetentionProperties{days=" + days
                + ", batchSize=" + batchSize
                + ", maxBatchesPerRun=" + maxBatchesPerRun + '}';
    }
}
