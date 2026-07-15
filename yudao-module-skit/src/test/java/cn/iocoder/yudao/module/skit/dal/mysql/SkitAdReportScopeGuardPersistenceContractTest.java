package cn.iocoder.yudao.module.skit.dal.mysql;

import cn.iocoder.yudao.module.skit.dal.mysql.ad.SkitAdAccountMapper;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SkitAdReportScopeGuardPersistenceContractTest {

    @Test
    void reportScopeMutationUsesARowLockAndChecksOnlyTheLatestUnsettledRevisions() throws Exception {
        Method lock = SkitAdAccountMapper.class.getMethod(
                "selectByProviderForUpdate", long.class, String.class);
        String lockSql = sql(lock).toLowerCase(Locale.ROOT);
        assertTrue(lockSql.contains("for update"));
        assertTrue(lockSql.contains("provider"));

        Method pending = SkitAdAccountMapper.class.getMethod(
                "hasUnsettledTakuReportScope", long.class, long.class);
        String pendingSql = sql(pending).toLowerCase(Locale.ROOT);
        assertTrue(pendingSql.contains("skit_ad_revenue_event"));
        assertTrue(pendingSql.contains("skit_ad_reconciliation_revision"));
        assertTrue(pendingSql.contains("reconciliation_revision_id"));
        assertTrue(pendingSql.contains("final_revision"));
        assertTrue(pendingSql.contains("status") && pendingSql.contains("applied"));
        assertTrue(pendingSql.contains("not exists"));
        assertTrue(pendingSql.contains("revision_no"));
        assertTrue(pendingSql.contains("tenant_id") && pendingSql.contains("ad_account_id"));
        assertTrue(pendingSql.contains("skit_ad_report_pull"));
        assertTrue(pendingSql.contains("final_window"));
        assertTrue(pendingSql.contains("report_date"));
        assertTrue(pendingSql.contains("report_timezone"));
        assertTrue(pendingSql.contains("currency") && pendingSql.contains("amount_scale"));
    }

    @Test
    void formalConfigurationTreatsAnyHistoricalEventOrPullAsAnImmutableScopeBoundary()
            throws Exception {
        Method historical = SkitAdAccountMapper.class.getMethod(
                "hasHistoricalTakuReportFacts", long.class, long.class);
        String sql = sql(historical).toLowerCase(Locale.ROOT);
        assertTrue(sql.contains("skit_ad_revenue_event"));
        assertTrue(sql.contains("skit_ad_report_pull"));
        assertTrue(sql.contains("tenant_id") && sql.contains("ad_account_id"));
        assertTrue(sql.contains("provider") && sql.contains("taku"));
    }

    private String sql(Method method) {
        Select select = method.getAnnotation(Select.class);
        assertTrue(select != null, method.getName() + " must remain explicit SQL");
        return String.join(" ", select.value()).replaceAll("\\s+", " ");
    }
}
