package cn.iocoder.yudao.module.skit.dal.mysql.ad;

import cn.iocoder.yudao.module.skit.dal.dataobject.ad.SkitAdNetworkCapabilityDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface SkitAdNetworkCapabilityMapper {

    @Select("SELECT * FROM `skit_ad_network_capability` WHERE `tenant_id`=#{tenantId} "
            + "AND `ad_account_id`=#{adAccountId} AND `network_firm_id`=#{networkFirmId} "
            + "AND `deleted`=b'0' FOR SHARE")
    SkitAdNetworkCapabilityDO selectForShare(@Param("tenantId") Long tenantId,
                                             @Param("adAccountId") Long adAccountId,
                                             @Param("networkFirmId") Integer networkFirmId);

}
