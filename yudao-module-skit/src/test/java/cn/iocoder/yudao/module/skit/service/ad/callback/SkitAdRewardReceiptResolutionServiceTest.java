package cn.iocoder.yudao.module.skit.service.ad.callback;

import cn.iocoder.yudao.module.skit.dal.dataobject.ad.SkitAdCallbackInboxDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.ad.SkitAdSessionDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.revenue.SkitAdRevenueEventDO;
import cn.iocoder.yudao.module.skit.dal.mysql.ad.SkitAdCallbackInboxMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.ad.SkitAdSessionMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.revenue.SkitAdRevenueEventMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SkitAdRewardReceiptResolutionServiceTest {

    private static final LocalDateTime RECEIVED_AT =
            LocalDateTime.of(2026, 7, 21, 1, 2, 3);
    private static final LocalDateTime RESOLVED_AT = RECEIVED_AT.plusMinutes(2);

    @Mock private SkitAdCallbackInboxMapper inboxMapper;
    @Mock private SkitAdSessionMapper sessionMapper;
    @Mock private SkitAdRevenueEventMapper revenueMapper;

    private SkitAdRewardReceiptResolutionService service;

    @BeforeEach
    void setUp() {
        service = new SkitAdRewardReceiptResolutionService(
                inboxMapper, sessionMapper, revenueMapper);
    }

    @ParameterizedTest
    @ValueSource(strings = {"PENDING", "PROCESSING", "RETRY_WAIT", "SUCCEEDED"})
    void nonFailedInboxStatesNeverReleaseTheEpisodeScope(String status) {
        SkitAdSessionDO session = pendingSession("NONE");
        when(inboxMapper.selectByTenantAccountAndId(11L, 13L, 19L))
                .thenReturn(rewardInbox(status));

        assertFalse(service.resolveTerminalReceipt(session, RESOLVED_AT));

        assertEquals("PENDING", session.getRewardVerificationStatus());
        verify(sessionMapper, never()).markTerminalRewardReceiptRejectedAndReleaseScopeCas(
                anyLong(), anyLong(), anyLong(), anyLong(), anyLong(), any(LocalDateTime.class),
                anyInt(), anyInt(), anyLong(), anyInt(), any(byte[].class), anyInt(),
                anyString(), anyString(), any(LocalDateTime.class));
        verify(revenueMapper, never()).selectByTenantSessionAndSourceForUpdate(
                anyLong(), anyLong(), anyString());
    }

    @Test
    void deadLetterRejectsOnlyTheBoundEpisodeAndReleasesItsScope() {
        SkitAdSessionDO session = pendingSession("NONE");
        when(inboxMapper.selectByTenantAccountAndId(11L, 13L, 19L))
                .thenReturn(rewardInbox("DEAD_LETTER"));
        when(revenueMapper.selectByTenantSessionAndSourceForUpdate(
                11L, 17L, "TAKU_IMPRESSION")).thenReturn(null);
        when(sessionMapper.markTerminalRewardReceiptRejectedAndReleaseScopeCas(
                11L, 17L, 13L, 12L, 19L, RECEIVED_AT, 2, 3,
                14L, 7, session.getActiveScopeHash(), 4, "DEAD_LETTER",
                "CALLBACK_DEAD_LETTER", RESOLVED_AT)).thenReturn(1);

        assertTrue(service.resolveTerminalReceipt(session, RESOLVED_AT));

        assertEquals("REJECTED", session.getRewardVerificationStatus());
        assertEquals("NONE", session.getRevenueStatus());
        assertNull(session.getActiveScopeHash());
        assertEquals("REWARD_REJECTED", session.getActiveScopeReleaseReason());
        assertEquals("CALLBACK_DEAD_LETTER", session.getFailureReason());
        assertEquals(5, session.getVersion());
    }

    @Test
    void deadLetterConvergesItsPendingImpressionToNonRewardedSuspense() {
        SkitAdSessionDO session = pendingSession("IMPRESSION_PENDING_REWARD");
        byte[] expectedScope = session.getActiveScopeHash();
        SkitAdRevenueEventDO event = pendingImpression(session);
        when(inboxMapper.selectByTenantAccountAndId(11L, 13L, 19L))
                .thenReturn(rewardInbox("DEAD_LETTER"));
        when(revenueMapper.selectByTenantSessionAndSourceForUpdate(
                11L, 17L, "TAKU_IMPRESSION")).thenReturn(event);
        when(sessionMapper.markTerminalRewardReceiptRejectedAndReleaseScopeCas(
                11L, 17L, 13L, 12L, 19L, RECEIVED_AT, 2, 3,
                14L, 7, expectedScope, 4, "DEAD_LETTER",
                "CALLBACK_DEAD_LETTER", RESOLVED_AT)).thenReturn(1);
        when(revenueMapper.markNonRewardedSuspenseCas(
                11L, 23L, 17L, 13L, 6, RESOLVED_AT)).thenReturn(1);

        assertTrue(service.resolveTerminalReceipt(session, RESOLVED_AT));

        assertEquals("SUSPENSE", session.getRevenueStatus());
        assertEquals("NON_REWARDED", event.getRewardQualificationStatus());
        assertEquals("SUSPENSE", event.getReconciliationStatus());
        assertEquals(7, event.getVersion());
    }

    @Test
    void deadLetterWithConflictingReplayUsesDedicatedIntegrityFailure() {
        SkitAdSessionDO session = pendingSession("NONE");
        when(inboxMapper.selectByTenantAccountAndId(11L, 13L, 19L))
                .thenReturn(rewardInbox("DEAD_LETTER")
                        .setDeliveryIntegrityStatus("PAYLOAD_CONFLICT"));
        when(revenueMapper.selectByTenantSessionAndSourceForUpdate(
                11L, 17L, "TAKU_IMPRESSION")).thenReturn(null);
        when(sessionMapper.markTerminalRewardReceiptRejectedAndReleaseScopeCas(
                11L, 17L, 13L, 12L, 19L, RECEIVED_AT, 2, 3,
                14L, 7, session.getActiveScopeHash(), 4, "DEAD_LETTER",
                "CALLBACK_PAYLOAD_CONFLICT", RESOLVED_AT)).thenReturn(1);

        assertTrue(service.resolveTerminalReceipt(session, RESOLVED_AT));

        assertEquals("REJECTED", session.getRewardVerificationStatus());
        assertEquals("CALLBACK_PAYLOAD_CONFLICT", session.getFailureReason());
        assertNull(session.getActiveScopeHash());
        assertEquals("REWARD_REJECTED", session.getActiveScopeReleaseReason());
    }

    @Test
    void conflictingDeadLetterStillRequiresOriginalSignedAuthority() {
        SkitAdSessionDO session = pendingSession("NONE");
        when(inboxMapper.selectByTenantAccountAndId(11L, 13L, 19L))
                .thenReturn(rewardInbox("DEAD_LETTER")
                        .setDeliveryIntegrityStatus("PAYLOAD_CONFLICT")
                        .setSignatureStatus("INVALID"));

        assertThrows(IllegalStateException.class,
                () -> service.resolveTerminalReceipt(session, RESOLVED_AT));

        verify(sessionMapper, never()).markTerminalRewardReceiptRejectedAndReleaseScopeCas(
                anyLong(), anyLong(), anyLong(), anyLong(), anyLong(), any(LocalDateTime.class),
                anyInt(), anyInt(), anyLong(), anyInt(), any(byte[].class), anyInt(),
                anyString(), anyString(), any(LocalDateTime.class));
    }

    @Test
    void rejectedInboxRepairsLegacyPendingSessionWithItsDeterministicError() {
        SkitAdSessionDO session = pendingSession("NONE");
        when(inboxMapper.selectByTenantAccountAndId(11L, 13L, 19L))
                .thenReturn(rewardInbox("REJECTED")
                        .setErrorCode("REWARD_USER_MISMATCH")
                        .setProvider("INVALID_PROVIDER")
                        .setCallbackKeyVersion(99)
                        .setDeliveryIntegrityStatus("PAYLOAD_CONFLICT"));
        when(revenueMapper.selectByTenantSessionAndSourceForUpdate(
                11L, 17L, "TAKU_IMPRESSION")).thenReturn(null);
        when(sessionMapper.markTerminalRewardReceiptRejectedAndReleaseScopeCas(
                11L, 17L, 13L, 12L, 19L, RECEIVED_AT, 2, 3,
                14L, 7, session.getActiveScopeHash(), 4, "REJECTED",
                "REWARD_USER_MISMATCH", RESOLVED_AT)).thenReturn(1);

        assertTrue(service.resolveTerminalReceipt(session, RESOLVED_AT));
        assertEquals("REWARD_USER_MISMATCH", session.getFailureReason());
    }

    @Test
    void alreadyGrantedSessionCannotBeRejectedByAStaleTerminalInbox() {
        SkitAdSessionDO session = pendingSession("FROZEN")
                .setRewardVerificationStatus("SIGNED_VERIFIED")
                .setEntitlementStatus("GRANTED")
                .setProviderTransactionId("transaction-1")
                .setActiveScopeHash(null)
                .setActiveScopeReleasedAt(RESOLVED_AT)
                .setActiveScopeReleaseReason("ENTITLEMENT_GRANTED");

        assertFalse(service.resolveTerminalReceipt(session, RESOLVED_AT));

        verify(inboxMapper, never()).selectByTenantAccountAndId(anyLong(), anyLong(), anyLong());
        verify(sessionMapper, never()).markTerminalRewardReceiptRejectedAndReleaseScopeCas(
                anyLong(), anyLong(), anyLong(), anyLong(), anyLong(), any(LocalDateTime.class),
                anyInt(), anyInt(), anyLong(), anyInt(), any(byte[].class), anyInt(),
                anyString(), anyString(), any(LocalDateTime.class));
    }

    @Test
    void forgedTerminalInboxFailsClosedBeforeAnyScopeMutation() {
        SkitAdSessionDO session = pendingSession("NONE");
        when(inboxMapper.selectByTenantAccountAndId(11L, 13L, 19L))
                .thenReturn(rewardInbox("DEAD_LETTER").setAdSessionId(999L));

        assertThrows(IllegalStateException.class,
                () -> service.resolveTerminalReceipt(session, RESOLVED_AT));

        verify(sessionMapper, never()).markTerminalRewardReceiptRejectedAndReleaseScopeCas(
                anyLong(), anyLong(), anyLong(), anyLong(), anyLong(), any(LocalDateTime.class),
                anyInt(), anyInt(), anyLong(), anyInt(), any(byte[].class), anyInt(),
                anyString(), anyString(), any(LocalDateTime.class));
    }

    @Test
    void lostTerminalCasAbortsBeforeTheImpressionCanBeConverged() {
        SkitAdSessionDO session = pendingSession("IMPRESSION_PENDING_REWARD");
        SkitAdRevenueEventDO event = pendingImpression(session);
        when(inboxMapper.selectByTenantAccountAndId(11L, 13L, 19L))
                .thenReturn(rewardInbox("DEAD_LETTER"));
        when(revenueMapper.selectByTenantSessionAndSourceForUpdate(
                11L, 17L, "TAKU_IMPRESSION")).thenReturn(event);

        assertThrows(IllegalStateException.class,
                () -> service.resolveTerminalReceipt(session, RESOLVED_AT));

        verify(revenueMapper, never()).markNonRewardedSuspenseCas(
                anyLong(), anyLong(), anyLong(), anyLong(), anyInt(), any(LocalDateTime.class));
    }

    @Test
    void partialReceiptBindingFailsClosed() {
        SkitAdSessionDO session = pendingSession("NONE").setRewardCallbackReceivedAt(null);

        assertThrows(IllegalStateException.class,
                () -> service.resolveTerminalReceipt(session, RESOLVED_AT));

        verify(inboxMapper, never()).selectByTenantAccountAndId(anyLong(), anyLong(), anyLong());
    }

    private static SkitAdSessionDO pendingSession(String revenueStatus) {
        SkitAdSessionDO session = new SkitAdSessionDO()
                .setId(17L).setAdAccountId(13L).setMemberId(12L).setPolicySnapshotId(15L)
                .setProvider("TAKU").setCallbackKeyVersion(2).setRewardSecretVersion(3)
                .setDramaId(14L).setEpisodeFrom(7).setEpisodeTo(7)
                .setRewardVerificationStatus("PENDING").setEntitlementStatus("NONE")
                .setRevenueStatus(revenueStatus).setRewardAcceptUntil(RECEIVED_AT.plusMinutes(1))
                .setRewardCallbackInboxId(19L).setRewardCallbackReceivedAt(RECEIVED_AT)
                .setActiveScopeHash(new byte[]{1, 2, 3}).setVersion(4);
        session.setTenantId(11L);
        return session;
    }

    private static SkitAdCallbackInboxDO rewardInbox(String status) {
        SkitAdCallbackInboxDO inbox = new SkitAdCallbackInboxDO()
                .setId(19L).setAdAccountId(13L).setAdSessionId(17L)
                .setCallbackKeyVersion(2).setRewardSecretVersion(3)
                .setProvider("TAKU").setCallbackType("REWARD")
                .setSignedFieldMask(63L).setEvidenceProvenance("SIGNED_ILRD")
                .setAuthenticationLevel("SIGNED_REWARD").setSignatureStatus("VALID")
                .setDeliveryIntegrityStatus("CANONICAL").setProcessingStatus(status)
                .setReceivedAt(RECEIVED_AT).setProcessedAt(RESOLVED_AT.minusSeconds(1));
        inbox.setTenantId(11L);
        return inbox;
    }

    private static SkitAdRevenueEventDO pendingImpression(SkitAdSessionDO session) {
        SkitAdRevenueEventDO event = new SkitAdRevenueEventDO()
                .setId(23L).setAdAccountId(13L).setAdSessionId(17L)
                .setSourceMemberId(12L).setPolicySnapshotId(15L)
                .setSourceType("TAKU_IMPRESSION")
                .setRewardQualificationStatus("PENDING_REWARD")
                .setSourceVerificationStatus("UNSIGNED_OBSERVATION")
                .setReconciliationStatus("FROZEN").setLegacyUnverified(false).setVersion(6);
        event.setTenantId(session.getTenantId());
        return event;
    }
}
