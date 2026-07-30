package cn.iocoder.yudao.module.skit.dal.mysql.ad;

import cn.iocoder.yudao.module.skit.dal.dataobject.ad.SkitPangleRewardAttestationDO;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface SkitPangleRewardAttestationMapper {

    @Insert("INSERT INTO `skit_pangle_reward_attestation` "
            + "(`tenant_id`,`taku_ad_account_id`,`pangle_ad_account_id`,`ad_session_id`,"
            + "`callback_key_version`,`pangle_reward_secret_version`,`pangle_reward_placement_id`,"
            + "`provider`,`provider_transaction_id`,`provider_user_id`,`extra_data_hash`,"
            + "`reward_name`,`reward_amount`,`canonical_payload_hash`,`credential_fingerprint`,"
            + "`received_at`,`creator`,`updater`) VALUES "
            + "(#{tenantId},#{takuAdAccountId},#{pangleAdAccountId},#{adSessionId},"
            + "#{callbackKeyVersion},#{pangleRewardSecretVersion},#{pangleRewardPlacementId},"
            + "#{provider},#{providerTransactionId},#{providerUserId},#{extraDataHash},"
            + "#{rewardName},#{rewardAmount},#{canonicalPayloadHash},#{credentialFingerprint},"
            + "#{receivedAt},'pangle-callback','pangle-callback')")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    @InterceptorIgnore(tenantLine = "true")
    int insert(SkitPangleRewardAttestationDO row);

    @Select("SELECT * FROM `skit_pangle_reward_attestation` WHERE `tenant_id`=#{tenantId} "
            + "AND `taku_ad_account_id`=#{takuAdAccountId} AND `ad_session_id`=#{adSessionId} "
            + "AND `deleted`=b'0'")
    @InterceptorIgnore(tenantLine = "true")
    SkitPangleRewardAttestationDO selectBySession(
            @Param("tenantId") Long tenantId,
            @Param("takuAdAccountId") Long takuAdAccountId,
            @Param("adSessionId") Long adSessionId);

    @Select("SELECT * FROM `skit_pangle_reward_attestation` WHERE `tenant_id`=#{tenantId} "
            + "AND `pangle_ad_account_id`=#{pangleAdAccountId} "
            + "AND `provider_transaction_id`=#{providerTransactionId} AND `deleted`=b'0'")
    @InterceptorIgnore(tenantLine = "true")
    SkitPangleRewardAttestationDO selectByTransactionId(
            @Param("tenantId") Long tenantId,
            @Param("pangleAdAccountId") Long pangleAdAccountId,
            @Param("providerTransactionId") String providerTransactionId);

}
