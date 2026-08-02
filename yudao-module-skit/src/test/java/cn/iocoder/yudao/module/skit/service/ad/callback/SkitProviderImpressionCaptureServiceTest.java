package cn.iocoder.yudao.module.skit.service.ad.callback;

import cn.iocoder.yudao.module.skit.dal.dataobject.provider.SkitProviderCallbackAttemptDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.provider.SkitProviderImpressionInboxDO;
import cn.iocoder.yudao.module.skit.dal.mysql.provider.SkitProviderCallbackAttemptMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.provider.SkitProviderImpressionInboxMapper;
import cn.iocoder.yudao.module.skit.framework.crypto.SkitProviderCallbackPayloadCryptoService;
import cn.iocoder.yudao.module.skit.service.provider.SkitProviderConnectionService.ProviderRouteResolution;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

import java.lang.reflect.Constructor;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static cn.iocoder.yudao.module.skit.service.ad.callback.SkitProviderImpressionCaptureService.CaptureDecision.ACK_200;
import static cn.iocoder.yudao.module.skit.service.ad.callback.SkitProviderImpressionCaptureService.CaptureDecision.PERSISTENCE_FAILURE_503;
import static cn.iocoder.yudao.module.skit.service.ad.callback.SkitProviderImpressionCaptureService.CaptureDecision.REJECT_602;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SkitProviderImpressionCaptureServiceTest {

    private static final String GOOD = "req_id=A&adsource_id=1&package_name=com.skit.app"
            + "&adformat=1&placement_id=p1&nw_firm_id=66&adsource_price=3.24"
            + "&currency=USD&timestamp=1783987200123&show_custom_ext=session";
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 3, 5, 30, 0);

    private SkitProviderImpressionWireParser parser;
    private SkitProviderCallbackPayloadCryptoService crypto;
    private SkitProviderImpressionInboxMapper inboxMapper;
    private SkitProviderCallbackAttemptMapper attemptMapper;
    private TransactionOperations transactions;
    private SkitProviderImpressionCaptureServiceImpl capture;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        parser = mock(SkitProviderImpressionWireParser.class);
        crypto = mock(SkitProviderCallbackPayloadCryptoService.class);
        inboxMapper = mock(SkitProviderImpressionInboxMapper.class);
        attemptMapper = mock(SkitProviderCallbackAttemptMapper.class);
        transactions = mock(TransactionOperations.class);
        doAnswer(invocation -> {
            TransactionCallback<Object> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        }).when(transactions).execute(any());
        capture = new SkitProviderImpressionCaptureServiceImpl(
                parser, crypto, inboxMapper, attemptMapper, transactions);
    }

    @Test
    void exposesExactlyTheThreeTransportDecisions() {
        assertArrayEquals(new SkitProviderImpressionCaptureService.CaptureDecision[]{
                        ACK_200, REJECT_602, PERSISTENCE_FAILURE_503},
                SkitProviderImpressionCaptureService.CaptureDecision.values());
    }

    @Test
    void validatesDefensivelyCopiesAndRedactsIngressEvidence() {
        byte[] correlation = sequence(16, 0);
        byte[] remote = sequence(32, 32);
        byte[] userAgent = sequence(32, 64);
        byte[] headers = sequence(32, 96);

        SkitProviderImpressionCaptureService.ProviderIngressEvidence evidence =
                SkitProviderImpressionCaptureService.ProviderIngressEvidence.of(
                        correlation, remote, userAgent, headers);

        assertEquals("pci-000102030405060708090a0b0c0d0e0f", evidence.getTraceId());
        assertArrayEquals(sequence(16, 0), evidence.getCorrelationId());
        assertArrayEquals(sequence(32, 32), evidence.getRemoteAddressHash());
        assertArrayEquals(sequence(32, 64), evidence.getUserAgentHash());
        assertArrayEquals(sequence(32, 96), evidence.getRequestHeaderFingerprint());
        Arrays.fill(correlation, (byte) 0);
        Arrays.fill(remote, (byte) 0);
        Arrays.fill(userAgent, (byte) 0);
        Arrays.fill(headers, (byte) 0);
        byte[] exposed = evidence.getCorrelationId();
        Arrays.fill(exposed, (byte) 0);
        assertArrayEquals(sequence(16, 0), evidence.getCorrelationId());
        assertFalse(evidence.toString().contains(Base64.getEncoder()
                .encodeToString(sequence(32, 32))));

        SkitProviderImpressionCaptureService.ProviderIngressEvidence noUserAgent =
                SkitProviderImpressionCaptureService.ProviderIngressEvidence.of(
                        sequence(16, 0), sequence(32, 32), null, sequence(32, 96));
        assertNull(noUserAgent.getUserAgentHash());
        assertThrows(IllegalArgumentException.class, () -> evidence(
                new byte[15], new byte[32], null, new byte[32]));
        assertThrows(IllegalArgumentException.class, () -> evidence(
                new byte[16], new byte[31], null, new byte[32]));
        assertThrows(IllegalArgumentException.class, () -> evidence(
                new byte[16], new byte[32], new byte[31], new byte[32]));
        assertThrows(IllegalArgumentException.class, () -> evidence(
                new byte[16], new byte[32], null, new byte[31]));
        evidence.close();
        assertTrue(evidence.isClosed());
        assertThrows(IllegalStateException.class, evidence::getRemoteAddressHash);
        noUserAgent.close();
        assertTrue(noUserAgent.isClosed());
        assertThrows(IllegalStateException.class, noUserAgent::getCorrelationId);
    }

    @Test
    void rejectsNullAndNonAcceptingRoutesBeforeParserCryptoOrPersistence() {
        assertEquals(REJECT_602, capture.capture(null, "bad raw space", null, null));
        assertEquals(REJECT_602, capture.capture(route(false), "bad raw space", null, null));

        verifyNoInteractions(parser, crypto, inboxMapper, attemptMapper, transactions);
    }

    @Test
    void onlyBoundedWireFailuresMapToReject602WithoutCryptoOrPersistence() {
        when(parser.parseBounded("bad raw space"))
                .thenThrow(new SkitProviderImpressionWireParser.WireBoundaryException("wire"));

        assertEquals(REJECT_602, capture.capture(
                route(true), "bad raw space", validEvidence(), NOW));

        verify(parser).parseBounded("bad raw space");
        verifyNoInteractions(crypto, inboxMapper, attemptMapper, transactions);
    }

    @Test
    void canonicalWireOverloadDoesNotParseAgainAndPersistsBeforeAck() {
        SkitProviderImpressionWireParser.WirePayload wire = parse(GOOD);
        List<String> order = new ArrayList<>();
        AtomicReference<SkitProviderImpressionInboxDO> canonical = new AtomicReference<>();
        AtomicReference<byte[]> persistedRemoteHash = new AtomicReference<>();
        AtomicReference<byte[]> persistedUserAgentHash = new AtomicReference<>();
        AtomicReference<byte[]> persistedHeaderFingerprint = new AtomicReference<>();
        when(crypto.encrypt(any(), any())).thenAnswer(invocation -> {
            order.add("encrypt");
            return envelope();
        });
        doAnswer(invocation -> {
            order.add("transaction-begin");
            verify(crypto).encrypt(any(), any());
            TransactionCallback<Object> callback = invocation.getArgument(0);
            Object result = callback.doInTransaction(mock(TransactionStatus.class));
            order.add("transaction-commit");
            return result;
        }).when(transactions).execute(any());
        doAnswer(invocation -> {
            SkitProviderImpressionInboxDO row = invocation.getArgument(0);
            row.setId(101L);
            canonical.set(row);
            order.add("inbox-upsert");
            return 1;
        }).when(inboxMapper).insertOrGetCanonical(any());
        when(inboxMapper.selectByConnectionAndIdForUpdate(8811L, 101L))
                .thenAnswer(invocation -> canonical.get());
        doAnswer(invocation -> {
            SkitProviderCallbackAttemptDO row = invocation.getArgument(0);
            persistedRemoteHash.set(row.getRemoteAddressHash().clone());
            persistedUserAgentHash.set(row.getUserAgentHash().clone());
            persistedHeaderFingerprint.set(row.getRequestHeaderFingerprint().clone());
            row.setId(501L);
            order.add("attempt-insert");
            return 1;
        }).when(attemptMapper).insert(any());
        when(inboxMapper.bindCanonicalAttemptCas(8811L, 101L, 501L)).thenReturn(1);
        when(inboxMapper.updateLastReceivedAt(8811L, 101L, NOW)).thenReturn(0);

        SkitProviderImpressionCaptureService.CaptureDecision decision = capture.capture(
                route(true), wire, validEvidence(), NOW);
        order.add("returned");

        assertEquals(ACK_200, decision);
        assertTrue(wire.isClosed(), "capture owns and wipes the consumed WirePayload");
        verifyNoInteractions(parser);
        assertTrue(order.indexOf("encrypt") < order.indexOf("transaction-begin"));
        assertTrue(order.indexOf("attempt-insert") < order.indexOf("transaction-commit"));
        assertTrue(order.indexOf("transaction-commit") < order.indexOf("returned"));

        ArgumentCaptor<SkitProviderImpressionInboxDO> inbox =
                ArgumentCaptor.forClass(SkitProviderImpressionInboxDO.class);
        verify(inboxMapper).insertOrGetCanonical(inbox.capture());
        assertEquals(8811L, inbox.getValue().getProviderConnectionId());
        assertEquals("OFFICIAL_V1", inbox.getValue().getDedupeScheme());
        assertEquals("UNSIGNED_PROVIDER_OBSERVATION", inbox.getValue().getAuthenticationLevel());
        assertEquals("PENDING", inbox.getValue().getProcessingStatus());
        assertEquals(NOW, inbox.getValue().getFirstReceivedAt());

        ArgumentCaptor<SkitProviderCallbackAttemptDO> attempt =
                ArgumentCaptor.forClass(SkitProviderCallbackAttemptDO.class);
        verify(attemptMapper).insert(attempt.capture());
        assertEquals("CANONICAL", attempt.getValue().getDeliveryIntegrityStatus());
        assertEquals("ACK_200", attempt.getValue().getResponseDecision());
        assertEquals("PROVIDER_CALLBACK_PAYLOAD", attempt.getValue().getPayloadPurpose());
        assertEquals(NOW.plusDays(7), attempt.getValue().getPayloadExpiresAt());
        assertEquals("pci-000102030405060708090a0b0c0d0e0f", attempt.getValue().getTraceId());
        assertArrayEquals(sequence(32, 32), persistedRemoteHash.get());
        assertArrayEquals(sequence(32, 64), persistedUserAgentHash.get());
        assertArrayEquals(sequence(32, 96), persistedHeaderFingerprint.get());
    }

    @Test
    void rawConvenienceUsesRequiredShortTransactionDefinitionAndCommitsBeforeAck() {
        List<String> order = new ArrayList<>();
        SkitProviderImpressionWireParser realParser = new SkitProviderImpressionWireParser();
        RecordingTransactionManager transactionManager = new RecordingTransactionManager(order);
        when(crypto.encrypt(any(), any())).thenAnswer(invocation -> {
            order.add("encrypt");
            return envelope();
        });
        AtomicReference<SkitProviderImpressionInboxDO> canonical = new AtomicReference<>();
        doAnswer(invocation -> {
            SkitProviderImpressionInboxDO row = invocation.getArgument(0);
            row.setId(102L);
            canonical.set(row);
            order.add("upsert");
            return 1;
        }).when(inboxMapper).insertOrGetCanonical(any());
        when(inboxMapper.selectByConnectionAndIdForUpdate(8811L, 102L))
                .thenAnswer(invocation -> canonical.get());
        doAnswer(invocation -> {
            SkitProviderCallbackAttemptDO row = invocation.getArgument(0);
            row.setId(502L);
            return 1;
        }).when(attemptMapper).insert(any());
        when(inboxMapper.bindCanonicalAttemptCas(8811L, 102L, 502L)).thenReturn(1);
        when(inboxMapper.updateLastReceivedAt(8811L, 102L, NOW)).thenReturn(1);
        SkitProviderImpressionCaptureService service = new SkitProviderImpressionCaptureServiceImpl(
                realParser, crypto, inboxMapper, attemptMapper, transactionManager);

        assertEquals(ACK_200, service.capture(route(true), GOOD, validEvidence(), NOW));
        order.add("returned");

        assertEquals(TransactionDefinition.PROPAGATION_REQUIRES_NEW,
                transactionManager.definition.getPropagationBehavior());
        assertEquals(TransactionDefinition.ISOLATION_READ_COMMITTED,
                transactionManager.definition.getIsolationLevel());
        assertEquals(1, transactionManager.definition.getTimeout());
        assertTrue(order.indexOf("encrypt") < order.indexOf("begin"));
        assertTrue(order.indexOf("commit") < order.indexOf("returned"));
    }

    @Test
    void emptyWireIsEncryptedAndPermanentlyCapturedAsFallbackQuarantine() {
        SkitProviderImpressionWireParser.WirePayload wire = parse("");
        AtomicReference<SkitProviderImpressionInboxDO> canonical = new AtomicReference<>();
        AtomicReference<byte[]> persistedDedupeHash = new AtomicReference<>();
        when(crypto.encrypt(any(), any())).thenAnswer(invocation -> {
            assertEquals(0, ((byte[]) invocation.getArgument(1)).length);
            return envelope();
        });
        doAnswer(invocation -> {
            SkitProviderImpressionInboxDO row = invocation.getArgument(0);
            persistedDedupeHash.set(row.getDedupeKeyHash().clone());
            row.setId(103L);
            canonical.set(row);
            return 1;
        }).when(inboxMapper).insertOrGetCanonical(any());
        when(inboxMapper.selectByConnectionAndIdForUpdate(8811L, 103L))
                .thenAnswer(invocation -> canonical.get());
        doAnswer(invocation -> {
            SkitProviderCallbackAttemptDO row = invocation.getArgument(0);
            row.setId(503L);
            return 1;
        }).when(attemptMapper).insert(any());
        when(inboxMapper.bindCanonicalAttemptCas(8811L, 103L, 503L)).thenReturn(1);
        when(inboxMapper.updateLastReceivedAt(8811L, 103L, NOW)).thenReturn(1);

        assertEquals(ACK_200, capture.capture(route(true), wire, validEvidence(), NOW));

        ArgumentCaptor<SkitProviderImpressionInboxDO> inbox =
                ArgumentCaptor.forClass(SkitProviderImpressionInboxDO.class);
        verify(inboxMapper).insertOrGetCanonical(inbox.capture());
        assertEquals("FALLBACK_WIRE_V1", inbox.getValue().getDedupeScheme());
        assertEquals("QUARANTINED", inbox.getValue().getProcessingStatus());
        assertEquals("OFFICIAL_FIELD_MISSING", inbox.getValue().getQuarantineReason());
        assertNotNull(persistedDedupeHash.get());
        assertNull(inbox.getValue().getMaterialIntegrityHash());

        ArgumentCaptor<SkitProviderCallbackAttemptDO> attempt =
                ArgumentCaptor.forClass(SkitProviderCallbackAttemptDO.class);
        verify(attemptMapper).insert(attempt.capture());
        assertEquals("FALLBACK_QUARANTINED", attempt.getValue().getDeliveryIntegrityStatus());
        assertEquals(0, attempt.getValue().getWireSizeBytes());
        assertEquals(0, attempt.getValue().getParameterCount());
    }

    @Test
    void sameOfficialMaterialIsEquivalentButDifferentMaterialIsAConflict() {
        byte[] canonicalMaterial;
        try (SkitProviderImpressionWireParser.WirePayload canonicalWire = parse(GOOD)) {
            canonicalMaterial = canonicalWire.getMaterialIntegrityHash();
        }
        SkitProviderImpressionInboxDO locked = lockedOfficialInbox(104L, 504L, canonicalMaterial);
        when(crypto.encrypt(any(), any())).thenAnswer(invocation -> envelope());
        doAnswer(invocation -> {
            SkitProviderImpressionInboxDO proposed = invocation.getArgument(0);
            proposed.setId(104L);
            locked.setDedupeKeyHash(proposed.getDedupeKeyHash().clone());
            return 0;
        }).when(inboxMapper).insertOrGetCanonical(any());
        when(inboxMapper.selectByConnectionAndIdForUpdate(8811L, 104L)).thenReturn(locked);
        doAnswer(invocation -> {
            SkitProviderCallbackAttemptDO attempt = invocation.getArgument(0);
            attempt.setId(700L + attempt.getReceivedAt().getSecond());
            return 1;
        }).when(attemptMapper).insert(any());
        when(inboxMapper.updateLastReceivedAt(anyLong(), anyLong(), any())).thenReturn(1);
        when(inboxMapper.markPayloadConflictCas(8811L, 104L, NOW.plusSeconds(2),
                "PAYLOAD_CONFLICT")).thenReturn(1);

        assertEquals(ACK_200, capture.capture(route(true),
                parse(GOOD + "&future=ignored"), evidenceWithCorrelation(1), NOW.plusSeconds(1)));
        assertEquals(ACK_200, capture.capture(route(true),
                parse(GOOD.replace("adsource_price=3.24", "adsource_price=3.25")),
                evidenceWithCorrelation(2), NOW.plusSeconds(2)));

        ArgumentCaptor<SkitProviderCallbackAttemptDO> attempts =
                ArgumentCaptor.forClass(SkitProviderCallbackAttemptDO.class);
        verify(attemptMapper, org.mockito.Mockito.times(2)).insert(attempts.capture());
        assertEquals("EQUIVALENT_DUPLICATE",
                attempts.getAllValues().get(0).getDeliveryIntegrityStatus());
        assertEquals("PAYLOAD_CONFLICT",
                attempts.getAllValues().get(1).getDeliveryIntegrityStatus());
        verify(inboxMapper).markPayloadConflictCas(
                8811L, 104L, NOW.plusSeconds(2), "PAYLOAD_CONFLICT");
        verify(inboxMapper, never()).bindCanonicalAttemptCas(anyLong(), anyLong(), anyLong());
    }

    @Test
    void cryptoAndTransactionFailuresNeverAckAndAlwaysWipeWire() {
        SkitProviderImpressionWireParser.WirePayload cryptoFailure = parse(GOOD);
        when(crypto.encrypt(any(), any())).thenThrow(new IllegalStateException("key unavailable"));

        assertEquals(PERSISTENCE_FAILURE_503, capture.capture(
                route(true), cryptoFailure, validEvidence(), NOW));
        assertTrue(cryptoFailure.isClosed());
        verifyNoInteractions(transactions, inboxMapper, attemptMapper);

        setUp();
        SkitProviderImpressionWireParser.WirePayload transactionFailure = parse(GOOD);
        when(crypto.encrypt(any(), any())).thenReturn(envelope());
        doThrow(new IllegalStateException("commit failed"))
                .when(transactions).execute(any());

        assertEquals(PERSISTENCE_FAILURE_503, capture.capture(
                route(true), transactionFailure, validEvidence(), NOW));
        assertTrue(transactionFailure.isClosed());
        verifyNoInteractions(inboxMapper, attemptMapper);
    }

    @Test
    void exactInsertAndCanonicalCasCountsFailClosed() {
        when(crypto.encrypt(any(), any())).thenReturn(envelope());
        AtomicReference<SkitProviderImpressionInboxDO> canonical = new AtomicReference<>();
        doAnswer(invocation -> {
            SkitProviderImpressionInboxDO row = invocation.getArgument(0);
            row.setId(105L);
            canonical.set(row);
            return 1;
        }).when(inboxMapper).insertOrGetCanonical(any());
        when(inboxMapper.selectByConnectionAndIdForUpdate(8811L, 105L))
                .thenAnswer(invocation -> canonical.get());
        when(attemptMapper.insert(any())).thenReturn(0);

        assertEquals(PERSISTENCE_FAILURE_503, capture.capture(
                route(true), parse(GOOD), validEvidence(), NOW));

        setUp();
        when(crypto.encrypt(any(), any())).thenReturn(envelope());
        AtomicReference<SkitProviderImpressionInboxDO> second = new AtomicReference<>();
        doAnswer(invocation -> {
            SkitProviderImpressionInboxDO row = invocation.getArgument(0);
            row.setId(106L);
            second.set(row);
            return 1;
        }).when(inboxMapper).insertOrGetCanonical(any());
        when(inboxMapper.selectByConnectionAndIdForUpdate(8811L, 106L))
                .thenAnswer(invocation -> second.get());
        doAnswer(invocation -> {
            SkitProviderCallbackAttemptDO row = invocation.getArgument(0);
            row.setId(506L);
            return 1;
        }).when(attemptMapper).insert(any());
        when(inboxMapper.bindCanonicalAttemptCas(8811L, 106L, 506L)).thenReturn(0);

        assertEquals(PERSISTENCE_FAILURE_503, capture.capture(
                route(true), parse(GOOD), validEvidence(), NOW));
    }

    private static SkitProviderImpressionInboxDO lockedOfficialInbox(
            long id, long canonicalAttemptId, byte[] materialHash) {
        SkitProviderImpressionInboxDO row = new SkitProviderImpressionInboxDO();
        row.setId(id);
        row.setProviderConnectionId(8811L);
        row.setDedupeScheme("OFFICIAL_V1");
        row.setCanonicalAttemptId(canonicalAttemptId);
        row.setMaterialIntegrityHash(materialHash);
        row.setIntegrityStatus("CANONICAL");
        row.setProcessingStatus("PENDING");
        return row;
    }

    private static SkitProviderImpressionWireParser.WirePayload parse(String raw) {
        return new SkitProviderImpressionWireParser().parseBounded(raw);
    }

    private static SkitProviderCallbackPayloadCryptoService.PayloadEnvelope envelope() {
        return new SkitProviderCallbackPayloadCryptoService.PayloadEnvelope(
                sequence(16, 1), sequence(12, 17), "provider-current", 1,
                "PROVIDER_CALLBACK_PAYLOAD");
    }

    private static SkitProviderImpressionCaptureService.ProviderIngressEvidence validEvidence() {
        return evidence(sequence(16, 0), sequence(32, 32),
                sequence(32, 64), sequence(32, 96));
    }

    private static SkitProviderImpressionCaptureService.ProviderIngressEvidence evidenceWithCorrelation(
            int lastByte) {
        byte[] correlation = sequence(16, 0);
        correlation[15] = (byte) lastByte;
        return evidence(correlation, sequence(32, 32), sequence(32, 64), sequence(32, 96));
    }

    private static SkitProviderImpressionCaptureService.ProviderIngressEvidence evidence(
            byte[] correlation, byte[] remote, byte[] userAgent, byte[] headers) {
        return SkitProviderImpressionCaptureService.ProviderIngressEvidence.of(
                correlation, remote, userAgent, headers);
    }

    private static ProviderRouteResolution route(boolean accepting) {
        try {
            Constructor<ProviderRouteResolution> constructor = ProviderRouteResolution.class
                    .getDeclaredConstructor(long.class, long.class, boolean.class);
            constructor.setAccessible(true);
            return constructor.newInstance(8811L, 9911L, accepting);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    private static byte[] sequence(int length, int seed) {
        byte[] result = new byte[length];
        for (int index = 0; index < length; index++) {
            result[index] = (byte) (seed + index);
        }
        return result;
    }

    private static final class RecordingTransactionManager extends AbstractPlatformTransactionManager {

        private final List<String> order;
        private TransactionDefinition definition;

        private RecordingTransactionManager(List<String> order) {
            this.order = order;
        }

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            this.definition = definition;
            order.add("begin");
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            order.add("commit");
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            order.add("rollback");
        }
    }
}
