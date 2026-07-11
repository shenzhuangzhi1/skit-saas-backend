package cn.iocoder.yudao.module.skit.service.revenue;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SkitRevenueReportValidatorTest {

    private final SkitRevenueReportValidator validator = new SkitRevenueReportValidator();

    @Test
    void shouldNormalizeEightDecimalMoneyAndRejectUnsafeAmounts() {
        assertEquals(new BigDecimal("12.34000000"), validator.normalizeMoney(new BigDecimal("12.34")));
        assertThrows(IllegalArgumentException.class,
                () -> validator.normalizeMoney(new BigDecimal("0.123456789")));
        assertThrows(IllegalArgumentException.class,
                () -> validator.normalizeMoney(new BigDecimal("1000")));
        assertThrows(IllegalArgumentException.class,
                () -> validator.normalizeMoney(new BigDecimal("-0.01")));
    }

    @Test
    void shouldRejectStaleOrFutureOccurredTimeAndNormalizeToUtcSeconds() {
        Instant now = Instant.parse("2026-07-11T12:00:00Z");
        validator.validateOccurredTime(OffsetDateTime.parse("2026-07-11T12:09:59Z"), now);
        assertThrows(IllegalArgumentException.class,
                () -> validator.validateOccurredTime(OffsetDateTime.parse("2026-07-11T12:10:01Z"), now));
        assertThrows(IllegalArgumentException.class,
                () -> validator.validateOccurredTime(OffsetDateTime.parse("2026-06-11T11:59:59Z"), now));
        assertEquals(LocalDateTime.of(2026, 7, 11, 12, 0, 0),
                validator.normalizeOccurredTime(OffsetDateTime.of(
                        2026, 7, 11, 20, 0, 0, 999_000_000, ZoneOffset.ofHours(8))));
    }

    @Test
    void shouldRejectOversizedRawData() {
        validator.validateRawData(repeat('a', SkitRevenueReportValidator.MAX_RAW_DATA_BYTES));
        assertThrows(IllegalArgumentException.class, () -> validator.validateRawData(
                repeat('a', SkitRevenueReportValidator.MAX_RAW_DATA_BYTES + 1)));
        assertThrows(IllegalArgumentException.class, () -> validator.validateRawData(
                repeat('中', SkitRevenueReportValidator.MAX_RAW_DATA_BYTES / 2)));
    }

    private String repeat(char value, int count) {
        StringBuilder builder = new StringBuilder(count);
        for (int index = 0; index < count; index++) {
            builder.append(value);
        }
        return builder.toString();
    }
}
