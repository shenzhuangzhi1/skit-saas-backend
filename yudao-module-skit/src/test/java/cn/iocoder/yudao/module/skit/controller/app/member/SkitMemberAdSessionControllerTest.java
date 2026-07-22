package cn.iocoder.yudao.module.skit.controller.app.member;

import cn.iocoder.yudao.framework.apilog.core.annotation.ApiAccessLog;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.util.validation.ValidationUtils;
import cn.iocoder.yudao.framework.ratelimiter.core.annotation.RateLimiter;
import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.skit.controller.app.member.vo.ad.SkitAdClientEventBatchReqVO;
import cn.iocoder.yudao.module.skit.controller.app.member.vo.ad.SkitAdClientEventReqVO;
import cn.iocoder.yudao.module.skit.controller.app.member.vo.ad.SkitAdSessionCreateReqVO;
import cn.iocoder.yudao.module.skit.controller.app.member.vo.ad.SkitAdSessionCreateRespVO;
import cn.iocoder.yudao.module.skit.controller.app.member.vo.ad.SkitAdSessionStatusRespVO;
import cn.iocoder.yudao.module.skit.controller.app.member.vo.ad.SkitEntitlementRespVO;
import cn.iocoder.yudao.module.skit.controller.app.member.vo.ad.SkitPlayerGrantCreateReqVO;
import cn.iocoder.yudao.module.skit.controller.app.member.vo.ad.SkitPlayerGrantRespVO;
import cn.iocoder.yudao.module.skit.framework.security.SkitMemberRateLimiterKeyResolver;
import cn.iocoder.yudao.module.skit.framework.security.SkitClientRuntimeResolver;
import cn.iocoder.yudao.module.skit.service.ad.SkitAdSessionService;
import cn.iocoder.yudao.module.skit.service.ad.SkitTenantAdCapabilityService;
import cn.iocoder.yudao.module.skit.service.content.SkitPangleDramaCatalogSyncService;
import cn.iocoder.yudao.module.skit.service.member.SkitContentEntitlementService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import javax.validation.ConstraintViolationException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SkitMemberAdSessionControllerTest {

    private static final String MEMBER_GUARD =
            "@ss.hasScope('skit_member') and @skitMemberSecurityGuard.isSkitMember()";

    @InjectMocks
    private SkitMemberAdSessionController controller;

    @Mock
    private SkitAdSessionService adSessionService;
    @Mock
    private SkitContentEntitlementService entitlementService;
    @Mock
    private SkitClientRuntimeResolver clientRuntimeResolver;
    @Mock
    private SkitPangleDramaCatalogSyncService catalogSyncService;

    private final SkitTenantAdCapabilityService.ClientRuntime runtime =
            new SkitTenantAdCapabilityService.ClientRuntime("2.4.0", 1);

    @BeforeEach
    void stubClientRuntime() {
        org.mockito.Mockito.lenient().when(clientRuntimeResolver.resolve()).thenReturn(runtime);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
        TenantContextHolder.clear();
    }

    @Test
    void oauthControllerExposesOnlyMemberGuardedResourceRoutes() throws Exception {
        RequestMapping root = SkitMemberAdSessionController.class.getAnnotation(RequestMapping.class);
        assertNotNull(root);
        assertEquals(Collections.singletonList("/skit/member"), Arrays.asList(root.value()));
        PreAuthorize guard = SkitMemberAdSessionController.class.getAnnotation(PreAuthorize.class);
        assertNotNull(guard);
        assertEquals(MEMBER_GUARD, guard.value());

        assertPostRoute("issuePlayerGrant", "/player-grants", SkitPlayerGrantCreateReqVO.class);
        assertPostRoute("createAdSession", "/ad-sessions", SkitAdSessionCreateReqVO.class);
        assertPostRoute("recordClientEvents", "/ad-sessions/{sessionId}/client-events",
                String.class, SkitAdClientEventBatchReqVO.class);
        assertGetRoute("getAdSession", "/ad-sessions/{sessionId}", String.class);
        assertGetRoute("getEntitlements", "/entitlements", Long.class);
    }

    @Test
    void oauthRequestsCannotSupplyTenantOrMemberIdentity() {
        Set<String> requestFields = Arrays.stream(new Class<?>[]{
                        SkitPlayerGrantCreateReqVO.class,
                        SkitAdSessionCreateReqVO.class,
                        SkitAdClientEventBatchReqVO.class,
                        SkitAdClientEventReqVO.class})
                .flatMap(type -> Arrays.stream(type.getDeclaredFields()))
                .map(Field::getName)
                .collect(Collectors.toSet());

        assertFalse(requestFields.contains("tenantId"));
        assertFalse(requestFields.contains("memberId"));
        assertFalse(requestFields.contains("userId"));
    }

    @Test
    void everyOauthMethodIsMemberRateLimited() {
        for (Method method : SkitMemberAdSessionController.class.getDeclaredMethods()) {
            if (method.isSynthetic()) {
                continue;
            }
            RateLimiter limiter = method.getAnnotation(RateLimiter.class);
            assertNotNull(limiter, method.getName() + " must be rate limited");
            assertEquals(SkitMemberRateLimiterKeyResolver.class, limiter.keyResolver());
            assertTrue(limiter.time() > 0);
            assertTrue(limiter.count() > 0);
        }
    }

    @Test
    void issuePlayerGrantDerivesMemberFromAuthenticatedPrincipalAndConsumesTokenOnce() throws Exception {
        authenticate(81L);
        SkitPlayerGrantCreateReqVO request = new SkitPlayerGrantCreateReqVO();
        request.setDramaId(901L);
        SkitContentEntitlementService.PlayerGrantIssue issue = playerGrantIssue(
                71L, 901L, LocalDateTime.of(2026, 7, 14, 13, 5), grantToken());
        when(entitlementService.issuePlayerGrant(81L, 901L, runtime)).thenReturn(issue);

        CommonResult<SkitPlayerGrantRespVO> result = controller.issuePlayerGrant(request);

        assertTrue(result.isSuccess());
        assertEquals(71L, result.getData().getGrantId());
        assertEquals(901L, result.getData().getDramaId());
        assertEquals(grantToken(), result.getData().getGrantToken());
        assertFalse(result.getData().toString().contains(grantToken()));
        assertTrue(result.getData().toString().contains("<write-only>"));
        verify(entitlementService).issuePlayerGrant(81L, 901L, runtime);
        assertThrows(IllegalStateException.class, issue::consumeGrantToken);
    }

    @Test
    void issuePlayerGrantRefreshesStaleCatalogOnlyAfterTheFailedGrantTransactionReturns() throws Exception {
        authenticate(81L);
        TenantContextHolder.setTenantId(162L);
        SkitPlayerGrantCreateReqVO request = new SkitPlayerGrantCreateReqVO();
        request.setDramaId(1631L);
        SkitContentEntitlementService.PlayerGrantIssue issue = playerGrantIssue(
                72L, 1631L, LocalDateTime.of(2026, 7, 22, 1, 5), grantToken());
        when(entitlementService.issuePlayerGrant(81L, 1631L, runtime))
                .thenThrow(cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception(
                        cn.iocoder.yudao.module.skit.enums.ErrorCodeConstants.AD_CONTENT_CATALOG_STALE))
                .thenReturn(issue);

        CommonResult<SkitPlayerGrantRespVO> result = controller.issuePlayerGrant(request);

        assertTrue(result.isSuccess());
        assertEquals(1631L, result.getData().getDramaId());
        verify(catalogSyncService).syncDrama(162L, 1631L);
        verify(entitlementService, org.mockito.Mockito.times(2))
                .issuePlayerGrant(81L, 1631L, runtime);
    }

    @Test
    void issuePlayerGrantSyncsMissingCatalogOnlyAfterTheFailedGrantTransactionReturns() throws Exception {
        authenticate(81L);
        TenantContextHolder.setTenantId(162L);
        SkitPlayerGrantCreateReqVO request = new SkitPlayerGrantCreateReqVO();
        request.setDramaId(1632L);
        SkitContentEntitlementService.PlayerGrantIssue issue = playerGrantIssue(
                73L, 1632L, LocalDateTime.of(2026, 7, 22, 1, 5), grantToken());
        when(entitlementService.issuePlayerGrant(81L, 1632L, runtime))
                .thenThrow(cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception(
                        cn.iocoder.yudao.module.skit.enums.ErrorCodeConstants.AD_CONTENT_CATALOG_MISSING))
                .thenReturn(issue);

        CommonResult<SkitPlayerGrantRespVO> result = controller.issuePlayerGrant(request);

        assertTrue(result.isSuccess());
        assertEquals(1632L, result.getData().getDramaId());
        verify(catalogSyncService).syncDrama(162L, 1632L);
        verify(entitlementService, org.mockito.Mockito.times(2))
                .issuePlayerGrant(81L, 1632L, runtime);
    }

    @Test
    void createSessionDerivesMemberAndExplicitlyMapsOneTimeCustomData() throws Exception {
        authenticate(82L);
        SkitAdSessionCreateReqVO request = new SkitAdSessionCreateReqVO();
        request.setDramaId(902L);
        request.setEpisodeNo(12);
        SkitAdSessionService.CreateResult created = createResult(
                "CREATED", "abcdefghijklmnopqrstuv", "session-secret");
        when(adSessionService.createForMember(eq(82L), org.mockito.ArgumentMatchers.any()))
                .thenReturn(created);

        CommonResult<SkitAdSessionCreateRespVO> result = controller.createAdSession(request);

        ArgumentCaptor<SkitAdSessionService.CreateCommand> command =
                ArgumentCaptor.forClass(SkitAdSessionService.CreateCommand.class);
        verify(adSessionService).createForMember(eq(82L), command.capture());
        assertEquals(902L, command.getValue().getDramaId());
        assertEquals(12, command.getValue().getEpisodeNo());
        assertEquals("2.4.0", command.getValue().getNativeVersion());
        assertEquals(1, command.getValue().getProtocolVersion());
        assertEquals("session-secret", result.getData().getCustomData());
        assertEquals("abcdefghijklmnopqrstuv", result.getData().getSessionId());
        assertFalse(result.getData().toString().contains("session-secret"));
        assertTrue(result.getData().toString().contains("<redacted>"));
    }

    @Test
    void clientEventsDeriveMemberAndMapOnlyValidatedTelemetryFields() throws Exception {
        authenticate(83L);
        SkitAdClientEventReqVO event = validEvent("abcdefghijklmnopqrstuv", 0, "LOAD_STARTED", "LOADING");
        SkitAdClientEventBatchReqVO request = new SkitAdClientEventBatchReqVO();
        request.setEvents(Collections.singletonList(event));
        SkitAdSessionService.SessionView view = sessionView("abcdefghijklmnopqrstuv", "LOADING");
        when(adSessionService.recordClientEvents(eq(83L), eq("abcdefghijklmnopqrstuv"),
                org.mockito.ArgumentMatchers.anyList(), eq(runtime))).thenReturn(view);

        CommonResult<SkitAdSessionStatusRespVO> result = controller.recordClientEvents(
                "abcdefghijklmnopqrstuv", request);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<SkitAdSessionService.ClientEventCommand>> events =
                ArgumentCaptor.forClass(List.class);
        verify(adSessionService).recordClientEvents(
                eq(83L), eq("abcdefghijklmnopqrstuv"), events.capture(), eq(runtime));
        assertEquals(1, events.getValue().size());
        assertEquals("LOAD_STARTED", events.getValue().get(0).getEventType());
        assertEquals("sdk-request-1", events.getValue().get(0).getSdkRequestId());
        assertEquals("LOADING", result.getData().getClientLifecycleStatus());
    }

    @Test
    void statusAndEntitlementsDeriveMemberFromLogin() throws Exception {
        authenticate(84L);
        when(adSessionService.getForMember(84L, "abcdefghijklmnopqrstuv", runtime))
                .thenReturn(sessionView("abcdefghijklmnopqrstuv", "SHOWN"));
        when(entitlementService.listGrantedEpisodes(84L, 903L, runtime)).thenReturn(Arrays.asList(1, 2, 5));

        CommonResult<SkitAdSessionStatusRespVO> status =
                controller.getAdSession("abcdefghijklmnopqrstuv");
        CommonResult<SkitEntitlementRespVO> entitlements = controller.getEntitlements(903L);

        assertEquals("SHOWN", status.getData().getClientLifecycleStatus());
        assertEquals(903L, entitlements.getData().getDramaId());
        assertEquals(Arrays.asList(1, 2, 5), entitlements.getData().getGrantedEpisodeNos());
        verify(adSessionService).getForMember(84L, "abcdefghijklmnopqrstuv", runtime);
        verify(entitlementService).listGrantedEpisodes(84L, 903L, runtime);
    }

    @Test
    void requestValidationRejectsInvalidScopesEnumsAndOversizedBatches() {
        SkitAdSessionCreateReqVO create = new SkitAdSessionCreateReqVO();
        create.setDramaId(0L);
        create.setEpisodeNo(-1);
        assertThrows(ConstraintViolationException.class, () -> ValidationUtils.validate(create));

        SkitAdClientEventReqVO invalidEvent = validEvent(
                "invalid session id", 0, "CLIENT_REWARDED_ALIAS", "COMPLETE");
        SkitAdClientEventBatchReqVO invalidBatch = new SkitAdClientEventBatchReqVO();
        invalidBatch.setEvents(Collections.singletonList(invalidEvent));
        assertThrows(ConstraintViolationException.class, () -> ValidationUtils.validate(invalidBatch));

        List<SkitAdClientEventReqVO> oversized = new ArrayList<>();
        for (int index = 0; index < 21; index++) {
            oversized.add(validEvent("abcdefghijklmnopqrstuv", index, "LOAD_STARTED", "LOADING"));
        }
        SkitAdClientEventBatchReqVO oversizedBatch = new SkitAdClientEventBatchReqVO();
        oversizedBatch.setEvents(oversized);
        assertThrows(ConstraintViolationException.class, () -> ValidationUtils.validate(oversizedBatch));

        SkitAdClientEventBatchReqVO nullElementBatch = new SkitAdClientEventBatchReqVO();
        nullElementBatch.setEvents(Collections.singletonList(null));
        assertThrows(ConstraintViolationException.class,
                () -> ValidationUtils.validate(nullElementBatch));
    }

    @Test
    void endpointsContainingOneTimeSecretsExplicitlyDisableResponseLogging() throws Exception {
        assertResponseLoggingDisabled(SkitMemberAdSessionController.class.getMethod(
                "issuePlayerGrant", SkitPlayerGrantCreateReqVO.class));
        assertResponseLoggingDisabled(SkitMemberAdSessionController.class.getMethod(
                "createAdSession", SkitAdSessionCreateReqVO.class));
    }

    private static void authenticate(Long memberId) {
        LoginUser principal = new LoginUser();
        principal.setId(memberId);
        principal.setTenantId(18L);
        principal.setScopes(Collections.singletonList("skit_member"));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, Collections.emptyList()));
    }

    private static SkitAdClientEventReqVO validEvent(String sessionId, int sequence,
                                                      String eventType, String nativeState) {
        SkitAdClientEventReqVO event = new SkitAdClientEventReqVO();
        event.setProtocolVersion(1);
        event.setClientEventId("client-event-" + sequence);
        event.setCallbackSequence(sequence);
        event.setSessionId(sessionId);
        event.setProvider("TAKU");
        event.setPlacementId("placement-1");
        event.setEventType(eventType);
        event.setNativeState(nativeState);
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

    private static SkitContentEntitlementService.PlayerGrantIssue playerGrantIssue(
            Long id, Long dramaId, LocalDateTime expiresAt, String token) throws Exception {
        Constructor<SkitContentEntitlementService.PlayerGrantIssue> constructor =
                SkitContentEntitlementService.PlayerGrantIssue.class.getDeclaredConstructor(
                        Long.class, Long.class, LocalDateTime.class, String.class);
        constructor.setAccessible(true);
        return constructor.newInstance(id, dramaId, expiresAt, token);
    }

    private static String grantToken() {
        return "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghi_jklmnop".substring(0, 43);
    }

    private static void assertPostRoute(String methodName, String path, Class<?>... parameters)
            throws Exception {
        PostMapping mapping = SkitMemberAdSessionController.class
                .getMethod(methodName, parameters).getAnnotation(PostMapping.class);
        assertNotNull(mapping);
        assertEquals(Collections.singletonList(path), Arrays.asList(mapping.value()));
    }

    private static void assertGetRoute(String methodName, String path, Class<?>... parameters)
            throws Exception {
        GetMapping mapping = SkitMemberAdSessionController.class
                .getMethod(methodName, parameters).getAnnotation(GetMapping.class);
        assertNotNull(mapping);
        assertEquals(Collections.singletonList(path), Arrays.asList(mapping.value()));
    }

    private static void assertResponseLoggingDisabled(Method method) {
        ApiAccessLog accessLog = method.getAnnotation(ApiAccessLog.class);
        assertNotNull(accessLog);
        assertFalse(accessLog.responseEnable());
    }

}
