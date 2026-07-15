package cn.iocoder.yudao.module.skit.service.analytics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SkitAdAnalyticsServiceImplTest {

    @Test
    void healthAndFreshnessUseCanonicalPersistedSuccessStates() {
        assertEquals("SUCCEEDED", SkitAdAnalyticsServiceImpl.reportSuccessStatus());
        assertEquals("`processing_status`='SUCCEEDED' AND `authentication_level`='SIGNED_REWARD' "
                        + "AND `signature_status`='VALID'",
                SkitAdAnalyticsServiceImpl.canonicalRewardCallbackSuccessPredicate());
    }

    @Test
    void canonicalBucketAmountsMustConserveAtEveryCurrencyScale() {
        assertDoesNotThrow(() -> SkitAdAnalyticsServiceImpl.requireConserved(100L, 70L, 30L));
        assertThrows(IllegalStateException.class,
                () -> SkitAdAnalyticsServiceImpl.requireConserved(100L, 69L, 30L));
    }

}
