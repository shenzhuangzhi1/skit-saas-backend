package cn.iocoder.yudao.module.skit.dal.mysql.reconciliation;

import cn.iocoder.yudao.module.skit.dal.dataobject.reconciliation.SkitAdReconciliationAllocationDO;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface SkitAdReconciliationAllocationMapper {

    @Insert("INSERT INTO `skit_ad_reconciliation_allocation` "
            + "(`tenant_id`,`reconciliation_bucket_id`,`reconciliation_revision_id`,`revision_no`,"
            + "`event_id`,`beneficiary_type`,`beneficiary_member_id`,`level_no`,`policy_snapshot_id`,"
            + "`currency`,`amount_scale`,`cumulative_target_units`,`creator`,`updater`) VALUES "
            + "(#{tenantId},#{reconciliationBucketId},#{reconciliationRevisionId},#{revisionNo},"
            + "#{eventId},#{beneficiaryType},#{beneficiaryMemberId},#{levelNo},#{policySnapshotId},"
            + "#{currency},#{amountScale},#{cumulativeTargetUnits},'reconciliation','reconciliation')")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertCanonical(SkitAdReconciliationAllocationDO row);

    @Select("SELECT * FROM `skit_ad_reconciliation_allocation` WHERE `tenant_id`=#{tenantId} "
            + "AND `event_id`=#{eventId} AND `reconciliation_revision_id`=#{revisionId} "
            + "AND `revision_no`=#{revisionNo} AND `beneficiary_type`=#{beneficiaryType} "
            + "AND `beneficiary_member_id`=#{beneficiaryMemberId} AND `level_no`=#{levelNo} "
            + "AND `policy_snapshot_id`=#{policySnapshotId} AND `deleted`=b'0' FOR UPDATE")
    SkitAdReconciliationAllocationDO selectCanonicalForUpdate(
            @Param("tenantId") long tenantId,
            @Param("eventId") long eventId,
            @Param("revisionId") long revisionId,
            @Param("revisionNo") int revisionNo,
            @Param("beneficiaryType") int beneficiaryType,
            @Param("beneficiaryMemberId") long beneficiaryMemberId,
            @Param("levelNo") int levelNo,
            @Param("policySnapshotId") long policySnapshotId);

    @Select("SELECT * FROM `skit_ad_reconciliation_allocation` WHERE `tenant_id`=#{tenantId} "
            + "AND `event_id`=#{eventId} AND `beneficiary_type`=#{beneficiaryType} "
            + "AND `beneficiary_member_id`=#{beneficiaryMemberId} AND `level_no`=#{levelNo} "
            + "AND `revision_no`<#{revisionNo} AND `deleted`=b'0' "
            + "ORDER BY `revision_no` DESC,`id` DESC LIMIT 1 FOR UPDATE")
    @InterceptorIgnore(tenantLine = "true") // tenant_id is explicit; preserve MySQL locking-clause order
    SkitAdReconciliationAllocationDO selectLatestBeforeRevisionForUpdate(
            @Param("tenantId") long tenantId,
            @Param("eventId") long eventId,
            @Param("beneficiaryType") int beneficiaryType,
            @Param("beneficiaryMemberId") long beneficiaryMemberId,
            @Param("levelNo") int levelNo,
            @Param("revisionNo") int revisionNo);

}
