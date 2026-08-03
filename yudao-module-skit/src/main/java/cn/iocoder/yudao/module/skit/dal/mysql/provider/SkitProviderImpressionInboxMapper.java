package cn.iocoder.yudao.module.skit.dal.mysql.provider;

import cn.iocoder.yudao.framework.tenant.core.aop.TenantIgnore;
import cn.iocoder.yudao.module.skit.dal.dataobject.provider.SkitProviderImpressionInboxDO;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.SelectKey;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
@TenantIgnore
@InterceptorIgnore(tenantLine = "true")
public interface SkitProviderImpressionInboxMapper {

    @TenantIgnore
    @InterceptorIgnore(tenantLine = "true")
    @Insert("INSERT INTO skit_provider_impression_inbox (provider_connection_id,dedupe_scheme,"
            + "dedupe_key_hash,provider_request_id_lexical,adsource_id_lexical,"
            + "material_integrity_hash,authentication_level,integrity_status,integrity_revision,"
            + "processing_status,quarantine_reason,processing_attempt_count,first_received_at,"
            + "last_received_at) VALUES (#{providerConnectionId},#{dedupeScheme},#{dedupeKeyHash},"
            + "#{providerRequestIdLexical},#{adsourceIdLexical},#{materialIntegrityHash},"
            + "#{authenticationLevel},#{integrityStatus},#{integrityRevision},#{processingStatus},"
            + "#{quarantineReason},#{processingAttemptCount},#{firstReceivedAt},#{lastReceivedAt}) "
            + "ON DUPLICATE KEY UPDATE id=LAST_INSERT_ID(id)")
    @SelectKey(statement = "SELECT LAST_INSERT_ID()", keyProperty = "id",
            before = false, resultType = Long.class)
    int insertOrGetCanonical(SkitProviderImpressionInboxDO row);

    @TenantIgnore
    @InterceptorIgnore(tenantLine = "true")
    @Select("SELECT * FROM skit_provider_impression_inbox "
            + "WHERE provider_connection_id=#{providerConnectionId} AND id=#{id} FOR UPDATE")
    SkitProviderImpressionInboxDO selectByConnectionAndIdForUpdate(
            @Param("providerConnectionId") long providerConnectionId,
            @Param("id") long id);

    @TenantIgnore
    @InterceptorIgnore(tenantLine = "true")
    @Select("SELECT * FROM skit_provider_impression_inbox "
            + "WHERE provider_connection_id=#{providerConnectionId} AND id=#{id}")
    SkitProviderImpressionInboxDO selectByConnectionAndId(
            @Param("providerConnectionId") long providerConnectionId,
            @Param("id") long id);

    @TenantIgnore
    @InterceptorIgnore(tenantLine = "true")
    @Update("UPDATE skit_provider_impression_inbox SET canonical_attempt_id=#{attemptId} "
            + "WHERE provider_connection_id=#{providerConnectionId} AND id=#{id} "
            + "AND canonical_attempt_id IS NULL")
    int bindCanonicalAttemptCas(@Param("providerConnectionId") long providerConnectionId,
                                @Param("id") long id,
                                @Param("attemptId") long attemptId);

    @TenantIgnore
    @InterceptorIgnore(tenantLine = "true")
    @Update("UPDATE skit_provider_impression_inbox SET integrity_status='PAYLOAD_CONFLICT',"
            + "integrity_revision=integrity_revision+1,"
            + "integrity_conflict_at=COALESCE(integrity_conflict_at,#{conflictAt}),"
            + "quarantine_reason=CASE WHEN processing_status IN "
            + "('PENDING','PROCESSING','RETRY_WAIT') "
            + "THEN #{reason} ELSE quarantine_reason END,"
            + "lease_owner=CASE WHEN processing_status IN "
            + "('PENDING','PROCESSING','RETRY_WAIT') "
            + "THEN NULL ELSE lease_owner END,"
            + "lease_until=CASE WHEN processing_status IN "
            + "('PENDING','PROCESSING','RETRY_WAIT') "
            + "THEN NULL ELSE lease_until END,"
            + "next_attempt_at=CASE WHEN processing_status IN "
            + "('PENDING','PROCESSING','RETRY_WAIT') "
            + "THEN NULL ELSE next_attempt_at END,"
            + "processing_status=CASE WHEN processing_status IN "
            + "('PENDING','PROCESSING','RETRY_WAIT') "
            + "THEN 'QUARANTINED' ELSE processing_status END "
            + "WHERE provider_connection_id=#{providerConnectionId} AND id=#{id} "
            + "AND integrity_status IN ('CANONICAL','PAYLOAD_CONFLICT')")
    int markPayloadConflictCas(@Param("providerConnectionId") long providerConnectionId,
                               @Param("id") long id,
                               @Param("conflictAt") LocalDateTime conflictAt,
                               @Param("reason") String reason);

    @TenantIgnore
    @InterceptorIgnore(tenantLine = "true")
    @Update("UPDATE skit_provider_impression_inbox SET quarantine_reason=#{reason},"
            + "lease_owner=NULL,lease_until=NULL,next_attempt_at=NULL,"
            + "processing_status='QUARANTINED' "
            + "WHERE provider_connection_id=#{providerConnectionId} AND id=#{id} "
            + "AND processing_status IN ('PENDING','PROCESSING')")
    int quarantineActiveCas(@Param("providerConnectionId") long providerConnectionId,
                            @Param("id") long id,
                            @Param("reason") String reason);

    @TenantIgnore
    @InterceptorIgnore(tenantLine = "true")
    @Update("UPDATE skit_provider_impression_inbox SET last_received_at="
            + "GREATEST(last_received_at,#{receivedAt}) "
            + "WHERE provider_connection_id=#{providerConnectionId} AND id=#{id}")
    int updateLastReceivedAt(@Param("providerConnectionId") long providerConnectionId,
                             @Param("id") long id,
                             @Param("receivedAt") LocalDateTime receivedAt);

    @TenantIgnore
    @InterceptorIgnore(tenantLine = "true")
    @Select("SELECT provider_connection_id,id,canonical_attempt_id,processing_attempt_count "
            + "FROM skit_provider_impression_inbox WHERE dedupe_scheme='OFFICIAL_V1' "
            + "AND integrity_status='CANONICAL' AND integrity_revision=0 "
            + "AND canonical_attempt_id IS NOT NULL AND (processing_status='PENDING' OR "
            + "(processing_status='RETRY_WAIT' AND next_attempt_at<=UTC_TIMESTAMP()) OR "
            + "(processing_status='PROCESSING' AND lease_until<=UTC_TIMESTAMP())) "
            + "ORDER BY id LIMIT #{limit} FOR UPDATE SKIP LOCKED")
    List<SkitProviderImpressionInboxDO> selectReadyAttributionClaimsForUpdate(
            @Param("limit") int limit);

    @TenantIgnore
    @InterceptorIgnore(tenantLine = "true")
    @Update("UPDATE skit_provider_impression_inbox SET processing_status='DEAD_LETTER',"
            + "quarantine_reason=#{reason},lease_owner=NULL,lease_until=NULL,next_attempt_at=NULL,"
            + "processed_at=UTC_TIMESTAMP() "
            + "WHERE provider_connection_id=#{providerConnectionId} AND id=#{id} "
            + "AND processing_status='PROCESSING' AND lease_until<=UTC_TIMESTAMP() "
            + "AND processing_attempt_count>=#{maxAttempts} AND dedupe_scheme='OFFICIAL_V1' "
            + "AND integrity_status='CANONICAL' AND integrity_revision=0")
    int markExpiredAttributionDeadLetterCas(
            @Param("providerConnectionId") long providerConnectionId,
            @Param("id") long id,
            @Param("reason") String reason,
            @Param("maxAttempts") int maxAttempts);

    @TenantIgnore
    @InterceptorIgnore(tenantLine = "true")
    @Update("UPDATE skit_provider_impression_inbox SET processing_status='PROCESSING',"
            + "quarantine_reason=NULL,lease_owner=#{leaseOwner},"
            + "lease_until=TIMESTAMPADD(SECOND,#{leaseSeconds},UTC_TIMESTAMP()),"
            + "processing_attempt_count=processing_attempt_count+1,next_attempt_at=NULL,"
            + "processed_at=NULL WHERE provider_connection_id=#{providerConnectionId} AND id=#{id} "
            + "AND processing_attempt_count<#{maxAttempts} AND dedupe_scheme='OFFICIAL_V1' "
            + "AND integrity_status='CANONICAL' AND integrity_revision=0 "
            + "AND canonical_attempt_id IS NOT NULL AND (processing_status='PENDING' OR "
            + "(processing_status='RETRY_WAIT' AND next_attempt_at<=UTC_TIMESTAMP()) OR "
            + "(processing_status='PROCESSING' AND lease_until<=UTC_TIMESTAMP()))")
    int claimForAttributionCas(@Param("providerConnectionId") long providerConnectionId,
                               @Param("id") long id,
                               @Param("leaseOwner") String leaseOwner,
                               @Param("leaseSeconds") int leaseSeconds,
                               @Param("maxAttempts") int maxAttempts);

    @TenantIgnore
    @InterceptorIgnore(tenantLine = "true")
    @Update("UPDATE skit_provider_impression_inbox SET processing_status='DEAD_LETTER',"
            + "quarantine_reason=#{reason},lease_owner=NULL,lease_until=NULL,next_attempt_at=NULL,"
            + "processed_at=UTC_TIMESTAMP() "
            + "WHERE provider_connection_id=#{providerConnectionId} AND id=#{id} "
            + "AND processing_status='PROCESSING' AND lease_owner=#{leaseOwner} "
            + "AND lease_until>=UTC_TIMESTAMP() AND processing_attempt_count>=#{maxAttempts}")
    int markAttributionDeadLetterCas(@Param("providerConnectionId") long providerConnectionId,
                                     @Param("id") long id,
                                     @Param("leaseOwner") String leaseOwner,
                                     @Param("reason") String reason,
                                     @Param("maxAttempts") int maxAttempts);

    @TenantIgnore
    @InterceptorIgnore(tenantLine = "true")
    @Update("UPDATE skit_provider_impression_inbox SET processing_status='RETRY_WAIT',"
            + "quarantine_reason=#{reason},lease_owner=NULL,lease_until=NULL,"
            + "next_attempt_at=TIMESTAMPADD(SECOND,#{backoffSeconds},UTC_TIMESTAMP()),"
            + "processed_at=NULL WHERE provider_connection_id=#{providerConnectionId} AND id=#{id} "
            + "AND processing_status='PROCESSING' AND lease_owner=#{leaseOwner} "
            + "AND lease_until>=UTC_TIMESTAMP() AND processing_attempt_count<#{maxAttempts}")
    int markAttributionRetryWaitCas(@Param("providerConnectionId") long providerConnectionId,
                                    @Param("id") long id,
                                    @Param("leaseOwner") String leaseOwner,
                                    @Param("reason") String reason,
                                    @Param("maxAttempts") int maxAttempts,
                                    @Param("backoffSeconds") int backoffSeconds);

    @TenantIgnore
    @InterceptorIgnore(tenantLine = "true")
    @Update("UPDATE skit_provider_impression_inbox SET processing_status='SUCCEEDED',"
            + "lease_owner=NULL,lease_until=NULL,next_attempt_at=NULL,processed_at=#{processedAt} "
            + "WHERE provider_connection_id=#{providerConnectionId} AND id=#{id} "
            + "AND processing_status='PROCESSING' AND lease_owner=#{leaseOwner} "
            + "AND lease_until>=UTC_TIMESTAMP() AND dedupe_scheme='OFFICIAL_V1' "
            + "AND integrity_status='CANONICAL' AND integrity_revision=0")
    int markAttributionSucceededCas(@Param("providerConnectionId") long providerConnectionId,
                                    @Param("id") long id,
                                    @Param("leaseOwner") String leaseOwner,
                                    @Param("processedAt") LocalDateTime processedAt);

    @TenantIgnore
    @InterceptorIgnore(tenantLine = "true")
    @Update("UPDATE skit_provider_impression_inbox SET processing_status='QUARANTINED',"
            + "quarantine_reason=#{reason},lease_owner=NULL,lease_until=NULL,next_attempt_at=NULL,"
            + "processed_at=#{processedAt} WHERE provider_connection_id=#{providerConnectionId} "
            + "AND id=#{id} AND processing_status='PROCESSING' AND lease_owner=#{leaseOwner} "
            + "AND lease_until>=UTC_TIMESTAMP() AND dedupe_scheme='OFFICIAL_V1' "
            + "AND integrity_status='CANONICAL' AND integrity_revision=0")
    int markAttributionQuarantinedCas(@Param("providerConnectionId") long providerConnectionId,
                                      @Param("id") long id,
                                      @Param("leaseOwner") String leaseOwner,
                                      @Param("reason") String reason,
                                      @Param("processedAt") LocalDateTime processedAt);
}
