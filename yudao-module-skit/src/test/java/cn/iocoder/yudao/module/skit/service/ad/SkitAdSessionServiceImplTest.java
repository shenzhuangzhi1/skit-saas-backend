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
import cn.iocoder.yudao.module.skit.dal.dataobject.revenue.SkitAdRevenueEventDO;
import cn.iocoder.yudao.module.skit.dal.mysql.ad.SkitAdAccountMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.ad.SkitAdClientEventMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.ad.SkitAdSessionMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.agent.SkitAgentMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.member.SkitMemberMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.revenue.SkitAdRevenueEventMapper;
import cn.iocoder.yudao.module.skit.service.ad.callback.SkitAdRewardReceiptResolutionService;
import cn.iocoder.yudao.module.skit.service.commission.SkitPolicySnapshotService;
import cn.iocoder.yudao.module.skit.service.content.SkitPangleDramaCatalogSyncService;
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
import static cn.iocoder.yudao.module.skit.enums.ErrorCodeConstants.AD_CONTENT_CATALOG_MISSING;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
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
    @Mock private SkitAdRevenueEventMapper revenueEventMapper;
    @Mock private SkitAdAccountMapper accountMapper;
    @Mock private SkitAgentMapper agentMapper;
    @Mock private SkitMemberMapper memberMapper;
    @Mock private SkitAdCredentialVersionService credentialService;
    @Mock private SkitPolicySnapshotService snapshotService;
    @Mock private SkitContentEntitlementService entitlementService;
    @Mock private SkitContentScopeService contentScopeService;
    @Mock private SkitPangleDramaCatalogSyncService catalogSyncService;
    @Mock private TenantService tenantService;
    @Mock private SkitTenantAdCapabilityService capabilityService;
    @Mock private SkitAdRewardReceiptResolutionService rewardReceiptResolutionService;

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
        org.mockito.Mockito.lenient().when(sessionMapper.selectDatabaseNow()).thenReturn(now());
        tokenService = new SkitHmacAdSessionTokenService(1, Collections.singletonMap(1, TOKEN_KEY));
        service = new SkitAdSessionServiceImpl(sessionMapper, clientEventMapper, revenueEventMapper, accountMapper,
                agentMapper, memberMapper, credentialService, snapshotService, entitlementService,
                contentScopeService, catalogSyncService, tenantService,
                tokenService, new ObjectMapper(), Clock.fixed(NOW, ZoneOffset.UTC),
                new FixedSecureRandom(sequence(16)), capabilityService,
                rewardReceiptResolutionService);
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

        verify(catalogSyncService, never()).syncMissingDrama(anyLong(), anyLong());
        verify(sessionMapper, never()).insert(any());
        verify(snapshotService, never()).createSnapshot(any());
        verify(credentialService, never()).getActiveCallbackKeyVersion(anyLong(), anyLong());
    }

    @Test
    void missingTenantCatalogSynchronizesThenRetriesTheWholeSessionTransaction() {
        stubNewSessionDependencies(TENANT_ID, MEMBER_ID);
        when(contentScopeService.resolveUnlockScopeForUpdate(MEMBER_ID, DRAMA_ID, 3))
                .thenThrow(cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception(
                        AD_CONTENT_CATALOG_MISSING))
                .thenReturn(contentScope(DRAMA_ID, 3, false));
        when(sessionMapper.insert(any(SkitAdSessionDO.class))).thenAnswer(invocation -> {
            invocation.<SkitAdSessionDO>getArgument(0).setId(91L);
            return 1;
        });

        SkitAdSessionService.CreateResult result = service.createForMember(
                MEMBER_ID, command(DRAMA_ID, 3));

        assertEquals("CREATED", result.getOutcome());
        verify(catalogSyncService).syncMissingDrama(TENANT_ID, DRAMA_ID);
        verify(contentScopeService, org.mockito.Mockito.times(3))
                .resolveUnlockScopeForUpdate(MEMBER_ID, DRAMA_ID, 3);
        verify(sessionMapper).insert(any(SkitAdSessionDO.class));
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

        org.mockito.InOrder requestOrder = org.mockito.Mockito.inOrder(
                sessionMapper, capabilityService);
        requestOrder.verify(sessionMapper).selectDatabaseNow();
        requestOrder.verify(capabilityService).checkClientAccess(eq(MEMBER_ID), any(),
                eq(SkitTenantAdCapabilityService.AccessOperation.AD_SESSION));
        org.mockito.Mockito.verifyNoMoreInteractions(sessionMapper);
        org.mockito.Mockito.verifyNoInteractions(accountMapper, credentialService, snapshotService);
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
                sessionMapper, clientEventMapper, revenueEventMapper, accountMapper, agentMapper, memberMapper,
                credentialService, snapshotService, entitlementService, contentScopeService,
                catalogSyncService, tenantService,
                tokenService, new ObjectMapper(), Clock.fixed(NOW, ZoneOffset.UTC),
                new FixedSecureRandom(sequence(16)), null, rewardReceiptResolutionService));
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
                    sessionMapper, clientEventMapper, revenueEventMapper, accountMapper, agentMapper, memberMapper,
                    credentialService, snapshotService, entitlementService, contentScopeService,
                    catalogSyncService, tenantService,
                    tokenService, new ObjectMapper(), Clock.fixed(NOW, shanghai),
                    new FixedSecureRandom(sequence(16)), capabilityService,
                    rewardReceiptResolutionService);

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
    void loadStartedSessionReusesHashOnlyTokenAndDoesNotCreateAnotherSnapshot() {
        when(agentMapper.selectByTenantId(TENANT_ID)).thenReturn(enabledAgent(TENANT_ID));
        when(memberMapper.selectByTenantAndIdForUpdate(TENANT_ID, MEMBER_ID))
                .thenReturn(enabledMember(TENANT_ID, MEMBER_ID));
        when(accountMapper.selectEnabledTakuForShare(TENANT_ID))
                .thenReturn(Collections.singletonList(enabledTaku(TENANT_ID)));
        SkitAdSessionDO existing = activeSession(TENANT_ID, MEMBER_ID)
                .setSessionId(SESSION_ID).setSessionTokenKeyVersion(1)
                .setSessionTokenHash(tokenService.restore(SESSION_ID, 1).getTokenHash())
                .setClientLifecycleStatus("LOADING").setLastCallbackSequence(0)
                .setLastClientEvent("LOAD_STARTED").setSdkRequestId("request-1");
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
    void differentNativePlayerGrantReclaimsPureLoadStartedSessionAndCreatesReplacement() {
        stubCreateNativeGrant("grant-b", TENANT_ID, MEMBER_ID, DRAMA_ID, 102L);
        stubNewSessionDependencies(TENANT_ID, MEMBER_ID);
        SkitAdSessionDO grantASession = nativeLoadStartedSession(101L);
        when(sessionMapper.selectActiveScopesOverlappingRangeForUpdate(
                TENANT_ID, MEMBER_ID, DRAMA_ID, 3, 3))
                .thenReturn(Collections.singletonList(grantASession));
        when(sessionMapper.rejectSupersededNativeGrantLoadingAndReleaseScopeCas(
                TENANT_ID, 92L, MEMBER_ID, 4, 101L, 102L, now())).thenReturn(1);
        when(sessionMapper.insert(any(SkitAdSessionDO.class))).thenAnswer(invocation -> {
            invocation.<SkitAdSessionDO>getArgument(0).setId(93L);
            return 1;
        });

        SkitAdSessionService.CreateResult result = service.createForNativeGrant(
                "grant-b", command(DRAMA_ID, 3));

        assertEquals("CREATED", result.getOutcome());
        ArgumentCaptor<SkitAdSessionDO> replacement = ArgumentCaptor.forClass(SkitAdSessionDO.class);
        verify(sessionMapper).insert(replacement.capture());
        assertEquals(102L, replacement.getValue().getNativePlayerGrantId());
        verify(sessionMapper).rejectSupersededNativeGrantLoadingAndReleaseScopeCas(
                TENANT_ID, 92L, MEMBER_ID, 4, 101L, 102L, now());
    }

    @Test
    void sameNativePlayerGrantReusesPureLoadStartedSession() {
        stubCreateNativeGrant("grant-a", TENANT_ID, MEMBER_ID, DRAMA_ID, 101L);
        stubSessionEnvelopeDependencies();
        SkitAdSessionDO grantASession = nativeLoadStartedSession(101L);
        when(sessionMapper.selectActiveScopeForUpdate(
                eq(TENANT_ID), eq(MEMBER_ID), any(byte[].class))).thenReturn(grantASession);

        SkitAdSessionService.CreateResult result = service.createForNativeGrant(
                "grant-a", command(DRAMA_ID, 3));

        assertEquals("REUSED", result.getOutcome());
        assertEquals(SESSION_ID, result.getSessionId());
        assertNotNull(result.getCustomData());
        verify(sessionMapper, never()).rejectSupersededNativeGrantLoadingAndReleaseScopeCas(
                anyLong(), anyLong(), anyLong(), anyInt(), anyLong(), anyLong(), any());
        verify(sessionMapper, never()).insert(any());
    }

    @Test
    void differentNativePlayerGrantCannotReclaimSessionWithShowFact() {
        stubCreateNativeGrant("grant-b", TENANT_ID, MEMBER_ID, DRAMA_ID, 102L);
        stubSessionEnvelopeDependencies();
        SkitAdSessionDO grantASession = nativeLoadStartedSession(101L)
                .setProviderShowId("show-1");
        when(sessionMapper.selectActiveScopesOverlappingRangeForUpdate(
                TENANT_ID, MEMBER_ID, DRAMA_ID, 3, 3))
                .thenReturn(Collections.singletonList(grantASession));

        SkitAdSessionService.CreateResult result = service.createForNativeGrant(
                "grant-b", command(DRAMA_ID, 3));

        assertEquals("VERIFYING", result.getOutcome());
        assertEquals(SESSION_ID, result.getSessionId());
        assertNull(result.getCustomData());
        verify(sessionMapper, never()).rejectSupersededNativeGrantLoadingAndReleaseScopeCas(
                anyLong(), anyLong(), anyLong(), anyInt(), anyLong(), anyLong(), any());
        verify(sessionMapper, never()).insert(any());
    }

    @Test
    void differentNativePlayerGrantFailsClosedWhenTakeoverCasLoses() {
        stubCreateNativeGrant("grant-b", TENANT_ID, MEMBER_ID, DRAMA_ID, 102L);
        stubSessionEnvelopeDependencies();
        SkitAdSessionDO grantASession = nativeLoadStartedSession(101L);
        when(sessionMapper.selectActiveScopesOverlappingRangeForUpdate(
                TENANT_ID, MEMBER_ID, DRAMA_ID, 3, 3))
                .thenReturn(Collections.singletonList(grantASession));

        ServiceException conflict = assertThrows(ServiceException.class,
                () -> service.createForNativeGrant("grant-b", command(DRAMA_ID, 3)));

        assertEquals(AD_SESSION_STATE_CONFLICT.getCode(), conflict.getCode());
        verify(sessionMapper).rejectSupersededNativeGrantLoadingAndReleaseScopeCas(
                TENANT_ID, 92L, MEMBER_ID, 4, 101L, 102L, now());
        verify(sessionMapper, never()).insert(any());
    }

    @Test
    void legacyUnrewardedClosedSessionIsRejectedReleasedAndReplacedAfterUpgrade() {
        stubNewSessionDependencies(TENANT_ID, MEMBER_ID);
        SkitAdSessionDO existing = activeSession(TENANT_ID, MEMBER_ID)
                .setClientLifecycleStatus("CLOSED").setProviderShowId("show-1")
                .setSdkRequestId("request-1").setLastCallbackSequence(2)
                .setLastClientEvent("CLOSED").setProtocolVersion(7).setVersion(4);
        byte[] activeScopeHash = existing.getActiveScopeHash();
        SkitAdClientEventDO closeEvidence = closedEvidence(existing, false);
        when(sessionMapper.selectActiveScopeForUpdate(eq(TENANT_ID), eq(MEMBER_ID), any(byte[].class)))
                .thenReturn(existing);
        when(clientEventMapper.selectBySequence(TENANT_ID, 92L, 2)).thenReturn(closeEvidence);
        when(sessionMapper.rejectLegacyUnrewardedClosedAndReleaseScopeCas(
                TENANT_ID, 92L, MEMBER_ID, 4, 2, DRAMA_ID, 3, 3,
                activeScopeHash, now())).thenReturn(1);
        when(sessionMapper.insert(any(SkitAdSessionDO.class))).thenAnswer(invocation -> {
            invocation.<SkitAdSessionDO>getArgument(0).setId(93L);
            return 1;
        });

        SkitAdSessionService.CreateResult result = service.createForMember(
                MEMBER_ID, command(DRAMA_ID, 3));

        assertEquals("CREATED", result.getOutcome());
        assertEquals("REJECTED", existing.getRewardVerificationStatus());
        assertEquals("NONE", existing.getEntitlementStatus());
        assertNull(existing.getActiveScopeHash());
        assertEquals("REWARD_REJECTED", existing.getActiveScopeReleaseReason());
        assertEquals("CLIENT_CLOSED_UNREWARDED", existing.getFailureReason());
        verify(sessionMapper).insert(any(SkitAdSessionDO.class));
    }

    @Test
    void legacyRewardObservedClosedSessionKeepsSignedVerificationPending() {
        stubSessionEnvelopeDependencies();
        SkitAdSessionDO existing = activeSession(TENANT_ID, MEMBER_ID)
                .setClientLifecycleStatus("CLOSED").setProviderShowId("show-1")
                .setSdkRequestId("request-1").setLastCallbackSequence(2)
                .setLastClientEvent("CLOSED").setProtocolVersion(7).setVersion(4);
        when(sessionMapper.selectActiveScopeForUpdate(eq(TENANT_ID), eq(MEMBER_ID), any(byte[].class)))
                .thenReturn(existing);
        when(clientEventMapper.selectBySequence(TENANT_ID, 92L, 2))
                .thenReturn(closedEvidence(existing, true));

        SkitAdSessionService.CreateResult result = service.createForMember(
                MEMBER_ID, command(DRAMA_ID, 3));

        assertEquals("VERIFYING", result.getOutcome());
        assertEquals(7, result.getProtocolVersion());
        assertEquals(SESSION_ID, result.getSessionId());
        assertNull(result.getCustomData());
        assertNotNull(existing.getActiveScopeHash());
        verify(sessionMapper, never()).rejectLegacyUnrewardedClosedAndReleaseScopeCas(
                anyLong(), anyLong(), anyLong(), anyInt(), anyInt(), anyLong(),
                anyInt(), anyInt(), any(byte[].class), any());
        verify(sessionMapper, never()).insert(any());
    }

    @Test
    void legacyUnrewardedClosedSessionWithRewardReceiptKeepsSignedVerificationPending() {
        stubSessionEnvelopeDependencies();
        SkitAdSessionDO existing = activeSession(TENANT_ID, MEMBER_ID)
                .setClientLifecycleStatus("CLOSED").setProviderShowId("show-1")
                .setSdkRequestId("request-1").setLastCallbackSequence(2)
                .setLastClientEvent("CLOSED").setVersion(4)
                .setRewardCallbackInboxId(901L).setRewardCallbackReceivedAt(now().minusSeconds(1));
        when(sessionMapper.selectActiveScopeForUpdate(eq(TENANT_ID), eq(MEMBER_ID), any(byte[].class)))
                .thenReturn(existing);

        SkitAdSessionService.CreateResult result = service.createForMember(
                MEMBER_ID, command(DRAMA_ID, 3));

        assertEquals("VERIFYING", result.getOutcome());
        assertEquals("PENDING", existing.getRewardVerificationStatus());
        assertNotNull(existing.getActiveScopeHash());
        verify(clientEventMapper, never()).selectBySequence(anyLong(), anyLong(), anyInt());
        verify(sessionMapper, never()).rejectLegacyUnrewardedClosedAndReleaseScopeCas(
                anyLong(), anyLong(), anyLong(), anyInt(), anyInt(), anyLong(),
                anyInt(), anyInt(), any(byte[].class), any());
    }

    @Test
    void failedPendingSessionReturnsVerifyingInsteadOfReused() {
        stubSessionEnvelopeDependencies();
        SkitAdSessionDO existing = activeSession(TENANT_ID, MEMBER_ID)
                .setClientLifecycleStatus("FAILED").setProviderShowId("show-1").setVersion(4);
        when(sessionMapper.selectActiveScopeForUpdate(eq(TENANT_ID), eq(MEMBER_ID), any(byte[].class)))
                .thenReturn(existing);

        SkitAdSessionService.CreateResult result = service.createForMember(
                MEMBER_ID, command(DRAMA_ID, 3));

        assertEquals("VERIFYING", result.getOutcome());
        assertEquals(SESSION_ID, result.getSessionId());
        assertNull(result.getCustomData());
        assertNotNull(existing.getActiveScopeHash());
        verify(sessionMapper, never()).insert(any());
    }

    @Test
    void preShowFailedSessionWithoutDisplayFactsIsRejectedAndReplaced() {
        stubNewSessionDependencies(TENANT_ID, MEMBER_ID);
        SkitAdSessionDO existing = activeSession(TENANT_ID, MEMBER_ID)
                .setClientLifecycleStatus("FAILED").setLastCallbackSequence(1)
                .setLastClientEvent("FAILED").setSdkRequestId("request-1").setVersion(4);
        when(sessionMapper.selectActiveScopeForUpdate(eq(TENANT_ID), eq(MEMBER_ID), any(byte[].class)))
                .thenReturn(existing);
        when(sessionMapper.rejectPreShowFailedAndReleaseScopeCas(
                TENANT_ID, 92L, MEMBER_ID, 4, now())).thenReturn(1);
        when(sessionMapper.insert(any(SkitAdSessionDO.class))).thenAnswer(invocation -> {
            invocation.<SkitAdSessionDO>getArgument(0).setId(93L);
            return 1;
        });

        SkitAdSessionService.CreateResult result = service.createForMember(
                MEMBER_ID, command(DRAMA_ID, 3));

        assertEquals("CREATED", result.getOutcome());
        verify(sessionMapper).insert(any(SkitAdSessionDO.class));
    }

    @Test
    void freshPureCreatedSessionIsReusedWithoutCreatingAnotherSnapshot() {
        stubSessionEnvelopeDependencies();
        SkitAdSessionDO existing = activeSession(TENANT_ID, MEMBER_ID).setVersion(4);
        when(sessionMapper.selectActiveScopeForUpdate(eq(TENANT_ID), eq(MEMBER_ID), any(byte[].class)))
                .thenReturn(existing);

        SkitAdSessionService.CreateResult result = service.createForMember(
                MEMBER_ID, command(DRAMA_ID, 3));

        assertEquals("REUSED", result.getOutcome());
        assertEquals(SESSION_ID, result.getSessionId());
        assertNotNull(result.getCustomData());
        verify(sessionMapper, never()).insert(any());
        verify(snapshotService, never()).createSnapshot(any());
    }

    @Test
    void stalePureCreatedSessionIsRejectedAndReplaced() {
        stubNewSessionDependencies(TENANT_ID, MEMBER_ID);
        SkitAdSessionDO existing = activeSession(TENANT_ID, MEMBER_ID).setVersion(4);
        existing.setCreateTime(now().minusSeconds(6));
        when(sessionMapper.selectActiveScopeForUpdate(eq(TENANT_ID), eq(MEMBER_ID), any(byte[].class)))
                .thenReturn(existing);
        when(sessionMapper.rejectPureCreatedAndReleaseScopeCas(
                TENANT_ID, 92L, MEMBER_ID, 4, now(), now().minusSeconds(5))).thenReturn(1);
        when(sessionMapper.insert(any(SkitAdSessionDO.class))).thenAnswer(invocation -> {
            invocation.<SkitAdSessionDO>getArgument(0).setId(93L);
            return 1;
        });

        SkitAdSessionService.CreateResult result = service.createForMember(
                MEMBER_ID, command(DRAMA_ID, 3));

        assertEquals("CREATED", result.getOutcome());
        verify(sessionMapper).insert(any(SkitAdSessionDO.class));
    }

    @Test
    void queuedFollowerDoesNotReclaimSessionCreatedAfterItsDatabaseRequestStart() {
        stubSessionEnvelopeDependencies();
        LocalDateTime requestStartedAt = now().minusSeconds(20);
        SkitAdSessionDO leaderSession = activeSession(TENANT_ID, MEMBER_ID).setVersion(4);
        leaderSession.setCreateTime(now().minusSeconds(10));
        when(sessionMapper.selectDatabaseNow()).thenReturn(requestStartedAt);
        when(sessionMapper.selectActiveScopeForUpdate(eq(TENANT_ID), eq(MEMBER_ID), any(byte[].class)))
                .thenReturn(leaderSession);

        SkitAdSessionService.CreateResult result = service.createForMember(
                MEMBER_ID, command(DRAMA_ID, 3));

        assertEquals("REUSED", result.getOutcome());
        assertEquals(SESSION_ID, result.getSessionId());
        verify(sessionMapper, never()).rejectPureCreatedAndReleaseScopeCas(
                anyLong(), anyLong(), anyLong(), anyInt(), any(), any());
        verify(sessionMapper, never()).insert(any());
    }

    @Test
    void stalePureCreatedReplacementFailsClosedWhenReleaseCasLosesToAClientFact() {
        stubSessionEnvelopeDependencies();
        SkitAdSessionDO existing = activeSession(TENANT_ID, MEMBER_ID).setVersion(4);
        existing.setCreateTime(now().minusSeconds(6));
        when(sessionMapper.selectActiveScopeForUpdate(eq(TENANT_ID), eq(MEMBER_ID), any(byte[].class)))
                .thenReturn(existing);

        ServiceException conflict = assertThrows(ServiceException.class,
                () -> service.createForMember(MEMBER_ID, command(DRAMA_ID, 3)));

        assertEquals(AD_SESSION_STATE_CONFLICT.getCode(), conflict.getCode());
        verify(sessionMapper, never()).insert(any());
    }

    @Test
    void statusPollRejectsStalePureCreatedSessionSoPendingUnlockCanRetry() {
        SkitAdSessionDO session = activeSession(TENANT_ID, MEMBER_ID).setVersion(4);
        session.setCreateTime(now().minusSeconds(6));
        when(sessionMapper.selectByTenantMemberAndSessionId(TENANT_ID, MEMBER_ID, SESSION_ID))
                .thenReturn(session);
        when(sessionMapper.selectByTenantMemberAndSessionIdForUpdate(TENANT_ID, MEMBER_ID, SESSION_ID))
                .thenReturn(session);
        when(sessionMapper.rejectPureCreatedAndReleaseScopeCas(
                TENANT_ID, 92L, MEMBER_ID, 4, now(), now().minusSeconds(5))).thenReturn(1);

        SkitAdSessionService.SessionView result = service.getForMember(
                MEMBER_ID, SESSION_ID, RUNTIME);

        assertEquals("LOAD_EXPIRED", result.getClientLifecycleStatus());
        assertEquals("REJECTED", result.getRewardVerificationStatus());
        assertNull(session.getActiveScopeHash());
        assertEquals("REWARD_REJECTED", session.getActiveScopeReleaseReason());
        assertEquals("ORPHAN_CREATED_REPLACED", session.getFailureReason());
    }

    @Test
    void statusPollImmediatelyRejectsAndReleasesLegacyUnrewardedClosedSession() {
        SkitAdSessionDO session = activeSession(TENANT_ID, MEMBER_ID)
                .setClientLifecycleStatus("CLOSED").setProviderShowId("show-1")
                .setSdkRequestId("request-1").setLastCallbackSequence(2)
                .setLastClientEvent("CLOSED").setVersion(4);
        byte[] activeScopeHash = session.getActiveScopeHash();
        when(sessionMapper.selectByTenantMemberAndSessionId(TENANT_ID, MEMBER_ID, SESSION_ID))
                .thenReturn(session);
        when(sessionMapper.selectByTenantMemberAndSessionIdForUpdate(TENANT_ID, MEMBER_ID, SESSION_ID))
                .thenReturn(session);
        when(clientEventMapper.selectBySequence(TENANT_ID, 92L, 2))
                .thenReturn(closedEvidence(session, false));
        when(sessionMapper.rejectLegacyUnrewardedClosedAndReleaseScopeCas(
                TENANT_ID, 92L, MEMBER_ID, 4, 2, DRAMA_ID, 3, 3,
                activeScopeHash, now())).thenReturn(1);

        SkitAdSessionService.SessionView result = service.getForMember(
                MEMBER_ID, SESSION_ID, RUNTIME);

        assertEquals("CLOSED", result.getClientLifecycleStatus());
        assertEquals("REJECTED", result.getRewardVerificationStatus());
        assertEquals("NONE", result.getEntitlementStatus());
        assertNull(session.getActiveScopeHash());
        assertEquals("REWARD_REJECTED", session.getActiveScopeReleaseReason());
        assertEquals("CLIENT_CLOSED_UNREWARDED", session.getFailureReason());
    }

    @Test
    void lateLoadStartIsAcceptedUntilARecoveryTransitionActuallyCommits() {
        SkitAdSessionDO session = activeSession(TENANT_ID, MEMBER_ID).setVersion(4);
        session.setCreateTime(now().minusSeconds(6));
        when(sessionMapper.selectByTenantMemberAndSessionIdForUpdate(TENANT_ID, MEMBER_ID, SESSION_ID))
                .thenReturn(session);
        when(clientEventMapper.selectByClientEventId(TENANT_ID, 92L, "event-0")).thenReturn(null);
        when(clientEventMapper.selectBySequence(TENANT_ID, 92L, 0)).thenReturn(null);
        when(clientEventMapper.insertCanonical(any(SkitAdClientEventDO.class))).thenReturn(1);
        when(sessionMapper.updateClientLifecycleCas(TENANT_ID, 92L, MEMBER_ID, 4,
                "CREATED", -1, 0, "LOADING", "LOAD_STARTED", "request-1",
                null, null, null)).thenReturn(1);

        SkitAdSessionService.SessionView result = service.recordClientEvents(
                MEMBER_ID, SESSION_ID, Collections.singletonList(loadStartedEvent(0)), RUNTIME);

        assertEquals("LOADING", result.getClientLifecycleStatus());
        assertEquals("PENDING", result.getRewardVerificationStatus());
        verify(clientEventMapper).insertCanonical(any());
        verify(sessionMapper, never()).rejectPureCreatedAndReleaseScopeCas(
                anyLong(), anyLong(), anyLong(), anyInt(), any(), any());
    }

    @Test
    void expiredLoadingSessionWithOnlyLoadStartedFactIsReleasedAndReplaced() {
        stubNewSessionDependencies(TENANT_ID, MEMBER_ID);
        SkitAdSessionDO existing = activeSession(TENANT_ID, MEMBER_ID)
                .setClientLifecycleStatus("LOADING").setLastCallbackSequence(0)
                .setLastClientEvent("LOAD_STARTED").setSdkRequestId("request-1")
                .setLoadExpiresAt(now().minusSeconds(1)).setVersion(4);
        when(sessionMapper.selectActiveScopeForUpdate(eq(TENANT_ID), eq(MEMBER_ID), any(byte[].class)))
                .thenReturn(existing);
        org.mockito.Mockito.lenient().when(sessionMapper.markLoadExpiredCas(
                TENANT_ID, 92L, MEMBER_ID, 4, now())).thenReturn(1);
        org.mockito.Mockito.lenient().when(sessionMapper.rejectUnstartedLoadExpiredAndReleaseScopeCas(
                TENANT_ID, 92L, MEMBER_ID, 5, now())).thenReturn(1);
        when(sessionMapper.insert(any(SkitAdSessionDO.class))).thenAnswer(invocation -> {
            invocation.<SkitAdSessionDO>getArgument(0).setId(93L);
            return 1;
        });

        SkitAdSessionService.CreateResult result = service.createForMember(
                MEMBER_ID, command(DRAMA_ID, 3));

        assertEquals("CREATED", result.getOutcome());
        verify(sessionMapper).insert(any(SkitAdSessionDO.class));
    }

    @Test
    void loadExpiredWithoutAnyRevenueFactReleasesScopeAndCreatesReplacement() {
        stubNewSessionDependencies(TENANT_ID, MEMBER_ID);
        String expiredSessionId = "EBESExQVFhcYGRobHB0eHw";
        SkitAdSessionDO existing = activeSession(TENANT_ID, MEMBER_ID)
                .setSessionId(expiredSessionId)
                .setSessionTokenHash(tokenService.restore(expiredSessionId, 1).getTokenHash())
                .setClientLifecycleStatus("LOAD_EXPIRED")
                .setLoadExpiresAt(now().minusSeconds(1)).setVersion(4);
        when(sessionMapper.selectActiveScopeForUpdate(eq(TENANT_ID), eq(MEMBER_ID), any(byte[].class)))
                .thenReturn(existing);
        when(sessionMapper.rejectUnstartedLoadExpiredAndReleaseScopeCas(
                TENANT_ID, 92L, MEMBER_ID, 4, now())).thenReturn(1);
        when(sessionMapper.insert(any(SkitAdSessionDO.class))).thenAnswer(invocation -> {
            invocation.<SkitAdSessionDO>getArgument(0).setId(93L);
            return 1;
        });

        SkitAdSessionService.CreateResult result = service.createForMember(
                MEMBER_ID, command(DRAMA_ID, 3));

        assertEquals("CREATED", result.getOutcome());
        assertFalse(expiredSessionId.equals(result.getSessionId()));
        verify(sessionMapper).rejectUnstartedLoadExpiredAndReleaseScopeCas(
                TENANT_ID, 92L, MEMBER_ID, 4, now());
    }

    @Test
    void loadExpiredReplacementFailsClosedWhenReleaseCasLoses() {
        stubSessionEnvelopeDependencies();
        SkitAdSessionDO existing = activeSession(TENANT_ID, MEMBER_ID)
                .setClientLifecycleStatus("LOAD_EXPIRED")
                .setLoadExpiresAt(now().minusSeconds(1)).setVersion(4);
        when(sessionMapper.selectActiveScopeForUpdate(eq(TENANT_ID), eq(MEMBER_ID), any(byte[].class)))
                .thenReturn(existing);
        when(sessionMapper.rejectUnstartedLoadExpiredAndReleaseScopeCas(
                TENANT_ID, 92L, MEMBER_ID, 4, now())).thenReturn(0);

        ServiceException conflict = assertThrows(ServiceException.class,
                () -> service.createForMember(MEMBER_ID, command(DRAMA_ID, 3)));

        assertEquals(AD_SESSION_STATE_CONFLICT.getCode(), conflict.getCode());
        verify(sessionMapper, never()).insert(any());
    }

    @Test
    void loadExpiredWithShowIdentityReturnsVerifyingAndDoesNotReleaseScope() {
        stubSessionEnvelopeDependencies();
        SkitAdSessionDO existing = activeSession(TENANT_ID, MEMBER_ID)
                .setClientLifecycleStatus("LOAD_EXPIRED").setProviderShowId("show-1")
                .setLoadExpiresAt(now().minusSeconds(1)).setVersion(4);
        when(sessionMapper.selectActiveScopeForUpdate(eq(TENANT_ID), eq(MEMBER_ID), any(byte[].class)))
                .thenReturn(existing);

        SkitAdSessionService.CreateResult result = service.createForMember(
                MEMBER_ID, command(DRAMA_ID, 3));

        assertEquals("VERIFYING", result.getOutcome());
        assertEquals(SESSION_ID, result.getSessionId());
        assertNotNull(existing.getActiveScopeHash());
        verify(sessionMapper, never()).insert(any());
    }

    @Test
    void loadExpiredWithCallbackReceiptReturnsVerifyingAndDoesNotReleaseScope() {
        stubSessionEnvelopeDependencies();
        SkitAdSessionDO existing = activeSession(TENANT_ID, MEMBER_ID)
                .setClientLifecycleStatus("LOAD_EXPIRED")
                .setRewardCallbackInboxId(901L).setRewardCallbackReceivedAt(now().minusSeconds(1))
                .setLoadExpiresAt(now().minusSeconds(1)).setVersion(4);
        when(sessionMapper.selectActiveScopeForUpdate(eq(TENANT_ID), eq(MEMBER_ID), any(byte[].class)))
                .thenReturn(existing);

        SkitAdSessionService.CreateResult result = service.createForMember(
                MEMBER_ID, command(DRAMA_ID, 3));

        assertEquals("VERIFYING", result.getOutcome());
        assertEquals(SESSION_ID, result.getSessionId());
        assertNotNull(existing.getActiveScopeHash());
        verify(sessionMapper, never()).insert(any());
    }

    @Test
    void loadExpiredWithRevenueFactReturnsVerifyingAndDoesNotReleaseScope() {
        stubSessionEnvelopeDependencies();
        SkitAdSessionDO existing = activeSession(TENANT_ID, MEMBER_ID)
                .setClientLifecycleStatus("LOAD_EXPIRED")
                .setRevenueStatus("IMPRESSION_PENDING_REWARD")
                .setLoadExpiresAt(now().minusSeconds(1)).setVersion(4);
        when(sessionMapper.selectActiveScopeForUpdate(eq(TENANT_ID), eq(MEMBER_ID), any(byte[].class)))
                .thenReturn(existing);

        SkitAdSessionService.CreateResult result = service.createForMember(
                MEMBER_ID, command(DRAMA_ID, 3));

        assertEquals("VERIFYING", result.getOutcome());
        assertEquals(SESSION_ID, result.getSessionId());
        assertNotNull(existing.getActiveScopeHash());
        verify(sessionMapper, never()).insert(any());
    }

    @Test
    void legacyMultiEpisodeActiveScopeIsRejectedAndReplacedByAnExactEpisodeSession() {
        stubNewSessionDependencies(TENANT_ID, MEMBER_ID);
        SkitAdSessionDO existing = activeSession(TENANT_ID, MEMBER_ID)
                .setEpisodeFrom(3).setEpisodeTo(5).setUnlockScope("drama:61:episodes:3-5")
                .setSessionId(SESSION_ID).setSessionTokenKeyVersion(1)
                .setSessionTokenHash(tokenService.restore(SESSION_ID, 1).getTokenHash())
                .setVersion(4);
        when(sessionMapper.selectActiveScopesOverlappingRangeForUpdate(
                TENANT_ID, MEMBER_ID, DRAMA_ID, 3, 3))
                .thenReturn(Collections.singletonList(existing));
        when(sessionMapper.rejectLegacyMultiEpisodeScopeCas(
                TENANT_ID, 92L, MEMBER_ID, 4, now())).thenReturn(1);
        when(sessionMapper.insert(any(SkitAdSessionDO.class))).thenAnswer(invocation -> {
            invocation.<SkitAdSessionDO>getArgument(0).setId(93L);
            return 1;
        });

        SkitAdSessionService.CreateResult result = service.createForMember(
                MEMBER_ID, command(DRAMA_ID, 3));

        assertEquals("CREATED", result.getOutcome());
        verify(sessionMapper).insert(any(SkitAdSessionDO.class));
    }

    @Test
    void contentAuthorityCannotExpandOneEpisodeRequestIntoAMultiEpisodeScope() {
        stubSessionEnvelopeDependencies();
        SkitContentScopeService.UnlockScope requested = new SkitContentScopeService.UnlockScope(
                TENANT_ID, 701L, DRAMA_ID, 3, 5, "drama:61:episodes:3-5", false);
        when(contentScopeService.resolveUnlockScopeForUpdate(MEMBER_ID, DRAMA_ID, 3))
                .thenReturn(requested);

        ServiceException rejected = assertThrows(ServiceException.class,
                () -> service.createForMember(MEMBER_ID, command(DRAMA_ID, 3)));

        assertEquals(AD_SESSION_INVALID.getCode(), rejected.getCode());
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
                .setSessionTokenHash(tokenService.restore(SESSION_ID, 1).getTokenHash())
                .setClientLifecycleStatus("LOADING").setLastCallbackSequence(0)
                .setLastClientEvent("LOAD_STARTED").setSdkRequestId("request-1");
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
    void acceptedRewardReceiptIsNeverTimedOutWhileSignedVerificationIsPending() {
        stubSessionEnvelopeDependencies();
        SkitAdSessionDO received = activeSession(TENANT_ID, MEMBER_ID)
                .setClientLifecycleStatus("CLOSED").setLastCallbackSequence(2)
                .setLastClientEvent("CLOSED").setSdkRequestId("request-1")
                .setProviderShowId("show-1").setRewardAcceptUntil(now().minusSeconds(1))
                .setRewardCallbackInboxId(901L).setRewardCallbackReceivedAt(now().minusMinutes(2))
                .setVersion(7);
        when(sessionMapper.selectActiveScopeForUpdate(eq(TENANT_ID), eq(MEMBER_ID), any(byte[].class)))
                .thenReturn(received);

        SkitAdSessionService.CreateResult result = service.createForMember(
                MEMBER_ID, command(DRAMA_ID, 3));

        assertEquals("VERIFYING", result.getOutcome());
        assertEquals("PENDING", received.getRewardVerificationStatus());
        assertNotNull(received.getActiveScopeHash());
        verify(sessionMapper, never()).markRewardVerifyTimeoutAndReleaseScopeCas(
                anyLong(), anyLong(), anyLong(), anyInt(), any());
        verify(sessionMapper, never()).rejectLegacyUnrewardedClosedAndReleaseScopeCas(
                anyLong(), anyLong(), anyLong(), anyInt(), anyInt(), anyLong(),
                anyInt(), anyInt(), any(byte[].class), any());
    }

    @Test
    void statusPollWithAcceptedRewardReceiptAvoidsTimeoutMutationAfterDeadline() {
        SkitAdSessionDO received = activeSession(TENANT_ID, MEMBER_ID)
                .setClientLifecycleStatus("CLOSED").setLastCallbackSequence(2)
                .setLastClientEvent("CLOSED").setSdkRequestId("request-1")
                .setProviderShowId("show-1").setRewardAcceptUntil(now().minusSeconds(1))
                .setRewardCallbackInboxId(901L).setRewardCallbackReceivedAt(now().minusMinutes(2))
                .setVersion(7);
        when(sessionMapper.selectByTenantMemberAndSessionId(TENANT_ID, MEMBER_ID, SESSION_ID))
                .thenReturn(received);
        when(sessionMapper.selectByTenantMemberAndSessionIdForUpdate(
                TENANT_ID, MEMBER_ID, SESSION_ID)).thenReturn(received);

        SkitAdSessionService.SessionView result = service.getForMember(
                MEMBER_ID, SESSION_ID, RUNTIME);

        assertEquals("PENDING", result.getRewardVerificationStatus());
        assertNotNull(received.getActiveScopeHash());
        verify(sessionMapper).selectByTenantMemberAndSessionIdForUpdate(
                TENANT_ID, MEMBER_ID, SESSION_ID);
        verify(rewardReceiptResolutionService).resolveTerminalReceipt(received, now());
        verify(sessionMapper, never()).markRewardVerifyTimeoutAndReleaseScopeCas(
                anyLong(), anyLong(), anyLong(), anyInt(), any());
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
    void preShowFailureRejectsRewardAndReleasesScopeWithoutGrantOrRevenue() {
        SkitAdSessionDO session = activeSession(TENANT_ID, MEMBER_ID)
                .setClientLifecycleStatus("LOADING").setLastCallbackSequence(0).setVersion(3);
        when(sessionMapper.selectByTenantMemberAndSessionIdForUpdate(TENANT_ID, MEMBER_ID, SESSION_ID))
                .thenReturn(session);
        when(clientEventMapper.selectByClientEventId(TENANT_ID, 92L, "event-1")).thenReturn(null);
        when(clientEventMapper.selectBySequence(TENANT_ID, 92L, 1)).thenReturn(null);
        when(clientEventMapper.insertCanonical(any(SkitAdClientEventDO.class))).thenReturn(1);
        when(sessionMapper.markPreShowClientFailureAndReleaseScopeCas(
                TENANT_ID, 92L, MEMBER_ID, 3, "LOADING", 0, 1,
                "request-1", now())).thenReturn(1);
        SkitAdSessionService.ClientEventCommand failed = baseEvent(1, "FAILED", "ERROR");

        SkitAdSessionService.SessionView result = service.recordClientEvents(
                MEMBER_ID, SESSION_ID, Collections.singletonList(failed), RUNTIME);

        assertEquals("FAILED", result.getClientLifecycleStatus());
        assertEquals("REJECTED", result.getRewardVerificationStatus());
        assertEquals("NONE", result.getEntitlementStatus());
        assertEquals("NONE", result.getRevenueStatus());
        assertNull(session.getActiveScopeHash());
        assertEquals(now(), session.getActiveScopeReleasedAt());
        assertEquals("REWARD_REJECTED", session.getActiveScopeReleaseReason());
        assertEquals("CLIENT_PRE_SHOW_FAILED", session.getFailureReason());
        verify(sessionMapper, never()).updateClientLifecycleCas(anyLong(), anyLong(), anyLong(),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void acceptedRewardCallbackWinsOverLaterPreShowClientFailure() {
        SkitAdSessionDO session = activeSession(TENANT_ID, MEMBER_ID)
                .setClientLifecycleStatus("LOADING").setLastCallbackSequence(0).setVersion(3)
                .setRewardCallbackInboxId(901L).setRewardCallbackReceivedAt(now().minusSeconds(1));
        when(sessionMapper.selectByTenantMemberAndSessionIdForUpdate(TENANT_ID, MEMBER_ID, SESSION_ID))
                .thenReturn(session);
        when(clientEventMapper.selectByClientEventId(TENANT_ID, 92L, "event-1")).thenReturn(null);
        when(clientEventMapper.selectBySequence(TENANT_ID, 92L, 1)).thenReturn(null);
        when(clientEventMapper.insertCanonical(any(SkitAdClientEventDO.class))).thenReturn(1);
        when(sessionMapper.updateClientLifecycleCas(TENANT_ID, 92L, MEMBER_ID, 3, "LOADING", 0,
                1, "FAILED", "FAILED", "request-1", null, null, null)).thenReturn(1);
        SkitAdSessionService.ClientEventCommand failed = baseEvent(1, "FAILED", "ERROR");

        SkitAdSessionService.SessionView result = service.recordClientEvents(
                MEMBER_ID, SESSION_ID, Collections.singletonList(failed), RUNTIME);

        assertEquals("FAILED", result.getClientLifecycleStatus());
        assertEquals("PENDING", result.getRewardVerificationStatus());
        assertNotNull(session.getActiveScopeHash());
        assertNull(session.getActiveScopeReleasedAt());
        assertNull(session.getActiveScopeReleaseReason());
        verify(sessionMapper, never()).markPreShowClientFailureAndReleaseScopeCas(
                anyLong(), anyLong(), anyLong(), any(), any(), any(), any(),
                any(), any());
    }

    @Test
    void shownUnrewardedCloseRejectsRewardAndReleasesOnlyItsEpisodeScope() {
        SkitAdSessionDO session = shownSession();
        byte[] activeScopeHash = session.getActiveScopeHash();
        when(sessionMapper.selectByTenantMemberAndSessionIdForUpdate(TENANT_ID, MEMBER_ID, SESSION_ID))
                .thenReturn(session);
        stubFreshClientEvent(2);
        when(sessionMapper.markUnrewardedClientCloseAndReleaseScopeCas(
                TENANT_ID, 92L, MEMBER_ID, 3, 1, 2, "request-1", "show-1",
                null, null, DRAMA_ID, 3, 3, activeScopeHash, now())).thenReturn(1);

        SkitAdSessionService.SessionView result = service.recordClientEvents(
                MEMBER_ID, SESSION_ID, Collections.singletonList(unrewardedCloseEvent(2)), RUNTIME);

        assertEquals("CLOSED", result.getClientLifecycleStatus());
        assertEquals("REJECTED", result.getRewardVerificationStatus());
        assertEquals("NONE", result.getEntitlementStatus());
        assertEquals("NONE", result.getRevenueStatus());
        assertNull(session.getActiveScopeHash());
        assertEquals(now(), session.getActiveScopeReleasedAt());
        assertEquals("REWARD_REJECTED", session.getActiveScopeReleaseReason());
        assertEquals("CLIENT_CLOSED_UNREWARDED", session.getFailureReason());
        verify(sessionMapper, never()).updateClientLifecycleCas(anyLong(), anyLong(), anyLong(),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(revenueEventMapper, never()).selectByTenantSessionAndSourceForUpdate(
                anyLong(), anyLong(), any());
    }

    @Test
    void overdueShownCloseKeepsCommittedVerifyTimeoutInsteadOfReterminalizingReward() {
        SkitAdSessionDO session = shownSession().setRewardAcceptUntil(now().minusSeconds(1));
        when(sessionMapper.selectByTenantMemberAndSessionIdForUpdate(TENANT_ID, MEMBER_ID, SESSION_ID))
                .thenReturn(session);
        when(sessionMapper.markRewardVerifyTimeoutAndReleaseScopeCas(
                TENANT_ID, 92L, MEMBER_ID, 3, now())).thenReturn(1);
        stubFreshClientEvent(2);
        when(sessionMapper.updateClientLifecycleCas(TENANT_ID, 92L, MEMBER_ID, 4,
                "SHOWN", 1, 2, "CLOSED", "CLOSED", "request-1", "show-1", null, null))
                .thenReturn(1);

        SkitAdSessionService.SessionView result = service.recordClientEvents(
                MEMBER_ID, SESSION_ID, Collections.singletonList(unrewardedCloseEvent(2)), RUNTIME);

        assertEquals("CLOSED", result.getClientLifecycleStatus());
        assertEquals("VERIFY_TIMEOUT", result.getRewardVerificationStatus());
        assertEquals("NONE", result.getEntitlementStatus());
        assertNull(session.getActiveScopeHash());
        assertEquals(now(), session.getActiveScopeReleasedAt());
        assertEquals("VERIFY_TIMEOUT", session.getActiveScopeReleaseReason());
        verify(sessionMapper, never()).markUnrewardedClientCloseAndReleaseScopeCas(
                anyLong(), anyLong(), anyLong(), anyInt(), anyInt(), anyInt(),
                any(), any(), any(), any(), anyLong(), anyInt(), anyInt(), any(byte[].class), any());
    }

    @Test
    void shownUnrewardedCloseConvergesPendingImpressionToNonRewardedSuspense() {
        SkitAdSessionDO session = shownSession().setRevenueStatus("IMPRESSION_PENDING_REWARD");
        byte[] activeScopeHash = session.getActiveScopeHash();
        SkitAdRevenueEventDO impression = pendingImpression(session);
        when(sessionMapper.selectByTenantMemberAndSessionIdForUpdate(TENANT_ID, MEMBER_ID, SESSION_ID))
                .thenReturn(session);
        stubFreshClientEvent(2);
        when(revenueEventMapper.selectByTenantSessionAndSourceForUpdate(
                TENANT_ID, 92L, "TAKU_IMPRESSION")).thenReturn(impression);
        when(sessionMapper.markUnrewardedClientCloseAndReleaseScopeCas(
                TENANT_ID, 92L, MEMBER_ID, 3, 1, 2, "request-1", "show-1",
                null, null, DRAMA_ID, 3, 3, activeScopeHash, now())).thenReturn(1);
        when(revenueEventMapper.markNonRewardedSuspenseCas(
                TENANT_ID, 801L, 92L, ACCOUNT_ID, 5, now())).thenReturn(1);

        SkitAdSessionService.SessionView result = service.recordClientEvents(
                MEMBER_ID, SESSION_ID, Collections.singletonList(unrewardedCloseEvent(2)), RUNTIME);

        assertEquals("REJECTED", result.getRewardVerificationStatus());
        assertEquals("SUSPENSE", result.getRevenueStatus());
        assertEquals("NON_REWARDED", impression.getRewardQualificationStatus());
        assertEquals("SUSPENSE", impression.getReconciliationStatus());
        verify(revenueEventMapper).markNonRewardedSuspenseCas(
                TENANT_ID, 801L, 92L, ACCOUNT_ID, 5, now());
    }

    @Test
    void signedRewardReceiptWinsBeforeUnrewardedCloseAndKeepsScopeActive() {
        SkitAdSessionDO session = shownSession()
                .setRewardCallbackInboxId(901L).setRewardCallbackReceivedAt(now().minusSeconds(1));
        when(sessionMapper.selectByTenantMemberAndSessionIdForUpdate(TENANT_ID, MEMBER_ID, SESSION_ID))
                .thenReturn(session);
        stubFreshClientEvent(2);
        when(sessionMapper.updateClientLifecycleCas(TENANT_ID, 92L, MEMBER_ID, 3,
                "SHOWN", 1, 2, "CLOSED", "CLOSED", "request-1", "show-1", null, null))
                .thenReturn(1);

        SkitAdSessionService.SessionView result = service.recordClientEvents(
                MEMBER_ID, SESSION_ID, Collections.singletonList(unrewardedCloseEvent(2)), RUNTIME);

        assertEquals("CLOSED", result.getClientLifecycleStatus());
        assertEquals("PENDING", result.getRewardVerificationStatus());
        assertEquals("NONE", result.getEntitlementStatus());
        assertNotNull(session.getActiveScopeHash());
        assertNull(session.getActiveScopeReleasedAt());
        verify(sessionMapper, never()).markUnrewardedClientCloseAndReleaseScopeCas(
                anyLong(), anyLong(), anyLong(), anyInt(), anyInt(), anyInt(),
                any(), any(), any(), any(), anyLong(), anyInt(), anyInt(), any(byte[].class), any());
    }

    @Test
    void unrewardedCloseCasLossFailsClosedSoEvidenceCannotCommitAlone() {
        SkitAdSessionDO session = shownSession();
        byte[] activeScopeHash = session.getActiveScopeHash();
        when(sessionMapper.selectByTenantMemberAndSessionIdForUpdate(TENANT_ID, MEMBER_ID, SESSION_ID))
                .thenReturn(session);
        stubFreshClientEvent(2);
        when(sessionMapper.markUnrewardedClientCloseAndReleaseScopeCas(
                TENANT_ID, 92L, MEMBER_ID, 3, 1, 2, "request-1", "show-1",
                null, null, DRAMA_ID, 3, 3, activeScopeHash, now())).thenReturn(0);

        ServiceException conflict = assertThrows(ServiceException.class,
                () -> service.recordClientEvents(MEMBER_ID, SESSION_ID,
                        Collections.singletonList(unrewardedCloseEvent(2)), RUNTIME));

        assertEquals(AD_SESSION_STATE_CONFLICT.getCode(), conflict.getCode());
        assertEquals("SHOWN", session.getClientLifecycleStatus());
        assertEquals("PENDING", session.getRewardVerificationStatus());
        assertNotNull(session.getActiveScopeHash());
    }

    @Test
    void partialRewardReceiptBindingFailsClosedInsteadOfDiscardingCallbackAuthority() {
        SkitAdSessionDO session = shownSession().setRewardCallbackInboxId(901L);
        when(sessionMapper.selectByTenantMemberAndSessionIdForUpdate(TENANT_ID, MEMBER_ID, SESSION_ID))
                .thenReturn(session);

        assertThrows(IllegalStateException.class,
                () -> service.recordClientEvents(MEMBER_ID, SESSION_ID,
                        Collections.singletonList(unrewardedCloseEvent(2)), RUNTIME));

        verify(sessionMapper, never()).markUnrewardedClientCloseAndReleaseScopeCas(
                anyLong(), anyLong(), anyLong(), anyInt(), anyInt(), anyInt(),
                any(), any(), any(), any(), anyLong(), anyInt(), anyInt(), any(byte[].class), any());
        verify(sessionMapper, never()).updateClientLifecycleCas(anyLong(), anyLong(), anyLong(),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void failedEventWithShowIdentityIsInvalidAndCannotReleaseScope() {
        SkitAdSessionDO session = activeSession(TENANT_ID, MEMBER_ID)
                .setClientLifecycleStatus("LOADING").setLastCallbackSequence(0).setVersion(3);
        when(sessionMapper.selectByTenantMemberAndSessionIdForUpdate(TENANT_ID, MEMBER_ID, SESSION_ID))
                .thenReturn(session);
        SkitAdSessionService.ClientEventCommand failed = baseEvent(1, "FAILED", "ERROR");
        failed.setProviderShowId("show-1");

        ServiceException rejected = assertThrows(ServiceException.class,
                () -> service.recordClientEvents(
                        MEMBER_ID, SESSION_ID, Collections.singletonList(failed), RUNTIME));

        assertEquals(AD_SESSION_INVALID.getCode(), rejected.getCode());
        assertEquals("PENDING", session.getRewardVerificationStatus());
        assertNotNull(session.getActiveScopeHash());
        verify(clientEventMapper, never()).insertCanonical(any());
        verify(sessionMapper, never()).markPreShowClientFailureAndReleaseScopeCas(
                anyLong(), anyLong(), anyLong(), any(), any(), any(), any(), any(), any());
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
    void overduePureCreatedEventUsesTheSameOrphanTerminalAsCreateAndStatusPoll() {
        SkitAdSessionDO session = activeSession(TENANT_ID, MEMBER_ID)
                .setLoadExpiresAt(now().minusSeconds(1)).setVersion(4);
        session.setCreateTime(now().minusSeconds(6));
        when(sessionMapper.selectByTenantMemberAndSessionIdForUpdate(
                TENANT_ID, MEMBER_ID, SESSION_ID)).thenReturn(session);
        when(sessionMapper.rejectPureCreatedAndReleaseScopeCas(
                TENANT_ID, 92L, MEMBER_ID, 4, now(), now().minusSeconds(5))).thenReturn(1);

        SkitAdSessionService.SessionView result = service.recordClientEvents(
                MEMBER_ID, SESSION_ID,
                Collections.singletonList(loadStartedEvent(0)), RUNTIME);

        assertEquals("LOAD_EXPIRED", result.getClientLifecycleStatus());
        assertEquals("REJECTED", result.getRewardVerificationStatus());
        assertNull(session.getActiveScopeHash());
        assertEquals("ORPHAN_CREATED_REPLACED", session.getFailureReason());
        verify(sessionMapper, never()).markLoadExpiredCas(
                anyLong(), anyLong(), anyLong(), anyInt(), any());
        verify(clientEventMapper, never()).insertCanonical(any());
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

    private SkitAdSessionService.ClientEventCommand unrewardedCloseEvent(int sequence) {
        SkitAdSessionService.ClientEventCommand event = baseEvent(sequence, "CLOSED", "CLOSED");
        event.setProviderShowId("show-1");
        event.setClosed(true);
        return event;
    }

    private void stubFreshClientEvent(int sequence) {
        when(clientEventMapper.selectByClientEventId(TENANT_ID, 92L, "event-" + sequence))
                .thenReturn(null);
        when(clientEventMapper.selectBySequence(TENANT_ID, 92L, sequence)).thenReturn(null);
        when(clientEventMapper.insertCanonical(any(SkitAdClientEventDO.class))).thenReturn(1);
    }

    private SkitAdSessionDO shownSession() {
        return activeSession(TENANT_ID, MEMBER_ID)
                .setClientLifecycleStatus("SHOWN").setLastCallbackSequence(1)
                .setLastClientEvent("SHOWN").setSdkRequestId("request-1")
                .setProviderShowId("show-1").setVersion(3);
    }

    private SkitAdClientEventDO closedEvidence(SkitAdSessionDO session, boolean rewardObserved) {
        SkitAdClientEventDO event = new SkitAdClientEventDO()
                .setId(702L).setAdSessionId(session.getId()).setProtocolVersion(1)
                .setClientEventId("event-2").setCallbackSequence(2)
                .setEventType("CLOSED").setNativeState("CLOSED")
                .setSdkRequestId("request-1").setProviderShowId("show-1")
                .setClientRewardObserved(rewardObserved).setClosed(true).setOccurredAt(now());
        event.setTenantId(session.getTenantId());
        return event;
    }

    private SkitAdRevenueEventDO pendingImpression(SkitAdSessionDO session) {
        SkitAdRevenueEventDO event = new SkitAdRevenueEventDO()
                .setId(801L).setAdAccountId(session.getAdAccountId()).setProvider("TAKU")
                .setPlacementId(session.getPlacementId()).setSourceMemberId(session.getMemberId())
                .setAdSessionId(session.getId()).setPolicySnapshotId(session.getPolicySnapshotId())
                .setSourceType("TAKU_IMPRESSION").setRewardQualificationStatus("PENDING_REWARD")
                .setSourceVerificationStatus("UNSIGNED_OBSERVATION")
                .setReconciliationStatus("FROZEN").setLegacyUnverified(false).setVersion(5);
        event.setTenantId(session.getTenantId());
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

    private void stubCreateNativeGrant(String token, long tenantId, long memberId,
                                       long dramaId, long grantId) {
        SkitContentEntitlementService.PlayerGrantReference reference =
                org.mockito.Mockito.mock(SkitContentEntitlementService.PlayerGrantReference.class);
        SkitContentEntitlementService.PlayerGrantScope scope =
                org.mockito.Mockito.mock(SkitContentEntitlementService.PlayerGrantScope.class);
        when(reference.getTenantId()).thenReturn(tenantId);
        when(reference.getMemberId()).thenReturn(memberId);
        when(entitlementService.resolvePlayerGrant(token)).thenReturn(reference);
        when(entitlementService.lockAndUsePlayerGrant(reference, dramaId)).thenReturn(scope);
        when(scope.getTenantId()).thenReturn(tenantId);
        when(scope.getMemberId()).thenReturn(memberId);
        when(scope.getDramaId()).thenReturn(dramaId);
        when(scope.getGrantId()).thenReturn(grantId);
    }

    private SkitAdSessionDO nativeLoadStartedSession(long nativePlayerGrantId) {
        return activeSession(TENANT_ID, MEMBER_ID)
                .setAccessMode("NATIVE_PLAYER_GRANT").setNativePlayerGrantId(nativePlayerGrantId)
                .setClientLifecycleStatus("LOADING").setLastCallbackSequence(0)
                .setLastClientEvent("LOAD_STARTED").setSdkRequestId("request-1").setVersion(4);
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
                .setAdAccountId(ACCOUNT_ID).setPolicySnapshotId(81L)
                .setProvider("TAKU").setPlacementId("placement-1")
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
        row.setCreateTime(now());
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
