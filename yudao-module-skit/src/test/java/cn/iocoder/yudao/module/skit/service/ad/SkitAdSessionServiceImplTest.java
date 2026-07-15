package cn.iocoder.yudao.module.skit.service.ad;

import cn.iocoder.yudao.framework.common.enums.CommonStatusEnum;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.skit.controller.app.member.vo.ad.SkitAdSessionCreateRespVO;
import cn.iocoder.yudao.module.skit.dal.dataobject.ad.SkitAdAccountDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.ad.SkitAdClientEventDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.ad.SkitAdSessionDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.agent.SkitAgentDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.member.SkitMemberDO;
import cn.iocoder.yudao.module.skit.dal.mysql.ad.SkitAdAccountMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.ad.SkitAdClientEventMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.ad.SkitAdSessionMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.agent.SkitAgentMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.member.SkitMemberMapper;
import cn.iocoder.yudao.module.skit.service.commission.SkitPolicySnapshotService;
import cn.iocoder.yudao.module.skit.service.member.SkitContentEntitlementService;
import cn.iocoder.yudao.module.skit.service.member.SkitContentScopeService;
import cn.iocoder.yudao.module.system.dal.dataobject.tenant.TenantDO;
import cn.iocoder.yudao.module.system.service.tenant.TenantService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Collections;
import java.util.TimeZone;

import static cn.iocoder.yudao.module.skit.enums.ErrorCodeConstants.AD_SESSION_INVALID;
import static cn.iocoder.yudao.module.skit.enums.ErrorCodeConstants.AD_SESSION_STATE_CONFLICT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SkitAdSessionServiceImplTest {

    private static final long TENANT_ID = 41L;
    private static final long MEMBER_ID = 51L;
    private static final long DRAMA_ID = 61L;
    private static final long ACCOUNT_ID = 71L;
    private static final String SESSION_ID = "AAECAwQFBgcICQoLDA0ODw";
    private static final Instant NOW = Instant.parse("2026-07-14T06:00:00Z");
    private static final SkitTenantAdCapabilityService.ClientRuntime RUNTIME =
            new SkitTenantAdCapabilityService.ClientRuntime("2.4.0", 1);
    private static final byte[] TOKEN_KEY = "0123456789abcdef0123456789abcdef"
            .getBytes(StandardCharsets.US_ASCII);

    @Mock private SkitAdSessionMapper sessionMapper;
    @Mock private SkitAdClientEventMapper clientEventMapper;
    @Mock private SkitAdAccountMapper accountMapper;
    @Mock private SkitAgentMapper agentMapper;
    @Mock private SkitMemberMapper memberMapper;
    @Mock private SkitAdCredentialVersionService credentialService;
    @Mock private SkitPolicySnapshotService snapshotService;
    @Mock private SkitContentEntitlementService entitlementService;
    @Mock private SkitContentScopeService contentScopeService;
    @Mock private TenantService tenantService;
    @Mock private SkitTenantAdCapabilityService capabilityService;

    private SkitAdSessionServiceImpl service;
    private SkitAdSessionTokenService tokenService;

    @BeforeEach
    void setUp() {
        TenantContextHolder.setTenantId(TENANT_ID);
        org.mockito.Mockito.lenient().when(tenantService.getTenantForShare(anyLong()))
                .thenAnswer(invocation -> enabledTenant(invocation.getArgument(0)));
        org.mockito.Mockito.lenient().when(contentScopeService.resolveUnlockScopeForUpdate(
                        anyLong(), anyLong(), any()))
                .thenAnswer(invocation -> contentScope(invocation.getArgument(1),
                        invocation.getArgument(2), false));
        tokenService = new SkitHmacAdSessionTokenService(1, Collections.singletonMap(1, TOKEN_KEY));
        service = new SkitAdSessionServiceImpl(sessionMapper, clientEventMapper, accountMapper,
                agentMapper, memberMapper, credentialService, snapshotService, entitlementService,
                contentScopeService, tenantService,
                tokenService, new ObjectMapper(), Clock.fixed(NOW, ZoneOffset.UTC),
                new FixedSecureRandom(sequence(16)), capabilityService);
    }

    @AfterEach
    void clearTenant() {
        TenantContextHolder.clear();
    }

    @Test
    void oauthCreateDerivesTenantAndMemberSelectsServerTakuAndSnapshotsEveryBoundary() {
        stubNewSessionDependencies(TENANT_ID, MEMBER_ID);
        when(sessionMapper.insert(any(SkitAdSessionDO.class))).thenAnswer(invocation -> {
            invocation.<SkitAdSessionDO>getArgument(0).setId(91L);
            return 1;
        });

        SkitAdSessionService.CreateResult result = service.createForMember(
                MEMBER_ID, command(DRAMA_ID, 3));

        assertEquals("CREATED", result.getOutcome());
        assertEquals(1, result.getProtocolVersion());
        assertEquals("TAKU", result.getProvider());
        assertEquals("placement-1", result.getPlacementId());
        assertNotNull(result.getSessionId());
        assertNotNull(result.getCustomData());
        assertFalse(result.toString().contains(result.getCustomData()));
        ArgumentCaptor<SkitAdSessionDO> row = ArgumentCaptor.forClass(SkitAdSessionDO.class);
        verify(sessionMapper).insert(row.capture());
        assertEquals(TENANT_ID, row.getValue().getTenantId());
        assertEquals(MEMBER_ID, row.getValue().getMemberId());
        assertEquals(ACCOUNT_ID, row.getValue().getAdAccountId());
        assertEquals(81L, row.getValue().getPolicySnapshotId());
        assertEquals(2, row.getValue().getCallbackKeyVersion());
        assertEquals(3, row.getValue().getRewardSecretVersion());
        assertEquals("MEMBER_OAUTH", row.getValue().getAccessMode());
        assertNull(row.getValue().getNativePlayerGrantId());
        assertEquals("drama:" + DRAMA_ID + ":episode:3", row.getValue().getUnlockScope());
        assertEquals(LocalDateTime.ofInstant(NOW.plusSeconds(300), ZoneOffset.UTC),
                row.getValue().getLoadExpiresAt());
        assertEquals(LocalDateTime.ofInstant(NOW.plusSeconds(1200), ZoneOffset.UTC),
                row.getValue().getRewardAcceptUntil());
        assertEquals("PENDING", row.getValue().getRewardVerificationStatus());
        assertEquals(-1, row.getValue().getLastCallbackSequence());
        assertTrue(tokenService.matches(result.getCustomData(), row.getValue().getSessionTokenHash()));
    }

    @Test
    void arbitraryClientDramaCannotCreateARevenueBearingSession() {
        long forgedDramaId = DRAMA_ID + 999;
        when(agentMapper.selectByTenantId(TENANT_ID)).thenReturn(enabledAgent(TENANT_ID));
        when(accountMapper.selectEnabledTakuForShare(TENANT_ID))
                .thenReturn(Collections.singletonList(enabledTaku(TENANT_ID)));
        when(memberMapper.selectByTenantAndIdForUpdate(TENANT_ID, MEMBER_ID))
                .thenReturn(enabledMember(TENANT_ID, MEMBER_ID));
        org.mockito.Mockito.doThrow(
                        cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception(
                                AD_SESSION_INVALID))
                .when(contentScopeService)
                .resolveUnlockScopeForUpdate(MEMBER_ID, forgedDramaId, 3);

        ServiceException rejected = assertThrows(ServiceException.class,
                () -> service.createForMember(MEMBER_ID, command(forgedDramaId, 3)));
        assertEquals(AD_SESSION_INVALID.getCode(), rejected.getCode());

        verify(sessionMapper, never()).insert(any());
        verify(snapshotService, never()).createSnapshot(any());
        verify(credentialService, never()).getActiveCallbackKeyVersion(anyLong(), anyLong());
    }

    @Test
    void clientRolloutGateRunsBeforeAnyAccountOrSessionMutation() {
        SkitAdSessionService.CreateCommand command = command(DRAMA_ID, 3);
        org.mockito.Mockito.doThrow(cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception(
                        cn.iocoder.yudao.module.skit.enums.ErrorCodeConstants.AD_ROLLOUT_NOT_READY,
                        "CLIENT_VERSION_REVOKED"))
                .when(capabilityService).checkClientAccess(eq(MEMBER_ID), any(),
                        eq(SkitTenantAdCapabilityService.AccessOperation.AD_SESSION));

        assertThrows(RuntimeException.class, () -> service.createForMember(MEMBER_ID, command));

        verify(capabilityService).checkClientAccess(eq(MEMBER_ID), any(),
                eq(SkitTenantAdCapabilityService.AccessOperation.AD_SESSION));
        org.mockito.Mockito.verifyNoInteractions(accountMapper, sessionMapper, credentialService, snapshotService);
    }

    @Test
    void publicRuntimeContractHasNoUngatedStatusOrEventOverloads() {
        assertThrows(NoSuchMethodException.class, () -> SkitAdSessionService.class.getMethod(
                "getForMember", Long.class, String.class));
        assertThrows(NoSuchMethodException.class, () -> SkitAdSessionService.class.getMethod(
                "getForNativeGrant", String.class, String.class));
        assertThrows(NoSuchMethodException.class, () -> SkitAdSessionService.class.getMethod(
                "recordClientEvents", Long.class, String.class, java.util.List.class));
        assertThrows(NoSuchMethodException.class, () -> SkitAdSessionService.class.getMethod(
                "recordClientEventsForNativeGrant", String.class, String.class, java.util.List.class));
    }

    @Test
    void missingCapabilityGateIsRejectedAtConstruction() {
        assertThrows(NullPointerException.class, () -> new SkitAdSessionServiceImpl(
                sessionMapper, clientEventMapper, accountMapper, agentMapper, memberMapper,
                credentialService, snapshotService, entitlementService, contentScopeService, tenantService,
                tokenService, new ObjectMapper(), Clock.fixed(NOW, ZoneOffset.UTC),
                new FixedSecureRandom(sequence(16)), null));
    }

    @Test
    void shanghaiClockKeepsSessionWindowsAsTrueEpochInstantsInJson() throws Exception {
        ZoneId shanghai = ZoneId.of("Asia/Shanghai");
        TimeZone previousTimeZone = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone(shanghai));
        try {
            stubNewSessionDependencies(TENANT_ID, MEMBER_ID);
            when(sessionMapper.insert(any(SkitAdSessionDO.class))).thenAnswer(invocation -> {
                invocation.<SkitAdSessionDO>getArgument(0).setId(91L);
                return 1;
            });
            SkitAdSessionServiceImpl shanghaiService = new SkitAdSessionServiceImpl(
                    sessionMapper, clientEventMapper, accountMapper, agentMapper, memberMapper,
                    credentialService, snapshotService, entitlementService, contentScopeService, tenantService,
                    tokenService, new ObjectMapper(), Clock.fixed(NOW, shanghai),
                    new FixedSecureRandom(sequence(16)), capabilityService);

            SkitAdSessionService.CreateResult result = shanghaiService.createForMember(
                    MEMBER_ID, command(DRAMA_ID, 3));

            ArgumentCaptor<SkitAdSessionDO> row = ArgumentCaptor.forClass(SkitAdSessionDO.class);
            verify(sessionMapper).insert(row.capture());
            assertEquals(LocalDateTime.ofInstant(NOW.plusSeconds(300), shanghai),
                    row.getValue().getLoadExpiresAt());
            assertEquals(LocalDateTime.ofInstant(NOW.plusSeconds(1200), shanghai),
                    row.getValue().getRewardAcceptUntil());
            com.fasterxml.jackson.databind.JsonNode json = new ObjectMapper().readTree(
                    JsonUtils.toJsonString(SkitAdSessionCreateRespVO.from(result)));
            assertEquals(NOW.plusSeconds(300).toEpochMilli(), json.path("loadExpiresAt").asLong());
            assertEquals(NOW.plusSeconds(1200).toEpochMilli(),
                    json.path("rewardAcceptUntil").asLong());
        } finally {
            TimeZone.setDefault(previousTimeZone);
        }
    }

    @Test
    void existingEntitlementSkipsCredentialsSnapshotAndAdSession() {
        when(agentMapper.selectByTenantId(TENANT_ID)).thenReturn(enabledAgent(TENANT_ID));
        when(memberMapper.selectByTenantAndIdForUpdate(TENANT_ID, MEMBER_ID))
                .thenReturn(enabledMember(TENANT_ID, MEMBER_ID));
        when(accountMapper.selectEnabledTakuForShare(TENANT_ID))
                .thenReturn(Collections.singletonList(enabledTaku(TENANT_ID)));
        when(contentScopeService.resolveUnlockScopeForUpdate(MEMBER_ID, DRAMA_ID, 3))
                .thenReturn(contentScope(DRAMA_ID, 3, true));

        SkitAdSessionService.CreateResult result = service.createForMember(
                MEMBER_ID, command(DRAMA_ID, 3));

        assertEquals("ALREADY_ENTITLED", result.getOutcome());
        assertNull(result.getSessionId());
        assertNull(result.getCustomData());
        verify(snapshotService, never()).createSnapshot(any());
        verify(credentialService, never()).getActiveCallbackKeyVersion(anyLong(), anyLong());
        verify(sessionMapper, never()).insert(any());
    }

    @Test
    void sameActiveScopeReusesHashOnlyTokenAndDoesNotCreateAnotherSnapshot() {
        when(agentMapper.selectByTenantId(TENANT_ID)).thenReturn(enabledAgent(TENANT_ID));
        when(memberMapper.selectByTenantAndIdForUpdate(TENANT_ID, MEMBER_ID))
                .thenReturn(enabledMember(TENANT_ID, MEMBER_ID));
        when(accountMapper.selectEnabledTakuForShare(TENANT_ID))
                .thenReturn(Collections.singletonList(enabledTaku(TENANT_ID)));
        SkitAdSessionDO existing = activeSession(TENANT_ID, MEMBER_ID)
                .setSessionId(SESSION_ID).setSessionTokenKeyVersion(1)
                .setSessionTokenHash(tokenService.restore(SESSION_ID, 1).getTokenHash());
        when(sessionMapper.selectActiveScopeForUpdate(eq(TENANT_ID), eq(MEMBER_ID), any(byte[].class)))
                .thenReturn(existing);

        SkitAdSessionService.CreateResult result = service.createForMember(
                MEMBER_ID, command(DRAMA_ID, 3));

        assertEquals("REUSED", result.getOutcome());
        assertEquals(SESSION_ID, result.getSessionId());
        assertEquals(tokenService.restore(SESSION_ID, 1).consumeCustomData(), result.getCustomData());
        verify(snapshotService, never()).createSnapshot(any());
        verify(sessionMapper, never()).insert(any());
    }

    @Test
    void overlappingRequestReusesThePersistedServerScopeThatAlreadyCoversTheEpisode() {
        stubSessionEnvelopeDependencies();
        SkitContentScopeService.UnlockScope requested = new SkitContentScopeService.UnlockScope(
                TENANT_ID, 701L, DRAMA_ID, 4, 6, "drama:61:episodes:4-6", false);
        when(contentScopeService.resolveUnlockScopeForUpdate(MEMBER_ID, DRAMA_ID, 4))
                .thenReturn(requested);
        SkitAdSessionDO existing = activeSession(TENANT_ID, MEMBER_ID)
                .setEpisodeFrom(3).setEpisodeTo(5).setUnlockScope("drama:61:episodes:3-5")
                .setSessionId(SESSION_ID).setSessionTokenKeyVersion(1)
                .setSessionTokenHash(tokenService.restore(SESSION_ID, 1).getTokenHash());
        when(sessionMapper.selectActiveScopesOverlappingRangeForUpdate(
                TENANT_ID, MEMBER_ID, DRAMA_ID, 4, 6))
                .thenReturn(Collections.singletonList(existing));

        SkitAdSessionService.CreateResult result = service.createForMember(
                MEMBER_ID, command(DRAMA_ID, 4));

        assertEquals("REUSED", result.getOutcome());
        assertEquals(SESSION_ID, result.getSessionId());
        verify(sessionMapper, never()).insert(any());
        verify(sessionMapper, never()).selectActiveScopeForUpdate(
                anyLong(), anyLong(), any(byte[].class));
    }

    @Test
    void overlappingButDifferentPendingScopeBlocksASecondRevenueSession() {
        stubSessionEnvelopeDependencies();
        SkitContentScopeService.UnlockScope requested = new SkitContentScopeService.UnlockScope(
                TENANT_ID, 701L, DRAMA_ID, 3, 5, "drama:61:episodes:3-5", false);
        when(contentScopeService.resolveUnlockScopeForUpdate(MEMBER_ID, DRAMA_ID, 3))
                .thenReturn(requested);
        SkitAdSessionDO existing = activeSession(TENANT_ID, MEMBER_ID)
                .setEpisodeFrom(4).setEpisodeTo(6).setUnlockScope("drama:61:episodes:4-6");
        when(sessionMapper.selectActiveScopesOverlappingRangeForUpdate(
                TENANT_ID, MEMBER_ID, DRAMA_ID, 3, 5))
                .thenReturn(Collections.singletonList(existing));

        ServiceException conflict = assertThrows(ServiceException.class,
                () -> service.createForMember(MEMBER_ID, command(DRAMA_ID, 3)));

        assertEquals(AD_SESSION_STATE_CONFLICT.getCode(), conflict.getCode());
        verify(sessionMapper, never()).insert(any());
        verify(snapshotService, never()).createSnapshot(any());
    }

    @Test
    void rewardSettlementBetweenScopeReadAndSessionLockCannotCreateAnotherRevenueSession() {
        stubSessionEnvelopeDependencies();
        when(contentScopeService.resolveUnlockScopeForUpdate(MEMBER_ID, DRAMA_ID, 3))
                .thenReturn(contentScope(DRAMA_ID, 3, false),
                        contentScope(DRAMA_ID, 3, true));
        when(sessionMapper.selectActiveScopesOverlappingRangeForUpdate(
                TENANT_ID, MEMBER_ID, DRAMA_ID, 3, 3))
                .thenReturn(Collections.emptyList());

        SkitAdSessionService.CreateResult result = service.createForMember(
                MEMBER_ID, command(DRAMA_ID, 3));

        assertEquals("ALREADY_ENTITLED", result.getOutcome());
        verify(contentScopeService, org.mockito.Mockito.times(2))
                .resolveUnlockScopeForUpdate(MEMBER_ID, DRAMA_ID, 3);
        verify(sessionMapper, never()).insert(any());
        verify(snapshotService, never()).createSnapshot(any());
    }

    @Test
    void duplicateCreateConflictRetriesAtBoundaryAndRereadsCanonicalActiveSession() {
        stubNewSessionDependencies(TENANT_ID, MEMBER_ID);
        SkitAdSessionDO canonical = activeSession(TENANT_ID, MEMBER_ID)
                .setSessionId(SESSION_ID).setSessionTokenKeyVersion(1)
                .setSessionTokenHash(tokenService.restore(SESSION_ID, 1).getTokenHash());
        when(sessionMapper.selectActiveScopeForUpdate(eq(TENANT_ID), eq(MEMBER_ID), any(byte[].class)))
                .thenReturn(null, canonical);
        when(sessionMapper.insert(any(SkitAdSessionDO.class)))
                .thenThrow(new DuplicateKeyException("active scope raced"));

        SkitAdSessionService.CreateResult result = service.createForMember(
                MEMBER_ID, command(DRAMA_ID, 3));

        assertEquals("REUSED", result.getOutcome());
        assertEquals(SESSION_ID, result.getSessionId());
        verify(sessionMapper, org.mockito.Mockito.times(2))
                .selectActiveScopeForUpdate(eq(TENANT_ID), eq(MEMBER_ID), any(byte[].class));
        verify(snapshotService, org.mockito.Mockito.times(1)).createSnapshot(MEMBER_ID);
    }

    @Test
    void disabledMemberCannotReuseAnExistingActiveSession() {
        when(agentMapper.selectByTenantId(TENANT_ID)).thenReturn(enabledAgent(TENANT_ID));
        when(accountMapper.selectEnabledTakuForShare(TENANT_ID))
                .thenReturn(Collections.singletonList(enabledTaku(TENANT_ID)));
        when(memberMapper.selectByTenantAndIdForUpdate(TENANT_ID, MEMBER_ID))
                .thenReturn(enabledMember(TENANT_ID, MEMBER_ID)
                        .setStatus(CommonStatusEnum.DISABLE.getStatus()));

        assertThrows(RuntimeException.class,
                () -> service.createForMember(MEMBER_ID, command(DRAMA_ID, 3)));

        verify(sessionMapper, never()).selectActiveScopeForUpdate(anyLong(), anyLong(), any(byte[].class));
    }

    @Test
    void expiredPendingScopeIsServerTerminatedAndReleasedBeforeReplacement() {
        stubNewSessionDependencies(TENANT_ID, MEMBER_ID);
        SkitAdSessionDO expired = activeSession(TENANT_ID, MEMBER_ID)
                .setRewardAcceptUntil(now().minusSeconds(1)).setVersion(7);
        when(sessionMapper.selectActiveScopeForUpdate(eq(TENANT_ID), eq(MEMBER_ID), any(byte[].class)))
                .thenReturn(expired);
        when(sessionMapper.markRewardVerifyTimeoutAndReleaseScopeCas(
                TENANT_ID, 92L, MEMBER_ID, 7, now())).thenReturn(1);
        when(sessionMapper.insert(any(SkitAdSessionDO.class))).thenAnswer(invocation -> {
            invocation.<SkitAdSessionDO>getArgument(0).setId(93L);
            return 1;
        });

        SkitAdSessionService.CreateResult result = service.createForMember(
                MEMBER_ID, command(DRAMA_ID, 3));

        assertEquals("CREATED", result.getOutcome());
        verify(sessionMapper).markRewardVerifyTimeoutAndReleaseScopeCas(
                TENANT_ID, 92L, MEMBER_ID, 7, now());
    }

    @Test
    void nativeGrantGloballyResolvesTenantThenRestoresUntrustedOriginalContext() {
        long nativeTenant = 77L;
        TenantContextHolder.setTenantId(999L);
        SkitContentEntitlementService.PlayerGrantReference reference =
                org.mockito.Mockito.mock(SkitContentEntitlementService.PlayerGrantReference.class);
        SkitContentEntitlementService.PlayerGrantScope scope =
                org.mockito.Mockito.mock(SkitContentEntitlementService.PlayerGrantScope.class);
        when(reference.getTenantId()).thenReturn(nativeTenant);
        when(reference.getMemberId()).thenReturn(MEMBER_ID);
        when(entitlementService.resolvePlayerGrant("native-token")).thenReturn(reference);
        when(entitlementService.lockAndUsePlayerGrant(reference, DRAMA_ID)).thenReturn(scope);
        when(scope.getTenantId()).thenReturn(nativeTenant);
        when(scope.getMemberId()).thenReturn(MEMBER_ID);
        when(scope.getDramaId()).thenReturn(DRAMA_ID);
        when(scope.getGrantId()).thenReturn(101L);
        stubNewSessionDependencies(nativeTenant, MEMBER_ID);
        when(sessionMapper.insert(any(SkitAdSessionDO.class))).thenAnswer(invocation -> {
            invocation.<SkitAdSessionDO>getArgument(0).setId(102L);
            return 1;
        });

        SkitAdSessionService.CreateResult result = service.createForNativeGrant(
                "native-token", command(DRAMA_ID, 3));

        assertEquals("CREATED", result.getOutcome());
        assertEquals(999L, TenantContextHolder.getTenantId());
        ArgumentCaptor<SkitAdSessionDO> row = ArgumentCaptor.forClass(SkitAdSessionDO.class);
        verify(sessionMapper).insert(row.capture());
        assertEquals(nativeTenant, row.getValue().getTenantId());
        assertEquals("NATIVE_PLAYER_GRANT", row.getValue().getAccessMode());
        assertEquals(101L, row.getValue().getNativePlayerGrantId());
    }

    @Test
    void strictClientEventAdvancesOnlyTelemetryWithCasAndNeverReleasesScope() {
        SkitAdSessionDO session = activeSession(TENANT_ID, MEMBER_ID)
                .setClientLifecycleStatus("CREATED").setLastCallbackSequence(-1).setVersion(0);
        when(sessionMapper.selectByTenantMemberAndSessionIdForUpdate(TENANT_ID, MEMBER_ID, SESSION_ID))
                .thenReturn(session);
        when(clientEventMapper.selectByClientEventId(TENANT_ID, 92L, "event-1")).thenReturn(null);
        when(clientEventMapper.selectBySequence(TENANT_ID, 92L, 0)).thenReturn(null);
        when(clientEventMapper.insertCanonical(any(SkitAdClientEventDO.class))).thenReturn(1);
        when(sessionMapper.updateClientLifecycleCas(TENANT_ID, 92L, MEMBER_ID, 0, "CREATED", -1,
                0, "LOADING", "LOAD_STARTED", "request-1", null, null, null)).thenReturn(1);
        SkitAdSessionService.ClientEventCommand event = new SkitAdSessionService.ClientEventCommand();
        event.setProtocolVersion(1);
        event.setClientEventId("event-1");
        event.setCallbackSequence(0);
        event.setSessionId(SESSION_ID);
        event.setProvider("TAKU");
        event.setPlacementId("placement-1");
        event.setEventType("LOAD_STARTED");
        event.setNativeState("LOADING");
        event.setSdkRequestId("request-1");
        event.setClientRewardObserved(false);
        event.setClosed(false);

        SkitAdSessionService.SessionView result = service.recordClientEvents(
                MEMBER_ID, SESSION_ID, Collections.singletonList(event), RUNTIME);

        assertEquals("LOADING", result.getClientLifecycleStatus());
        assertEquals("PENDING", result.getRewardVerificationStatus());
        assertEquals("NONE", result.getEntitlementStatus());
        assertNotNull(session.getActiveScopeHash());
    }

    @Test
    void serverMarksCreatedSessionLoadExpiredAndDoesNotAcceptLateLoadStart() {
        SkitAdSessionDO session = activeSession(TENANT_ID, MEMBER_ID)
                .setLoadExpiresAt(now().minusSeconds(1)).setVersion(4);
        when(sessionMapper.selectByTenantMemberAndSessionIdForUpdate(TENANT_ID, MEMBER_ID, SESSION_ID))
                .thenReturn(session);
        when(sessionMapper.markLoadExpiredCas(TENANT_ID, 92L, MEMBER_ID, 4, now())).thenReturn(1);
        SkitAdSessionService.ClientEventCommand event = loadStartedEvent(0);

        SkitAdSessionService.SessionView result = service.recordClientEvents(
                MEMBER_ID, SESSION_ID, Collections.singletonList(event), RUNTIME);

        assertEquals("LOAD_EXPIRED", result.getClientLifecycleStatus());
        verify(clientEventMapper, never()).insertCanonical(any());
        assertNotNull(session.getActiveScopeHash(), "load expiry must not release the reward-window scope");
    }

    @Test
    void statusPollingUsesUnlockedReadAndLocksOnlyWhenServerExpiryIsDue() {
        SkitAdSessionDO session = activeSession(TENANT_ID, MEMBER_ID)
                .setLoadExpiresAt(now().minusSeconds(1)).setVersion(4);
        when(sessionMapper.selectByTenantMemberAndSessionId(TENANT_ID, MEMBER_ID, SESSION_ID))
                .thenReturn(session);
        when(sessionMapper.selectByTenantMemberAndSessionIdForUpdate(TENANT_ID, MEMBER_ID, SESSION_ID))
                .thenReturn(session);
        when(sessionMapper.markLoadExpiredCas(TENANT_ID, 92L, MEMBER_ID, 4, now())).thenReturn(1);

        SkitAdSessionService.SessionView result = service.getForMember(MEMBER_ID, SESSION_ID, RUNTIME);

        assertEquals("LOAD_EXPIRED", result.getClientLifecycleStatus());
        verify(sessionMapper).selectByTenantMemberAndSessionId(TENANT_ID, MEMBER_ID, SESSION_ID);
        verify(sessionMapper).selectByTenantMemberAndSessionIdForUpdate(TENANT_ID, MEMBER_ID, SESSION_ID);
    }

    @Test
    void validNativeGrantCanResumeOAuthSessionForSameTenantMemberAndDrama() {
        long nativeTenant = 77L;
        stubNativeGrant("native-token", nativeTenant, MEMBER_ID, DRAMA_ID, 101L);
        SkitAdSessionDO oauthSession = activeSession(nativeTenant, MEMBER_ID)
                .setAccessMode("MEMBER_OAUTH").setNativePlayerGrantId(null);
        when(sessionMapper.selectByTenantMemberAndSessionId(nativeTenant, MEMBER_ID, SESSION_ID))
                .thenReturn(oauthSession);

        SkitAdSessionService.SessionView result = service.getForNativeGrant(
                "native-token", SESSION_ID, RUNTIME);

        assertEquals(SESSION_ID, result.getSessionId());
    }

    @Test
    void validGrantBCanResumeSessionOriginallyCreatedByGrantA() {
        long nativeTenant = 77L;
        stubNativeGrant("grant-b", nativeTenant, MEMBER_ID, DRAMA_ID, 102L);
        SkitAdSessionDO grantASession = activeSession(nativeTenant, MEMBER_ID)
                .setAccessMode("NATIVE_PLAYER_GRANT").setNativePlayerGrantId(101L);
        when(sessionMapper.selectByTenantMemberAndSessionId(nativeTenant, MEMBER_ID, SESSION_ID))
                .thenReturn(grantASession);

        SkitAdSessionService.SessionView result = service.getForNativeGrant(
                "grant-b", SESSION_ID, RUNTIME);

        assertEquals(SESSION_ID, result.getSessionId());
    }

    @Test
    void nativeGrantStillRejectsCrossTenantMemberOrDramaSessions() {
        long nativeTenant = 77L;
        stubNativeGrant("native-token", nativeTenant, MEMBER_ID, DRAMA_ID, 101L);
        SkitAdSessionDO wrongTenant = activeSession(nativeTenant + 1, MEMBER_ID);
        SkitAdSessionDO wrongMember = activeSession(nativeTenant, MEMBER_ID + 1);
        SkitAdSessionDO wrongDrama = activeSession(nativeTenant, MEMBER_ID).setDramaId(DRAMA_ID + 1);
        when(sessionMapper.selectByTenantMemberAndSessionId(nativeTenant, MEMBER_ID, SESSION_ID))
                .thenReturn(wrongTenant, wrongMember, wrongDrama);

        assertThrows(RuntimeException.class,
                () -> service.getForNativeGrant("native-token", SESSION_ID, RUNTIME));
        assertThrows(RuntimeException.class,
                () -> service.getForNativeGrant("native-token", SESSION_ID, RUNTIME));
        assertThrows(RuntimeException.class,
                () -> service.getForNativeGrant("native-token", SESSION_ID, RUNTIME));
    }

    @Test
    void shownEventRequiresProviderShowIdAndRewardedCloseKeepsCumulativeFlag() {
        SkitAdSessionDO session = activeSession(TENANT_ID, MEMBER_ID)
                .setClientLifecycleStatus("LOADING").setLastCallbackSequence(0).setVersion(2);
        when(sessionMapper.selectByTenantMemberAndSessionIdForUpdate(TENANT_ID, MEMBER_ID, SESSION_ID))
                .thenReturn(session);
        SkitAdSessionService.ClientEventCommand shown = shownEvent(1, null);

        assertThrows(RuntimeException.class,
                () -> service.recordClientEvents(
                        MEMBER_ID, SESSION_ID, Collections.singletonList(shown), RUNTIME));
        verify(clientEventMapper, never()).insertCanonical(any());

        session.setClientLifecycleStatus("CLIENT_REWARDED").setLastCallbackSequence(2)
                .setProviderShowId("show-1").setVersion(3);
        SkitAdSessionService.ClientEventCommand closed = baseEvent(3, "CLOSED", "CLOSED");
        closed.setProviderShowId("show-1");
        closed.setClientRewardObserved(true);
        closed.setClosed(true);
        when(clientEventMapper.selectByClientEventId(TENANT_ID, 92L, "event-3")).thenReturn(null);
        when(clientEventMapper.selectBySequence(TENANT_ID, 92L, 3)).thenReturn(null);
        when(clientEventMapper.insertCanonical(any(SkitAdClientEventDO.class))).thenReturn(1);
        when(sessionMapper.updateClientLifecycleCas(TENANT_ID, 92L, MEMBER_ID, 3,
                "CLIENT_REWARDED", 2, 3, "CLOSED", "CLOSED", "request-1", "show-1", null, null))
                .thenReturn(1);

        SkitAdSessionService.SessionView result = service.recordClientEvents(
                MEMBER_ID, SESSION_ID, Collections.singletonList(closed), RUNTIME);

        assertEquals("CLOSED", result.getClientLifecycleStatus());
    }

    @Test
    void missingOrAmbiguousTakuAccountFailsClosed() {
        when(agentMapper.selectByTenantId(TENANT_ID)).thenReturn(enabledAgent(TENANT_ID));
        when(accountMapper.selectEnabledTakuForShare(TENANT_ID)).thenReturn(Collections.emptyList());
        assertThrows(RuntimeException.class,
                () -> service.createForMember(MEMBER_ID, command(DRAMA_ID, 3)));

        when(accountMapper.selectEnabledTakuForShare(TENANT_ID))
                .thenReturn(Arrays.asList(enabledTaku(TENANT_ID), enabledTaku(TENANT_ID)));
        assertThrows(RuntimeException.class,
                () -> service.createForMember(MEMBER_ID, command(DRAMA_ID, 3)));
    }

    private void stubNewSessionDependencies(long tenantId, long memberId) {
        when(agentMapper.selectByTenantId(tenantId)).thenReturn(enabledAgent(tenantId));
        when(memberMapper.selectByTenantAndIdForUpdate(tenantId, memberId))
                .thenReturn(enabledMember(tenantId, memberId));
        when(accountMapper.selectEnabledTakuForShare(tenantId))
                .thenReturn(Collections.singletonList(enabledTaku(tenantId)));
        org.mockito.Mockito.lenient().when(
                sessionMapper.selectActiveScopeForUpdate(eq(tenantId), eq(memberId), any(byte[].class)))
                .thenReturn(null);
        when(credentialService.getActiveCallbackKeyVersion(tenantId, ACCOUNT_ID))
                .thenReturn(new SkitAdCredentialVersionService.CredentialMetadata(
                        tenantId, ACCOUNT_ID, 2, true, null));
        when(credentialService.getActiveRewardSecretVersion(tenantId, ACCOUNT_ID))
                .thenReturn(new SkitAdCredentialVersionService.CredentialMetadata(
                        tenantId, ACCOUNT_ID, 3, true, null));
        SkitPolicySnapshotService.PolicySnapshot snapshot =
                org.mockito.Mockito.mock(SkitPolicySnapshotService.PolicySnapshot.class);
        when(snapshot.getId()).thenReturn(81L);
        when(snapshot.getTenantId()).thenReturn(tenantId);
        when(snapshot.getSourceMemberId()).thenReturn(memberId);
        when(snapshotService.createSnapshot(memberId)).thenReturn(snapshot);
    }

    private void stubSessionEnvelopeDependencies() {
        when(agentMapper.selectByTenantId(TENANT_ID)).thenReturn(enabledAgent(TENANT_ID));
        when(memberMapper.selectByTenantAndIdForUpdate(TENANT_ID, MEMBER_ID))
                .thenReturn(enabledMember(TENANT_ID, MEMBER_ID));
        when(accountMapper.selectEnabledTakuForShare(TENANT_ID))
                .thenReturn(Collections.singletonList(enabledTaku(TENANT_ID)));
    }

    private SkitAdSessionService.ClientEventCommand loadStartedEvent(int sequence) {
        return baseEvent(sequence, "LOAD_STARTED", "LOADING");
    }

    private SkitAdSessionService.ClientEventCommand shownEvent(int sequence, String providerShowId) {
        SkitAdSessionService.ClientEventCommand event = baseEvent(sequence, "SHOWN", "SHOWING");
        event.setProviderShowId(providerShowId);
        return event;
    }

    private SkitAdSessionService.ClientEventCommand baseEvent(int sequence, String type, String nativeState) {
        SkitAdSessionService.ClientEventCommand event = new SkitAdSessionService.ClientEventCommand();
        event.setProtocolVersion(1);
        event.setClientEventId("event-" + sequence);
        event.setCallbackSequence(sequence);
        event.setSessionId(SESSION_ID);
        event.setProvider("TAKU");
        event.setPlacementId("placement-1");
        event.setEventType(type);
        event.setNativeState(nativeState);
        event.setSdkRequestId("request-1");
        event.setClientRewardObserved(false);
        event.setClosed(false);
        return event;
    }

    private SkitAdSessionService.CreateCommand command(long dramaId, int episodeNo) {
        SkitAdSessionService.CreateCommand command = new SkitAdSessionService.CreateCommand();
        command.setDramaId(dramaId);
        command.setEpisodeNo(episodeNo);
        command.setNativeVersion("2.4.0");
        command.setProtocolVersion(1);
        return command;
    }

    private SkitContentScopeService.UnlockScope contentScope(
            long dramaId, int episodeNo, boolean alreadyEntitled) {
        return new SkitContentScopeService.UnlockScope(TenantContextHolder.getRequiredTenantId(),
                701L, dramaId, episodeNo, episodeNo,
                "drama:" + dramaId + ":episode:" + episodeNo, alreadyEntitled);
    }

    private void stubNativeGrant(String token, long tenantId, long memberId,
                                 long dramaId, long grantId) {
        SkitContentEntitlementService.PlayerGrantReference reference =
                org.mockito.Mockito.mock(SkitContentEntitlementService.PlayerGrantReference.class);
        SkitContentEntitlementService.PlayerGrantScope scope =
                org.mockito.Mockito.mock(SkitContentEntitlementService.PlayerGrantScope.class);
        when(reference.getTenantId()).thenReturn(tenantId);
        when(reference.getMemberId()).thenReturn(memberId);
        when(reference.getDramaId()).thenReturn(dramaId);
        when(entitlementService.resolvePlayerGrant(token)).thenReturn(reference);
        when(entitlementService.lockAndUsePlayerGrant(reference, dramaId)).thenReturn(scope);
        when(scope.getTenantId()).thenReturn(tenantId);
        org.mockito.Mockito.lenient().when(scope.getGrantId()).thenReturn(grantId);
        when(scope.getMemberId()).thenReturn(memberId);
        when(scope.getDramaId()).thenReturn(dramaId);
    }

    private SkitAgentDO enabledAgent(long tenantId) {
        return SkitAgentDO.builder().id(1L).tenantId(tenantId)
                .status(CommonStatusEnum.ENABLE.getStatus()).build();
    }

    private TenantDO enabledTenant(long tenantId) {
        return new TenantDO().setId(tenantId).setStatus(CommonStatusEnum.ENABLE.getStatus());
    }

    private SkitMemberDO enabledMember(long tenantId, long memberId) {
        SkitMemberDO row = new SkitMemberDO();
        row.setId(memberId);
        row.setTenantId(tenantId);
        row.setStatus(CommonStatusEnum.ENABLE.getStatus());
        return row;
    }

    private SkitAdAccountDO enabledTaku(long tenantId) {
        SkitAdAccountDO row = SkitAdAccountDO.builder().id(ACCOUNT_ID).provider("TAKU")
                .accountName("tenant-taku").appId("app-1")
                .configData("{\"placementId\":\"placement-1\"}")
                .status(CommonStatusEnum.ENABLE.getStatus()).build();
        row.setTenantId(tenantId);
        return row;
    }

    private SkitAdSessionDO activeSession(long tenantId, long memberId) {
        SkitAdSessionDO row = new SkitAdSessionDO().setId(92L).setSessionId(SESSION_ID)
                .setSessionTokenKeyVersion(1).setProtocolVersion(1).setMemberId(memberId)
                .setAdAccountId(ACCOUNT_ID).setProvider("TAKU").setPlacementId("placement-1")
                .setScenarioId("drama_unlock").setBusinessType("EPISODE_UNLOCK")
                .setDramaId(DRAMA_ID).setEpisodeFrom(3).setEpisodeTo(3)
                .setUnlockScope("drama:" + DRAMA_ID + ":episode:3")
                .setSessionTokenHash(tokenService.restore(SESSION_ID, 1).getTokenHash())
                .setActiveScopeHash(new byte[32])
                .setPseudonymousUserId("member-opaque").setAccessMode("MEMBER_OAUTH")
                .setClientLifecycleStatus("CREATED").setRewardVerificationStatus("PENDING")
                .setEntitlementStatus("NONE").setRevenueStatus("NONE")
                .setLoadExpiresAt(now().plusMinutes(5)).setRewardAcceptUntil(now().plusMinutes(20))
                .setLastCallbackSequence(-1).setVersion(0);
        row.setTenantId(tenantId);
        return row;
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(NOW, ZoneOffset.UTC);
    }

    private static byte[] sequence(int size) {
        byte[] result = new byte[size];
        for (int index = 0; index < size; index++) {
            result[index] = (byte) index;
        }
        return result;
    }

    private static final class FixedSecureRandom extends SecureRandom {
        private final byte[] bytes;

        private FixedSecureRandom(byte[] bytes) {
            this.bytes = Arrays.copyOf(bytes, bytes.length);
        }

        @Override
        public void nextBytes(byte[] target) {
            System.arraycopy(bytes, 0, target, 0, target.length);
        }
    }

}
