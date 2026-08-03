package cn.iocoder.yudao.module.skit.dal.mysql.provider;

import cn.iocoder.yudao.framework.tenant.core.aop.TenantIgnore;
import cn.iocoder.yudao.module.skit.dal.dataobject.provider.SkitProviderCallbackAttemptDO;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
@TenantIgnore
@InterceptorIgnore(tenantLine = "true")
public interface SkitProviderCallbackAttemptMapper {

    @TenantIgnore
    @InterceptorIgnore(tenantLine = "true")
    @Insert("INSERT INTO skit_provider_callback_attempt (correlation_id,provider_connection_id,"
            + "inbox_id,dedupe_scheme,wire_payload_hash,material_integrity_hash,"
            + "delivery_integrity_status,response_decision,payload_ciphertext,payload_nonce,"
            + "payload_key_id,payload_purpose,payload_envelope_version,payload_expires_at,"
            + "wire_size_bytes,parameter_count,remote_address_hash,user_agent_hash,"
            + "request_header_fingerprint,trace_id,received_at) VALUES (#{correlationId},"
            + "#{providerConnectionId},#{inboxId},#{dedupeScheme},#{wirePayloadHash},"
            + "#{materialIntegrityHash},#{deliveryIntegrityStatus},#{responseDecision},"
            + "#{payloadCiphertext},#{payloadNonce},#{payloadKeyId},#{payloadPurpose},"
            + "#{payloadEnvelopeVersion},#{payloadExpiresAt},#{wireSizeBytes},#{parameterCount},"
            + "#{remoteAddressHash},#{userAgentHash},#{requestHeaderFingerprint},#{traceId},"
            + "#{receivedAt})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(SkitProviderCallbackAttemptDO row);

    @TenantIgnore
    @InterceptorIgnore(tenantLine = "true")
    @Select("SELECT wire_payload_hash FROM skit_provider_callback_attempt "
            + "WHERE provider_connection_id=#{providerConnectionId} AND id=#{id}")
    SkitProviderCallbackAttemptDO selectWirePayloadHashByConnectionAndId(
            @Param("providerConnectionId") long providerConnectionId,
            @Param("id") long id);

    @TenantIgnore
    @InterceptorIgnore(tenantLine = "true")
    @Select("SELECT a.* FROM skit_provider_callback_attempt a "
            + "JOIN skit_provider_impression_inbox i ON i.provider_connection_id=a.provider_connection_id "
            + "AND i.id=a.inbox_id AND i.canonical_attempt_id=a.id "
            + "WHERE a.provider_connection_id=#{providerConnectionId} AND a.inbox_id=#{inboxId} "
            + "AND a.id=#{attemptId} AND a.delivery_integrity_status='CANONICAL'")
    SkitProviderCallbackAttemptDO selectCanonicalPayload(
            @Param("providerConnectionId") long providerConnectionId,
            @Param("inboxId") long inboxId,
            @Param("attemptId") long attemptId);

    @TenantIgnore
    @InterceptorIgnore(tenantLine = "true")
    @Select("SELECT a.id,a.provider_connection_id,a.inbox_id "
            + "FROM skit_provider_callback_attempt a "
            + "WHERE a.payload_ciphertext IS NOT NULL "
            + "AND a.payload_expires_at<=LEAST(#{now},UTC_TIMESTAMP()) "
            + "AND EXISTS (SELECT 1 FROM skit_provider_impression_inbox i "
            + "WHERE i.provider_connection_id=a.provider_connection_id AND i.id=a.inbox_id "
            + "AND (((i.processing_status='SUCCEEDED' OR i.processing_status='QUARANTINED') "
            + "AND i.processed_at IS NOT NULL) OR (i.processing_status='DEAD_LETTER' "
            + "AND i.processed_at IS NOT NULL AND i.dead_letter_alerted_at IS NOT NULL))) "
            + "ORDER BY a.payload_expires_at,a.id LIMIT #{limit} FOR UPDATE SKIP LOCKED")
    List<SkitProviderCallbackAttemptDO> selectEligiblePayloadsForPurge(
            @Param("now") LocalDateTime now, @Param("limit") int limit);

    @TenantIgnore
    @InterceptorIgnore(tenantLine = "true")
    @Update("UPDATE skit_provider_callback_attempt a "
            + "JOIN skit_provider_impression_inbox i "
            + "ON i.provider_connection_id=a.provider_connection_id AND i.id=a.inbox_id "
            + "SET a.payload_ciphertext=NULL,a.payload_nonce=NULL,a.payload_key_id=NULL,"
            + "a.payload_purpose=NULL,a.payload_envelope_version=NULL,"
            + "a.payload_expires_at=NULL,a.payload_purged_at=LEAST(#{now},UTC_TIMESTAMP()) "
            + "WHERE a.id=#{id} AND a.provider_connection_id=#{providerConnectionId} "
            + "AND a.inbox_id=#{inboxId} AND a.payload_ciphertext IS NOT NULL "
            + "AND a.payload_expires_at<=LEAST(#{now},UTC_TIMESTAMP()) "
            + "AND (((i.processing_status='SUCCEEDED' OR i.processing_status='QUARANTINED') "
            + "AND i.processed_at IS NOT NULL) OR (i.processing_status='DEAD_LETTER' "
            + "AND i.processed_at IS NOT NULL AND i.dead_letter_alerted_at IS NOT NULL))")
    int purgeEligiblePayload(@Param("id") long id,
                             @Param("providerConnectionId") long providerConnectionId,
                             @Param("inboxId") long inboxId,
                             @Param("now") LocalDateTime now);
}
