package cn.iocoder.yudao.module.skit.dal.mysql;

import cn.iocoder.yudao.module.skit.dal.mysql.reconciliation.SkitAdReconciliationEventLinkMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.reconciliation.SkitAdReportPullMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.revenue.SkitAdRevenueEventMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SkitAdReportHardeningPersistenceContractTest {

    @Test
    void historicalCatchUpIsTenantScopedBoundedAndExcludesFinalAssociationHistory()
            throws Exception {
        Method events = SkitAdRevenueEventMapper.class.getMethod(
                "selectHistoricalPendingEventTimes", long.class, long.class,
                LocalDateTime.class, int.class);
        String eventSql = selectSql(events);
        assertContains(eventSql, "tenant_id", "ad_account_id", "occurred_time",
                "skit_ad_reconciliation_event_link", "final_revision", "limit");

        Method pulls = SkitAdReportPullMapper.class.getMethod(
                "selectPendingFinalReportDates", long.class, long.class, LocalDate.class,
                String.class, String.class, int.class, int.class);
        String pullSql = selectSql(pulls);
        assertContains(pullSql, "tenant_id", "ad_account_id", "report_date",
                "report_timezone", "currency", "amount_scale", "final_window", "limit");
    }

    @Test
    void eventRevisionAssociationWritesAndReadsOnlyTheFullTenantCanonicalEnvelope()
            throws Exception {
        Method insert = SkitAdReconciliationEventLinkMapper.class.getMethod(
                "insert", cn.iocoder.yudao.module.skit.dal.dataobject.reconciliation
                        .SkitAdReconciliationEventLinkDO.class);
        Method select = SkitAdReconciliationEventLinkMapper.class.getMethod(
                "selectCanonicalForUpdate", long.class, long.class, long.class);
        String insertSql = String.join(" ", insert.getAnnotation(Insert.class).value())
                .toLowerCase(Locale.ROOT);
        String selectSql = selectSql(select);
        assertContains(insertSql, "tenant_id", "reconciliation_revision_id", "event_id",
                "policy_snapshot_id", "association_status", "actual_units");
        assertContains(selectSql, "tenant_id", "reconciliation_revision_id", "event_id",
                "for update");
    }

    private static String selectSql(Method method) {
        Select annotation = method.getAnnotation(Select.class);
        assertTrue(annotation != null, method.getName() + " must remain explicit SQL");
        return String.join(" ", annotation.value()).replaceAll("\\s+", " ")
                .toLowerCase(Locale.ROOT);
    }

    private static void assertContains(String source, String... fragments) {
        for (String fragment : fragments) {
            assertTrue(source.contains(fragment), () -> "missing '" + fragment + "'");
        }
    }
}
