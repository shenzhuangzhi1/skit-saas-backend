package cn.iocoder.yudao.module.skit.dal.mysql.provider;

import cn.iocoder.yudao.framework.tenant.core.aop.TenantIgnore;
import cn.iocoder.yudao.module.skit.dal.dataobject.provider.SkitProviderImpressionTenantRoute;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** Global, server-owned lookup from a provider observation to one exact tenant session. */
@Mapper
@TenantIgnore
@InterceptorIgnore(tenantLine = "true")
public interface SkitProviderImpressionAttributionMapper {

    @TenantIgnore
    @InterceptorIgnore(tenantLine = "true")
    @Select("SELECT s.tenant_id,s.ad_account_id,s.id AS ad_session_id,s.callback_key_version "
            + "FROM skit_ad_provider_connection p "
            + "JOIN skit_ad_session s ON BINARY s.session_id=BINARY #{showCustomExt} "
            + "AND s.provider='TAKU' AND s.deleted=b'0' "
            + "JOIN skit_ad_account a ON a.tenant_id=s.tenant_id AND a.id=s.ad_account_id "
            + "AND a.provider='TAKU' AND a.status=0 AND a.deleted=b'0' "
            + "JOIN skit_tenant_ad_capability c ON c.tenant_id=s.tenant_id "
            + "AND c.ad_account_id=s.ad_account_id AND c.deleted=b'0' "
            + "WHERE p.id=#{providerConnectionId} AND p.provider='TAKU' "
            + "AND ((p.account_mode='SHARED_MASTER' AND NOT EXISTS (SELECT 1 "
            + "FROM skit_ad_provider_connection owned WHERE owned.provider='TAKU' "
            + "AND owned.account_mode='TENANT_OWNED' AND owned.state<>'RETIRED' "
            + "AND owned.owner_tenant_id=s.tenant_id "
            + "AND owned.owner_ad_account_id=s.ad_account_id)) "
            + "OR (p.account_mode='TENANT_OWNED' AND p.owner_tenant_id=s.tenant_id "
            + "AND p.owner_ad_account_id=s.ad_account_id)) "
            + "AND BINARY s.placement_id=BINARY #{placementId} "
            + "AND BINARY s.pseudonymous_user_id=BINARY #{userId} "
            + "AND JSON_VALID(a.config_data) "
            + "AND BINARY JSON_UNQUOTE(JSON_EXTRACT(a.config_data,'$.placementId'))="
            + "BINARY #{placementId} "
            + "AND BINARY c.dedicated_unlock_placement_id=BINARY #{placementId} "
            + "AND EXISTS (SELECT 1 FROM skit_app_release_profile rp "
            + "WHERE rp.tenant_id=s.tenant_id AND rp.status=0 AND rp.deleted=b'0' "
            + "AND BINARY rp.native_package=BINARY #{packageName}) LIMIT 2 FOR SHARE")
    List<SkitProviderImpressionTenantRoute> selectExactRoute(
            @Param("providerConnectionId") long providerConnectionId,
            @Param("showCustomExt") String showCustomExt,
            @Param("packageName") String packageName,
            @Param("placementId") String placementId,
            @Param("userId") String userId);
}
