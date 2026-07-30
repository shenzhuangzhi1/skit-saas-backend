package cn.iocoder.yudao.module.skit.dal.mysql.ad;

import cn.iocoder.yudao.module.skit.dal.dataobject.ad.SkitAdCallbackClaimDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.ad.SkitAdCallbackInboxDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.ad.SkitAdRetentionClaimDO;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.SelectKey;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface SkitAdCallbackInboxMapper {

    @Select("SELECT `tenant_id`,`ad_account_id`,`id` FROM `skit_ad_callback_inbox` "
            + "WHERE `processing_status` IN ('SUCCEEDED','REJECTED','DEAD_LETTER') "
            + "AND `payload_ciphertext` IS NOT NULL AND `payload_nonce` IS NOT NULL "
            + "AND `payload_key_id` IS NOT NULL AND `payload_envelope_version` IS NOT NULL "
            + "AND `payload_expires_at`<=CURRENT_TIMESTAMP "
            + "ORDER BY `payload_expires_at`,`id` LIMIT #{limit}")
    List<SkitAdRetentionClaimDO> selectExpiredTerminalPayloadClaims(@Param("limit") int limit);

    @Select("SELECT `tenant_id`,`ad_account_id`,`id` FROM `skit_ad_callback_inbox` "
            + "WHERE `processing_status`='DEAD_LETTER' AND `dead_letter_alerted_at` IS NULL "
            + "ORDER BY `id` LIMIT #{limit}")
    List<SkitAdCallbackClaimDO> selectUnalertedDeadLetterClaims(@Param("limit") int limit);

    @Select("SELECT `tenant_id`,`ad_account_id`,`id` FROM `skit_ad_callback_inbox` WHERE "
            + "(`processing_status`='PENDING' OR "
            + "(`processing_status`='RETRY_WAIT' AND `next_attempt_at`<=CURRENT_TIMESTAMP) OR "
            + "(`processing_status`='PROCESSING' AND `lease_until`<=CURRENT_TIMESTAMP)) "
            + "ORDER BY `id` LIMIT #{limit} FOR UPDATE SKIP LOCKED")
    @InterceptorIgnore(tenantLine = "true") // intentionally claims across tenants and returns tenant_id for scoped CAS
    List<SkitAdCallbackClaimDO> selectReadyClaimsForUpdate(@Param("limit") int limit);

    @Update("UPDATE `skit_ad_callback_inbox` SET `processing_status`='PROCESSING',"
            + "`error_code`=NULL,`lease_owner`=#{leaseOwner},"
            + "`lease_until`=TIMESTAMPADD(SECOND,#{leaseSeconds},CURRENT_TIMESTAMP),"
            + "`processing_attempt_count`=`processing_attempt_count`+1,"
            + "`next_attempt_at`=NULL,`processed_at`=NULL,"
            + "`updater`='callback-drain',`update_time`=CURRENT_TIMESTAMP "
            + "WHERE `tenant_id`=#{tenantId} AND `ad_account_id`=#{adAccountId} AND `id`=#{id} AND "
            + "(`processing_status`='PENDING' OR "
            + "(`processing_status`='RETRY_WAIT' AND `next_attempt_at`<=CURRENT_TIMESTAMP) OR "
            + "(`processing_status`='PROCESSING' AND `lease_until`<=CURRENT_TIMESTAMP))")
    int claimForProcessingCas(@Param("tenantId") Long tenantId,
                              @Param("adAccountId") Long adAccountId,
                              @Param("id") Long id,
                              @Param("leaseOwner") String leaseOwner,
                              @Param("leaseSeconds") int leaseSeconds);

    @Update("UPDATE `skit_ad_callback_inbox` SET `processing_status`='DEAD_LETTER',"
            + "`error_code`=#{errorCode},`lease_owner`=NULL,`lease_until`=NULL,`next_attempt_at`=NULL,"
            + "`processed_at`=CURRENT_TIMESTAMP,`updater`='callback-drain',"
            + "`update_time`=CURRENT_TIMESTAMP "
            + "WHERE `tenant_id`=#{tenantId} AND `ad_account_id`=#{adAccountId} AND `id`=#{id} "
            + "AND `processing_status`='PROCESSING' AND `lease_until`<=CURRENT_TIMESTAMP "
            + "AND `processing_attempt_count`>=#{maxAttempts}")
    int markExpiredProcessingDeadLetterCas(@Param("tenantId") Long tenantId,
                                           @Param("adAccountId") Long adAccountId,
                                           @Param("id") Long id,
                                           @Param("errorCode") String errorCode,
                                           @Param("maxAttempts") int maxAttempts);

    @Insert("INSERT INTO `skit_ad_callback_inbox` ("
            + "`tenant_id`,`ad_account_id`,`ad_session_id`,`callback_key_version`,"
            + "`reward_secret_version`,`provider`,`callback_type`,`idempotency_key`,"
            + "`provider_user_id`,`extra_data_hash`,`provider_transaction_id`,`provider_show_id`,"
            + "`provider_request_id`,`placement_id`,`adsource_id`,`network_firm_id`,"
            + "`source_currency`,`source_amount_units`,`amount_scale`,`signed_field_mask`,"
            + "`evidence_provenance`,`canonical_payload_hash`,`authentication_level`,"
            + "`signature_status`,`delivery_integrity_status`,`processing_status`,"
            + "`payload_ciphertext`,`payload_nonce`,`payload_key_id`,`payload_envelope_version`,"
            + "`payload_expires_at`,`processing_attempt_count`,`received_at`,"
            + "`ingress_response_code`,`creator`,`updater`) VALUES ("
            + "#{tenantId},#{adAccountId},#{adSessionId},#{callbackKeyVersion},"
            + "#{rewardSecretVersion},#{provider},#{callbackType},#{idempotencyKey},"
            + "#{providerUserId},#{extraDataHash},#{providerTransactionId},#{providerShowId},"
            + "#{providerRequestId},#{placementId},#{adsourceId},#{networkFirmId},"
            + "#{sourceCurrency},#{sourceAmountUnits},#{amountScale},#{signedFieldMask},"
            + "#{evidenceProvenance},#{canonicalPayloadHash},#{authenticationLevel},"
            + "#{signatureStatus},#{deliveryIntegrityStatus},#{processingStatus},"
            + "#{payloadCiphertext},#{payloadNonce},#{payloadKeyId},#{payloadEnvelopeVersion},"
            + "#{payloadExpiresAt},#{processingAttemptCount},#{receivedAt},"
            + "#{ingressResponseCode},'callback-ingress','callback-ingress') "
            + "ON DUPLICATE KEY UPDATE `id`=LAST_INSERT_ID(`id`)")
    @InterceptorIgnore(tenantLine = "true") // tenant_id is explicitly bound and guarded by database FKs
    @SelectKey(statement = "SELECT LAST_INSERT_ID()", keyProperty = "id",
            before = false, resultType = Long.class)
    int insertOrGetCanonical(SkitAdCallbackInboxDO row);

    @Select("SELECT * FROM `skit_ad_callback_inbox` WHERE `tenant_id`=#{tenantId} "
            + "AND `ad_account_id`=#{adAccountId} AND `id`=#{id} FOR UPDATE")
    SkitAdCallbackInboxDO selectByTenantAccountAndIdForUpdate(@Param("tenantId") Long tenantId,
                                                              @Param("adAccountId") Long adAccountId,
                                                              @Param("id") Long id);

    @Select("SELECT * FROM `skit_ad_callback_inbox` WHERE `tenant_id`=#{tenantId} "
            + "AND `ad_account_id`=#{adAccountId} AND `id`=#{id} AND `deleted`=b'0'")
    SkitAdCallbackInboxDO selectByTenantAccountAndId(@Param("tenantId") Long tenantId,
                                                     @Param("adAccountId") Long adAccountId,
                                                     @Param("id") Long id);

    @Select("SELECT * FROM `skit_ad_callback_inbox` WHERE `tenant_id`=#{tenantId} "
            + "AND `ad_account_id`=#{adAccountId} AND `id`=#{id} "
            + "AND `processing_status`='PROCESSING' AND `lease_owner`=#{leaseOwner} "
            + "AND `lease_until`>=CURRENT_TIMESTAMP FOR UPDATE")
    SkitAdCallbackInboxDO selectActiveClaimForUpdate(@Param("tenantId") Long tenantId,
                                                      @Param("adAccountId") Long adAccountId,
                                                      @Param("id") Long id,
                                                      @Param("leaseOwner") String leaseOwner);

    @Update("UPDATE `skit_ad_callback_inbox` SET "
            + "`delivery_integrity_status`='PAYLOAD_CONFLICT',`integrity_conflict_at`=#{conflictAt},"
            + "`updater`='callback-integrity',`update_time`=#{conflictAt} "
            + "WHERE `tenant_id`=#{tenantId} AND `ad_account_id`=#{adAccountId} AND `id`=#{id} "
            + "AND `delivery_integrity_status`='CANONICAL' AND `integrity_conflict_at` IS NULL")
    int markPayloadConflict(@Param("tenantId") Long tenantId,
                            @Param("adAccountId") Long adAccountId,
                            @Param("id") Long id,
                            @Param("conflictAt") LocalDateTime conflictAt);

    @Update("UPDATE `skit_ad_callback_inbox` SET `processing_status`='SUCCEEDED',"
            + "`error_code`=NULL,`lease_owner`=NULL,`lease_until`=NULL,`next_attempt_at`=NULL,"
            + "`processed_at`=CURRENT_TIMESTAMP,`updater`='callback-processor',"
            + "`update_time`=CURRENT_TIMESTAMP "
            + "WHERE `tenant_id`=#{tenantId} AND `ad_account_id`=#{adAccountId} AND `id`=#{id} "
            + "AND `processing_status`='PROCESSING' AND `lease_owner`=#{leaseOwner} "
            + "AND `lease_until`>=CURRENT_TIMESTAMP")
    int markSucceededCas(@Param("tenantId") Long tenantId,
                         @Param("adAccountId") Long adAccountId,
                         @Param("id") Long id,
                         @Param("leaseOwner") String leaseOwner);

    @Update("UPDATE `skit_ad_callback_inbox` SET `processing_status`='REJECTED',"
            + "`error_code`=#{errorCode},`lease_owner`=NULL,`lease_until`=NULL,`next_attempt_at`=NULL,"
            + "`processed_at`=CURRENT_TIMESTAMP,`updater`='callback-processor',"
            + "`update_time`=CURRENT_TIMESTAMP "
            + "WHERE `tenant_id`=#{tenantId} AND `ad_account_id`=#{adAccountId} AND `id`=#{id} "
            + "AND `processing_status`='PROCESSING' AND `lease_owner`=#{leaseOwner} "
            + "AND `lease_until`>=CURRENT_TIMESTAMP")
    int markRejectedCas(@Param("tenantId") Long tenantId,
                        @Param("adAccountId") Long adAccountId,
                        @Param("id") Long id,
                        @Param("leaseOwner") String leaseOwner,
                        @Param("errorCode") String errorCode);

    @Update("UPDATE `skit_ad_callback_inbox` SET `processing_status`='RETRY_WAIT',"
            + "`error_code`=#{errorCode},`lease_owner`=NULL,`lease_until`=NULL,"
            + "`next_attempt_at`=TIMESTAMPADD(SECOND,CAST(LEAST(#{maxBackoffSeconds},"
            + "#{baseBackoffSeconds}*POW(2,LEAST(`processing_attempt_count`-1,30))) AS SIGNED),"
            + "CURRENT_TIMESTAMP),`processed_at`=NULL,`updater`='callback-drain',"
            + "`update_time`=CURRENT_TIMESTAMP "
            + "WHERE `tenant_id`=#{tenantId} AND `ad_account_id`=#{adAccountId} AND `id`=#{id} "
            + "AND `processing_status`='PROCESSING' AND `lease_owner`=#{leaseOwner} "
            + "AND `lease_until`>=CURRENT_TIMESTAMP AND `processing_attempt_count`<#{maxAttempts}")
    int markRetryWaitCas(@Param("tenantId") Long tenantId,
                         @Param("adAccountId") Long adAccountId,
                         @Param("id") Long id,
                         @Param("leaseOwner") String leaseOwner,
                         @Param("errorCode") String errorCode,
                         @Param("maxAttempts") int maxAttempts,
                         @Param("baseBackoffSeconds") int baseBackoffSeconds,
                         @Param("maxBackoffSeconds") int maxBackoffSeconds);

    @Update("UPDATE `skit_ad_callback_inbox` `i` "
            + "JOIN `skit_ad_session` `s` ON `s`.`tenant_id`=`i`.`tenant_id` "
            + "AND `s`.`ad_account_id`=`i`.`ad_account_id` AND `s`.`id`=`i`.`ad_session_id` "
            + "AND `s`.`callback_key_version`=`i`.`callback_key_version` "
            + "SET `i`.`processing_status`='RETRY_WAIT',"
            + "`i`.`error_code`='PANGLE_ATTESTATION_PENDING',"
            + "`i`.`lease_owner`=NULL,`i`.`lease_until`=NULL,"
            + "`i`.`next_attempt_at`=CASE WHEN EXISTS(SELECT 1 "
            + "FROM `skit_pangle_reward_attestation` `a` "
            + "WHERE `a`.`tenant_id`=`i`.`tenant_id` "
            + "AND `a`.`taku_ad_account_id`=`i`.`ad_account_id` "
            + "AND `a`.`ad_session_id`=`i`.`ad_session_id` "
            + "AND `a`.`callback_key_version`=`i`.`callback_key_version` "
            + "AND `a`.`pangle_ad_account_id`=`s`.`pangle_ad_account_id` "
            + "AND `a`.`pangle_reward_secret_version`=`s`.`pangle_reward_secret_version` "
            + "AND `a`.`pangle_reward_placement_id`=`s`.`pangle_reward_placement_id` "
            + "AND `a`.`provider`='PANGLE' "
            + "AND `a`.`provider_user_id`=`i`.`provider_user_id` "
            + "AND `a`.`extra_data_hash`=`i`.`extra_data_hash` AND `a`.`deleted`=b'0') "
            + "THEN CURRENT_TIMESTAMP ELSE TIMESTAMPADD(SECOND,"
            + "CAST(LEAST(#{maxBackoffSeconds},#{baseBackoffSeconds}*"
            + "POW(2,LEAST(`i`.`processing_attempt_count`-1,30))) AS SIGNED),"
            + "CURRENT_TIMESTAMP) END,`i`.`processed_at`=NULL,"
            + "`i`.`updater`='callback-drain',`i`.`update_time`=CURRENT_TIMESTAMP "
            + "WHERE `i`.`tenant_id`=#{tenantId} AND `i`.`ad_account_id`=#{adAccountId} "
            + "AND `i`.`id`=#{id} AND `i`.`provider`='TAKU' "
            + "AND `i`.`callback_type`='REWARD' AND `i`.`network_firm_id`=15 "
            + "AND `i`.`processing_status`='PROCESSING' AND `i`.`lease_owner`=#{leaseOwner} "
            + "AND `i`.`lease_until`>=CURRENT_TIMESTAMP "
            + "AND `i`.`reward_secret_version`=`s`.`reward_secret_version` "
            + "AND `i`.`received_at`=`s`.`reward_callback_received_at` "
            + "AND `s`.`reward_callback_inbox_id`=`i`.`id` "
            + "AND `s`.`provider`='TAKU' AND `s`.`reward_verification_status`='PENDING' "
            + "AND `s`.`entitlement_status`='NONE' "
            + "AND `s`.`reward_accept_until`>=CURRENT_TIMESTAMP "
            + "AND `i`.`received_at`<=`s`.`reward_accept_until` "
            + "AND `s`.`active_scope_hash` IS NOT NULL "
            + "AND `s`.`active_scope_released_at` IS NULL "
            + "AND `s`.`active_scope_release_reason` IS NULL "
            + "AND `i`.`payload_expires_at`>CURRENT_TIMESTAMP "
            + "AND `i`.`deleted`=b'0' AND `s`.`deleted`=b'0'")
    @InterceptorIgnore(tenantLine = "true") // every joined row is explicitly bound to the callback tenant
    int markPanglePrerequisiteRetryWaitCas(
            @Param("tenantId") Long tenantId,
            @Param("adAccountId") Long adAccountId,
            @Param("id") Long id,
            @Param("leaseOwner") String leaseOwner,
            @Param("baseBackoffSeconds") int baseBackoffSeconds,
            @Param("maxBackoffSeconds") int maxBackoffSeconds);

    @Update("UPDATE `skit_ad_callback_inbox` `i` "
            + "JOIN `skit_ad_session` `s` ON `s`.`tenant_id`=`i`.`tenant_id` "
            + "AND `s`.`ad_account_id`=`i`.`ad_account_id` AND `s`.`id`=`i`.`ad_session_id` "
            + "AND `s`.`callback_key_version`=`i`.`callback_key_version` "
            + "SET `i`.`processing_status`='RETRY_WAIT',"
            + "`i`.`error_code`='PANGLE_ATTESTATION_PENDING',"
            + "`i`.`lease_owner`=NULL,`i`.`lease_until`=NULL,"
            + "`i`.`next_attempt_at`=CASE WHEN EXISTS(SELECT 1 "
            + "FROM `skit_pangle_reward_attestation` `a` "
            + "WHERE `a`.`tenant_id`=`i`.`tenant_id` "
            + "AND `a`.`taku_ad_account_id`=`i`.`ad_account_id` "
            + "AND `a`.`ad_session_id`=`i`.`ad_session_id` "
            + "AND `a`.`callback_key_version`=`i`.`callback_key_version` "
            + "AND `a`.`pangle_ad_account_id`=`s`.`pangle_ad_account_id` "
            + "AND `a`.`pangle_reward_secret_version`=`s`.`pangle_reward_secret_version` "
            + "AND `a`.`pangle_reward_placement_id`=`s`.`pangle_reward_placement_id` "
            + "AND `a`.`provider`='PANGLE' "
            + "AND `a`.`provider_user_id`=`i`.`provider_user_id` "
            + "AND `a`.`extra_data_hash`=`i`.`extra_data_hash` AND `a`.`deleted`=b'0') "
            + "THEN CURRENT_TIMESTAMP ELSE TIMESTAMPADD(SECOND,"
            + "CAST(LEAST(#{maxBackoffSeconds},#{baseBackoffSeconds}*"
            + "POW(2,LEAST(`i`.`processing_attempt_count`-1,30))) AS SIGNED),"
            + "CURRENT_TIMESTAMP) END,`i`.`processed_at`=NULL,"
            + "`i`.`updater`='callback-drain',`i`.`update_time`=CURRENT_TIMESTAMP "
            + "WHERE `i`.`tenant_id`=#{tenantId} AND `i`.`ad_account_id`=#{adAccountId} "
            + "AND `i`.`id`=#{id} AND `i`.`provider`='TAKU' "
            + "AND `i`.`callback_type`='REWARD' AND `i`.`network_firm_id`=15 "
            + "AND `i`.`processing_status`='PROCESSING' "
            + "AND `i`.`lease_until`<=CURRENT_TIMESTAMP "
            + "AND `i`.`reward_secret_version`=`s`.`reward_secret_version` "
            + "AND `i`.`received_at`=`s`.`reward_callback_received_at` "
            + "AND `s`.`reward_callback_inbox_id`=`i`.`id` "
            + "AND `s`.`provider`='TAKU' AND `s`.`reward_verification_status`='PENDING' "
            + "AND `s`.`entitlement_status`='NONE' "
            + "AND `s`.`reward_accept_until`>=CURRENT_TIMESTAMP "
            + "AND `i`.`received_at`<=`s`.`reward_accept_until` "
            + "AND `s`.`active_scope_hash` IS NOT NULL "
            + "AND `s`.`active_scope_released_at` IS NULL "
            + "AND `s`.`active_scope_release_reason` IS NULL "
            + "AND `i`.`payload_expires_at`>CURRENT_TIMESTAMP "
            + "AND `i`.`deleted`=b'0' AND `s`.`deleted`=b'0'")
    @InterceptorIgnore(tenantLine = "true") // every joined row is explicitly bound to the callback tenant
    int recoverExpiredPanglePrerequisiteRetryWaitCas(
            @Param("tenantId") Long tenantId,
            @Param("adAccountId") Long adAccountId,
            @Param("id") Long id,
            @Param("baseBackoffSeconds") int baseBackoffSeconds,
            @Param("maxBackoffSeconds") int maxBackoffSeconds);

    @Update("UPDATE `skit_ad_callback_inbox` `i` "
            + "JOIN `skit_ad_session` `s` ON `s`.`tenant_id`=`i`.`tenant_id` "
            + "AND `s`.`ad_account_id`=`i`.`ad_account_id` AND `s`.`id`=`i`.`ad_session_id` "
            + "AND `s`.`callback_key_version`=`i`.`callback_key_version` "
            + "JOIN `skit_pangle_reward_attestation` `a` ON `a`.`tenant_id`=`i`.`tenant_id` "
            + "AND `a`.`taku_ad_account_id`=`i`.`ad_account_id` "
            + "AND `a`.`ad_session_id`=`i`.`ad_session_id` "
            + "AND `a`.`callback_key_version`=`i`.`callback_key_version` "
            + "AND `a`.`pangle_ad_account_id`=`s`.`pangle_ad_account_id` "
            + "AND `a`.`pangle_reward_secret_version`=`s`.`pangle_reward_secret_version` "
            + "AND `a`.`pangle_reward_placement_id`=`s`.`pangle_reward_placement_id` "
            + "AND `a`.`provider`='PANGLE' "
            + "AND `a`.`provider_user_id`=`i`.`provider_user_id` "
            + "AND `a`.`extra_data_hash`=`i`.`extra_data_hash` AND `a`.`deleted`=b'0' "
            + "SET `i`.`processing_status`='PENDING',`i`.`error_code`=NULL,"
            + "`i`.`lease_owner`=NULL,`i`.`lease_until`=NULL,`i`.`next_attempt_at`=NULL,"
            + "`i`.`processed_at`=NULL,`i`.`updater`='pangle-attestation',"
            + "`i`.`update_time`=CURRENT_TIMESTAMP "
            + "WHERE `i`.`tenant_id`=#{tenantId} AND `i`.`ad_account_id`=#{adAccountId} "
            + "AND `i`.`ad_session_id`=#{adSessionId} "
            + "AND `i`.`callback_key_version`=#{callbackKeyVersion} "
            + "AND `i`.`provider`='TAKU' AND `i`.`callback_type`='REWARD' "
            + "AND `i`.`network_firm_id`=15 AND `i`.`processing_status`='RETRY_WAIT' "
            + "AND `i`.`error_code`='PANGLE_ATTESTATION_PENDING' "
            + "AND `i`.`lease_owner` IS NULL AND `i`.`lease_until` IS NULL "
            + "AND `i`.`processed_at` IS NULL "
            + "AND `i`.`reward_secret_version`=`s`.`reward_secret_version` "
            + "AND `i`.`received_at`=`s`.`reward_callback_received_at` "
            + "AND `s`.`reward_callback_inbox_id`=`i`.`id` "
            + "AND `s`.`provider`='TAKU' AND `s`.`reward_verification_status`='PENDING' "
            + "AND `s`.`entitlement_status`='NONE' "
            + "AND `s`.`reward_accept_until`>=CURRENT_TIMESTAMP "
            + "AND `i`.`received_at`<=`s`.`reward_accept_until` "
            + "AND `s`.`active_scope_hash` IS NOT NULL "
            + "AND `s`.`active_scope_released_at` IS NULL "
            + "AND `s`.`active_scope_release_reason` IS NULL "
            + "AND `i`.`payload_expires_at`>CURRENT_TIMESTAMP "
            + "AND `i`.`deleted`=b'0' AND `s`.`deleted`=b'0'")
    @InterceptorIgnore(tenantLine = "true") // exact tenant/account/session/version scope is enforced in SQL
    int wakePangleAttestationPendingRewardCas(
            @Param("tenantId") Long tenantId,
            @Param("adAccountId") Long adAccountId,
            @Param("adSessionId") Long adSessionId,
            @Param("callbackKeyVersion") Integer callbackKeyVersion);

    @Update("UPDATE `skit_ad_callback_inbox` SET `processing_status`='DEAD_LETTER',"
            + "`error_code`=#{errorCode},`lease_owner`=NULL,`lease_until`=NULL,`next_attempt_at`=NULL,"
            + "`processed_at`=CURRENT_TIMESTAMP,`updater`='callback-drain',"
            + "`update_time`=CURRENT_TIMESTAMP "
            + "WHERE `tenant_id`=#{tenantId} AND `ad_account_id`=#{adAccountId} AND `id`=#{id} "
            + "AND `processing_status`='PROCESSING' AND `lease_owner`=#{leaseOwner} "
            + "AND `lease_until`>=CURRENT_TIMESTAMP AND `processing_attempt_count`>=#{maxAttempts}")
    int markDeadLetterCas(@Param("tenantId") Long tenantId,
                          @Param("adAccountId") Long adAccountId,
                          @Param("id") Long id,
                          @Param("leaseOwner") String leaseOwner,
                          @Param("errorCode") String errorCode,
                          @Param("maxAttempts") int maxAttempts);

    @Update("UPDATE `skit_ad_callback_inbox` SET "
            + "`dead_letter_alerted_at`=CURRENT_TIMESTAMP,`updater`='callback-alert',"
            + "`update_time`=CURRENT_TIMESTAMP "
            + "WHERE `tenant_id`=#{tenantId} AND `ad_account_id`=#{adAccountId} AND `id`=#{id} "
            + "AND `processing_status`='DEAD_LETTER' AND `dead_letter_alerted_at` IS NULL "
            + "AND `processed_at` IS NOT NULL AND `processed_at`<=CURRENT_TIMESTAMP")
    int markDeadLetterAlertedCas(@Param("tenantId") Long tenantId,
                                 @Param("adAccountId") Long adAccountId,
                                 @Param("id") Long id);

    @Update("UPDATE `skit_ad_callback_inbox` SET `payload_ciphertext`=NULL,"
            + "`payload_nonce`=NULL,`payload_key_id`=NULL,`payload_envelope_version`=NULL,"
            + "`payload_expires_at`=NULL,`updater`='callback-retention',"
            + "`update_time`=CURRENT_TIMESTAMP WHERE `tenant_id`=#{tenantId} "
            + "AND `ad_account_id`=#{adAccountId} AND `id`=#{id} "
            + "AND `processing_status` IN ('SUCCEEDED','REJECTED','DEAD_LETTER') "
            + "AND `payload_ciphertext` IS NOT NULL AND `payload_nonce` IS NOT NULL "
            + "AND `payload_key_id` IS NOT NULL AND `payload_envelope_version` IS NOT NULL "
            + "AND `payload_expires_at`<=CURRENT_TIMESTAMP")
    int eraseExpiredTerminalPayloadCas(@Param("tenantId") Long tenantId,
                                       @Param("adAccountId") Long adAccountId,
                                       @Param("id") Long id);

}
