package cn.iocoder.yudao.module.skit.dal.mysql.revenue;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.skit.dal.dataobject.revenue.SkitAdRevenueEventDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.reconciliation.SkitAdReportEventRouteDO;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface SkitAdRevenueEventMapper extends BaseMapperX<SkitAdRevenueEventDO> {

    @Select("SELECT MIN(`e`.`occurred_time`) FROM `skit_ad_revenue_event` `e` "
            + "WHERE `e`.`tenant_id`=#{tenantId} AND `e`.`ad_account_id`=#{adAccountId} "
            + "AND `e`.`provider`='TAKU' AND `e`.`source_type`='TAKU_IMPRESSION' "
            + "AND `e`.`occurred_time`<#{beforeTime} AND `e`.`match_status`='MATCHED' "
            + "AND `e`.`reconciliation_status` IN ('FROZEN','SUSPENSE','RECONCILED') "
            + "AND `e`.`legacy_unverified`=b'0' AND `e`.`deleted`=b'0' "
            + "AND NOT EXISTS (SELECT 1 FROM `skit_ad_reconciliation_event_link` `l` "
            + "JOIN `skit_ad_reconciliation_revision` `r` "
            + "ON `r`.`tenant_id`=`l`.`tenant_id` AND `r`.`id`=`l`.`reconciliation_revision_id` "
            + "AND `r`.`final_revision`=b'1' AND `r`.`deleted`=b'0' "
            + "WHERE `l`.`tenant_id`=`e`.`tenant_id` AND `l`.`event_id`=`e`.`id` "
            + "AND `l`.`deleted`=b'0') AND NOT EXISTS (SELECT 1 "
            + "FROM `skit_ad_reconciliation_revision` `current_revision` "
            + "WHERE `current_revision`.`tenant_id`=`e`.`tenant_id` "
            + "AND `current_revision`.`id`=`e`.`reconciliation_revision_id` "
            + "AND `current_revision`.`final_revision`=b'1' "
            + "AND `current_revision`.`deleted`=b'0') "
            + "GROUP BY DATE(`e`.`occurred_time`) ORDER BY MIN(`e`.`occurred_time`),MIN(`e`.`id`) "
            + "LIMIT #{limit}")
    List<LocalDateTime> selectHistoricalPendingEventTimes(
            @Param("tenantId") long tenantId, @Param("adAccountId") long adAccountId,
            @Param("beforeTime") LocalDateTime beforeTime, @Param("limit") int limit);

    @Select("SELECT `e`.`placement_id`,`i`.`network_firm_id`,`i`.`adsource_id` "
            + "FROM `skit_ad_revenue_event` `e` JOIN `skit_ad_callback_inbox` `i` "
            + "ON `i`.`tenant_id`=`e`.`tenant_id` AND `i`.`id`=`e`.`callback_inbox_id` "
            + "AND `i`.`ad_account_id`=`e`.`ad_account_id` "
            + "WHERE `e`.`tenant_id`=#{tenantId} AND `e`.`ad_account_id`=#{adAccountId} "
            + "AND `e`.`provider`='TAKU' AND `e`.`source_type`='TAKU_IMPRESSION' "
            + "AND `e`.`placement_id`=#{placementId} AND `e`.`occurred_time`>=#{rangeStart} "
            + "AND `e`.`occurred_time`<#{rangeEnd} AND `e`.`match_status`='MATCHED' "
            + "AND `e`.`source_verification_status` IN ('UNSIGNED_OBSERVATION','REPORT_CONFIRMED') "
            + "AND `e`.`reward_qualification_status` IN ('REWARDED','NON_REWARDED') "
            + "AND `e`.`reconciliation_status` IN ('FROZEN','SUSPENSE','RECONCILED') "
            + "AND `e`.`legacy_unverified`=b'0' AND `e`.`deleted`=b'0' "
            + "AND `i`.`network_firm_id`>0 AND `i`.`adsource_id` IS NOT NULL "
            + "AND `i`.`delivery_integrity_status`='CANONICAL' AND `i`.`deleted`=b'0' "
            + "AND `e`.`adsource_id`=`i`.`adsource_id` "
            + "ORDER BY `e`.`placement_id`,`i`.`network_firm_id`,`i`.`adsource_id`,`e`.`id` FOR UPDATE")
    @InterceptorIgnore(tenantLine = "true") // every joined table is explicitly scoped by tenant_id
    List<SkitAdReportEventRouteDO> selectReportEventRoutesForUpdate(
            @Param("tenantId") long tenantId, @Param("adAccountId") long adAccountId,
            @Param("placementId") String placementId,
            @Param("rangeStart") LocalDateTime rangeStart,
            @Param("rangeEnd") LocalDateTime rangeEnd);

    @Select("SELECT `e`.* FROM `skit_ad_revenue_event` `e` "
            + "JOIN `skit_ad_callback_inbox` `i` ON `i`.`tenant_id`=`e`.`tenant_id` "
            + "AND `i`.`id`=`e`.`callback_inbox_id` AND `i`.`ad_account_id`=`e`.`ad_account_id` "
            + "WHERE `e`.`tenant_id`=#{tenantId} AND `e`.`ad_account_id`=#{adAccountId} "
            + "AND `e`.`provider`='TAKU' AND `e`.`source_type`='TAKU_IMPRESSION' "
            + "AND `e`.`placement_id`=#{placementId} AND `e`.`source_currency`=#{currency} "
            + "AND `e`.`amount_scale`=#{amountScale} AND `e`.`occurred_time`>=#{rangeStart} "
            + "AND `e`.`occurred_time`<#{rangeEnd} AND `e`.`match_status`='MATCHED' "
            + "AND `e`.`source_verification_status` IN ('UNSIGNED_OBSERVATION','REPORT_CONFIRMED') "
            + "AND `e`.`reward_qualification_status` IN ('REWARDED','NON_REWARDED') "
            + "AND `e`.`reconciliation_status` IN ('FROZEN','SUSPENSE','RECONCILED') "
            + "AND `e`.`legacy_unverified`=b'0' AND `e`.`deleted`=b'0' "
            + "AND `i`.`network_firm_id`=#{networkFirmId} AND `i`.`adsource_id`=#{adsourceId} "
            + "AND `i`.`delivery_integrity_status`='CANONICAL' AND `i`.`deleted`=b'0' "
            + "AND `e`.`adsource_id`=`i`.`adsource_id` ORDER BY `e`.`id` FOR UPDATE")
    @InterceptorIgnore(tenantLine = "true") // every joined table is explicitly scoped by tenant_id
    List<SkitAdRevenueEventDO> selectReportBucketEventsForUpdate(
            @Param("tenantId") long tenantId, @Param("adAccountId") long adAccountId,
            @Param("placementId") String placementId, @Param("networkFirmId") int networkFirmId,
            @Param("adsourceId") String adsourceId, @Param("currency") String currency,
            @Param("amountScale") int amountScale, @Param("rangeStart") LocalDateTime rangeStart,
            @Param("rangeEnd") LocalDateTime rangeEnd);

    @Update("UPDATE `skit_ad_revenue_event` SET `reconciliation_bucket_id`=#{bucketId},"
            + "`reconciliation_revision_id`=#{revisionId},`reconciled_amount_units`=#{actualUnits},"
            + "`source_verification_status`='REPORT_CONFIRMED',"
            + "`reconciliation_status`='RECONCILED',`reconciled_at`=CURRENT_TIMESTAMP,"
            + "`version`=`version`+1,`updater`='reconciliation',`update_time`=CURRENT_TIMESTAMP "
            + "WHERE `tenant_id`=#{tenantId} AND `id`=#{eventId} AND `ad_account_id`=#{adAccountId} "
            + "AND `callback_inbox_id`=#{callbackInboxId} AND `version`=#{expectedVersion} "
            + "AND `source_type`='TAKU_IMPRESSION' AND `match_status`='MATCHED' "
            + "AND `reward_qualification_status` IN ('REWARDED','NON_REWARDED') "
            + "AND `reconciliation_status` IN ('FROZEN','SUSPENSE','RECONCILED') "
            + "AND `legacy_unverified`=b'0' AND `deleted`=b'0'")
    int markReportConfirmedCas(@Param("tenantId") long tenantId,
                               @Param("adAccountId") long adAccountId,
                               @Param("eventId") long eventId,
                               @Param("callbackInboxId") long callbackInboxId,
                               @Param("expectedVersion") int expectedVersion,
                               @Param("bucketId") long bucketId,
                               @Param("revisionId") long revisionId,
                               @Param("actualUnits") long actualUnits);

    @Update("UPDATE `skit_ad_revenue_event` SET `reconciliation_bucket_id`=#{bucketId},"
            + "`reconciliation_revision_id`=#{revisionId},`reconciliation_status`='SUSPENSE',"
            + "`reconciled_amount_units`=0,`reconciled_at`=CURRENT_TIMESTAMP,"
            + "`version`=`version`+1,`updater`='reconciliation-suspense',"
            + "`update_time`=CURRENT_TIMESTAMP WHERE `tenant_id`=#{tenantId} AND `id`=#{eventId} "
            + "AND `ad_account_id`=#{adAccountId} AND `callback_inbox_id`=#{callbackInboxId} "
            + "AND `version`=#{expectedVersion} AND `source_type`='TAKU_IMPRESSION' "
            + "AND `match_status`='MATCHED' "
            + "AND `reward_qualification_status` IN ('REWARDED','NON_REWARDED') "
            + "AND `reconciliation_status` IN ('FROZEN','SUSPENSE','RECONCILED') "
            + "AND `legacy_unverified`=b'0' AND `deleted`=b'0'")
    int markReportSuspenseCas(@Param("tenantId") long tenantId,
                              @Param("adAccountId") long adAccountId,
                              @Param("eventId") long eventId,
                              @Param("callbackInboxId") long callbackInboxId,
                              @Param("expectedVersion") int expectedVersion,
                              @Param("bucketId") long bucketId,
                              @Param("revisionId") long revisionId);

    @Select("SELECT * FROM `skit_ad_revenue_event` WHERE `tenant_id`=#{tenantId} "
            + "AND `ad_session_id`=#{adSessionId} AND `source_type`=#{sourceType} FOR UPDATE")
    SkitAdRevenueEventDO selectByTenantSessionAndSourceForUpdate(@Param("tenantId") Long tenantId,
                                                                 @Param("adSessionId") Long adSessionId,
                                                                 @Param("sourceType") String sourceType);

    @Update("UPDATE `skit_ad_revenue_event` SET `reward_qualification_status`='REWARDED',"
            + "`provider_transaction_id`=#{providerTransactionId},"
            + "`provider_show_id`=COALESCE(`provider_show_id`,#{providerShowId}),"
            + "`verified_at`=#{verifiedAt},`version`=`version`+1,"
            + "`updater`='callback-processor',`update_time`=#{verifiedAt} "
            + "WHERE `tenant_id`=#{tenantId} AND `id`=#{id} AND `ad_session_id`=#{adSessionId} "
            + "AND `ad_account_id`=#{adAccountId} AND `version`=#{expectedVersion} "
            + "AND `callback_inbox_id`=#{expectedCallbackInboxId} "
            + "AND `placement_id`=#{expectedPlacementId} AND `adsource_id`=#{expectedAdsourceId} "
            + "AND `source_type`='TAKU_IMPRESSION' AND `reward_qualification_status`='PENDING_REWARD' "
            + "AND `source_verification_status`='UNSIGNED_OBSERVATION' "
            + "AND `reconciliation_status`='FROZEN' AND `legacy_unverified`=b'0' "
            + "AND `deleted`=b'0' AND EXISTS (SELECT 1 FROM `skit_ad_callback_inbox` `i` "
            + "WHERE `i`.`tenant_id`=#{tenantId} AND `i`.`ad_account_id`=#{adAccountId} "
            + "AND `i`.`id`=#{expectedCallbackInboxId} AND `i`.`ad_session_id`=#{adSessionId} "
            + "AND `i`.`provider`='TAKU' AND `i`.`callback_type`='IMPRESSION' "
            + "AND `i`.`placement_id`=#{expectedPlacementId} "
            + "AND `i`.`network_firm_id`=#{expectedNetworkFirmId} "
            + "AND `i`.`adsource_id`=#{expectedAdsourceId} "
            + "AND `i`.`delivery_integrity_status`='CANONICAL' "
            + "AND `i`.`processing_status`='SUCCEEDED' AND `i`.`deleted`=b'0')")
    int markRewardQualifiedCas(@Param("tenantId") Long tenantId,
                               @Param("id") Long id,
                               @Param("adSessionId") Long adSessionId,
                               @Param("adAccountId") Long adAccountId,
                               @Param("expectedVersion") Integer expectedVersion,
                               @Param("expectedCallbackInboxId") Long expectedCallbackInboxId,
                               @Param("expectedPlacementId") String expectedPlacementId,
                               @Param("expectedNetworkFirmId") Integer expectedNetworkFirmId,
                               @Param("expectedAdsourceId") String expectedAdsourceId,
                               @Param("providerTransactionId") String providerTransactionId,
                               @Param("providerShowId") String providerShowId,
                               @Param("verifiedAt") LocalDateTime verifiedAt);

    @Update("UPDATE `skit_ad_revenue_event` SET `reward_qualification_status`='NON_REWARDED',"
            + "`reconciliation_status`='SUSPENSE',`version`=`version`+1,"
            + "`updater`='callback-processor',`update_time`=#{rejectedAt} "
            + "WHERE `tenant_id`=#{tenantId} AND `id`=#{id} AND `ad_session_id`=#{adSessionId} "
            + "AND `ad_account_id`=#{adAccountId} AND `version`=#{expectedVersion} "
            + "AND `source_type`='TAKU_IMPRESSION' AND `reward_qualification_status`='PENDING_REWARD' "
            + "AND `source_verification_status`='UNSIGNED_OBSERVATION' "
            + "AND `reconciliation_status`='FROZEN' AND `legacy_unverified`=b'0'")
    int markNonRewardedSuspenseCas(@Param("tenantId") Long tenantId,
                                   @Param("id") Long id,
                                   @Param("adSessionId") Long adSessionId,
                                   @Param("adAccountId") Long adAccountId,
                                   @Param("expectedVersion") Integer expectedVersion,
                                   @Param("rejectedAt") LocalDateTime rejectedAt);

    @Update("UPDATE `skit_ad_revenue_event` SET `reward_qualification_status`='NON_REWARDED',"
            + "`reconciliation_status`='FROZEN',`version`=`version`+1,"
            + "`updater`='ad-reward-timeout',`update_time`=CURRENT_TIMESTAMP "
            + "WHERE `tenant_id`=#{tenantId} AND `id`=#{id} AND `ad_session_id`=#{adSessionId} "
            + "AND `ad_account_id`=#{adAccountId} AND `version`=#{expectedVersion} "
            + "AND `source_type`='TAKU_IMPRESSION' "
            + "AND `reward_qualification_status`='PENDING_REWARD' "
            + "AND `source_verification_status`='UNSIGNED_OBSERVATION' "
            + "AND `reconciliation_status`='FROZEN' AND `legacy_unverified`=b'0' "
            + "AND `deleted`=b'0'")
    int markNonRewardedFrozenOnTimeoutCas(@Param("tenantId") Long tenantId,
                                          @Param("id") Long id,
                                          @Param("adSessionId") Long adSessionId,
                                          @Param("adAccountId") Long adAccountId,
                                          @Param("expectedVersion") Integer expectedVersion);

    default SkitAdRevenueEventDO selectByAccountSourceAndExternalEventId(Long adAccountId, String sourceType,
                                                                         String externalEventId) {
        return selectOne(new LambdaQueryWrapperX<SkitAdRevenueEventDO>()
                .eq(SkitAdRevenueEventDO::getAdAccountId, adAccountId)
                .eq(SkitAdRevenueEventDO::getSourceType, sourceType)
                .eq(SkitAdRevenueEventDO::getExternalEventId, externalEventId));
    }

}
