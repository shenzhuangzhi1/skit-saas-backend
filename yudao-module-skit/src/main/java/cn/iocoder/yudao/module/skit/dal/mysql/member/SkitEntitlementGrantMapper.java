package cn.iocoder.yudao.module.skit.dal.mysql.member;

import cn.iocoder.yudao.module.skit.dal.dataobject.member.SkitEntitlementGrantDO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;

@Mapper
public interface SkitEntitlementGrantMapper {

    @Insert("INSERT INTO `skit_entitlement_grant` (tenant_id,`ad_session_id`,`entitlement_id`,"
            + "`member_id`,`drama_id`,`episode_no`,`provider_transaction_id`,`grant_result`,"
            + "`granted_at`,`creator`,`updater`) VALUES "
            + "(#{tenantId},#{adSessionId},#{entitlementId},#{memberId},#{dramaId},#{episodeNo},"
            + "#{providerTransactionId},#{grantResult},#{grantedAt},'entitlement-grant','entitlement-grant')")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(SkitEntitlementGrantDO row);

}
