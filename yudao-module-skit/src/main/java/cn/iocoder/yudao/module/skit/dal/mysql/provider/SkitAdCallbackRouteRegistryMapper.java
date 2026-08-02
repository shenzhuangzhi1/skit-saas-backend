package cn.iocoder.yudao.module.skit.dal.mysql.provider;

import cn.iocoder.yudao.framework.tenant.core.aop.TenantIgnore;
import cn.iocoder.yudao.module.skit.dal.dataobject.provider.SkitAdCallbackRouteRegistryDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.provider.SkitAdCallbackRouteRegistryVerificationRow;
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

    @Select("SELECT * FROM skit_ad_callback_route_registry WHERE provider_callback_route_id=#{providerRouteId} LIMIT 1")
    @TenantIgnore
    @InterceptorIgnore(tenantLine = "true")
    SkitAdCallbackRouteRegistryDO selectByProviderCallbackRouteId(
            @Param("providerRouteId") Long providerRouteId);

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

    @Update("UPDATE skit_ad_callback_route_registry SET tombstoned_at=#{at} "
            + "WHERE provider_callback_route_id=#{providerRouteId} AND route_type='PROVIDER_CALLBACK_ROUTE' "
            + "AND tombstoned_at IS NULL")
    @TenantIgnore
    @InterceptorIgnore(tenantLine = "true")
    int tombstoneProviderRoute(@Param("providerRouteId") Long providerRouteId,
                               @Param("at") LocalDateTime at);

    @Select("SELECT k.id tenant_callback_key_id,k.callback_key_hash key_hash,"
            + "k.tenant_id,k.ad_account_id,k.key_version,k.active,k.accept_until,k.revoked_at,k.create_time registered_at "
            + "FROM skit_ad_callback_key k WHERE k.id>#{afterId} ORDER BY k.id LIMIT #{limit}")
    @TenantIgnore
    @InterceptorIgnore(tenantLine = "true")
    List<SkitAdCallbackRouteRegistryDO> selectLegacyTenantKeysAfterId(
            @Param("afterId") Long afterId, @Param("limit") Integer limit);

    @Select("SELECT k.id tenant_callback_key_id,k.tenant_id expected_tenant_id,"
            + "k.ad_account_id expected_ad_account_id,k.key_version expected_key_version,"
            + "k.active expected_active,k.accept_until expected_accept_until,"
            + "k.callback_key_hash expected_key_hash,k.revoked_at expected_tombstoned_at,"
            + "r.id registry_id,r.route_type actual_route_type,"
            + "r.provider_callback_route_id actual_provider_callback_route_id,"
            + "r.tenant_callback_key_id actual_tenant_callback_key_id,"
            + "actual_k.tenant_id actual_tenant_id,actual_k.ad_account_id actual_ad_account_id,"
            + "actual_k.key_version actual_key_version,actual_k.active actual_active,"
            + "actual_k.accept_until actual_accept_until,r.key_hash actual_key_hash,"
            + "r.tombstoned_at actual_tombstoned_at FROM skit_ad_callback_key k "
            + "LEFT JOIN skit_ad_callback_route_registry r ON r.tenant_callback_key_id=k.id "
            + "LEFT JOIN skit_ad_callback_key actual_k ON actual_k.id=r.tenant_callback_key_id "
            + "WHERE k.id>#{afterId} ORDER BY k.id LIMIT #{limit}")
    @TenantIgnore
    @InterceptorIgnore(tenantLine = "true")
    List<SkitAdCallbackRouteRegistryVerificationRow> selectVerificationPairsAfterId(
            @Param("afterId") Long afterId, @Param("limit") Integer limit);

}
