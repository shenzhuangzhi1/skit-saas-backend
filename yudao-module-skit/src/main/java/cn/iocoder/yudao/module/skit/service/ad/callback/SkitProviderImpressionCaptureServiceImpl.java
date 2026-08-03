package cn.iocoder.yudao.module.skit.service.ad.callback;

import cn.iocoder.yudao.module.skit.dal.dataobject.provider.SkitProviderCallbackAttemptDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.provider.SkitProviderImpressionInboxDO;
import cn.iocoder.yudao.module.skit.dal.mysql.provider.SkitProviderCallbackAttemptMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.provider.SkitProviderImpressionInboxMapper;
import cn.iocoder.yudao.module.skit.framework.crypto.SkitProviderCallbackPayloadCryptoService;
import cn.iocoder.yudao.module.skit.framework.observability.SkitProviderImpressionCaptureObservation;
import cn.iocoder.yudao.module.skit.framework.observability.SkitProviderImpressionCaptureObservation.FormatBucket;
import cn.iocoder.yudao.module.skit.framework.observability.SkitProviderImpressionCaptureObservation.PersistenceFailure;
import cn.iocoder.yudao.module.skit.framework.observability.SkitProviderImpressionCaptureObservation.TransactionOutcome;
import cn.iocoder.yudao.module.skit.service.provider.SkitProviderConnectionService.ProviderRouteResolution;
import io.micrometer.core.instrument.Metrics;
import org.springframework.dao.DataAccessException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Objects;

@Service
public class SkitProviderImpressionCaptureServiceImpl
        implements SkitProviderImpressionCaptureService {

    private static final String OFFICIAL_SCHEME = "OFFICIAL_V1";
    private static final String FALLBACK_SCHEME = "FALLBACK_WIRE_V1";
    private static final String AUTHENTICATION_LEVEL = "UNSIGNED_PROVIDER_OBSERVATION";

    private final SkitProviderImpressionWireParser parser;
    private final SkitProviderCallbackPayloadCryptoService crypto;
    private final SkitProviderImpressionInboxMapper inboxMapper;
    private final SkitProviderCallbackAttemptMapper attemptMapper;
    private final TransactionOperations transactions;
    private final SkitProviderImpressionRetentionProperties retention;
    private final SkitProviderImpressionCaptureObservation observation;

    @Autowired
    public SkitProviderImpressionCaptureServiceImpl(
            SkitProviderImpressionWireParser parser,
            SkitProviderCallbackPayloadCryptoService crypto,
            SkitProviderImpressionInboxMapper inboxMapper,
            SkitProviderCallbackAttemptMapper attemptMapper,
            PlatformTransactionManager transactionManager,
            SkitProviderImpressionRetentionProperties retention,
            SkitProviderImpressionCaptureObservation observation) {
        this(parser, crypto, inboxMapper, attemptMapper,
                shortTransaction(Objects.requireNonNull(transactionManager, "transactionManager")),
                retention, observation);
    }

    public SkitProviderImpressionCaptureServiceImpl(
            SkitProviderImpressionWireParser parser,
            SkitProviderCallbackPayloadCryptoService crypto,
            SkitProviderImpressionInboxMapper inboxMapper,
            SkitProviderCallbackAttemptMapper attemptMapper,
            PlatformTransactionManager transactionManager,
            SkitProviderImpressionRetentionProperties retention) {
        this(parser, crypto, inboxMapper, attemptMapper, transactionManager, retention,
                defaultObservation());
    }

    public SkitProviderImpressionCaptureServiceImpl(
            SkitProviderImpressionWireParser parser,
            SkitProviderCallbackPayloadCryptoService crypto,
            SkitProviderImpressionInboxMapper inboxMapper,
            SkitProviderCallbackAttemptMapper attemptMapper,
            PlatformTransactionManager transactionManager) {
        this(parser, crypto, inboxMapper, attemptMapper,
                shortTransaction(Objects.requireNonNull(transactionManager, "transactionManager")),
                new SkitProviderImpressionRetentionProperties(), defaultObservation());
    }

    SkitProviderImpressionCaptureServiceImpl(
            SkitProviderImpressionWireParser parser,
            SkitProviderCallbackPayloadCryptoService crypto,
            SkitProviderImpressionInboxMapper inboxMapper,
            SkitProviderCallbackAttemptMapper attemptMapper,
            TransactionOperations transactions) {
        this(parser, crypto, inboxMapper, attemptMapper, transactions,
                new SkitProviderImpressionRetentionProperties(), defaultObservation());
    }

    SkitProviderImpressionCaptureServiceImpl(
            SkitProviderImpressionWireParser parser,
            SkitProviderCallbackPayloadCryptoService crypto,
            SkitProviderImpressionInboxMapper inboxMapper,
            SkitProviderCallbackAttemptMapper attemptMapper,
            TransactionOperations transactions,
            SkitProviderImpressionRetentionProperties retention) {
        this(parser, crypto, inboxMapper, attemptMapper, transactions, retention,
                defaultObservation());
    }

    SkitProviderImpressionCaptureServiceImpl(
            SkitProviderImpressionWireParser parser,
            SkitProviderCallbackPayloadCryptoService crypto,
            SkitProviderImpressionInboxMapper inboxMapper,
            SkitProviderCallbackAttemptMapper attemptMapper,
            TransactionOperations transactions,
            SkitProviderImpressionRetentionProperties retention,
            SkitProviderImpressionCaptureObservation observation) {
        this.parser = Objects.requireNonNull(parser, "parser");
        this.crypto = Objects.requireNonNull(crypto, "crypto");
        this.inboxMapper = Objects.requireNonNull(inboxMapper, "inboxMapper");
        this.attemptMapper = Objects.requireNonNull(attemptMapper, "attemptMapper");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.retention = Objects.requireNonNull(retention, "retention");
        this.observation = Objects.requireNonNull(observation, "observation");
    }

    @Override
    public CaptureDecision capture(ProviderRouteResolution route,
                                   SkitProviderImpressionWireParser.WirePayload wirePayload,
                                   ProviderIngressEvidence evidence,
                                   LocalDateTime receivedAt) {
        if (route == null || !route.isAccepting()) {
            close(wirePayload);
            close(evidence);
            return CaptureDecision.REJECT_602;
        }
        if (wirePayload == null || evidence == null || receivedAt == null) {
            close(wirePayload);
            close(evidence);
            observe(() -> observation.recordPersistenceFailure(
                    PersistenceFailure.INTERNAL, FormatBucket.UNKNOWN));
            return CaptureDecision.PERSISTENCE_FAILURE_503;
        }

        FormatBucket format = FormatBucket.UNKNOWN;
        byte[] wireBytes = null;
        byte[] wireHash = null;
        byte[] dedupeHash = null;
        byte[] materialHash = null;
        byte[] correlationId = null;
        byte[] remoteAddressHash = null;
        byte[] userAgentHash = null;
        byte[] headerFingerprint = null;
        SkitProviderCallbackPayloadCryptoService.Context cryptoContext = null;
        SkitProviderCallbackPayloadCryptoService.PayloadEnvelope envelope = null;
        SkitProviderImpressionInboxDO inbox = null;
        SkitProviderCallbackAttemptDO attempt = null;
        try {
            format = FormatBucket.fromWirePayload(wirePayload);
            long connectionId = route.getProviderConnectionId();
            if (connectionId <= 0) {
                FormatBucket safeFormat = format;
                observe(() -> observation.recordPersistenceFailure(
                        PersistenceFailure.INTERNAL, safeFormat));
                return CaptureDecision.PERSISTENCE_FAILURE_503;
            }
            wireBytes = wirePayload.getWireBytes();
            wireHash = wirePayload.getWirePayloadHash();
            dedupeHash = wirePayload.getDedupeKeyHash();
            String dedupeScheme = wirePayload.getDedupeScheme();
            if (OFFICIAL_SCHEME.equals(dedupeScheme)) {
                materialHash = wirePayload.getMaterialIntegrityHash();
            } else if (!FALLBACK_SCHEME.equals(dedupeScheme)) {
                FormatBucket safeFormat = format;
                observe(() -> observation.recordPersistenceFailure(
                        PersistenceFailure.INTERNAL, safeFormat));
                return CaptureDecision.PERSISTENCE_FAILURE_503;
            }
            correlationId = evidence.getCorrelationId();
            remoteAddressHash = evidence.getRemoteAddressHash();
            userAgentHash = evidence.getUserAgentHash();
            headerFingerprint = evidence.getRequestHeaderFingerprint();

            cryptoContext = SkitProviderCallbackPayloadCryptoService.Context
                    .providerCallbackPayload(connectionId, correlationId, wireHash,
                            SkitProviderCallbackPayloadCryptoService.CURRENT_ENVELOPE_VERSION);
            try {
                envelope = crypto.encrypt(cryptoContext, wireBytes);
            } catch (RuntimeException cryptoFailure) {
                FormatBucket safeFormat = format;
                observe(() -> observation.recordPersistenceFailure(
                        PersistenceFailure.CRYPTO, safeFormat));
                return CaptureDecision.PERSISTENCE_FAILURE_503;
            }
            inbox = proposedInbox(connectionId, wirePayload, dedupeHash, materialHash, receivedAt);
            attempt = proposedAttempt(connectionId, wirePayload, evidence, envelope,
                    wireHash, materialHash, correlationId, remoteAddressHash,
                    userAgentHash, headerFingerprint, receivedAt);
            final SkitProviderImpressionInboxDO transactionInbox = inbox;
            final SkitProviderCallbackAttemptDO transactionAttempt = attempt;
            long transactionStarted = System.nanoTime();
            final CaptureOutcome outcome;
            try {
                outcome = transactions.execute(status -> persistOneAttempt(
                        transactionInbox, transactionAttempt,
                        wirePayload.getQuarantineReason(), receivedAt));
            } catch (RuntimeException transactionFailure) {
                Duration duration = elapsed(transactionStarted);
                FormatBucket safeFormat = format;
                observe(() -> observation.recordTransactionDuration(
                        duration, TransactionOutcome.FAILED, safeFormat));
                PersistenceFailure category = transactionFailure instanceof DataAccessException
                        ? PersistenceFailure.DATABASE : PersistenceFailure.TRANSACTION;
                observe(() -> observation.recordPersistenceFailure(category, safeFormat));
                return CaptureDecision.PERSISTENCE_FAILURE_503;
            }
            Duration duration = elapsed(transactionStarted);
            FormatBucket safeFormat = format;
            if (outcome == null) {
                observe(() -> observation.recordTransactionDuration(
                        duration, TransactionOutcome.FAILED, safeFormat));
                observe(() -> observation.recordPersistenceFailure(
                        PersistenceFailure.TRANSACTION, safeFormat));
                return CaptureDecision.PERSISTENCE_FAILURE_503;
            }
            observe(() -> observation.recordTransactionDuration(
                    duration, TransactionOutcome.COMMITTED, safeFormat));
            recordCommittedOutcome(outcome, safeFormat);
            return CaptureDecision.ACK_200;
        } catch (RuntimeException exception) {
            FormatBucket safeFormat = format;
            observe(() -> observation.recordPersistenceFailure(
                    PersistenceFailure.INTERNAL, safeFormat));
            return CaptureDecision.PERSISTENCE_FAILURE_503;
        } finally {
            close(envelope);
            close(cryptoContext);
            wipeInbox(inbox);
            wipeAttempt(attempt);
            wipe(wireBytes);
            wipe(wireHash);
            wipe(dedupeHash);
            wipe(materialHash);
            wipe(correlationId);
            wipe(remoteAddressHash);
            wipe(userAgentHash);
            wipe(headerFingerprint);
            wirePayload.close();
            evidence.close();
        }
    }

    @Override
    public CaptureDecision capture(ProviderRouteResolution route, String rawQuery,
                                   ProviderIngressEvidence evidence,
                                   LocalDateTime receivedAt) {
        if (route == null || !route.isAccepting()) {
            close(evidence);
            return CaptureDecision.REJECT_602;
        }
        final SkitProviderImpressionWireParser.WirePayload wirePayload;
        try {
            wirePayload = parser.parseBounded(rawQuery);
        } catch (SkitProviderImpressionWireParser.WireBoundaryException exception) {
            close(evidence);
            return CaptureDecision.REJECT_602;
        } catch (RuntimeException exception) {
            close(evidence);
            observe(() -> observation.recordPersistenceFailure(
                    PersistenceFailure.INTERNAL, FormatBucket.UNKNOWN));
            return CaptureDecision.PERSISTENCE_FAILURE_503;
        }
        return capture(route, wirePayload, evidence, receivedAt);
    }

    private CaptureOutcome persistOneAttempt(SkitProviderImpressionInboxDO proposed,
                                             SkitProviderCallbackAttemptDO attempt,
                                             String ingressQuarantineReason,
                                             LocalDateTime receivedAt) {
        int upsertCount = inboxMapper.insertOrGetCanonical(proposed);
        if (upsertCount < 0 || upsertCount > 2 || proposed.getId() == null) {
            throw new IllegalStateException("Provider impression Inbox upsert failed");
        }
        long connectionId = proposed.getProviderConnectionId();
        long inboxId = proposed.getId();
        SkitProviderImpressionInboxDO locked = inboxMapper
                .selectByConnectionAndIdForUpdate(connectionId, inboxId);
        requireLockedIdentity(proposed, locked);

        boolean first = locked.getCanonicalAttemptId() == null;
        String deliveryStatus;
        if (FALLBACK_SCHEME.equals(proposed.getDedupeScheme())) {
            if (!first) {
                SkitProviderCallbackAttemptDO canonicalAttempt = attemptMapper
                        .selectWirePayloadHashByConnectionAndId(
                                connectionId, locked.getCanonicalAttemptId());
                byte[] canonicalWireHash = canonicalAttempt == null
                        ? null : canonicalAttempt.getWirePayloadHash();
                try {
                    if (!constantTimeEquals(
                            canonicalWireHash, attempt.getWirePayloadHash())) {
                        throw new IllegalStateException("Fallback wire hash collision");
                    }
                } finally {
                    wipe(canonicalWireHash);
                    if (canonicalAttempt != null) {
                        canonicalAttempt.setWirePayloadHash(null);
                    }
                }
            }
            deliveryStatus = "FALLBACK_QUARANTINED";
        } else if (first) {
            deliveryStatus = "CANONICAL";
        } else if (constantTimeEquals(
                locked.getMaterialIntegrityHash(), attempt.getMaterialIntegrityHash())) {
            deliveryStatus = "EQUIVALENT_DUPLICATE";
        } else {
            deliveryStatus = "PAYLOAD_CONFLICT";
        }

        attempt.setInboxId(inboxId);
        attempt.setDeliveryIntegrityStatus(deliveryStatus);
        if (attemptMapper.insert(attempt) != 1 || attempt.getId() == null) {
            throw new IllegalStateException("Provider callback Attempt insert failed");
        }
        if (first && inboxMapper.bindCanonicalAttemptCas(
                connectionId, inboxId, attempt.getId()) != 1) {
            throw new IllegalStateException("Provider canonical Attempt CAS failed");
        }
        if ("PAYLOAD_CONFLICT".equals(deliveryStatus)
                && inboxMapper.markPayloadConflictCas(connectionId, inboxId, receivedAt,
                "PAYLOAD_CONFLICT") != 1) {
            throw new IllegalStateException("Provider integrity conflict CAS failed");
        }
        if (ingressQuarantineReason != null && !"PAYLOAD_CONFLICT".equals(deliveryStatus)
                && ("PENDING".equals(locked.getProcessingStatus())
                || "PROCESSING".equals(locked.getProcessingStatus()))
                && inboxMapper.quarantineActiveCas(connectionId, inboxId,
                ingressQuarantineReason) != 1) {
            throw new IllegalStateException("Provider ingress quarantine CAS failed");
        }
        int lastReceivedCount = inboxMapper.updateLastReceivedAt(
                connectionId, inboxId, receivedAt);
        if (lastReceivedCount < 0 || lastReceivedCount > 1) {
            throw new IllegalStateException("Provider last-received update failed");
        }
        return new CaptureOutcome(deliveryStatus,
                ingressQuarantineReason != null
                        || "FALLBACK_QUARANTINED".equals(deliveryStatus)
                        || "PAYLOAD_CONFLICT".equals(deliveryStatus));
    }

    private void recordCommittedOutcome(CaptureOutcome outcome, FormatBucket format) {
        if ("EQUIVALENT_DUPLICATE".equals(outcome.deliveryStatus)) {
            observe(() -> observation.recordDuplicate(format));
        }
        if ("PAYLOAD_CONFLICT".equals(outcome.deliveryStatus)) {
            observe(() -> observation.recordConflict(format));
        }
        if ("FALLBACK_QUARANTINED".equals(outcome.deliveryStatus)) {
            observe(() -> observation.recordFallback(format));
        }
        if (outcome.quarantined) {
            observe(() -> observation.recordQuarantined(format));
        }
    }

    private static SkitProviderImpressionInboxDO proposedInbox(
            long connectionId, SkitProviderImpressionWireParser.WirePayload wirePayload,
            byte[] dedupeHash, byte[] materialHash, LocalDateTime receivedAt) {
        SkitProviderImpressionInboxDO row = new SkitProviderImpressionInboxDO();
        row.setProviderConnectionId(connectionId);
        row.setDedupeScheme(wirePayload.getDedupeScheme());
        row.setDedupeKeyHash(copy(dedupeHash));
        row.setProviderRequestIdLexical(wirePayload.getProviderRequestIdLexical());
        row.setAdsourceIdLexical(wirePayload.getAdsourceIdLexical());
        row.setMaterialIntegrityHash(copy(materialHash));
        row.setAuthenticationLevel(AUTHENTICATION_LEVEL);
        row.setIntegrityStatus("CANONICAL");
        row.setIntegrityRevision(0L);
        String quarantine = wirePayload.getQuarantineReason();
        row.setProcessingStatus(quarantine == null ? "PENDING" : "QUARANTINED");
        row.setQuarantineReason(quarantine);
        row.setProcessingAttemptCount(0);
        row.setFirstReceivedAt(receivedAt);
        row.setLastReceivedAt(receivedAt);
        return row;
    }

    private SkitProviderCallbackAttemptDO proposedAttempt(
            long connectionId, SkitProviderImpressionWireParser.WirePayload wirePayload,
            ProviderIngressEvidence evidence,
            SkitProviderCallbackPayloadCryptoService.PayloadEnvelope envelope,
            byte[] wireHash, byte[] materialHash, byte[] correlationId,
            byte[] remoteAddressHash, byte[] userAgentHash, byte[] headerFingerprint,
            LocalDateTime receivedAt) {
        SkitProviderCallbackAttemptDO row = new SkitProviderCallbackAttemptDO();
        row.setCorrelationId(copy(correlationId));
        row.setProviderConnectionId(connectionId);
        row.setDedupeScheme(wirePayload.getDedupeScheme());
        row.setWirePayloadHash(copy(wireHash));
        row.setMaterialIntegrityHash(copy(materialHash));
        row.setResponseDecision("ACK_200");
        row.setPayloadCiphertext(envelope.getCiphertext());
        row.setPayloadNonce(envelope.getNonce());
        row.setPayloadKeyId(envelope.getKeyId());
        row.setPayloadPurpose(envelope.getPurpose());
        row.setPayloadEnvelopeVersion(envelope.getEnvelopeVersion());
        row.setPayloadExpiresAt(retention.expiresAt(receivedAt));
        row.setWireSizeBytes(wirePayload.getWireSizeBytes());
        row.setParameterCount(wirePayload.getParameterCount());
        row.setRemoteAddressHash(copy(remoteAddressHash));
        row.setUserAgentHash(copy(userAgentHash));
        row.setRequestHeaderFingerprint(copy(headerFingerprint));
        row.setTraceId(evidence.getTraceId());
        row.setReceivedAt(receivedAt);
        return row;
    }

    private static void requireLockedIdentity(SkitProviderImpressionInboxDO proposed,
                                              SkitProviderImpressionInboxDO locked) {
        if (locked == null
                || !Objects.equals(proposed.getProviderConnectionId(), locked.getProviderConnectionId())
                || !Objects.equals(proposed.getDedupeScheme(), locked.getDedupeScheme())
                || !constantTimeEquals(proposed.getDedupeKeyHash(), locked.getDedupeKeyHash())) {
            throw new IllegalStateException("Provider impression Inbox identity mismatch");
        }
    }

    private static TransactionTemplate shortTransaction(PlatformTransactionManager manager) {
        TransactionTemplate template = new TransactionTemplate(manager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        template.setTimeout(1);
        return template;
    }

    private static Duration elapsed(long startedAtNanos) {
        return Duration.ofNanos(Math.max(0L, System.nanoTime() - startedAtNanos));
    }

    private static SkitProviderImpressionCaptureObservation defaultObservation() {
        return new SkitProviderImpressionCaptureObservation(Metrics.globalRegistry);
    }

    private static void observe(Runnable signal) {
        try {
            signal.run();
        } catch (RuntimeException ignored) {
            // Telemetry failure must not change a committed capture decision.
        }
    }

    private static final class CaptureOutcome {
        private final String deliveryStatus;
        private final boolean quarantined;

        private CaptureOutcome(String deliveryStatus, boolean quarantined) {
            this.deliveryStatus = deliveryStatus;
            this.quarantined = quarantined;
        }
    }

    private static boolean constantTimeEquals(byte[] first, byte[] second) {
        return first != null && second != null && java.security.MessageDigest.isEqual(first, second);
    }

    private static byte[] copy(byte[] value) {
        return value == null ? null : value.clone();
    }

    private static void wipeInbox(SkitProviderImpressionInboxDO row) {
        if (row == null) {
            return;
        }
        wipe(row.getDedupeKeyHash());
        wipe(row.getMaterialIntegrityHash());
        row.setDedupeKeyHash(null);
        row.setMaterialIntegrityHash(null);
    }

    private static void wipeAttempt(SkitProviderCallbackAttemptDO row) {
        if (row == null) {
            return;
        }
        wipe(row.getCorrelationId());
        wipe(row.getWirePayloadHash());
        wipe(row.getMaterialIntegrityHash());
        wipe(row.getPayloadCiphertext());
        wipe(row.getPayloadNonce());
        wipe(row.getRemoteAddressHash());
        wipe(row.getUserAgentHash());
        wipe(row.getRequestHeaderFingerprint());
        row.setCorrelationId(null);
        row.setWirePayloadHash(null);
        row.setMaterialIntegrityHash(null);
        row.setPayloadCiphertext(null);
        row.setPayloadNonce(null);
        row.setRemoteAddressHash(null);
        row.setUserAgentHash(null);
        row.setRequestHeaderFingerprint(null);
    }

    private static void wipe(byte[] value) {
        if (value != null) {
            Arrays.fill(value, (byte) 0);
        }
    }

    private static void close(AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception ignored) {
            // All Task 4 close implementations are non-throwing; cleanup must not change a decision.
        }
    }
}
