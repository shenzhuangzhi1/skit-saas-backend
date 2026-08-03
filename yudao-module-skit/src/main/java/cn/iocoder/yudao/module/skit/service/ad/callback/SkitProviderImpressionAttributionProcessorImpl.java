package cn.iocoder.yudao.module.skit.service.ad.callback;

import cn.iocoder.yudao.framework.tenant.core.util.TenantUtils;
import cn.iocoder.yudao.module.skit.dal.dataobject.provider.SkitProviderCallbackAttemptDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.provider.SkitProviderImpressionInboxDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.provider.SkitProviderImpressionTenantRoute;
import cn.iocoder.yudao.module.skit.dal.mysql.provider.SkitProviderCallbackAttemptMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.provider.SkitProviderImpressionAttributionMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.provider.SkitProviderImpressionInboxMapper;
import cn.iocoder.yudao.module.skit.framework.crypto.SkitProviderCallbackPayloadCryptoService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import static cn.iocoder.yudao.module.skit.service.ad.callback.SkitCallbackIngressService.IngressResponse.OK;

@Service
public class SkitProviderImpressionAttributionProcessorImpl
        implements SkitProviderImpressionAttributionProcessor {

    private static final String ROUTE_UNMATCHED = "ATTRIBUTION_ROUTE_UNMATCHED";
    private static final String PAYLOAD_INVALID = "ATTRIBUTION_PAYLOAD_INVALID";
    private static final String TENANT_INGRESS_REJECTED = "TENANT_INGRESS_REJECTED";

    private final SkitProviderImpressionInboxMapper inboxMapper;
    private final SkitProviderCallbackAttemptMapper attemptMapper;
    private final SkitProviderImpressionAttributionMapper attributionMapper;
    private final SkitCallbackIngressService tenantIngress;
    private final SkitProviderCallbackPayloadCryptoService crypto;
    private final TakuCallbackCanonicalizer canonicalizer;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final ZoneId tenantDatabaseZone;

    @Autowired
    public SkitProviderImpressionAttributionProcessorImpl(
            SkitProviderImpressionInboxMapper inboxMapper,
            SkitProviderCallbackAttemptMapper attemptMapper,
            SkitProviderImpressionAttributionMapper attributionMapper,
            SkitCallbackIngressService tenantIngress,
            SkitProviderCallbackPayloadCryptoService crypto,
            TakuCallbackCanonicalizer canonicalizer,
            PlatformTransactionManager transactionManager) {
        this(inboxMapper, attemptMapper, attributionMapper, tenantIngress, crypto, canonicalizer,
                transactionManager, Clock.systemUTC(), ZoneId.systemDefault());
    }

    SkitProviderImpressionAttributionProcessorImpl(
            SkitProviderImpressionInboxMapper inboxMapper,
            SkitProviderCallbackAttemptMapper attemptMapper,
            SkitProviderImpressionAttributionMapper attributionMapper,
            SkitCallbackIngressService tenantIngress,
            SkitProviderCallbackPayloadCryptoService crypto,
            TakuCallbackCanonicalizer canonicalizer,
            PlatformTransactionManager transactionManager,
            Clock clock,
            ZoneId tenantDatabaseZone) {
        this.inboxMapper = Objects.requireNonNull(inboxMapper, "inboxMapper");
        this.attemptMapper = Objects.requireNonNull(attemptMapper, "attemptMapper");
        this.attributionMapper = Objects.requireNonNull(attributionMapper, "attributionMapper");
        this.tenantIngress = Objects.requireNonNull(tenantIngress, "tenantIngress");
        this.crypto = Objects.requireNonNull(crypto, "crypto");
        this.canonicalizer = Objects.requireNonNull(canonicalizer, "canonicalizer");
        this.transactions = new TransactionTemplate(
                Objects.requireNonNull(transactionManager, "transactionManager"));
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.clock = Objects.requireNonNull(clock, "clock");
        this.tenantDatabaseZone = Objects.requireNonNull(tenantDatabaseZone, "tenantDatabaseZone");
    }

    @Override
    public ProcessResult process(long providerConnectionId, long inboxId, String leaseOwner) {
        if (providerConnectionId <= 0 || inboxId <= 0 || leaseOwner == null
                || leaseOwner.isEmpty() || leaseOwner.length() > 64) {
            throw new IllegalArgumentException("Provider attribution claim is invalid");
        }
        SkitProviderImpressionInboxDO claimed = TenantUtils.executeIgnore(
                () -> inboxMapper.selectByConnectionAndId(providerConnectionId, inboxId));
        if (!activeClaim(claimed, providerConnectionId, inboxId, leaseOwner)) {
            return ProcessResult.STALE;
        }
        SkitProviderCallbackAttemptDO attempt = TenantUtils.executeIgnore(
                () -> attemptMapper.selectCanonicalPayload(providerConnectionId, inboxId,
                        claimed.getCanonicalAttemptId()));
        if (!canonicalAttempt(attempt, claimed)) {
            throw new IllegalStateException("Provider canonical attempt is unavailable");
        }

        byte[] plaintext = null;
        try {
            plaintext = decrypt(attempt);
            String rawQuery = new String(plaintext, StandardCharsets.US_ASCII);
            final TakuImpressionCallback callback;
            try {
                callback = canonicalizer.canonicalizeImpression(rawQuery);
            } catch (TakuCallbackCanonicalizer.CallbackParseException invalid) {
                return quarantine(providerConnectionId, inboxId, leaseOwner, PAYLOAD_INVALID);
            }
            return publish(providerConnectionId, inboxId, leaseOwner, claimed,
                    callback, rawQuery);
        } finally {
            wipe(plaintext);
            wipeAttempt(attempt);
        }
    }

    private ProcessResult publish(long connectionId, long inboxId, String leaseOwner,
                                  SkitProviderImpressionInboxDO claimed,
                                  TakuImpressionCallback callback,
                                  String rawQuery) {
        LocalDateTime tenantReceivedAt = LocalDateTime.ofInstant(
                claimed.getFirstReceivedAt().toInstant(ZoneOffset.UTC), tenantDatabaseZone);
        return TenantUtils.executeIgnore(() -> transactions.execute(status -> {
            SkitProviderImpressionInboxDO locked =
                    inboxMapper.selectByConnectionAndIdForUpdate(connectionId, inboxId);
            if (!sameActiveClaim(claimed, locked, connectionId, inboxId, leaseOwner)) {
                return ProcessResult.STALE;
            }
            List<SkitProviderImpressionTenantRoute> routes =
                    attributionMapper.selectExactRoute(connectionId,
                            callback.getShowCustomExt(), callback.getPackageName(),
                            callback.getPlacementId(), callback.getUserId());
            LocalDateTime processedAt = now();
            if (routes == null || routes.size() != 1 || !validRoute(routes.get(0))) {
                requireOne(inboxMapper.markAttributionQuarantinedCas(connectionId, inboxId,
                        leaseOwner, ROUTE_UNMATCHED, processedAt),
                        "Provider attribution route quarantine CAS failed");
                return ProcessResult.QUARANTINED;
            }
            SkitProviderImpressionTenantRoute resolved = routes.get(0);
            SkitCallbackRoutingService.CallbackRoute route =
                    new SkitCallbackRoutingService.CallbackRoute(
                            resolved.getTenantId(), resolved.getAdAccountId(),
                            resolved.getCallbackKeyVersion(), true, null);
            SkitCallbackIngressService.IngressResponse response = TenantUtils.execute(
                    resolved.getTenantId(), () -> tenantIngress.receiveAttributedImpression(
                            route, rawQuery, tenantReceivedAt));
            if (response == OK) {
                requireOne(inboxMapper.markAttributionSucceededCas(
                        connectionId, inboxId, leaseOwner, processedAt),
                        "Provider attribution success CAS failed");
                return ProcessResult.SUCCEEDED;
            }
            requireOne(inboxMapper.markAttributionQuarantinedCas(connectionId, inboxId,
                    leaseOwner, TENANT_INGRESS_REJECTED, processedAt),
                    "Provider attribution quarantine CAS failed");
            return ProcessResult.QUARANTINED;
        }));
    }

    private ProcessResult quarantine(long connectionId, long inboxId, String leaseOwner,
                                     String reason) {
        return TenantUtils.executeIgnore(() -> transactions.execute(status -> {
            int changed = inboxMapper.markAttributionQuarantinedCas(
                    connectionId, inboxId, leaseOwner, reason, now());
            if (changed == 0) {
                return ProcessResult.STALE;
            }
            requireOne(changed, "Provider attribution quarantine CAS changed multiple rows");
            return ProcessResult.QUARANTINED;
        }));
    }

    private byte[] decrypt(SkitProviderCallbackAttemptDO attempt) {
        try (SkitProviderCallbackPayloadCryptoService.Context context =
                     SkitProviderCallbackPayloadCryptoService.Context.providerCallbackPayload(
                             attempt.getProviderConnectionId(), attempt.getCorrelationId(),
                             attempt.getWirePayloadHash(), attempt.getPayloadEnvelopeVersion());
             SkitProviderCallbackPayloadCryptoService.PayloadEnvelope envelope =
                     new SkitProviderCallbackPayloadCryptoService.PayloadEnvelope(
                             attempt.getPayloadCiphertext(), attempt.getPayloadNonce(),
                             attempt.getPayloadKeyId(), attempt.getPayloadEnvelopeVersion(),
                             attempt.getPayloadPurpose())) {
            return crypto.decrypt(context, envelope);
        }
    }

    private static boolean activeClaim(SkitProviderImpressionInboxDO row, long connectionId,
                                       long inboxId, String leaseOwner) {
        return row != null && Objects.equals(row.getProviderConnectionId(), connectionId)
                && Objects.equals(row.getId(), inboxId) && row.getCanonicalAttemptId() != null
                && row.getCanonicalAttemptId() > 0 && "OFFICIAL_V1".equals(row.getDedupeScheme())
                && "CANONICAL".equals(row.getIntegrityStatus())
                && Objects.equals(row.getIntegrityRevision(), 0L)
                && "PROCESSING".equals(row.getProcessingStatus())
                && Objects.equals(row.getLeaseOwner(), leaseOwner)
                && row.getFirstReceivedAt() != null;
    }

    private static boolean sameActiveClaim(SkitProviderImpressionInboxDO expected,
                                           SkitProviderImpressionInboxDO actual,
                                           long connectionId, long inboxId, String leaseOwner) {
        return activeClaim(actual, connectionId, inboxId, leaseOwner)
                && Objects.equals(expected.getCanonicalAttemptId(), actual.getCanonicalAttemptId())
                && Objects.equals(expected.getIntegrityRevision(), actual.getIntegrityRevision());
    }

    private static boolean canonicalAttempt(SkitProviderCallbackAttemptDO attempt,
                                            SkitProviderImpressionInboxDO inbox) {
        return attempt != null && Objects.equals(attempt.getId(), inbox.getCanonicalAttemptId())
                && Objects.equals(attempt.getProviderConnectionId(), inbox.getProviderConnectionId())
                && Objects.equals(attempt.getInboxId(), inbox.getId())
                && attempt.getCorrelationId() != null && attempt.getWirePayloadHash() != null
                && attempt.getPayloadCiphertext() != null && attempt.getPayloadNonce() != null
                && attempt.getPayloadKeyId() != null && attempt.getPayloadPurpose() != null
                && attempt.getPayloadEnvelopeVersion() != null;
    }

    private static boolean validRoute(SkitProviderImpressionTenantRoute route) {
        return route != null && route.getTenantId() != null && route.getTenantId() > 0
                && route.getAdAccountId() != null && route.getAdAccountId() > 0
                && route.getAdSessionId() != null && route.getAdSessionId() > 0
                && route.getCallbackKeyVersion() != null && route.getCallbackKeyVersion() > 0;
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).withNano(0);
    }

    private static void requireOne(int changed, String message) {
        if (changed != 1) {
            throw new IllegalStateException(message);
        }
    }

    private static void wipeAttempt(SkitProviderCallbackAttemptDO attempt) {
        if (attempt == null) {
            return;
        }
        wipe(attempt.getCorrelationId());
        wipe(attempt.getWirePayloadHash());
        wipe(attempt.getMaterialIntegrityHash());
        wipe(attempt.getPayloadCiphertext());
        wipe(attempt.getPayloadNonce());
    }

    private static void wipe(byte[] value) {
        if (value != null) {
            Arrays.fill(value, (byte) 0);
        }
    }
}
