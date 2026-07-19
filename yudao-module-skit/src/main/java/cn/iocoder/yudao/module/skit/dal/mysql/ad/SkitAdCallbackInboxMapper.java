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
