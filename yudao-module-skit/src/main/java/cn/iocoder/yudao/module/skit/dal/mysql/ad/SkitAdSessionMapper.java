package cn.iocoder.yudao.module.skit.dal.mysql.ad;

import cn.iocoder.yudao.module.skit.dal.dataobject.ad.SkitAdSessionDO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface SkitAdSessionMapper {

    @Insert("INSERT INTO `skit_ad_session` (tenant_id,`session_id`,`session_token_hash`,"
            + "`session_token_key_version`,`protocol_version`,`member_id`,`ad_account_id`,"
            + "`policy_snapshot_id`,`callback_key_version`,`reward_secret_version`,`provider`,"
            + "`placement_id`,`scenario_id`,`business_type`,`drama_id`,`episode_from`,`episode_to`,"
            + "`unlock_scope`,`active_scope_hash`,`pseudonymous_user_id`,`access_mode`,"
            + "`native_player_grant_id`,`client_lifecycle_status`,`reward_verification_status`,"
            + "`entitlement_status`,`revenue_status`,`load_expires_at`,`reward_accept_until`,"
            + "`reward_verified_at`,`entitled_at`,`sdk_request_id`,`provider_show_id`,"
            + "`provider_transaction_id`,`network_firm_id`,`adsource_id`,`last_callback_sequence`,"
            + "`last_client_event`,`failure_reason`,`version`,`creator`,`updater`) VALUES "
            + "(#{tenantId},#{sessionId},#{sessionTokenHash},#{sessionTokenKeyVersion},#{protocolVersion},"
            + "#{memberId},#{adAccountId},#{policySnapshotId},#{callbackKeyVersion},"
            + "#{rewardSecretVersion},#{provider},#{placementId},#{scenarioId},#{businessType},"
            + "#{dramaId},#{episodeFrom},#{episodeTo},#{unlockScope},#{activeScopeHash},"
            + "#{pseudonymousUserId},#{accessMode},#{nativePlayerGrantId},#{clientLifecycleStatus},"
            + "#{rewardVerificationStatus},#{entitlementStatus},#{revenueStatus},#{loadExpiresAt},"
            + "#{rewardAcceptUntil},#{rewardVerifiedAt},#{entitledAt},#{sdkRequestId},#{providerShowId},"
            + "#{providerTransactionId},#{networkFirmId},#{adsourceId},#{lastCallbackSequence},"
            + "#{lastClientEvent},#{failureReason},#{version},'ad-session','ad-session')")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(SkitAdSessionDO row);

    @Select("SELECT * FROM `skit_ad_session` WHERE `tenant_id`=#{tenantId} "
            + "AND `member_id`=#{memberId} AND `session_id`=#{sessionId} "
            + "AND `deleted`=b'0' LIMIT 1")
    SkitAdSessionDO selectByTenantMemberAndSessionId(@Param("tenantId") Long tenantId,
                                                     @Param("memberId") Long memberId,
                                                     @Param("sessionId") String sessionId);

    @Select("SELECT * FROM `skit_ad_session` WHERE `tenant_id`=#{tenantId} "
            + "AND `member_id`=#{memberId} AND `session_id`=#{sessionId} "
            + "AND `deleted`=b'0' FOR UPDATE")
    SkitAdSessionDO selectByTenantMemberAndSessionIdForUpdate(@Param("tenantId") Long tenantId,
                                                              @Param("memberId") Long memberId,
                                                              @Param("sessionId") String sessionId);

    @Select("SELECT * FROM `skit_ad_session` WHERE `tenant_id`=#{tenantId} "
            + "AND `member_id`=#{memberId} AND `active_scope_hash`=#{activeScopeHash} "
            + "AND `active_scope_released_at` IS NULL AND `deleted`=b'0' FOR UPDATE")
    SkitAdSessionDO selectActiveScopeForUpdate(@Param("tenantId") Long tenantId,
                                               @Param("memberId") Long memberId,
                                               @Param("activeScopeHash") byte[] activeScopeHash);

    @Update("UPDATE `skit_ad_session` SET `client_lifecycle_status`=#{nextStatus},"
            + "`last_callback_sequence`=#{callbackSequence},`last_client_event`=#{eventType},"
            + "`sdk_request_id`=COALESCE(`sdk_request_id`,#{sdkRequestId}),"
            + "`provider_show_id`=COALESCE(`provider_show_id`,#{providerShowId}),"
            + "`network_firm_id`=COALESCE(`network_firm_id`,#{networkFirmId}),"
            + "`adsource_id`=COALESCE(`adsource_id`,#{adsourceId}),"
            + "`version`=`version`+1,`updater`='ad-client-event',`update_time`=CURRENT_TIMESTAMP "
            + "WHERE `tenant_id`=#{tenantId} AND `id`=#{id} AND `member_id`=#{memberId} "
            + "AND `version`=#{expectedVersion} AND `client_lifecycle_status`=#{expectedStatus} "
            + "AND `last_callback_sequence`=#{expectedLastCallbackSequence} "
            + "AND #{callbackSequence} > `last_callback_sequence` "
            + "AND (#{sdkRequestId} IS NULL OR `sdk_request_id` IS NULL OR `sdk_request_id`=#{sdkRequestId}) "
            + "AND (#{providerShowId} IS NULL OR `provider_show_id` IS NULL "
            + "OR `provider_show_id`=#{providerShowId}) AND `deleted`=b'0'")
    int updateClientLifecycleCas(@Param("tenantId") Long tenantId,
                                 @Param("id") Long id,
                                 @Param("memberId") Long memberId,
                                 @Param("expectedVersion") Integer expectedVersion,
                                 @Param("expectedStatus") String expectedStatus,
                                 @Param("expectedLastCallbackSequence") Integer expectedLastCallbackSequence,
                                 @Param("callbackSequence") Integer callbackSequence,
                                 @Param("nextStatus") String nextStatus,
                                 @Param("eventType") String eventType,
                                 @Param("sdkRequestId") String sdkRequestId,
                                 @Param("providerShowId") String providerShowId,
                                 @Param("networkFirmId") Integer networkFirmId,
                                 @Param("adsourceId") String adsourceId);

    @Update("UPDATE `skit_ad_session` SET `client_lifecycle_status`='LOAD_EXPIRED',"
            + "`failure_reason`='LOAD_WINDOW_EXPIRED',`version`=`version`+1,"
            + "`updater`='ad-load-expiry',`update_time`=#{authoritativeNow} "
            + "WHERE `tenant_id`=#{tenantId} AND `id`=#{id} AND `member_id`=#{memberId} "
            + "AND `version`=#{expectedVersion} AND `client_lifecycle_status`='CREATED' "
            + "AND `load_expires_at` < #{authoritativeNow} AND `deleted`=b'0'")
    int markLoadExpiredCas(@Param("tenantId") Long tenantId,
                           @Param("id") Long id,
                           @Param("memberId") Long memberId,
                           @Param("expectedVersion") Integer expectedVersion,
                           @Param("authoritativeNow") LocalDateTime authoritativeNow);

    @Update("UPDATE `skit_ad_session` SET `reward_verification_status`='VERIFY_TIMEOUT',"
            + "`active_scope_hash`=NULL,`active_scope_released_at`=#{authoritativeNow},"
            + "`active_scope_release_reason`='VERIFY_TIMEOUT',`version`=`version`+1,"
            + "`updater`='ad-reward-timeout',`update_time`=#{authoritativeNow} "
            + "WHERE `tenant_id`=#{tenantId} AND `id`=#{id} AND `member_id`=#{memberId} "
            + "AND `version`=#{expectedVersion} AND `reward_verification_status`='PENDING' "
            + "AND `reward_accept_until` < #{authoritativeNow} AND `active_scope_hash` IS NOT NULL "
            + "AND `active_scope_released_at` IS NULL AND `active_scope_release_reason` IS NULL "
            + "AND `deleted`=b'0'")
    int markRewardVerifyTimeoutAndReleaseScopeCas(@Param("tenantId") Long tenantId,
                                                  @Param("id") Long id,
                                                  @Param("memberId") Long memberId,
                                                  @Param("expectedVersion") Integer expectedVersion,
                                                  @Param("authoritativeNow") LocalDateTime authoritativeNow);

}
