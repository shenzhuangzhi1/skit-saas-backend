package cn.iocoder.yudao.module.skit.dal.mysql.reconciliation;

import cn.iocoder.yudao.module.skit.dal.dataobject.reconciliation.SkitAdReconciliationRevisionDO;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;

@Mapper
public interface SkitAdReconciliationRevisionMapper {

    @Insert("INSERT INTO `skit_ad_reconciliation_revision` (`tenant_id`,`ad_account_id`,"
            + "`reconciliation_bucket_id`,`report_pull_id`,`bucket_key`,`report_date`,`revision_hash`,"
            + "`revision_no`,`target_actual_units`,`unmatched_actual_units`,`amount_scale`,`currency`,"
            + "`final_revision`,`source_report_impressions`,`source_report_impressions_available`,"
            + "`matched_event_count`,`status`,"
            + "`reconciled_at`,`creator`,`updater`) VALUES (#{tenantId},#{adAccountId},"
            + "#{reconciliationBucketId},#{reportPullId},#{bucketKey},#{reportDate},#{revisionHash},"
            + "#{revisionNo},#{targetActualUnits},#{unmatchedActualUnits},#{amountScale},#{currency},"
            + "#{finalRevision},#{sourceReportImpressions},#{sourceReportImpressionsAvailable},"
            + "#{matchedEventCount},#{status},"
            + "#{reconciledAt},'reconciliation','reconciliation')")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(SkitAdReconciliationRevisionDO row);

    @Select("SELECT * FROM `skit_ad_reconciliation_revision` WHERE `tenant_id`=#{tenantId} "
            + "AND `ad_account_id`=#{adAccountId} AND `bucket_key`=#{bucketKey} "
            + "AND `report_date`=#{reportDate} AND `revision_hash`=#{revisionHash} "
            + "AND `deleted`=b'0' LIMIT 1 FOR UPDATE")
    SkitAdReconciliationRevisionDO selectCanonicalForUpdate(
            @Param("tenantId") long tenantId, @Param("adAccountId") long adAccountId,
            @Param("bucketKey") String bucketKey, @Param("reportDate") LocalDate reportDate,
            @Param("revisionHash") byte[] revisionHash);

    @Select("SELECT * FROM `skit_ad_reconciliation_revision` WHERE `tenant_id`=#{tenantId} "
            + "AND `reconciliation_bucket_id`=#{bucketId} AND `deleted`=b'0' "
            + "ORDER BY `revision_no` DESC,`id` DESC LIMIT 1 FOR UPDATE")
    @InterceptorIgnore(tenantLine = "true") // tenant_id is explicit; preserve MySQL locking-clause order
    SkitAdReconciliationRevisionDO selectLatestForUpdate(@Param("tenantId") long tenantId,
                                                          @Param("bucketId") long bucketId);

}
