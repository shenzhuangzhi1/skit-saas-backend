package cn.iocoder.yudao.module.skit.service.ad.callback;

import cn.iocoder.yudao.module.skit.dal.dataobject.provider.SkitProviderCallbackAttemptDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.provider.SkitProviderImpressionInboxDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.provider.SkitProviderImpressionTenantRoute;
import cn.iocoder.yudao.module.skit.dal.mysql.provider.SkitProviderCallbackAttemptMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.provider.SkitProviderImpressionAttributionMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.provider.SkitProviderImpressionInboxMapper;
import cn.iocoder.yudao.module.skit.framework.crypto.SkitProviderCallbackPayloadCryptoService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Collections;

import static cn.iocoder.yudao.module.skit.service.ad.callback.SkitCallbackIngressService.IngressResponse.OK;
import static cn.iocoder.yudao.module.skit.service.ad.callback.SkitCallbackIngressService.IngressResponse.REJECTED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SkitProviderImpressionAttributionProcessorTest {

    private static final long CONNECTION_ID = 101L;
    private static final long INBOX_ID = 201L;
    private static final long ATTEMPT_ID = 301L;
    private static final long TENANT_ID = 401L;
    private static final long ACCOUNT_ID = 501L;
    private static final long SESSION_ROW_ID = 601L;
    private static final int CALLBACK_KEY_VERSION = 7;
    private static final String LEASE_OWNER = "provider-attribution-test";
    private static final String SESSION_ID = "AAAAAAAAAAAAAAAAAAAAAA";
    private static final String PACKAGE_NAME = "com.example.skit";
    private static final String PLACEMENT_ID = "reward-placement-1";
    private static final String USER_ID = "member-pseudo-42";
    private static final LocalDateTime RECEIVED_AT_UTC =
            LocalDateTime.of(2026, 8, 3, 1, 2, 3);
    private static final LocalDateTime PROCESSED_AT_UTC =
            LocalDateTime.of(2026, 8, 3, 2, 3, 4);
    private static final ZoneId TENANT_DATABASE_ZONE = ZoneId.of("Asia/Shanghai");
    private static final String RAW_QUERY = "user_id=" + USER_ID
            + "&req_id=req-100&package_name=" + PACKAGE_NAME
            + "&adformat=1&placement_id=" + PLACEMENT_ID
            + "&nw_firm_id=66&adsource_id=56789&adsource_price=12.345"
            + "&currency=USD&timestamp=1785720000&show_custom_ext=" + SESSION_ID;

    @Test
    void exactSharedMasterBindingCreatesTenantInboxAndMarksProviderSucceeded() {
        Fixture fixture = new Fixture();
        when(fixture.attributionMapper.selectExactRoute(CONNECTION_ID, SESSION_ID, PACKAGE_NAME,
                PLACEMENT_ID, USER_ID)).thenReturn(Collections.singletonList(exactRoute()));
        when(fixture.tenantIngress.receiveAttributedImpression(any(), eq(RAW_QUERY),
                eq(LocalDateTime.of(2026, 8, 3, 9, 2, 3)))).thenReturn(OK);
        when(fixture.inboxMapper.markAttributionSucceededCas(
                CONNECTION_ID, INBOX_ID, LEASE_OWNER, PROCESSED_AT_UTC)).thenReturn(1);

        assertEquals(SkitProviderImpressionAttributionProcessor.ProcessResult.SUCCEEDED,
                fixture.processor.process(CONNECTION_ID, INBOX_ID, LEASE_OWNER));

        ArgumentCaptor<SkitCallbackRoutingService.CallbackRoute> routeCaptor =
                ArgumentCaptor.forClass(SkitCallbackRoutingService.CallbackRoute.class);
        verify(fixture.tenantIngress).receiveAttributedImpression(
                routeCaptor.capture(), eq(RAW_QUERY),
                eq(LocalDateTime.of(2026, 8, 3, 9, 2, 3)));
        assertEquals(TENANT_ID, routeCaptor.getValue().getTenantId());
        assertEquals(ACCOUNT_ID, routeCaptor.getValue().getAdAccountId());
        assertEquals(CALLBACK_KEY_VERSION, routeCaptor.getValue().getCallbackKeyVersion());
        verify(fixture.inboxMapper).markAttributionSucceededCas(
                CONNECTION_ID, INBOX_ID, LEASE_OWNER, PROCESSED_AT_UTC);
        InOrder finalBoundary = inOrder(fixture.inboxMapper, fixture.attributionMapper,
                fixture.tenantIngress);
        finalBoundary.verify(fixture.inboxMapper)
                .selectByConnectionAndIdForUpdate(CONNECTION_ID, INBOX_ID);
        finalBoundary.verify(fixture.attributionMapper).selectExactRoute(
                CONNECTION_ID, SESSION_ID, PACKAGE_NAME, PLACEMENT_ID, USER_ID);
        finalBoundary.verify(fixture.tenantIngress).receiveAttributedImpression(
                any(), eq(RAW_QUERY), eq(LocalDateTime.of(2026, 8, 3, 9, 2, 3)));
        assertEquals(1, fixture.transactions.definitions.size());
        assertEquals(TransactionDefinition.PROPAGATION_REQUIRES_NEW,
                fixture.transactions.definitions.get(0).getPropagationBehavior());
        assertEquals(TransactionDefinition.ISOLATION_READ_COMMITTED,
                fixture.transactions.definitions.get(0).getIsolationLevel());
        assertTrue(fixture.attempt.getPayloadCiphertext()[0] == 0,
                "processor must wipe the loaded ciphertext copy");
        assertTrue(fixture.attempt.getPayloadNonce()[0] == 0,
                "processor must wipe the loaded nonce copy");
    }

    @Test
    void noExactServerOwnedRouteQuarantinesWithoutTenantWrite() {
        Fixture fixture = new Fixture();
        when(fixture.attributionMapper.selectExactRoute(CONNECTION_ID, SESSION_ID, PACKAGE_NAME,
                PLACEMENT_ID, USER_ID)).thenReturn(Collections.emptyList());
        when(fixture.inboxMapper.markAttributionQuarantinedCas(CONNECTION_ID, INBOX_ID,
                LEASE_OWNER, "ATTRIBUTION_ROUTE_UNMATCHED", PROCESSED_AT_UTC)).thenReturn(1);

        assertEquals(SkitProviderImpressionAttributionProcessor.ProcessResult.QUARANTINED,
                fixture.processor.process(CONNECTION_ID, INBOX_ID, LEASE_OWNER));

        verifyNoInteractions(fixture.tenantIngress);
        verify(fixture.inboxMapper).markAttributionQuarantinedCas(CONNECTION_ID, INBOX_ID,
                LEASE_OWNER, "ATTRIBUTION_ROUTE_UNMATCHED", PROCESSED_AT_UTC);
    }

    @Test
    void unsupportedAdFormatQuarantinesBeforeTenantLookup() {
        Fixture fixture = new Fixture();
        when(fixture.crypto.decrypt(any(), any())).thenReturn(
                RAW_QUERY.replace("adformat=1", "adformat=2")
                        .getBytes(StandardCharsets.US_ASCII));
        when(fixture.inboxMapper.markAttributionQuarantinedCas(CONNECTION_ID, INBOX_ID,
                LEASE_OWNER, "ATTRIBUTION_PAYLOAD_INVALID", PROCESSED_AT_UTC)).thenReturn(1);

        assertEquals(SkitProviderImpressionAttributionProcessor.ProcessResult.QUARANTINED,
                fixture.processor.process(CONNECTION_ID, INBOX_ID, LEASE_OWNER));

        verifyNoInteractions(fixture.attributionMapper, fixture.tenantIngress);
    }

    @Test
    void leaseLostBeforeTenantPublishLeavesBothInboxesUntouched() {
        Fixture fixture = new Fixture();
        when(fixture.attributionMapper.selectExactRoute(CONNECTION_ID, SESSION_ID, PACKAGE_NAME,
                PLACEMENT_ID, USER_ID)).thenReturn(Collections.singletonList(exactRoute()));
        when(fixture.inboxMapper.selectByConnectionAndIdForUpdate(CONNECTION_ID, INBOX_ID))
                .thenReturn(claimedInbox().setLeaseOwner("other-worker"));

        assertEquals(SkitProviderImpressionAttributionProcessor.ProcessResult.STALE,
                fixture.processor.process(CONNECTION_ID, INBOX_ID, LEASE_OWNER));

        verifyNoInteractions(fixture.tenantIngress);
        verify(fixture.inboxMapper, never()).markAttributionSucceededCas(
                CONNECTION_ID, INBOX_ID, LEASE_OWNER, PROCESSED_AT_UTC);
    }

    @Test
    void tenantIngressRejectionQuarantinesProviderObservation() {
        Fixture fixture = new Fixture();
        when(fixture.attributionMapper.selectExactRoute(CONNECTION_ID, SESSION_ID, PACKAGE_NAME,
                PLACEMENT_ID, USER_ID)).thenReturn(Collections.singletonList(exactRoute()));
        when(fixture.tenantIngress.receiveAttributedImpression(any(), eq(RAW_QUERY),
                eq(LocalDateTime.of(2026, 8, 3, 9, 2, 3)))).thenReturn(REJECTED);
        when(fixture.inboxMapper.markAttributionQuarantinedCas(CONNECTION_ID, INBOX_ID,
                LEASE_OWNER, "TENANT_INGRESS_REJECTED", PROCESSED_AT_UTC)).thenReturn(1);

        assertEquals(SkitProviderImpressionAttributionProcessor.ProcessResult.QUARANTINED,
                fixture.processor.process(CONNECTION_ID, INBOX_ID, LEASE_OWNER));

        verify(fixture.inboxMapper).markAttributionQuarantinedCas(CONNECTION_ID, INBOX_ID,
                LEASE_OWNER, "TENANT_INGRESS_REJECTED", PROCESSED_AT_UTC);
    }

    private static SkitProviderImpressionInboxDO claimedInbox() {
        return new SkitProviderImpressionInboxDO().setId(INBOX_ID)
                .setProviderConnectionId(CONNECTION_ID).setCanonicalAttemptId(ATTEMPT_ID)
                .setDedupeScheme("OFFICIAL_V1").setIntegrityStatus("CANONICAL")
                .setIntegrityRevision(0L).setProcessingStatus("PROCESSING")
                .setLeaseOwner(LEASE_OWNER).setLeaseUntil(PROCESSED_AT_UTC.plusMinutes(1))
                .setFirstReceivedAt(RECEIVED_AT_UTC);
    }

    private static SkitProviderCallbackAttemptDO canonicalAttempt() {
        return new SkitProviderCallbackAttemptDO().setId(ATTEMPT_ID)
                .setProviderConnectionId(CONNECTION_ID).setInboxId(INBOX_ID)
                .setCorrelationId(new byte[16]).setWirePayloadHash(new byte[32])
                .setPayloadCiphertext(new byte[] {1, 2, 3}).setPayloadNonce(new byte[12])
                .setPayloadKeyId("provider-key-v1")
                .setPayloadPurpose(SkitProviderCallbackPayloadCryptoService.PURPOSE)
                .setPayloadEnvelopeVersion(
                        SkitProviderCallbackPayloadCryptoService.CURRENT_ENVELOPE_VERSION)
                .setReceivedAt(RECEIVED_AT_UTC);
    }

    private static SkitProviderImpressionTenantRoute exactRoute() {
        return new SkitProviderImpressionTenantRoute()
                .setTenantId(TENANT_ID).setAdAccountId(ACCOUNT_ID)
                .setAdSessionId(SESSION_ROW_ID).setCallbackKeyVersion(CALLBACK_KEY_VERSION);
    }

    private static final class Fixture {
        private final SkitProviderImpressionInboxMapper inboxMapper =
                mock(SkitProviderImpressionInboxMapper.class);
        private final SkitProviderCallbackAttemptMapper attemptMapper =
                mock(SkitProviderCallbackAttemptMapper.class);
        private final SkitProviderImpressionAttributionMapper attributionMapper =
                mock(SkitProviderImpressionAttributionMapper.class);
        private final SkitCallbackIngressService tenantIngress =
                mock(SkitCallbackIngressService.class);
        private final SkitProviderCallbackPayloadCryptoService crypto =
                mock(SkitProviderCallbackPayloadCryptoService.class);
        private final RecordingTransactionManager transactions = new RecordingTransactionManager();
        private final SkitProviderImpressionInboxDO claimed = claimedInbox();
        private final SkitProviderCallbackAttemptDO attempt = canonicalAttempt();
        private final SkitProviderImpressionAttributionProcessorImpl processor;

        private Fixture() {
            processor = new SkitProviderImpressionAttributionProcessorImpl(
                    inboxMapper, attemptMapper, attributionMapper, tenantIngress, crypto,
                    new TakuCallbackCanonicalizer(), transactions,
                    Clock.fixed(PROCESSED_AT_UTC.toInstant(ZoneOffset.UTC), ZoneOffset.UTC),
                    TENANT_DATABASE_ZONE);
            when(inboxMapper.selectByConnectionAndId(CONNECTION_ID, INBOX_ID))
                    .thenReturn(claimed);
            when(attemptMapper.selectCanonicalPayload(CONNECTION_ID, INBOX_ID, ATTEMPT_ID))
                    .thenReturn(attempt);
            when(crypto.decrypt(any(), any()))
                    .thenReturn(RAW_QUERY.getBytes(StandardCharsets.US_ASCII));
            when(inboxMapper.selectByConnectionAndIdForUpdate(CONNECTION_ID, INBOX_ID))
                    .thenReturn(claimed);
        }
    }

    private static final class RecordingTransactionManager implements PlatformTransactionManager {
        private final java.util.List<TransactionDefinition> definitions = new java.util.ArrayList<>();

        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            definitions.add(definition);
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
        }

        @Override
        public void rollback(TransactionStatus status) {
        }
    }
}
