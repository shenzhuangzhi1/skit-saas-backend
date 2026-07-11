package cn.iocoder.yudao.module.skit.service.revenue;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static cn.iocoder.yudao.module.skit.enums.SkitDomainConstants.MONEY_SCALE;

/** 客户端预估收益上报的纯校验 seam。 */
public class SkitRevenueReportValidator {

    /** Generous per-impression ceiling; client reports above this are never credible and are rejected as abuse. */
    static final BigDecimal MAX_GROSS_EXCLUSIVE = new BigDecimal("1000");
    static final int MAX_RAW_DATA_BYTES = 16 * 1024;

    public BigDecimal normalizeMoney(BigDecimal amount) {
        if (amount == null || amount.signum() < 0 || amount.abs().compareTo(MAX_GROSS_EXCLUSIVE) >= 0) {
            throw new IllegalArgumentException("grossAmount 必须在 0 到 1000 元之间");
        }
        try {
            return amount.setScale(MONEY_SCALE, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException ex) {
            throw new IllegalArgumentException("grossAmount 最多支持 8 位小数", ex);
        }
    }

    public void validateOccurredTime(OffsetDateTime occurredTime, Instant now) {
        if (occurredTime == null || now == null) {
            throw new IllegalArgumentException("occurredTime 不能为空");
        }
        Instant occurred = occurredTime.toInstant();
        if (occurred.isAfter(now.plusSeconds(10 * 60L))
                || occurred.isBefore(now.minusSeconds(30 * 24 * 3600L))) {
            throw new IllegalArgumentException("occurredTime 必须在最近 30 天内且不能超前 10 分钟");
        }
    }

    public void validateRawData(String rawData) {
        if (rawData != null && rawData.getBytes(StandardCharsets.UTF_8).length > MAX_RAW_DATA_BYTES) {
            throw new IllegalArgumentException("rawData 最大 16 KB");
        }
    }

    public LocalDateTime normalizeOccurredTime(OffsetDateTime occurredTime) {
        return occurredTime.withOffsetSameInstant(ZoneOffset.UTC).withNano(0).toLocalDateTime();
    }
}
