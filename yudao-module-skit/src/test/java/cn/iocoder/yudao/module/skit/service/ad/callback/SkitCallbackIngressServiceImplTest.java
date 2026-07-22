package cn.iocoder.yudao.module.skit.service.ad.callback;

import cn.iocoder.yudao.module.skit.dal.dataobject.ad.SkitAdCallbackAttemptDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.ad.SkitAdCallbackEdgeAttemptDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.ad.SkitAdCallbackInboxDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.ad.SkitAdNetworkCapabilityDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.ad.SkitAdSessionDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.ad.SkitTenantAdCapabilityDO;
import cn.iocoder.yudao.module.skit.dal.mysql.ad.SkitAdCallbackAttemptMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.ad.SkitAdCallbackEdgeAttemptMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.ad.SkitAdCallbackInboxMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.ad.SkitAdNetworkCapabilityMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.ad.SkitAdSessionMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.ad.SkitTenantAdCapabilityMapper;
import cn.iocoder.yudao.module.skit.framework.crypto.SkitCallbackPayloadCryptoService;
import cn.iocoder.yudao.module.skit.service.ad.SkitAdCredentialVersionService;
import cn.iocoder.yudao.module.skit.service.ad.SkitAdSessionTokenService;
import cn.iocoder.yudao.module.skit.service.ad.SkitHmacAdSessionTokenService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

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
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SkitCallbackIngressServiceImplTest {

    private static final long TENANT_ID = 17L;
    private static final long ACCOUNT_ID = 29L;
    private static final long SESSION_ROW_ID = 55L;
    private static final long INBOX_ID = 91L;
    private static final String CALLBACK_KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
    private static final String PLACEMENT_ID = "reward-placement";
    private static final String SHOW_ID = "show-123";
    private static final String SESSION_PUBLIC_ID = "0123456789abcdefghijkl";
    private static final String PSEUDONYMOUS_USER = "m_member_42";
    private static final byte[] REWARD_SECRET = "taku-reward-secret-32-bytes-value"
            .getBytes(StandardCharsets.US_ASCII);
    private static final LocalDateTime RECEIVED_AT =
            LocalDateTime.of(2026, 7, 14, 23, 20, 0);

    private SkitCallbackRoutingService routingService;
    private SkitAdCredentialVersionService credentialService;
    private SkitAdSessionTokenService tokenService;
    private SkitAdSessionMapper sessionMapper;
    private SkitAdCallbackInboxMapper inboxMapper;
    private SkitAdCallbackAttemptMapper attemptMapper;
    private SkitAdCallbackEdgeAttemptMapper edgeAttemptMapper;
    private SkitAdNetworkCapabilityMapper networkCapabilityMapper;
    private SkitTenantAdCapabilityMapper tenantCapabilityMapper;
    private SkitCallbackPayloadCryptoService payloadCryptoService;
    private SkitCallbackRateLimiter rateLimiter;
    private SkitCallbackIngressServiceImpl service;
    private SkitAdSessionDO session;
    private String customData;
    private AtomicReference<SkitAdCallbackInboxDO> insertedInbox;
    private AtomicReference<byte[]> encryptedPlaintext;

    @BeforeEach
    void setUp() {
        routingService = mock(SkitCallbackRoutingService.class);
        credentialService = mock(SkitAdCredentialVersionService.class);
        byte[] tokenKey = "0123456789abcdef0123456789abcdef"
                .getBytes(StandardCharsets.US_ASCII);
        tokenService = new SkitHmacAdSessionTokenService(1, Collections.singletonMap(1, tokenKey));
        customData = tokenService.issue("session-for-callback").consumeCustomData();
        sessionMapper = mock(SkitAdSessionMapper.class);
        inboxMapper = mock(SkitAdCallbackInboxMapper.class);
        attemptMapper = mock(SkitAdCallbackAttemptMapper.class);
        edgeAttemptMapper = mock(SkitAdCallbackEdgeAttemptMapper.class);
        networkCapabilityMapper = mock(SkitAdNetworkCapabilityMapper.class);
        tenantCapabilityMapper = mock(SkitTenantAdCapabilityMapper.class);
        payloadCryptoService = mock(SkitCallbackPayloadCryptoService.class);
        rateLimiter = mock(SkitCallbackRateLimiter.class);
        Clock clock = Clock.fixed(Instant.parse("2026-07-14T15:20:00Z"),
                ZoneId.of("Asia/Shanghai"));
        service = new SkitCallbackIngressServiceImpl(routingService, new TakuCallbackCanonicalizer(),
                new TakuRewardSignatureVerifier(new ObjectMapper()), credentialService, tokenService,
                sessionMapper, inboxMapper, attemptMapper, edgeAttemptMapper,
                new SkitRewardAuthorityPolicy(tenantCapabilityMapper, networkCapabilityMapper),
                payloadCryptoService, rateLimiter, clock);

        when(routingService.resolve(CALLBACK_KEY, RECEIVED_AT)).thenReturn(
                new SkitCallbackRoutingService.CallbackRoute(
                        TENANT_ID, ACCOUNT_ID, 4, true, null));
        session = session();
        when(sessionMapper.selectByTokenHashForUpdate(eq(TENANT_ID), eq(ACCOUNT_ID), any(byte[].class)))
                .thenReturn(session);
        when(sessionMapper.selectByAccountAndSessionIdForUpdate(
                TENANT_ID, ACCOUNT_ID, SESSION_PUBLIC_ID)).thenReturn(session);
        when(networkCapabilityMapper.selectForShare(TENANT_ID, ACCOUNT_ID, 66))
                .thenReturn(validCapability());
        when(tenantCapabilityMapper.selectByTenantForShare(TENANT_ID))
                .thenReturn(selectedNetworks("[66]"));
        when(credentialService.resolveRewardSecret(eq(TENANT_ID), eq(ACCOUNT_ID), eq(7),
                eq(session.getRewardAcceptUntil()), eq(RECEIVED_AT))).thenAnswer(invocation ->
                new SkitAdCredentialVersionService.ResolvedRewardSecret(
                        TENANT_ID, ACCOUNT_ID, 7, true, null, REWARD_SECRET));
        encryptedPlaintext = new AtomicReference<>();
        when(payloadCryptoService.encrypt(any(), any(byte[].class))).thenAnswer(invocation -> {
            encryptedPlaintext.set(((byte[]) invocation.getArgument(1)).clone());
            return new SkitCallbackPayloadCryptoService.PayloadEnvelope(
                    new byte[]{1, 2, 3}, new byte[12], "primary", 1);
        });
        insertedInbox = new AtomicReference<>();
        doAnswer(invocation -> {
            SkitAdCallbackInboxDO row = invocation.getArgument(0);
            row.setId(INBOX_ID);
            insertedInbox.set(row);
            return 1;
        }).when(inboxMapper).insertOrGetCanonical(any(SkitAdCallbackInboxDO.class));
        when(inboxMapper.selectByTenantAccountAndIdForUpdate(TENANT_ID, ACCOUNT_ID, INBOX_ID))
                .thenAnswer(invocation -> insertedInbox.get());
        when(attemptMapper.selectMaxAttemptNo(TENANT_ID, INBOX_ID)).thenReturn(0);
        when(attemptMapper.insert(any(SkitAdCallbackAttemptDO.class))).thenReturn(1);
        when(edgeAttemptMapper.insert(any(SkitAdCallbackEdgeAttemptDO.class))).thenReturn(1);
        when(sessionMapper.markRewardCallbackReceivedCas(
                TENANT_ID, SESSION_ROW_ID, ACCOUNT_ID, INBOX_ID, RECEIVED_AT)).thenReturn(1);
    }

    @Test
    void validSignedRewardPersistsCanonicalInboxAttemptAndReceiptInOneTenant() {
        String rawQuery = signedRewardQuery(customData, REWARD_SECRET);

        SkitCallbackIngressService.IngressResponse result =
                service.receiveReward(CALLBACK_KEY, rawQuery, "203.0.113.8");

        assertEquals(SkitCallbackIngressService.IngressResponse.OK, result);
        SkitAdCallbackInboxDO row = insertedInbox.get();
        assertNotNull(row);
        assertEquals(TENANT_ID, row.getTenantId());
        assertEquals(ACCOUNT_ID, row.getAdAccountId());
        assertEquals(SESSION_ROW_ID, row.getAdSessionId());
        assertEquals(SHOW_ID, row.getProviderTransactionId());
        assertEquals(Integer.valueOf(4), row.getCallbackKeyVersion());
        assertEquals(Integer.valueOf(7), row.getRewardSecretVersion());
        assertEquals("SIGNED_REWARD", row.getAuthenticationLevel());
        assertEquals("PENDING", row.getProcessingStatus());
        assertEquals(Integer.valueOf(200), row.getIngressResponseCode());
        verify(sessionMapper).markRewardCallbackReceivedCas(
                TENANT_ID, SESSION_ROW_ID, ACCOUNT_ID, INBOX_ID, RECEIVED_AT);
        verify(attemptMapper).insert(any(SkitAdCallbackAttemptDO.class));
        assertArrayEquals(rawQuery.getBytes(StandardCharsets.US_ASCII), encryptedPlaintext.get());
        verify(edgeAttemptMapper, never()).insert(any());
    }

    @Test
    void signedIlrdShowIdMayDifferFromRewardTransactionAndIsStoredSeparately() {
        String transactionId = "reward-transaction-789";
        String signedShowId = "sdk-show-456";
        session.setProviderShowId(signedShowId);

        SkitCallbackIngressService.IngressResponse result = service.receiveReward(
                CALLBACK_KEY,
                signedRewardQuery(customData, REWARD_SECRET, transactionId, signedShowId),
                "203.0.113.8");

        assertEquals(SkitCallbackIngressService.IngressResponse.OK, result);
        assertEquals(transactionId, insertedInbox.get().getProviderTransactionId());
        assertEquals(signedShowId, insertedInbox.get().getProviderShowId());
        verify(sessionMapper).markRewardCallbackReceivedCas(
                TENANT_ID, SESSION_ROW_ID, ACCOUNT_ID, INBOX_ID, RECEIVED_AT);
    }

    @Test
    void signedShowCustomExtRecoversCurrentSessionWhenSdkReplaysOlderValidCustomData() {
        String cachedCustomData = tokenService.issue("cached-session-from-prior-ad").consumeCustomData();
        SkitAdSessionDO staleSession = session().setId(SESSION_ROW_ID + 1L)
                .setSessionId("zyxwvutsrqponmlkjihgfe")
                .setSessionTokenHash(tokenService.hashCustomData(cachedCustomData));
        when(sessionMapper.selectByTokenHashForUpdate(eq(TENANT_ID), eq(ACCOUNT_ID), any(byte[].class)))
                .thenReturn(staleSession);
        when(sessionMapper.selectByAccountAndSessionIdForUpdate(
                TENANT_ID, ACCOUNT_ID, SESSION_PUBLIC_ID)).thenReturn(session);

        SkitCallbackIngressService.IngressResponse result = service.receiveReward(
                CALLBACK_KEY,
                signedRewardQuery(cachedCustomData, REWARD_SECRET, SHOW_ID, SHOW_ID,
                        SESSION_PUBLIC_ID),
                "203.0.113.8");

        assertEquals(SkitCallbackIngressService.IngressResponse.OK, result);
        assertEquals(SESSION_ROW_ID, insertedInbox.get().getAdSessionId());
        verify(sessionMapper).selectByAccountAndSessionIdForUpdate(
                TENANT_ID, ACCOUNT_ID, SESSION_PUBLIC_ID);
        verify(sessionMapper, never()).selectByTokenHashForUpdate(
                eq(TENANT_ID), eq(ACCOUNT_ID), any(byte[].class));
        verify(sessionMapper).markRewardCallbackReceivedCas(
                TENANT_ID, SESSION_ROW_ID, ACCOUNT_ID, INBOX_ID, RECEIVED_AT);
    }

    @Test
    void missingSignedShowCustomExtRejectsEvenWhenExtraDataMatchesSession() {
        SkitCallbackIngressService.IngressResponse result = service.receiveReward(
                CALLBACK_KEY,
                signedRewardQuery(customData, REWARD_SECRET, SHOW_ID, SHOW_ID, null),
                "203.0.113.8");

        assertEquals(SkitCallbackIngressService.IngressResponse.REJECTED, result);
        verify(inboxMapper, never()).insertOrGetCanonical(any());
        verify(sessionMapper, never()).markRewardCallbackReceivedCas(
                anyLong(), anyLong(), anyLong(), anyLong(), any());
    }

    @Test
    void missingSignedShowIdRejectsEvenWhenSessionHasProviderShow() {
        session.setProviderShowId(SHOW_ID);
        when(sessionMapper.selectByAccountAndSessionIdForUpdate(
                TENANT_ID, ACCOUNT_ID, SESSION_PUBLIC_ID)).thenReturn(session);

        SkitCallbackIngressService.IngressResponse result = service.receiveReward(
                CALLBACK_KEY,
                signedRewardQuery(customData, REWARD_SECRET, SHOW_ID, null, SESSION_PUBLIC_ID),
                "203.0.113.8");

        assertEquals(SkitCallbackIngressService.IngressResponse.REJECTED, result);
        verify(inboxMapper, never()).insertOrGetCanonical(any());
        verify(sessionMapper, never()).markRewardCallbackReceivedCas(
                anyLong(), anyLong(), anyLong(), anyLong(), any());
    }

    @Test
    void missingSessionProviderShowRejectsEvenWhenSignedIlrdHasExactShow() {
        session.setProviderShowId(null);
        when(sessionMapper.selectByAccountAndSessionIdForUpdate(
                TENANT_ID, ACCOUNT_ID, SESSION_PUBLIC_ID)).thenReturn(session);

        SkitCallbackIngressService.IngressResponse result = service.receiveReward(
                CALLBACK_KEY,
                signedRewardQuery(customData, REWARD_SECRET, SHOW_ID, SHOW_ID,
                        SESSION_PUBLIC_ID),
                "203.0.113.8");

        assertEquals(SkitCallbackIngressService.IngressResponse.REJECTED, result);
        verify(inboxMapper, never()).insertOrGetCanonical(any());
        verify(sessionMapper, never()).markRewardCallbackReceivedCas(
                anyLong(), anyLong(), anyLong(), anyLong(), any());
    }

    @Test
    void badDigestReturns601AndCanNeverCreateDurableBusinessFacts() {
        String rawQuery = signedRewardQuery(customData,
                "different-secret-value-32-bytes".getBytes(StandardCharsets.US_ASCII));

        SkitCallbackIngressService.IngressResponse result =
                service.receiveReward(CALLBACK_KEY, rawQuery, "203.0.113.8");

        assertEquals(SkitCallbackIngressService.IngressResponse.INVALID_SIGNATURE, result);
        verify(edgeAttemptMapper).insert(any(SkitAdCallbackEdgeAttemptDO.class));
        verify(inboxMapper, never()).insertOrGetCanonical(any());
        verify(sessionMapper, never()).markRewardCallbackReceivedCas(
                anyLong(), anyLong(), anyLong(), anyLong(), any());
    }

    @Test
    void missingNetworkCapabilityReturns602WithoutInboxOrReceipt() {
        when(networkCapabilityMapper.selectForShare(TENANT_ID, ACCOUNT_ID, 66)).thenReturn(null);

        assertCapabilityRejected();
    }

    @Test
    void disabledNetworkCapabilityReturns602WithoutInboxOrReceipt() {
        when(networkCapabilityMapper.selectForShare(TENANT_ID, ACCOUNT_ID, 66))
                .thenReturn(validCapability().setEnabled(false));

        assertCapabilityRejected();
    }

    @Test
    void unverifiedNetworkCapabilityReturns602WithoutInboxOrReceipt() {
        when(networkCapabilityMapper.selectForShare(TENANT_ID, ACCOUNT_ID, 66))
                .thenReturn(validCapability().setVerifiedAt(null));

        assertCapabilityRejected();
    }

    @Test
    void wrongRewardAuthorityReturns602WithoutInboxOrReceipt() {
        when(networkCapabilityMapper.selectForShare(TENANT_ID, ACCOUNT_ID, 66))
                .thenReturn(validCapability().setRewardAuthority("UNSIGNED_PROVIDER_OBSERVATION"));

        assertCapabilityRejected();
    }

    @Test
    void everyRequiredIdentityCapabilityMustBeEnabled() {
        SkitAdNetworkCapabilityDO[] invalidCapabilities = {
                validCapability().setSupportsUserId(false),
                validCapability().setSupportsCustomData(false),
                validCapability().setSupportsStableTransaction(false)
        };
        for (SkitAdNetworkCapabilityDO invalid : invalidCapabilities) {
            when(networkCapabilityMapper.selectForShare(TENANT_ID, ACCOUNT_ID, 66))
                    .thenReturn(invalid);
            assertEquals(SkitCallbackIngressService.IngressResponse.REJECTED,
                    service.receiveReward(CALLBACK_KEY,
                            signedRewardQuery(customData, REWARD_SECRET), "203.0.113.8"));
        }

        verify(inboxMapper, never()).insertOrGetCanonical(any());
        verify(sessionMapper, never()).markRewardCallbackReceivedCas(
                anyLong(), anyLong(), anyLong(), anyLong(), any());
    }

    @Test
    void signedSelectedDynamicNetworkIsAcceptedWithoutAFixedRuntimeAllowList() {
        int dynamicNetwork = 46;
        when(tenantCapabilityMapper.selectByTenantForShare(TENANT_ID))
                .thenReturn(selectedNetworks("[46]"));
        when(networkCapabilityMapper.selectForShare(TENANT_ID, ACCOUNT_ID, dynamicNetwork))
                .thenReturn(validCapability().setNetworkFirmId(dynamicNetwork));

        SkitCallbackIngressService.IngressResponse result = service.receiveReward(
                CALLBACK_KEY,
                signedRewardQuery(customData, REWARD_SECRET, SHOW_ID, SHOW_ID, SESSION_PUBLIC_ID,
                        dynamicNetwork, dynamicNetwork),
                "203.0.113.8");

        assertEquals(SkitCallbackIngressService.IngressResponse.OK, result);
        assertEquals(dynamicNetwork, insertedInbox.get().getNetworkFirmId());
        verify(sessionMapper).markRewardCallbackReceivedCas(
                TENANT_ID, SESSION_ROW_ID, ACCOUNT_ID, INBOX_ID, RECEIVED_AT);
    }

    @Test
    void rolloutOffRejectsSignedRewardBeforeInboxOrSessionReceiptMutation() {
        when(tenantCapabilityMapper.selectByTenantForShare(TENANT_ID))
                .thenReturn(selectedNetworks("[66]").setRolloutState("OFF"));

        SkitCallbackIngressService.IngressResponse result = service.receiveReward(
                CALLBACK_KEY, signedRewardQuery(customData, REWARD_SECRET), "203.0.113.8");

        assertEquals(SkitCallbackIngressService.IngressResponse.REJECTED, result);
        verify(inboxMapper, never()).insertOrGetCanonical(any());
        verify(sessionMapper, never()).markRewardCallbackReceivedCas(
                anyLong(), anyLong(), anyLong(), anyLong(), any());
    }

    @Test
    void topLevelSpoofCannotAuthorizeADifferentSignedIlrdNetwork() {
        int signedNetwork = 46;
        when(networkCapabilityMapper.selectForShare(TENANT_ID, ACCOUNT_ID, 66))
                .thenReturn(validCapability());

        SkitCallbackIngressService.IngressResponse result = service.receiveReward(
                CALLBACK_KEY,
                signedRewardQuery(customData, REWARD_SECRET, SHOW_ID, SHOW_ID, null,
                        signedNetwork, 66),
                "203.0.113.8");

        assertEquals(SkitCallbackIngressService.IngressResponse.REJECTED, result);
        verify(inboxMapper, never()).insertOrGetCanonical(any());
        verify(sessionMapper, never()).markRewardCallbackReceivedCas(
                anyLong(), anyLong(), anyLong(), anyLong(), any());
    }

    @Test
    void crossTenantOrCrossAccountCapabilityCannotAuthorizeReward() {
        SkitAdNetworkCapabilityDO wrongTenant = validCapability();
        wrongTenant.setTenantId(TENANT_ID + 1);
        SkitAdNetworkCapabilityDO[] escapedCapabilities = {
                wrongTenant,
                validCapability().setAdAccountId(ACCOUNT_ID + 1)
        };
        for (SkitAdNetworkCapabilityDO escaped : escapedCapabilities) {
            when(networkCapabilityMapper.selectForShare(TENANT_ID, ACCOUNT_ID, 66))
                    .thenReturn(escaped);
            assertEquals(SkitCallbackIngressService.IngressResponse.REJECTED,
                    service.receiveReward(CALLBACK_KEY,
                            signedRewardQuery(customData, REWARD_SECRET), "203.0.113.8"));
        }

        verify(inboxMapper, never()).insertOrGetCanonical(any());
        verify(sessionMapper, never()).markRewardCallbackReceivedCas(
                anyLong(), anyLong(), anyLong(), anyLong(), any());
    }

    @Test
    void oldCallbackKeyCannotAuthorizeSessionCreatedWithAnotherVersion() {
        when(routingService.resolve(CALLBACK_KEY, RECEIVED_AT)).thenReturn(
                new SkitCallbackRoutingService.CallbackRoute(
                        TENANT_ID, ACCOUNT_ID, 3, false, RECEIVED_AT.plusMinutes(1)));

        SkitCallbackIngressService.IngressResponse result = service.receiveReward(
                CALLBACK_KEY, signedRewardQuery(customData, REWARD_SECRET), "203.0.113.8");

        assertEquals(SkitCallbackIngressService.IngressResponse.REJECTED, result);
        verify(credentialService, never()).resolveRewardSecret(anyLong(), anyLong(),
                org.mockito.ArgumentMatchers.anyInt(), any(), any());
        verify(inboxMapper, never()).insertOrGetCanonical(any());
    }

    @Test
    void signedRewardAfterTerminalPreShowFailureIsRejectedBeforeVerificationOrInbox() {
        session.setClientLifecycleStatus("FAILED").setRewardVerificationStatus("REJECTED");

        SkitCallbackIngressService.IngressResponse result = service.receiveReward(
                CALLBACK_KEY, signedRewardQuery(customData, REWARD_SECRET), "203.0.113.8");

        assertEquals(SkitCallbackIngressService.IngressResponse.REJECTED, result);
        verify(credentialService, never()).resolveRewardSecret(anyLong(), anyLong(),
                org.mockito.ArgumentMatchers.anyInt(), any(), any());
        verify(inboxMapper, never()).insertOrGetCanonical(any());
        verify(sessionMapper, never()).markRewardCallbackReceivedCas(
                anyLong(), anyLong(), anyLong(), anyLong(), any());
        ArgumentCaptor<SkitAdCallbackEdgeAttemptDO> edge =
                ArgumentCaptor.forClass(SkitAdCallbackEdgeAttemptDO.class);
        verify(edgeAttemptMapper).insert(edge.capture());
        assertEquals("SESSION_MISMATCH", edge.getValue().getResultCode());
    }

    @Test
    void lateSignedRewardAfterUnrewardedCloseRejectionCannotGrantOrBindReceipt() {
        session.setClientLifecycleStatus("CLOSED").setRewardVerificationStatus("REJECTED")
                .setEntitlementStatus("NONE").setActiveScopeHash(null)
                .setActiveScopeReleasedAt(RECEIVED_AT.minusSeconds(1))
                .setActiveScopeReleaseReason("REWARD_REJECTED")
                .setFailureReason("CLIENT_CLOSED_UNREWARDED");

        SkitCallbackIngressService.IngressResponse result = service.receiveReward(
                CALLBACK_KEY, signedRewardQuery(customData, REWARD_SECRET), "203.0.113.8");

        assertEquals(SkitCallbackIngressService.IngressResponse.REJECTED, result);
        verify(credentialService, never()).resolveRewardSecret(anyLong(), anyLong(),
                org.mockito.ArgumentMatchers.anyInt(), any(), any());
        verify(inboxMapper, never()).insertOrGetCanonical(any());
        verify(sessionMapper, never()).markRewardCallbackReceivedCas(
                anyLong(), anyLong(), anyLong(), anyLong(), any());
    }

    @Test
    void sameIdempotencyKeyWithDifferentPayloadIsFrozenAsConflictAndReturns602() {
        SkitAdCallbackInboxDO canonical = new SkitAdCallbackInboxDO()
                .setId(INBOX_ID).setAdAccountId(ACCOUNT_ID).setAdSessionId(SESSION_ROW_ID)
                .setCallbackKeyVersion(4).setProvider("TAKU").setCallbackType("REWARD")
                .setIdempotencyKey(SHOW_ID)
                .setCanonicalPayloadHash(new byte[32]).setIngressResponseCode(200)
                .setDeliveryIntegrityStatus("CANONICAL").setReceivedAt(RECEIVED_AT.minusSeconds(2));
        canonical.setTenantId(TENANT_ID);
        when(inboxMapper.selectByTenantAccountAndIdForUpdate(TENANT_ID, ACCOUNT_ID, INBOX_ID))
                .thenReturn(canonical);
        when(inboxMapper.markPayloadConflict(TENANT_ID, ACCOUNT_ID, INBOX_ID, RECEIVED_AT))
                .thenReturn(1);

        SkitCallbackIngressService.IngressResponse result = service.receiveReward(
                CALLBACK_KEY, signedRewardQuery(customData, REWARD_SECRET), "203.0.113.8");

        assertEquals(SkitCallbackIngressService.IngressResponse.REJECTED, result);
        verify(inboxMapper).markPayloadConflict(TENANT_ID, ACCOUNT_ID, INBOX_ID, RECEIVED_AT);
        ArgumentCaptor<SkitAdCallbackAttemptDO> attempt =
                ArgumentCaptor.forClass(SkitAdCallbackAttemptDO.class);
        verify(attemptMapper).insert(attempt.capture());
        assertEquals("PAYLOAD_CONFLICT", attempt.getValue().getResultCode());
        verify(sessionMapper, never()).markRewardCallbackReceivedCas(
                anyLong(), anyLong(), anyLong(), anyLong(), any());
    }

    @Test
    void conflictingReplayAfterReceiptIsStillAuditedAndFreezesCanonicalInbox() {
        session.setRewardCallbackInboxId(INBOX_ID)
                .setRewardCallbackReceivedAt(RECEIVED_AT.minusSeconds(2));
        SkitAdCallbackInboxDO canonical = new SkitAdCallbackInboxDO()
                .setId(INBOX_ID).setAdAccountId(ACCOUNT_ID).setAdSessionId(SESSION_ROW_ID)
                .setCallbackKeyVersion(4).setProvider("TAKU").setCallbackType("REWARD")
                .setIdempotencyKey(SHOW_ID).setCanonicalPayloadHash(new byte[32])
                .setIngressResponseCode(200).setDeliveryIntegrityStatus("CANONICAL")
                .setReceivedAt(RECEIVED_AT.minusSeconds(2));
        canonical.setTenantId(TENANT_ID);
        when(inboxMapper.selectByTenantAccountAndIdForUpdate(TENANT_ID, ACCOUNT_ID, INBOX_ID))
                .thenReturn(canonical);
        when(inboxMapper.markPayloadConflict(TENANT_ID, ACCOUNT_ID, INBOX_ID, RECEIVED_AT))
                .thenReturn(1);

        SkitCallbackIngressService.IngressResponse result = service.receiveReward(
                CALLBACK_KEY, signedRewardQuery(customData, REWARD_SECRET), "203.0.113.8");

        assertEquals(SkitCallbackIngressService.IngressResponse.REJECTED, result);
        verify(inboxMapper).markPayloadConflict(TENANT_ID, ACCOUNT_ID, INBOX_ID, RECEIVED_AT);
        ArgumentCaptor<SkitAdCallbackAttemptDO> attempt =
                ArgumentCaptor.forClass(SkitAdCallbackAttemptDO.class);
        verify(attemptMapper).insert(attempt.capture());
        assertEquals("PAYLOAD_CONFLICT", attempt.getValue().getResultCode());
        verify(credentialService, never()).resolveRewardSecret(anyLong(), anyLong(),
                org.mockito.ArgumentMatchers.anyInt(), any(), any());
    }

    @Test
    void unsignedImpressionWithoutSessionCorrelationIsDurableButNeverAuthoritative() {
        String rawQuery = "user_id=u1&req_id=req-1&package_name=com.example.app&adformat=1"
                + "&placement_id=" + PLACEMENT_ID + "&adsource_id=0007"
                + "&adsource_price=1.234567891234&currency=USD&timestamp=1784042400";

        SkitCallbackIngressService.IngressResponse result =
                service.receiveImpression(CALLBACK_KEY, rawQuery, "203.0.113.9");

        assertEquals(SkitCallbackIngressService.IngressResponse.OK, result);
        SkitAdCallbackInboxDO row = insertedInbox.get();
        assertNull(row.getAdSessionId());
        assertEquals("UNSIGNED_PROVIDER_OBSERVATION", row.getAuthenticationLevel());
        assertEquals("UNMATCHED", row.getEvidenceProvenance());
        assertEquals(Integer.valueOf(200), row.getIngressResponseCode());
        verify(sessionMapper, never()).markRewardCallbackReceivedCas(
                anyLong(), anyLong(), anyLong(), anyLong(), any());
    }

    @Test
    void unknownKeyIsDeterministic602WhileInfrastructureFailurePropagates() {
        when(routingService.resolve(CALLBACK_KEY, RECEIVED_AT))
                .thenThrow(new SkitAdCredentialVersionService.CredentialUnavailableException());
        SkitCallbackIngressService.IngressResponse result = service.receiveImpression(
                CALLBACK_KEY, "req_id=irrelevant", "203.0.113.9");
        assertEquals(SkitCallbackIngressService.IngressResponse.REJECTED, result);
        ArgumentCaptor<SkitAdCallbackEdgeAttemptDO> edge =
                ArgumentCaptor.forClass(SkitAdCallbackEdgeAttemptDO.class);
        verify(edgeAttemptMapper).insert(edge.capture());
        assertNull(edge.getValue().getTenantId());
        assertNull(edge.getValue().getAdAccountId());

        doThrow(new IllegalStateException("database unavailable"))
                .when(routingService).resolve(CALLBACK_KEY, RECEIVED_AT);
        assertThrows(IllegalStateException.class, () -> service.receiveImpression(
                CALLBACK_KEY, "req_id=irrelevant", "203.0.113.9"));
    }

    @Test
    void clientIpEvidenceIsKeyedSoDatabaseTheftCannotEnumerateIpv4Addresses() {
        String rotatedCallbackKey = "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB";
        List<byte[]> persistedIpHashes = new ArrayList<>();
        when(edgeAttemptMapper.insert(any(SkitAdCallbackEdgeAttemptDO.class))).thenAnswer(invocation -> {
            SkitAdCallbackEdgeAttemptDO row = invocation.getArgument(0);
            persistedIpHashes.add(row.getClientIpHash().clone());
            return 1;
        });
        when(routingService.resolve(anyString(), eq(RECEIVED_AT)))
                .thenThrow(new SkitAdCredentialVersionService.CredentialUnavailableException());

        service.receiveImpression(CALLBACK_KEY, "req_id=irrelevant", "203.0.113.9");
        service.receiveImpression(rotatedCallbackKey, "req_id=irrelevant", "203.0.113.9");

        verify(edgeAttemptMapper, org.mockito.Mockito.times(2)).insert(any());
        assertFalse(Arrays.equals(persistedIpHashes.get(0), persistedIpHashes.get(1)),
                "the persisted IP digest must be keyed by the callback secret");
    }

    @Test
    void distinctTrustedProxyClientIpsProduceDistinctEvidenceHashesForSameCallbackKey() {
        List<byte[]> persistedIpHashes = new ArrayList<>();
        when(edgeAttemptMapper.insert(any(SkitAdCallbackEdgeAttemptDO.class))).thenAnswer(invocation -> {
            SkitAdCallbackEdgeAttemptDO row = invocation.getArgument(0);
            persistedIpHashes.add(row.getClientIpHash().clone());
            return 1;
        });
        when(routingService.resolve(CALLBACK_KEY, RECEIVED_AT))
                .thenThrow(new SkitAdCredentialVersionService.CredentialUnavailableException());

        service.receiveImpression(CALLBACK_KEY, "req_id=irrelevant", "203.0.113.8");
        service.receiveImpression(CALLBACK_KEY, "req_id=irrelevant", "203.0.113.9");

        assertEquals(2, persistedIpHashes.size());
        assertFalse(Arrays.equals(persistedIpHashes.get(0), persistedIpHashes.get(1)));
    }

    @Test
    void rateLimitIsTransientAndCannotBecomePermanent602OrDatabaseWriteAmplification() {
        doThrow(new SkitCallbackRateLimiter.RateLimitExceededException())
                .when(rateLimiter).check(anyString(), eq("203.0.113.10"), anyString());

        assertThrows(SkitCallbackRateLimiter.RateLimitExceededException.class,
                () -> service.receiveImpression(
                        CALLBACK_KEY, "req_id=irrelevant", "203.0.113.10"));
        assertThrows(SkitCallbackRateLimiter.RateLimitExceededException.class,
                () -> service.receiveReward(
                        CALLBACK_KEY, "irrelevant", "203.0.113.10"));

        verify(routingService, never()).resolve(anyString(), any());
        verify(edgeAttemptMapper, never()).insert(any());
        verify(inboxMapper, never()).insertOrGetCanonical(any());
    }

    private SkitAdSessionDO session() {
        SkitAdSessionDO row = new SkitAdSessionDO()
                .setId(SESSION_ROW_ID).setSessionId(SESSION_PUBLIC_ID)
                .setSessionTokenHash(tokenService.hashCustomData(customData))
                .setSessionTokenKeyVersion(1).setProtocolVersion(1).setMemberId(42L)
                .setAdAccountId(ACCOUNT_ID).setPolicySnapshotId(88L)
                .setCallbackKeyVersion(4).setRewardSecretVersion(7).setProvider("TAKU")
                .setPlacementId(PLACEMENT_ID).setScenarioId("drama_unlock")
                .setBusinessType("EPISODE_UNLOCK").setDramaId(801L)
                .setEpisodeFrom(3).setEpisodeTo(3).setUnlockScope("drama:801:episode:3")
                .setProviderShowId(SHOW_ID)
                .setPseudonymousUserId(PSEUDONYMOUS_USER).setRewardVerificationStatus("PENDING")
                .setRewardAcceptUntil(RECEIVED_AT.plusMinutes(5)).setVersion(0);
        row.setTenantId(TENANT_ID);
        return row;
    }

    private SkitAdNetworkCapabilityDO validCapability() {
        SkitAdNetworkCapabilityDO row = new SkitAdNetworkCapabilityDO()
                .setId(77L).setAdAccountId(ACCOUNT_ID).setNetworkFirmId(66)
                .setRewardAuthority("SIGNED_REWARD").setSupportsUserId(true)
                .setSupportsCustomData(true).setSupportsStableTransaction(true)
                .setSupportsImpressionRevenue(true).setSupportsReporting(true)
                .setEnabled(true).setVerifiedAt(RECEIVED_AT.minusDays(1));
        row.setTenantId(TENANT_ID);
        return row;
    }

    private SkitTenantAdCapabilityDO selectedNetworks(String networkIdsJson) {
        SkitTenantAdCapabilityDO row = new SkitTenantAdCapabilityDO().setId(81L)
                .setAdAccountId(ACCOUNT_ID).setRolloutState("SHADOW_TEST_USERS")
                .setDedicatedUnlockPlacementId(PLACEMENT_ID)
                .setUnlockNetworkFirmIdsJson(networkIdsJson).setReadinessVersion(3);
        row.setTenantId(TENANT_ID);
        return row;
    }

    private void assertCapabilityRejected() {
        SkitCallbackIngressService.IngressResponse result = service.receiveReward(
                CALLBACK_KEY, signedRewardQuery(customData, REWARD_SECRET), "203.0.113.8");

        assertEquals(SkitCallbackIngressService.IngressResponse.REJECTED, result);
        verify(inboxMapper, never()).insertOrGetCanonical(any());
        verify(sessionMapper, never()).markRewardCallbackReceivedCas(
                anyLong(), anyLong(), anyLong(), anyLong(), any());
    }

    private static String signedRewardQuery(String extraData, byte[] signingSecret) {
        return signedRewardQuery(extraData, signingSecret, SHOW_ID, SHOW_ID, SESSION_PUBLIC_ID);
    }

    private static String signedRewardQuery(String extraData, byte[] signingSecret,
                                            String transactionId, String signedShowId) {
        return signedRewardQuery(extraData, signingSecret, transactionId, signedShowId,
                SESSION_PUBLIC_ID);
    }

    private static String signedRewardQuery(String extraData, byte[] signingSecret,
                                            String transactionId, String signedShowId,
                                            String signedShowCustomExt) {
        return signedRewardQuery(extraData, signingSecret, transactionId, signedShowId,
                signedShowCustomExt, 66, 66);
    }

    private static String signedRewardQuery(String extraData, byte[] signingSecret,
                                            String transactionId, String signedShowId,
                                            String signedShowCustomExt, int signedNetworkFirmId,
                                            int topLevelNetworkFirmId) {
        String ilrd = "{\"network_firm_id\":" + signedNetworkFirmId + ",\"adsource_id\":\"7\","
                + (signedShowId == null ? "" : "\"id\":\"" + signedShowId + "\",")
                + "\"adunit_id\":\"" + PLACEMENT_ID + "\""
                + (signedShowCustomExt == null ? "" : ",\"show_custom_ext\":\""
                + signedShowCustomExt + "\"") + "}";
        String preimage = "trans_id=" + transactionId + "&placement_id=" + PLACEMENT_ID
                + "&adsource_id=7&reward_amount=1&reward_name=coin&sec_key="
                + new String(signingSecret, StandardCharsets.US_ASCII) + "&ilrd=" + ilrd;
        String sign = md5Hex(preimage);
        return "user_id=" + encode(PSEUDONYMOUS_USER) + "&trans_id=" + transactionId
                + "&reward_amount=1&reward_name=coin&placement_id=" + PLACEMENT_ID
                + "&extra_data=" + encode(extraData) + "&network_firm_id=" + topLevelNetworkFirmId
                + "&adsource_id=7"
                + "&scenario_id=drama_unlock&sign=" + sign + "&ilrd=" + encode(ilrd);
    }

    private static String md5Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("MD5")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(32);
            for (byte item : digest) {
                result.append(String.format("%02x", item & 0xff));
            }
            return result.toString();
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static String encode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

}
