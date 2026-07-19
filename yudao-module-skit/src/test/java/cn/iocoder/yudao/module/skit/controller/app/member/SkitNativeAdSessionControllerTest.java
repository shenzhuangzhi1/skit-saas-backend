package cn.iocoder.yudao.module.skit.controller.app.member;

import cn.iocoder.yudao.framework.apilog.core.annotation.ApiAccessLog;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.ratelimiter.core.annotation.RateLimiter;
import cn.iocoder.yudao.framework.tenant.core.aop.TenantIgnore;
import cn.iocoder.yudao.module.skit.controller.app.member.vo.ad.SkitAdClientEventBatchReqVO;
import cn.iocoder.yudao.module.skit.controller.app.member.vo.ad.SkitAdClientEventReqVO;
import cn.iocoder.yudao.module.skit.controller.app.member.vo.ad.SkitAdSessionCreateReqVO;
import cn.iocoder.yudao.module.skit.controller.app.member.vo.ad.SkitAdSessionCreateRespVO;
import cn.iocoder.yudao.module.skit.controller.app.member.vo.ad.SkitAdSessionStatusRespVO;
import cn.iocoder.yudao.module.skit.controller.app.member.vo.ad.SkitGrantedEpisodesRespVO;
import cn.iocoder.yudao.module.skit.controller.app.member.vo.ad.SkitRewardProvenanceRespVO;
import cn.iocoder.yudao.module.skit.framework.security.SkitClientIpRateLimiterKeyResolver;
import cn.iocoder.yudao.module.skit.framework.security.SkitClientRuntimeResolver;
import cn.iocoder.yudao.module.skit.service.ad.SkitAdSessionService;
import cn.iocoder.yudao.module.skit.service.ad.SkitTenantAdCapabilityService;
import cn.iocoder.yudao.module.skit.service.member.SkitContentEntitlementService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;

import javax.annotation.security.PermitAll;
import javax.servlet.http.HttpServletResponse;
import javax.validation.constraints.Pattern;
import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SkitNativeAdSessionControllerTest {

    @InjectMocks
    private SkitNativeAdSessionController controller;

    @Mock
    private SkitAdSessionService adSessionService;
    @Mock
    private SkitContentEntitlementService entitlementService;
    @Mock
    private SkitClientRuntimeResolver clientRuntimeResolver;
    @Mock
    private HttpServletResponse response;

    private final SkitTenantAdCapabilityService.ClientRuntime runtime =
            new SkitTenantAdCapabilityService.ClientRuntime("2.4.0", 1);

    @BeforeEach
    void stubClientRuntime() {
        org.mockito.Mockito.lenient().when(clientRuntimeResolver.resolve()).thenReturn(runtime);
    }

    @Test
    void nativeControllerIsPublicTenantIgnoredAndUsesOnlyGrantScopedRoutes() throws Exception {
        RequestMapping root = SkitNativeAdSessionController.class.getAnnotation(RequestMapping.class);
        assertNotNull(root);
        assertEquals(Collections.singletonList("/skit/member/native"), Arrays.asList(root.value()));
        assertNotNull(SkitNativeAdSessionController.class.getAnnotation(PermitAll.class));
        assertNotNull(SkitNativeAdSessionController.class.getAnnotation(TenantIgnore.class));

        assertPostRoute("createAdSession", "/ad-sessions",
                String.class, SkitAdSessionCreateReqVO.class);
        assertPostRoute("recordClientEvents", "/ad-sessions/{sessionId}/client-events",
                String.class, String.class, SkitAdClientEventBatchReqVO.class);
        assertGetRoute("getAdSession", "/ad-sessions/{sessionId}", String.class, String.class);
        assertGetRoute("getEntitlements", "/entitlements", String.class);
        assertGetRoute("getRewardProvenance", "/entitlements/{episodeNo}/reward-provenance",
                String.class, Integer.class, HttpServletResponse.class);
    }

    @Test
    void everyNativeMethodIsIpLimitedAndDisablesRequestAndResponseLogging() {
        for (Method method : SkitNativeAdSessionController.class.getDeclaredMethods()) {
            if (method.isSynthetic()) {
                continue;
            }
            RateLimiter limiter = method.getAnnotation(RateLimiter.class);
            assertNotNull(limiter, method.getName() + " must be rate limited");
            assertEquals(SkitClientIpRateLimiterKeyResolver.class, limiter.keyResolver());
            assertTrue(limiter.time() > 0);
            assertTrue(limiter.count() > 0);

            ApiAccessLog accessLog = method.getAnnotation(ApiAccessLog.class);
            assertNotNull(accessLog, method.getName() + " must define safe access logging");
            assertFalse(accessLog.requestEnable());
            assertFalse(accessLog.responseEnable());
        }
    }

    @Test
    void everyNativeMethodRequiresTheExactBase64UrlGrantHeader() {
        for (Method method : SkitNativeAdSessionController.class.getDeclaredMethods()) {
            if (method.isSynthetic()) {
                continue;
            }
            Annotation[] grantAnnotations = method.getParameterAnnotations()[0];
            RequestHeader header = find(grantAnnotations, RequestHeader.class);
            assertNotNull(header, method.getName() + " must receive the player grant in a header");
            assertEquals("X-Skit-Player-Grant", header.value());
            Pattern pattern = find(grantAnnotations, Pattern.class);
            assertNotNull(pattern);
            assertEquals("[A-Za-z0-9_-]{43}", pattern.regexp());
        }
    }

    @Test
    void nativeCreateDelegatesOnlyGrantAndGrantBoundContentScope() throws Exception {
        String grant = grantToken();
        SkitAdSessionCreateReqVO request = new SkitAdSessionCreateReqVO();
        request.setDramaId(904L);
        request.setEpisodeNo(7);
        when(adSessionService.createForNativeGrant(eq(grant), any()))
                .thenReturn(createResult("CREATED", "abcdefghijklmnopqrstuv", "native-secret"));

        CommonResult<SkitAdSessionCreateRespVO> result = controller.createAdSession(grant, request);

        ArgumentCaptor<SkitAdSessionService.CreateCommand> command =
                ArgumentCaptor.forClass(SkitAdSessionService.CreateCommand.class);
        verify(adSessionService).createForNativeGrant(eq(grant), command.capture());
        assertEquals(904L, command.getValue().getDramaId());
        assertEquals(7, command.getValue().getEpisodeNo());
        assertEquals("2.4.0", command.getValue().getNativeVersion());
        assertEquals(1, command.getValue().getProtocolVersion());
        assertEquals("native-secret", result.getData().getCustomData());
        assertFalse(result.getData().toString().contains("native-secret"));
    }

    @Test
    void nativeStatusEventsAndEntitlementsRemainGrantScoped() throws Exception {
        String grant = grantToken();
        String sessionId = "abcdefghijklmnopqrstuv";
        SkitAdSessionService.SessionView view = sessionView(sessionId, "SHOWN");
        when(adSessionService.getForNativeGrant(grant, sessionId, runtime)).thenReturn(view);
        when(adSessionService.recordClientEventsForNativeGrant(
                eq(grant), eq(sessionId), anyList(), eq(runtime)))
                .thenReturn(view);
        when(entitlementService.listGrantedEpisodesForPlayerGrant(grant, runtime))
                .thenReturn(Arrays.asList(1, 3, 7));

        SkitAdClientEventBatchReqVO batch = new SkitAdClientEventBatchReqVO();
        batch.setEvents(Collections.singletonList(validEvent(sessionId)));
        CommonResult<SkitAdSessionStatusRespVO> status = controller.getAdSession(grant, sessionId);
        CommonResult<SkitAdSessionStatusRespVO> afterEvents =
                controller.recordClientEvents(grant, sessionId, batch);
        CommonResult<SkitGrantedEpisodesRespVO> entitlements = controller.getEntitlements(grant);

        assertEquals("SHOWN", status.getData().getClientLifecycleStatus());
        assertEquals("SHOWN", afterEvents.getData().getClientLifecycleStatus());
        assertEquals(Arrays.asList(1, 3, 7), entitlements.getData().getGrantedEpisodeNos());
        verify(adSessionService).getForNativeGrant(grant, sessionId, runtime);
        verify(adSessionService).recordClientEventsForNativeGrant(
                eq(grant), eq(sessionId), anyList(), eq(runtime));
        verify(entitlementService).listGrantedEpisodesForPlayerGrant(grant, runtime);
    }

    @Test
    void nativeRewardProvenanceRemainsGrantAndEpisodeScoped() {
        String grant = grantToken();
        SkitContentEntitlementService.VerifiedRewardProvenance proof =
                new SkitContentEntitlementService.VerifiedRewardProvenance(
                        7, "abcdefghijklmnopqrstuv", "TAKU", "show-verified-1");
        when(entitlementService.findVerifiedRewardProvenanceForPlayerGrant(grant, 7, runtime))
                .thenReturn(proof);

        CommonResult<SkitRewardProvenanceRespVO> result =
                controller.getRewardProvenance(grant, 7, response);

        assertTrue(result.getData().isVerified());
        assertEquals(7, result.getData().getEpisodeNo());
        assertEquals("abcdefghijklmnopqrstuv", result.getData().getSessionId());
        assertEquals("TAKU", result.getData().getProvider());
        assertEquals("show-verified-1", result.getData().getProviderShowId());
        verify(entitlementService).findVerifiedRewardProvenanceForPlayerGrant(grant, 7, runtime);
        verify(response).setHeader("Cache-Control", "no-store");
        verify(response).setHeader("Pragma", "no-cache");
    }

    private static SkitAdClientEventReqVO validEvent(String sessionId) {
        SkitAdClientEventReqVO event = new SkitAdClientEventReqVO();
        event.setProtocolVersion(1);
        event.setClientEventId("client-event-native-1");
        event.setCallbackSequence(1);
        event.setSessionId(sessionId);
        event.setProvider("TAKU");
        event.setPlacementId("placement-1");
        event.setEventType("SHOWN");
        event.setNativeState("SHOWING");
        event.setSdkRequestId("sdk-request-1");
        event.setProviderShowId("show-1");
        event.setNetworkFirmId(66);
        event.setAdsourceId("adsource-1");
        event.setClientRewardObserved(false);
        event.setClosed(false);
        return event;
    }

    private static SkitAdSessionService.CreateResult createResult(
            String outcome, String sessionId, String customData) throws Exception {
        Constructor<SkitAdSessionService.CreateResult> constructor =
                SkitAdSessionService.CreateResult.class.getDeclaredConstructor(
                        String.class, Integer.class, String.class, String.class, String.class,
                        String.class, String.class, String.class, LocalDateTime.class, LocalDateTime.class);
        constructor.setAccessible(true);
        return constructor.newInstance(outcome, 1, sessionId, "TAKU", "placement-1",
                "opaque-user", customData, "drama_unlock",
                LocalDateTime.of(2026, 7, 14, 13, 5),
                LocalDateTime.of(2026, 7, 14, 13, 20));
    }

    private static SkitAdSessionService.SessionView sessionView(String sessionId, String lifecycle)
            throws Exception {
        Constructor<SkitAdSessionService.SessionView> constructor =
                SkitAdSessionService.SessionView.class.getDeclaredConstructor(
                        String.class, String.class, String.class, String.class, String.class,
                        String.class, LocalDateTime.class, LocalDateTime.class);
        constructor.setAccessible(true);
        return constructor.newInstance(sessionId, lifecycle, "PENDING", "NONE", "NONE",
                "show-1", LocalDateTime.of(2026, 7, 14, 13, 5),
                LocalDateTime.of(2026, 7, 14, 13, 20));
    }

    private static String grantToken() {
        return "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghi_jklmnop".substring(0, 43);
    }

    private static void assertPostRoute(String methodName, String path, Class<?>... parameters)
            throws Exception {
        PostMapping mapping = SkitNativeAdSessionController.class
                .getMethod(methodName, parameters).getAnnotation(PostMapping.class);
        assertNotNull(mapping);
        assertEquals(Collections.singletonList(path), Arrays.asList(mapping.value()));
    }

    private static void assertGetRoute(String methodName, String path, Class<?>... parameters)
            throws Exception {
        GetMapping mapping = SkitNativeAdSessionController.class
                .getMethod(methodName, parameters).getAnnotation(GetMapping.class);
        assertNotNull(mapping);
        assertEquals(Collections.singletonList(path), Arrays.asList(mapping.value()));
    }

    private static <A extends Annotation> A find(Annotation[] annotations, Class<A> type) {
        for (Annotation annotation : annotations) {
            if (type.isInstance(annotation)) {
                return type.cast(annotation);
            }
        }
        return null;
    }

}
