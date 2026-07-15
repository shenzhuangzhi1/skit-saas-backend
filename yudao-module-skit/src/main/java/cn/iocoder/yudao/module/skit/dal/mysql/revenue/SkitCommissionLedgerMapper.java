package cn.iocoder.yudao.module.skit.dal.mysql.revenue;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.skit.dal.dataobject.revenue.SkitCommissionLedgerDO;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface SkitCommissionLedgerMapper extends BaseMapperX<SkitCommissionLedgerDO> {

    @Insert("INSERT INTO `skit_commission_ledger` "
            + "(`tenant_id`,`event_id`,`beneficiary_type`,`beneficiary_member_id`,`level_no`,"
            + "`gross_amount`,`rate_bps`,`amount`,`rule_version`,`status`,`entry_type`,"
            + "`balance_bucket`,`currency`,`gross_amount_units`,`amount_units`,`amount_scale`,"
            + "`reversal_of_id`,`reconciliation_revision_id`,`policy_snapshot_id`,`revision_no`,"
            + "`legacy_unverified`,`creator`,`updater`) VALUES "
            + "(#{tenantId},#{eventId},#{beneficiaryType},#{beneficiaryMemberId},#{levelNo},"
            + "#{grossAmount},#{rateBps},#{amount},#{ruleVersion},#{status},#{entryType},"
            + "#{balanceBucket},#{currency},#{grossAmountUnits},#{amountUnits},#{amountScale},"
            + "#{reversalOfId},#{reconciliationRevisionId},#{policySnapshotId},#{revisionNo},"
            + "#{legacyUnverified},'verified-estimate','verified-estimate')")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertCanonicalEstimate(SkitCommissionLedgerDO row);

    @Insert("INSERT INTO `skit_commission_ledger` "
            + "(`tenant_id`,`event_id`,`beneficiary_type`,`beneficiary_member_id`,`level_no`,"
            + "`gross_amount`,`rate_bps`,`amount`,`rule_version`,`status`,`entry_type`,"
            + "`balance_bucket`,`currency`,`gross_amount_units`,`amount_units`,`amount_scale`,"
            + "`reversal_of_id`,`reconciliation_revision_id`,`policy_snapshot_id`,`revision_no`,"
            + "`legacy_unverified`,`creator`,`updater`) VALUES "
            + "(#{tenantId},#{eventId},#{beneficiaryType},#{beneficiaryMemberId},#{levelNo},"
            + "#{grossAmount},#{rateBps},#{amount},#{ruleVersion},#{status},#{entryType},"
            + "#{balanceBucket},#{currency},#{grossAmountUnits},#{amountUnits},#{amountScale},"
            + "#{reversalOfId},#{reconciliationRevisionId},#{policySnapshotId},#{revisionNo},"
            + "#{legacyUnverified},'reconciliation','reconciliation')")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertCanonicalReconciliationEntry(SkitCommissionLedgerDO row);

    @Select("SELECT * FROM `skit_commission_ledger` WHERE `tenant_id`=#{tenantId} "
            + "AND `event_id`=#{eventId} AND `beneficiary_type`=#{beneficiaryType} "
            + "AND `beneficiary_member_id`=#{beneficiaryMemberId} AND `level_no`=#{levelNo} "
            + "AND `entry_type`=#{entryType} AND `revision_no`=#{revisionNo} "
            + "AND `deleted`=b'0' LIMIT 1 FOR UPDATE")
    SkitCommissionLedgerDO selectCanonicalEntryForUpdate(
            @Param("tenantId") long tenantId,
            @Param("eventId") long eventId,
            @Param("beneficiaryType") int beneficiaryType,
            @Param("beneficiaryMemberId") long beneficiaryMemberId,
            @Param("levelNo") int levelNo,
            @Param("entryType") String entryType,
            @Param("revisionNo") int revisionNo);

    @Select("SELECT * FROM `skit_commission_ledger` WHERE `tenant_id`=#{tenantId} "
            + "AND `event_id`=#{eventId} AND `entry_type`=#{entryType} "
            + "AND `deleted`=b'0' ORDER BY `beneficiary_type`,`beneficiary_member_id`,`level_no` FOR UPDATE")
    @InterceptorIgnore(tenantLine = "true") // tenant_id is explicit; preserve MySQL locking-clause order
    List<SkitCommissionLedgerDO> selectEntriesForEventAndTypeForUpdate(
            @Param("tenantId") long tenantId,
            @Param("eventId") long eventId,
            @Param("entryType") String entryType);

    default PageResult<SkitCommissionLedgerDO> selectPage(PageParam pageParam, Long memberId, Integer beneficiaryType,
                                                           LocalDateTime[] createTime) {
        return selectPage(pageParam, new LambdaQueryWrapperX<SkitCommissionLedgerDO>()
                .eqIfPresent(SkitCommissionLedgerDO::getBeneficiaryMemberId, memberId)
                .eqIfPresent(SkitCommissionLedgerDO::getBeneficiaryType, beneficiaryType)
                .betweenIfPresent(SkitCommissionLedgerDO::getCreateTime, createTime)
                .orderByDesc(SkitCommissionLedgerDO::getId));
    }

}
