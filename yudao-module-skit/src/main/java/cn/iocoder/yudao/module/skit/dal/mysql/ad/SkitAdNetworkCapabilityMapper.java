package cn.iocoder.yudao.module.skit.dal.mysql.ad;

import cn.iocoder.yudao.module.skit.dal.dataobject.ad.SkitAdNetworkCapabilityDO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface SkitAdNetworkCapabilityMapper {

    @Insert("INSERT INTO `skit_ad_network_capability` "
            + "(`tenant_id`,`ad_account_id`,`network_firm_id`,`reward_authority`,"
            + "`supports_user_id`,`supports_custom_data`,`supports_stable_transaction`,"
            + "`supports_impression_revenue`,`supports_reporting`,`enabled`,`verified_at`,"
            + "`creator`,`updater`,`deleted`) VALUES "
            + "(#{tenantId},#{adAccountId},66,'SIGNED_REWARD',b'1',b'1',b'1',b'1',b'1',b'1',"
            + "CURRENT_TIMESTAMP,'taku-adx-policy','taku-adx-policy',b'0') "
            + "ON DUPLICATE KEY UPDATE `reward_authority`='SIGNED_REWARD',"
            + "`supports_user_id`=b'1',`supports_custom_data`=b'1',"
            + "`supports_stable_transaction`=b'1',`supports_impression_revenue`=b'1',"
            + "`supports_reporting`=b'1',`enabled`=b'1',"
            + "`verified_at`=COALESCE(`verified_at`,CURRENT_TIMESTAMP),"
            + "`updater`='taku-adx-policy',`deleted`=b'0'")
    int upsertTakuAdxAuthority(@Param("tenantId") Long tenantId,
                               @Param("adAccountId") Long adAccountId);

    @Select("SELECT * FROM `skit_ad_network_capability` WHERE `tenant_id`=#{tenantId} "
            + "AND `ad_account_id`=#{adAccountId} AND `network_firm_id`=#{networkFirmId} "
            + "AND `deleted`=b'0' FOR SHARE")
    SkitAdNetworkCapabilityDO selectForShare(@Param("tenantId") Long tenantId,
                                             @Param("adAccountId") Long adAccountId,
                                             @Param("networkFirmId") Integer networkFirmId);

}
