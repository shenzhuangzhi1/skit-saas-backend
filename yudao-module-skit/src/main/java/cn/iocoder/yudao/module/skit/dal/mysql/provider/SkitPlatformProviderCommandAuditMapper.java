package cn.iocoder.yudao.module.skit.dal.mysql.provider;

import cn.iocoder.yudao.framework.tenant.core.aop.TenantIgnore;
import cn.iocoder.yudao.module.skit.dal.dataobject.provider.SkitPlatformProviderCommandAuditDO;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;

@Mapper
@TenantIgnore
@InterceptorIgnore(tenantLine = "true")
public interface SkitPlatformProviderCommandAuditMapper {

  @Insert(
      "INSERT INTO skit_platform_provider_command_audit "
          + "(actor_user_id,original_login_tenant_id,action,provider_connection_id,"
          + "provider_callback_route_id,callback_route_registry_id,reason,reauthenticated_at,"
          + "request_fingerprint,before_state_hash,after_state_hash,trace_id,result_status,"
          + "result_code,occurred_at) VALUES (#{actorUserId},#{originalLoginTenantId},#{action},"
          + "#{providerConnectionId},#{providerCallbackRouteId},#{callbackRouteRegistryId},"
          + "#{reason},#{reauthenticatedAt},#{requestFingerprint},#{beforeStateHash},"
          + "#{afterStateHash},#{traceId},#{resultStatus},#{resultCode},#{occurredAt})")
  @Options(useGeneratedKeys = true, keyProperty = "id")
  @TenantIgnore
  @InterceptorIgnore(tenantLine = "true")
  int insert(SkitPlatformProviderCommandAuditDO row);
}
