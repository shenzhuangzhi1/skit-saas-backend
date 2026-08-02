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

    @Update("UPDATE skit_ad_callback_route_registry_migration SET expected_row_count=#{expectedCount},"
            + "verified_row_count=#{verifiedCount},verification_mismatch_count=#{mismatchCount},"
            + "verification_hash=#{verificationHash},verified_at=#{verifiedAt},"
            + "blocked_reason_hash=#{blockedReasonHash},blocked_at=#{blockedAt},"
            + "phase_revision=phase_revision+1,updated_at=#{verifiedAt} "
            + "WHERE singleton_id=1 AND migration_phase='VERIFY' AND phase_revision=#{expectedRevision} "
            + "AND verified_at IS NULL")
    @TenantIgnore
    @InterceptorIgnore(tenantLine = "true")
    int recordVerification(@Param("expectedRevision") Long expectedRevision,
                           @Param("expectedCount") Long expectedCount,
                           @Param("verifiedCount") Long verifiedCount,
                           @Param("mismatchCount") Long mismatchCount,
                           @Param("verificationHash") byte[] verificationHash,
                           @Param("verifiedAt") LocalDateTime verifiedAt,
                           @Param("blockedReasonHash") byte[] blockedReasonHash,
                           @Param("blockedAt") LocalDateTime blockedAt);

    @Update("UPDATE skit_ad_callback_route_registry_migration SET "
            + "blocked_reason_hash=#{blockedReasonHash},blocked_at=#{blockedAt},"
            + "phase_revision=phase_revision+1,updated_at=#{blockedAt} "
            + "WHERE singleton_id=1 AND migration_phase='VERIFY' AND phase_revision=#{expectedRevision} "
            + "AND verified_at IS NULL AND blocked_at IS NULL")
    @TenantIgnore
    @InterceptorIgnore(tenantLine = "true")
    int recordBlocked(@Param("expectedRevision") Long expectedRevision,
                      @Param("blockedReasonHash") byte[] blockedReasonHash,
                      @Param("blockedAt") LocalDateTime blockedAt);

}
