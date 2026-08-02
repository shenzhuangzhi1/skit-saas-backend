package cn.iocoder.yudao.module.skit.dal.mysql.provider;

import cn.iocoder.yudao.framework.tenant.core.aop.TenantIgnore;
import cn.iocoder.yudao.module.skit.dal.dataobject.provider.SkitAdCallbackRouteRegistryDO;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/** Explicit SQL only: this global table must never receive an injected tenant predicate. */
@Mapper
@TenantIgnore
@InterceptorIgnore(tenantLine = "true")
public interface SkitAdCallbackRouteRegistryMapper {

    String TENANT_PROJECTION = "SELECT r.*,k.tenant_id,k.ad_account_id,k.key_version,k.active,"
            + "k.accept_until,k.revoked_at FROM skit_ad_callback_route_registry r "
            + "LEFT JOIN skit_ad_callback_key k ON k.id=r.tenant_callback_key_id ";

    @Select(TENANT_PROJECTION + "WHERE r.key_hash=#{keyHash} LIMIT 1")
    @TenantIgnore
    @InterceptorIgnore(tenantLine = "true")
    SkitAdCallbackRouteRegistryDO selectLookupByKeyHash(@Param("keyHash") byte[] keyHash);

    @Select(TENANT_PROJECTION + "WHERE r.tenant_callback_key_id=#{tenantCallbackKeyId} LIMIT 1")
    @TenantIgnore
    @InterceptorIgnore(tenantLine = "true")
    SkitAdCallbackRouteRegistryDO selectByTenantCallbackKeyId(
            @Param("tenantCallbackKeyId") Long tenantCallbackKeyId);

    @Insert("INSERT INTO skit_ad_callback_route_registry "
            + "(key_hash,route_type,provider_callback_route_id,tenant_callback_key_id,registered_at,tombstoned_at) "
            + "VALUES (#{keyHash},#{routeType},#{providerCallbackRouteId},#{tenantCallbackKeyId},"
            + "#{registeredAt},#{tombstonedAt})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    @TenantIgnore
    @InterceptorIgnore(tenantLine = "true")
    int insert(SkitAdCallbackRouteRegistryDO row);

    @Update("UPDATE skit_ad_callback_route_registry r JOIN skit_ad_callback_key k "
            + "ON k.id=r.tenant_callback_key_id SET r.tombstoned_at=k.revoked_at "
            + "WHERE r.route_type='TENANT_CALLBACK_KEY' AND k.tenant_id=#{tenantId} "
            + "AND k.ad_account_id=#{adAccountId} AND k.revoked_at IS NOT NULL "
            + "AND r.tombstoned_at IS NULL")
    @TenantIgnore
    @InterceptorIgnore(tenantLine = "true")
    int tombstoneRevokedTenantKeys(@Param("tenantId") Long tenantId,
                                   @Param("adAccountId") Long adAccountId);

    @Select("SELECT k.id tenant_callback_key_id,k.callback_key_hash key_hash,"
            + "k.tenant_id,k.ad_account_id,k.key_version,k.active,k.accept_until,k.revoked_at,k.create_time registered_at "
            + "FROM skit_ad_callback_key k WHERE k.id>#{afterId} ORDER BY k.id LIMIT #{limit}")
    @TenantIgnore
    @InterceptorIgnore(tenantLine = "true")
    List<SkitAdCallbackRouteRegistryDO> selectLegacyTenantKeysAfterId(
            @Param("afterId") Long afterId, @Param("limit") Integer limit);

    @Select("SELECT COUNT(*) FROM skit_ad_callback_key")
    @TenantIgnore
    @InterceptorIgnore(tenantLine = "true")
    long countLegacyTenantKeys();

    @Select("SELECT COUNT(*) FROM skit_ad_callback_route_registry "
            + "WHERE route_type='TENANT_CALLBACK_KEY'")
    @TenantIgnore
    @InterceptorIgnore(tenantLine = "true")
    long countTenantRoutes();

    @Select(TENANT_PROJECTION + "WHERE r.route_type='TENANT_CALLBACK_KEY' "
            + "AND r.tenant_callback_key_id>#{afterId} ORDER BY r.tenant_callback_key_id LIMIT #{limit}")
    @TenantIgnore
    @InterceptorIgnore(tenantLine = "true")
    List<SkitAdCallbackRouteRegistryDO> selectTenantRoutesAfterId(
            @Param("afterId") Long afterId, @Param("limit") Integer limit);

    @Select("SELECT COUNT(*) FROM skit_ad_callback_key k "
            + "LEFT JOIN skit_ad_callback_route_registry r ON r.tenant_callback_key_id=k.id "
            + "WHERE r.id IS NULL OR r.route_type<>'TENANT_CALLBACK_KEY' "
            + "OR r.provider_callback_route_id IS NOT NULL OR r.key_hash<>k.callback_key_hash "
            + "OR (k.revoked_at IS NULL AND r.tombstoned_at IS NOT NULL) "
            + "OR (k.revoked_at IS NOT NULL AND NOT (r.tombstoned_at<=>k.revoked_at))")
    @TenantIgnore
    @InterceptorIgnore(tenantLine = "true")
    long countTenantRouteMismatches();

}
