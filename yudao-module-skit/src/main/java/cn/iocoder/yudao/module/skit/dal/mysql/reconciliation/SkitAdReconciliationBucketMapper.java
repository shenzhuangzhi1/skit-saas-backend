package cn.iocoder.yudao.module.skit.dal.mysql.reconciliation;

import cn.iocoder.yudao.module.skit.dal.dataobject.reconciliation.SkitAdReconciliationBucketDO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDate;

@Mapper
public interface SkitAdReconciliationBucketMapper {

    @Insert("INSERT INTO `skit_ad_reconciliation_bucket` (`tenant_id`,`ad_account_id`,`bucket_key`,"
            + "`report_date`,`report_timezone`,`app_id`,`placement_id`,`ad_format`,`network_firm_id`,"
            + "`network_account_id`,`adsource_id`,`currency`,`amount_scale`,`estimate_units`,"
            + "`report_actual_units`,`report_impressions`,`report_impressions_available`,`matched_impressions`,"
            + "`attributable_actual_units`,`suspense_units`,`status`,`creator`,`updater`) VALUES "
            + "(#{tenantId},#{adAccountId},#{bucketKey},#{reportDate},#{reportTimezone},#{appId},"
            + "#{placementId},#{adFormat},#{networkFirmId},#{networkAccountId},#{adsourceId},"
            + "#{currency},#{amountScale},#{estimateUnits},#{reportActualUnits},#{reportImpressions},"
            + "#{reportImpressionsAvailable},#{matchedImpressions},#{attributableActualUnits},"
            + "#{suspenseUnits},#{status},"
            + "'reconciliation','reconciliation')")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(SkitAdReconciliationBucketDO row);

    @Select("SELECT * FROM `skit_ad_reconciliation_bucket` WHERE `tenant_id`=#{tenantId} "
            + "AND `ad_account_id`=#{adAccountId} AND `bucket_key`=#{bucketKey} "
            + "AND `report_date`=#{reportDate} AND `report_timezone`=#{reportTimezone} "
            + "AND `app_id`=#{appId} AND `placement_id`=#{placementId} AND `ad_format`=#{adFormat} "
            + "AND `network_account_id`=#{networkAccountId} AND `network_firm_id`=#{networkFirmId} "
            + "AND `adsource_id`=#{adsourceId} AND `currency`=#{currency} "
            + "AND `deleted`=b'0' LIMIT 1 FOR UPDATE")
    SkitAdReconciliationBucketDO selectIdentityForUpdate(
            @Param("tenantId") long tenantId, @Param("adAccountId") long adAccountId,
            @Param("bucketKey") String bucketKey, @Param("reportDate") LocalDate reportDate,
            @Param("reportTimezone") String reportTimezone, @Param("appId") String appId,
            @Param("placementId") String placementId, @Param("adFormat") String adFormat,
            @Param("networkAccountId") String networkAccountId,
            @Param("networkFirmId") int networkFirmId, @Param("adsourceId") String adsourceId,
            @Param("currency") String currency);

    @Update("UPDATE `skit_ad_reconciliation_bucket` SET `estimate_units`=#{estimateUnits},"
            + "`report_actual_units`=#{reportActualUnits},`report_impressions`=#{reportImpressions},"
            + "`report_impressions_available`=#{reportImpressionsAvailable},"
            + "`matched_impressions`=#{matchedImpressions},"
            + "`attributable_actual_units`=#{attributableActualUnits},`suspense_units`=#{suspenseUnits},"
            + "`status`=#{status},`updater`='reconciliation',`update_time`=CURRENT_TIMESTAMP "
            + "WHERE `tenant_id`=#{tenantId} AND `id`=#{id} AND `ad_account_id`=#{adAccountId} "
            + "AND `deleted`=b'0'")
    int updateProjection(SkitAdReconciliationBucketDO row);

}
