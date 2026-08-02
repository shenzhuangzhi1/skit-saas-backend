package cn.iocoder.yudao.module.skit.dal.mysql.provider;

import cn.iocoder.yudao.framework.tenant.core.aop.TenantIgnore;
import cn.iocoder.yudao.module.skit.dal.dataobject.provider.SkitAdProviderConnectionDO;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.*;

@Mapper
@TenantIgnore
@InterceptorIgnore(tenantLine = "true")
public interface SkitAdProviderConnectionMapper {
  @TenantIgnore
  @InterceptorIgnore(tenantLine = "true")
  @Insert(
      "INSERT INTO skit_ad_provider_connection"
          + " (connection_code,provider,account_mode,external_account_ref_hash,state,created_by_user_id,created_at,updated_by_user_id,updated_at)"
          + " VALUES"
          + " (#{connectionCode},#{provider},#{accountMode},#{externalAccountRefHash},#{state},#{createdByUserId},#{createdAt},#{updatedByUserId},#{updatedAt})")
  @Options(useGeneratedKeys = true, keyProperty = "id")
  int insert(SkitAdProviderConnectionDO row);

  @TenantIgnore
  @InterceptorIgnore(tenantLine = "true")
  @Select("SELECT * FROM skit_ad_provider_connection WHERE id=#{id} FOR UPDATE")
  SkitAdProviderConnectionDO selectByIdForUpdate(@Param("id") long id);

  @TenantIgnore
  @InterceptorIgnore(tenantLine = "true")
  @Select("SELECT * FROM skit_ad_provider_connection WHERE id=#{id}")
  SkitAdProviderConnectionDO selectById(@Param("id") long id);

  @TenantIgnore
  @InterceptorIgnore(tenantLine = "true")
  @Update(
      "UPDATE skit_ad_provider_connection SET"
          + " state='BLOCKED',active_callback_route_id=NULL,blocked_at=#{at},updated_by_user_id=#{actor},updated_at=#{at}"
          + " WHERE id=#{id} AND state<>'BLOCKED' AND state<>'RETIRED'")
  int block(@Param("id") long id, @Param("actor") long actor, @Param("at") LocalDateTime at);
}
