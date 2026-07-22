package cn.iocoder.yudao.module.skit.dal.mysql.ad;

import cn.iocoder.yudao.module.skit.dal.dataobject.ad.SkitAdNetworkCapabilityDO;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface SkitAdNetworkCapabilityMapper {

    @Insert("INSERT INTO `skit_ad_network_capability` "
            + "(`tenant_id`,`ad_account_id`,`network_firm_id`,`reward_authority`,"
            + "`supports_user_id`,`supports_custom_data`,`supports_stable_transaction`,"
            + "`supports_impression_revenue`,`supports_reporting`,`enabled`,`verified_at`,"
            + "`creator`,`updater`,`deleted`) VALUES "
            + "(#{tenantId},#{adAccountId},#{networkFirmId},#{rewardAuthority},"
            + "#{supportsUserId},#{supportsCustomData},#{supportsStableTransaction},"
            + "#{supportsImpressionRevenue},#{supportsReporting},b'1',CURRENT_TIMESTAMP,"
            + "'capability-verification','capability-verification',b'0') "
            + "ON DUPLICATE KEY UPDATE `reward_authority`=#{rewardAuthority},"
            + "`supports_user_id`=#{supportsUserId},`supports_custom_data`=#{supportsCustomData},"
            + "`supports_stable_transaction`=#{supportsStableTransaction},"
            + "`supports_impression_revenue`=#{supportsImpressionRevenue},"
            + "`supports_reporting`=#{supportsReporting},`enabled`=b'1',"
            + "`verified_at`=CURRENT_TIMESTAMP,`updater`='capability-verification',"
            + "`deleted`=b'0'")
    @InterceptorIgnore(tenantLine = "true") // every branch is explicitly tenant/account scoped
    int upsertVerified(@Param("tenantId") Long tenantId,
                       @Param("adAccountId") Long adAccountId,
                       @Param("networkFirmId") Integer networkFirmId,
                       @Param("rewardAuthority") String rewardAuthority,
                       @Param("supportsUserId") Boolean supportsUserId,
                       @Param("supportsCustomData") Boolean supportsCustomData,
                       @Param("supportsStableTransaction") Boolean supportsStableTransaction,
                       @Param("supportsImpressionRevenue") Boolean supportsImpressionRevenue,
                       @Param("supportsReporting") Boolean supportsReporting);

    @Update("UPDATE `skit_ad_network_capability` SET `enabled`=b'0',"
            + "`updater`='capability-verification',`update_time`=CURRENT_TIMESTAMP "
            + "WHERE `tenant_id`=#{tenantId} AND `ad_account_id`=#{adAccountId} "
            + "AND `network_firm_id`=#{networkFirmId} AND `deleted`=b'0'")
    @InterceptorIgnore(tenantLine = "true") // tenant_id is explicit
    int disable(@Param("tenantId") Long tenantId,
                @Param("adAccountId") Long adAccountId,
                @Param("networkFirmId") Integer networkFirmId);

    @Select("SELECT * FROM `skit_ad_network_capability` WHERE `tenant_id`=#{tenantId} "
            + "AND `ad_account_id`=#{adAccountId} AND `network_firm_id`=#{networkFirmId} "
            + "AND `deleted`=b'0' FOR SHARE")
    @InterceptorIgnore(tenantLine = "true") // tenant_id is explicit
    SkitAdNetworkCapabilityDO selectForShare(@Param("tenantId") Long tenantId,
                                             @Param("adAccountId") Long adAccountId,
                                             @Param("networkFirmId") Integer networkFirmId);

    @Select("SELECT * FROM `skit_ad_network_capability` WHERE `tenant_id`=#{tenantId} "
            + "AND `ad_account_id`=#{adAccountId} AND `network_firm_id`=#{networkFirmId} "
            + "AND `deleted`=b'0' FOR UPDATE")
    @InterceptorIgnore(tenantLine = "true") // tenant_id is explicit
    SkitAdNetworkCapabilityDO selectForUpdate(@Param("tenantId") Long tenantId,
                                              @Param("adAccountId") Long adAccountId,
                                              @Param("networkFirmId") Integer networkFirmId);

    @Select("SELECT * FROM `skit_ad_network_capability` WHERE `tenant_id`=#{tenantId} "
            + "AND `ad_account_id`=#{adAccountId} AND `deleted`=b'0' "
            + "ORDER BY `network_firm_id`")
    @InterceptorIgnore(tenantLine = "true") // tenant_id is explicit
    List<SkitAdNetworkCapabilityDO> selectAllForShare(@Param("tenantId") Long tenantId,
                                                      @Param("adAccountId") Long adAccountId);

}
