package cn.iocoder.yudao.module.skit.dal.mysql.ad;

import cn.hutool.core.collection.CollUtil;
import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import cn.iocoder.yudao.framework.common.enums.CommonStatusEnum;
import cn.iocoder.yudao.module.skit.dal.dataobject.ad.SkitAdAccountDO;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.Collection;
import java.util.Collections;
import java.util.List;

@Mapper
public interface SkitAdAccountMapper extends BaseMapperX<SkitAdAccountDO> {

    String REPORT_ACCOUNT_UNSETTLED_FACTS = "(EXISTS (SELECT 1 FROM `skit_ad_revenue_event` `e` "
            + "LEFT JOIN `skit_ad_reconciliation_revision` `r` "
            + "ON `r`.`tenant_id`=`e`.`tenant_id` "
            + "AND `r`.`id`=`e`.`reconciliation_revision_id` AND `r`.`deleted`=b'0' "
            + "WHERE `e`.`tenant_id`=`a`.`tenant_id` AND `e`.`ad_account_id`=`a`.`id` "
            + "AND `e`.`provider`='TAKU' AND `e`.`source_type`='TAKU_IMPRESSION' "
            + "AND `e`.`legacy_unverified`=b'0' AND `e`.`deleted`=b'0' "
            + "AND (`e`.`reconciliation_revision_id` IS NULL OR `r`.`id` IS NULL "
            + "OR `e`.`reconciliation_status`<>'RECONCILED' "
            + "OR `r`.`final_revision`=b'0' OR `r`.`status`<>'APPLIED')) "
            + "OR EXISTS (SELECT 1 FROM `skit_ad_reconciliation_revision` `r` "
            + "WHERE `r`.`tenant_id`=`a`.`tenant_id` AND `r`.`ad_account_id`=`a`.`id` "
            + "AND `r`.`deleted`=b'0' AND NOT EXISTS (SELECT 1 "
            + "FROM `skit_ad_reconciliation_revision` `newer` "
            + "WHERE `newer`.`tenant_id`=`r`.`tenant_id` "
            + "AND `newer`.`reconciliation_bucket_id`=`r`.`reconciliation_bucket_id` "
            + "AND `newer`.`revision_no`>`r`.`revision_no` AND `newer`.`deleted`=b'0') "
            + "AND (`r`.`final_revision`=b'0' OR `r`.`status`<>'APPLIED')))";

    String REPORT_ACCOUNT_PENDING_WINDOW = "EXISTS (SELECT 1 FROM `skit_ad_report_pull` `p` "
            + "WHERE `p`.`tenant_id`=`a`.`tenant_id` AND `p`.`ad_account_id`=`a`.`id` "
            + "AND `p`.`status`='SUCCEEDED' AND `p`.`final_window`=b'0' AND `p`.`deleted`=b'0' "
            + "AND NOT EXISTS (SELECT 1 FROM `skit_ad_report_pull` `p2` "
            + "WHERE `p2`.`tenant_id`=`p`.`tenant_id` "
            + "AND `p2`.`ad_account_id`=`p`.`ad_account_id` "
            + "AND `p2`.`report_date`=`p`.`report_date` "
            + "AND `p2`.`report_timezone`=`p`.`report_timezone` "
            + "AND `p2`.`currency`=`p`.`currency` AND `p2`.`amount_scale`=`p`.`amount_scale` "
            + "AND `p2`.`status`='SUCCEEDED' AND `p2`.`final_window`=b'1' "
            + "AND `p2`.`deleted`=b'0'))";

    String REPORT_ACCOUNT_UNSETTLED = "(" + REPORT_ACCOUNT_UNSETTLED_FACTS
            + " OR " + REPORT_ACCOUNT_PENDING_WINDOW + ")";

    String REPORT_ACCOUNT_PENDING_DRAIN_FACTS = "(EXISTS (SELECT 1 FROM `skit_ad_revenue_event` `e` "
            + "LEFT JOIN `skit_ad_reconciliation_revision` `r` "
            + "ON `r`.`tenant_id`=`e`.`tenant_id` "
            + "AND `r`.`id`=`e`.`reconciliation_revision_id` AND `r`.`deleted`=b'0' "
            + "WHERE `e`.`tenant_id`=`a`.`tenant_id` AND `e`.`ad_account_id`=`a`.`id` "
            + "AND `e`.`provider`='TAKU' AND `e`.`source_type`='TAKU_IMPRESSION' "
            + "AND `e`.`legacy_unverified`=b'0' AND `e`.`deleted`=b'0' "
            + "AND (`e`.`reconciliation_revision_id` IS NULL OR `r`.`id` IS NULL "
            + "OR `r`.`final_revision`=b'0')) OR EXISTS (SELECT 1 "
            + "FROM `skit_ad_reconciliation_revision` `r` "
            + "WHERE `r`.`tenant_id`=`a`.`tenant_id` AND `r`.`ad_account_id`=`a`.`id` "
            + "AND `r`.`final_revision`=b'0' AND `r`.`deleted`=b'0' "
            + "AND NOT EXISTS (SELECT 1 FROM `skit_ad_reconciliation_revision` `newer` "
            + "WHERE `newer`.`tenant_id`=`r`.`tenant_id` "
            + "AND `newer`.`reconciliation_bucket_id`=`r`.`reconciliation_bucket_id` "
            + "AND `newer`.`revision_no`>`r`.`revision_no` AND `newer`.`deleted`=b'0')))";

    String REPORT_ACCOUNT_PENDING_DRAIN = "(" + REPORT_ACCOUNT_PENDING_DRAIN_FACTS
            + " OR " + REPORT_ACCOUNT_PENDING_WINDOW + ")";

    String REPORT_ROUTE_TENANT_ELIGIBILITY = "EXISTS (SELECT 1 FROM `skit_agent` `g` "
            + "JOIN `system_tenant` `t` ON `t`.`id`=`g`.`tenant_id` AND `t`.`deleted`=b'0' "
            + "WHERE `g`.`tenant_id`=`a`.`tenant_id` AND `g`.`deleted`=b'0' AND "
            + "((`g`.`archived_time` IS NULL AND `t`.`status`=0) OR "
            + "(`g`.`archived_time` IS NOT NULL "
            + "AND CURRENT_TIMESTAMP<DATE_ADD(`g`.`archived_time`,INTERVAL 3 DAY) "
            + "AND EXISTS (SELECT 1 FROM `skit_ad_revenue_event` `e` "
            + "LEFT JOIN `skit_ad_reconciliation_revision` `r` "
            + "ON `r`.`tenant_id`=`e`.`tenant_id` "
            + "AND `r`.`id`=`e`.`reconciliation_revision_id` AND `r`.`deleted`=b'0' "
            + "WHERE `e`.`tenant_id`=`a`.`tenant_id` AND `e`.`ad_account_id`=`a`.`id` "
            + "AND `e`.`provider`='TAKU' AND `e`.`source_type`='TAKU_IMPRESSION' "
            + "AND `e`.`occurred_time`<`g`.`archived_time` "
            + "AND `e`.`reconciliation_status` IN ('FROZEN','SUSPENSE','RECONCILED') "
            + "AND `e`.`legacy_unverified`=b'0' AND `e`.`deleted`=b'0' "
            + "AND (`e`.`reconciliation_revision_id` IS NULL OR `r`.`final_revision`=b'0')))))";

    @Select("SELECT `id` FROM `skit_ad_account` WHERE `tenant_id`=#{tenantId} AND `id`=#{id} "
            + "AND `deleted`=b'0' FOR UPDATE")
    Long lockByTenantAndId(@Param("tenantId") Long tenantId, @Param("id") Long id);

    @Select("SELECT * FROM `skit_ad_account` WHERE `tenant_id`=#{tenantId} "
            + "AND `provider`=#{provider} AND `deleted`=b'0' LIMIT 1 FOR UPDATE")
    @InterceptorIgnore(tenantLine = "true") // tenant_id is explicit; avoid appending predicates after FOR UPDATE
    SkitAdAccountDO selectByProviderForUpdate(@Param("tenantId") long tenantId,
                                               @Param("provider") String provider);

    /**
     * Fail-closed guard for an in-place Taku request-scope mutation.
     * Only the latest append-only revision of each bucket is authoritative;
     * historical D+1/D+2 revisions must not keep a fully settled bucket pending forever.
     */
    @Select("SELECT " + REPORT_ACCOUNT_UNSETTLED + " FROM `skit_ad_account` `a` "
            + "WHERE `a`.`tenant_id`=#{tenantId} AND `a`.`id`=#{adAccountId} "
            + "AND `a`.`provider`='TAKU' AND `a`.`deleted`=b'0'")
    @InterceptorIgnore(tenantLine = "true") // all correlated tables are explicitly scoped by tenant_id
    boolean hasUnsettledTakuReportScope(@Param("tenantId") long tenantId,
                                        @Param("adAccountId") long adAccountId);

    /** Any immutable financial event or report attempt permanently freezes this account's report scope. */
    @Select("SELECT (EXISTS (SELECT 1 FROM `skit_ad_revenue_event` `e` "
            + "WHERE `e`.`tenant_id`=#{tenantId} AND `e`.`ad_account_id`=#{adAccountId} "
            + "AND `e`.`provider`='TAKU' AND `e`.`legacy_unverified`=b'0' "
            + "AND `e`.`deleted`=b'0') OR EXISTS (SELECT 1 FROM `skit_ad_report_pull` `p` "
            + "WHERE `p`.`tenant_id`=#{tenantId} AND `p`.`ad_account_id`=#{adAccountId} "
            + "AND `p`.`provider`='TAKU' AND `p`.`deleted`=b'0'))")
    @InterceptorIgnore(tenantLine = "true") // explicit tenant predicates are required for delegated checks
    boolean hasHistoricalTakuReportFacts(@Param("tenantId") long tenantId,
                                         @Param("adAccountId") long adAccountId);

    @Select("SELECT `id`,`tenant_id`,`provider`,`app_id`,`config_data`,`status` "
            + "FROM `skit_ad_account` WHERE `tenant_id`=#{tenantId} "
            + "AND `provider`='TAKU' AND `status`=0 AND `deleted`=b'0' FOR UPDATE")
    List<SkitAdAccountDO> selectEnabledTakuForUpdate(@Param("tenantId") Long tenantId);

    @Select("SELECT `id`,`tenant_id`,`provider`,`app_id`,`config_data`,`status` "
            + "FROM `skit_ad_account` WHERE `tenant_id`=#{tenantId} "
            + "AND `provider`='TAKU' AND `status`=0 AND `deleted`=b'0' FOR SHARE")
    List<SkitAdAccountDO> selectEnabledTakuForShare(@Param("tenantId") Long tenantId);

    /** Uses the MyBatis-Plus entity result map so the encrypted Server Key is decrypted by its type handler. */
    default List<SkitAdAccountDO> selectEnabledPangleForShare(Long tenantId) {
        return selectList(new LambdaQueryWrapperX<SkitAdAccountDO>()
                .eq(SkitAdAccountDO::getTenantId, tenantId)
                .eq(SkitAdAccountDO::getProvider, "PANGLE")
                .eq(SkitAdAccountDO::getStatus, CommonStatusEnum.ENABLE.getStatus()));
    }

    /** Global routing projection. Call only inside TenantUtils.executeIgnore. */
    @Select("SELECT `a`.`id`,`a`.`tenant_id`,`a`.`provider`,`a`.`account_id`,`a`.`app_id`,"
            + "`a`.`config_data`,`a`.`status`,`a`.`report_timezone`,`a`.`report_currency`,"
            + "`a`.`report_amount_scale`,`a`.`report_pull_lease_owner`,`a`.`report_pull_lease_until`,"
            + "`a`.`report_next_allowed_at`,`a`.`report_last_success_at`,`a`.`report_failure_count` "
            + "FROM `skit_ad_account` `a` "
            + "WHERE `a`.`provider`='TAKU' AND `a`.`deleted`=b'0' "
            + "AND (`a`.`status`=0 OR " + REPORT_ACCOUNT_PENDING_DRAIN + ") "
            + "AND " + REPORT_ROUTE_TENANT_ELIGIBILITY + " "
            + "AND EXISTS (SELECT 1 FROM `skit_ad_reporting_credential_version` `c` "
            + "WHERE `c`.`tenant_id`=`a`.`tenant_id` AND `c`.`ad_account_id`=`a`.`id` "
            + "AND `c`.`active`=b'1' AND `c`.`revoked_at` IS NULL AND `c`.`deleted`=b'0') "
            + "AND (`a`.`report_next_allowed_at` IS NULL "
            + "OR `a`.`report_next_allowed_at`<=CURRENT_TIMESTAMP) "
            + "AND (`a`.`report_pull_lease_until` IS NULL "
            + "OR `a`.`report_pull_lease_until`<CURRENT_TIMESTAMP) "
            + "ORDER BY COALESCE(`a`.`report_next_allowed_at`,'1970-01-01'),"
            + "`a`.`tenant_id`,`a`.`id` LIMIT #{limit}")
    List<SkitAdAccountDO> selectDueReportRoutes(@Param("limit") int limit);

    @Update("UPDATE `skit_ad_account` `a` SET `report_pull_lease_owner`=#{leaseOwner},"
            + "`report_pull_lease_until`=DATE_ADD(CURRENT_TIMESTAMP,INTERVAL #{leaseSeconds} SECOND),"
            + "`updater`='report-pull-lease',`update_time`=CURRENT_TIMESTAMP "
            + "WHERE `a`.`tenant_id`=#{tenantId} AND `a`.`id`=#{id} AND `a`.`provider`='TAKU' "
            + "AND `a`.`deleted`=b'0' AND (`a`.`status`=0 OR "
            + REPORT_ACCOUNT_PENDING_DRAIN + ") "
            + "AND " + REPORT_ROUTE_TENANT_ELIGIBILITY + " "
            + "AND (`a`.`report_next_allowed_at` IS NULL "
            + "OR `a`.`report_next_allowed_at`<=CURRENT_TIMESTAMP) "
            + "AND (`a`.`report_pull_lease_until` IS NULL "
            + "OR `a`.`report_pull_lease_until`<CURRENT_TIMESTAMP)")
    int claimReportPullLeaseCas(@Param("tenantId") long tenantId, @Param("id") long id,
                                @Param("leaseOwner") String leaseOwner,
                                @Param("leaseSeconds") int leaseSeconds);

    @Select("SELECT `id`,`tenant_id`,`provider`,`account_id`,`app_id`,`config_data`,`status`,"
            + "`report_timezone`,`report_currency`,`report_amount_scale`,`report_pull_lease_owner`,"
            + "`report_pull_lease_until`,`report_next_allowed_at`,`report_last_success_at`,"
            + "`report_failure_count` "
            + "FROM `skit_ad_account` `a` WHERE `a`.`tenant_id`=#{tenantId} AND `a`.`id`=#{id} "
            + "AND `a`.`provider`='TAKU' AND `a`.`deleted`=b'0' "
            + "AND (`a`.`status`=0 OR " + REPORT_ACCOUNT_PENDING_DRAIN + ") FOR UPDATE")
    SkitAdAccountDO selectReportAccountForUpdate(@Param("tenantId") long tenantId,
                                                  @Param("id") long id);

    @Select("SELECT CASE WHEN JSON_VALID(`config_data`) THEN "
            + "JSON_UNQUOTE(JSON_EXTRACT(`config_data`,'$.placementId')) ELSE '' END "
            + "FROM `skit_ad_account` WHERE `tenant_id`=#{tenantId} AND `id`=#{adAccountId} "
            + "AND `provider`='TAKU' AND `status`=0 AND `deleted`=b'0'")
    @InterceptorIgnore(tenantLine = "true")
    String selectEnabledTakuPlacementId(@Param("tenantId") long tenantId,
                                        @Param("adAccountId") long adAccountId);

    @Select("SELECT CASE WHEN JSON_VALID(`config_data`) THEN "
            + "JSON_UNQUOTE(JSON_EXTRACT(`config_data`,'$.placementId')) ELSE '' END "
            + "FROM `skit_ad_account` WHERE `tenant_id`=#{tenantId} AND `id`=#{adAccountId} "
            + "AND `provider`='TAKU' AND `status`=0 AND `deleted`=b'0' FOR UPDATE")
    @InterceptorIgnore(tenantLine = "true")
    String selectEnabledTakuPlacementIdForUpdate(@Param("tenantId") long tenantId,
                                                 @Param("adAccountId") long adAccountId);

    @Update("UPDATE `skit_ad_account` SET `report_pull_lease_owner`=NULL,"
            + "`report_pull_lease_until`=NULL,`report_last_success_at`=CURRENT_TIMESTAMP,"
            + "`report_next_allowed_at`=DATE_ADD(CURRENT_TIMESTAMP,INTERVAL #{delaySeconds} SECOND),"
            + "`report_failure_count`=0,"
            + "`updater`='report-pull-success',`update_time`=CURRENT_TIMESTAMP "
            + "WHERE `tenant_id`=#{tenantId} AND `id`=#{id} "
            + "AND `report_pull_lease_owner`=#{leaseOwner}")
    int completeReportPullLeaseCas(@Param("tenantId") long tenantId, @Param("id") long id,
                                   @Param("leaseOwner") String leaseOwner,
                                   @Param("delaySeconds") int delaySeconds);

    @Update("UPDATE `skit_ad_account` SET `report_pull_lease_owner`=NULL,"
            + "`report_pull_lease_until`=NULL,"
            + "`report_next_allowed_at`=TIMESTAMPADD(SECOND,LEAST(#{maxBackoffSeconds},"
            + "#{baseBackoffSeconds}*CASE LEAST(`report_failure_count`,4) "
            + "WHEN 0 THEN 1 WHEN 1 THEN 2 WHEN 2 THEN 4 WHEN 3 THEN 8 ELSE 16 END),"
            + "CURRENT_TIMESTAMP),`report_failure_count`=LEAST(`report_failure_count`+1,5),"
            + "`updater`='report-pull-failure',`update_time`=CURRENT_TIMESTAMP "
            + "WHERE `tenant_id`=#{tenantId} AND `id`=#{id} "
            + "AND `report_pull_lease_owner`=#{leaseOwner}")
    int failReportPullLeaseCas(@Param("tenantId") long tenantId, @Param("id") long id,
                               @Param("leaseOwner") String leaseOwner,
                               @Param("baseBackoffSeconds") int baseBackoffSeconds,
                               @Param("maxBackoffSeconds") int maxBackoffSeconds);

    default SkitAdAccountDO selectByProvider(String provider) {
        return selectOne(SkitAdAccountDO::getProvider, provider);
    }

    default List<SkitAdAccountDO> selectListByTenantIds(Collection<Long> tenantIds) {
        if (CollUtil.isEmpty(tenantIds)) {
            return Collections.emptyList();
        }
        return selectList(new LambdaQueryWrapperX<SkitAdAccountDO>()
                .in(SkitAdAccountDO::getTenantId, tenantIds)
                .orderByAsc(SkitAdAccountDO::getTenantId)
                .orderByAsc(SkitAdAccountDO::getId));
    }

    /** Clears only the encrypted Pangle secret and disables the provider, preserving public metadata. */
    default int clearPangleCredentials() {
        return update(new SkitAdAccountDO(), new LambdaUpdateWrapper<SkitAdAccountDO>()
                .eq(SkitAdAccountDO::getProvider, "PANGLE")
                .set(SkitAdAccountDO::getSecret, null)
                .set(SkitAdAccountDO::getStatus, CommonStatusEnum.DISABLE.getStatus()));
    }

    /** Clears both encrypted Taku credentials and disables the provider, preserving public metadata. */
    default int clearTakuCredentials() {
        return update(new SkitAdAccountDO(), new LambdaUpdateWrapper<SkitAdAccountDO>()
                .eq(SkitAdAccountDO::getProvider, "TAKU")
                .set(SkitAdAccountDO::getAppKey, null)
                .set(SkitAdAccountDO::getSecret, null)
                .set(SkitAdAccountDO::getStatus, CommonStatusEnum.DISABLE.getStatus()));
    }

}
