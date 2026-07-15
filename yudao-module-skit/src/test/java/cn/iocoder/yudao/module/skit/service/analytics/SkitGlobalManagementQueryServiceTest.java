package cn.iocoder.yudao.module.skit.service.analytics;

import cn.iocoder.yudao.module.skit.controller.admin.tenant.vo.SkitAdAnalyticsOverviewRespVO;
import cn.iocoder.yudao.module.skit.controller.admin.tenant.vo.SkitAdAnalyticsQueryReqVO;
import cn.iocoder.yudao.module.skit.controller.admin.tenant.vo.SkitAdAnalyticsTimeseriesReqVO;
import cn.iocoder.yudao.module.skit.controller.admin.tenant.vo.SkitAdAnalyticsTimeseriesRespVO;
import cn.iocoder.yudao.module.skit.controller.admin.tenant.vo.SkitAdEventPageReqVO;
import cn.iocoder.yudao.module.skit.controller.admin.tenant.vo.SkitAdEventRespVO;
import cn.iocoder.yudao.module.skit.controller.admin.tenant.vo.SkitReconciliationPageReqVO;
import cn.iocoder.yudao.module.skit.controller.admin.tenant.vo.SkitReconciliationRespVO;
import cn.iocoder.yudao.module.skit.controller.admin.tenant.vo.SkitStablePageRespVO;
import cn.iocoder.yudao.module.skit.service.reconciliation.SkitReconciliationQueryServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkitGlobalManagementQueryServiceTest {

    @Test
    void globalAnalyticsUsesOneServerSideAggregateWithoutTenantPredicate() {
        RecordingJdbcTemplate jdbc = new RecordingJdbcTemplate();
        SkitAdAnalyticsServiceImpl service = new SkitAdAnalyticsServiceImpl(jdbc,
                Clock.fixed(Instant.parse("2026-07-15T03:00:00Z"), ZoneOffset.UTC));

        SkitAdAnalyticsOverviewRespVO result = service.getGlobalOverview(
                new SkitAdAnalyticsQueryReqVO());

        assertNull(result.getTenantId());
        assertEquals("UTC+8", result.getTimezone());
        assertNoTenantPredicate(jdbc.calls);
        assertTrue(jdbc.calls.stream().anyMatch(call ->
                call.sql.contains("GROUP BY `x`.`currency`")));
        assertTrue(jdbc.calls.stream().anyMatch(call ->
                call.sql.contains("GROUP BY `e`.`source_currency`,`e`.`amount_scale`")));
    }

    @Test
    void selectedTimezoneConvertsBoundariesButKeepsRawRangeColumnsIndexable() {
        RecordingJdbcTemplate jdbc = new RecordingJdbcTemplate();
        SkitAdAnalyticsServiceImpl service = new SkitAdAnalyticsServiceImpl(jdbc,
                Clock.fixed(Instant.parse("2026-07-15T03:00:00Z"), ZoneOffset.UTC));
        SkitAdAnalyticsTimeseriesReqVO query = new SkitAdAnalyticsTimeseriesReqVO();
        query.setTimezone("UTC-8");
        query.setStartTime(LocalDateTime.of(2026, 7, 13, 0, 0));
        query.setEndTime(LocalDateTime.of(2026, 7, 14, 0, 0));

        SkitAdAnalyticsTimeseriesRespVO result = service.getGlobalTimeseries(query);

        assertEquals("UTC-8", result.getTimezone());
        assertEquals(LocalDateTime.of(2026, 7, 14, 19, 0), result.getAsOf());
        SqlCall session = jdbc.calls.stream().filter(call ->
                call.sql.contains("FROM `skit_ad_session` `s`")).findFirst().orElseThrow(AssertionError::new);
        assertTrue(session.sql.contains("DATE_FORMAT(CONVERT_TZ(`x`.`create_time`,'+08:00','-08:00')"));
        assertTrue(session.sql.contains("`s`.`create_time`>=?"));
        assertFalse(session.sql.contains("CONVERT_TZ(`s`.`create_time`"));
        assertEquals(LocalDateTime.of(2026, 7, 13, 16, 0), session.args[0]);
        assertEquals(LocalDateTime.of(2026, 7, 14, 16, 0), session.args[1]);

        SqlCall revenue = jdbc.calls.stream().filter(call ->
                call.sql.contains("FROM `skit_ad_revenue_event` `e` WHERE"))
                .findFirst().orElseThrow(AssertionError::new);
        assertTrue(revenue.sql.contains("DATE_FORMAT(CONVERT_TZ(`e`.`occurred_time`,'+08:00','-08:00')"));
        assertTrue(revenue.sql.contains("`e`.`occurred_time`>=?"));
        assertEquals(LocalDateTime.of(2026, 7, 13, 16, 0), revenue.args[0]);
        assertEquals(LocalDateTime.of(2026, 7, 14, 16, 0), revenue.args[1]);
    }

    @Test
    void sessionCurrencyPrefersImmutableRevenueSnapshotOverMutableAccountConfig() {
        RecordingJdbcTemplate jdbc = new RecordingJdbcTemplate();
        SkitAdAnalyticsServiceImpl service = new SkitAdAnalyticsServiceImpl(jdbc,
                Clock.fixed(Instant.parse("2026-07-15T03:00:00Z"), ZoneOffset.UTC));
        SkitAdAnalyticsQueryReqVO query = new SkitAdAnalyticsQueryReqVO();
        query.setCurrency("USD");

        service.getGlobalOverview(query);

        SqlCall session = jdbc.calls.stream().filter(call ->
                call.sql.contains("FROM `skit_ad_session` `s`")).findFirst().orElseThrow(AssertionError::new);
        assertTrue(session.sql.contains("LEFT JOIN `skit_ad_revenue_event` `re`"));
        assertTrue(session.sql.contains("CASE WHEN NULLIF(`re`.`source_currency`,'') IS NOT NULL "
                + "THEN `re`.`source_currency`"));
        assertTrue(session.sql.contains("WHEN `s`.`revenue_status` "
                + "IN ('NONE','IMPRESSION_PENDING_REWARD') THEN COALESCE("
                + "NULLIF(`a`.`report_currency`,''),'CNY') ELSE 'CNY' END"));
        assertTrue(session.sql.contains("`re`.`legacy_unverified`=b'0'"));
    }

    @Test
    void suspenseSeparatesPreReportEstimatesFromCanonicalLatestReportBuckets() {
        RecordingJdbcTemplate jdbc = new RecordingJdbcTemplate();
        SkitAdAnalyticsServiceImpl service = new SkitAdAnalyticsServiceImpl(jdbc,
                Clock.fixed(Instant.parse("2026-07-15T03:00:00Z"), ZoneOffset.UTC));

        service.getGlobalOverview(new SkitAdAnalyticsQueryReqVO());

        SqlCall events = jdbc.calls.stream().filter(call ->
                call.sql.contains("FROM `skit_ad_revenue_event` `e` WHERE"))
                .findFirst().orElseThrow(AssertionError::new);
        assertTrue(events.sql.contains("`e`.`reconciliation_status`='FROZEN' "
                + "AND `e`.`reconciliation_bucket_id` IS NULL"));
        assertTrue(events.sql.contains("`e`.`reconciliation_status`='SUSPENSE' "
                + "AND `e`.`reconciliation_bucket_id` IS NULL"));
        assertTrue(events.sql.contains("AS `pre_report_suspense_units`"));

        SqlCall buckets = jdbc.calls.stream().filter(call ->
                call.sql.contains("FROM `skit_ad_reconciliation_bucket` `b`"))
                .findFirst().orElseThrow(AssertionError::new);
        assertTrue(buckets.sql.contains("SUM(`b`.`suspense_units`) AS `report_suspense_units`"));
        assertTrue(buckets.sql.contains("NOT EXISTS (SELECT 1 FROM `skit_ad_reconciliation_revision` `r2`"));
        assertTrue(buckets.sql.contains("`r2`.`revision_no`>`r`.`revision_no`"));
        assertTrue(buckets.sql.contains("`b`.`report_actual_units`"));
        assertTrue(buckets.sql.contains("`b`.`attributable_actual_units`"));
        assertFalse(buckets.sql.contains("estimated_amount_units"));
    }

    @Test
    void eventPageInterpretsWindowInSelectedTimezoneBeforeBindingRawDatabaseRange() {
        RecordingJdbcTemplate jdbc = new RecordingJdbcTemplate();
        SkitAdEventQueryServiceImpl service = new SkitAdEventQueryServiceImpl(jdbc,
                Clock.fixed(Instant.parse("2026-07-15T03:00:00Z"), ZoneOffset.UTC));
        SkitAdEventPageReqVO query = new SkitAdEventPageReqVO();
        query.setTimezone("UTC-8");
        query.setStartTime(LocalDateTime.of(2026, 7, 13, 0, 0));
        query.setEndTime(LocalDateTime.of(2026, 7, 14, 0, 0));

        SkitStablePageRespVO<SkitAdEventRespVO> result = service.getGlobalPage(query);

        assertEquals("UTC-8", result.getTimezone());
        assertEquals(LocalDateTime.of(2026, 7, 14, 19, 0), result.getAsOf());
        SqlCall count = jdbc.calls.stream().filter(call ->
                call.sql.startsWith("SELECT COUNT(*) FROM `skit_ad_revenue_event`"))
                .findFirst().orElseThrow(AssertionError::new);
        assertTrue(count.sql.contains("`e`.`occurred_time`>=?"));
        assertFalse(count.sql.contains("CONVERT_TZ(`e`.`occurred_time`"));
        assertEquals(LocalDateTime.of(2026, 7, 13, 16, 0), count.args[0]);
        assertEquals(LocalDateTime.of(2026, 7, 14, 16, 0), count.args[1]);
    }

    @Test
    void globalEventPageIsGloballyOrderedAndRowsSelectOwningTenant() {
        RecordingJdbcTemplate jdbc = new RecordingJdbcTemplate();
        SkitAdEventQueryServiceImpl service = new SkitAdEventQueryServiceImpl(jdbc);

        SkitStablePageRespVO<SkitAdEventRespVO> result = service.getGlobalPage(
                new SkitAdEventPageReqVO());

        assertNull(result.getTenantId());
        assertNoTenantPredicate(jdbc.calls);
        assertTrue(jdbc.calls.stream().anyMatch(call ->
                call.sql.contains("SELECT `e`.`tenant_id`,`e`.`id`")
                        && call.sql.contains("ORDER BY `e`.`occurred_time` DESC,`e`.`id` DESC")));
    }

    @Test
    void eventCallbackTraceUnionsImpressionAndAuthoritativeRewardInboxTenantSafely() {
        String sql = SkitAdEventQueryServiceImpl.callbackTraceSql();

        assertTrue(sql.contains("`e`.`callback_inbox_id` AS `inbox_id`"));
        assertTrue(sql.contains("`s`.`reward_callback_inbox_id` AS `inbox_id`"));
        assertTrue(sql.contains(" UNION SELECT "));
        assertFalse(sql.contains("UNION ALL"));
        assertTrue(sql.contains("`s`.`tenant_id`=`e`.`tenant_id`"));
        assertTrue(sql.contains("`s`.`ad_account_id`=`e`.`ad_account_id`"));
        assertTrue(sql.contains("`i`.`tenant_id`=`b`.`tenant_id`"));
        assertTrue(sql.contains("`a`.`tenant_id`=`i`.`tenant_id`"));
        assertTrue(sql.contains("COALESCE(`a`.`received_at`,`i`.`received_at`) AS `trace_received_at`"));
        assertTrue(sql.contains("`i`.`authentication_level`"));
        assertTrue(sql.contains("LIMIT 500"));
    }

    @Test
    void globalReconciliationPageIsGloballyOrderedAndRowsSelectOwningTenant() {
        RecordingJdbcTemplate jdbc = new RecordingJdbcTemplate();
        SkitReconciliationQueryServiceImpl service =
                new SkitReconciliationQueryServiceImpl(jdbc);

        SkitStablePageRespVO<SkitReconciliationRespVO> result = service.getGlobalPage(
                new SkitReconciliationPageReqVO());

        assertNull(result.getTenantId());
        assertNoTenantPredicate(jdbc.calls);
        assertTrue(jdbc.calls.stream().anyMatch(call ->
                call.sql.contains("SELECT `b`.`tenant_id`,`b`.`id`")
                        && call.sql.contains("ORDER BY `b`.`report_date` DESC,`b`.`id` DESC")));
    }

    private void assertNoTenantPredicate(List<SqlCall> calls) {
        assertFalse(calls.isEmpty());
        for (SqlCall call : calls) {
            assertFalse(call.sql.contains("`tenant_id`=?"), call.sql);
        }
    }

    private static final class RecordingJdbcTemplate extends JdbcTemplate {

        private final List<SqlCall> calls = new ArrayList<>();

        @Override
        public <T> T queryForObject(String sql, Class<T> requiredType, Object... args) {
            record(sql, args);
            if (Long.class.equals(requiredType)) {
                return requiredType.cast(0L);
            }
            return null;
        }

        @Override
        public <T> List<T> query(String sql, Object[] args, RowMapper<T> rowMapper) {
            record(sql, args);
            return Collections.emptyList();
        }

        @Override
        public List<Map<String, Object>> queryForList(String sql, Object... args) {
            record(sql, args);
            return Collections.emptyList();
        }

        @Override
        public Map<String, Object> queryForMap(String sql, Object... args) {
            record(sql, args);
            return new LinkedHashMap<>();
        }

        @Override
        public <T> List<T> queryForList(String sql, Class<T> elementType, Object... args) {
            record(sql, args);
            return Collections.emptyList();
        }

        private void record(String sql, Object[] args) {
            calls.add(new SqlCall(sql, args == null ? new Object[0] : args.clone()));
        }
    }

    private static final class SqlCall {
        private final String sql;
        private final Object[] args;

        private SqlCall(String sql, Object[] args) {
            this.sql = sql;
            this.args = args;
        }
    }

}
