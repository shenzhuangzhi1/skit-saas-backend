package cn.iocoder.yudao.module.skit.dal.mysql.provider;

import cn.iocoder.yudao.framework.tenant.core.aop.TenantIgnore;
import cn.iocoder.yudao.module.skit.dal.dataobject.provider.SkitAdCallbackRouteRegistryMigrationDO;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/** Monotonic singleton state transitions guarded again by the Task 1 database trigger. */
@Mapper
@TenantIgnore
@InterceptorIgnore(tenantLine = "true")
public interface SkitAdCallbackRouteRegistryMigrationMapper {

    @Select("SELECT * FROM skit_ad_callback_route_registry_migration WHERE singleton_id=1")
    @TenantIgnore
    @InterceptorIgnore(tenantLine = "true")
    SkitAdCallbackRouteRegistryMigrationDO selectSingleton();

    @Select("SELECT * FROM skit_ad_callback_route_registry_migration WHERE singleton_id=1 FOR UPDATE")
    @TenantIgnore
    @InterceptorIgnore(tenantLine = "true")
    SkitAdCallbackRouteRegistryMigrationDO selectSingletonForUpdate();

    @Update("UPDATE skit_ad_callback_route_registry_migration SET migration_phase=#{toPhase},"
            + "phase_revision=phase_revision+1,completed_at=#{completedAt},updated_at=#{updatedAt} "
            + "WHERE singleton_id=1 AND migration_phase=#{fromPhase} AND phase_revision=#{expectedRevision}")
    @TenantIgnore
    @InterceptorIgnore(tenantLine = "true")
    int transition(@Param("fromPhase") String fromPhase,
                   @Param("toPhase") String toPhase,
                   @Param("expectedRevision") Long expectedRevision,
                   @Param("completedAt") LocalDateTime completedAt,
                   @Param("updatedAt") LocalDateTime updatedAt);

    @Update("UPDATE skit_ad_callback_route_registry_migration SET last_callback_key_id=#{lastId},"
            + "last_batch_size=#{batchSize},phase_revision=phase_revision+1,updated_at=#{updatedAt} "
            + "WHERE singleton_id=1 AND migration_phase='BACKFILL' AND phase_revision=#{expectedRevision} "
            + "AND last_callback_key_id<=#{lastId}")
    @TenantIgnore
    @InterceptorIgnore(tenantLine = "true")
    int updateBackfillCursor(@Param("expectedRevision") Long expectedRevision,
                             @Param("lastId") Long lastId,
                             @Param("batchSize") Integer batchSize,
                             @Param("updatedAt") LocalDateTime updatedAt);

    @Update("UPDATE skit_ad_callback_route_registry_migration SET "
            + "credential_mutation_epoch=credential_mutation_epoch+1,"
            + "phase_revision=phase_revision+1,updated_at=#{updatedAt} "
            + "WHERE singleton_id=1 AND migration_phase IN ('DUAL_WRITE','BACKFILL','VERIFY') "
            + "AND phase_revision=#{expectedRevision}")
    @TenantIgnore
    @InterceptorIgnore(tenantLine = "true")
    int incrementCredentialMutationEpoch(@Param("expectedRevision") Long expectedRevision,
                                         @Param("updatedAt") LocalDateTime updatedAt);

    @Update("UPDATE skit_ad_callback_route_registry_migration SET migration_phase='VERIFY',"
            + "verification_run_id=verification_run_id+1,"
            + "verification_snapshot_epoch=credential_mutation_epoch,"
            + "verification_cursor_callback_key_id=0,verification_expected_progress_count=0,"
            + "verification_actual_progress_count=0,verification_progress_mismatch_count=0,"
            + "verification_expected_rolling_hash=#{seedHash},"
            + "verification_actual_rolling_hash=#{seedHash},"
            + "phase_revision=phase_revision+1,updated_at=#{updatedAt} "
            + "WHERE singleton_id=1 AND migration_phase='BACKFILL' "
            + "AND phase_revision=#{expectedRevision} AND blocked_at IS NULL")
    @TenantIgnore
    @InterceptorIgnore(tenantLine = "true")
    int startVerification(@Param("expectedRevision") Long expectedRevision,
                          @Param("seedHash") byte[] seedHash,
                          @Param("updatedAt") LocalDateTime updatedAt);

    @Update("UPDATE skit_ad_callback_route_registry_migration SET "
            + "verification_run_id=verification_run_id+1,"
            + "verification_snapshot_epoch=credential_mutation_epoch,"
            + "verification_cursor_callback_key_id=0,verification_expected_progress_count=0,"
            + "verification_actual_progress_count=0,verification_progress_mismatch_count=0,"
            + "verification_expected_rolling_hash=#{seedHash},"
            + "verification_actual_rolling_hash=#{seedHash},"
            + "phase_revision=phase_revision+1,updated_at=#{updatedAt} "
            + "WHERE singleton_id=1 AND migration_phase='VERIFY' "
            + "AND phase_revision=#{expectedRevision} AND verified_at IS NULL AND blocked_at IS NULL "
            + "AND verification_snapshot_epoch<>credential_mutation_epoch")
    @TenantIgnore
    @InterceptorIgnore(tenantLine = "true")
    int restartVerification(@Param("expectedRevision") Long expectedRevision,
                            @Param("seedHash") byte[] seedHash,
                            @Param("updatedAt") LocalDateTime updatedAt);

    @Update("UPDATE skit_ad_callback_route_registry_migration SET "
            + "verification_cursor_callback_key_id=#{cursor},"
            + "verification_expected_progress_count=#{expectedCount},"
            + "verification_actual_progress_count=#{actualCount},"
            + "verification_progress_mismatch_count=#{mismatchCount},"
            + "verification_expected_rolling_hash=#{expectedRollingHash},"
            + "verification_actual_rolling_hash=#{actualRollingHash},"
            + "phase_revision=phase_revision+1,updated_at=#{updatedAt} "
            + "WHERE singleton_id=1 AND migration_phase='VERIFY' "
            + "AND phase_revision=#{expectedRevision} AND verification_run_id=#{verificationRunId} "
            + "AND verification_snapshot_epoch=credential_mutation_epoch "
            + "AND verification_cursor_callback_key_id<#{cursor} "
            + "AND verification_expected_progress_count<=#{expectedCount} "
            + "AND verification_actual_progress_count<=#{actualCount} "
            + "AND verification_progress_mismatch_count<=#{mismatchCount} "
            + "AND verified_at IS NULL AND blocked_at IS NULL")
    @TenantIgnore
    @InterceptorIgnore(tenantLine = "true")
    int updateVerificationProgress(@Param("expectedRevision") Long expectedRevision,
                                   @Param("verificationRunId") Long verificationRunId,
                                   @Param("cursor") Long cursor,
                                   @Param("expectedCount") Long expectedCount,
                                   @Param("actualCount") Long actualCount,
                                   @Param("mismatchCount") Long mismatchCount,
                                   @Param("expectedRollingHash") byte[] expectedRollingHash,
                                   @Param("actualRollingHash") byte[] actualRollingHash,
                                   @Param("updatedAt") LocalDateTime updatedAt);

    @Update("UPDATE skit_ad_callback_route_registry_migration SET migration_phase='SHADOW_READ',"
            + "expected_row_count=#{expectedCount},"
            + "verified_row_count=#{verifiedCount},verification_mismatch_count=0,"
            + "verification_hash=#{verificationHash},verified_at=#{verifiedAt},"
            + "phase_revision=phase_revision+1,updated_at=#{verifiedAt} "
            + "WHERE singleton_id=1 AND migration_phase='VERIFY' AND phase_revision=#{expectedRevision} "
            + "AND verification_run_id=#{verificationRunId} "
            + "AND verification_snapshot_epoch=credential_mutation_epoch "
            + "AND verification_expected_progress_count=#{expectedCount} "
            + "AND verification_actual_progress_count=#{verifiedCount} "
            + "AND verification_expected_progress_count=verification_actual_progress_count "
            + "AND verification_progress_mismatch_count=0 "
            + "AND verification_expected_rolling_hash=verification_actual_rolling_hash "
            + "AND verified_at IS NULL AND blocked_at IS NULL")
    @TenantIgnore
    @InterceptorIgnore(tenantLine = "true")
    int completeVerificationAndEnterShadow(@Param("expectedRevision") Long expectedRevision,
                                           @Param("verificationRunId") Long verificationRunId,
                                           @Param("expectedCount") Long expectedCount,
                                           @Param("verifiedCount") Long verifiedCount,
                                           @Param("verificationHash") byte[] verificationHash,
                                           @Param("verifiedAt") LocalDateTime verifiedAt);

    @Update("UPDATE skit_ad_callback_route_registry_migration SET "
            + "blocked_reason_hash=#{blockedReasonHash},blocked_at=#{blockedAt},"
            + "phase_revision=phase_revision+1,updated_at=#{blockedAt} "
            + "WHERE singleton_id=1 AND migration_phase IN ('BACKFILL','VERIFY') "
            + "AND phase_revision=#{expectedRevision} AND verified_at IS NULL AND blocked_at IS NULL")
    @TenantIgnore
    @InterceptorIgnore(tenantLine = "true")
    int recordBlocked(@Param("expectedRevision") Long expectedRevision,
                      @Param("blockedReasonHash") byte[] blockedReasonHash,
                      @Param("blockedAt") LocalDateTime blockedAt);

}
