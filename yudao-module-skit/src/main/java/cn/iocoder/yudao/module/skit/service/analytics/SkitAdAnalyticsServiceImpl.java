package cn.iocoder.yudao.module.skit.service.analytics;

import cn.iocoder.yudao.module.skit.controller.admin.tenant.vo.SkitAdAnalyticsOverviewRespVO;
import cn.iocoder.yudao.module.skit.controller.admin.tenant.vo.SkitAdAnalyticsQueryReqVO;
import cn.iocoder.yudao.module.skit.controller.admin.tenant.vo.SkitAdAnalyticsTimeseriesReqVO;
import cn.iocoder.yudao.module.skit.controller.admin.tenant.vo.SkitAdAnalyticsTimeseriesRespVO;
import cn.iocoder.yudao.module.skit.service.management.SkitManagementTimezone;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.skit.enums.ErrorCodeConstants.MANAGEMENT_QUERY_RANGE_INVALID;

@Service
public class SkitAdAnalyticsServiceImpl implements SkitAdAnalyticsService {

    private static final String DEFAULT_CURRENCY = "CNY";
    private static final Duration DEFAULT_RANGE = Duration.ofDays(1);
    private static final Duration MAX_RANGE = Duration.ofDays(90);
    private static final Duration REPORT_FRESHNESS = Duration.ofHours(48);
    private static final DateTimeFormatter SQL_BUCKET = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String REPORT_SUCCESS_STATUS = "SUCCEEDED";

    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    @Autowired
    public SkitAdAnalyticsServiceImpl(JdbcTemplate jdbcTemplate) {
        this(jdbcTemplate, Clock.systemDefaultZone());
    }

    SkitAdAnalyticsServiceImpl(JdbcTemplate jdbcTemplate, Clock clock) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public SkitAdAnalyticsOverviewRespVO getOverview(long tenantId,
                                                      SkitAdAnalyticsQueryReqVO query) {
        return getOverviewInternal(tenantId, query);
    }

    @Override
    public SkitAdAnalyticsOverviewRespVO getGlobalOverview(SkitAdAnalyticsQueryReqVO query) {
        return getOverviewInternal(null, query);
    }

    private SkitAdAnalyticsOverviewRespVO getOverviewInternal(Long tenantId,
                                                               SkitAdAnalyticsQueryReqVO query) {
        QueryWindow window = window(query);
        Map<String, SkitAdAnalyticsOverviewRespVO.CurrencyGroup> groups = new TreeMap<>();
        loadSessionOverview(tenantId, query, window, groups);
        loadRevenueOverview(tenantId, query, window, groups);
        loadReportSuspenseOverview(tenantId, query, window, groups);
        loadCommissionOverview(tenantId, query, window, groups);

        SkitAdAnalyticsOverviewRespVO response = new SkitAdAnalyticsOverviewRespVO();
        response.setTenantId(tenantId);
        response.setAsOf(window.displayAsOf);
        response.setTimezone(window.timezone.getName());
        response.setGroups(new ArrayList<>(groups.values()));
        response.setFreshness(loadFreshness(tenantId, query, window.timezone));
        response.setPlatformHealth(loadHealth(tenantId, query, window));
        return response;
    }

    @Override
    public SkitAdAnalyticsTimeseriesRespVO getTimeseries(long tenantId,
                                                          SkitAdAnalyticsTimeseriesReqVO query) {
        return getTimeseriesInternal(tenantId, query);
    }

    @Override
    public SkitAdAnalyticsTimeseriesRespVO getGlobalTimeseries(
            SkitAdAnalyticsTimeseriesReqVO query) {
        return getTimeseriesInternal(null, query);
    }

    private SkitAdAnalyticsTimeseriesRespVO getTimeseriesInternal(Long tenantId,
                                                                   SkitAdAnalyticsTimeseriesReqVO query) {
        QueryWindow window = window(query);
        String granularity = "DAY".equals(query.getGranularity()) ? "DAY" : "HOUR";
        Map<SeriesKey, SkitAdAnalyticsTimeseriesRespVO.Point> points = new TreeMap<>();
        loadSessionTimeseries(tenantId, query, window, granularity, points);
        loadRevenueTimeseries(tenantId, query, window, granularity, points);
        loadReportSuspenseTimeseries(tenantId, query, window, granularity, points);

        Map<String, SkitAdAnalyticsTimeseriesRespVO.CurrencySeries> groups = new TreeMap<>();
        for (Map.Entry<SeriesKey, SkitAdAnalyticsTimeseriesRespVO.Point> entry : points.entrySet()) {
            SkitAdAnalyticsTimeseriesRespVO.CurrencySeries series = groups.computeIfAbsent(
                    entry.getKey().currency, currency -> {
                        SkitAdAnalyticsTimeseriesRespVO.CurrencySeries created =
                                new SkitAdAnalyticsTimeseriesRespVO.CurrencySeries();
                        created.setCurrency(currency);
                        return created;
                    });
            series.getItems().add(entry.getValue());
        }
        for (SkitAdAnalyticsTimeseriesRespVO.CurrencySeries series : groups.values()) {
            series.getItems().sort(Comparator.comparing(
                    SkitAdAnalyticsTimeseriesRespVO.Point::getBucketStart));
        }

        SkitAdAnalyticsTimeseriesRespVO response = new SkitAdAnalyticsTimeseriesRespVO();
        response.setTenantId(tenantId);
        response.setAsOf(window.displayAsOf);
        response.setTimezone(window.timezone.getName());
        response.setGranularity(granularity);
        response.setGroups(new ArrayList<>(groups.values()));
        return response;
    }

    private void loadSessionOverview(Long tenantId, SkitAdAnalyticsQueryReqVO query,
                                     QueryWindow window,
                                     Map<String, SkitAdAnalyticsOverviewRespVO.CurrencyGroup> groups) {
        Sql sql = sessionAggregationSql(tenantId, query, window, null);
        for (Map<String, Object> row : jdbcTemplate.queryForList(sql.text, sql.args.toArray())) {
            SkitAdAnalyticsOverviewRespVO.CurrencyGroup group = group(groups, currency(row));
            group.setRequestCount(longValue(row, "request_count"));
            group.setDisplayCount(longValue(row, "display_count"));
            group.setClientRewardCount(longValue(row, "client_reward_count"));
            group.setVerifiedRewardCount(longValue(row, "verified_reward_count"));
            group.setSkipCount(longValue(row, "skip_count"));
            group.setFailureCount(longValue(row, "failure_count"));
            group.setUniqueMemberCount(longValue(row, "unique_member_count"));
        }
    }

    private void loadRevenueOverview(Long tenantId, SkitAdAnalyticsQueryReqVO query,
                                     QueryWindow window,
                                     Map<String, SkitAdAnalyticsOverviewRespVO.CurrencyGroup> groups) {
        Sql sql = revenueAggregationSql(tenantId, query, window, null);
        Map<String, RevenueTotals> totals = new LinkedHashMap<>();
        for (Map<String, Object> row : jdbcTemplate.queryForList(sql.text, sql.args.toArray())) {
            String currency = currency(row);
            int scale = intValue(row, "amount_scale");
            RevenueTotals total = totals.computeIfAbsent(currency, ignored -> new RevenueTotals());
            total.frozen = total.frozen.add(decimal(row, "frozen_units", scale));
            total.reconciled = total.reconciled.add(decimal(row, "reconciled_units", scale));
            total.preReportSuspense = total.preReportSuspense.add(
                    decimal(row, "pre_report_suspense_units", scale));
        }
        for (Map.Entry<String, RevenueTotals> entry : totals.entrySet()) {
            SkitAdAnalyticsOverviewRespVO.CurrencyGroup group = group(groups, entry.getKey());
            group.setFrozenRevenue(money(entry.getValue().frozen));
            group.setReconciledRevenue(money(entry.getValue().reconciled));
            group.setPreReportSuspenseRevenue(money(entry.getValue().preReportSuspense));
            group.setSuspenseRevenue(money(entry.getValue().preReportSuspense));
        }
    }

    private void loadReportSuspenseOverview(
            Long tenantId, SkitAdAnalyticsQueryReqVO query, QueryWindow window,
            Map<String, SkitAdAnalyticsOverviewRespVO.CurrencyGroup> groups) {
        Sql sql = reconciliationBucketAggregationSql(tenantId, query, window, null);
        for (Map<String, Object> row : jdbcTemplate.queryForList(sql.text, sql.args.toArray())) {
            requireConserved(longValue(row, "report_actual_units"),
                    longValue(row, "attributable_actual_units"),
                    longValue(row, "report_suspense_units"));
            SkitAdAnalyticsOverviewRespVO.CurrencyGroup group = group(groups, currency(row));
            BigDecimal reportSuspense = decimal(
                    row, "report_suspense_units", intValue(row, "amount_scale"));
            group.setReportSuspenseRevenue(addMoney(
                    group.getReportSuspenseRevenue(), reportSuspense));
            group.setSuspenseRevenue(addMoney(group.getSuspenseRevenue(), reportSuspense));
        }
    }

    private void loadCommissionOverview(Long tenantId, SkitAdAnalyticsQueryReqVO query,
                                        QueryWindow window,
                                        Map<String, SkitAdAnalyticsOverviewRespVO.CurrencyGroup> groups) {
        StringBuilder text = new StringBuilder("SELECT `l`.`currency`, `l`.`amount_scale`,"
                + "`l`.`beneficiary_type`,`l`.`level_no`,SUM(`l`.`amount_units`) AS `amount_units` "
                + "FROM `skit_commission_ledger` `l` JOIN `skit_ad_revenue_event` `e` "
                + "ON `e`.`tenant_id`=`l`.`tenant_id` AND `e`.`id`=`l`.`event_id` "
                + "WHERE " + tenantPredicate("l", tenantId)
                + "`e`.`occurred_time`>=? AND `e`.`occurred_time`<? "
                + "AND `l`.`legacy_unverified`=b'0' AND `l`.`deleted`=b'0' AND `e`.`deleted`=b'0'");
        List<Object> args = baseArgs(tenantId, window);
        appendAccountAndCurrency(text, args, query, "e", "l.currency");
        text.append(" GROUP BY `l`.`currency`,`l`.`amount_scale`,`l`.`beneficiary_type`,`l`.`level_no`");

        Map<String, BigDecimal> retained = new LinkedHashMap<>();
        Map<String, Map<Integer, BigDecimal>> levelShares = new LinkedHashMap<>();
        for (Map<String, Object> row : jdbcTemplate.queryForList(text.toString(), args.toArray())) {
            String currency = currency(row);
            BigDecimal amount = decimal(row, "amount_units", intValue(row, "amount_scale"));
            int beneficiaryType = intValue(row, "beneficiary_type");
            if (beneficiaryType == 2) {
                retained.merge(currency, amount, BigDecimal::add);
            } else if (beneficiaryType == 1) {
                int levelNo = intValue(row, "level_no");
                levelShares.computeIfAbsent(currency, ignored -> new TreeMap<>())
                        .merge(levelNo, amount, BigDecimal::add);
            }
        }
        for (String currency : unionKeys(retained, levelShares)) {
            SkitAdAnalyticsOverviewRespVO.CurrencyGroup group = group(groups, currency);
            group.setAgentRetainedRevenue(money(retained.getOrDefault(currency, BigDecimal.ZERO)));
            List<SkitAdAnalyticsOverviewRespVO.LevelShare> shares = new ArrayList<>();
            for (Map.Entry<Integer, BigDecimal> level : levelShares
                    .getOrDefault(currency, new TreeMap<>()).entrySet()) {
                SkitAdAnalyticsOverviewRespVO.LevelShare share =
                        new SkitAdAnalyticsOverviewRespVO.LevelShare();
                share.setLevelNo(level.getKey());
                share.setAmount(money(level.getValue()));
                shares.add(share);
            }
            group.setLevelShares(shares);
        }
    }

    private void loadSessionTimeseries(Long tenantId, SkitAdAnalyticsTimeseriesReqVO query,
                                       QueryWindow window, String granularity,
                                       Map<SeriesKey, SkitAdAnalyticsTimeseriesRespVO.Point> points) {
        Sql sql = sessionAggregationSql(tenantId, query, window, granularity);
        for (Map<String, Object> row : jdbcTemplate.queryForList(sql.text, sql.args.toArray())) {
            SeriesKey key = new SeriesKey(currency(row), bucket(row));
            SkitAdAnalyticsTimeseriesRespVO.Point point = point(points, key);
            point.setRequestCount(longValue(row, "request_count"));
            point.setDisplayCount(longValue(row, "display_count"));
            point.setClientRewardCount(longValue(row, "client_reward_count"));
            point.setVerifiedRewardCount(longValue(row, "verified_reward_count"));
            point.setSkipCount(longValue(row, "skip_count"));
            point.setFailureCount(longValue(row, "failure_count"));
            point.setUniqueMemberCount(longValue(row, "unique_member_count"));
        }
    }

    private void loadRevenueTimeseries(Long tenantId, SkitAdAnalyticsTimeseriesReqVO query,
                                       QueryWindow window, String granularity,
                                       Map<SeriesKey, SkitAdAnalyticsTimeseriesRespVO.Point> points) {
        Sql sql = revenueAggregationSql(tenantId, query, window, granularity);
        for (Map<String, Object> row : jdbcTemplate.queryForList(sql.text, sql.args.toArray())) {
            SeriesKey key = new SeriesKey(currency(row), bucket(row));
            SkitAdAnalyticsTimeseriesRespVO.Point point = point(points, key);
            int scale = intValue(row, "amount_scale");
            point.setFrozenRevenue(addMoney(point.getFrozenRevenue(), decimal(row, "frozen_units", scale)));
            point.setReconciledRevenue(addMoney(point.getReconciledRevenue(),
                    decimal(row, "reconciled_units", scale)));
            BigDecimal preReport = decimal(row, "pre_report_suspense_units", scale);
            point.setPreReportSuspenseRevenue(addMoney(
                    point.getPreReportSuspenseRevenue(), preReport));
            point.setSuspenseRevenue(addMoney(point.getSuspenseRevenue(), preReport));
        }
    }

    private void loadReportSuspenseTimeseries(
            Long tenantId, SkitAdAnalyticsTimeseriesReqVO query, QueryWindow window,
            String granularity,
            Map<SeriesKey, SkitAdAnalyticsTimeseriesRespVO.Point> points) {
        Sql sql = reconciliationBucketAggregationSql(tenantId, query, window, granularity);
        for (Map<String, Object> row : jdbcTemplate.queryForList(sql.text, sql.args.toArray())) {
            requireConserved(longValue(row, "report_actual_units"),
                    longValue(row, "attributable_actual_units"),
                    longValue(row, "report_suspense_units"));
            SeriesKey key = new SeriesKey(currency(row), bucket(row));
            SkitAdAnalyticsTimeseriesRespVO.Point point = point(points, key);
            BigDecimal reportSuspense = decimal(
                    row, "report_suspense_units", intValue(row, "amount_scale"));
            point.setReportSuspenseRevenue(addMoney(
                    point.getReportSuspenseRevenue(), reportSuspense));
            point.setSuspenseRevenue(addMoney(point.getSuspenseRevenue(), reportSuspense));
        }
    }

    private Sql sessionAggregationSql(Long tenantId, SkitAdAnalyticsQueryReqVO query,
                                      QueryWindow window, String granularity) {
        String bucketSelect = granularity == null ? "" : "," + bucketExpression(
                "x.create_time", granularity, window.timezone)
                + " AS `bucket_start`";
        String bucketGroup = granularity == null ? "" : ",`bucket_start`";
        String sessionCurrency = sessionCurrencyExpression();
        StringBuilder text = new StringBuilder("SELECT `x`.`currency`" + bucketSelect
                + ",COUNT(*) AS `request_count`,SUM(`x`.`shown`) AS `display_count`,"
                + "SUM(`x`.`client_reward`) AS `client_reward_count`,SUM(`x`.`verified`) "
                + "AS `verified_reward_count`,SUM(CASE WHEN `x`.`closed`=1 "
                + "AND `x`.`client_reward`=0 AND `x`.`verified`=0 THEN 1 ELSE 0 END) AS `skip_count`,"
                + "SUM(`x`.`failed`) AS `failure_count`,COUNT(DISTINCT `x`.`member_id`) "
                + "AS `unique_member_count` FROM (SELECT `s`.`id`,`s`.`member_id`,`s`.`create_time`,"
                + sessionCurrency + " AS `currency`,"
                + "MAX(CASE WHEN `c`.`event_type`='SHOWN' THEN 1 ELSE 0 END) AS `shown`,"
                + "MAX(CASE WHEN `c`.`event_type`='REWARD_OBSERVED' THEN 1 ELSE 0 END) AS `client_reward`,"
                + "CASE WHEN `s`.`reward_verification_status`='SIGNED_VERIFIED' THEN 1 ELSE 0 END AS `verified`,"
                + "MAX(CASE WHEN `c`.`event_type`='CLOSED' THEN 1 ELSE 0 END) AS `closed`,"
                + "CASE WHEN `s`.`client_lifecycle_status` IN ('FAILED','LOAD_EXPIRED') "
                + "OR MAX(CASE WHEN `c`.`event_type`='FAILED' THEN 1 ELSE 0 END)=1 THEN 1 ELSE 0 END AS `failed` "
                + "FROM `skit_ad_session` `s` LEFT JOIN `skit_ad_account` `a` "
                + "ON `a`.`tenant_id`=`s`.`tenant_id` AND `a`.`id`=`s`.`ad_account_id` "
                + "AND `a`.`deleted`=b'0' LEFT JOIN `skit_ad_revenue_event` `re` "
                + "ON `re`.`tenant_id`=`s`.`tenant_id` AND `re`.`ad_session_id`=`s`.`id` "
                + "AND `re`.`id`=(SELECT `re2`.`id` FROM `skit_ad_revenue_event` `re2` "
                + "WHERE `re2`.`tenant_id`=`s`.`tenant_id` AND `re2`.`ad_session_id`=`s`.`id` "
                + "AND `re2`.`legacy_unverified`=b'0' AND `re2`.`deleted`=b'0' "
                + "ORDER BY `re2`.`id` LIMIT 1) AND `re`.`legacy_unverified`=b'0' "
                + "AND `re`.`deleted`=b'0' LEFT JOIN `skit_ad_client_event` `c` "
                + "ON `c`.`tenant_id`=`s`.`tenant_id` AND `c`.`ad_session_id`=`s`.`id` "
                + "AND `c`.`deleted`=b'0' WHERE " + tenantPredicate("s", tenantId)
                + "`s`.`create_time`>=? "
                + "AND `s`.`create_time`<? AND `s`.`deleted`=b'0'");
        List<Object> args = baseArgs(tenantId, window);
        appendAccountAndCurrency(text, args, query, "s", sessionCurrency);
        text.append(" GROUP BY `s`.`id`,`s`.`member_id`,`s`.`create_time`,`a`.`report_currency`,"
                + "`re`.`source_currency`,`s`.`revenue_status`,"
                + "`s`.`reward_verification_status`,`s`.`client_lifecycle_status`) `x` "
                + "GROUP BY `x`.`currency`" + bucketGroup + " ORDER BY `x`.`currency`" + bucketGroup);
        return new Sql(text.toString(), args);
    }

    private Sql revenueAggregationSql(Long tenantId, SkitAdAnalyticsQueryReqVO query,
                                      QueryWindow window, String granularity) {
        String bucket = granularity == null ? "" : "," + bucketExpression(
                "e.occurred_time", granularity, window.timezone)
                + " AS `bucket_start`";
        String bucketGroup = granularity == null ? "" : ",`bucket_start`";
        StringBuilder text = new StringBuilder("SELECT `e`.`source_currency` AS `currency`,"
                + "`e`.`amount_scale`" + bucket
                + ",SUM(CASE WHEN `e`.`reconciliation_status`='FROZEN' "
                + "AND `e`.`reconciliation_bucket_id` IS NULL "
                + "THEN `e`.`estimated_amount_units` ELSE 0 END) AS `frozen_units`,"
                + "SUM(CASE WHEN `e`.`reconciliation_status`='RECONCILED' "
                + "THEN `e`.`reconciled_amount_units` ELSE 0 END) AS `reconciled_units`,"
                + "SUM(CASE WHEN `e`.`reconciliation_status`='SUSPENSE' "
                + "AND `e`.`reconciliation_bucket_id` IS NULL "
                + "THEN `e`.`estimated_amount_units` ELSE 0 END) "
                + "AS `pre_report_suspense_units` "
                + "FROM `skit_ad_revenue_event` `e` WHERE " + tenantPredicate("e", tenantId)
                + "`e`.`occurred_time`>=? AND `e`.`occurred_time`<? "
                + "AND `e`.`legacy_unverified`=b'0' AND `e`.`deleted`=b'0'");
        List<Object> args = baseArgs(tenantId, window);
        appendAccountAndCurrency(text, args, query, "e", "e.source_currency");
        text.append(" GROUP BY `e`.`source_currency`,`e`.`amount_scale`" + bucketGroup
                + " ORDER BY `e`.`source_currency`" + bucketGroup);
        return new Sql(text.toString(), args);
    }

    private Sql reconciliationBucketAggregationSql(
            Long tenantId, SkitAdAnalyticsQueryReqVO query,
            QueryWindow window, String granularity) {
        String bucket = granularity == null ? ""
                : ",CAST(CONCAT(`b`.`report_date`,' 00:00:00') AS DATETIME) AS `bucket_start`";
        String bucketGroup = granularity == null ? "" : ",`bucket_start`";
        StringBuilder text = new StringBuilder("SELECT `b`.`currency`,`b`.`amount_scale`" + bucket
                + ",SUM(`b`.`report_actual_units`) AS `report_actual_units`,"
                + "SUM(`b`.`attributable_actual_units`) AS `attributable_actual_units`,"
                + "SUM(`b`.`suspense_units`) AS `report_suspense_units` "
                + "FROM `skit_ad_reconciliation_bucket` `b` "
                + "JOIN `skit_ad_reconciliation_revision` `r` "
                + "ON `r`.`tenant_id`=`b`.`tenant_id` "
                + "AND `r`.`reconciliation_bucket_id`=`b`.`id` "
                + "AND `r`.`status` IN ('APPLIED','PARTIAL','SUSPENSE') "
                + "AND `r`.`deleted`=b'0' WHERE " + tenantPredicate("b", tenantId)
                + "`b`.`report_date`>=? AND `b`.`report_date`<? "
                + "AND `b`.`deleted`=b'0' AND NOT EXISTS (SELECT 1 "
                + "FROM `skit_ad_reconciliation_revision` `r2` WHERE "
                + "`r2`.`tenant_id`=`r`.`tenant_id` "
                + "AND `r2`.`reconciliation_bucket_id`=`r`.`reconciliation_bucket_id` "
                + "AND `r2`.`status` IN ('APPLIED','PARTIAL','SUSPENSE') "
                + "AND `r2`.`deleted`=b'0' AND (`r2`.`revision_no`>`r`.`revision_no` "
                + "OR (`r2`.`revision_no`=`r`.`revision_no` AND `r2`.`id`>`r`.`id`)))");
        List<Object> args = new ArrayList<>();
        if (tenantId != null) args.add(tenantId);
        args.add(window.displayStart.toLocalDate());
        args.add(window.displayEnd.toLocalDate().plusDays(
                window.displayEnd.toLocalTime().equals(java.time.LocalTime.MIDNIGHT) ? 0 : 1));
        appendAccountAndCurrency(text, args, query, "b", "b.currency");
        text.append(" GROUP BY `b`.`currency`,`b`.`amount_scale`").append(bucketGroup)
                .append(" ORDER BY `b`.`currency`").append(bucketGroup);
        return new Sql(text.toString(), args);
    }

    private SkitAdAnalyticsOverviewRespVO.Freshness loadFreshness(
            Long tenantId, SkitAdAnalyticsQueryReqVO query, SkitManagementTimezone timezone) {
        String accountSession = query.getAdAccountId() == null ? "" : " AND `ad_account_id`=?";
        List<Object> args = new ArrayList<>();
        if (tenantId != null) args.add(tenantId);
        if (query.getAdAccountId() != null) args.add(query.getAdAccountId());
        Map<String, Object> row = jdbcTemplate.queryForMap("SELECT "
                + "(SELECT MAX(`create_time`) FROM `skit_ad_session` WHERE "
                + tenantPredicate(null, tenantId)
                + "`deleted`=b'0'" + accountSession + ") AS `last_session_at`,"
                + "(SELECT MAX(`received_at`) FROM `skit_ad_callback_inbox` WHERE "
                + tenantPredicate(null, tenantId)
                + "`callback_type`='REWARD' AND " + canonicalRewardCallbackSuccessPredicate()
                + " AND `deleted`=b'0'"
                + accountSession + ") AS `last_signed_reward_at`,"
                + "(SELECT MAX(`occurred_time`) FROM `skit_ad_revenue_event` WHERE "
                + tenantPredicate(null, tenantId)
                + "`source_type`='TAKU_IMPRESSION' AND `legacy_unverified`=b'0' AND `deleted`=b'0'"
                + accountSession + ") AS `last_impression_at`,"
                + "(SELECT MAX(`pulled_at`) FROM `skit_ad_report_pull` WHERE "
                + tenantPredicate(null, tenantId)
                + "`status`='" + REPORT_SUCCESS_STATUS + "' AND `deleted`=b'0'" + accountSession
                + ") AS `last_report_success_at`", repeatedArgs(args, 4).toArray());
        SkitAdAnalyticsOverviewRespVO.Freshness freshness =
                new SkitAdAnalyticsOverviewRespVO.Freshness();
        freshness.setLastSessionAt(timezone.fromDatabase(dateTime(row.get("last_session_at"))));
        freshness.setLastSignedRewardAt(timezone.fromDatabase(
                dateTime(row.get("last_signed_reward_at"))));
        freshness.setLastImpressionAt(timezone.fromDatabase(dateTime(row.get("last_impression_at"))));
        freshness.setLastReportSuccessAt(timezone.fromDatabase(
                dateTime(row.get("last_report_success_at"))));
        return freshness;
    }

    private SkitAdAnalyticsOverviewRespVO.PlatformHealth loadHealth(Long tenantId,
                                                                    SkitAdAnalyticsQueryReqVO query,
                                                                    QueryWindow window) {
        StringBuilder callbackSql = new StringBuilder("SELECT COUNT(*) AS `total`,"
                + "SUM(CASE WHEN " + canonicalRewardCallbackSuccessPredicate() + " "
                + "THEN 1 ELSE 0 END) AS `success`,SUM(CASE WHEN `processing_status` "
                + "IN ('DEAD_LETTER','REJECTED') THEN 1 ELSE 0 END) AS `alerts` "
                + "FROM `skit_ad_callback_inbox` WHERE " + tenantPredicate(null, tenantId)
                + "`received_at`>=? "
                + "AND `received_at`<? AND `callback_type`='REWARD' AND `deleted`=b'0'");
        List<Object> args = baseArgs(tenantId, window);
        if (query.getAdAccountId() != null) {
            callbackSql.append(" AND `ad_account_id`=?");
            args.add(query.getAdAccountId());
        }
        Map<String, Object> callback = jdbcTemplate.queryForMap(callbackSql.toString(), args.toArray());
        long total = longValue(callback, "total");
        long success = longValue(callback, "success");
        long alerts = longValue(callback, "alerts");
        BigDecimal rate = total == 0 ? BigDecimal.ZERO : BigDecimal.valueOf(success)
                .divide(BigDecimal.valueOf(total), 4, RoundingMode.HALF_UP);

        StringBuilder reportSql = new StringBuilder("SELECT COALESCE(SUM(`report_failure_count`),0) "
                + "AS `failure_count`,MAX(`report_last_success_at`) AS `last_success_at` "
                + "FROM `skit_ad_account` WHERE " + tenantPredicate(null, tenantId)
                + "`deleted`=b'0'");
        List<Object> reportArgs = new ArrayList<>();
        if (tenantId != null) reportArgs.add(tenantId);
        if (query.getAdAccountId() != null) {
            reportSql.append(" AND `id`=?");
            reportArgs.add(query.getAdAccountId());
        }
        Map<String, Object> report = jdbcTemplate.queryForMap(reportSql.toString(), reportArgs.toArray());
        long reportFailures = longValue(report, "failure_count");
        LocalDateTime lastReportSuccess = dateTime(report.get("last_success_at"));
        String reportStatus;
        if (reportFailures > 0) {
            reportStatus = "FAILED";
        } else if (lastReportSuccess == null) {
            reportStatus = "NO_DATA";
        } else if (lastReportSuccess.isBefore(window.databaseAsOf.minus(REPORT_FRESHNESS))) {
            reportStatus = "STALE";
        } else {
            reportStatus = REPORT_SUCCESS_STATUS;
        }

        SkitAdAnalyticsOverviewRespVO.PlatformHealth health =
                new SkitAdAnalyticsOverviewRespVO.PlatformHealth();
        health.setCallbackSuccessRate(rate.toPlainString());
        health.setReportStatus(reportStatus);
        health.setOpenAlertCount(alerts + reportFailures);
        if (total == 0 && "NO_DATA".equals(reportStatus)) {
            health.setStatus("NO_DATA");
        } else if (alerts > 0 || "FAILED".equals(reportStatus) || rate.compareTo(new BigDecimal("0.95")) < 0) {
            health.setStatus("CRITICAL");
        } else if (rate.compareTo(new BigDecimal("0.98")) < 0
                || !REPORT_SUCCESS_STATUS.equals(reportStatus)) {
            health.setStatus("DEGRADED");
        } else {
            health.setStatus("HEALTHY");
        }
        return health;
    }

    static String canonicalRewardCallbackSuccessPredicate() {
        return "`processing_status`='SUCCEEDED' AND `authentication_level`='SIGNED_REWARD' "
                + "AND `signature_status`='VALID'";
    }

    static String reportSuccessStatus() {
        return REPORT_SUCCESS_STATUS;
    }

    private QueryWindow window(SkitAdAnalyticsQueryReqVO query) {
        SkitManagementTimezone timezone = SkitManagementTimezone.of(query.getTimezone());
        LocalDateTime displayAsOf = timezone.now(clock).withNano(0);
        LocalDateTime displayEnd = query.getEndTime() == null
                || query.getEndTime().isAfter(displayAsOf) ? displayAsOf : query.getEndTime();
        LocalDateTime displayStart = query.getStartTime() == null
                ? displayEnd.minus(DEFAULT_RANGE) : query.getStartTime();
        if (!displayStart.isBefore(displayEnd)
                || Duration.between(displayStart, displayEnd).compareTo(MAX_RANGE) > 0) {
            throw exception(MANAGEMENT_QUERY_RANGE_INVALID);
        }
        return new QueryWindow(timezone.toDatabase(displayStart), timezone.toDatabase(displayEnd),
                timezone.toDatabase(displayAsOf), displayStart, displayEnd, displayAsOf, timezone);
    }

    private void appendAccountAndCurrency(StringBuilder sql, List<Object> args,
                                          SkitAdAnalyticsQueryReqVO query,
                                          String accountAlias, String currencyExpression) {
        if (query.getAdAccountId() != null) {
            sql.append(" AND `").append(accountAlias).append("`.`ad_account_id`=?");
            args.add(query.getAdAccountId());
        }
        if (query.getCurrency() != null) {
            sql.append(" AND ").append(currencyExpression).append("=?");
            args.add(query.getCurrency());
        }
    }

    private String bucketExpression(String column, String granularity,
                                    SkitManagementTimezone timezone) {
        String quotedColumn = "`" + column.replace(".", "`.`") + "`";
        return "DATE_FORMAT(" + timezone.sqlProjection(quotedColumn) + ",'"
                + ("DAY".equals(granularity) ? "%Y-%m-%d 00:00:00" : "%Y-%m-%d %H:00:00") + "')";
    }

    private List<Object> baseArgs(Long tenantId, QueryWindow window) {
        List<Object> args = new ArrayList<>();
        if (tenantId != null) args.add(tenantId);
        args.add(window.databaseStart);
        args.add(window.databaseEnd);
        return args;
    }

    private String sessionCurrencyExpression() {
        return "UPPER(CASE WHEN NULLIF(`re`.`source_currency`,'') IS NOT NULL "
                + "THEN `re`.`source_currency` WHEN `s`.`revenue_status` "
                + "IN ('NONE','IMPRESSION_PENDING_REWARD') THEN COALESCE("
                + "NULLIF(`a`.`report_currency`,''),'" + DEFAULT_CURRENCY + "') "
                + "ELSE '" + DEFAULT_CURRENCY + "' END)";
    }

    private String tenantPredicate(String alias, Long tenantId) {
        if (tenantId == null) return "";
        String qualifier = alias == null ? "" : "`" + alias + "`.";
        return qualifier + "`tenant_id`=? AND ";
    }

    private List<Object> repeatedArgs(List<Object> values, int repetitions) {
        List<Object> result = new ArrayList<>(values.size() * repetitions);
        for (int index = 0; index < repetitions; index++) result.addAll(values);
        return result;
    }

    private SkitAdAnalyticsOverviewRespVO.CurrencyGroup group(
            Map<String, SkitAdAnalyticsOverviewRespVO.CurrencyGroup> groups, String currency) {
        return groups.computeIfAbsent(currency, ignored -> {
            SkitAdAnalyticsOverviewRespVO.CurrencyGroup group =
                    new SkitAdAnalyticsOverviewRespVO.CurrencyGroup();
            group.setCurrency(currency);
            group.setRequestCount(0L);
            group.setDisplayCount(0L);
            group.setClientRewardCount(0L);
            group.setVerifiedRewardCount(0L);
            group.setSkipCount(0L);
            group.setFailureCount(0L);
            group.setUniqueMemberCount(0L);
            group.setFrozenRevenue("0");
            group.setReconciledRevenue("0");
            group.setPreReportSuspenseRevenue("0");
            group.setReportSuspenseRevenue("0");
            group.setSuspenseRevenue("0");
            group.setAgentRetainedRevenue("0");
            return group;
        });
    }

    private SkitAdAnalyticsTimeseriesRespVO.Point point(
            Map<SeriesKey, SkitAdAnalyticsTimeseriesRespVO.Point> points, SeriesKey key) {
        return points.computeIfAbsent(key, ignored -> {
            SkitAdAnalyticsTimeseriesRespVO.Point point = new SkitAdAnalyticsTimeseriesRespVO.Point();
            point.setBucketStart(key.bucketStart);
            point.setRequestCount(0L);
            point.setDisplayCount(0L);
            point.setClientRewardCount(0L);
            point.setVerifiedRewardCount(0L);
            point.setSkipCount(0L);
            point.setFailureCount(0L);
            point.setUniqueMemberCount(0L);
            point.setFrozenRevenue("0");
            point.setReconciledRevenue("0");
            point.setPreReportSuspenseRevenue("0");
            point.setReportSuspenseRevenue("0");
            point.setSuspenseRevenue("0");
            return point;
        });
    }

    private String currency(Map<String, Object> row) {
        Object value = row.get("currency");
        return value == null ? DEFAULT_CURRENCY : value.toString().toUpperCase(Locale.ROOT);
    }

    private LocalDateTime bucket(Map<String, Object> row) {
        Object value = row.get("bucket_start");
        if (value instanceof Timestamp) return ((Timestamp) value).toLocalDateTime();
        return LocalDateTime.parse(String.valueOf(value), SQL_BUCKET);
    }

    private long longValue(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value instanceof Number ? ((Number) value).longValue() : 0L;
    }

    private int intValue(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value instanceof Number ? ((Number) value).intValue() : 0;
    }

    private BigDecimal decimal(Map<String, Object> row, String key, int scale) {
        Object value = row.get(key);
        if (value == null) return BigDecimal.ZERO;
        long units = value instanceof Number ? ((Number) value).longValue() : Long.parseLong(value.toString());
        return BigDecimal.valueOf(units, scale);
    }

    private String addMoney(String existing, BigDecimal addition) {
        return money(new BigDecimal(existing == null ? "0" : existing).add(addition));
    }

    private String money(BigDecimal value) {
        BigDecimal normalized = value.stripTrailingZeros();
        return normalized.scale() < 0 ? normalized.setScale(0).toPlainString() : normalized.toPlainString();
    }

    private LocalDateTime dateTime(Object value) {
        if (value instanceof Timestamp) return ((Timestamp) value).toLocalDateTime();
        return value instanceof LocalDateTime ? (LocalDateTime) value : null;
    }

    private List<String> unionKeys(Map<String, ?> first, Map<String, ?> second) {
        Map<String, Boolean> union = new TreeMap<>();
        first.keySet().forEach(key -> union.put(key, true));
        second.keySet().forEach(key -> union.put(key, true));
        return new ArrayList<>(union.keySet());
    }

    private static final class QueryWindow {
        private final LocalDateTime databaseStart;
        private final LocalDateTime databaseEnd;
        private final LocalDateTime databaseAsOf;
        private final LocalDateTime displayStart;
        private final LocalDateTime displayEnd;
        private final LocalDateTime displayAsOf;
        private final SkitManagementTimezone timezone;

        private QueryWindow(LocalDateTime databaseStart, LocalDateTime databaseEnd,
                            LocalDateTime databaseAsOf, LocalDateTime displayStart,
                            LocalDateTime displayEnd, LocalDateTime displayAsOf,
                            SkitManagementTimezone timezone) {
            this.databaseStart = databaseStart;
            this.databaseEnd = databaseEnd;
            this.databaseAsOf = databaseAsOf;
            this.displayStart = displayStart;
            this.displayEnd = displayEnd;
            this.displayAsOf = displayAsOf;
            this.timezone = timezone;
        }
    }

    private static final class RevenueTotals {
        private BigDecimal frozen = BigDecimal.ZERO;
        private BigDecimal reconciled = BigDecimal.ZERO;
        private BigDecimal preReportSuspense = BigDecimal.ZERO;
    }

    static void requireConserved(long reportActual, long attributableActual, long suspense) {
        long parts;
        try {
            parts = Math.addExact(attributableActual, suspense);
        } catch (ArithmeticException overflow) {
            throw new IllegalStateException(
                    "Canonical reconciliation bucket violates amount conservation", overflow);
        }
        if (reportActual < 0 || attributableActual < 0 || suspense < 0 || reportActual != parts) {
            throw new IllegalStateException(
                    "Canonical reconciliation bucket violates amount conservation");
        }
    }

    private static final class Sql {
        private final String text;
        private final List<Object> args;

        private Sql(String text, List<Object> args) {
            this.text = text;
            this.args = args;
        }
    }

    private static final class SeriesKey implements Comparable<SeriesKey> {
        private final String currency;
        private final LocalDateTime bucketStart;

        private SeriesKey(String currency, LocalDateTime bucketStart) {
            this.currency = currency;
            this.bucketStart = bucketStart;
        }

        @Override
        public int compareTo(SeriesKey other) {
            int currencyOrder = currency.compareTo(other.currency);
            return currencyOrder != 0 ? currencyOrder : bucketStart.compareTo(other.bucketStart);
        }

        @Override
        public boolean equals(Object value) {
            if (this == value) return true;
            if (!(value instanceof SeriesKey)) return false;
            SeriesKey other = (SeriesKey) value;
            return currency.equals(other.currency) && bucketStart.equals(other.bucketStart);
        }

        @Override
        public int hashCode() {
            return Objects.hash(currency, bucketStart);
        }
    }

}
