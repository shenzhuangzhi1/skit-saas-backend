package cn.iocoder.yudao.module.skit.dal.mysql.provider;

import cn.iocoder.yudao.framework.tenant.core.aop.TenantIgnore;
import cn.iocoder.yudao.module.skit.dal.dataobject.provider.SkitAdProviderCallbackRouteDO;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.*;

@Mapper
@TenantIgnore
@InterceptorIgnore(tenantLine = "true")
public interface SkitAdProviderCallbackRouteMapper {
  @TenantIgnore
  @InterceptorIgnore(tenantLine = "true")
  @Insert(
      "INSERT INTO skit_ad_provider_callback_route"
          + " (provider_connection_id,route_version,purpose,state,route_slot,created_by_user_id,created_at,updated_by_user_id,updated_at)"
          + " VALUES"
          + " (#{providerConnectionId},#{routeVersion},#{purpose},'DRAFT','INACTIVE',#{createdByUserId},#{createdAt},#{updatedByUserId},#{updatedAt})")
  @Options(useGeneratedKeys = true, keyProperty = "id")
  int insertDraft(SkitAdProviderCallbackRouteDO row);

  @TenantIgnore
  @InterceptorIgnore(tenantLine = "true")
  @Select("SELECT * FROM skit_ad_provider_callback_route WHERE id=#{id} FOR UPDATE")
  SkitAdProviderCallbackRouteDO selectByIdForUpdate(@Param("id") long id);

  @TenantIgnore
  @InterceptorIgnore(tenantLine = "true")
  @Select("SELECT * FROM skit_ad_provider_callback_route WHERE id=#{id}")
  SkitAdProviderCallbackRouteDO selectById(@Param("id") long id);

  @TenantIgnore
  @InterceptorIgnore(tenantLine = "true")
  @Select(
      "SELECT COALESCE(MAX(route_version),0) FROM skit_ad_provider_callback_route WHERE"
          + " provider_connection_id=#{connectionId}")
  Integer selectMaxRouteVersion(@Param("connectionId") long connectionId);

  @TenantIgnore
  @InterceptorIgnore(tenantLine = "true")
  @Select(
      "SELECT * FROM skit_ad_provider_callback_route WHERE provider_connection_id=#{connectionId}"
          + " AND route_slot<>'INACTIVE' AND state NOT IN ('ABANDONED','RETIRED') FOR UPDATE")
  List<SkitAdProviderCallbackRouteDO> selectAcceptingForUpdate(
      @Param("connectionId") long connectionId);

  @TenantIgnore
  @InterceptorIgnore(tenantLine = "true")
  @Update(
      "UPDATE skit_ad_provider_callback_route SET"
          + " callback_route_registry_id=#{registryId},callback_key_fingerprint=#{fingerprint},canonical_origin=#{origin},callback_path_version=#{pathVersion},callback_template_version=#{templateVersion},callback_origin_fingerprint=#{originHash},callback_contract_fingerprint=#{contractHash},state='ISSUED',route_slot='PRIMARY_ACCEPTING',issued_at=#{at},issued_by_user_id=#{actor},updated_by_user_id=#{actor},updated_at=#{at}"
          + " WHERE id=#{id} AND state='DRAFT' AND callback_route_registry_id IS NULL")
  int issueCas(
      @Param("id") long id,
      @Param("registryId") long registryId,
      @Param("fingerprint") String fingerprint,
      @Param("origin") String origin,
      @Param("pathVersion") int pathVersion,
      @Param("templateVersion") int templateVersion,
      @Param("originHash") byte[] originHash,
      @Param("contractHash") byte[] contractHash,
      @Param("actor") long actor,
      @Param("at") LocalDateTime at);

  @TenantIgnore
  @InterceptorIgnore(tenantLine = "true")
  @Update(
      "UPDATE skit_ad_provider_callback_route SET"
          + " state='ABANDONED',route_slot='INACTIVE',abandoned_at=#{at},updated_by_user_id=#{actor},updated_at=#{at}"
          + " WHERE id=#{id} AND state='ISSUED' AND purpose=#{purpose}")
  int abandonCas(
      @Param("id") long id,
      @Param("purpose") String purpose,
      @Param("actor") long actor,
      @Param("at") LocalDateTime at);

  @TenantIgnore
  @InterceptorIgnore(tenantLine = "true")
  @Update(
      "UPDATE skit_ad_provider_callback_route SET"
          + " state='SUBMITTED',submission_ticket=#{ticket},submission_reference=#{reference},submission_recipient=#{recipient},submitted_by_user_id=#{actor},submitted_at=#{at},updated_by_user_id=#{actor},updated_at=#{at}"
          + " WHERE id=#{id} AND state='ISSUED' AND purpose='PRODUCTION'")
  int submitCas(
      @Param("id") long id,
      @Param("ticket") String ticket,
      @Param("reference") String reference,
      @Param("recipient") String recipient,
      @Param("actor") long actor,
      @Param("at") LocalDateTime at);

  @TenantIgnore
  @InterceptorIgnore(tenantLine = "true")
  @Update(
      "UPDATE skit_ad_provider_callback_route SET"
          + " state='BLOCKED',route_slot='INACTIVE',blocked_at=#{at},updated_by_user_id=#{actor},updated_at=#{at}"
          + " WHERE provider_connection_id=#{connectionId} AND state IN"
          + " ('ISSUED','SUBMITTED','ACTIVE')")
  int blockAccepting(
      @Param("connectionId") long connectionId,
      @Param("actor") long actor,
      @Param("at") LocalDateTime at);
}
