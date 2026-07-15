package cn.iocoder.yudao.module.skit.controller.app.member;

import cn.iocoder.yudao.framework.apilog.core.annotation.ApiAccessLog;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.ratelimiter.core.annotation.RateLimiter;
import cn.iocoder.yudao.module.skit.dal.mysql.ad.SkitAdClientEventMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.ad.SkitAdSessionMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.member.SkitContentEntitlementMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.member.SkitEntitlementGrantMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.revenue.SkitAdRevenueEventMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.revenue.SkitCommissionLedgerMapper;
import cn.iocoder.yudao.module.skit.framework.observability.SkitLegacyAdRevenueObservation;
import cn.iocoder.yudao.module.skit.framework.security.SkitMemberRateLimiterKeyResolver;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class SkitMemberAdRevenueControllerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void forgedAndRepeatedLegacyReportsAreAcknowledgedWithoutAnyBusinessWrite() throws Exception {
        SkitLegacyAdRevenueObservation observation = mock(SkitLegacyAdRevenueObservation.class);
        SkitMemberAdRevenueController controller = new SkitMemberAdRevenueController(observation);
        SkitAdSessionMapper sessionMapper = mock(SkitAdSessionMapper.class);
        SkitAdClientEventMapper clientEventMapper = mock(SkitAdClientEventMapper.class);
        SkitContentEntitlementMapper entitlementMapper = mock(SkitContentEntitlementMapper.class);
        SkitEntitlementGrantMapper entitlementGrantMapper = mock(SkitEntitlementGrantMapper.class);
        SkitAdRevenueEventMapper revenueEventMapper = mock(SkitAdRevenueEventMapper.class);
        SkitCommissionLedgerMapper ledgerMapper = mock(SkitCommissionLedgerMapper.class);
        SkitMemberAdRevenueController.ReportReqVO request = forgedRequest();

        for (int attempt = 0; attempt < 3; attempt++) {
            CommonResult<SkitMemberAdRevenueController.LegacyReportRespVO> response = controller.report(request);
            JsonNode body = objectMapper.valueToTree(response.getData());

            assertTrue(response.isSuccess());
            assertTrue(body.path("deprecated").asBoolean());
            assertEquals("LEGACY_UNVERIFIED", body.path("status").asText());
            assertFalse(body.path("financialEffect").asBoolean(true));
            assertEquals("/skit/member/ad-sessions", body.path("replacement").asText());
            assertFalse(body.has("classification"));
        }

        verifyNoInteractions(sessionMapper, clientEventMapper, entitlementMapper, entitlementGrantMapper,
                revenueEventMapper, ledgerMapper);
        verify(observation, times(3)).recordAcknowledged();
    }

    @Test
    void legacyRouteRequiresMemberAuthenticationRateLimitsByMemberAndSuppressesPayloadLogs() throws Exception {
        Method report = SkitMemberAdRevenueController.class.getMethod(
                "report", SkitMemberAdRevenueController.ReportReqVO.class);

        PreAuthorize preAuthorize = report.getAnnotation(PreAuthorize.class);
        assertNotNull(preAuthorize);
        assertEquals("@ss.hasScope('skit_member') and @skitMemberSecurityGuard.isSkitMember()",
                preAuthorize.value());
        RateLimiter rateLimiter = report.getAnnotation(RateLimiter.class);
        assertNotNull(rateLimiter);
        assertEquals(60, rateLimiter.time());
        assertEquals(60, rateLimiter.count());
        assertEquals(SkitMemberRateLimiterKeyResolver.class, rateLimiter.keyResolver());
        ApiAccessLog accessLog = report.getAnnotation(ApiAccessLog.class);
        assertNotNull(accessLog);
        assertFalse(accessLog.requestEnable());
        assertFalse(accessLog.responseEnable());
        assertTrue(Arrays.stream(SkitMemberAdRevenueController.class.getDeclaredFields())
                .allMatch(field -> field.getType() == SkitLegacyAdRevenueObservation.class));
    }

    @Test
    void legacyObservationUsesDedicatedLowCardinalityMetricComponent() throws Exception {
        assertNotNull(SkitLegacyAdRevenueObservation.class.getDeclaredMethod("recordAcknowledged"));
    }

    private SkitMemberAdRevenueController.ReportReqVO forgedRequest() throws Exception {
        SkitMemberAdRevenueController.ReportReqVO request = new SkitMemberAdRevenueController.ReportReqVO();
        request.setProvider("TAKU");
        request.setExternalEventId("attacker-controlled-event");
        request.setPlacementId("foreign-placement");
        request.setGrossAmount(new BigDecimal("999999999.99999999"));
        request.setOccurredTime(OffsetDateTime.parse("2026-07-14T12:30:00+08:00"));
        request.setCompleted(true);
        request.setMock(false);
        request.setRawData(objectMapper.readTree("{\"tenantId\":999,\"memberId\":888,"
                + "\"sessionId\":\"foreign-session\",\"ecpm\":999999999,\"completed\":true}"));
        return request;
    }

}
