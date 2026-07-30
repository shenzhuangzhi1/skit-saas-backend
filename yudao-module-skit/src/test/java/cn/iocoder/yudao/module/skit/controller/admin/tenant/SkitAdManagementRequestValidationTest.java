package cn.iocoder.yudao.module.skit.controller.admin.tenant;

import cn.iocoder.yudao.module.skit.controller.admin.tenant.vo.SkitAdAnalyticsQueryReqVO;
import cn.iocoder.yudao.module.skit.controller.admin.tenant.vo.SkitAdEventPageReqVO;
import cn.iocoder.yudao.module.skit.controller.admin.tenant.vo.SkitAdRewardSecretRotateReqVO;
import cn.iocoder.yudao.module.skit.controller.admin.tenant.vo.SkitReconciliationPageReqVO;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import javax.validation.Validation;
import javax.validation.Validator;
import javax.validation.ValidatorFactory;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkitAdManagementRequestValidationTest {

    private static final ValidatorFactory VALIDATOR_FACTORY =
            Validation.buildDefaultValidatorFactory();
    private static final Validator VALIDATOR = VALIDATOR_FACTORY.getValidator();

    @AfterAll
    static void closeValidatorFactory() {
        VALIDATOR_FACTORY.close();
    }

    @Test
    void monitoringTimezonesAcceptOnlyTakuCanonicalFixedOffsets() throws Exception {
        for (String timezone : new String[]{"UTC-8", "UTC+8", "UTC+0"}) {
            SkitAdAnalyticsQueryReqVO request = new SkitAdAnalyticsQueryReqVO();
            request.setTimezone(timezone);
            assertTrue(VALIDATOR.validate(request).isEmpty(), timezone);
        }
        for (String timezone : new String[]{"Asia/Shanghai", "GMT+8", "UTC+09", ""}) {
            SkitAdAnalyticsQueryReqVO request = new SkitAdAnalyticsQueryReqVO();
            request.setTimezone(timezone);
            assertFalse(VALIDATOR.validate(request).isEmpty(), timezone);
        }

        Method detail = SkitAdEventController.class.getMethod("get", Long.class, Long.class,
                String.class);
        assertFalse(VALIDATOR.forExecutables().validateParameters(
                new SkitAdEventController(null, null), detail,
                new Object[]{1L, null, "Asia/Shanghai"}).isEmpty());
    }

    @Test
    void eventPageRejectsUnknownCanonicalFilterValuesAtControllerBoundary() throws Exception {
        Method page = SkitAdEventController.class.getMethod("getPage", SkitAdEventPageReqVO.class);
        SkitAdEventController controller = new SkitAdEventController(null, null);

        assertInvalidEventPage(controller, page, request -> request.setProvider("taku"));
        assertInvalidEventPage(controller, page, request -> request.setMatchStatus("MATCH"));
        assertInvalidEventPage(controller, page,
                request -> request.setSourceVerificationStatus("VERIFIED"));
        assertInvalidEventPage(controller, page,
                request -> request.setReconciliationStatus("DONE"));
    }

    @Test
    void reconciliationPageRejectsUnknownStatusAtControllerBoundary() throws Exception {
        Method page = SkitReconciliationController.class.getMethod("getPage",
                SkitReconciliationPageReqVO.class);
        SkitReconciliationPageReqVO request = new SkitReconciliationPageReqVO();
        request.setStatus("DONE");

        assertFalse(VALIDATOR.forExecutables().validateParameters(
                new SkitReconciliationController(null, null), page, new Object[]{request}).isEmpty());
    }

    @Test
    void pangleRewardSecurityKeysUseExactUtf8ByteBoundsAtBothAdminBoundaries() {
        SkitTenantBusinessController.AdAccountSaveReqVO legacy =
                new SkitTenantBusinessController.AdAccountSaveReqVO();
        legacy.setPangleEnabled(false);
        legacy.setTakuEnabled(false);
        legacy.setReason("validate legacy Pangle reward secret");

        legacy.setPangleRewardSecurityKey("1234567");
        assertFalse(VALIDATOR.validate(legacy).isEmpty());
        legacy.setPangleRewardSecurityKey("😀😀");
        assertTrue(VALIDATOR.validate(legacy).isEmpty());
        legacy.setPangleRewardSecurityKey(repeat("😀", 512));
        assertTrue(VALIDATOR.validate(legacy).isEmpty());
        legacy.setPangleRewardSecurityKey(repeat("😀", 513));
        assertFalse(VALIDATOR.validate(legacy).isEmpty());
        legacy.setPangleRewardSecurityKey(repeat(" ", 2049));
        assertFalse(VALIDATOR.validate(legacy).isEmpty());

        SkitAdRewardSecretRotateReqVO rotate = new SkitAdRewardSecretRotateReqVO();
        rotate.setAdAccountId(4201L);
        rotate.setExpectedReadinessVersion(7);
        rotate.setPriorAcceptanceMinutes(15);
        rotate.setReason("validate rotated Pangle reward secret");

        rotate.setRewardSecret("1234567".toCharArray());
        assertFalse(VALIDATOR.validate(rotate).isEmpty());
        rotate.setRewardSecret("😀😀".toCharArray());
        assertTrue(VALIDATOR.validate(rotate).isEmpty());
        rotate.setRewardSecret(repeat("😀", 512).toCharArray());
        assertTrue(VALIDATOR.validate(rotate).isEmpty());
        rotate.setRewardSecret(repeat("😀", 513).toCharArray());
        assertFalse(VALIDATOR.validate(rotate).isEmpty());
    }

    private static String repeat(String value, int count) {
        StringBuilder result = new StringBuilder(value.length() * count);
        for (int i = 0; i < count; i++) {
            result.append(value);
        }
        return result.toString();
    }

    private void assertInvalidEventPage(SkitAdEventController controller, Method method,
                                        java.util.function.Consumer<SkitAdEventPageReqVO> mutation) {
        SkitAdEventPageReqVO request = new SkitAdEventPageReqVO();
        mutation.accept(request);
        assertFalse(VALIDATOR.forExecutables().validateParameters(
                controller, method, new Object[]{request}).isEmpty());
    }

}
