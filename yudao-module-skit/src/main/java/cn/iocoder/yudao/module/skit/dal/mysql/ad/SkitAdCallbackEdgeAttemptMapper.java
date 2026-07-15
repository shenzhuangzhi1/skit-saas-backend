package cn.iocoder.yudao.module.skit.dal.mysql.ad;

import cn.iocoder.yudao.framework.tenant.core.aop.TenantIgnore;
import cn.iocoder.yudao.module.skit.dal.dataobject.ad.SkitAdCallbackEdgeAttemptDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.ad.SkitAdRetentionClaimDO;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
@TenantIgnore
public interface SkitAdCallbackEdgeAttemptMapper {

    @Select("SELECT `tenant_id`,`ad_account_id`,`id` FROM `skit_ad_callback_edge_attempt` "
            + "WHERE `received_at`<DATE_SUB(CURRENT_TIMESTAMP,INTERVAL #{retentionDays} DAY) "
            + "ORDER BY `received_at`,`id` LIMIT #{limit}")
    List<SkitAdRetentionClaimDO> selectExpiredRetentionClaims(
            @Param("retentionDays") int retentionDays, @Param("limit") int limit);

    @Insert("INSERT INTO `skit_ad_callback_edge_attempt` "
            + "(`tenant_id`,`ad_account_id`,`callback_key_hash`,`provider`,`callback_type`,"
            + "`client_ip_hash`,`request_method`,`result_code`,`received_at`,`creator`,`updater`) VALUES "
            + "(#{tenantId},#{adAccountId},#{callbackKeyHash},#{provider},#{callbackType},"
            + "#{clientIpHash},#{requestMethod},#{resultCode},#{receivedAt},"
            + "'callback-edge','callback-edge')")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(SkitAdCallbackEdgeAttemptDO row);

    @Delete("DELETE FROM `skit_ad_callback_edge_attempt` WHERE `tenant_id`=#{tenantId} "
            + "AND `ad_account_id`=#{adAccountId} AND `id`=#{id} "
            + "AND `received_at`<DATE_SUB(CURRENT_TIMESTAMP,INTERVAL #{retentionDays} DAY)")
    int deleteExpiredKnownRouteClaimCas(@Param("tenantId") Long tenantId,
                                        @Param("adAccountId") Long adAccountId,
                                        @Param("id") Long id,
                                        @Param("retentionDays") int retentionDays);

    @Delete("DELETE FROM `skit_ad_callback_edge_attempt` WHERE `tenant_id` IS NULL "
            + "AND `ad_account_id` IS NULL AND `id`=#{id} "
            + "AND `received_at`<DATE_SUB(CURRENT_TIMESTAMP,INTERVAL #{retentionDays} DAY)")
    int deleteExpiredUnknownRouteClaimCas(@Param("id") Long id,
                                          @Param("retentionDays") int retentionDays);

}
