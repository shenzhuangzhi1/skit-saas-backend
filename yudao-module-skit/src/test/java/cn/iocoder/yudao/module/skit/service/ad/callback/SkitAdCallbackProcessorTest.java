package cn.iocoder.yudao.module.skit.service.ad.callback;

import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.skit.dal.dataobject.ad.SkitAdCallbackInboxDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.ad.SkitAdNetworkCapabilityDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.ad.SkitAdSessionDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.ad.SkitTenantAdCapabilityDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.member.SkitContentEntitlementDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.member.SkitEntitlementGrantDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.revenue.SkitAdRevenueEventDO;
import cn.iocoder.yudao.module.skit.dal.mysql.ad.SkitAdCallbackInboxMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.ad.SkitAdNetworkCapabilityMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.ad.SkitAdSessionMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.ad.SkitTenantAdCapabilityMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.member.SkitContentEntitlementMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.member.SkitEntitlementGrantMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.revenue.SkitAdRevenueEventMapper;
import cn.iocoder.yudao.module.skit.framework.crypto.SkitCallbackPayloadCryptoService;
import cn.iocoder.yudao.module.skit.service.ad.SkitAdCredentialVersionService;
import cn.iocoder.yudao.module.skit.service.ad.SkitAdSessionTokenService;
import cn.iocoder.yudao.module.skit.service.ad.SkitHmacAdSessionTokenService;
import cn.iocoder.yudao.module.skit.service.commission.SkitFrozenCommissionProjectionService;
import cn.iocoder.yudao.module.skit.service.commission.SkitPolicySnapshotService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.Select;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SkitAdCallbackProcessorTest {

    private static final long TENANT_ID = 17L;
    private static final long ACCOUNT_ID = 29L;
    private static final long SESSION_ID = 55L;
    private static final long INBOX_ID = 91L;
    private static final long IMPRESSION_INBOX_ID = 92L;
    private static final long SNAPSHOT_ID = 73L;
    private static final long MEMBER_ID = 42L;
    private static final long DRAMA_ID = 801L;
    private static final String WORKER = "callback-worker-a";
    private static final String PLACEMENT = "reward-placement";
    private static final String TRANSACTION = "reward-transaction-789";
    private static final String SIGNED_SHOW = "signed-show-456";
    private static final String SESSION_PUBLIC_ID = "0123456789abcdefghijkl";
    private static final String ADSOURCE = "56789";
    private static final String PSEUDONYMOUS_USER = "m_member_42";
    private static final byte[] REWARD_SECRET = "taku-reward-secret-32-bytes-value"
            .getBytes(StandardCharsets.US_ASCII);
    private static final LocalDateTime RECEIVED_AT = LocalDateTime.of(2026, 7, 14, 23, 20);
    private static final LocalDateTime PROCESSING_AT = LocalDateTime.of(2026, 7, 15, 1, 20);

    private SkitAdCallbackInboxMapper inboxMapper;
    private SkitAdSessionMapper sessionMapper;
    private SkitAdNetworkCapabilityMapper capabilityMapper;
    private SkitTenantAdCapabilityMapper tenantCapabilityMapper;
    private SkitContentEntitlementMapper entitlementMapper;
    private SkitEntitlementGrantMapper grantMapper;
    private SkitAdRevenueEventMapper revenueMapper;
    private SkitCallbackPayloadCryptoService payloadCrypto;
    private SkitAdCredentialVersionService credentialService;
    private SkitAdSessionTokenService tokenService;
    private SkitPolicySnapshotService snapshotService;
    private SkitFrozenCommissionProjectionService projectionService;
    private SkitAdCallbackProcessorImpl processor;
    private SkitAdSessionDO session;
    private SkitAdCallbackInboxDO inbox;
    private String customData;
    private String rewardQuery;
    private AtomicReference<byte[]> decryptedPayload;

    @BeforeEach
    void setUp() {
        TenantContextHolder.setTenantId(TENANT_ID);
        inboxMapper = mock(SkitAdCallbackInboxMapper.class);
        sessionMapper = mock(SkitAdSessionMapper.class);
        capabilityMapper = mock(SkitAdNetworkCapabilityMapper.class);
        tenantCapabilityMapper = mock(SkitTenantAdCapabilityMapper.class);
        entitlementMapper = mock(SkitContentEntitlementMapper.class);
        grantMapper = mock(SkitEntitlementGrantMapper.class);
        revenueMapper = mock(SkitAdRevenueEventMapper.class);
        payloadCrypto = mock(SkitCallbackPayloadCryptoService.class);
        credentialService = mock(SkitAdCredentialVersionService.class);
        snapshotService = mock(SkitPolicySnapshotService.class);
        projectionService = mock(SkitFrozenCommissionProjectionService.class);
        tokenService = new SkitHmacAdSessionTokenService(1, Collections.singletonMap(1,
                "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.US_ASCII)));
        customData = tokenService.issue("server-session-public-id").consumeCustomData();
        rewardQuery = signedRewardQuery(customData, TRANSACTION, SIGNED_SHOW);
        decryptedPayload = new AtomicReference<>(rewardQuery.getBytes(StandardCharsets.US_ASCII));
        Clock clock = Clock.fixed(Instant.parse("2026-07-14T17:20:00Z"),
                ZoneId.of("Asia/Shanghai"));
        processor = new SkitAdCallbackProcessorImpl(inboxMapper, sessionMapper, capabilityMapper,
                entitlementMapper, grantMapper, revenueMapper, payloadCrypto, credentialService,
                tokenService, snapshotService, projectionService, new TakuCallbackCanonicalizer(),
                new TakuRewardSignatureVerifier(new ObjectMapper()),
                new SkitRewardAuthorityPolicy(tenantCapabilityMapper, capabilityMapper), clock);

        SkitPolicySnapshotService.PolicySnapshot snapshot =
                mock(SkitPolicySnapshotService.PolicySnapshot.class);
        when(snapshot.getId()).thenReturn(SNAPSHOT_ID);
        when(snapshot.getTenantId()).thenReturn(TENANT_ID);
        when(snapshot.getSourceMemberId()).thenReturn(MEMBER_ID);
        when(snapshot.getRuleVersion()).thenReturn(5);
        when(snapshotService.getRequired(SNAPSHOT_ID)).thenReturn(snapshot);
        when(projectionService.projectRewardedEstimate(any(SkitAdRevenueEventDO.class)))
                .thenReturn(mock(SkitFrozenCommissionProjectionService.ProjectionResult.class));
        when(projectionService.projectNonRewardedEstimate(any(SkitAdRevenueEventDO.class)))
                .thenReturn(mock(SkitFrozenCommissionProjectionService.ProjectionResult.class));

        session = rewardSession();
        inbox = rewardInbox(rewardQuery);
        when(inboxMapper.selectActiveClaimForUpdate(TENANT_ID, ACCOUNT_ID, INBOX_ID, WORKER))
                .thenAnswer(invocation -> inbox);
        when(sessionMapper.selectByTenantAccountAndIdForUpdate(TENANT_ID, ACCOUNT_ID, SESSION_ID))
                .thenAnswer(invocation -> session);
        when(capabilityMapper.selectForShare(TENANT_ID, ACCOUNT_ID, 66))
                .thenReturn(rewardCapability());
        when(tenantCapabilityMapper.selectByTenantForShare(TENANT_ID))
                .thenReturn(selectedNetworks("[66]"));
        when(payloadCrypto.decrypt(any(), any())).thenAnswer(invocation ->
                decryptedPayload.get().clone());
        when(credentialService.resolveRewardSecret(TENANT_ID, ACCOUNT_ID, 7,
                session.getRewardAcceptUntil(), RECEIVED_AT)).thenAnswer(invocation ->
                new SkitAdCredentialVersionService.ResolvedRewardSecret(
                        TENANT_ID, ACCOUNT_ID, 7, false, RECEIVED_AT.plusMinutes(1),
                        REWARD_SECRET.clone()));
        when(sessionMapper.markSignedRewardAndGrantCas(eq(TENANT_ID), eq(SESSION_ID), eq(ACCOUNT_ID),
                eq(INBOX_ID), eq(RECEIVED_AT), anyInt(), eq(4), eq(7), anyString(),
                any(), anyInt(), anyString(), eq(PROCESSING_AT))).thenReturn(1);
        when(sessionMapper.markRewardReceiptRejectedCas(eq(TENANT_ID), eq(SESSION_ID), eq(ACCOUNT_ID),
                eq(INBOX_ID), eq(RECEIVED_AT), anyInt(), eq(PROCESSING_AT), anyString()))
                .thenReturn(1);
        when(sessionMapper.updateRevenueStateCas(eq(TENANT_ID), eq(SESSION_ID), eq(ACCOUNT_ID),
                anyInt(), anyString(), anyString(), eq(PROCESSING_AT))).thenReturn(1);
        when(inboxMapper.markSucceededCas(TENANT_ID, ACCOUNT_ID, INBOX_ID, WORKER))
                .thenReturn(1);
        when(inboxMapper.markRejectedCas(eq(TENANT_ID), eq(ACCOUNT_ID), eq(INBOX_ID), eq(WORKER),
                anyString())).thenReturn(1);
        when(inboxMapper.selectByTenantAccountAndIdForUpdate(
                TENANT_ID, ACCOUNT_ID, IMPRESSION_INBOX_ID))
                .thenReturn(impressionAuthorityInbox(IMPRESSION_INBOX_ID, 66, ADSOURCE));
        when(entitlementMapper.selectEpisodesForUpdate(TENANT_ID, MEMBER_ID, DRAMA_ID,
                Collections.singletonList(3))).thenReturn(Collections.emptyList());
        AtomicLong entitlementId = new AtomicLong(500);
        doAnswer(invocation -> {
            SkitContentEntitlementDO row = invocation.getArgument(0);
            row.setId(entitlementId.incrementAndGet());
            return 1;
        }).when(entitlementMapper).insertGrantedIfAbsent(any(SkitContentEntitlementDO.class));
        when(entitlementMapper.renewExpiredLeaseCas(anyLong(), anyLong(), anyLong(), anyLong(),
                anyInt(), anyInt(), any(LocalDateTime.class), any(LocalDateTime.class))).thenReturn(1);
        when(grantMapper.selectBySessionAndEpisodeForUpdate(eq(TENANT_ID), eq(SESSION_ID), anyInt()))
                .thenReturn(null);
        when(grantMapper.insert(any(SkitEntitlementGrantDO.class))).thenReturn(1);
        when(revenueMapper.selectByTenantSessionAndSourceForUpdate(
                TENANT_ID, SESSION_ID, "TAKU_IMPRESSION")).thenReturn(null);
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    @Test
    void legacyMultiEpisodeRewardIsRejectedWithoutGrantingAnyEpisode() {
        session.setEpisodeFrom(3).setEpisodeTo(4).setUnlockScope("range:3-4");

        SkitAdCallbackProcessor.ProcessResult result =
                processor.process(TENANT_ID, ACCOUNT_ID, INBOX_ID, WORKER);

        assertEquals(SkitAdCallbackProcessor.Outcome.REJECTED, result.getOutcome());
        assertEquals("LEGACY_MULTI_EPISODE_SCOPE", result.getErrorCode());
        verify(sessionMapper).markRewardReceiptRejectedCas(TENANT_ID, SESSION_ID, ACCOUNT_ID,
                INBOX_ID, RECEIVED_AT, 9, PROCESSING_AT, "LEGACY_MULTI_EPISODE_SCOPE");
        verify(entitlementMapper, never()).insertGrantedIfAbsent(any());
        verify(grantMapper, never()).insert(any());
        verify(sessionMapper, never()).markSignedRewardAndGrantCas(
                anyLong(), anyLong(), anyLong(), anyLong(), any(), anyInt(), anyInt(), anyInt(),
                anyString(), any(), anyInt(), anyString(), any());
    }

    @Test
    void delayedRewardUsesReceivedTimeAndGrantsExactEpisodeWhileKeepingTransactionAndShowDistinct() {
        session.setClientLifecycleStatus("CLOSED");
        session.setRevenueStatus("IMPRESSION_PENDING_REWARD");
        SkitAdRevenueEventDO pendingEstimate = pendingEstimate();
        SkitAdRevenueEventDO rewardedEstimate = pendingEstimate().setRewardQualificationStatus("REWARDED")
                .setProviderTransactionId(TRANSACTION).setProviderShowId(SIGNED_SHOW).setVersion(3);
        when(revenueMapper.selectByTenantSessionAndSourceForUpdate(
                TENANT_ID, SESSION_ID, "TAKU_IMPRESSION")).thenReturn(pendingEstimate, rewardedEstimate);
        when(revenueMapper.markRewardQualifiedCas(TENANT_ID, pendingEstimate.getId(), SESSION_ID,
                ACCOUNT_ID, pendingEstimate.getVersion(), IMPRESSION_INBOX_ID, PLACEMENT, 66,
                ADSOURCE, TRANSACTION, SIGNED_SHOW, RECEIVED_AT))
                .thenReturn(1);

        SkitAdCallbackProcessor.ProcessResult result =
                processor.process(TENANT_ID, ACCOUNT_ID, INBOX_ID, WORKER);

        assertEquals(SkitAdCallbackProcessor.Outcome.SUCCEEDED, result.getOutcome());
        verify(credentialService).resolveRewardSecret(TENANT_ID, ACCOUNT_ID, 7,
                session.getRewardAcceptUntil(), RECEIVED_AT);
        verify(sessionMapper).markSignedRewardAndGrantCas(TENANT_ID, SESSION_ID, ACCOUNT_ID,
                INBOX_ID, RECEIVED_AT, 9, 4, 7, TRANSACTION, SIGNED_SHOW, 66, ADSOURCE,
                PROCESSING_AT);
        ArgumentCaptor<SkitContentEntitlementDO> entitlements =
                ArgumentCaptor.forClass(SkitContentEntitlementDO.class);
        verify(entitlementMapper)
                .insertGrantedIfAbsent(entitlements.capture());
        assertEquals(3, entitlements.getValue().getEpisodeNo());
        ArgumentCaptor<SkitEntitlementGrantDO> grants =
                ArgumentCaptor.forClass(SkitEntitlementGrantDO.class);
        verify(grantMapper).insert(grants.capture());
        assertEquals(TRANSACTION, grants.getValue().getProviderTransactionId());
        verify(revenueMapper).markRewardQualifiedCas(TENANT_ID, pendingEstimate.getId(), SESSION_ID,
                ACCOUNT_ID, pendingEstimate.getVersion(), IMPRESSION_INBOX_ID, PLACEMENT, 66,
                ADSOURCE, TRANSACTION, SIGNED_SHOW, RECEIVED_AT);
        verify(inboxMapper).selectByTenantAccountAndIdForUpdate(
                TENANT_ID, ACCOUNT_ID, IMPRESSION_INBOX_ID);
        verify(projectionService).projectRewardedEstimate(rewardedEstimate);
        verify(inboxMapper).markSucceededCas(TENANT_ID, ACCOUNT_ID, INBOX_ID, WORKER);
        String rewardCasSql = updateSql(SkitAdRevenueEventMapper.class, "markRewardQualifiedCas");
        assertTrue(rewardCasSql.contains("callback_inbox_id`=#{expectedcallbackinboxid}"));
        assertTrue(rewardCasSql.contains("placement_id`=#{expectedplacementid}"));
        assertTrue(rewardCasSql.contains("network_firm_id`=#{expectednetworkfirmid}"));
        assertTrue(rewardCasSql.contains("adsource_id`=#{expectedadsourceid}"));
    }

    @Test
    void newSignedRewardCreatesANewLeaseForAnExpiredEpisodeInsteadOfReportingAlreadyOwned() {
        SkitContentEntitlementDO expired = entitlement(3, "GRANTED", 503L)
                .setGrantedAt(PROCESSING_AT.minusMinutes(5));
        when(entitlementMapper.selectEpisodesForUpdate(TENANT_ID, MEMBER_ID, DRAMA_ID,
                Collections.singletonList(3))).thenReturn(Collections.singletonList(expired));

        SkitAdCallbackProcessor.ProcessResult result =
                processor.process(TENANT_ID, ACCOUNT_ID, INBOX_ID, WORKER);

        assertEquals(SkitAdCallbackProcessor.Outcome.SUCCEEDED, result.getOutcome());
        ArgumentCaptor<SkitEntitlementGrantDO> grants =
                ArgumentCaptor.forClass(SkitEntitlementGrantDO.class);
        verify(grantMapper).insert(grants.capture());
        assertEquals("CREATED", grants.getAllValues().get(0).getGrantResult());
        assertEquals(PROCESSING_AT, expired.getGrantedAt());
        verify(entitlementMapper).renewExpiredLeaseCas(TENANT_ID, expired.getId(), MEMBER_ID,
                DRAMA_ID, 3, 0, PROCESSING_AT.minusMinutes(5), PROCESSING_AT);
    }

    @Test
    void signedRewardCannotPromoteAnImpressionFromAnotherNetworkIdentity() {
        String impressionAdsource = "attacker-source-a";
        session.setRevenueStatus("IMPRESSION_PENDING_REWARD");
        SkitAdRevenueEventDO pendingEstimate = pendingEstimate().setAdsourceId(impressionAdsource);
        SkitAdCallbackInboxDO impressionEvidence = impressionAuthorityInbox(
                pendingEstimate.getCallbackInboxId(), 67, impressionAdsource);
        when(revenueMapper.selectByTenantSessionAndSourceForUpdate(
                TENANT_ID, SESSION_ID, "TAKU_IMPRESSION")).thenReturn(pendingEstimate);
        when(inboxMapper.selectByTenantAccountAndIdForUpdate(
                TENANT_ID, ACCOUNT_ID, pendingEstimate.getCallbackInboxId()))
                .thenReturn(impressionEvidence);

        SkitAdCallbackProcessor.ProcessResult result =
                processor.process(TENANT_ID, ACCOUNT_ID, INBOX_ID, WORKER);

        assertEquals(SkitAdCallbackProcessor.Outcome.REJECTED, result.getOutcome());
        assertEquals("SIGNED_REWARD_IMPRESSION_AUTHORITY_MISMATCH", result.getErrorCode());
        verify(inboxMapper).selectByTenantAccountAndIdForUpdate(
                TENANT_ID, ACCOUNT_ID, pendingEstimate.getCallbackInboxId());
        verify(sessionMapper).markRewardReceiptRejectedCas(TENANT_ID, SESSION_ID, ACCOUNT_ID,
                INBOX_ID, RECEIVED_AT, 9, PROCESSING_AT,
                "SIGNED_REWARD_IMPRESSION_AUTHORITY_MISMATCH");
        verify(inboxMapper).markRejectedCas(TENANT_ID, ACCOUNT_ID, INBOX_ID, WORKER,
                "SIGNED_REWARD_IMPRESSION_AUTHORITY_MISMATCH");
        verify(sessionMapper, never()).markSignedRewardAndGrantCas(anyLong(), anyLong(), anyLong(),
                anyLong(), any(), anyInt(), anyInt(), anyInt(), anyString(), any(), anyInt(),
                anyString(), any());
        verify(entitlementMapper, never()).insertGrantedIfAbsent(any());
        verify(grantMapper, never()).insert(any());
        verify(revenueMapper, never()).markRewardQualifiedCas(anyLong(), anyLong(), anyLong(),
                anyLong(), anyInt(), anyLong(), anyString(), anyInt(), anyString(), anyString(),
                any(), any());
        verify(revenueMapper, never()).markNonRewardedSuspenseCas(anyLong(), anyLong(), anyLong(),
                anyLong(), anyInt(), any());
        verify(projectionService, never()).projectRewardedEstimate(any());
        assertEquals("PENDING_REWARD", pendingEstimate.getRewardQualificationStatus());
        assertEquals("FROZEN", pendingEstimate.getReconciliationStatus());
    }

    @Test
    void signedRewardCannotPromoteAnImpressionFromAnotherPlacement() {
        String impressionPlacement = "impression-placement-a";
        session.setRevenueStatus("IMPRESSION_PENDING_REWARD");
        SkitAdRevenueEventDO pendingEstimate = pendingEstimate().setPlacementId(impressionPlacement);
        SkitAdCallbackInboxDO impressionEvidence = impressionAuthorityInbox(
                pendingEstimate.getCallbackInboxId(), 66, ADSOURCE)
                .setPlacementId(impressionPlacement);
        when(revenueMapper.selectByTenantSessionAndSourceForUpdate(
                TENANT_ID, SESSION_ID, "TAKU_IMPRESSION")).thenReturn(pendingEstimate);
        when(inboxMapper.selectByTenantAccountAndIdForUpdate(
                TENANT_ID, ACCOUNT_ID, pendingEstimate.getCallbackInboxId()))
                .thenReturn(impressionEvidence);

        SkitAdCallbackProcessor.ProcessResult result =
                processor.process(TENANT_ID, ACCOUNT_ID, INBOX_ID, WORKER);

        assertEquals(SkitAdCallbackProcessor.Outcome.REJECTED, result.getOutcome());
        assertEquals("SIGNED_REWARD_IMPRESSION_AUTHORITY_MISMATCH", result.getErrorCode());
        verify(sessionMapper, never()).markSignedRewardAndGrantCas(anyLong(), anyLong(), anyLong(),
                anyLong(), any(), anyInt(), anyInt(), anyInt(), anyString(), any(), anyInt(),
                anyString(), any());
        verify(entitlementMapper, never()).insertGrantedIfAbsent(any());
        verify(grantMapper, never()).insert(any());
        verify(revenueMapper, never()).markRewardQualifiedCas(anyLong(), anyLong(), anyLong(),
                anyLong(), anyInt(), anyLong(), anyString(), anyInt(), anyString(), anyString(),
                any(), any());
        assertEquals("PENDING_REWARD", pendingEstimate.getRewardQualificationStatus());
        assertEquals("FROZEN", pendingEstimate.getReconciliationStatus());
    }

    @Test
    void rewardConvergenceRejectsMissingSignedShowAfterSuccessfulCas() {
        session.setRevenueStatus("IMPRESSION_PENDING_REWARD");
        SkitAdRevenueEventDO pendingEstimate = pendingEstimate();
        SkitAdRevenueEventDO incompleteRewardedEstimate = pendingEstimate()
                .setRewardQualificationStatus("REWARDED")
                .setProviderTransactionId(TRANSACTION)
                .setProviderShowId(null)
                .setVersion(3);
        when(revenueMapper.selectByTenantSessionAndSourceForUpdate(
                TENANT_ID, SESSION_ID, "TAKU_IMPRESSION"))
                .thenReturn(pendingEstimate, incompleteRewardedEstimate);
        when(revenueMapper.markRewardQualifiedCas(TENANT_ID, pendingEstimate.getId(), SESSION_ID,
                ACCOUNT_ID, pendingEstimate.getVersion(), IMPRESSION_INBOX_ID, PLACEMENT, 66,
                ADSOURCE, TRANSACTION, SIGNED_SHOW, RECEIVED_AT))
                .thenReturn(1);

        assertThrows(IllegalStateException.class,
                () -> processor.process(TENANT_ID, ACCOUNT_ID, INBOX_ID, WORKER));

        verify(projectionService, never()).projectRewardedEstimate(any());
        verify(inboxMapper, never()).markSucceededCas(anyLong(), anyLong(), anyLong(), anyString());
    }

    @Test
    void alreadyRewardedEstimateRequiresExactProviderTransaction() {
        SkitAdRevenueEventDO incompleteRewardedEstimate = pendingEstimate()
                .setRewardQualificationStatus("REWARDED")
                .setProviderTransactionId(null)
                .setProviderShowId(SIGNED_SHOW);
        when(revenueMapper.selectByTenantSessionAndSourceForUpdate(
                TENANT_ID, SESSION_ID, "TAKU_IMPRESSION"))
                .thenReturn(incompleteRewardedEstimate);

        assertThrows(IllegalStateException.class,
                () -> processor.process(TENANT_ID, ACCOUNT_ID, INBOX_ID, WORKER));

        verify(projectionService, never()).projectRewardedEstimate(any());
        verify(inboxMapper, never()).markSucceededCas(anyLong(), anyLong(), anyLong(), anyString());
    }

    @Test
    void signedRewardWithoutSignedShowPreservesIndependentClientShow() {
        String queryWithoutShow = signedRewardQuery(customData, TRANSACTION, null);
        decryptedPayload.set(queryWithoutShow.getBytes(StandardCharsets.US_ASCII));
        inbox = rewardInbox(queryWithoutShow).setProviderShowId(null);
        session.setProviderShowId("client-sdk-show-id");

        SkitAdCallbackProcessor.ProcessResult result =
                processor.process(TENANT_ID, ACCOUNT_ID, INBOX_ID, WORKER);

        assertEquals(SkitAdCallbackProcessor.Outcome.SUCCEEDED, result.getOutcome());
        verify(sessionMapper).markSignedRewardAndGrantCas(TENANT_ID, SESSION_ID, ACCOUNT_ID,
                INBOX_ID, RECEIVED_AT, 9, 4, 7, TRANSACTION, null, 66, ADSOURCE,
                PROCESSING_AT);
    }

    @Test
    void signedShowCustomExtAllowsAStoredCachedCustomDataPayloadToGrantItsOwnSession() {
        String cachedCustomData = tokenService.issue("cached-session-from-prior-ad").consumeCustomData();
        session.setSessionId(SESSION_PUBLIC_ID);
        String query = signedRewardQuery(cachedCustomData, TRANSACTION, SIGNED_SHOW, SESSION_PUBLIC_ID);
        decryptedPayload.set(query.getBytes(StandardCharsets.US_ASCII));
        inbox = rewardInbox(query).setExtraDataHash(tokenService.hashCustomData(cachedCustomData));

        SkitAdCallbackProcessor.ProcessResult result =
                processor.process(TENANT_ID, ACCOUNT_ID, INBOX_ID, WORKER);

        assertEquals(SkitAdCallbackProcessor.Outcome.SUCCEEDED, result.getOutcome());
        verify(sessionMapper).markSignedRewardAndGrantCas(TENANT_ID, SESSION_ID, ACCOUNT_ID,
                INBOX_ID, RECEIVED_AT, 9, 4, 7, TRANSACTION, SIGNED_SHOW, 66, ADSOURCE,
                PROCESSING_AT);
    }

    @Test
    void signedShowMismatchOrReceivedAfterDeadlineRejectsWithoutEntitlement() {
        session.setProviderShowId("different-client-show");
        session.setRevenueStatus("IMPRESSION_PENDING_REWARD");
        SkitAdRevenueEventDO pendingEstimate = pendingEstimate();
        when(revenueMapper.selectByTenantSessionAndSourceForUpdate(
                TENANT_ID, SESSION_ID, "TAKU_IMPRESSION")).thenReturn(pendingEstimate);
        when(revenueMapper.markNonRewardedSuspenseCas(TENANT_ID, pendingEstimate.getId(), SESSION_ID,
                ACCOUNT_ID, pendingEstimate.getVersion(), PROCESSING_AT)).thenReturn(1);

        SkitAdCallbackProcessor.ProcessResult showMismatch =
                processor.process(TENANT_ID, ACCOUNT_ID, INBOX_ID, WORKER);

        assertEquals(SkitAdCallbackProcessor.Outcome.REJECTED, showMismatch.getOutcome());
        assertEquals("SIGNED_SHOW_MISMATCH", showMismatch.getErrorCode());
        verify(entitlementMapper, never()).insertGrantedIfAbsent(any());
        verify(grantMapper, never()).insert(any());
        verify(sessionMapper).markRewardReceiptRejectedCas(TENANT_ID, SESSION_ID, ACCOUNT_ID,
                INBOX_ID, RECEIVED_AT, 9, PROCESSING_AT, "SIGNED_SHOW_MISMATCH");
        verify(revenueMapper).markNonRewardedSuspenseCas(TENANT_ID, pendingEstimate.getId(), SESSION_ID,
                ACCOUNT_ID, pendingEstimate.getVersion(), PROCESSING_AT);
        verify(inboxMapper).markRejectedCas(TENANT_ID, ACCOUNT_ID, INBOX_ID, WORKER,
                "SIGNED_SHOW_MISMATCH");
        String rejectSql = updateSql(SkitAdSessionMapper.class, "markRewardReceiptRejectedCas");
        assertTrue(rejectSql.contains("revenue_status`=case when `revenue_status`='impression_pending_reward'"));
        assertTrue(rejectSql.contains("then 'suspense'"));
        String eventSql = updateSql(SkitAdRevenueEventMapper.class, "markNonRewardedSuspenseCas");
        assertTrue(eventSql.contains("reward_qualification_status`='non_rewarded'"));
        assertTrue(eventSql.contains("reconciliation_status`='suspense'"));
        assertTrue(Arrays.stream(SkitAdCallbackProcessorImpl.class.getDeclaredFields())
                .noneMatch(field -> field.getType().getSimpleName().contains("Ledger")),
                "integrity rejection must not create ledger side effects");
    }

    @Test
    void revokedExistingEntitlementFailsClosedAndCannotBeRegranted() {
        SkitContentEntitlementDO revoked = entitlement(3, "SECURITY_REVOKED", 301L);
        when(entitlementMapper.selectEpisodesForUpdate(TENANT_ID, MEMBER_ID, DRAMA_ID,
                Collections.singletonList(3))).thenReturn(Collections.singletonList(revoked));

        SkitAdCallbackProcessor.ProcessResult result =
                processor.process(TENANT_ID, ACCOUNT_ID, INBOX_ID, WORKER);

        assertEquals(SkitAdCallbackProcessor.Outcome.REJECTED, result.getOutcome());
        assertEquals("ENTITLEMENT_SECURITY_REVOKED", result.getErrorCode());
        verify(entitlementMapper, never()).insertGrantedIfAbsent(any());
        verify(grantMapper, never()).insert(any());
    }

    @Test
    void forgedTenantOrAccountEnvelopeNeverMutatesBusinessFacts() {
        TenantContextHolder.setTenantId(999L);
        assertThrows(IllegalStateException.class,
                () -> processor.process(TENANT_ID, ACCOUNT_ID, INBOX_ID, WORKER));
        verify(inboxMapper, never()).selectActiveClaimForUpdate(
                anyLong(), anyLong(), anyLong(), anyString());

        TenantContextHolder.setTenantId(TENANT_ID);
        inbox.setAdAccountId(ACCOUNT_ID + 1);
        assertThrows(IllegalStateException.class,
                () -> processor.process(TENANT_ID, ACCOUNT_ID, INBOX_ID, WORKER));
        verify(sessionMapper, never()).markSignedRewardAndGrantCas(anyLong(), anyLong(), anyLong(),
                anyLong(), any(), anyInt(), anyInt(), anyInt(), anyString(), any(), anyInt(),
                anyString(), any());
    }

    @Test
    void databaseRejectedOrExpiredClaimCannotReachAnyBusinessMutation() {
        when(inboxMapper.selectActiveClaimForUpdate(TENANT_ID, ACCOUNT_ID, INBOX_ID, WORKER))
                .thenReturn(null);

        assertThrows(IllegalStateException.class,
                () -> processor.process(TENANT_ID, ACCOUNT_ID, INBOX_ID, WORKER));

        verify(sessionMapper, never()).selectByTenantAccountAndIdForUpdate(
                anyLong(), anyLong(), anyLong());
        verify(inboxMapper, never()).markSucceededCas(anyLong(), anyLong(), anyLong(), anyString());
        verify(inboxMapper, never()).markRejectedCas(
                anyLong(), anyLong(), anyLong(), anyString(), anyString());
        verify(entitlementMapper, never()).insertGrantedIfAbsent(any());
        verify(revenueMapper, never()).insert(any(SkitAdRevenueEventDO.class));
    }

    @Test
    void matchedImpressionUsesExactIntegerEcpmDivisionAndNeverGrantsEntitlement() {
        String query = impressionQuery("12.345678901234", "USD");
        decryptedPayload.set(query.getBytes(StandardCharsets.US_ASCII));
        inbox = impressionInbox(query);
        session.setRewardVerificationStatus("PENDING");
        session.setRevenueStatus("NONE");
        when(capabilityMapper.selectForShare(TENANT_ID, ACCOUNT_ID, 66))
                .thenReturn(impressionCapability());
        AtomicReference<SkitAdRevenueEventDO> inserted = new AtomicReference<>();
        doAnswer(invocation -> {
            SkitAdRevenueEventDO row = invocation.getArgument(0);
            row.setId(700L);
            inserted.set(row);
            return 1;
        }).when(revenueMapper).insert(any(SkitAdRevenueEventDO.class));

        SkitAdCallbackProcessor.ProcessResult result =
                processor.process(TENANT_ID, ACCOUNT_ID, INBOX_ID, WORKER);

        assertEquals(SkitAdCallbackProcessor.Outcome.SUCCEEDED, result.getOutcome());
        SkitAdRevenueEventDO event = inserted.get();
        assertEquals(Long.valueOf(12_345_678_901_234_000L), event.getSourceAmountUnits());
        assertEquals(Long.valueOf(12_345_678_901_234L), event.getEstimatedAmountUnits());
        assertEquals(Integer.valueOf(15), event.getAmountScale());
        assertEquals(new BigDecimal("0.01234567"), event.getGrossAmount());
        assertEquals("USD", event.getSourceCurrency());
        assertEquals("MATCHED", event.getMatchStatus());
        assertEquals("UNSIGNED_OBSERVATION", event.getSourceVerificationStatus());
        assertEquals("PENDING_REWARD", event.getRewardQualificationStatus());
        assertEquals("FROZEN", event.getReconciliationStatus());
        assertEquals(Boolean.FALSE, event.getLegacyUnverified());
        assertEquals(Integer.valueOf(5), event.getRuleVersion());
        verify(sessionMapper).updateRevenueStateCas(TENANT_ID, SESSION_ID, ACCOUNT_ID, 9,
                "NONE", "IMPRESSION_PENDING_REWARD", PROCESSING_AT);
        verify(snapshotService).getRequired(SNAPSHOT_ID);
        verify(projectionService, never()).projectRewardedEstimate(any());
        verify(entitlementMapper, never()).insertGrantedIfAbsent(any());
        verify(grantMapper, never()).insert(any());
        verify(sessionMapper, never()).markSignedRewardAndGrantCas(anyLong(), anyLong(), anyLong(),
                anyLong(), any(), anyInt(), anyInt(), anyInt(), anyString(), any(), anyInt(),
                anyString(), any());
    }

    @Test
    void impressionAfterRewardCreatesRewardQualifiedFrozenEventWithoutTouchingRewardState() {
        String query = impressionQuery("1", "CNY");
        decryptedPayload.set(query.getBytes(StandardCharsets.US_ASCII));
        inbox = impressionInbox(query);
        session.setRewardVerificationStatus("SIGNED_VERIFIED");
        session.setEntitlementStatus("GRANTED");
        session.setRevenueStatus("NONE");
        session.setProviderTransactionId(TRANSACTION).setNetworkFirmId(66).setAdsourceId(ADSOURCE);
        when(capabilityMapper.selectForShare(TENANT_ID, ACCOUNT_ID, 66))
                .thenReturn(impressionCapability());
        doAnswer(invocation -> {
            SkitAdRevenueEventDO row = invocation.getArgument(0);
            row.setId(701L);
            return 1;
        }).when(revenueMapper).insert(any(SkitAdRevenueEventDO.class));

        SkitAdCallbackProcessor.ProcessResult result =
                processor.process(TENANT_ID, ACCOUNT_ID, INBOX_ID, WORKER);

        assertEquals(SkitAdCallbackProcessor.Outcome.SUCCEEDED, result.getOutcome());
        ArgumentCaptor<SkitAdRevenueEventDO> event = ArgumentCaptor.forClass(SkitAdRevenueEventDO.class);
        verify(revenueMapper).insert(event.capture());
        assertEquals("REWARDED", event.getValue().getRewardQualificationStatus());
        assertEquals(TRANSACTION, event.getValue().getProviderTransactionId());
        assertEquals(Integer.valueOf(5), event.getValue().getRuleVersion());
        verify(projectionService).projectRewardedEstimate(event.getValue());
        verify(sessionMapper).updateRevenueStateCas(TENANT_ID, SESSION_ID, ACCOUNT_ID, 9,
                "NONE", "FROZEN", PROCESSING_AT);
        verify(sessionMapper, never()).markSignedRewardAndGrantCas(anyLong(), anyLong(), anyLong(),
                anyLong(), any(), anyInt(), anyInt(), anyInt(), anyString(), any(), anyInt(),
                anyString(), any());
    }

    @Test
    void signedRewardRejectsUnsignedImpressionFromDifferentNetworkIdentity() {
        String attackerAdsource = "56790";
        String query = impressionQuery("1", "CNY")
                .replace("nw_firm_id=66", "nw_firm_id=67")
                .replace("adsource_id=" + ADSOURCE, "adsource_id=" + attackerAdsource);
        decryptedPayload.set(query.getBytes(StandardCharsets.US_ASCII));
        inbox = impressionInbox(query).setNetworkFirmId(67).setAdsourceId(attackerAdsource);
        session.setRewardVerificationStatus("SIGNED_VERIFIED");
        session.setEntitlementStatus("GRANTED");
        session.setRevenueStatus("NONE");
        session.setProviderTransactionId(TRANSACTION).setNetworkFirmId(66).setAdsourceId(ADSOURCE);

        SkitAdCallbackProcessor.ProcessResult result =
                processor.process(TENANT_ID, ACCOUNT_ID, INBOX_ID, WORKER);

        assertEquals(SkitAdCallbackProcessor.Outcome.REJECTED, result.getOutcome());
        assertEquals("IMPRESSION_SIGNED_AUTHORITY_MISMATCH", result.getErrorCode());
        verify(capabilityMapper, never()).selectForShare(TENANT_ID, ACCOUNT_ID, 67);
        verify(revenueMapper, never()).insert(any(SkitAdRevenueEventDO.class));
        verify(projectionService, never()).projectRewardedEstimate(any());
    }

    @Test
    void existingRewardedImpressionRequiresExactSignedShow() {
        String query = impressionQuery("1", "USD");
        decryptedPayload.set(query.getBytes(StandardCharsets.US_ASCII));
        inbox = impressionInbox(query);
        session.setRewardVerificationStatus("SIGNED_VERIFIED");
        session.setEntitlementStatus("GRANTED");
        session.setRevenueStatus("FROZEN");
        session.setProviderTransactionId(TRANSACTION).setNetworkFirmId(66).setAdsourceId(ADSOURCE);
        when(capabilityMapper.selectForShare(TENANT_ID, ACCOUNT_ID, 66))
                .thenReturn(impressionCapability());
        SkitAdRevenueEventDO existing = matchedExistingEvent(query, "REWARDED", "FROZEN",
                1_000_000L, 1_000L, 6).setProviderShowId(null);
        when(revenueMapper.selectByTenantSessionAndSourceForUpdate(
                TENANT_ID, SESSION_ID, "TAKU_IMPRESSION")).thenReturn(existing);

        assertThrows(IllegalStateException.class,
                () -> processor.process(TENANT_ID, ACCOUNT_ID, INBOX_ID, WORKER));

        verify(projectionService, never()).projectRewardedEstimate(any());
        verify(inboxMapper, never()).markSucceededCas(anyLong(), anyLong(), anyLong(), anyString());
    }

    @Test
    void existingImpressionRequiresExactFrozenPolicyRuleVersion() {
        String query = impressionQuery("1", "USD");
        decryptedPayload.set(query.getBytes(StandardCharsets.US_ASCII));
        inbox = impressionInbox(query);
        session.setRewardVerificationStatus("SIGNED_VERIFIED");
        session.setEntitlementStatus("GRANTED");
        session.setRevenueStatus("FROZEN");
        session.setProviderTransactionId(TRANSACTION).setNetworkFirmId(66).setAdsourceId(ADSOURCE);
        when(capabilityMapper.selectForShare(TENANT_ID, ACCOUNT_ID, 66))
                .thenReturn(impressionCapability());
        SkitAdRevenueEventDO existing = matchedExistingEvent(query, "REWARDED", "FROZEN",
                1_000_000L, 1_000L, 6).setRuleVersion(6);
        when(revenueMapper.selectByTenantSessionAndSourceForUpdate(
                TENANT_ID, SESSION_ID, "TAKU_IMPRESSION")).thenReturn(existing);

        assertThrows(IllegalStateException.class,
                () -> processor.process(TENANT_ID, ACCOUNT_ID, INBOX_ID, WORKER));

        verify(projectionService, never()).projectRewardedEstimate(any());
        verify(inboxMapper, never()).markSucceededCas(anyLong(), anyLong(), anyLong(), anyString());
    }

    @Test
    void unsignedUnmatchedOrUnsupportedImpressionCreatesNoRevenueOrEntitlement() {
        String query = impressionQuery("2", "USD");
        decryptedPayload.set(query.getBytes(StandardCharsets.US_ASCII));
        inbox = impressionInbox(query).setAdSessionId(null);

        SkitAdCallbackProcessor.ProcessResult result =
                processor.process(TENANT_ID, ACCOUNT_ID, INBOX_ID, WORKER);

        assertEquals(SkitAdCallbackProcessor.Outcome.REJECTED, result.getOutcome());
        assertEquals("IMPRESSION_SESSION_UNMATCHED", result.getErrorCode());
        verify(revenueMapper, never()).insert(any(SkitAdRevenueEventDO.class));
        verify(entitlementMapper, never()).insertGrantedIfAbsent(any());
    }

    @Test
    void impressionAfterVerifyTimeoutFreezesAgentOnlyProjectionWithoutEntitlement() {
        String query = impressionQuery("3.25", "USD");
        decryptedPayload.set(query.getBytes(StandardCharsets.US_ASCII));
        inbox = impressionInbox(query);
        session.setRewardVerificationStatus("VERIFY_TIMEOUT");
        session.setRevenueStatus("NONE");
        session.setActiveScopeHash(null).setActiveScopeReleasedAt(RECEIVED_AT.plusMinutes(21))
                .setActiveScopeReleaseReason("VERIFY_TIMEOUT");
        when(capabilityMapper.selectForShare(TENANT_ID, ACCOUNT_ID, 66))
                .thenReturn(impressionCapability());
        AtomicReference<SkitAdRevenueEventDO> inserted = new AtomicReference<>();
        doAnswer(invocation -> {
            SkitAdRevenueEventDO row = invocation.getArgument(0);
            row.setId(702L);
            inserted.set(row);
            return 1;
        }).when(revenueMapper).insert(any(SkitAdRevenueEventDO.class));

        SkitAdCallbackProcessor.ProcessResult result =
                processor.process(TENANT_ID, ACCOUNT_ID, INBOX_ID, WORKER);

        assertEquals(SkitAdCallbackProcessor.Outcome.SUCCEEDED, result.getOutcome());
        assertEquals("NON_REWARDED", inserted.get().getRewardQualificationStatus());
        assertEquals("FROZEN", inserted.get().getReconciliationStatus());
        verify(sessionMapper).updateRevenueStateCas(TENANT_ID, SESSION_ID, ACCOUNT_ID, 9,
                "NONE", "FROZEN", PROCESSING_AT);
        verify(projectionService).projectNonRewardedEstimate(inserted.get());
        verify(projectionService, never()).projectRewardedEstimate(any());
        verify(entitlementMapper, never()).insertGrantedIfAbsent(any());
        verify(grantMapper, never()).insert(any());
    }

    @Test
    void impressionAfterIntegrityRejectionStaysSuspenseAndCannotProjectMoney() {
        String query = impressionQuery("3.25", "USD");
        decryptedPayload.set(query.getBytes(StandardCharsets.US_ASCII));
        inbox = impressionInbox(query);
        session.setRewardVerificationStatus("REJECTED");
        session.setRevenueStatus("NONE");
        session.setActiveScopeHash(null).setActiveScopeReleasedAt(RECEIVED_AT.plusMinutes(1))
                .setActiveScopeReleaseReason("REWARD_REJECTED");
        when(capabilityMapper.selectForShare(TENANT_ID, ACCOUNT_ID, 66))
                .thenReturn(impressionCapability());
        AtomicReference<SkitAdRevenueEventDO> inserted = new AtomicReference<>();
        doAnswer(invocation -> {
            SkitAdRevenueEventDO row = invocation.getArgument(0);
            row.setId(703L);
            inserted.set(row);
            return 1;
        }).when(revenueMapper).insert(any(SkitAdRevenueEventDO.class));

        SkitAdCallbackProcessor.ProcessResult result =
                processor.process(TENANT_ID, ACCOUNT_ID, INBOX_ID, WORKER);

        assertEquals(SkitAdCallbackProcessor.Outcome.SUCCEEDED, result.getOutcome());
        assertEquals("NON_REWARDED", inserted.get().getRewardQualificationStatus());
        assertEquals("SUSPENSE", inserted.get().getReconciliationStatus());
        verify(sessionMapper).updateRevenueStateCas(TENANT_ID, SESSION_ID, ACCOUNT_ID, 9,
                "NONE", "SUSPENSE", PROCESSING_AT);
        verify(projectionService, never()).projectRewardedEstimate(any());
        verify(projectionService, never()).projectNonRewardedEstimate(any());
        verify(entitlementMapper, never()).insertGrantedIfAbsent(any());
        verify(grantMapper, never()).insert(any());
    }

    @Test
    void impressionAfterSecurityRevocationPreservesSignedFactButCannotProjectMoney() {
        String query = impressionQuery("3.25", "USD");
        decryptedPayload.set(query.getBytes(StandardCharsets.US_ASCII));
        inbox = impressionInbox(query);
        session.setRewardVerificationStatus("SIGNED_VERIFIED");
        session.setEntitlementStatus("SECURITY_REVOKED");
        session.setRevenueStatus("NONE");
        session.setProviderTransactionId(TRANSACTION).setNetworkFirmId(66).setAdsourceId(ADSOURCE);
        session.setActiveScopeHash(null).setActiveScopeReleasedAt(RECEIVED_AT.plusMinutes(1))
                .setActiveScopeReleaseReason("ENTITLEMENT_GRANTED");
        when(capabilityMapper.selectForShare(TENANT_ID, ACCOUNT_ID, 66))
                .thenReturn(impressionCapability());
        AtomicReference<SkitAdRevenueEventDO> inserted = new AtomicReference<>();
        doAnswer(invocation -> {
            SkitAdRevenueEventDO row = invocation.getArgument(0);
            row.setId(704L);
            inserted.set(row);
            return 1;
        }).when(revenueMapper).insert(any(SkitAdRevenueEventDO.class));

        SkitAdCallbackProcessor.ProcessResult result =
                processor.process(TENANT_ID, ACCOUNT_ID, INBOX_ID, WORKER);

        assertEquals(SkitAdCallbackProcessor.Outcome.SUCCEEDED, result.getOutcome());
        assertEquals("REWARDED", inserted.get().getRewardQualificationStatus());
        assertEquals("SUSPENSE", inserted.get().getReconciliationStatus());
        verify(sessionMapper).updateRevenueStateCas(TENANT_ID, SESSION_ID, ACCOUNT_ID, 9,
                "NONE", "SUSPENSE", PROCESSING_AT);
        verify(projectionService, never()).projectRewardedEstimate(any());
        verify(projectionService, never()).projectNonRewardedEstimate(any());
    }

    @Test
    void existingSecurityRevokedImpressionConvergesWithoutDuplicateOrProjection() {
        String query = impressionQuery("3.25", "USD");
        decryptedPayload.set(query.getBytes(StandardCharsets.US_ASCII));
        inbox = impressionInbox(query);
        session.setRewardVerificationStatus("SIGNED_VERIFIED");
        session.setEntitlementStatus("SECURITY_REVOKED");
        session.setRevenueStatus("NONE");
        session.setProviderTransactionId(TRANSACTION).setNetworkFirmId(66).setAdsourceId(ADSOURCE);
        session.setActiveScopeHash(null).setActiveScopeReleasedAt(RECEIVED_AT.plusMinutes(1))
                .setActiveScopeReleaseReason("ENTITLEMENT_GRANTED");
        when(capabilityMapper.selectForShare(TENANT_ID, ACCOUNT_ID, 66))
                .thenReturn(impressionCapability());
        SkitAdRevenueEventDO existing = matchedExistingEvent(query, "REWARDED", "SUSPENSE",
                325_000L, 325L, 5);
        when(revenueMapper.selectByTenantSessionAndSourceForUpdate(
                TENANT_ID, SESSION_ID, "TAKU_IMPRESSION")).thenReturn(existing);

        SkitAdCallbackProcessor.ProcessResult result =
                processor.process(TENANT_ID, ACCOUNT_ID, INBOX_ID, WORKER);

        assertEquals(SkitAdCallbackProcessor.Outcome.SUCCEEDED, result.getOutcome());
        verify(revenueMapper, never()).insert(any(SkitAdRevenueEventDO.class));
        verify(sessionMapper).updateRevenueStateCas(TENANT_ID, SESSION_ID, ACCOUNT_ID, 9,
                "NONE", "SUSPENSE", PROCESSING_AT);
        verify(projectionService, never()).projectRewardedEstimate(any());
        verify(projectionService, never()).projectNonRewardedEstimate(any());
        verify(entitlementMapper, never()).insertGrantedIfAbsent(any());
        verify(grantMapper, never()).insert(any());
    }

    @Test
    void unsupportedRewardCapabilityRejectsBeforeSessionOrEntitlementMutation() {
        SkitAdNetworkCapabilityDO unsupported = rewardCapability().setNetworkFirmId(520764)
                .setEnabled(false);
        when(capabilityMapper.selectForShare(TENANT_ID, ACCOUNT_ID, 66)).thenReturn(unsupported);

        SkitAdCallbackProcessor.ProcessResult result =
                processor.process(TENANT_ID, ACCOUNT_ID, INBOX_ID, WORKER);

        assertEquals(SkitAdCallbackProcessor.Outcome.REJECTED, result.getOutcome());
        assertEquals("NETWORK_CAPABILITY_REJECTED", result.getErrorCode());
        verify(sessionMapper, never()).markSignedRewardAndGrantCas(anyLong(), anyLong(), anyLong(),
                anyLong(), any(), anyInt(), anyInt(), anyInt(), anyString(), any(), anyInt(),
                anyString(), any());
        verify(entitlementMapper, never()).insertGrantedIfAbsent(any());
        verify(grantMapper, never()).insert(any());
    }

    @Test
    void deselectedRewardIsRejectedBySharedAuthorityPolicyBeforeEntitlementMutation() {
        when(tenantCapabilityMapper.selectByTenantForShare(TENANT_ID))
                .thenReturn(selectedNetworks("[22,46]"));

        SkitAdCallbackProcessor.ProcessResult result =
                processor.process(TENANT_ID, ACCOUNT_ID, INBOX_ID, WORKER);

        assertEquals(SkitAdCallbackProcessor.Outcome.REJECTED, result.getOutcome());
        assertEquals("SIGNED_NETWORK_NOT_SELECTED", result.getErrorCode());
        verify(sessionMapper, never()).markSignedRewardAndGrantCas(anyLong(), anyLong(), anyLong(),
                anyLong(), any(), anyInt(), anyInt(), anyInt(), anyString(), any(), anyInt(),
                anyString(), any());
        verify(entitlementMapper, never()).insertGrantedIfAbsent(any());
        verify(grantMapper, never()).insert(any());
    }

    @Test
    void infrastructureDecryptionFailurePropagatesForRetryWithoutTerminalAck() {
        when(payloadCrypto.decrypt(any(), any())).thenThrow(new IllegalStateException("kms unavailable"));

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> processor.process(TENANT_ID, ACCOUNT_ID, INBOX_ID, WORKER));

        assertEquals("kms unavailable", failure.getMessage());
        verify(inboxMapper, never()).markSucceededCas(anyLong(), anyLong(), anyLong(), anyString());
        verify(inboxMapper, never()).markRejectedCas(anyLong(), anyLong(), anyLong(), anyString(),
                anyString());
        verify(entitlementMapper, never()).insertGrantedIfAbsent(any());
    }

    @Test
    void processorAndMappersEnforceTenantLeaseReceiptAndOrthogonalStateContracts() throws Exception {
        Method process = SkitAdCallbackProcessorImpl.class.getMethod(
                "process", long.class, long.class, long.class, String.class);
        Transactional transactional = process.getAnnotation(Transactional.class);
        assertEquals(Isolation.READ_COMMITTED, transactional.isolation());

        String inboxSuccess = updateSql(SkitAdCallbackInboxMapper.class, "markSucceededCas");
        assertContainsAll(inboxSuccess, "tenant_id`=#{tenantid}", "ad_account_id`=#{adaccountid}",
                "id`=#{id}", "processing_status`='processing'", "lease_owner`=#{leaseowner}",
                "lease_until`>=current_timestamp", "processed_at`=current_timestamp",
                "update_time`=current_timestamp");
        String inboxReject = updateSql(SkitAdCallbackInboxMapper.class, "markRejectedCas");
        assertContainsAll(inboxReject, "tenant_id`=#{tenantid}",
                "ad_account_id`=#{adaccountid}", "id`=#{id}",
                "processing_status`='processing'", "lease_owner`=#{leaseowner}",
                "lease_until`>=current_timestamp", "processed_at`=current_timestamp",
                "update_time`=current_timestamp");
        String activeClaim = selectSql(SkitAdCallbackInboxMapper.class,
                "selectActiveClaimForUpdate");
        assertContainsAll(activeClaim, "tenant_id`=#{tenantid}",
                "ad_account_id`=#{adaccountid}", "id`=#{id}",
                "processing_status`='processing'", "lease_owner`=#{leaseowner}",
                "lease_until`>=current_timestamp", "for update");
        String sessionLock = selectSql(SkitAdSessionMapper.class,
                "selectByTenantAccountAndIdForUpdate");
        assertContainsAll(sessionLock, "tenant_id`=#{tenantid}", "ad_account_id`=#{adaccountid}",
                "id`=#{id}", "for update");
        String reward = updateSql(SkitAdSessionMapper.class, "markSignedRewardAndGrantCas");
        assertContainsAll(reward, "reward_callback_inbox_id`=#{callbackinboxid}",
                "reward_callback_received_at`=#{callbackreceivedat}",
                "reward_accept_until` >= #{callbackreceivedat}",
                "callback_key_version`=#{callbackkeyversion}",
                "reward_secret_version`=#{rewardsecretversion}",
                "reward_verification_status`='signed_verified'", "entitlement_status`='granted'",
                "revenue_status`=case when `revenue_status`='impression_pending_reward'",
                "active_scope_release_reason`='entitlement_granted'");
        assertTrue(!reward.contains("client_lifecycle_status"),
                "server reward must not overwrite independent client lifecycle state");
        String grantLock = selectSql(SkitEntitlementGrantMapper.class,
                "selectBySessionAndEpisodeForUpdate");
        assertContainsAll(grantLock, "tenant_id`=#{tenantid}", "ad_session_id`=#{adsessionid}",
                "episode_no`=#{episodeno}", "for update");
        String revenueLock = selectSql(SkitAdRevenueEventMapper.class,
                "selectByTenantSessionAndSourceForUpdate");
        assertContainsAll(revenueLock, "tenant_id`=#{tenantid}", "ad_session_id`=#{adsessionid}",
                "source_type`=#{sourcetype}", "for update");
        assertTrue(Arrays.stream(SkitAdCallbackProcessorImpl.class.getDeclaredFields())
                .noneMatch(field -> field.getType().getSimpleName().contains("TenantService")
                        || field.getType().getSimpleName().contains("AgentMapper")),
                "pre-archive sessions must not be rejected from current tenant archive state");
    }

    private SkitAdSessionDO rewardSession() {
        SkitAdSessionDO row = new SkitAdSessionDO()
                .setId(SESSION_ID).setSessionId("server-session-public-id")
                .setSessionTokenHash(tokenService.hashCustomData(customData))
                .setSessionTokenKeyVersion(1).setProtocolVersion(1).setMemberId(MEMBER_ID)
                .setAdAccountId(ACCOUNT_ID).setPolicySnapshotId(SNAPSHOT_ID)
                .setCallbackKeyVersion(4).setRewardSecretVersion(7).setProvider("TAKU")
                .setPlacementId(PLACEMENT).setScenarioId("drama_unlock")
                .setBusinessType("EPISODE_UNLOCK").setDramaId(DRAMA_ID)
                .setEpisodeFrom(3).setEpisodeTo(3).setUnlockScope("drama:801:episode:3")
                .setActiveScopeHash(new byte[32]).setPseudonymousUserId(PSEUDONYMOUS_USER)
                .setAccessMode("MEMBER_OAUTH").setClientLifecycleStatus("SHOWN")
                .setRewardVerificationStatus("PENDING").setEntitlementStatus("NONE")
                .setRevenueStatus("NONE").setLoadExpiresAt(RECEIVED_AT.minusMinutes(5))
                .setRewardAcceptUntil(RECEIVED_AT.plusMinutes(20))
                .setRewardCallbackInboxId(INBOX_ID).setRewardCallbackReceivedAt(RECEIVED_AT)
                .setProviderShowId(SIGNED_SHOW).setLastCallbackSequence(3).setVersion(9);
        row.setTenantId(TENANT_ID);
        return row;
    }

    private SkitAdCallbackInboxDO rewardInbox(String query) {
        TakuRewardCallback parsed = new TakuCallbackCanonicalizer().canonicalizeReward(query);
        SkitAdCallbackInboxDO row = baseInbox("REWARD", parsed.getTransactionId(),
                parsed.getCanonicalPayloadHash())
                .setAdSessionId(SESSION_ID).setCallbackKeyVersion(4).setRewardSecretVersion(7)
                .setProviderUserId(PSEUDONYMOUS_USER)
                .setExtraDataHash(tokenService.hashCustomData(customData))
                .setProviderTransactionId(TRANSACTION).setProviderShowId(SIGNED_SHOW)
                .setPlacementId(PLACEMENT).setAdsourceId(ADSOURCE).setNetworkFirmId(66)
                .setSignedFieldMask(63L).setEvidenceProvenance("SIGNED_ILRD")
                .setAuthenticationLevel("SIGNED_REWARD").setSignatureStatus("VALID");
        return row;
    }

    private SkitAdCallbackInboxDO impressionInbox(String query) {
        TakuImpressionCallback parsed = new TakuCallbackCanonicalizer().canonicalizeImpression(query);
        return baseInbox("IMPRESSION", parsed.getRequestId().length() + ":" + parsed.getRequestId()
                        + ":" + parsed.getAdsourceId(), parsed.getCanonicalPayloadHash())
                .setAdSessionId(SESSION_ID).setCallbackKeyVersion(4).setProviderUserId(PSEUDONYMOUS_USER)
                .setProviderRequestId(parsed.getRequestId()).setPlacementId(PLACEMENT)
                .setAdsourceId(ADSOURCE).setNetworkFirmId(66).setSourceCurrency(parsed.getCurrency())
                .setSignedFieldMask(0L).setEvidenceProvenance("MATCHED_SESSION")
                .setAuthenticationLevel("UNSIGNED_PROVIDER_OBSERVATION")
                .setSignatureStatus("NOT_APPLICABLE");
    }

    private SkitAdCallbackInboxDO impressionAuthorityInbox(long id, int networkFirmId,
                                                            String adsourceId) {
        SkitAdCallbackInboxDO row = new SkitAdCallbackInboxDO().setId(id)
                .setAdAccountId(ACCOUNT_ID).setAdSessionId(SESSION_ID).setProvider("TAKU")
                .setCallbackType("IMPRESSION").setPlacementId(PLACEMENT)
                .setAdsourceId(adsourceId).setNetworkFirmId(networkFirmId)
                .setDeliveryIntegrityStatus("CANONICAL").setProcessingStatus("SUCCEEDED");
        row.setTenantId(TENANT_ID);
        row.setDeleted(false);
        return row;
    }

    private SkitAdCallbackInboxDO baseInbox(String type, String idempotencyKey, byte[] hash) {
        SkitAdCallbackInboxDO row = new SkitAdCallbackInboxDO()
                .setId(INBOX_ID).setAdAccountId(ACCOUNT_ID).setProvider("TAKU")
                .setCallbackType(type).setIdempotencyKey(idempotencyKey)
                .setCanonicalPayloadHash(hash).setDeliveryIntegrityStatus("CANONICAL")
                .setProcessingStatus("PROCESSING").setPayloadCiphertext(new byte[]{1, 2, 3})
                .setPayloadNonce(new byte[12]).setPayloadKeyId("primary")
                .setPayloadEnvelopeVersion(1).setPayloadExpiresAt(RECEIVED_AT.plusDays(90))
                .setLeaseOwner(WORKER).setLeaseUntil(PROCESSING_AT.plusMinutes(1))
                .setProcessingAttemptCount(1).setReceivedAt(RECEIVED_AT).setIngressResponseCode(200);
        row.setTenantId(TENANT_ID);
        return row;
    }

    private SkitAdNetworkCapabilityDO rewardCapability() {
        SkitAdNetworkCapabilityDO row = new SkitAdNetworkCapabilityDO().setId(1L)
                .setAdAccountId(ACCOUNT_ID).setNetworkFirmId(66).setRewardAuthority("SIGNED_REWARD")
                .setSupportsUserId(true).setSupportsCustomData(true)
                .setSupportsStableTransaction(true).setSupportsImpressionRevenue(true)
                .setSupportsReporting(true).setEnabled(true).setVerifiedAt(RECEIVED_AT.minusDays(1));
        row.setTenantId(TENANT_ID);
        return row;
    }

    private SkitAdNetworkCapabilityDO impressionCapability() {
        return rewardCapability();
    }

    private SkitTenantAdCapabilityDO selectedNetworks(String networkIdsJson) {
        SkitTenantAdCapabilityDO row = new SkitTenantAdCapabilityDO().setId(81L)
                .setAdAccountId(ACCOUNT_ID).setRolloutState("SHADOW_TEST_USERS")
                .setDedicatedUnlockPlacementId(PLACEMENT)
                .setUnlockNetworkFirmIdsJson(networkIdsJson).setReadinessVersion(3);
        row.setTenantId(TENANT_ID);
        return row;
    }

    private SkitAdRevenueEventDO pendingEstimate() {
        SkitAdRevenueEventDO row = new SkitAdRevenueEventDO().setId(700L).setAdAccountId(ACCOUNT_ID)
                .setAdSessionId(SESSION_ID).setCallbackInboxId(IMPRESSION_INBOX_ID)
                .setPolicySnapshotId(SNAPSHOT_ID)
                .setSourceMemberId(MEMBER_ID).setProvider("TAKU").setPlacementId(PLACEMENT)
                .setSourceType("TAKU_IMPRESSION").setRewardQualificationStatus("PENDING_REWARD")
                .setAdsourceId(ADSOURCE).setReconciliationStatus("FROZEN").setRuleVersion(5)
                .setLegacyUnverified(false).setVersion(2);
        row.setTenantId(TENANT_ID);
        return row;
    }

    private SkitAdRevenueEventDO matchedExistingEvent(String query, String qualification,
                                                      String reconciliation, long sourceUnits,
                                                      long estimatedUnits, int scale) {
        TakuImpressionCallback callback = new TakuCallbackCanonicalizer().canonicalizeImpression(query);
        SkitAdRevenueEventDO row = new SkitAdRevenueEventDO().setId(705L)
                .setAdAccountId(ACCOUNT_ID).setAdSessionId(SESSION_ID).setCallbackInboxId(INBOX_ID)
                .setPolicySnapshotId(SNAPSHOT_ID).setSourceMemberId(MEMBER_ID).setProvider("TAKU")
                .setPlacementId(PLACEMENT).setExternalEventId(inbox.getIdempotencyKey())
                .setSourceType("TAKU_IMPRESSION").setProviderTransactionId(TRANSACTION)
                .setProviderShowId(session.getProviderShowId()).setAdsourceId(ADSOURCE)
                .setSourceAmountUnits(sourceUnits).setEstimatedAmountUnits(estimatedUnits)
                .setReconciledAmountUnits(0L).setAmountScale(scale).setSourceCurrency("USD")
                .setMatchStatus("MATCHED").setSourceVerificationStatus("UNSIGNED_OBSERVATION")
                .setRewardQualificationStatus(qualification).setReconciliationStatus(reconciliation)
                .setPayloadHash(callback.getCanonicalPayloadHash()).setRuleVersion(5)
                .setLegacyUnverified(false).setVersion(1);
        row.setTenantId(TENANT_ID);
        return row;
    }

    private SkitContentEntitlementDO entitlement(int episode, String status, long id) {
        SkitContentEntitlementDO row = new SkitContentEntitlementDO().setId(id).setMemberId(MEMBER_ID)
                .setDramaId(DRAMA_ID).setEpisodeNo(episode).setStatus(status)
                .setGrantedAt(PROCESSING_AT).setVersion(0);
        row.setTenantId(TENANT_ID);
        return row;
    }

    private String signedRewardQuery(String extraData, String transactionId, String signedShowId) {
        return signedRewardQuery(extraData, transactionId, signedShowId, null);
    }

    private String signedRewardQuery(String extraData, String transactionId, String signedShowId,
                                     String signedShowCustomExt) {
        String ilrd = "{\"network_firm_id\":66,\"adsource_id\":\"" + ADSOURCE + "\""
                + (signedShowId == null ? "" : ",\"id\":\"" + signedShowId + "\"")
                + ",\"adunit_id\":\"" + PLACEMENT + "\""
                + (signedShowCustomExt == null ? "" : ",\"show_custom_ext\":\""
                + signedShowCustomExt + "\"") + "}";
        String signing = "trans_id=" + transactionId + "&placement_id=" + PLACEMENT
                + "&adsource_id=" + ADSOURCE + "&reward_amount=1&reward_name=coin&sec_key="
                + new String(REWARD_SECRET, StandardCharsets.US_ASCII) + "&ilrd=" + ilrd;
        return "user_id=" + PSEUDONYMOUS_USER + "&trans_id=" + transactionId
                + "&placement_id=" + PLACEMENT + "&adsource_id=" + ADSOURCE
                + "&reward_amount=1&reward_name=coin&extra_data=" + encode(extraData)
                + "&network_firm_id=66&sign=" + md5(signing) + "&ilrd=" + encode(ilrd);
    }

    private String impressionQuery(String price, String currency) {
        return "user_id=" + PSEUDONYMOUS_USER + "&req_id=impression-request-1"
                + "&package_name=com.example.skit&adformat=1&placement_id=" + PLACEMENT
                + "&nw_firm_id=66&adsource_id=" + ADSOURCE + "&adsource_price=" + price
                + "&currency=" + currency + "&timestamp=1784042400000"
                + "&show_custom_ext=server-session-public-id";
    }

    private static String encode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8").replace("+", "%20");
        } catch (Exception ex) {
            throw new AssertionError(ex);
        }
    }

    private static String md5(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("MD5")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(32);
            for (byte item : digest) {
                result.append(String.format("%02x", item & 0xff));
            }
            return result.toString();
        } catch (Exception ex) {
            throw new AssertionError(ex);
        }
    }

    private static String updateSql(Class<?> mapperType, String methodName) {
        return Arrays.stream(mapperType.getDeclaredMethods())
                .filter(method -> method.getName().equals(methodName)).findFirst()
                .map(method -> method.getAnnotation(Update.class))
                .map(annotation -> String.join(" ", annotation.value()).toLowerCase())
                .orElseThrow(AssertionError::new);
    }

    private static String selectSql(Class<?> mapperType, String methodName) {
        return Arrays.stream(mapperType.getDeclaredMethods())
                .filter(method -> method.getName().equals(methodName)).findFirst()
                .map(method -> method.getAnnotation(Select.class))
                .map(annotation -> String.join(" ", annotation.value()).toLowerCase())
                .orElseThrow(AssertionError::new);
    }

    private static void assertContainsAll(String value, String... fragments) {
        for (String fragment : fragments) {
            assertTrue(value.contains(fragment), "missing SQL fragment: " + fragment + " in " + value);
        }
    }
}
