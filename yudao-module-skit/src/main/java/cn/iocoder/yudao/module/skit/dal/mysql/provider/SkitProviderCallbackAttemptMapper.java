package cn.iocoder.yudao.module.skit.dal.mysql.provider;

import cn.iocoder.yudao.framework.tenant.core.aop.TenantIgnore;
import cn.iocoder.yudao.module.skit.dal.dataobject.provider.SkitProviderCallbackAttemptDO;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

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
    @Select("SELECT * FROM skit_provider_callback_attempt "
            + "WHERE provider_connection_id=#{providerConnectionId} AND id=#{id}")
    SkitProviderCallbackAttemptDO selectByConnectionAndId(
            @Param("providerConnectionId") long providerConnectionId,
            @Param("id") long id);

    @TenantIgnore
    @InterceptorIgnore(tenantLine = "true")
    @Select("SELECT * FROM skit_provider_callback_attempt "
            + "WHERE provider_connection_id=#{providerConnectionId} AND inbox_id=#{inboxId} "
            + "ORDER BY id")
    List<SkitProviderCallbackAttemptDO> selectByInbox(
            @Param("providerConnectionId") long providerConnectionId,
            @Param("inboxId") long inboxId);
}
