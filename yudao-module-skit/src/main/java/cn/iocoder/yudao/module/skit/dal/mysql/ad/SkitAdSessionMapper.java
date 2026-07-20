package cn.iocoder.yudao.module.skit.dal.mysql.ad;

import cn.iocoder.yudao.module.skit.dal.dataobject.ad.SkitAdRewardExpiryClaimDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.ad.SkitAdSessionDO;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface SkitAdSessionMapper {

    @Select("SELECT `tenant_id`,`ad_account_id`,`id` FROM `skit_ad_session` "
            + "WHERE `reward_verification_status`='PENDING' "
            + "AND `reward_accept_until`<CURRENT_TIMESTAMP "
            + "AND `reward_callback_inbox_id` IS NULL AND `reward_callback_received_at` IS NULL "
            + "AND `active_scope_hash` IS NOT NULL AND `active_scope_released_at` IS NULL "
            + "AND `active_scope_release_reason` IS NULL AND `deleted`=b'0' "
            + "ORDER BY `reward_accept_until`,`id` LIMIT #{limit}")
    List<SkitAdRewardExpiryClaimDO> selectExpiredRewardClaims(@Param("limit") int limit);

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
            + "AND `ad_account_id`=#{adAccountId} AND `session_token_hash`=#{sessionTokenHash} "
            + "AND `deleted`=b'0' FOR UPDATE")
    SkitAdSessionDO selectByTokenHashForUpdate(@Param("tenantId") Long tenantId,
                                               @Param("adAccountId") Long adAccountId,
                                               @Param("sessionTokenHash") byte[] sessionTokenHash);

    @Select("SELECT * FROM `skit_ad_session` WHERE `tenant_id`=#{tenantId} "
            + "AND `ad_account_id`=#{adAccountId} AND `session_id`=#{sessionId} "
            + "AND `deleted`=b'0' FOR UPDATE")
    SkitAdSessionDO selectByAccountAndSessionIdForUpdate(@Param("tenantId") Long tenantId,
                                                         @Param("adAccountId") Long adAccountId,
                                                         @Param("sessionId") String sessionId);

    @Select("SELECT * FROM `skit_ad_session` WHERE `tenant_id`=#{tenantId} "
            + "AND `ad_account_id`=#{adAccountId} AND `id`=#{id} AND `deleted`=b'0' FOR UPDATE")
    SkitAdSessionDO selectByTenantAccountAndIdForUpdate(@Param("tenantId") Long tenantId,
                                                        @Param("adAccountId") Long adAccountId,
                                                        @Param("id") Long id);

    @Select("SELECT * FROM `skit_ad_session` WHERE `tenant_id`=#{tenantId} "
            + "AND `member_id`=#{memberId} AND `active_scope_hash`=#{activeScopeHash} "
            + "AND `active_scope_released_at` IS NULL AND `deleted`=b'0' FOR UPDATE")
    SkitAdSessionDO selectActiveScopeForUpdate(@Param("tenantId") Long tenantId,
                                               @Param("memberId") Long memberId,
                                               @Param("activeScopeHash") byte[] activeScopeHash);

    @Select("SELECT * FROM `skit_ad_session` WHERE `tenant_id`=#{tenantId} "
            + "AND `member_id`=#{memberId} AND `drama_id`=#{dramaId} "
            + "AND `episode_from`<=#{episodeTo} AND `episode_to`>=#{episodeFrom} "
            + "AND `active_scope_hash` IS NOT NULL AND `active_scope_released_at` IS NULL "
            + "AND `active_scope_release_reason` IS NULL AND `deleted`=b'0' "
            + "ORDER BY `episode_from`,`episode_to`,`id` FOR UPDATE")
    @InterceptorIgnore(tenantLine = "true") // tenant_id is explicit; preserve MySQL ORDER BY ... FOR UPDATE order
    List<SkitAdSessionDO> selectActiveScopesOverlappingRangeForUpdate(
            @Param("tenantId") Long tenantId,
            @Param("memberId") Long memberId,
            @Param("dramaId") Long dramaId,
            @Param("episodeFrom") Integer episodeFrom,
            @Param("episodeTo") Integer episodeTo);

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

    @Update("UPDATE `skit_ad_session` SET `client_lifecycle_status`='FAILED',"
            + "`reward_verification_status`='REJECTED',"
            + "`last_callback_sequence`=#{callbackSequence},`last_client_event`='FAILED',"
            + "`sdk_request_id`=COALESCE(`sdk_request_id`,#{sdkRequestId}),"
            + "`active_scope_hash`=NULL,`active_scope_released_at`=#{failedAt},"
            + "`active_scope_release_reason`='REWARD_REJECTED',"
            + "`failure_reason`='CLIENT_PRE_SHOW_FAILED',`version`=`version`+1,"
            + "`updater`='ad-client-event',`update_time`=#{failedAt} "
            + "WHERE `tenant_id`=#{tenantId} AND `id`=#{id} AND `member_id`=#{memberId} "
            + "AND `version`=#{expectedVersion} AND `client_lifecycle_status`=#{expectedStatus} "
            + "AND `client_lifecycle_status` IN ('CREATED','LOADING') "
            + "AND `last_callback_sequence`=#{expectedLastCallbackSequence} "
            + "AND #{callbackSequence} > `last_callback_sequence` "
            + "AND `reward_verification_status`='PENDING' AND `entitlement_status`='NONE' "
            + "AND `revenue_status`='NONE' AND `reward_callback_inbox_id` IS NULL "
            + "AND `reward_callback_received_at` IS NULL AND `provider_show_id` IS NULL "
            + "AND `active_scope_hash` IS NOT NULL AND `active_scope_released_at` IS NULL "
            + "AND `active_scope_release_reason` IS NULL "
            + "AND (#{sdkRequestId} IS NULL OR `sdk_request_id` IS NULL OR `sdk_request_id`=#{sdkRequestId}) "
            + "AND `deleted`=b'0'")
    int markPreShowClientFailureAndReleaseScopeCas(
            @Param("tenantId") Long tenantId,
            @Param("id") Long id,
            @Param("memberId") Long memberId,
            @Param("expectedVersion") Integer expectedVersion,
            @Param("expectedStatus") String expectedStatus,
            @Param("expectedLastCallbackSequence") Integer expectedLastCallbackSequence,
            @Param("callbackSequence") Integer callbackSequence,
            @Param("sdkRequestId") String sdkRequestId,
            @Param("failedAt") LocalDateTime failedAt);

    @Update("UPDATE `skit_ad_session` SET `reward_verification_status`='REJECTED',"
            + "`active_scope_hash`=NULL,`active_scope_released_at`=#{rejectedAt},"
            + "`active_scope_release_reason`='REWARD_REJECTED',"
            + "`failure_reason`='ORPHAN_CREATED_REPLACED',`version`=`version`+1,"
            + "`updater`='ad-session-retry',`update_time`=#{rejectedAt} "
            + "WHERE `tenant_id`=#{tenantId} AND `id`=#{id} AND `member_id`=#{memberId} "
            + "AND `version`=#{expectedVersion} AND `client_lifecycle_status`='CREATED' "
            + "AND `reward_verification_status`='PENDING' AND `entitlement_status`='NONE' "
            + "AND `revenue_status`='NONE' AND `sdk_request_id` IS NULL "
            + "AND `provider_show_id` IS NULL AND `provider_transaction_id` IS NULL "
            + "AND `reward_callback_inbox_id` IS NULL AND `reward_callback_received_at` IS NULL "
            + "AND `network_firm_id` IS NULL AND `adsource_id` IS NULL "
            + "AND `last_callback_sequence`=-1 AND `last_client_event` IS NULL "
            + "AND `active_scope_hash` IS NOT NULL AND `active_scope_released_at` IS NULL "
            + "AND `active_scope_release_reason` IS NULL AND `deleted`=b'0'")
    int rejectPureCreatedAndReleaseScopeCas(
            @Param("tenantId") Long tenantId,
            @Param("id") Long id,
            @Param("memberId") Long memberId,
            @Param("expectedVersion") Integer expectedVersion,
            @Param("rejectedAt") LocalDateTime rejectedAt);

    @Update("UPDATE `skit_ad_session` SET `reward_verification_status`='REJECTED',"
            + "`revenue_status`=CASE WHEN `revenue_status`='IMPRESSION_PENDING_REWARD' "
            + "THEN 'SUSPENSE' ELSE `revenue_status` END,"
            + "`active_scope_hash`=NULL,`active_scope_released_at`=#{rejectedAt},"
            + "`active_scope_release_reason`='REWARD_REJECTED',"
            + "`failure_reason`='LEGACY_MULTI_EPISODE_SCOPE',`version`=`version`+1,"
            + "`updater`='ad-session-migration',`update_time`=#{rejectedAt} "
            + "WHERE `tenant_id`=#{tenantId} AND `id`=#{id} AND `member_id`=#{memberId} "
            + "AND `version`=#{expectedVersion} AND `episode_from`<>`episode_to` "
            + "AND `reward_verification_status`='PENDING' AND `entitlement_status`='NONE' "
            + "AND `active_scope_hash` IS NOT NULL AND `active_scope_released_at` IS NULL "
            + "AND `active_scope_release_reason` IS NULL AND `deleted`=b'0'")
    int rejectLegacyMultiEpisodeScopeCas(
            @Param("tenantId") Long tenantId,
            @Param("id") Long id,
            @Param("memberId") Long memberId,
            @Param("expectedVersion") Integer expectedVersion,
            @Param("rejectedAt") LocalDateTime rejectedAt);

    @Update("UPDATE `skit_ad_session` SET `client_lifecycle_status`='LOAD_EXPIRED',"
            + "`failure_reason`='LOAD_WINDOW_EXPIRED',`version`=`version`+1,"
            + "`updater`='ad-load-expiry',`update_time`=#{authoritativeNow} "
            + "WHERE `tenant_id`=#{tenantId} AND `id`=#{id} AND `member_id`=#{memberId} "
            + "AND `version`=#{expectedVersion} "
            + "AND `client_lifecycle_status` IN ('CREATED','LOADING') "
            + "AND `load_expires_at` < #{authoritativeNow} "
            + "AND `reward_verification_status`='PENDING' AND `entitlement_status`='NONE' "
            + "AND `revenue_status`='NONE' AND `provider_show_id` IS NULL "
            + "AND `provider_transaction_id` IS NULL AND `reward_callback_inbox_id` IS NULL "
            + "AND `reward_callback_received_at` IS NULL AND `network_firm_id` IS NULL "
            + "AND `adsource_id` IS NULL "
            + "AND ((`client_lifecycle_status`='CREATED' AND `last_callback_sequence`=-1 "
            + "AND `last_client_event` IS NULL AND `sdk_request_id` IS NULL) "
            + "OR (`client_lifecycle_status`='LOADING' AND `last_callback_sequence`>=0 "
            + "AND `last_client_event`='LOAD_STARTED' AND `sdk_request_id` IS NOT NULL)) "
            + "AND `active_scope_hash` IS NOT NULL AND `active_scope_released_at` IS NULL "
            + "AND `active_scope_release_reason` IS NULL AND `deleted`=b'0'")
    int markLoadExpiredCas(@Param("tenantId") Long tenantId,
                           @Param("id") Long id,
                           @Param("memberId") Long memberId,
                           @Param("expectedVersion") Integer expectedVersion,
                           @Param("authoritativeNow") LocalDateTime authoritativeNow);

    @Update("UPDATE `skit_ad_session` SET `reward_verification_status`='REJECTED',"
            + "`active_scope_hash`=NULL,`active_scope_released_at`=#{rejectedAt},"
            + "`active_scope_release_reason`='REWARD_REJECTED',"
            + "`failure_reason`='LOAD_WINDOW_EXPIRED',`version`=`version`+1,"
            + "`updater`='ad-session-retry',`update_time`=#{rejectedAt} "
            + "WHERE `tenant_id`=#{tenantId} AND `id`=#{id} AND `member_id`=#{memberId} "
            + "AND `version`=#{expectedVersion} AND `client_lifecycle_status`='LOAD_EXPIRED' "
            + "AND `reward_verification_status`='PENDING' AND `entitlement_status`='NONE' "
            + "AND `revenue_status`='NONE' AND `provider_show_id` IS NULL "
            + "AND `provider_transaction_id` IS NULL AND `reward_callback_inbox_id` IS NULL "
            + "AND `reward_callback_received_at` IS NULL AND `network_firm_id` IS NULL "
            + "AND `adsource_id` IS NULL "
            + "AND ((`last_callback_sequence`=-1 AND `last_client_event` IS NULL "
            + "AND `sdk_request_id` IS NULL) OR (`last_callback_sequence`>=0 "
            + "AND `last_client_event`='LOAD_STARTED' AND `sdk_request_id` IS NOT NULL)) "
            + "AND `active_scope_hash` IS NOT NULL "
            + "AND `active_scope_released_at` IS NULL AND `active_scope_release_reason` IS NULL "
            + "AND `deleted`=b'0'")
    int rejectUnstartedLoadExpiredAndReleaseScopeCas(
            @Param("tenantId") Long tenantId,
            @Param("id") Long id,
            @Param("memberId") Long memberId,
            @Param("expectedVersion") Integer expectedVersion,
            @Param("rejectedAt") LocalDateTime rejectedAt);

    @Update("UPDATE `skit_ad_session` SET `reward_verification_status`='REJECTED',"
            + "`active_scope_hash`=NULL,`active_scope_released_at`=#{rejectedAt},"
            + "`active_scope_release_reason`='REWARD_REJECTED',"
            + "`failure_reason`='CLIENT_PRE_SHOW_FAILED',`version`=`version`+1,"
            + "`updater`='ad-session-retry',`update_time`=#{rejectedAt} "
            + "WHERE `tenant_id`=#{tenantId} AND `id`=#{id} AND `member_id`=#{memberId} "
            + "AND `version`=#{expectedVersion} AND `client_lifecycle_status`='FAILED' "
            + "AND `reward_verification_status`='PENDING' AND `entitlement_status`='NONE' "
            + "AND `revenue_status`='NONE' AND `provider_show_id` IS NULL "
            + "AND `provider_transaction_id` IS NULL AND `reward_callback_inbox_id` IS NULL "
            + "AND `reward_callback_received_at` IS NULL AND `network_firm_id` IS NULL "
            + "AND `adsource_id` IS NULL AND `last_callback_sequence`>=0 "
            + "AND `last_client_event`='FAILED' AND `sdk_request_id` IS NOT NULL "
            + "AND `active_scope_hash` IS NOT NULL AND `active_scope_released_at` IS NULL "
            + "AND `active_scope_release_reason` IS NULL AND `deleted`=b'0'")
    int rejectPreShowFailedAndReleaseScopeCas(
            @Param("tenantId") Long tenantId,
            @Param("id") Long id,
            @Param("memberId") Long memberId,
            @Param("expectedVersion") Integer expectedVersion,
            @Param("rejectedAt") LocalDateTime rejectedAt);

    @Update("UPDATE `skit_ad_session` SET `reward_verification_status`='REJECTED',"
            + "`active_scope_hash`=NULL,`active_scope_released_at`=#{rejectedAt},"
            + "`active_scope_release_reason`='REWARD_REJECTED',"
            + "`failure_reason`='NATIVE_PLAYER_GRANT_SUPERSEDED',`version`=`version`+1,"
            + "`updater`='ad-session-takeover',`update_time`=#{rejectedAt} "
            + "WHERE `tenant_id`=#{tenantId} AND `id`=#{id} AND `member_id`=#{memberId} "
            + "AND `version`=#{expectedVersion} AND `access_mode`='NATIVE_PLAYER_GRANT' "
            + "AND `native_player_grant_id`=#{expectedNativePlayerGrantId} "
            + "AND `native_player_grant_id`<>#{currentNativePlayerGrantId} "
            + "AND `client_lifecycle_status`='LOADING' AND `last_callback_sequence`>=0 "
            + "AND `last_client_event`='LOAD_STARTED' AND `sdk_request_id` IS NOT NULL "
            + "AND `reward_verification_status`='PENDING' AND `entitlement_status`='NONE' "
            + "AND `revenue_status`='NONE' AND `provider_show_id` IS NULL "
            + "AND `provider_transaction_id` IS NULL AND `reward_callback_inbox_id` IS NULL "
            + "AND `reward_callback_received_at` IS NULL AND `network_firm_id` IS NULL "
            + "AND `adsource_id` IS NULL AND `active_scope_hash` IS NOT NULL "
            + "AND `active_scope_released_at` IS NULL AND `active_scope_release_reason` IS NULL "
            + "AND `deleted`=b'0'")
    int rejectSupersededNativeGrantLoadingAndReleaseScopeCas(
            @Param("tenantId") Long tenantId,
            @Param("id") Long id,
            @Param("memberId") Long memberId,
            @Param("expectedVersion") Integer expectedVersion,
            @Param("expectedNativePlayerGrantId") Long expectedNativePlayerGrantId,
            @Param("currentNativePlayerGrantId") Long currentNativePlayerGrantId,
            @Param("rejectedAt") LocalDateTime rejectedAt);

    @Update("UPDATE `skit_ad_session` SET `reward_callback_inbox_id`=#{callbackInboxId},"
            + "`reward_callback_received_at`=#{receivedAt},`version`=`version`+1,"
            + "`updater`='callback-ingress',`update_time`=#{receivedAt} "
            + "WHERE `tenant_id`=#{tenantId} AND `id`=#{id} AND `ad_account_id`=#{adAccountId} "
            + "AND `reward_verification_status`='PENDING' AND `reward_accept_until` >= #{receivedAt} "
            + "AND `reward_callback_inbox_id` IS NULL AND `reward_callback_received_at` IS NULL "
            + "AND `deleted`=b'0'")
    int markRewardCallbackReceivedCas(@Param("tenantId") Long tenantId,
                                      @Param("id") Long id,
                                      @Param("adAccountId") Long adAccountId,
                                      @Param("callbackInboxId") Long callbackInboxId,
                                      @Param("receivedAt") LocalDateTime receivedAt);

    @Update("UPDATE `skit_ad_session` SET `reward_verification_status`='SIGNED_VERIFIED',"
            + "`entitlement_status`='GRANTED',"
            + "`revenue_status`=CASE WHEN `revenue_status`='IMPRESSION_PENDING_REWARD' "
            + "THEN 'FROZEN' ELSE `revenue_status` END,"
            + "`reward_verified_at`=#{verifiedAt},`entitled_at`=#{verifiedAt},"
            + "`provider_transaction_id`=#{providerTransactionId},"
            + "`provider_show_id`=COALESCE(`provider_show_id`,#{providerShowId}),"
            + "`network_firm_id`=COALESCE(`network_firm_id`,#{networkFirmId}),"
            + "`adsource_id`=COALESCE(`adsource_id`,#{adsourceId}),"
            + "`active_scope_hash`=NULL,`active_scope_released_at`=#{verifiedAt},"
            + "`active_scope_release_reason`='ENTITLEMENT_GRANTED',`version`=`version`+1,"
            + "`updater`='callback-processor',`update_time`=#{verifiedAt} "
            + "WHERE `tenant_id`=#{tenantId} AND `id`=#{id} AND `ad_account_id`=#{adAccountId} "
            + "AND `version`=#{expectedVersion} AND `reward_verification_status`='PENDING' "
            + "AND `entitlement_status`='NONE' AND `reward_callback_inbox_id`=#{callbackInboxId} "
            + "AND `reward_callback_received_at`=#{callbackReceivedAt} "
            + "AND `reward_accept_until` >= #{callbackReceivedAt} "
            + "AND `callback_key_version`=#{callbackKeyVersion} "
            + "AND `reward_secret_version`=#{rewardSecretVersion} "
            + "AND (`provider_transaction_id` IS NULL OR `provider_transaction_id`=#{providerTransactionId}) "
            + "AND (#{providerShowId} IS NULL OR `provider_show_id` IS NULL "
            + "OR `provider_show_id`=#{providerShowId}) "
            + "AND (`network_firm_id` IS NULL OR `network_firm_id`=#{networkFirmId}) "
            + "AND (`adsource_id` IS NULL OR `adsource_id`=#{adsourceId}) "
            + "AND `active_scope_hash` IS NOT NULL AND `active_scope_released_at` IS NULL "
            + "AND `active_scope_release_reason` IS NULL AND `deleted`=b'0'")
    int markSignedRewardAndGrantCas(@Param("tenantId") Long tenantId,
                                    @Param("id") Long id,
                                    @Param("adAccountId") Long adAccountId,
                                    @Param("callbackInboxId") Long callbackInboxId,
                                    @Param("callbackReceivedAt") LocalDateTime callbackReceivedAt,
                                    @Param("expectedVersion") Integer expectedVersion,
                                    @Param("callbackKeyVersion") Integer callbackKeyVersion,
                                    @Param("rewardSecretVersion") Integer rewardSecretVersion,
                                    @Param("providerTransactionId") String providerTransactionId,
                                    @Param("providerShowId") String providerShowId,
                                    @Param("networkFirmId") Integer networkFirmId,
                                    @Param("adsourceId") String adsourceId,
                                    @Param("verifiedAt") LocalDateTime verifiedAt);

    @Update("UPDATE `skit_ad_session` SET `reward_verification_status`='REJECTED',"
            + "`revenue_status`=CASE WHEN `revenue_status`='IMPRESSION_PENDING_REWARD' "
            + "THEN 'SUSPENSE' ELSE `revenue_status` END,"
            + "`active_scope_hash`=NULL,`active_scope_released_at`=#{rejectedAt},"
            + "`active_scope_release_reason`='REWARD_REJECTED',`failure_reason`=#{failureReason},"
            + "`version`=`version`+1,`updater`='callback-processor',`update_time`=#{rejectedAt} "
            + "WHERE `tenant_id`=#{tenantId} AND `id`=#{id} AND `ad_account_id`=#{adAccountId} "
            + "AND `reward_callback_inbox_id`=#{callbackInboxId} "
            + "AND `reward_callback_received_at`=#{callbackReceivedAt} "
            + "AND `version`=#{expectedVersion} AND `reward_verification_status`='PENDING' "
            + "AND `entitlement_status`='NONE' AND `active_scope_hash` IS NOT NULL "
            + "AND `active_scope_released_at` IS NULL AND `active_scope_release_reason` IS NULL "
            + "AND `deleted`=b'0'")
    int markRewardReceiptRejectedCas(@Param("tenantId") Long tenantId,
                                     @Param("id") Long id,
                                     @Param("adAccountId") Long adAccountId,
                                     @Param("callbackInboxId") Long callbackInboxId,
                                     @Param("callbackReceivedAt") LocalDateTime callbackReceivedAt,
                                     @Param("expectedVersion") Integer expectedVersion,
                                     @Param("rejectedAt") LocalDateTime rejectedAt,
                                     @Param("failureReason") String failureReason);

    @Update("UPDATE `skit_ad_session` SET `revenue_status`=#{nextStatus},`version`=`version`+1,"
            + "`updater`='callback-processor',`update_time`=#{updatedAt} "
            + "WHERE `tenant_id`=#{tenantId} AND `id`=#{id} AND `ad_account_id`=#{adAccountId} "
            + "AND `version`=#{expectedVersion} AND `revenue_status`=#{expectedStatus} "
            + "AND `deleted`=b'0'")
    int updateRevenueStateCas(@Param("tenantId") Long tenantId,
                              @Param("id") Long id,
                              @Param("adAccountId") Long adAccountId,
                              @Param("expectedVersion") Integer expectedVersion,
                              @Param("expectedStatus") String expectedStatus,
                              @Param("nextStatus") String nextStatus,
                              @Param("updatedAt") LocalDateTime updatedAt);

    @Update("UPDATE `skit_ad_session` SET `reward_verification_status`='VERIFY_TIMEOUT',"
            + "`active_scope_hash`=NULL,`active_scope_released_at`=#{authoritativeNow},"
            + "`active_scope_release_reason`='VERIFY_TIMEOUT',`version`=`version`+1,"
            + "`updater`='ad-reward-timeout',`update_time`=#{authoritativeNow} "
            + "WHERE `tenant_id`=#{tenantId} AND `id`=#{id} AND `member_id`=#{memberId} "
            + "AND `version`=#{expectedVersion} AND `reward_verification_status`='PENDING' "
            + "AND `reward_accept_until` < #{authoritativeNow} AND `active_scope_hash` IS NOT NULL "
            + "AND `reward_callback_inbox_id` IS NULL AND `reward_callback_received_at` IS NULL "
            + "AND `active_scope_released_at` IS NULL AND `active_scope_release_reason` IS NULL "
            + "AND `deleted`=b'0'")
    int markRewardVerifyTimeoutAndReleaseScopeCas(@Param("tenantId") Long tenantId,
                                                  @Param("id") Long id,
                                                  @Param("memberId") Long memberId,
                                                  @Param("expectedVersion") Integer expectedVersion,
                                                  @Param("authoritativeNow") LocalDateTime authoritativeNow);

    @Update("UPDATE `skit_ad_session` SET `reward_verification_status`='VERIFY_TIMEOUT',"
            + "`revenue_status`=#{nextRevenueStatus},`active_scope_hash`=NULL,"
            + "`active_scope_released_at`=CURRENT_TIMESTAMP,"
            + "`active_scope_release_reason`='VERIFY_TIMEOUT',`version`=`version`+1,"
            + "`updater`='ad-reward-timeout',`update_time`=CURRENT_TIMESTAMP "
            + "WHERE `tenant_id`=#{tenantId} AND `id`=#{id} AND `ad_account_id`=#{adAccountId} "
            + "AND `member_id`=#{memberId} AND `version`=#{expectedVersion} "
            + "AND `revenue_status`=#{expectedRevenueStatus} "
            + "AND `reward_verification_status`='PENDING' AND `entitlement_status`='NONE' "
            + "AND `reward_accept_until`<CURRENT_TIMESTAMP "
            + "AND `reward_callback_inbox_id` IS NULL AND `reward_callback_received_at` IS NULL "
            + "AND `active_scope_hash` IS NOT NULL AND `active_scope_released_at` IS NULL "
            + "AND `active_scope_release_reason` IS NULL AND `deleted`=b'0'")
    int markRewardVerifyTimeoutByAccountCas(@Param("tenantId") Long tenantId,
                                            @Param("id") Long id,
                                            @Param("adAccountId") Long adAccountId,
                                            @Param("memberId") Long memberId,
                                            @Param("expectedVersion") Integer expectedVersion,
                                            @Param("expectedRevenueStatus") String expectedRevenueStatus,
                                            @Param("nextRevenueStatus") String nextRevenueStatus);

    @Update("UPDATE `skit_ad_session` SET `client_lifecycle_status`='FAILED',"
            + "`reward_verification_status`='REJECTED',`active_scope_hash`=NULL,"
            + "`active_scope_released_at`=CURRENT_TIMESTAMP,"
            + "`active_scope_release_reason`='REWARD_REJECTED',"
            + "`failure_reason`='ROLLOUT_REVOKED',`version`=`version`+1,"
            + "`updater`='ad-rollout',`update_time`=CURRENT_TIMESTAMP "
            + "WHERE `tenant_id`=#{tenantId} AND `reward_verification_status`='PENDING' "
            + "AND `entitlement_status`='NONE' AND `active_scope_hash` IS NOT NULL "
            + "AND `active_scope_released_at` IS NULL AND `active_scope_release_reason` IS NULL "
            + "AND `deleted`=b'0'")
    int rejectPendingForTenantRollout(@Param("tenantId") Long tenantId);

}
