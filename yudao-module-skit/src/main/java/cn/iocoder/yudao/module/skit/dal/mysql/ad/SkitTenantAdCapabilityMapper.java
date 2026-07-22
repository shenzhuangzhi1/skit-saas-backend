package cn.iocoder.yudao.module.skit.dal.mysql.ad;

import cn.iocoder.yudao.module.skit.dal.dataobject.ad.SkitTenantAdCapabilityDO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface SkitTenantAdCapabilityMapper {

    @Select("SELECT * FROM `skit_tenant_ad_capability` WHERE `tenant_id`=#{tenantId} "
            + "AND `deleted`=b'0' FOR SHARE")
    SkitTenantAdCapabilityDO selectByTenantForShare(@Param("tenantId") Long tenantId);

    @Select("SELECT * FROM `skit_tenant_ad_capability` WHERE `tenant_id`=#{tenantId} "
            + "AND `deleted`=b'0' FOR UPDATE")
    SkitTenantAdCapabilityDO selectByTenantForUpdate(@Param("tenantId") Long tenantId);

    @Insert("INSERT INTO `skit_tenant_ad_capability` "
            + "(`tenant_id`,`rollout_state`,`dedicated_unlock_placement_id`,"
            + "`unlock_network_firm_ids_json`,`shadow_test_member_ids_json`,"
            + "`min_native_version`,`min_protocol_version`,`readiness_version`,`creator`,`updater`) "
            + "VALUES (#{tenantId},'OFF','', '[]','[]','',1,0,'ad-readiness','ad-readiness')")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertDefault(SkitTenantAdCapabilityDO row);

    @Update("UPDATE `skit_tenant_ad_capability` SET `ad_account_id`=#{adAccountId},"
            + "`dedicated_unlock_placement_id`=#{dedicatedUnlockPlacementId},"
            + "`dedicated_placement_verified_at`=CASE WHEN #{dedicatedPlacementVerified}=b'1' "
            + "THEN COALESCE(`dedicated_placement_verified_at`,CURRENT_TIMESTAMP) ELSE NULL END,"
            + "`reward_callback_template_verified_at`=CASE WHEN #{rewardCallbackTemplateVerified}=b'1' "
            + "THEN COALESCE(`reward_callback_template_verified_at`,CURRENT_TIMESTAMP) ELSE NULL END,"
            + "`impression_callback_template_verified_at`=CASE WHEN #{impressionCallbackTemplateVerified}=b'1' "
            + "THEN COALESCE(`impression_callback_template_verified_at`,CURRENT_TIMESTAMP) ELSE NULL END,"
            + "`unlock_network_firm_ids_json`=#{unlockNetworkFirmIdsJson},"
            + "`shadow_test_member_ids_json`=#{shadowTestMemberIdsJson},"
            + "`min_native_version`=#{minNativeVersion},`min_protocol_version`=#{minProtocolVersion},"
            + "`readiness_version`=`readiness_version`+1,`updater`='ad-readiness',"
            + "`update_time`=CURRENT_TIMESTAMP WHERE `tenant_id`=#{tenantId} AND `id`=#{id} "
            + "AND `readiness_version`=#{expectedReadinessVersion} AND `rollout_state`<>'ENFORCED' "
            + "AND `deleted`=b'0'")
    int updateConfigurationCas(@Param("tenantId") Long tenantId,
                               @Param("id") Long id,
                               @Param("expectedReadinessVersion") Integer expectedReadinessVersion,
                               @Param("adAccountId") Long adAccountId,
                               @Param("dedicatedUnlockPlacementId") String dedicatedUnlockPlacementId,
                               @Param("dedicatedPlacementVerified") Boolean dedicatedPlacementVerified,
                               @Param("rewardCallbackTemplateVerified") Boolean rewardCallbackTemplateVerified,
                               @Param("impressionCallbackTemplateVerified") Boolean impressionCallbackTemplateVerified,
                               @Param("unlockNetworkFirmIdsJson") String unlockNetworkFirmIdsJson,
                               @Param("shadowTestMemberIdsJson") String shadowTestMemberIdsJson,
                               @Param("minNativeVersion") String minNativeVersion,
                               @Param("minProtocolVersion") Integer minProtocolVersion);

    @Update("UPDATE `skit_tenant_ad_capability` SET `rollout_state`=#{targetState},"
            + "`min_native_version`=#{minNativeVersion},`min_protocol_version`=#{minProtocolVersion},"
            + "`readiness_version`=`readiness_version`+1,"
            + "`enforced_at`=CASE WHEN #{targetState}='ENFORCED' THEN CURRENT_TIMESTAMP ELSE NULL END,"
            + "`updater`='ad-rollout',`update_time`=CURRENT_TIMESTAMP "
            + "WHERE `tenant_id`=#{tenantId} AND `id`=#{id} "
            + "AND `readiness_version`=#{expectedReadinessVersion} AND `deleted`=b'0' AND ("
            + "(#{targetState}='SHADOW_TEST_USERS' AND `rollout_state`='OFF') OR "
            + "(#{targetState}='ENFORCED' AND `rollout_state`='SHADOW_TEST_USERS') OR "
            + "(#{targetState}='OFF' AND `rollout_state` IN ('SHADOW_TEST_USERS','ENFORCED')))" )
    int transitionCas(@Param("tenantId") Long tenantId,
                      @Param("id") Long id,
                      @Param("expectedReadinessVersion") Integer expectedReadinessVersion,
                      @Param("targetState") String targetState,
                      @Param("minNativeVersion") String minNativeVersion,
                      @Param("minProtocolVersion") Integer minProtocolVersion);

    @Update("UPDATE `skit_tenant_ad_capability` SET "
            + "`readiness_version`=`readiness_version`+1,"
            + "`updater`='network-capability-verification',`update_time`=CURRENT_TIMESTAMP "
            + "WHERE `tenant_id`=#{tenantId} AND `id`=#{id} AND `ad_account_id`=#{adAccountId} "
            + "AND `readiness_version`=#{expectedReadinessVersion} AND `deleted`=b'0'")
    int bumpNetworkCapabilityVersionCas(@Param("tenantId") Long tenantId,
                                        @Param("id") Long id,
                                        @Param("adAccountId") Long adAccountId,
                                        @Param("expectedReadinessVersion") Integer expectedReadinessVersion);

}
