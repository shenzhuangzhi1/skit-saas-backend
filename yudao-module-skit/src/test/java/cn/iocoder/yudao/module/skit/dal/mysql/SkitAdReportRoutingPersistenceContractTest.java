package cn.iocoder.yudao.module.skit.dal.mysql;

import cn.iocoder.yudao.module.skit.dal.mysql.ad.SkitAdAccountMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.revenue.SkitAdRevenueEventMapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SkitAdReportRoutingPersistenceContractTest {

    @Test
    void archivedTenantRouteIsBoundedToD3AndRequiresUnfinalizedHistoricalEvidence() throws Exception {
        Method select = SkitAdAccountMapper.class.getMethod("selectDueReportRoutes", int.class);
        String sql = String.join(" ", select.getAnnotation(Select.class).value());

        assertContains(sql, "FROM `skit_agent`", "JOIN `system_tenant`",
                "`g`.`archived_time` IS NULL", "INTERVAL 3 DAY",
                "`e`.`occurred_time`<`g`.`archived_time`",
                "`e`.`reconciliation_revision_id` IS NULL OR `r`.`final_revision`=b'0'");
    }

    @Test
    void leaseClaimRepeatsArchiveEligibilityAsACrossNodeCasGuard() throws Exception {
        Method claim = SkitAdAccountMapper.class.getMethod("claimReportPullLeaseCas",
                long.class, long.class, String.class, int.class);
        String sql = String.join(" ", claim.getAnnotation(Update.class).value());

        assertContains(sql, "FROM `skit_agent`", "INTERVAL 3 DAY",
                "`r`.`final_revision`=b'0'", "`report_pull_lease_owner`=#{leaseOwner}");
    }

    @Test
    void disabledAccountStillDrainsAnExistingUnsettledScopeWithoutAcceptingNewTraffic() throws Exception {
        Method select = SkitAdAccountMapper.class.getMethod("selectDueReportRoutes", int.class);
        String selectSql = String.join(" ", select.getAnnotation(Select.class).value());
        Method locked = SkitAdAccountMapper.class.getMethod(
                "selectReportAccountForUpdate", long.class, long.class);
        String lockedSql = String.join(" ", locked.getAnnotation(Select.class).value());

        assertContains(selectSql, "`a`.`status`=0 OR", "`e`.`reconciliation_revision_id` IS NULL",
                "`r`.`final_revision`=b'0'", "NOT EXISTS", "`p`.`final_window`=b'0'",
                "`p2`.`final_window`=b'1'");
        assertContains(lockedSql, "`a`.`status`=0 OR", "`r`.`final_revision`=b'0'",
                "`p`.`final_window`=b'0'", "`p2`.`final_window`=b'1'");
    }

    @Test
    void failureBackoffIsBoundedExponentialAndSuccessResetsTheCounter() throws Exception {
        Method fail = SkitAdAccountMapper.class.getMethod("failReportPullLeaseCas",
                long.class, long.class, String.class, int.class, int.class);
        String failSql = String.join(" ", fail.getAnnotation(Update.class).value());
        Method success = SkitAdAccountMapper.class.getMethod("completeReportPullLeaseCas",
                long.class, long.class, String.class, int.class);
        String successSql = String.join(" ", success.getAnnotation(Update.class).value());

        assertContains(failSql, "LEAST(#{maxBackoffSeconds}",
                "`report_failure_count`=LEAST(`report_failure_count`+1,5)");
        assertContains(successSql, "`report_failure_count`=0");
    }

    @Test
    void suspenseTransitionClearsTheMutableEventProjectionBehindTenantAndVersionCas() throws Exception {
        Method suspense = SkitAdRevenueEventMapper.class.getMethod("markReportSuspenseCas",
                long.class, long.class, long.class, long.class, int.class, long.class, long.class);
        String sql = String.join(" ", suspense.getAnnotation(Update.class).value());

        assertContains(sql, "`reconciled_amount_units`=0", "`reconciled_at`=CURRENT_TIMESTAMP",
                "`reconciliation_status`='SUSPENSE'", "`tenant_id`=#{tenantId}",
                "`ad_account_id`=#{adAccountId}", "`version`=#{expectedVersion}");
    }

    private static void assertContains(String source, String... fragments) {
        for (String fragment : fragments) {
            assertTrue(source.contains(fragment), () -> "missing SQL fragment: " + fragment);
        }
    }
}
