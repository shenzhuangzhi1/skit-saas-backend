package cn.iocoder.yudao.module.skit.dal.mysql.provider;

import cn.iocoder.yudao.framework.tenant.core.aop.TenantIgnore;
import cn.iocoder.yudao.module.skit.dal.dataobject.provider.SkitProviderConnectionReadProjection;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** Read-only mapper whose SQL cannot materialize credential or submission fields. */
@Mapper
@TenantIgnore
@InterceptorIgnore(tenantLine = "true")
public interface SkitProviderConnectionReadMapper {

  @Select(
      "SELECT c.id AS connection_id,c.provider,c.account_mode,c.state AS connection_state,"
          + "c.active_callback_route_id,c.created_at AS connection_created_at,"
          + "c.updated_at AS connection_updated_at,c.blocked_at AS connection_blocked_at,"
          + "r.id AS route_id,r.route_version,r.purpose,r.state AS route_state,r.route_slot,"
          + "r.canonical_origin,r.callback_path_version,r.callback_template_version,"
          + "r.callback_key_fingerprint,r.issued_at,r.submitted_at,r.abandoned_at,"
          + "r.updated_at AS route_updated_at FROM skit_ad_provider_connection c "
          + "LEFT JOIN skit_ad_provider_callback_route r ON r.id=COALESCE("
          + "c.active_callback_route_id,(SELECT candidate.id "
          + "FROM skit_ad_provider_callback_route candidate "
          + "WHERE candidate.provider_connection_id=c.id "
          + "AND candidate.state IN ('DRAFT','ISSUED','SUBMITTED') "
          + "ORDER BY candidate.route_version DESC,candidate.id DESC LIMIT 1)) "
          + "WHERE c.id=#{connectionId}")
  @TenantIgnore
  @InterceptorIgnore(tenantLine = "true")
  SkitProviderConnectionReadProjection selectSafeByConnectionId(
      @Param("connectionId") long connectionId);
}
