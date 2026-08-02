package cn.iocoder.yudao.module.skit.service.ad.callback;

import cn.iocoder.yudao.module.skit.dal.dataobject.ad.SkitAdSessionDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.ad.SkitPangleRewardAttestationDO;
import cn.iocoder.yudao.module.skit.dal.mysql.ad.SkitAdSessionMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.ad.SkitPangleRewardAttestationMapper;
import cn.iocoder.yudao.module.skit.service.ad.SkitAdCredentialVersionService;
import cn.iocoder.yudao.module.skit.service.ad.SkitAdSessionTokenService;
import cn.iocoder.yudao.module.skit.service.ad.SkitHmacAdSessionTokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SkitPangleCallbackIngressServiceImplTest {

    private static final long TENANT_ID = 17L;
    private static final long TAKU_ACCOUNT_ID = 29L;
    private static final long PANGLE_ACCOUNT_ID = 31L;
    private static final long SESSION_ROW_ID = 55L;
    private static final String CALLBACK_KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
    private static final String SESSION_PUBLIC_ID = "0123456789abcdefghijkl";
    private static final String USER_ID = "m_member_42";
    private static final String PANGLE_PLACEMENT_ID = "pangle-reward-placement";
    private static final byte[] PANGLE_SECURITY_KEY =
            "pangle-security-key-value".getBytes(StandardCharsets.UTF_8);
    private static final byte[] CREDENTIAL_METADATA_FINGERPRINT_DOMAIN =
            "skit-pangle-reward-credential-metadata-v2\0"
                    .getBytes(StandardCharsets.US_ASCII);
    private static final LocalDateTime RECEIVED_AT =
            LocalDateTime.of(2026, 7, 14, 23, 20);

    private SkitCallbackRoutingService routingService;
    private SkitCallbackRouteRegistryService registryService;
    private SkitAdCredentialVersionService credentialService;
    private SkitAdSessionTokenService tokenService;
    private SkitAdSessionMapper sessionMapper;
    private SkitPangleRewardAttestationMapper attestationMapper;
    private SkitPangleAttestationInboxWakeService inboxWakeService;
    private SkitCallbackRateLimiter rateLimiter;
    private SkitPangleCallbackIngressServiceImpl service;
    private SkitAdSessionDO session;
    private String customData;
    private AtomicReference<SkitPangleRewardAttestationDO> inserted;

    @BeforeEach
    void setUp() {
        credentialService = mock(SkitAdCredentialVersionService.class);
        registryService = mock(SkitCallbackRouteRegistryService.class);
        routingService = new SkitCallbackRoutingService(registryService);
        tokenService = new SkitHmacAdSessionTokenService(1, Collections.singletonMap(1,
                "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.US_ASCII)));
        customData = tokenService.issue("pangle-ingress-session").consumeCustomData();
        sessionMapper = mock(SkitAdSessionMapper.class);
        attestationMapper = mock(SkitPangleRewardAttestationMapper.class);
        inboxWakeService = mock(SkitPangleAttestationInboxWakeService.class);
        rateLimiter = mock(SkitCallbackRateLimiter.class);
        Clock clock = Clock.fixed(Instant.parse("2026-07-14T15:20:00Z"),
                ZoneId.of("Asia/Shanghai"));
        service = new SkitPangleCallbackIngressServiceImpl(routingService,
                new PangleRewardCallbackCanonicalizer(), new PangleRewardSignatureVerifier(),
                credentialService, tokenService, sessionMapper, attestationMapper,
                inboxWakeService, rateLimiter, clock);

        when(registryService.lookupTenantReward(any(byte[].class), eq(RECEIVED_AT)))
                .thenReturn(SkitCallbackRouteRegistryService.RouteLookup.tenant(
                        TENANT_ID, TAKU_ACCOUNT_ID, 4, true, null));
        session = session();
        when(sessionMapper.selectByTokenHashForUpdate(
                eq(TENANT_ID), eq(TAKU_ACCOUNT_ID), any(byte[].class))).thenReturn(session);
        when(credentialService.resolveRewardSecret(
                TENANT_ID, PANGLE_ACCOUNT_ID, 9,
                session.getRewardAcceptUntil(), RECEIVED_AT)).thenAnswer(invocation ->
                new SkitAdCredentialVersionService.ResolvedRewardSecret(
                        TENANT_ID, PANGLE_ACCOUNT_ID, 9, true, null,
                        PANGLE_SECURITY_KEY.clone()));
        inserted = new AtomicReference<>();
        AtomicLong nextId = new AtomicLong(700L);
        doAnswer(invocation -> {
            SkitPangleRewardAttestationDO row = invocation.getArgument(0);
            row.setId(nextId.incrementAndGet());
            inserted.set(row);
            return 1;
        }).when(attestationMapper).insert(any(SkitPangleRewardAttestationDO.class));
        when(attestationMapper.selectByTransactionId(
                eq(TENANT_ID), eq(PANGLE_ACCOUNT_ID), anyString())).thenAnswer(invocation -> {
            SkitPangleRewardAttestationDO row = inserted.get();
            return row != null && invocation.getArgument(2).equals(row.getProviderTransactionId())
                    ? row : null;
        });
        when(attestationMapper.selectBySession(
                TENANT_ID, TAKU_ACCOUNT_ID, SESSION_ROW_ID)).thenAnswer(invocation -> inserted.get());
    }

    @Test
    void validCallbackPersistsOnlyImmutableTenantBoundAttestation() {
        String query = signedQuery("transaction-1", USER_ID, "coins", "1");

        boolean accepted = service.receiveReward(CALLBACK_KEY, query, "203.0.113.8");

        assertTrue(accepted);
        ArgumentCaptor<SkitPangleRewardAttestationDO> captor =
                ArgumentCaptor.forClass(SkitPangleRewardAttestationDO.class);
        verify(attestationMapper).insert(captor.capture());
        SkitPangleRewardAttestationDO row = captor.getValue();
        assertEquals(TENANT_ID, row.getTenantId());
        assertEquals(TAKU_ACCOUNT_ID, row.getTakuAdAccountId());
        assertEquals(PANGLE_ACCOUNT_ID, row.getPangleAdAccountId());
        assertEquals(SESSION_ROW_ID, row.getAdSessionId());
        assertEquals(Integer.valueOf(4), row.getCallbackKeyVersion());
        assertEquals(Integer.valueOf(9), row.getPangleRewardSecretVersion());
        assertEquals(PANGLE_PLACEMENT_ID, row.getPangleRewardPlacementId());
        assertEquals("PANGLE", row.getProvider());
        assertEquals("transaction-1", row.getProviderTransactionId());
        assertEquals(USER_ID, row.getProviderUserId());
        assertEquals("coins", row.getRewardName());
        assertEquals(Integer.valueOf(1), row.getRewardAmount());
        assertArrayEquals(tokenService.hashCustomData(customData), row.getExtraDataHash());
        assertEquals(32, row.getCanonicalPayloadHash().length);
        assertArrayEquals(expectedCredentialMetadataFingerprint(
                        TENANT_ID, TAKU_ACCOUNT_ID, PANGLE_ACCOUNT_ID, 9),
                row.getCredentialFingerprint());
        assertEquals(RECEIVED_AT, row.getReceivedAt());
        verify(rateLimiter).check("PANGLE", CALLBACK_KEY, "203.0.113.8", "REWARD");
        verify(inboxWakeService).wakeRetry(
                TENANT_ID, TAKU_ACCOUNT_ID, SESSION_ROW_ID, 4);
    }

    @Test
    void credentialFingerprintCannotBeUsedAsAnOfflineSecurityKeyOracle() {
        assertTrue(service.receiveReward(CALLBACK_KEY,
                signedQuery(PANGLE_SECURITY_KEY,
                        "transaction-security-key-a", USER_ID, "coins", "1"),
                "203.0.113.8"));
        byte[] firstFingerprint = inserted.get().getCredentialFingerprint().clone();

        byte[] replacementSecurityKey =
                "different-pangle-security-key".getBytes(StandardCharsets.UTF_8);
        inserted.set(null);
        session.setId(SESSION_ROW_ID + 1);
        when(credentialService.resolveRewardSecret(
                TENANT_ID, PANGLE_ACCOUNT_ID, 9,
                session.getRewardAcceptUntil(), RECEIVED_AT)).thenAnswer(invocation ->
                new SkitAdCredentialVersionService.ResolvedRewardSecret(
                        TENANT_ID, PANGLE_ACCOUNT_ID, 9, true, null,
                        replacementSecurityKey.clone()));

        assertTrue(service.receiveReward(CALLBACK_KEY,
                signedQuery(replacementSecurityKey,
                        "transaction-security-key-b", USER_ID, "coins", "1"),
                "203.0.113.8"));

        assertArrayEquals(firstFingerprint, inserted.get().getCredentialFingerprint(),
                "the fingerprint must identify public credential metadata, not Security Key bytes");
    }

    @Test
    void wakeIsDeferredUntilTheAttestationTransactionCommits() {
        TransactionSynchronizationManager.initSynchronization();
        try {
            assertTrue(service.receiveReward(CALLBACK_KEY,
                    signedQuery("transaction-after-commit", USER_ID, "coins", "1"),
                    "203.0.113.8"));

            verify(inboxWakeService, never()).wakeRetry(
                    TENANT_ID, TAKU_ACCOUNT_ID, SESSION_ROW_ID, 4);
            assertEquals(1, TransactionSynchronizationManager.getSynchronizations().size());

            for (TransactionSynchronization synchronization
                    : TransactionSynchronizationManager.getSynchronizations()) {
                synchronization.afterCommit();
            }

            verify(inboxWakeService).wakeRetry(
                    TENANT_ID, TAKU_ACCOUNT_ID, SESSION_ROW_ID, 4);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void wrongSignatureIsFalseAndNeverPersists() {
        String query = signedQuery("transaction-2", USER_ID, "coins", "1")
                .replaceFirst("sign=[0-9a-f]{64}", "sign=" + repeat('0', 64));

        assertFalse(service.receiveReward(CALLBACK_KEY, query, "203.0.113.8"));

        verify(attestationMapper, never()).insert(any());
    }

    @Test
    void wrongRouteSessionOrUserIsFalseBeforeCredentialResolution() {
        session.setAdAccountId(TAKU_ACCOUNT_ID + 1);
        assertFalse(service.receiveReward(CALLBACK_KEY,
                signedQuery("transaction-3", USER_ID, "coins", "1"), "203.0.113.8"));

        session.setAdAccountId(TAKU_ACCOUNT_ID);
        assertFalse(service.receiveReward(CALLBACK_KEY,
                signedQuery("transaction-3", "another-user", "coins", "1"), "203.0.113.8"));

        verify(credentialService, never()).resolveRewardSecret(
                anyLong(), anyLong(), anyInt(), any(), any());
        verify(attestationMapper, never()).insert(any());
    }

    @Test
    void expiredRewardWindowIsFalse() {
        session.setRewardAcceptUntil(RECEIVED_AT.minusSeconds(1));

        assertFalse(service.receiveReward(CALLBACK_KEY,
                signedQuery("transaction-4", USER_ID, "coins", "1"), "203.0.113.8"));

        verify(attestationMapper, never()).insert(any());
    }

    @Test
    void absentOrPartialPangleCredentialSnapshotIsFalse() {
        session.setPangleRewardPlacementId(null);

        assertFalse(service.receiveReward(CALLBACK_KEY,
                signedQuery("transaction-5", USER_ID, "coins", "1"), "203.0.113.8"));

        verify(credentialService, never()).resolveRewardSecret(
                anyLong(), anyLong(), anyInt(), any(), any());
        verify(attestationMapper, never()).insert(any());
    }

    @Test
    void exactTransactionAndCanonicalPayloadReplayIsTrueWithoutSecondInsert() {
        String query = signedQuery("transaction-6", USER_ID, "coins", "1");

        assertTrue(service.receiveReward(CALLBACK_KEY, query, "203.0.113.8"));
        assertTrue(service.receiveReward(CALLBACK_KEY, query, "203.0.113.8"));

        verify(attestationMapper).insert(any());
        verify(inboxWakeService, times(2)).wakeRetry(
                TENANT_ID, TAKU_ACCOUNT_ID, SESSION_ROW_ID, 4);
    }

    @Test
    void exactReplayRemainsTrueAfterRewardWindowExpires() {
        String query = signedQuery("transaction-6-expired", USER_ID, "coins", "1");
        assertTrue(service.receiveReward(CALLBACK_KEY, query, "203.0.113.8"));
        session.setRewardAcceptUntil(RECEIVED_AT.minusSeconds(1));

        assertTrue(service.receiveReward(CALLBACK_KEY, query, "203.0.113.8"));

        verify(attestationMapper).insert(any());
        verify(inboxWakeService, times(2)).wakeRetry(
                TENANT_ID, TAKU_ACCOUNT_ID, SESSION_ROW_ID, 4);
    }

    @Test
    void exactReplayRemainsTrueAfterSessionHasAlreadyGranted() {
        String query = signedQuery("transaction-6-granted", USER_ID, "coins", "1");
        assertTrue(service.receiveReward(CALLBACK_KEY, query, "203.0.113.8"));
        session.setRewardVerificationStatus("VERIFIED");

        assertTrue(service.receiveReward(CALLBACK_KEY, query, "203.0.113.8"));

        verify(attestationMapper).insert(any());
        verify(inboxWakeService, times(2)).wakeRetry(
                TENANT_ID, TAKU_ACCOUNT_ID, SESSION_ROW_ID, 4);
    }

    @Test
    void expiredReplayWithSameTransactionButChangedPayloadIsFalse() {
        assertTrue(service.receiveReward(CALLBACK_KEY,
                signedQuery("transaction-6-conflict", USER_ID, "coins", "1"),
                "203.0.113.8"));
        session.setRewardAcceptUntil(RECEIVED_AT.minusSeconds(1));

        assertFalse(service.receiveReward(CALLBACK_KEY,
                signedQuery("transaction-6-conflict", USER_ID, "gems", "1"),
                "203.0.113.8"));

        verify(attestationMapper).insert(any());
        verify(inboxWakeService).wakeRetry(
                TENANT_ID, TAKU_ACCOUNT_ID, SESSION_ROW_ID, 4);
    }

    @Test
    void sameTransactionWithDifferentPayloadIsFalse() {
        assertTrue(service.receiveReward(CALLBACK_KEY,
                signedQuery("transaction-7", USER_ID, "coins", "1"), "203.0.113.8"));

        assertFalse(service.receiveReward(CALLBACK_KEY,
                signedQuery("transaction-7", USER_ID, "gems", "1"), "203.0.113.8"));

        verify(attestationMapper).insert(any());
    }

    @Test
    void secondTransactionForOneSessionIsFalse() {
        assertTrue(service.receiveReward(CALLBACK_KEY,
                signedQuery("transaction-8a", USER_ID, "coins", "1"), "203.0.113.8"));

        assertFalse(service.receiveReward(CALLBACK_KEY,
                signedQuery("transaction-8b", USER_ID, "coins", "1"), "203.0.113.8"));

        verify(attestationMapper).insert(any());
    }

    @Test
    void unknownCallbackKeyIsFalseWithoutSessionLookup() {
        when(registryService.lookupTenantReward(any(byte[].class), eq(RECEIVED_AT)))
                .thenThrow(new SkitCallbackRouteRegistryService.CallbackRouteRejectedException());

        assertFalse(service.receiveReward(CALLBACK_KEY,
                signedQuery("transaction-9", USER_ID, "coins", "1"), "203.0.113.8"));

        verify(sessionMapper, never()).selectByTokenHashForUpdate(
                eq(TENANT_ID), eq(TAKU_ACCOUNT_ID), any(byte[].class));
        verify(attestationMapper, never()).insert(any());
    }

    @Test
    void malformedQueryIsFalseButInfrastructureFailuresPropagate() {
        assertFalse(service.receiveReward(CALLBACK_KEY, "user_id=only", "203.0.113.8"));

        doThrow(new IllegalStateException("redis unavailable"))
                .when(rateLimiter).check("PANGLE", CALLBACK_KEY, "203.0.113.9", "REWARD");
        assertThrows(IllegalStateException.class, () -> service.receiveReward(
                CALLBACK_KEY, signedQuery("transaction-10", USER_ID, "coins", "1"),
                "203.0.113.9"));
    }

    private SkitAdSessionDO session() {
        SkitAdSessionDO row = new SkitAdSessionDO()
                .setId(SESSION_ROW_ID).setSessionId(SESSION_PUBLIC_ID)
                .setSessionTokenHash(tokenService.hashCustomData(customData))
                .setSessionTokenKeyVersion(1).setProtocolVersion(1).setMemberId(42L)
                .setAdAccountId(TAKU_ACCOUNT_ID).setPolicySnapshotId(88L)
                .setCallbackKeyVersion(4).setRewardSecretVersion(7)
                .setPangleAdAccountId(PANGLE_ACCOUNT_ID)
                .setPangleRewardSecretVersion(9)
                .setPangleRewardPlacementId(PANGLE_PLACEMENT_ID)
                .setProvider("TAKU").setPlacementId("taku-placement")
                .setScenarioId("drama_unlock").setBusinessType("EPISODE_UNLOCK")
                .setDramaId(801L).setEpisodeFrom(3).setEpisodeTo(3)
                .setUnlockScope("drama:801:episode:3")
                .setPseudonymousUserId(USER_ID).setRewardVerificationStatus("PENDING")
                .setRewardAcceptUntil(RECEIVED_AT.plusMinutes(5)).setVersion(0);
        row.setTenantId(TENANT_ID);
        return row;
    }

    private String signedQuery(String transactionId, String userId,
                               String rewardName, String rewardAmount) {
        return signedQuery(PANGLE_SECURITY_KEY, transactionId, userId, rewardName, rewardAmount);
    }

    private String signedQuery(byte[] securityKey, String transactionId, String userId,
                               String rewardName, String rewardAmount) {
        String signature = sha256Hex(securityKey, transactionId);
        return "user_id=" + encode(userId) + "&trans_id=" + encode(transactionId)
                + "&reward_name=" + encode(rewardName) + "&reward_amount=" + rewardAmount
                + "&extra=" + encode(customData) + "&sign=" + signature;
    }

    private static byte[] expectedCredentialMetadataFingerprint(
            long tenantId, long takuAdAccountId, long pangleAdAccountId,
            int rewardSecretVersion) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(CREDENTIAL_METADATA_FINGERPRINT_DOMAIN);
            digest.update(ByteBuffer.allocate(Long.BYTES).putLong(tenantId).array());
            digest.update(ByteBuffer.allocate(Long.BYTES).putLong(takuAdAccountId).array());
            digest.update(ByteBuffer.allocate(Long.BYTES).putLong(pangleAdAccountId).array());
            digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(rewardSecretVersion).array());
            return digest.digest();
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static String sha256Hex(byte[] securityKey, String transactionId) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(securityKey);
            digest.update((byte) ':');
            byte[] value = digest.digest(transactionId.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(value.length * 2);
            for (byte item : value) {
                result.append(String.format("%02x", item & 0xff));
            }
            return result.toString();
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static String encode(String value) {
        try {
            return URLEncoder.encode(value, StandardCharsets.UTF_8.name());
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static String repeat(char value, int count) {
        char[] result = new char[count];
        java.util.Arrays.fill(result, value);
        return new String(result);
    }
}
