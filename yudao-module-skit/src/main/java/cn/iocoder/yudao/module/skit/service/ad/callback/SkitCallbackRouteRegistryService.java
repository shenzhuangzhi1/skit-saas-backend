package cn.iocoder.yudao.module.skit.service.ad.callback;

import cn.iocoder.yudao.framework.tenant.core.util.TenantUtils;
import cn.iocoder.yudao.module.skit.dal.dataobject.ad.SkitAdCallbackKeyDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.provider.SkitAdCallbackRouteRegistryDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.provider.SkitAdCallbackRouteRegistryMigrationDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.provider.SkitAdCallbackRouteRegistryVerificationRow;
import cn.iocoder.yudao.module.skit.dal.mysql.ad.SkitAdCallbackKeyMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.provider.SkitAdCallbackRouteRegistryMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.provider.SkitAdCallbackRouteRegistryMigrationMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Owns the one global callback-key namespace and the durable tenant-key cutover. Raw callback keys
 * never enter this service.
 */
@Service
public class SkitCallbackRouteRegistryService {

  static final int BACKFILL_BATCH_SIZE = 200;
  static final String SHADOW_MISMATCH_METRIC = "skit.callback.registry.shadow.mismatch";

  private final SkitAdCallbackRouteRegistryMapper registryMapper;
  private final SkitAdCallbackRouteRegistryMigrationMapper migrationMapper;
  private final SkitAdCallbackKeyMapper legacyMapper;
  private final MeterRegistry meterRegistry;
  private final TransactionOperations migrationTransactions;
  private final Clock clock;
  private final Object tenantMutationResourceKey = new Object();

  @Autowired
  public SkitCallbackRouteRegistryService(
      SkitAdCallbackRouteRegistryMapper registryMapper,
      SkitAdCallbackRouteRegistryMigrationMapper migrationMapper,
      SkitAdCallbackKeyMapper legacyMapper,
      MeterRegistry meterRegistry,
      PlatformTransactionManager transactionManager) {
    TransactionTemplate transactions =
        new TransactionTemplate(Objects.requireNonNull(transactionManager, "transactionManager"));
    transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    this.registryMapper = Objects.requireNonNull(registryMapper, "registryMapper");
    this.migrationMapper = Objects.requireNonNull(migrationMapper, "migrationMapper");
    this.legacyMapper = Objects.requireNonNull(legacyMapper, "legacyMapper");
    this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry");
    this.migrationTransactions = transactions;
    this.clock = Clock.systemDefaultZone();
  }

  SkitCallbackRouteRegistryService(
      SkitAdCallbackRouteRegistryMapper registryMapper,
      SkitAdCallbackRouteRegistryMigrationMapper migrationMapper,
      SkitAdCallbackKeyMapper legacyMapper,
      MeterRegistry meterRegistry,
      Clock clock) {
    this(registryMapper, migrationMapper, legacyMapper, meterRegistry, directTransactions(), clock);
  }

  SkitCallbackRouteRegistryService(
      SkitAdCallbackRouteRegistryMapper registryMapper,
      SkitAdCallbackRouteRegistryMigrationMapper migrationMapper,
      SkitAdCallbackKeyMapper legacyMapper,
      MeterRegistry meterRegistry,
      TransactionOperations migrationTransactions,
      Clock clock) {
    this.registryMapper = Objects.requireNonNull(registryMapper, "registryMapper");
    this.migrationMapper = Objects.requireNonNull(migrationMapper, "migrationMapper");
    this.legacyMapper = Objects.requireNonNull(legacyMapper, "legacyMapper");
    this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry");
    this.migrationTransactions =
        Objects.requireNonNull(migrationTransactions, "migrationTransactions");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /**
   * One deterministic global lookup. It never tries a second owner table on a miss or type
   * mismatch.
   */
  public RouteLookup lookup(byte[] keyHash, LocalDateTime authoritativeReceivedAt) {
    requireLookupArguments(keyHash, authoritativeReceivedAt);
    SkitAdCallbackRouteRegistryDO row =
        ignoreTenant(() -> registryMapper.selectLookupByKeyHash(keyHash));
    return routeLookup(row, authoritativeReceivedAt);
  }

  /**
   * Migration-aware tenant reward resolution. The caller supplies the one hash it computed.
   * SHADOW_READ compares the same hash against both paths but fails closed on every difference.
   */
  RouteLookup lookupTenantReward(byte[] keyHash, LocalDateTime authoritativeReceivedAt) {
    requireLookupArguments(keyHash, authoritativeReceivedAt);
    MigrationPhase phase = currentPhase();
    SkitAdCallbackRouteRegistryDO registryRow =
        ignoreTenant(() -> registryMapper.selectLookupByKeyHash(keyHash));
    if (phase == MigrationPhase.DUAL_WRITE
        || phase == MigrationPhase.BACKFILL
        || phase == MigrationPhase.VERIFY) {
      if (registryRow == null) {
        return legacyTenantLookup(keyHash, authoritativeReceivedAt);
      }
      RouteLookup resolved = routeLookup(registryRow, authoritativeReceivedAt);
      if (resolved.getRouteType() != RouteType.TENANT_CALLBACK_KEY) {
        throw rejected();
      }
      return resolved;
    }
    RouteLookup registry = null;
    CallbackRouteRejectedException registryFailure = null;
    try {
      registry = routeLookup(registryRow, authoritativeReceivedAt);
    } catch (CallbackRouteRejectedException rejected) {
      registryFailure = rejected;
    }
    if (phase == MigrationPhase.SHADOW_READ) {
      RouteLookup legacy = null;
      CallbackRouteRejectedException legacyFailure = null;
      try {
        legacy = legacyTenantLookup(keyHash, authoritativeReceivedAt);
      } catch (CallbackRouteRejectedException rejected) {
        legacyFailure = rejected;
      }
      String mismatch = shadowMismatch(registry, registryFailure, legacy, legacyFailure);
      if (mismatch != null) {
        meterRegistry
            .counter(
                SHADOW_MISMATCH_METRIC,
                "phase",
                MigrationPhase.SHADOW_READ.name(),
                "outcome",
                mismatch)
            .increment();
        throw rejected();
      }
    }
    if (registryFailure != null) {
      throw registryFailure;
    }
    return Objects.requireNonNull(registry, "registry lookup");
  }

  /** Standalone registry registration: owns its transaction, gate epoch, and opaque capability. */
  @Transactional(rollbackFor = Exception.class)
  public void registerTenantKey(TenantCallbackKeyRegistration registration) {
    Objects.requireNonNull(registration, "registration").validate();
    TenantKeyMutation mutation =
        beginTenantKeyMutation(registration.tenantId, registration.adAccountId);
    registerTenantKey(mutation, registration);
  }

  @Transactional(rollbackFor = Exception.class)
  public void registerTenantKey(
      TenantKeyMutation mutation, TenantCallbackKeyRegistration registration) {
    registerTenantKeys(mutation, java.util.Collections.singletonList(registration));
  }

  @Transactional(rollbackFor = Exception.class)
  public void registerTenantKeys(
      TenantKeyMutation mutation, List<TenantCallbackKeyRegistration> registrations) {
    requireMutation(mutation);
    if (registrations == null || registrations.isEmpty()) {
      return;
    }
    for (TenantCallbackKeyRegistration registration : registrations) {
      Objects.requireNonNull(registration, "registration").validate();
      if (!registration.belongsTo(mutation.tenantId, mutation.adAccountId)) {
        throw new IllegalArgumentException(
            "Tenant callback-key registration is outside mutation scope");
      }
    }
    ignoreTenant(
        () -> {
          SkitAdCallbackRouteRegistryMigrationDO observed =
              requiredState(migrationMapper.selectSingleton());
          if (!MigrationPhase.ENFORCED.name().equals(observed.getMigrationPhase())) {
            requiredState(migrationMapper.selectSingletonForUpdate());
          }
          for (TenantCallbackKeyRegistration registration : registrations) {
            insertOrValidate(registration.toRow());
          }
          return null;
        });
  }

  /** Provider routes may be registered only by an existing outer lifecycle transaction. */
  @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
  public long registerProviderRoute(ProviderCallbackRouteRegistration registration) {
    Objects.requireNonNull(registration, "registration").validate();
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "Provider callback route registration requires an outer transaction");
    }
    return ignoreTenant(
        () -> {
          SkitAdCallbackRouteRegistryDO scratch = registration.toRow();
          try {
            insertOrValidateProvider(scratch);
            SkitAdCallbackRouteRegistryDO row =
                registryMapper.selectByProviderCallbackRouteId(
                    registration.providerCallbackRouteId);
            if (row == null || row.getId() == null || row.getId() <= 0) {
              throw new RegistryOwnershipConflictException();
            }
            return row.getId();
          } finally {
            Arrays.fill(scratch.getKeyHash(), (byte) 0);
            registration.destroy();
          }
        });
  }

  @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
  public void tombstoneProviderRoute(long providerRouteId, LocalDateTime tombstonedAt) {
    if (providerRouteId <= 0 || tombstonedAt == null) {
      throw new IllegalArgumentException("Invalid provider route tombstone");
    }
    int changed =
        ignoreTenant(() -> registryMapper.tombstoneProviderRoute(providerRouteId, tombstonedAt));
    if (changed != 1) {
      throw new IllegalStateException("Provider callback route registry tombstone changed no row");
    }
  }

  /**
   * Serializes every tenant callback-key mutation with VERIFY before any legacy row changes. The
   * epoch increment commits or rolls back with the caller's credential transaction.
   */
  @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
  public TenantKeyMutation beginTenantKeyMutation(long tenantId, long adAccountId) {
    if (tenantId <= 0 || adAccountId <= 0) {
      throw new IllegalArgumentException("Invalid tenant callback-key mutation scope");
    }
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || !TransactionSynchronizationManager.isSynchronizationActive()) {
      throw new IllegalStateException(
          "Tenant callback-key mutation requires an active transaction");
    }
    if (TransactionSynchronizationManager.hasResource(tenantMutationResourceKey)) {
      throw new IllegalStateException("A tenant callback-key mutation is already active");
    }
    ignoreTenant(
        () -> {
          SkitAdCallbackRouteRegistryMigrationDO state =
              requiredState(migrationMapper.selectSingletonForUpdate());
          MigrationPhase phase = phase(state);
          if (phase == MigrationPhase.DUAL_WRITE
              || phase == MigrationPhase.BACKFILL
              || phase == MigrationPhase.VERIFY) {
            if (migrationMapper.incrementCredentialMutationEpoch(state.getPhaseRevision(), now())
                != 1) {
              throw concurrentMigration();
            }
          }
          return null;
        });
    TenantKeyMutation mutation = new TenantKeyMutation(this, tenantId, adAccountId);
    TransactionSynchronizationManager.bindResource(tenantMutationResourceKey, mutation);
    try {
      TransactionSynchronizationManager.registerSynchronization(mutation.synchronization);
    } catch (RuntimeException | Error registrationFailure) {
      mutation.active = false;
      if (TransactionSynchronizationManager.getResource(tenantMutationResourceKey) == mutation) {
        TransactionSynchronizationManager.unbindResource(tenantMutationResourceKey);
      }
      throw registrationFailure;
    }
    return mutation;
  }

  @Transactional(rollbackFor = Exception.class)
  public void tombstoneRevokedTenantKeys(
      TenantKeyMutation mutation,
      long tenantId,
      long adAccountId,
      LocalDateTime revokedAt,
      int expectedCount) {
    requireMutation(mutation);
    if (tenantId <= 0 || adAccountId <= 0 || revokedAt == null || expectedCount < 0) {
      throw new IllegalArgumentException("Invalid tenant callback-key tombstone request");
    }
    if (mutation.tenantId != tenantId || mutation.adAccountId != adAccountId) {
      throw new IllegalArgumentException("Tenant callback-key tombstone is outside mutation scope");
    }
    int changed =
        ignoreTenant(() -> registryMapper.tombstoneRevokedTenantKeys(tenantId, adAccountId));
    if (changed != expectedCount) {
      throw new IllegalStateException("Tenant callback-key registry tombstone count mismatch");
    }
  }

  /** Backfills and verifies in committed 200-row keyset batches, then stops in SHADOW_READ. */
  public RegistryMigrationReport backfillAndVerifyTenantKeys() {
    while (true) {
      MigrationStepResult result =
          migrationTransactions.execute(status -> ignoreTenant(this::advanceOneMigrationStep));
      if (result == null) {
        throw new IllegalStateException(
            "Callback registry migration transaction returned no result");
      }
      if (result.blocked) {
        throw new RegistryMigrationBlockedException();
      }
      if (result.report != null) {
        return result.report;
      }
    }
  }

  private MigrationStepResult advanceOneMigrationStep() {
    SkitAdCallbackRouteRegistryMigrationDO state =
        requiredState(migrationMapper.selectSingletonForUpdate());
    if (state.getBlockedAt() != null) {
      return MigrationStepResult.blocked();
    }
    MigrationPhase phase = phase(state);
    if (phase == MigrationPhase.DUAL_WRITE) {
      transition(state, MigrationPhase.BACKFILL, null);
      return MigrationStepResult.continueMigration();
    }
    if (phase == MigrationPhase.BACKFILL) {
      return backfillOneBatch(state);
    }
    if (phase == MigrationPhase.VERIFY && state.getVerifiedAt() == null) {
      return verifyOneBatch(state);
    }
    if (phase(state) == MigrationPhase.VERIFY) {
      throw new IllegalStateException("Callback registry VERIFY state has invalid final evidence");
    }
    return MigrationStepResult.complete(report(state));
  }

  /** Advances one cutover boundary per explicit call: SHADOW_READ -> HASH_FIRST -> ENFORCED. */
  public void enableHashFirstReads() {
    Boolean blocked =
        migrationTransactions.execute(
            status ->
                ignoreTenant(
                    () -> {
                      SkitAdCallbackRouteRegistryMigrationDO state =
                          requiredState(migrationMapper.selectSingletonForUpdate());
                      if (state.getBlockedAt() != null) {
                        return Boolean.TRUE;
                      }
                      MigrationPhase phase = phase(state);
                      if (phase == MigrationPhase.SHADOW_READ) {
                        requireVerified(state);
                        transition(state, MigrationPhase.HASH_FIRST, null);
                      } else if (phase == MigrationPhase.HASH_FIRST) {
                        requireVerified(state);
                        transition(state, MigrationPhase.ENFORCED, now());
                      } else if (phase != MigrationPhase.ENFORCED) {
                        throw new IllegalStateException(
                            "Callback registry is not ready for hash-first reads");
                      }
                      return Boolean.FALSE;
                    }));
    if (Boolean.TRUE.equals(blocked)) {
      throw new RegistryMigrationBlockedException();
    }
  }

  public RegistryMigrationReport migrationReport() {
    return ignoreTenant(() -> report(requiredState(migrationMapper.selectSingleton())));
  }

  private MigrationStepResult backfillOneBatch(SkitAdCallbackRouteRegistryMigrationDO state) {
    List<SkitAdCallbackRouteRegistryDO> batch =
        registryMapper.selectLegacyTenantKeysAfterId(
            state.getLastCallbackKeyId(), BACKFILL_BATCH_SIZE);
    if (batch == null || batch.isEmpty()) {
      byte[] seedHash = verificationSeed();
      try {
        if (migrationMapper.startVerification(state.getPhaseRevision(), seedHash, now()) != 1) {
          throw concurrentMigration();
        }
      } finally {
        Arrays.fill(seedHash, (byte) 0);
      }
      return MigrationStepResult.continueMigration();
    }
    long cursor = state.getLastCallbackKeyId();
    try {
      for (SkitAdCallbackRouteRegistryDO legacy : batch) {
        TenantCallbackKeyRegistration registration =
            TenantCallbackKeyRegistration.fromLegacy(legacy, now());
        insertOrValidate(registration.toRow());
        cursor = Math.max(cursor, registration.getTenantCallbackKeyId());
      }
    } catch (RegistryOwnershipConflictException conflict) {
      byte[] blockedReasonHash =
          sha256(
              "callback-registry-backfill-ownership-conflict".getBytes(StandardCharsets.US_ASCII));
      try {
        if (migrationMapper.recordBlocked(state.getPhaseRevision(), blockedReasonHash, now())
            != 1) {
          throw concurrentMigration();
        }
      } finally {
        Arrays.fill(blockedReasonHash, (byte) 0);
      }
      return MigrationStepResult.blocked();
    }
    if (migrationMapper.updateBackfillCursor(state.getPhaseRevision(), cursor, batch.size(), now())
        != 1) {
      throw concurrentMigration();
    }
    return MigrationStepResult.continueMigration();
  }

  private MigrationStepResult verifyOneBatch(SkitAdCallbackRouteRegistryMigrationDO state) {
    requireVerificationProgress(state);
    if (!state.getVerificationSnapshotEpoch().equals(state.getCredentialMutationEpoch())) {
      byte[] seedHash = verificationSeed();
      try {
        if (migrationMapper.restartVerification(state.getPhaseRevision(), seedHash, now()) != 1) {
          throw concurrentMigration();
        }
      } finally {
        Arrays.fill(seedHash, (byte) 0);
      }
      return MigrationStepResult.continueMigration();
    }

    List<SkitAdCallbackRouteRegistryVerificationRow> batch =
        registryMapper.selectVerificationPairsAfterId(
            state.getVerificationCursorCallbackKeyId(), BACKFILL_BATCH_SIZE);
    if (batch == null || batch.isEmpty()) {
      return finishVerification(state);
    }

    MessageDigest expectedBatch = sha256Digest();
    MessageDigest actualBatch = sha256Digest();
    long cursor = state.getVerificationCursorCallbackKeyId();
    long expectedCount = state.getVerificationExpectedProgressCount();
    long actualCount = state.getVerificationActualProgressCount();
    long mismatchCount = state.getVerificationProgressMismatchCount();
    for (SkitAdCallbackRouteRegistryVerificationRow row : batch) {
      long ownerId =
          requiredPositive(row.getTenantCallbackKeyId(), "Verification pair has no legacy owner");
      frameVerificationTuple(
          expectedBatch,
          RouteType.TENANT_CALLBACK_KEY.name(),
          null,
          ownerId,
          row.getExpectedTenantId(),
          row.getExpectedAdAccountId(),
          row.getExpectedKeyVersion(),
          row.getExpectedActive(),
          row.getExpectedAcceptUntil(),
          row.getExpectedKeyHash(),
          row.getExpectedTombstonedAt());
      expectedCount++;
      if (row.getRegistryId() != null) {
        frameVerificationTuple(
            actualBatch,
            row.getActualRouteType(),
            row.getActualProviderCallbackRouteId(),
            row.getActualTenantCallbackKeyId(),
            row.getActualTenantId(),
            row.getActualAdAccountId(),
            row.getActualKeyVersion(),
            row.getActualActive(),
            row.getActualAcceptUntil(),
            row.getActualKeyHash(),
            row.getActualTombstonedAt());
        actualCount++;
      }
      if (!sameVerificationPair(row)) {
        mismatchCount++;
      }
      cursor = Math.max(cursor, ownerId);
    }
    byte[] expectedBatchHash = expectedBatch.digest();
    byte[] actualBatchHash = actualBatch.digest();
    byte[] expectedRollingHash =
        sha256(state.getVerificationExpectedRollingHash(), expectedBatchHash);
    byte[] actualRollingHash = sha256(state.getVerificationActualRollingHash(), actualBatchHash);
    try {
      if (migrationMapper.updateVerificationProgress(
              state.getPhaseRevision(),
              state.getVerificationRunId(),
              cursor,
              expectedCount,
              actualCount,
              mismatchCount,
              expectedRollingHash,
              actualRollingHash,
              now())
          != 1) {
        throw concurrentMigration();
      }
    } finally {
      Arrays.fill(expectedBatchHash, (byte) 0);
      Arrays.fill(actualBatchHash, (byte) 0);
      Arrays.fill(expectedRollingHash, (byte) 0);
      Arrays.fill(actualRollingHash, (byte) 0);
    }
    return MigrationStepResult.continueMigration();
  }

  private MigrationStepResult finishVerification(SkitAdCallbackRouteRegistryMigrationDO state) {
    boolean matches =
        state.getVerificationProgressMismatchCount() == 0L
            && state
                .getVerificationExpectedProgressCount()
                .equals(state.getVerificationActualProgressCount())
            && MessageDigest.isEqual(
                state.getVerificationExpectedRollingHash(),
                state.getVerificationActualRollingHash());
    LocalDateTime verifiedAt = now();
    if (!matches) {
      byte[] blockedReasonHash =
          sha256("callback-registry-verification-mismatch".getBytes(StandardCharsets.US_ASCII));
      try {
        if (migrationMapper.recordBlocked(state.getPhaseRevision(), blockedReasonHash, verifiedAt)
            != 1) {
          throw concurrentMigration();
        }
      } finally {
        Arrays.fill(blockedReasonHash, (byte) 0);
      }
      return MigrationStepResult.blocked();
    }
    byte[] verificationHash =
        sha256(
            "callback-registry-verification-final-v1".getBytes(StandardCharsets.US_ASCII),
            longBytes(state.getVerificationRunId()),
            longBytes(state.getVerificationSnapshotEpoch()),
            longBytes(state.getVerificationCursorCallbackKeyId()),
            longBytes(state.getVerificationExpectedProgressCount()),
            state.getVerificationExpectedRollingHash(),
            state.getVerificationActualRollingHash());
    try {
      if (migrationMapper.completeVerificationAndEnterShadow(
              state.getPhaseRevision(),
              state.getVerificationRunId(),
              state.getVerificationExpectedProgressCount(),
              state.getVerificationActualProgressCount(),
              verificationHash,
              verifiedAt)
          != 1) {
        throw concurrentMigration();
      }
    } finally {
      Arrays.fill(verificationHash, (byte) 0);
    }
    SkitAdCallbackRouteRegistryMigrationDO completed =
        requiredState(migrationMapper.selectSingletonForUpdate());
    return MigrationStepResult.complete(report(completed));
  }

  private static void requireVerificationProgress(SkitAdCallbackRouteRegistryMigrationDO state) {
    if (state.getCredentialMutationEpoch() == null
        || state.getVerificationRunId() == null
        || state.getVerificationRunId() <= 0
        || state.getVerificationSnapshotEpoch() == null
        || state.getVerificationCursorCallbackKeyId() == null
        || state.getVerificationExpectedProgressCount() == null
        || state.getVerificationActualProgressCount() == null
        || state.getVerificationProgressMismatchCount() == null
        || state.getVerificationExpectedRollingHash() == null
        || state.getVerificationActualRollingHash() == null) {
      throw new IllegalStateException("Callback registry verification progress is unavailable");
    }
  }

  private static boolean sameVerificationPair(SkitAdCallbackRouteRegistryVerificationRow row) {
    return row.getRegistryId() != null
        && row.getRegistryId() > 0
        && RouteType.TENANT_CALLBACK_KEY.name().equals(row.getActualRouteType())
        && row.getActualProviderCallbackRouteId() == null
        && Objects.equals(row.getTenantCallbackKeyId(), row.getActualTenantCallbackKeyId())
        && Objects.equals(row.getExpectedTenantId(), row.getActualTenantId())
        && Objects.equals(row.getExpectedAdAccountId(), row.getActualAdAccountId())
        && Objects.equals(row.getExpectedKeyVersion(), row.getActualKeyVersion())
        && Objects.equals(row.getExpectedActive(), row.getActualActive())
        && Objects.equals(row.getExpectedAcceptUntil(), row.getActualAcceptUntil())
        && row.getExpectedKeyHash() != null
        && row.getActualKeyHash() != null
        && MessageDigest.isEqual(row.getExpectedKeyHash(), row.getActualKeyHash())
        && Objects.equals(row.getExpectedTombstonedAt(), row.getActualTombstonedAt());
  }

  private static void frameVerificationTuple(
      MessageDigest digest,
      String routeType,
      Long providerRouteId,
      Long tenantKeyId,
      Long tenantId,
      Long adAccountId,
      Integer keyVersion,
      Boolean active,
      LocalDateTime acceptUntil,
      byte[] keyHash,
      LocalDateTime tombstonedAt) {
    frame(digest, routeType);
    frame(digest, providerRouteId);
    frame(digest, tenantKeyId);
    frame(digest, tenantId);
    frame(digest, adAccountId);
    frame(digest, keyVersion);
    frame(digest, active);
    frame(digest, acceptUntil);
    frame(digest, keyHash);
    frame(digest, tombstonedAt);
  }

  private static byte[] verificationSeed() {
    return sha256("callback-registry-verification-seed-v1".getBytes(StandardCharsets.US_ASCII));
  }

  private static byte[] longBytes(long value) {
    return ByteBuffer.allocate(Long.BYTES).putLong(value).array();
  }

  private void insertOrValidate(SkitAdCallbackRouteRegistryDO requested) {
    try {
      registryMapper.insert(requested);
    } catch (DuplicateKeyException duplicate) {
      // Exact replays are valid; ownership conflicts fail closed without logging identity material.
    }
    SkitAdCallbackRouteRegistryDO byHash =
        registryMapper.selectLookupByKeyHash(requested.getKeyHash());
    SkitAdCallbackRouteRegistryDO byOwner =
        registryMapper.selectByTenantCallbackKeyId(requested.getTenantCallbackKeyId());
    if (!sameTenantOwnerAndState(requested, byHash)
        || !sameTenantOwnerAndState(requested, byOwner)) {
      throw new RegistryOwnershipConflictException();
    }
  }

  private void insertOrValidateProvider(SkitAdCallbackRouteRegistryDO requested) {
    try {
      registryMapper.insert(requested);
    } catch (DuplicateKeyException duplicate) {
      // An exact transaction replay is safe; a cross-owner collision is not.
    }
    SkitAdCallbackRouteRegistryDO actual =
        registryMapper.selectLookupByKeyHash(requested.getKeyHash());
    if (actual == null
        || !RouteType.PROVIDER_CALLBACK_ROUTE.name().equals(actual.getRouteType())
        || !Objects.equals(
            requested.getProviderCallbackRouteId(), actual.getProviderCallbackRouteId())
        || actual.getTenantCallbackKeyId() != null
        || !MessageDigest.isEqual(requested.getKeyHash(), actual.getKeyHash())) {
      throw new RegistryOwnershipConflictException();
    }
  }

  private RouteLookup legacyTenantLookup(byte[] keyHash, LocalDateTime receivedAt) {
    SkitAdCallbackKeyDO row = ignoreTenant(() -> legacyMapper.selectByHash(keyHash));
    if (row == null
        || !accepted(row.getActive(), row.getAcceptUntil(), row.getRevokedAt(), receivedAt)
        || row.getTenantId() == null
        || row.getTenantId() <= 0
        || row.getAdAccountId() == null
        || row.getAdAccountId() <= 0
        || row.getKeyVersion() == null
        || row.getKeyVersion() <= 0) {
      throw rejected();
    }
    return RouteLookup.tenant(
        row.getTenantId(),
        row.getAdAccountId(),
        row.getKeyVersion(),
        Boolean.TRUE.equals(row.getActive()),
        row.getAcceptUntil());
  }

  private static RouteLookup routeLookup(
      SkitAdCallbackRouteRegistryDO row, LocalDateTime receivedAt) {
    if (row == null) {
      throw rejected();
    }
    RouteType routeType = routeType(row.getRouteType());
    if (routeType == RouteType.PROVIDER_CALLBACK_ROUTE) {
      if (row.getProviderCallbackRouteId() == null
          || row.getProviderCallbackRouteId() <= 0
          || row.getTenantCallbackKeyId() != null
          || isAtOrAfter(receivedAt, row.getTombstonedAt())) {
        throw rejected();
      }
      return RouteLookup.provider(row.getProviderCallbackRouteId());
    }
    if (row.getTenantCallbackKeyId() == null
        || row.getTenantCallbackKeyId() <= 0
        || row.getProviderCallbackRouteId() != null
        || row.getTenantId() == null
        || row.getTenantId() <= 0
        || row.getAdAccountId() == null
        || row.getAdAccountId() <= 0
        || row.getKeyVersion() == null
        || row.getKeyVersion() <= 0
        || !Objects.equals(row.getTombstonedAt(), row.getRevokedAt())
        || !accepted(row.getActive(), row.getAcceptUntil(), row.getRevokedAt(), receivedAt)) {
      throw rejected();
    }
    return RouteLookup.tenant(
        row.getTenantId(),
        row.getAdAccountId(),
        row.getKeyVersion(),
        Boolean.TRUE.equals(row.getActive()),
        row.getAcceptUntil());
  }

  private static String shadowMismatch(
      RouteLookup registry,
      CallbackRouteRejectedException registryFailure,
      RouteLookup legacy,
      CallbackRouteRejectedException legacyFailure) {
    if (registryFailure != null || legacyFailure != null) {
      return registryFailure != null && legacyFailure != null ? null : "PRESENCE";
    }
    if (registry == null || legacy == null || registry.getRouteType() != legacy.getRouteType()) {
      return "TYPE";
    }
    if (registry.getRouteType() != RouteType.TENANT_CALLBACK_KEY) {
      return "TYPE";
    }
    if (registry.getTenantId() != legacy.getTenantId()
        || registry.getAdAccountId() != legacy.getAdAccountId()
        || registry.getKeyVersion() != legacy.getKeyVersion()
        || registry.isActive() != legacy.isActive()
        || !Objects.equals(registry.getAcceptUntil(), legacy.getAcceptUntil())) {
      return "OWNER_OR_STATE";
    }
    return null;
  }

  private void transition(
      SkitAdCallbackRouteRegistryMigrationDO state, MigrationPhase to, LocalDateTime completedAt) {
    MigrationPhase from = phase(state);
    if (migrationMapper.transition(
            from.name(), to.name(), state.getPhaseRevision(), completedAt, now())
        != 1) {
      throw concurrentMigration();
    }
  }

  private MigrationPhase currentPhase() {
    return ignoreTenant(() -> phase(requiredState(migrationMapper.selectSingleton())));
  }

  private static RegistryMigrationReport report(SkitAdCallbackRouteRegistryMigrationDO state) {
    return new RegistryMigrationReport(
        phase(state),
        state.getPhaseRevision(),
        state.getLastCallbackKeyId(),
        state.getExpectedRowCount(),
        state.getVerifiedRowCount(),
        state.getVerificationMismatchCount(),
        state.getBlockedAt() != null);
  }

  private static void requireVerified(SkitAdCallbackRouteRegistryMigrationDO state) {
    if (state.getVerifiedAt() == null
        || state.getExpectedRowCount() == null
        || !state.getExpectedRowCount().equals(state.getVerifiedRowCount())
        || state.getVerificationMismatchCount() == null
        || state.getVerificationMismatchCount() != 0L
        || state.getVerificationHash() == null) {
      throw new IllegalStateException("Callback registry verification is incomplete");
    }
  }

  private static SkitAdCallbackRouteRegistryMigrationDO requiredState(
      SkitAdCallbackRouteRegistryMigrationDO state) {
    if (state == null
        || !Integer.valueOf(1).equals(state.getSingletonId())
        || state.getMigrationPhase() == null
        || state.getPhaseRevision() == null
        || state.getLastCallbackKeyId() == null) {
      throw new IllegalStateException("Callback registry migration state is unavailable");
    }
    return state;
  }

  private static MigrationPhase phase(SkitAdCallbackRouteRegistryMigrationDO state) {
    try {
      return MigrationPhase.valueOf(state.getMigrationPhase());
    } catch (IllegalArgumentException invalid) {
      throw new IllegalStateException("Callback registry migration phase is invalid");
    }
  }

  private static RouteType routeType(String value) {
    try {
      return RouteType.valueOf(value);
    } catch (RuntimeException invalid) {
      throw rejected();
    }
  }

  private static boolean sameTenantOwnerAndState(
      SkitAdCallbackRouteRegistryDO expected, SkitAdCallbackRouteRegistryDO actual) {
    return actual != null
        && "TENANT_CALLBACK_KEY".equals(actual.getRouteType())
        && actual.getProviderCallbackRouteId() == null
        && Objects.equals(expected.getTenantCallbackKeyId(), actual.getTenantCallbackKeyId())
        && MessageDigest.isEqual(expected.getKeyHash(), actual.getKeyHash())
        && Objects.equals(expected.getTombstonedAt(), actual.getTombstonedAt())
        && Objects.equals(expected.getTenantId(), actual.getTenantId())
        && Objects.equals(expected.getAdAccountId(), actual.getAdAccountId())
        && Objects.equals(expected.getKeyVersion(), actual.getKeyVersion())
        && Objects.equals(expected.getActive(), actual.getActive())
        && Objects.equals(expected.getAcceptUntil(), actual.getAcceptUntil())
        && Objects.equals(expected.getRevokedAt(), actual.getRevokedAt());
  }

  private static boolean accepted(
      Boolean active,
      LocalDateTime acceptUntil,
      LocalDateTime revokedAt,
      LocalDateTime receivedAt) {
    if (revokedAt != null) {
      return receivedAt.isBefore(revokedAt)
          && (acceptUntil == null || !receivedAt.isAfter(acceptUntil));
    }
    return Boolean.TRUE.equals(active) || (acceptUntil != null && !receivedAt.isAfter(acceptUntil));
  }

  private static boolean isAtOrAfter(LocalDateTime value, LocalDateTime boundary) {
    return boundary != null && !value.isBefore(boundary);
  }

  private void requireMutation(TenantKeyMutation mutation) {
    if (mutation == null
        || mutation.owner != this
        || !mutation.active
        || mutation.suspended
        || !TransactionSynchronizationManager.isActualTransactionActive()
        || !TransactionSynchronizationManager.isSynchronizationActive()
        || !hasCurrentSynchronization(mutation.synchronization)
        || TransactionSynchronizationManager.getResource(tenantMutationResourceKey) != mutation) {
      throw new IllegalStateException("Tenant callback-key mutation gate is unavailable");
    }
  }

  private static boolean hasCurrentSynchronization(TransactionSynchronization expected) {
    for (TransactionSynchronization current :
        TransactionSynchronizationManager.getSynchronizations()) {
      if (current == expected) {
        return true;
      }
    }
    return false;
  }

  private static void requireLookupArguments(byte[] keyHash, LocalDateTime receivedAt) {
    if (keyHash == null || keyHash.length != 32 || receivedAt == null) {
      throw rejected();
    }
  }

  private static long requiredPositive(Long value, String message) {
    if (value == null || value <= 0) {
      throw new IllegalStateException(message);
    }
    return value;
  }

  private static void frame(MessageDigest digest, Object value) {
    byte[] bytes;
    if (value == null) {
      bytes = null;
    } else if (value instanceof byte[]) {
      bytes = (byte[]) value;
    } else {
      bytes = String.valueOf(value).getBytes(StandardCharsets.UTF_8);
    }
    digest.update(ByteBuffer.allocate(4).putInt(bytes == null ? -1 : bytes.length).array());
    if (bytes != null) {
      digest.update(bytes);
    }
  }

  private static byte[] sha256(byte[]... values) {
    MessageDigest digest = sha256Digest();
    for (byte[] value : values) {
      frame(digest, value);
    }
    return digest.digest();
  }

  private static MessageDigest sha256Digest() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private static IllegalStateException concurrentMigration() {
    return new IllegalStateException("Callback registry migration changed concurrently");
  }

  private static CallbackRouteRejectedException rejected() {
    return new CallbackRouteRejectedException();
  }

  private LocalDateTime now() {
    return LocalDateTime.now(clock);
  }

  private static <T> T ignoreTenant(java.util.function.Supplier<T> operation) {
    AtomicReference<T> result = new AtomicReference<>();
    TenantUtils.executeIgnore(() -> result.set(operation.get()));
    return result.get();
  }

  private static TransactionOperations directTransactions() {
    return new TransactionOperations() {
      @Override
      public <T> T execute(TransactionCallback<T> action) {
        return action.doInTransaction(null);
      }
    };
  }

  private static final class MigrationStepResult {
    private final RegistryMigrationReport report;
    private final boolean blocked;

    private MigrationStepResult(RegistryMigrationReport report, boolean blocked) {
      this.report = report;
      this.blocked = blocked;
    }

    private static MigrationStepResult continueMigration() {
      return new MigrationStepResult(null, false);
    }

    private static MigrationStepResult complete(RegistryMigrationReport report) {
      return new MigrationStepResult(report, false);
    }

    private static MigrationStepResult blocked() {
      return new MigrationStepResult(null, true);
    }
  }

  public enum RouteType {
    TENANT_CALLBACK_KEY,
    PROVIDER_CALLBACK_ROUTE
  }

  public enum MigrationPhase {
    DUAL_WRITE,
    BACKFILL,
    VERIFY,
    SHADOW_READ,
    HASH_FIRST,
    ENFORCED
  }

  public static final class CallbackRouteRejectedException extends IllegalStateException {
    public CallbackRouteRejectedException() {
      super("Callback route is unavailable for this endpoint");
    }
  }

  public static final class RegistryMigrationBlockedException extends IllegalStateException {
    public RegistryMigrationBlockedException() {
      super("Callback registry migration is blocked");
    }
  }

  /** Opaque transaction-bound capability proving the singleton mutation gate is held. */
  public static final class TenantKeyMutation {
    private final SkitCallbackRouteRegistryService owner;
    private final long tenantId;
    private final long adAccountId;
    private final TransactionSynchronization synchronization;
    private volatile boolean active = true;
    private volatile boolean suspended;

    private TenantKeyMutation(
        SkitCallbackRouteRegistryService owner, long tenantId, long adAccountId) {
      this.owner = owner;
      this.tenantId = tenantId;
      this.adAccountId = adAccountId;
      this.synchronization =
          new TenantKeyMutationSynchronization(owner.tenantMutationResourceKey, this);
    }

    @Override
    public String toString() {
      return "TenantKeyMutation{tenantId=" + tenantId + ", adAccountId=" + adAccountId + '}';
    }
  }

  private static final class TenantKeyMutationSynchronization
      implements TransactionSynchronization {

    private final Object resourceKey;
    private final TenantKeyMutation mutation;

    private TenantKeyMutationSynchronization(Object resourceKey, TenantKeyMutation mutation) {
      this.resourceKey = resourceKey;
      this.mutation = mutation;
    }

    @Override
    public void suspend() {
      if (!mutation.active
          || mutation.suspended
          || !TransactionSynchronizationManager.isSynchronizationActive()
          || !hasCurrentSynchronization(this)
          || TransactionSynchronizationManager.getResource(resourceKey) != mutation) {
        throw new IllegalStateException("Tenant callback-key mutation gate cannot be suspended");
      }
      TransactionSynchronizationManager.unbindResource(resourceKey);
      mutation.suspended = true;
    }

    @Override
    public void resume() {
      if (!mutation.active
          || !mutation.suspended
          || !TransactionSynchronizationManager.isActualTransactionActive()
          || !TransactionSynchronizationManager.isSynchronizationActive()
          || TransactionSynchronizationManager.hasResource(resourceKey)) {
        throw new IllegalStateException("Tenant callback-key mutation gate cannot be resumed");
      }
      TransactionSynchronizationManager.bindResource(resourceKey, mutation);
      mutation.suspended = false;
    }

    @Override
    public void afterCompletion(int status) {
      mutation.active = false;
      mutation.suspended = false;
      if (TransactionSynchronizationManager.getResource(resourceKey) == mutation) {
        TransactionSynchronizationManager.unbindResource(resourceKey);
      }
    }
  }

  private static final class RegistryOwnershipConflictException extends IllegalStateException {
    private RegistryOwnershipConflictException() {
      super("Callback registry key ownership conflict");
    }
  }

  public static final class RouteLookup {

    private final RouteType routeType;
    private final Long providerCallbackRouteId;
    private final long tenantId;
    private final long adAccountId;
    private final int keyVersion;
    private final boolean active;
    private final LocalDateTime acceptUntil;

    private RouteLookup(
        RouteType routeType,
        Long providerCallbackRouteId,
        long tenantId,
        long adAccountId,
        int keyVersion,
        boolean active,
        LocalDateTime acceptUntil) {
      this.routeType = routeType;
      this.providerCallbackRouteId = providerCallbackRouteId;
      this.tenantId = tenantId;
      this.adAccountId = adAccountId;
      this.keyVersion = keyVersion;
      this.active = active;
      this.acceptUntil = acceptUntil;
    }

    static RouteLookup provider(long providerCallbackRouteId) {
      return new RouteLookup(
          RouteType.PROVIDER_CALLBACK_ROUTE, providerCallbackRouteId, 0, 0, 0, false, null);
    }

    static RouteLookup tenant(
        long tenantId,
        long adAccountId,
        int keyVersion,
        boolean active,
        LocalDateTime acceptUntil) {
      return new RouteLookup(
          RouteType.TENANT_CALLBACK_KEY,
          null,
          tenantId,
          adAccountId,
          keyVersion,
          active,
          acceptUntil);
    }

    public RouteType getRouteType() {
      return routeType;
    }

    public Long getProviderCallbackRouteId() {
      return providerCallbackRouteId;
    }

    public long getTenantId() {
      return tenantId;
    }

    public long getAdAccountId() {
      return adAccountId;
    }

    public int getKeyVersion() {
      return keyVersion;
    }

    public boolean isActive() {
      return active;
    }

    public LocalDateTime getAcceptUntil() {
      return acceptUntil;
    }

    @Override
    public String toString() {
      return routeType == RouteType.TENANT_CALLBACK_KEY
          ? "RouteLookup{routeType=TENANT_CALLBACK_KEY, tenantId="
              + tenantId
              + ", adAccountId="
              + adAccountId
              + ", keyVersion="
              + keyVersion
              + '}'
          : "RouteLookup{routeType=PROVIDER_CALLBACK_ROUTE, providerCallbackRouteId="
              + providerCallbackRouteId
              + '}';
    }
  }

  public static final class TenantCallbackKeyRegistration {

    private final long tenantCallbackKeyId;
    private final byte[] keyHash;
    private final long tenantId;
    private final long adAccountId;
    private final int keyVersion;
    private final boolean active;
    private final LocalDateTime acceptUntil;
    private final LocalDateTime registeredAt;
    private final LocalDateTime revokedAt;

    public TenantCallbackKeyRegistration(
        long tenantCallbackKeyId,
        byte[] keyHash,
        long tenantId,
        long adAccountId,
        int keyVersion,
        LocalDateTime registeredAt,
        LocalDateTime revokedAt) {
      this.tenantCallbackKeyId = tenantCallbackKeyId;
      this.keyHash = keyHash == null ? null : keyHash.clone();
      this.tenantId = tenantId;
      this.adAccountId = adAccountId;
      this.keyVersion = keyVersion;
      this.active = revokedAt == null;
      this.acceptUntil = null;
      this.registeredAt = registeredAt;
      this.revokedAt = revokedAt;
    }

    private TenantCallbackKeyRegistration(
        long tenantCallbackKeyId,
        byte[] keyHash,
        long tenantId,
        long adAccountId,
        int keyVersion,
        boolean active,
        LocalDateTime acceptUntil,
        LocalDateTime registeredAt,
        LocalDateTime revokedAt) {
      this.tenantCallbackKeyId = tenantCallbackKeyId;
      this.keyHash = keyHash == null ? null : keyHash.clone();
      this.tenantId = tenantId;
      this.adAccountId = adAccountId;
      this.keyVersion = keyVersion;
      this.active = active;
      this.acceptUntil = acceptUntil;
      this.registeredAt = registeredAt;
      this.revokedAt = revokedAt;
    }

    static TenantCallbackKeyRegistration fromLegacy(
        SkitAdCallbackRouteRegistryDO legacy, LocalDateTime fallbackRegisteredAt) {
      return new TenantCallbackKeyRegistration(
          requiredPositive(
              legacy.getTenantCallbackKeyId(), "Legacy callback key has no durable identifier"),
          legacy.getKeyHash(),
          requiredPositive(legacy.getTenantId(), "Legacy callback key has no tenant owner"),
          requiredPositive(legacy.getAdAccountId(), "Legacy callback key has no account owner"),
          legacy.getKeyVersion() == null ? 0 : legacy.getKeyVersion(),
          Boolean.TRUE.equals(legacy.getActive()),
          legacy.getAcceptUntil(),
          legacy.getRegisteredAt() == null ? fallbackRegisteredAt : legacy.getRegisteredAt(),
          legacy.getRevokedAt());
    }

    public static TenantCallbackKeyRegistration fromLegacy(
        SkitAdCallbackKeyDO legacy, LocalDateTime fallbackRegisteredAt) {
      Objects.requireNonNull(legacy, "legacy");
      return new TenantCallbackKeyRegistration(
          requiredPositive(legacy.getId(), "Legacy callback key has no durable identifier"),
          legacy.getCallbackKeyHash(),
          requiredPositive(legacy.getTenantId(), "Legacy callback key has no tenant owner"),
          requiredPositive(legacy.getAdAccountId(), "Legacy callback key has no account owner"),
          legacy.getKeyVersion() == null ? 0 : legacy.getKeyVersion(),
          Boolean.TRUE.equals(legacy.getActive()),
          legacy.getAcceptUntil(),
          legacy.getCreateTime() == null ? fallbackRegisteredAt : legacy.getCreateTime(),
          legacy.getRevokedAt());
    }

    void validate() {
      if (tenantCallbackKeyId <= 0
          || keyHash == null
          || keyHash.length != 32
          || tenantId <= 0
          || adAccountId <= 0
          || keyVersion <= 0
          || registeredAt == null) {
        throw new IllegalArgumentException("Invalid tenant callback-key registration");
      }
    }

    SkitAdCallbackRouteRegistryDO toRow() {
      validate();
      return new SkitAdCallbackRouteRegistryDO()
          .setKeyHash(keyHash.clone())
          .setRouteType(RouteType.TENANT_CALLBACK_KEY.name())
          .setTenantCallbackKeyId(tenantCallbackKeyId)
          .setRegisteredAt(registeredAt)
          .setTombstonedAt(revokedAt)
          .setTenantId(tenantId)
          .setAdAccountId(adAccountId)
          .setKeyVersion(keyVersion)
          .setActive(active)
          .setAcceptUntil(acceptUntil)
          .setRevokedAt(revokedAt);
    }

    long getTenantCallbackKeyId() {
      return tenantCallbackKeyId;
    }

    private boolean belongsTo(long expectedTenantId, long expectedAdAccountId) {
      return tenantId == expectedTenantId && adAccountId == expectedAdAccountId;
    }

    @Override
    public String toString() {
      return "TenantCallbackKeyRegistration{tenantCallbackKeyId="
          + tenantCallbackKeyId
          + ", tenantId="
          + tenantId
          + ", adAccountId="
          + adAccountId
          + ", keyVersion="
          + keyVersion
          + '}';
    }
  }

  public static final class ProviderCallbackRouteRegistration {
    private final long providerCallbackRouteId;
    private final byte[] keyHash;
    private final LocalDateTime registeredAt;

    public ProviderCallbackRouteRegistration(
        long providerCallbackRouteId, byte[] keyHash, LocalDateTime registeredAt) {
      this.providerCallbackRouteId = providerCallbackRouteId;
      this.keyHash = keyHash == null ? null : keyHash.clone();
      this.registeredAt = registeredAt;
    }

    void validate() {
      if (providerCallbackRouteId <= 0
          || keyHash == null
          || keyHash.length != 32
          || registeredAt == null) {
        throw new IllegalArgumentException("Invalid provider callback route registration");
      }
    }

    SkitAdCallbackRouteRegistryDO toRow() {
      validate();
      return new SkitAdCallbackRouteRegistryDO()
          .setKeyHash(keyHash.clone())
          .setRouteType(RouteType.PROVIDER_CALLBACK_ROUTE.name())
          .setProviderCallbackRouteId(providerCallbackRouteId)
          .setRegisteredAt(registeredAt);
    }

    void destroy() {
      if (keyHash != null) {
        Arrays.fill(keyHash, (byte) 0);
      }
    }

    @Override
    public String toString() {
      return "ProviderCallbackRouteRegistration{providerCallbackRouteId="
          + providerCallbackRouteId
          + '}';
    }
  }

  public static final class RegistryMigrationReport {

    private final MigrationPhase phase;
    private final long phaseRevision;
    private final long cursor;
    private final Long expectedRowCount;
    private final Long verifiedRowCount;
    private final Long mismatchCount;
    private final boolean blocked;

    RegistryMigrationReport(
        MigrationPhase phase,
        long phaseRevision,
        long cursor,
        Long expectedRowCount,
        Long verifiedRowCount,
        Long mismatchCount,
        boolean blocked) {
      this.phase = phase;
      this.phaseRevision = phaseRevision;
      this.cursor = cursor;
      this.expectedRowCount = expectedRowCount;
      this.verifiedRowCount = verifiedRowCount;
      this.mismatchCount = mismatchCount;
      this.blocked = blocked;
    }

    public MigrationPhase getPhase() {
      return phase;
    }

    public long getPhaseRevision() {
      return phaseRevision;
    }

    public long getCursor() {
      return cursor;
    }

    public Long getExpectedRowCount() {
      return expectedRowCount;
    }

    public Long getVerifiedRowCount() {
      return verifiedRowCount;
    }

    public Long getMismatchCount() {
      return mismatchCount;
    }

    public boolean isBlocked() {
      return blocked;
    }

    @Override
    public boolean equals(Object other) {
      if (this == other) {
        return true;
      }
      if (!(other instanceof RegistryMigrationReport)) {
        return false;
      }
      RegistryMigrationReport that = (RegistryMigrationReport) other;
      return phaseRevision == that.phaseRevision
          && cursor == that.cursor
          && blocked == that.blocked
          && phase == that.phase
          && Objects.equals(expectedRowCount, that.expectedRowCount)
          && Objects.equals(verifiedRowCount, that.verifiedRowCount)
          && Objects.equals(mismatchCount, that.mismatchCount);
    }

    @Override
    public int hashCode() {
      return Objects.hash(
          phase, phaseRevision, cursor, expectedRowCount, verifiedRowCount, mismatchCount, blocked);
    }

    @Override
    public String toString() {
      return "RegistryMigrationReport{phase="
          + phase
          + ", phaseRevision="
          + phaseRevision
          + ", cursor="
          + cursor
          + ", expectedRowCount="
          + expectedRowCount
          + ", verifiedRowCount="
          + verifiedRowCount
          + ", mismatchCount="
          + mismatchCount
          + ", blocked="
          + blocked
          + '}';
    }
  }
}
