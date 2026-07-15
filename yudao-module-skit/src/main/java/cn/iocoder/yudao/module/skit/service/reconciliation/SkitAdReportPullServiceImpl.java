package cn.iocoder.yudao.module.skit.service.reconciliation;

import cn.iocoder.yudao.framework.tenant.core.util.TenantUtils;
import cn.iocoder.yudao.module.skit.dal.dataobject.ad.SkitAdAccountDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.reconciliation.SkitAdReconciliationBucketDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.reconciliation.SkitAdReconciliationRevisionDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.reconciliation.SkitAdReconciliationEventLinkDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.reconciliation.SkitAdReportEventRouteDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.reconciliation.SkitAdReportPullDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.revenue.SkitAdRevenueEventDO;
import cn.iocoder.yudao.module.skit.dal.mysql.ad.SkitAdAccountMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.reconciliation.SkitAdReconciliationBucketMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.reconciliation.SkitAdReconciliationRevisionMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.reconciliation.SkitAdReconciliationEventLinkMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.reconciliation.SkitAdReportPullMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.revenue.SkitAdRevenueEventMapper;
import cn.iocoder.yudao.module.skit.framework.observability.SkitAdReportPullObservation;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Supplier;

@Service
@Slf4j
public class SkitAdReportPullServiceImpl implements SkitAdReportPullService {

    private static final int MAX_ACCOUNTS_PER_RUN = 3;
    private static final int LEASE_SECONDS = 900;
    static final int SUCCESS_DELAY_SECONDS = 7_200;
    static final int FAILURE_BASE_BACKOFF_SECONDS = 300;
    static final int FAILURE_MAX_BACKOFF_SECONDS = 3_600;
    private static final int FAILURE_ALERT_THRESHOLD = 3;
    private static final int MAX_HISTORICAL_DATES_PER_RUN = 2;
    private static final int HISTORICAL_EVENT_DATE_SCAN_LIMIT = 64;
    private static final int HISTORICAL_PENDING_PULL_SCAN_LIMIT = 8;
    private static final String PROVIDER = "TAKU";
    private static final String AD_FORMAT = "rewarded_video";
    private static final String MISSING_NETWORK_ACCOUNT = "__NO_REPORT_RECORD__";

    private final SkitAdAccountMapper accountMapper;
    private final SkitAdReportPullMapper pullMapper;
    private final SkitAdReconciliationBucketMapper bucketMapper;
    private final SkitAdReconciliationRevisionMapper revisionMapper;
    private final SkitAdReconciliationEventLinkMapper eventLinkMapper;
    private final SkitAdRevenueEventMapper eventMapper;
    private final SkitReportingCredentialService credentialService;
    private final TakuReportingClient reportingClient;
    private final SkitReconciliationAllocator allocator;
    private final SkitLedgerProjectionService ledgerProjectionService;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate requiresNew;
    private final Clock clock;
    private final Supplier<String> leaseOwnerSupplier;
    private final SkitAdReportPullObservation observation;

    @Autowired
    public SkitAdReportPullServiceImpl(SkitAdAccountMapper accountMapper,
                                       SkitAdReportPullMapper pullMapper,
                                       SkitAdReconciliationBucketMapper bucketMapper,
                                       SkitAdReconciliationRevisionMapper revisionMapper,
                                       SkitAdReconciliationEventLinkMapper eventLinkMapper,
                                       SkitAdRevenueEventMapper eventMapper,
                                       SkitReportingCredentialService credentialService,
                                       TakuReportingClient reportingClient,
                                       SkitReconciliationAllocator allocator,
                                       SkitLedgerProjectionService ledgerProjectionService,
                                       ObjectMapper objectMapper,
                                       PlatformTransactionManager transactionManager) {
        this(accountMapper, pullMapper, bucketMapper, revisionMapper, eventLinkMapper, eventMapper,
                credentialService, reportingClient, allocator, ledgerProjectionService,
                objectMapper, transactionManager, Clock.systemDefaultZone(),
                () -> "taku-report-" + UUID.randomUUID(), new SkitAdReportPullObservation());
    }

    SkitAdReportPullServiceImpl(SkitAdAccountMapper accountMapper,
                                SkitAdReportPullMapper pullMapper,
                                SkitAdReconciliationBucketMapper bucketMapper,
                                SkitAdReconciliationRevisionMapper revisionMapper,
                                SkitAdReconciliationEventLinkMapper eventLinkMapper,
                                SkitAdRevenueEventMapper eventMapper,
                                SkitReportingCredentialService credentialService,
                                TakuReportingClient reportingClient,
                                SkitReconciliationAllocator allocator,
                                SkitLedgerProjectionService ledgerProjectionService,
                                ObjectMapper objectMapper,
                                PlatformTransactionManager transactionManager,
                                Clock clock, Supplier<String> leaseOwnerSupplier,
                                SkitAdReportPullObservation observation) {
        this.accountMapper = Objects.requireNonNull(accountMapper, "accountMapper");
        this.pullMapper = Objects.requireNonNull(pullMapper, "pullMapper");
        this.bucketMapper = Objects.requireNonNull(bucketMapper, "bucketMapper");
        this.revisionMapper = Objects.requireNonNull(revisionMapper, "revisionMapper");
        this.eventLinkMapper = Objects.requireNonNull(eventLinkMapper, "eventLinkMapper");
        this.eventMapper = Objects.requireNonNull(eventMapper, "eventMapper");
        this.credentialService = Objects.requireNonNull(credentialService, "credentialService");
        this.reportingClient = Objects.requireNonNull(reportingClient, "reportingClient");
        this.allocator = Objects.requireNonNull(allocator, "allocator");
        this.ledgerProjectionService = Objects.requireNonNull(ledgerProjectionService,
                "ledgerProjectionService");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.requiresNew = new TransactionTemplate(
                Objects.requireNonNull(transactionManager, "transactionManager"));
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.requiresNew.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.clock = Objects.requireNonNull(clock, "clock");
        this.leaseOwnerSupplier = Objects.requireNonNull(leaseOwnerSupplier, "leaseOwnerSupplier");
        this.observation = Objects.requireNonNull(observation, "observation");
    }

    @Override
    public int pullDueAccounts() {
        if (isOfficialQuietMinute(LocalDateTime.now(clock).getMinute())) {
            return 0;
        }
        String leaseOwner = canonicalLeaseOwner(leaseOwnerSupplier.get());
        List<SkitAdAccountDO> claimed = claimDueAccounts(leaseOwner);
        for (SkitAdAccountDO route : claimed) {
            processAccount(route, leaseOwner);
        }
        return claimed.size();
    }

    private List<SkitAdAccountDO> claimDueAccounts(String leaseOwner) {
        return TenantUtils.executeIgnore(() -> requiresNew.execute(status -> {
            List<SkitAdAccountDO> candidates = accountMapper.selectDueReportRoutes(MAX_ACCOUNTS_PER_RUN);
            List<SkitAdAccountDO> result = new ArrayList<>();
            for (SkitAdAccountDO candidate : candidates) {
                validateRoute(candidate);
                int changed = accountMapper.claimReportPullLeaseCas(candidate.getTenantId(),
                        candidate.getId(), leaseOwner, LEASE_SECONDS);
                if (changed == 1) {
                    result.add(candidate);
                } else if (changed != 0) {
                    throw new IllegalStateException("Report lease CAS changed multiple accounts");
                }
            }
            return result;
        }));
    }

    private void processAccount(SkitAdAccountDO route, String leaseOwner) {
        boolean succeeded = true;
        try {
            ReportConfig config = config(route);
            LocalDate today = LocalDate.now(clock.withZone(config.offset));
            for (ProviderWindow window : scheduledWindows(route, config, today)) {
                succeeded &= processWindow(route, config, window, leaseOwner);
            }
        } catch (RuntimeException failure) {
            succeeded = false;
            log.warn("[processAccount][Taku report account failed; tenantId={}, adAccountId={}, "
                            + "exceptionType={}]", route.getTenantId(), route.getId(),
                    failure.getClass().getSimpleName());
        }
        final boolean completed = succeeded;
        TenantUtils.execute(route.getTenantId(), (Runnable) () -> requiresNew.executeWithoutResult(status -> {
            int changed = completed
                    ? accountMapper.completeReportPullLeaseCas(route.getTenantId(), route.getId(),
                    leaseOwner, SUCCESS_DELAY_SECONDS)
                    : accountMapper.failReportPullLeaseCas(route.getTenantId(), route.getId(),
                    leaseOwner, FAILURE_BASE_BACKOFF_SECONDS, FAILURE_MAX_BACKOFF_SECONDS);
            if (changed != 1) {
                throw new IllegalStateException("Report lease completion lost ownership");
            }
        }));
        if (!completed && nextFailureCount(route) >= FAILURE_ALERT_THRESHOLD) {
            observation.recordRepeatedFailureAlert();
            log.error("[processAccount][Repeated Taku report failures; tenantId={}, adAccountId={}, "
                            + "consecutiveFailures={}]", route.getTenantId(), route.getId(),
                    nextFailureCount(route));
        }
    }

    private boolean processWindow(SkitAdAccountDO route, ReportConfig config,
                                  ProviderWindow window, String leaseOwner) {
        long startedNanos = System.nanoTime();
        int credentialVersion = 0;
        String failureReason = "CREDENTIAL_FAILURE";
        try {
            SkitReportingCredentialService.Metadata metadata = TenantUtils.execute(
                    route.getTenantId(), () -> credentialService.getMetadata(
                            route.getTenantId(), route.getId()));
            if (metadata == null || !metadata.isActive() || metadata.getVersion() <= 0) {
                throw new IllegalStateException("Active reporting credential metadata is unavailable");
            }
            credentialVersion = metadata.getVersion();
            final int expectedVersion = credentialVersion;
            FetchedReport fetched = TenantUtils.execute(route.getTenantId(),
                    () -> credentialService.withActivePublisherKeyVersion(
                            route.getTenantId(), route.getId(), (version, key) -> {
                                if (version != expectedVersion) {
                                    throw new IllegalStateException(
                                            "Reporting credential changed during a leased pull");
                                }
                                try {
                                    return new FetchedReport(version, reportingClient.fetch(
                                            new TakuReportingClient.ReportRequest(window.reportDate,
                                                    window.reportDate, route.getAppId(), config.placementId,
                                                    config.adFormat, route.getReportTimezone(),
                                                    route.getReportCurrency(),
                                                    route.getReportAmountScale()), key));
                                } catch (RuntimeException upstreamFailure) {
                                    throw new UpstreamReportFailure(upstreamFailure);
                                }
                            }));
            failureReason = "PERSISTENCE_FAILURE";
            TenantUtils.execute(route.getTenantId(), (Runnable) () ->
                    requiresNew.executeWithoutResult(status -> persistReport(
                            route, config, window, fetched, leaseOwner)));
            failureReason = "CREDENTIAL_FAILURE";
            TenantUtils.execute(route.getTenantId(), (Runnable) () ->
                    credentialService.markPermissionVerified(route.getTenantId(), route.getId(),
                            fetched.credentialVersion));
            observation.recordSuccess(elapsed(startedNanos));
            return true;
        } catch (RuntimeException failure) {
            if (hasCause(failure, UpstreamReportFailure.class)) {
                failureReason = "UPSTREAM_FAILURE";
            }
            if (credentialVersion > 0) {
                appendFailedPullSafely(route, config, window, credentialVersion,
                        failureReason, leaseOwner);
            }
            observation.recordFailure(elapsed(startedNanos), failureReason);
            log.warn("[processWindow][Taku report date failed; tenantId={}, adAccountId={}, "
                            + "reportDate={}, errorCode={}, exceptionType={}]",
                    route.getTenantId(), route.getId(), window.reportDate, failureReason,
                    failure.getClass().getSimpleName());
            return false;
        }
    }

    private Duration elapsed(long startedNanos) {
        return Duration.ofNanos(Math.max(0L, System.nanoTime() - startedNanos));
    }

    private static boolean hasCause(Throwable failure, Class<? extends Throwable> type) {
        Throwable current = failure;
        for (int depth = 0; current != null && depth < 16; depth++) {
            if (type.isInstance(current)) {
                return true;
            }
            Throwable cause = current.getCause();
            if (cause == current) {
                break;
            }
            current = cause;
        }
        return false;
    }

    private void appendFailedPullSafely(SkitAdAccountDO route, ReportConfig config,
                                        ProviderWindow window, int credentialVersion,
                                        String errorCode, String leaseOwner) {
        try {
            TenantUtils.execute(route.getTenantId(), (Runnable) () ->
                    requiresNew.executeWithoutResult(status -> persistFailedPull(route, config,
                            window, credentialVersion, errorCode, leaseOwner)));
        } catch (RuntimeException persistenceFailure) {
            log.error("[appendFailedPullSafely][Failed report fact could not be appended; "
                            + "tenantId={}, adAccountId={}, reportDate={}, exceptionType={}]",
                    route.getTenantId(), route.getId(), window.reportDate,
                    persistenceFailure.getClass().getSimpleName());
        }
    }

    private void persistReport(SkitAdAccountDO route, ReportConfig config, ProviderWindow window,
                               FetchedReport fetched, String leaseOwner) {
        LocalDate reportDate = window.reportDate;
        SkitAdAccountDO locked = accountMapper.selectReportAccountForUpdate(
                route.getTenantId(), route.getId());
        validateLockedAccount(route, locked, leaseOwner);
        byte[] requestHash = SkitReportRequestScopeFingerprint.fingerprint(
                route.getTenantId(), route.getId(), route.getAppId(), config.placementId,
                config.adFormat, reportDate, route.getReportTimezone(), route.getReportCurrency(),
                route.getReportAmountScale(), fetched.credentialVersion);
        byte[] responseHash = hash(responseCanonical(fetched.response));
        LocalDateTime rangeStart = providerBoundary(reportDate, config.offset);
        LocalDateTime rangeEnd = providerBoundary(reportDate.plusDays(1), config.offset);
        SkitAdReportPullDO pull = new SkitAdReportPullDO()
                .setAdAccountId(route.getId()).setProvider(PROVIDER)
                .setRangeStart(rangeStart).setRangeEnd(rangeEnd).setReportDate(reportDate)
                .setReportTimezone(route.getReportTimezone()).setCurrency(route.getReportCurrency())
                .setAmountScale(route.getReportAmountScale()).setRequestHash(requestHash)
                .setCredentialVersion(fetched.credentialVersion).setResponseHash(responseHash)
                .setStatus("SUCCEEDED").setFinalWindow(window.finalRevision)
                .setPulledAt(LocalDateTime.now(clock));
        pull.setTenantId(route.getTenantId());
        appendCanonicalPull(pull);
        List<MergedRecord> records = merge(fetched.response, route.getReportAmountScale());
        appendMissingLocalRoutes(route, config, rangeStart, rangeEnd, reportDate, records);
        records.sort(Comparator.comparing((MergedRecord row) -> recordKey(row)));
        for (MergedRecord record : records) {
            reconcileBucket(route, pull, record, rangeStart, rangeEnd,
                    window.finalRevision);
        }
    }

    private void persistFailedPull(SkitAdAccountDO route, ReportConfig config,
                                   ProviderWindow window, int credentialVersion,
                                   String errorCode, String leaseOwner) {
        SkitAdAccountDO locked = accountMapper.selectReportAccountForUpdate(
                route.getTenantId(), route.getId());
        validateLockedAccount(route, locked, leaseOwner);
        LocalDate reportDate = window.reportDate;
        byte[] requestHash = SkitReportRequestScopeFingerprint.fingerprint(
                route.getTenantId(), route.getId(), route.getAppId(), config.placementId,
                config.adFormat, reportDate, route.getReportTimezone(), route.getReportCurrency(),
                route.getReportAmountScale(), credentialVersion);
        SkitAdReportPullDO pull = new SkitAdReportPullDO()
                .setAdAccountId(route.getId()).setProvider(PROVIDER)
                .setRangeStart(providerBoundary(reportDate, config.offset))
                .setRangeEnd(providerBoundary(reportDate.plusDays(1), config.offset))
                .setReportDate(reportDate).setReportTimezone(route.getReportTimezone())
                .setCurrency(route.getReportCurrency()).setAmountScale(route.getReportAmountScale())
                .setRequestHash(requestHash).setCredentialVersion(credentialVersion)
                .setResponseHash(hash(parts("FAILED", errorCode, leaseOwner, reportDate)))
                .setStatus("FAILED").setFinalWindow(window.finalRevision)
                .setPulledAt(LocalDateTime.now(clock)).setErrorCode(errorCode);
        pull.setTenantId(route.getTenantId());
        appendCanonicalPull(pull);
    }

    private void reconcileBucket(SkitAdAccountDO account,
                                 SkitAdReportPullDO pull, MergedRecord record,
                                 LocalDateTime rangeStart, LocalDateTime rangeEnd,
                                 boolean finalRevision) {
        String bucketKey = hex(hash(bucketCanonical(account, record)));
        SkitAdReconciliationBucketDO bucket = bucketMapper.selectIdentityForUpdate(
                account.getTenantId(), account.getId(), bucketKey, record.reportDate,
                account.getReportTimezone(), record.appId, record.placementId, record.adFormat,
                record.networkAccountId, record.networkFirmId, record.adsourceId,
                account.getReportCurrency());
        if (bucket == null) {
            SkitAdReconciliationBucketDO candidate = bucketRow(account, bucketKey, record);
            try {
                if (bucketMapper.insert(candidate) != 1) {
                    throw new IllegalStateException("Reconciliation bucket was not inserted");
                }
            } catch (DuplicateKeyException ignored) {
                // A concurrent lease winner can only converge through the complete tenant-leading key.
            }
            bucket = bucketMapper.selectIdentityForUpdate(account.getTenantId(), account.getId(),
                    bucketKey, record.reportDate, account.getReportTimezone(), record.appId,
                    record.placementId, record.adFormat, record.networkAccountId,
                    record.networkFirmId, record.adsourceId, account.getReportCurrency());
        }
        requireBucketIdentity(bucket, account, bucketKey, record);

        List<SkitAdRevenueEventDO> events = eventMapper.selectReportBucketEventsForUpdate(
                account.getTenantId(), account.getId(), record.placementId, record.networkFirmId,
                record.adsourceId, account.getReportCurrency(), account.getReportAmountScale(),
                rangeStart, rangeEnd);
        List<SkitReconciliationAllocator.EventEstimate> estimates = new ArrayList<>();
        long estimateTotal = 0L;
        for (SkitAdRevenueEventDO event : events) {
            validateMatchedEvent(event, account, record);
            estimateTotal = Math.addExact(estimateTotal, event.getEstimatedAmountUnits());
            estimates.add(new SkitReconciliationAllocator.EventEstimate(event.getId(),
                    event.getEstimatedAmountUnits(), "REWARDED".equals(event.getRewardQualificationStatus()),
                    account.getReportCurrency(), account.getReportAmountScale(), record.appId,
                    account.getReportTimezone(), record.placementId, record.adFormat,
                    record.networkFirmId, record.networkAccountId, record.adsourceId));
        }
        SkitReconciliationAllocator.Result result = allocator.allocate(
                new SkitReconciliationAllocator.Bucket(record.actualUnits, record.impressions,
                        record.dimensionsComplete, account.getReportCurrency(),
                        account.getReportAmountScale(),
                        record.appId, account.getReportTimezone(), record.placementId,
                        record.adFormat, record.networkFirmId, record.networkAccountId,
                        record.adsourceId, estimates));
        String revisionStatus = revisionStatus(result.getStatus());
        byte[] revisionHash = SkitReconciliationRevisionHasher.hash(account.getTenantId(),
                account.getId(), bucketKey, record.reportDate, record.actualUnits,
                record.impressions, account.getReportCurrency(), account.getReportAmountScale(),
                finalRevision, record.dimensionsComplete, events);
        SkitAdReconciliationRevisionDO revision = canonicalRevision(account, bucket, pull,
                record, revisionHash, revisionStatus, result, events.size(), finalRevision);
        appendEventRevisionLinks(account, bucket, revision, events, result);

        if ("SUSPENSE".equals(result.getStatus())) {
            for (SkitAdRevenueEventDO event : events) {
                if (!Objects.equals(event.getReconciliationRevisionId(), revision.getId())) {
                    if (hasPriorFormalSettlement(event)) {
                        ledgerProjectionService.project(new SkitLedgerProjectionService.ProjectionCommand(
                                account.getTenantId(), bucket.getId(), revision.getId(),
                                revision.getRevisionNo(), event, 0L));
                    }
                    int changed = eventMapper.markReportSuspenseCas(account.getTenantId(), account.getId(),
                            event.getId(), event.getCallbackInboxId(), event.getVersion(), bucket.getId(),
                            revision.getId());
                    if (changed != 1) {
                        throw new IllegalStateException("Revenue event suspense CAS lost its envelope");
                    }
                }
            }
        } else {
            Map<Long, SkitAdRevenueEventDO> byId = new LinkedHashMap<>();
            for (SkitAdRevenueEventDO event : events) {
                byId.put(event.getId(), event);
            }
            for (SkitReconciliationAllocator.EventAllocation allocation : result.getEventAllocations()) {
                SkitAdRevenueEventDO event = byId.remove(allocation.getEventId());
                if (event == null) {
                    throw new IllegalStateException("Allocator returned an event outside the locked bucket");
                }
                ledgerProjectionService.project(new SkitLedgerProjectionService.ProjectionCommand(
                        account.getTenantId(), bucket.getId(), revision.getId(),
                        revision.getRevisionNo(), event, allocation.getActualUnits()));
                if (!Objects.equals(event.getReconciliationRevisionId(), revision.getId())
                        || !Objects.equals(event.getReconciledAmountUnits(), allocation.getActualUnits())) {
                    int changed = eventMapper.markReportConfirmedCas(account.getTenantId(), account.getId(),
                            event.getId(), event.getCallbackInboxId(), event.getVersion(), bucket.getId(),
                            revision.getId(), allocation.getActualUnits());
                    if (changed != 1) {
                        throw new IllegalStateException("Revenue event reconciliation CAS lost its envelope");
                    }
                }
            }
            if (!byId.isEmpty()) {
                throw new IllegalStateException("Allocator omitted locked matched events");
            }
        }
        bucket.setEstimateUnits(estimateTotal).setReportActualUnits(record.actualUnits)
                .setReportImpressions(record.impressions == null ? 0L : record.impressions)
                .setReportImpressionsAvailable(record.impressions != null)
                .setMatchedImpressions((long) events.size())
                .setAttributableActualUnits(result.getAttributableActualUnits())
                .setSuspenseUnits(result.getSuspenseUnits()).setStatus(result.getStatus());
        if (bucketMapper.updateProjection(bucket) != 1) {
            throw new IllegalStateException("Reconciliation bucket projection was not updated");
        }
    }

    private SkitAdReconciliationRevisionDO canonicalRevision(
            SkitAdAccountDO account, SkitAdReconciliationBucketDO bucket, SkitAdReportPullDO pull,
            MergedRecord record, byte[] revisionHash, String status,
            SkitReconciliationAllocator.Result result, int matchedEventCount,
            boolean finalRevision) {
        SkitAdReconciliationRevisionDO canonical = revisionMapper.selectCanonicalForUpdate(
                account.getTenantId(), account.getId(), bucket.getBucketKey(), record.reportDate,
                revisionHash);
        if (canonical != null) {
            requireSameRevision(canonical, account, bucket, record, revisionHash, status,
                    result, matchedEventCount, finalRevision);
            return canonical;
        }
        SkitAdReconciliationRevisionDO latest = revisionMapper.selectLatestForUpdate(
                account.getTenantId(), bucket.getId());
        int revisionNo = latest == null ? 1 : Math.addExact(latest.getRevisionNo(), 1);
        SkitAdReconciliationRevisionDO candidate = new SkitAdReconciliationRevisionDO()
                .setAdAccountId(account.getId()).setReconciliationBucketId(bucket.getId())
                .setReportPullId(pull.getId()).setBucketKey(bucket.getBucketKey())
                .setReportDate(record.reportDate).setRevisionHash(revisionHash)
                .setRevisionNo(revisionNo).setTargetActualUnits(result.getAttributableActualUnits())
                .setUnmatchedActualUnits(result.getSuspenseUnits())
                .setAmountScale(account.getReportAmountScale()).setCurrency(account.getReportCurrency())
                .setFinalRevision(finalRevision).setSourceReportImpressions(
                        record.impressions == null ? 0L : record.impressions)
                .setSourceReportImpressionsAvailable(record.impressions != null)
                .setMatchedEventCount((long) matchedEventCount).setStatus(status)
                .setReconciledAt(LocalDateTime.now(clock));
        candidate.setTenantId(account.getTenantId());
        try {
            if (revisionMapper.insert(candidate) != 1) {
                throw new IllegalStateException("Reconciliation revision was not appended");
            }
        } catch (DuplicateKeyException ignored) {
            // Duplicate revision/hash or revision number is resolved only by its canonical key.
        }
        canonical = revisionMapper.selectCanonicalForUpdate(account.getTenantId(), account.getId(),
                bucket.getBucketKey(), record.reportDate, revisionHash);
        requireSameRevision(canonical, account, bucket, record, revisionHash, status,
                result, matchedEventCount, finalRevision);
        return canonical;
    }

    private void appendCanonicalPull(SkitAdReportPullDO candidate) {
        try {
            if (pullMapper.insert(candidate) != 1) {
                throw new IllegalStateException("Report pull fact was not appended");
            }
        } catch (DuplicateKeyException ignored) {
            // Plain INSERT deliberately avoids the immutable report-pull UPDATE trigger.
        }
        SkitAdReportPullDO canonical = pullMapper.selectCanonicalForUpdate(candidate.getTenantId(),
                candidate.getAdAccountId(), candidate.getRangeStart(), candidate.getRangeEnd(),
                candidate.getRequestHash(), candidate.getResponseHash(),
                candidate.getCredentialVersion(), Boolean.TRUE.equals(candidate.getFinalWindow()));
        requireSamePull(candidate, canonical);
        candidate.setId(canonical.getId());
    }

    private void appendEventRevisionLinks(SkitAdAccountDO account,
                                          SkitAdReconciliationBucketDO bucket,
                                          SkitAdReconciliationRevisionDO revision,
                                          List<SkitAdRevenueEventDO> events,
                                          SkitReconciliationAllocator.Result result) {
        boolean suspense = "SUSPENSE".equals(result.getStatus());
        Map<Long, Long> actualByEvent = new LinkedHashMap<>();
        if (!suspense) {
            for (SkitReconciliationAllocator.EventAllocation allocation
                    : result.getEventAllocations()) {
                if (actualByEvent.put(allocation.getEventId(), allocation.getActualUnits()) != null) {
                    throw new IllegalStateException("Allocator duplicated an event association");
                }
            }
        }
        for (SkitAdRevenueEventDO event : events) {
            Long actualUnits = suspense ? 0L : actualByEvent.remove(event.getId());
            if (actualUnits == null) {
                throw new IllegalStateException("Allocator omitted an immutable event association");
            }
            SkitAdReconciliationEventLinkDO candidate =
                    new SkitAdReconciliationEventLinkDO()
                            .setReconciliationBucketId(bucket.getId())
                            .setReconciliationRevisionId(revision.getId())
                            .setRevisionNo(revision.getRevisionNo())
                            .setEventId(event.getId())
                            .setPolicySnapshotId(event.getPolicySnapshotId())
                            .setAssociationStatus(suspense ? "SUSPENSE" : "ATTRIBUTED")
                            .setActualUnits(actualUnits);
            candidate.setTenantId(account.getTenantId());
            appendCanonicalEventLink(candidate);
        }
        if (!actualByEvent.isEmpty()) {
            throw new IllegalStateException("Allocator associated an event outside the locked bucket");
        }
    }

    private void appendCanonicalEventLink(SkitAdReconciliationEventLinkDO candidate) {
        try {
            if (eventLinkMapper.insert(candidate) != 1) {
                throw new IllegalStateException("Reconciliation event link was not appended");
            }
        } catch (DuplicateKeyException ignored) {
            // Exact revision/event replay converges through the immutable canonical key.
        }
        SkitAdReconciliationEventLinkDO canonical =
                eventLinkMapper.selectCanonicalForUpdate(candidate.getTenantId(),
                        candidate.getReconciliationRevisionId(), candidate.getEventId());
        requireSameEventLink(candidate, canonical);
        candidate.setId(canonical.getId());
    }

    private SkitAdReconciliationBucketDO bucketRow(SkitAdAccountDO account, String bucketKey,
                                                    MergedRecord record) {
        SkitAdReconciliationBucketDO row = new SkitAdReconciliationBucketDO()
                .setAdAccountId(account.getId()).setBucketKey(bucketKey).setReportDate(record.reportDate)
                .setReportTimezone(account.getReportTimezone()).setAppId(record.appId)
                .setPlacementId(record.placementId).setAdFormat(record.adFormat)
                .setNetworkFirmId(record.networkFirmId).setNetworkAccountId(record.networkAccountId)
                .setAdsourceId(record.adsourceId).setCurrency(account.getReportCurrency())
                .setAmountScale(account.getReportAmountScale()).setEstimateUnits(0L)
                .setReportActualUnits(record.actualUnits)
                .setReportImpressions(record.impressions == null ? 0L : record.impressions)
                .setReportImpressionsAvailable(record.impressions != null)
                .setMatchedImpressions(0L).setAttributableActualUnits(0L)
                .setSuspenseUnits(record.actualUnits).setStatus("SUSPENSE");
        row.setTenantId(account.getTenantId());
        return row;
    }

    static List<MergedRecord> merge(TakuReportingClient.ReportResponse response, int scale) {
        Map<String, MergedRecord> grouped = new LinkedHashMap<>();
        List<TakuReportingClient.ReportRecord> ordered = new ArrayList<>(response.getRecords());
        Collections.sort(ordered, reportRecordComparator());
        for (TakuReportingClient.ReportRecord record : ordered) {
            String key = recordKey(record);
            long units = amountUnits(record.getRevenue(), scale);
            MergedRecord prior = grouped.get(key);
            if (prior == null) {
                grouped.put(key, new MergedRecord(record, units));
            } else {
                prior.actualUnits = Math.addExact(prior.actualUnits, units);
                prior.impressions = addNullable(prior.impressions, record.getImpressionApi());
            }
        }
        Map<String, Set<String>> accountsByCallbackRoute = new LinkedHashMap<>();
        for (MergedRecord record : grouped.values()) {
            accountsByCallbackRoute.computeIfAbsent(callbackRouteKey(record), ignored ->
                    new LinkedHashSet<>()).add(record.networkAccountId);
        }
        for (MergedRecord record : grouped.values()) {
            record.dimensionsComplete = accountsByCallbackRoute
                    .get(callbackRouteKey(record)).size() == 1;
        }
        return new ArrayList<>(grouped.values());
    }

    private void appendMissingLocalRoutes(SkitAdAccountDO account, ReportConfig config,
                                          LocalDateTime rangeStart, LocalDateTime rangeEnd,
                                          LocalDate reportDate, List<MergedRecord> records) {
        Set<String> reported = new LinkedHashSet<>();
        for (MergedRecord record : records) {
            reported.add(callbackRouteKey(record));
        }
        Set<String> appended = new LinkedHashSet<>();
        List<SkitAdReportEventRouteDO> localRoutes = eventMapper.selectReportEventRoutesForUpdate(
                account.getTenantId(), account.getId(), config.placementId, rangeStart, rangeEnd);
        for (SkitAdReportEventRouteDO route : localRoutes) {
            validateLocalRoute(route, config);
            String key = callbackRouteKey(reportDate, account.getAppId(), route.getPlacementId(),
                    config.adFormat, route.getNetworkFirmId(), route.getAdsourceId());
            if (!reported.contains(key) && appended.add(key)) {
                records.add(MergedRecord.missingReportRecord(reportDate, account.getAppId(),
                        route.getPlacementId(), config.adFormat, route.getNetworkFirmId(),
                        route.getAdsourceId()));
            }
        }
    }

    private ReportConfig config(SkitAdAccountDO account) {
        validateRoute(account);
        if (!Arrays.asList("UTC-8", "UTC+8", "UTC+0").contains(account.getReportTimezone())
                || account.getReportCurrency() == null
                || !account.getReportCurrency().matches("[A-Z]{3}")
                || account.getReportAmountScale() == null || account.getReportAmountScale() < 0
                || account.getReportAmountScale() > 18 || account.getAppId() == null
                || account.getAppId().trim().isEmpty()) {
            throw new IllegalStateException("Taku report account money/timezone configuration is invalid");
        }
        try {
            Map<String, Object> values = objectMapper.readValue(account.getConfigData(),
                    new TypeReference<Map<String, Object>>() { });
            String placement = canonical(String.valueOf(values.get("placementId")), 128);
            String adFormat = canonical(String.valueOf(values.get("adFormat")), 32);
            if (!AD_FORMAT.equals(adFormat)) {
                throw new IllegalStateException("Taku report placement is not dedicated rewarded video");
            }
            return new ReportConfig(placement, adFormat, offset(account.getReportTimezone()));
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("Taku report placement configuration is invalid");
        }
    }

    private static void validateRoute(SkitAdAccountDO route) {
        if (route == null || route.getTenantId() == null || route.getTenantId() <= 0L
                || route.getId() == null || route.getId() <= 0L || !PROVIDER.equals(route.getProvider())
                || route.getStatus() == null || (route.getStatus() != 0 && route.getStatus() != 1)
                || normalizedFailureCount(route) < 0 || normalizedFailureCount(route) > 5) {
            throw new IllegalStateException("Taku report routing projection is invalid");
        }
    }

    private static void validateLockedAccount(SkitAdAccountDO route, SkitAdAccountDO locked,
                                              String leaseOwner) {
        if (locked == null || !Objects.equals(route.getTenantId(), locked.getTenantId())
                || !Objects.equals(route.getId(), locked.getId())
                || !Objects.equals(route.getStatus(), locked.getStatus())
                || !Objects.equals(route.getAppId(), locked.getAppId())
                || !Objects.equals(route.getConfigData(), locked.getConfigData())
                || !Objects.equals(route.getReportTimezone(), locked.getReportTimezone())
                || !Objects.equals(route.getReportCurrency(), locked.getReportCurrency())
                || !Objects.equals(route.getReportAmountScale(), locked.getReportAmountScale())
                || !Objects.equals(normalizedFailureCount(route), normalizedFailureCount(locked))
                || !Objects.equals(leaseOwner, locked.getReportPullLeaseOwner())) {
            throw new IllegalStateException("Taku report account changed after its cross-node lease was claimed");
        }
    }

    private static void validateMatchedEvent(SkitAdRevenueEventDO event, SkitAdAccountDO account,
                                             MergedRecord record) {
        if (event == null || !Objects.equals(event.getTenantId(), account.getTenantId())
                || !Objects.equals(event.getAdAccountId(), account.getId())
                || !Objects.equals(event.getPlacementId(), record.placementId)
                || !Objects.equals(event.getAdsourceId(), record.adsourceId)
                || !Objects.equals(event.getSourceCurrency(), account.getReportCurrency())
                || !Objects.equals(event.getAmountScale(), account.getReportAmountScale())
                || event.getEstimatedAmountUnits() == null || event.getEstimatedAmountUnits() < 0L
                || event.getCallbackInboxId() == null || event.getPolicySnapshotId() == null
                || event.getVersion() == null) {
            throw new IllegalStateException("Matched revenue event crossed its historical callback envelope");
        }
    }

    private static void validateLocalRoute(SkitAdReportEventRouteDO route, ReportConfig config) {
        if (route == null || !Objects.equals(route.getPlacementId(), config.placementId)
                || route.getNetworkFirmId() == null || route.getNetworkFirmId() <= 0
                || route.getAdsourceId() == null || route.getAdsourceId().isEmpty()
                || !route.getAdsourceId().equals(route.getAdsourceId().trim())) {
            throw new IllegalStateException("Local report route crossed its callback evidence envelope");
        }
    }

    private static void requireBucketIdentity(SkitAdReconciliationBucketDO row,
                                              SkitAdAccountDO account, String key,
                                              MergedRecord record) {
        if (row == null || row.getId() == null || row.getId() <= 0L
                || !Objects.equals(row.getTenantId(), account.getTenantId())
                || !Objects.equals(row.getAdAccountId(), account.getId())
                || !Objects.equals(row.getBucketKey(), key)
                || !Objects.equals(row.getReportDate(), record.reportDate)
                || !Objects.equals(row.getReportTimezone(), account.getReportTimezone())
                || !Objects.equals(row.getAppId(), record.appId)
                || !Objects.equals(row.getPlacementId(), record.placementId)
                || !Objects.equals(row.getAdFormat(), record.adFormat)
                || !Objects.equals(row.getNetworkFirmId(), record.networkFirmId)
                || !Objects.equals(row.getNetworkAccountId(), record.networkAccountId)
                || !Objects.equals(row.getAdsourceId(), record.adsourceId)
                || !Objects.equals(row.getCurrency(), account.getReportCurrency())
                || !Objects.equals(row.getAmountScale(), account.getReportAmountScale())) {
            throw new IllegalStateException("Conflicting canonical reconciliation bucket");
        }
    }

    private static void requireSamePull(SkitAdReportPullDO expected, SkitAdReportPullDO actual) {
        if (actual == null || actual.getId() == null || actual.getId() <= 0L
                || !Objects.equals(expected.getTenantId(), actual.getTenantId())
                || !Objects.equals(expected.getAdAccountId(), actual.getAdAccountId())
                || !Objects.equals(expected.getProvider(), actual.getProvider())
                || !Objects.equals(expected.getRangeStart(), actual.getRangeStart())
                || !Objects.equals(expected.getRangeEnd(), actual.getRangeEnd())
                || !Objects.equals(expected.getReportDate(), actual.getReportDate())
                || !Objects.equals(expected.getReportTimezone(), actual.getReportTimezone())
                || !Objects.equals(expected.getCurrency(), actual.getCurrency())
                || !Objects.equals(expected.getAmountScale(), actual.getAmountScale())
                || !Arrays.equals(expected.getRequestHash(), actual.getRequestHash())
                || !Objects.equals(expected.getCredentialVersion(), actual.getCredentialVersion())
                || !Arrays.equals(expected.getResponseHash(), actual.getResponseHash())
                || !Objects.equals(expected.getStatus(), actual.getStatus())
                || !Objects.equals(expected.getFinalWindow(), actual.getFinalWindow())
                || !Objects.equals(expected.getErrorCode(), actual.getErrorCode())) {
            throw new IllegalStateException("Conflicting canonical report pull fact");
        }
    }

    private static void requireSameEventLink(SkitAdReconciliationEventLinkDO expected,
                                             SkitAdReconciliationEventLinkDO actual) {
        if (actual == null || actual.getId() == null || actual.getId() <= 0L
                || !Objects.equals(expected.getTenantId(), actual.getTenantId())
                || !Objects.equals(expected.getReconciliationBucketId(),
                actual.getReconciliationBucketId())
                || !Objects.equals(expected.getReconciliationRevisionId(),
                actual.getReconciliationRevisionId())
                || !Objects.equals(expected.getRevisionNo(), actual.getRevisionNo())
                || !Objects.equals(expected.getEventId(), actual.getEventId())
                || !Objects.equals(expected.getPolicySnapshotId(), actual.getPolicySnapshotId())
                || !Objects.equals(expected.getAssociationStatus(), actual.getAssociationStatus())
                || !Objects.equals(expected.getActualUnits(), actual.getActualUnits())) {
            throw new IllegalStateException("Conflicting canonical reconciliation event link");
        }
    }

    private static void requireSameRevision(SkitAdReconciliationRevisionDO actual,
                                            SkitAdAccountDO account,
                                            SkitAdReconciliationBucketDO bucket,
                                            MergedRecord record, byte[] revisionHash,
                                            String status, SkitReconciliationAllocator.Result result,
                                            int matchedEventCount, boolean finalRevision) {
        if (actual == null || actual.getId() == null || actual.getId() <= 0L
                || actual.getRevisionNo() == null || actual.getRevisionNo() <= 0
                || !Objects.equals(actual.getTenantId(), account.getTenantId())
                || !Objects.equals(actual.getAdAccountId(), account.getId())
                || !Objects.equals(actual.getReconciliationBucketId(), bucket.getId())
                || !Objects.equals(actual.getBucketKey(), bucket.getBucketKey())
                || !Objects.equals(actual.getReportDate(), record.reportDate)
                || !Arrays.equals(actual.getRevisionHash(), revisionHash)
                || !Objects.equals(actual.getTargetActualUnits(), result.getAttributableActualUnits())
                || !Objects.equals(actual.getUnmatchedActualUnits(), result.getSuspenseUnits())
                || !Objects.equals(actual.getAmountScale(), account.getReportAmountScale())
                || !Objects.equals(actual.getCurrency(), account.getReportCurrency())
                || !Objects.equals(actual.getFinalRevision(), finalRevision)
                || !Objects.equals(actual.getSourceReportImpressions(),
                record.impressions == null ? 0L : record.impressions)
                || !Objects.equals(actual.getSourceReportImpressionsAvailable(),
                record.impressions != null)
                || !Objects.equals(actual.getMatchedEventCount(), (long) matchedEventCount)
                || !Objects.equals(actual.getStatus(), status)) {
            throw new IllegalStateException("Conflicting canonical reconciliation revision");
        }
    }

    private static boolean isOfficialQuietMinute(int minute) {
        return minute == 0 || minute == 30 || (minute >= 20 && minute <= 25);
    }

    private static int normalizedFailureCount(SkitAdAccountDO account) {
        return account.getReportFailureCount() == null ? 0 : account.getReportFailureCount();
    }

    private static int nextFailureCount(SkitAdAccountDO account) {
        return Math.min(5, Math.addExact(normalizedFailureCount(account), 1));
    }

    private List<ProviderWindow> scheduledWindows(SkitAdAccountDO account,
                                                  ReportConfig config,
                                                  LocalDate providerToday) {
        LocalDate firstRegularFinalDate = providerToday.minusDays(3);
        List<LocalDate> historical = TenantUtils.execute(account.getTenantId(), () ->
                requiresNew.execute(status -> {
                    List<LocalDateTime> eventTimes =
                            eventMapper.selectHistoricalPendingEventTimes(account.getTenantId(),
                                    account.getId(), providerBoundary(firstRegularFinalDate,
                                            config.offset), HISTORICAL_EVENT_DATE_SCAN_LIMIT);
                    List<LocalDate> pendingPullDates = pullMapper.selectPendingFinalReportDates(
                            account.getTenantId(), account.getId(), firstRegularFinalDate,
                            account.getReportTimezone(), account.getReportCurrency(),
                            account.getReportAmountScale(), HISTORICAL_PENDING_PULL_SCAN_LIMIT);
                    TreeSet<LocalDate> dates = new TreeSet<>();
                    if (eventTimes != null) {
                        for (LocalDateTime eventTime : eventTimes) {
                            if (eventTime != null) {
                                LocalDate providerDate = eventTime.atZone(clock.getZone())
                                        .withZoneSameInstant(config.offset).toLocalDate();
                                if (providerDate.isBefore(firstRegularFinalDate)) {
                                    dates.add(providerDate);
                                }
                            }
                        }
                    }
                    if (pendingPullDates != null) {
                        for (LocalDate pendingDate : pendingPullDates) {
                            if (pendingDate != null && pendingDate.isBefore(firstRegularFinalDate)) {
                                dates.add(pendingDate);
                            }
                        }
                    }
                    List<LocalDate> bounded = new ArrayList<>();
                    for (LocalDate date : dates) {
                        if (bounded.size() >= MAX_HISTORICAL_DATES_PER_RUN) {
                            break;
                        }
                        bounded.add(date);
                    }
                    return bounded;
                }));
        List<ProviderWindow> result = new ArrayList<>();
        Set<LocalDate> appended = new LinkedHashSet<>();
        if (historical != null) {
            for (LocalDate date : historical) {
                if (appended.add(date)) {
                    result.add(new ProviderWindow(date, true));
                }
            }
        }
        for (ProviderWindow regular : providerWindows(providerToday, account.getStatus())) {
            if (appended.add(regular.reportDate)) {
                result.add(regular);
            }
        }
        return Collections.unmodifiableList(result);
    }

    static List<ProviderWindow> providerWindows(LocalDate providerToday) {
        Objects.requireNonNull(providerToday, "providerToday");
        return Collections.unmodifiableList(Arrays.asList(
                new ProviderWindow(providerToday.minusDays(1), false),
                new ProviderWindow(providerToday.minusDays(2), false),
                new ProviderWindow(providerToday.minusDays(3), true)));
    }

    static List<ProviderWindow> providerWindows(LocalDate providerToday, Integer accountStatus) {
        if (Objects.equals(accountStatus, 0)) {
            return providerWindows(providerToday);
        }
        if (Objects.equals(accountStatus, 1)) {
            // A disabled account may finish facts already observed while active, but must not
            // create a fresh rolling D+1/D+2 backlog. Waiting for D+3 closes each old date once.
            return Collections.singletonList(new ProviderWindow(
                    Objects.requireNonNull(providerToday, "providerToday").minusDays(3), true));
        }
        throw new IllegalStateException("Taku report account status is invalid");
    }

    static String revisionStatus(String allocationStatus) {
        if ("RECONCILED".equals(allocationStatus)) {
            return "APPLIED";
        }
        if ("PARTIAL".equals(allocationStatus) || "SUSPENSE".equals(allocationStatus)) {
            return allocationStatus;
        }
        throw new IllegalStateException("Unknown reconciliation allocation status");
    }

    static boolean hasPriorFormalSettlement(SkitAdRevenueEventDO event) {
        return event != null && event.getReconciliationBucketId() != null
                && event.getReconciliationRevisionId() != null
                && event.getReconciledAmountUnits() != null
                && "REPORT_CONFIRMED".equals(event.getSourceVerificationStatus())
                && "RECONCILED".equals(event.getReconciliationStatus());
    }

    private static String canonicalLeaseOwner(String owner) {
        if (owner == null || owner.isEmpty() || owner.length() > 64) {
            throw new IllegalStateException("Taku report lease owner is invalid");
        }
        return owner;
    }

    private static String canonical(String value, int maximum) {
        if (value == null || value.isEmpty() || value.length() > maximum
                || !value.equals(value.trim()) || "null".equals(value)) {
            throw new IllegalStateException("Taku report configuration value is not canonical");
        }
        return value;
    }

    private static ZoneOffset offset(String timezone) {
        if ("UTC+8".equals(timezone)) {
            return ZoneOffset.ofHours(8);
        }
        if ("UTC-8".equals(timezone)) {
            return ZoneOffset.ofHours(-8);
        }
        if ("UTC+0".equals(timezone)) {
            return ZoneOffset.UTC;
        }
        throw new IllegalStateException("Unsupported Taku report timezone");
    }

    private LocalDateTime providerBoundary(LocalDate date, ZoneOffset offset) {
        return LocalDateTime.ofInstant(date.atStartOfDay(offset).toInstant(), clock.getZone());
    }

    private static long amountUnits(String value, int scale) {
        try {
            return new BigDecimal(value).movePointRight(scale)
                    .setScale(0, RoundingMode.UNNECESSARY).longValueExact();
        } catch (ArithmeticException | NumberFormatException exception) {
            throw new IllegalStateException("Taku report revenue cannot be represented at account scale");
        }
    }

    private static Long addNullable(Long left, Long right) {
        if (left == null || right == null) {
            return null;
        }
        return Math.addExact(left, right);
    }

    private static byte[] hash(String canonical) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String hex(byte[] value) {
        StringBuilder result = new StringBuilder(value.length * 2);
        for (byte item : value) {
            result.append(String.format("%02x", item & 0xff));
        }
        return result.toString();
    }

    private static String responseCanonical(TakuReportingClient.ReportResponse response) {
        List<TakuReportingClient.ReportRecord> records = new ArrayList<>(response.getRecords());
        Collections.sort(records, reportRecordComparator());
        StringBuilder result = new StringBuilder(parts(response.getReportTimezone(), response.getCurrency()));
        for (TakuReportingClient.ReportRecord record : records) {
            result.append(parts(record.getReportDate(), record.getAppId(), record.getPlacementId(),
                    record.getAdFormat(), record.getNetworkFirmId(), record.getNetworkAccountId(),
                    record.getAdsourceId(), record.getRevenue(), record.getImpressionApi()));
        }
        return result.toString();
    }

    private static String bucketCanonical(SkitAdAccountDO account, MergedRecord record) {
        return parts(account.getTenantId(), account.getId(), record.reportDate,
                account.getReportTimezone(), record.appId, record.placementId, record.adFormat,
                record.networkFirmId, record.networkAccountId, record.adsourceId,
                account.getReportCurrency(), account.getReportAmountScale());
    }

    private static String recordKey(TakuReportingClient.ReportRecord record) {
        return parts(record.getReportDate(), record.getAppId(), record.getPlacementId(),
                record.getAdFormat(), record.getNetworkFirmId(), record.getNetworkAccountId(),
                record.getAdsourceId());
    }

    private static String recordKey(MergedRecord record) {
        return parts(callbackRouteKey(record), record.networkAccountId);
    }

    private static String callbackRouteKey(MergedRecord record) {
        return callbackRouteKey(record.reportDate, record.appId, record.placementId,
                record.adFormat, record.networkFirmId, record.adsourceId);
    }

    private static String callbackRouteKey(LocalDate reportDate, String appId, String placementId,
                                           String adFormat, int networkFirmId, String adsourceId) {
        return parts(reportDate, appId, placementId, adFormat, networkFirmId, adsourceId);
    }

    private static Comparator<TakuReportingClient.ReportRecord> reportRecordComparator() {
        return Comparator.comparing((TakuReportingClient.ReportRecord row) -> recordKey(row));
    }

    private static String parts(Object... values) {
        StringBuilder result = new StringBuilder();
        for (Object value : values) {
            String text = String.valueOf(value);
            result.append(text.length()).append(':').append(text);
        }
        return result.toString();
    }

    private static final class FetchedReport {
        private final int credentialVersion;
        private final TakuReportingClient.ReportResponse response;

        private FetchedReport(int credentialVersion, TakuReportingClient.ReportResponse response) {
            this.credentialVersion = credentialVersion;
            this.response = Objects.requireNonNull(response, "response");
        }
    }

    private static final class UpstreamReportFailure extends RuntimeException {
        private UpstreamReportFailure(RuntimeException cause) {
            super("Taku reporting upstream request failed", cause);
        }
    }

    private static final class ReportConfig {
        private final String placementId;
        private final String adFormat;
        private final ZoneOffset offset;

        private ReportConfig(String placementId, String adFormat, ZoneOffset offset) {
            this.placementId = placementId;
            this.adFormat = adFormat;
            this.offset = offset;
        }
    }

    static final class ProviderWindow {
        private final LocalDate reportDate;
        private final boolean finalRevision;

        private ProviderWindow(LocalDate reportDate, boolean finalRevision) {
            this.reportDate = reportDate;
            this.finalRevision = finalRevision;
        }

        LocalDate getReportDate() { return reportDate; }
        boolean isFinalRevision() { return finalRevision; }
    }

    static final class MergedRecord {
        private final LocalDate reportDate;
        private final String appId;
        private final String placementId;
        private final String adFormat;
        private final int networkFirmId;
        private final String networkAccountId;
        private final String adsourceId;
        private long actualUnits;
        private Long impressions;
        private boolean dimensionsComplete = true;

        private MergedRecord(TakuReportingClient.ReportRecord record, long actualUnits) {
            this.reportDate = record.getReportDate();
            this.appId = record.getAppId();
            this.placementId = record.getPlacementId();
            this.adFormat = record.getAdFormat();
            this.networkFirmId = record.getNetworkFirmId();
            this.networkAccountId = record.getNetworkAccountId();
            this.adsourceId = record.getAdsourceId();
            this.actualUnits = actualUnits;
            this.impressions = record.getImpressionApi();
        }

        private MergedRecord(LocalDate reportDate, String appId, String placementId,
                             String adFormat, int networkFirmId, String networkAccountId,
                             String adsourceId, long actualUnits, Long impressions,
                             boolean dimensionsComplete) {
            this.reportDate = reportDate;
            this.appId = appId;
            this.placementId = placementId;
            this.adFormat = adFormat;
            this.networkFirmId = networkFirmId;
            this.networkAccountId = networkAccountId;
            this.adsourceId = adsourceId;
            this.actualUnits = actualUnits;
            this.impressions = impressions;
            this.dimensionsComplete = dimensionsComplete;
        }

        private static MergedRecord missingReportRecord(LocalDate reportDate, String appId,
                                                        String placementId, String adFormat,
                                                        int networkFirmId, String adsourceId) {
            return new MergedRecord(reportDate, appId, placementId, adFormat, networkFirmId,
                    MISSING_NETWORK_ACCOUNT, adsourceId, 0L, null, false);
        }

        String getNetworkAccountId() { return networkAccountId; }
        long getActualUnits() { return actualUnits; }
        boolean isDimensionsComplete() { return dimensionsComplete; }
    }

}
