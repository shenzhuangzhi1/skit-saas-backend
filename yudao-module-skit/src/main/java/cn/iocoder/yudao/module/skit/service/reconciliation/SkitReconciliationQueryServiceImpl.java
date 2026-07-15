package cn.iocoder.yudao.module.skit.service.reconciliation;

import cn.iocoder.yudao.module.skit.controller.admin.tenant.vo.SkitReconciliationDetailRespVO;
import cn.iocoder.yudao.module.skit.controller.admin.tenant.vo.SkitReconciliationPageReqVO;
import cn.iocoder.yudao.module.skit.controller.admin.tenant.vo.SkitReconciliationRespVO;
import cn.iocoder.yudao.module.skit.controller.admin.tenant.vo.SkitStablePageRespVO;
import cn.iocoder.yudao.module.skit.service.management.SkitManagementTimezone;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.skit.enums.ErrorCodeConstants.MANAGEMENT_QUERY_RANGE_INVALID;
import static cn.iocoder.yudao.module.skit.enums.ErrorCodeConstants.MANAGEMENT_RESOURCE_NOT_FOUND;

@Service
public class SkitReconciliationQueryServiceImpl implements SkitReconciliationQueryService {

    private static final long MAX_REPORT_DAYS = 366;

    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    @Autowired
    public SkitReconciliationQueryServiceImpl(JdbcTemplate jdbcTemplate) {
        this(jdbcTemplate, Clock.systemDefaultZone());
    }

    public SkitReconciliationQueryServiceImpl(JdbcTemplate jdbcTemplate, Clock clock) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public SkitStablePageRespVO<SkitReconciliationRespVO> getPage(
            long tenantId, SkitReconciliationPageReqVO query) {
        return getPageInternal(tenantId, query);
    }

    @Override
    public SkitStablePageRespVO<SkitReconciliationRespVO> getGlobalPage(
            SkitReconciliationPageReqVO query) {
        return getPageInternal(null, query);
    }

    private SkitStablePageRespVO<SkitReconciliationRespVO> getPageInternal(
            Long tenantId, SkitReconciliationPageReqVO query) {
        SkitManagementTimezone timezone = SkitManagementTimezone.of(query.getTimezone());
        LocalDateTime asOf = timezone.now(clock).withNano(0);
        LocalDate end = query.getReportDateEnd() == null ? asOf.toLocalDate() : query.getReportDateEnd();
        LocalDate start = query.getReportDateStart() == null ? end.minusDays(30) : query.getReportDateStart();
        if (start.isAfter(end) || ChronoUnit.DAYS.between(start, end) > MAX_REPORT_DAYS) {
            throw exception(MANAGEMENT_QUERY_RANGE_INVALID);
        }
        Filter filter = filter(tenantId, query, start, end);
        Long total = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM `skit_ad_reconciliation_bucket` `b` "
                + filter.where, Long.class, filter.args.toArray());
        List<Object> pageArgs = new ArrayList<>(filter.args);
        pageArgs.add(query.getPageSize());
        pageArgs.add((query.getPageNo() - 1) * query.getPageSize());
        List<SkitReconciliationRespVO> rows = jdbcTemplate.query(selectBase() + filter.where
                        + " ORDER BY `b`.`report_date` DESC,`b`.`id` DESC LIMIT ? OFFSET ?",
                pageArgs.toArray(), (rs, rowNum) -> reconciliation(rs,
                        new SkitReconciliationRespVO(), timezone));

        SkitStablePageRespVO<SkitReconciliationRespVO> response = new SkitStablePageRespVO<>();
        response.setTenantId(tenantId);
        response.setAsOf(asOf);
        response.setTimezone(timezone.getName());
        response.setPageNo(query.getPageNo());
        response.setPageSize(query.getPageSize());
        response.setTotal(total == null ? 0L : total);
        response.setList(rows);
        return response;
    }

    @Override
    public SkitReconciliationDetailRespVO get(long tenantId, long bucketId, String timezone) {
        return getInternal(tenantId, bucketId, timezone);
    }

    @Override
    public SkitReconciliationDetailRespVO getGlobal(long bucketId, String timezone) {
        return getInternal(null, bucketId, timezone);
    }

    private SkitReconciliationDetailRespVO getInternal(Long tenantId, long bucketId,
                                                        String timezone) {
        SkitManagementTimezone selectedTimezone = SkitManagementTimezone.of(timezone);
        String scope = tenantId == null ? "WHERE `b`.`id`=? "
                : "WHERE `b`.`tenant_id`=? AND `b`.`id`=? ";
        Object[] args = tenantId == null ? new Object[]{bucketId} : new Object[]{tenantId, bucketId};
        List<SkitReconciliationDetailRespVO> buckets = jdbcTemplate.query(selectBase()
                        + scope + "AND `b`.`deleted`=b'0' LIMIT 1",
                args, (rs, rowNum) -> {
                    SkitReconciliationDetailRespVO detail = reconciliation(rs,
                            new SkitReconciliationDetailRespVO(), selectedTimezone);
                    detail.setReportTimezone(rs.getString("report_timezone"));
                    detail.setAppId(rs.getString("app_id"));
                    detail.setPlacementId(rs.getString("placement_id"));
                    detail.setAdFormat(rs.getString("ad_format"));
                    detail.setNetworkFirmId(rs.getInt("network_firm_id"));
                    detail.setNetworkAccountId(rs.getString("network_account_id"));
                    detail.setAdsourceId(rs.getString("adsource_id"));
                    return detail;
                });
        if (buckets.isEmpty()) throw exception(MANAGEMENT_RESOURCE_NOT_FOUND);
        SkitReconciliationDetailRespVO result = buckets.get(0);
        result.setAsOf(selectedTimezone.now(clock).withNano(0));
        result.setTimezone(selectedTimezone.getName());
        result.setReportPulls(reportPulls(result.getTenantId(), bucketId, selectedTimezone));
        result.setUnmatchedItems(unmatchedItems(result.getTenantId(), bucketId));
        result.setRevisions(revisions(result.getTenantId(), bucketId, selectedTimezone));
        return result;
    }

    private List<SkitReconciliationDetailRespVO.ReportPull> reportPulls(
            long tenantId, long bucketId, SkitManagementTimezone timezone) {
        return jdbcTemplate.query("SELECT DISTINCT `p`.`id`,`p`.`status`,`p`.`range_start`,"
                        + "`p`.`range_end`,`p`.`pulled_at`,`p`.`error_code` "
                        + "FROM `skit_ad_reconciliation_revision` `r` JOIN `skit_ad_report_pull` `p` "
                        + "ON `p`.`tenant_id`=`r`.`tenant_id` AND `p`.`id`=`r`.`report_pull_id` "
                        + "WHERE `r`.`tenant_id`=? AND `r`.`reconciliation_bucket_id`=? "
                        + "AND `r`.`deleted`=b'0' AND `p`.`deleted`=b'0' "
                        + "ORDER BY `p`.`pulled_at` DESC,`p`.`id` DESC",
                new Object[]{tenantId, bucketId}, (rs, rowNum) -> {
                    SkitReconciliationDetailRespVO.ReportPull item =
                            new SkitReconciliationDetailRespVO.ReportPull();
                    item.setId(rs.getLong("id"));
                    item.setStatus(rs.getString("status"));
                    item.setRangeStart(timezone.fromDatabase(dateTime(rs.getTimestamp("range_start"))));
                    item.setRangeEnd(timezone.fromDatabase(dateTime(rs.getTimestamp("range_end"))));
                    item.setPulledAt(timezone.fromDatabase(dateTime(rs.getTimestamp("pulled_at"))));
                    item.setErrorCode(rs.getString("error_code"));
                    return item;
                });
    }

    private List<SkitReconciliationDetailRespVO.UnmatchedItem> unmatchedItems(long tenantId,
                                                                               long bucketId) {
        return jdbcTemplate.query("SELECT `id`,`provider_transaction_id`,`estimated_amount_units`,"
                        + "`amount_scale`,`source_verification_status`,`reward_qualification_status` "
                        + "FROM `skit_ad_revenue_event` WHERE `tenant_id`=? "
                        + "AND `reconciliation_bucket_id`=? AND `reconciliation_status`='SUSPENSE' "
                        + "AND `legacy_unverified`=b'0' AND `deleted`=b'0' "
                        + "ORDER BY `occurred_time`,`id` LIMIT 500",
                new Object[]{tenantId, bucketId}, (rs, rowNum) -> {
                    SkitReconciliationDetailRespVO.UnmatchedItem item =
                            new SkitReconciliationDetailRespVO.UnmatchedItem();
                    item.setEventId(rs.getLong("id"));
                    item.setProviderTransactionId(rs.getString("provider_transaction_id"));
                    item.setEstimatedAmount(money(rs.getLong("estimated_amount_units"),
                            rs.getInt("amount_scale")));
                    item.setReason(rs.getString("source_verification_status") + ":"
                            + rs.getString("reward_qualification_status"));
                    return item;
                });
    }

    private List<SkitReconciliationDetailRespVO.Revision> revisions(
            long tenantId, long bucketId, SkitManagementTimezone timezone) {
        return jdbcTemplate.query("SELECT `id`,`revision_no`,`target_actual_units`,"
                        + "`unmatched_actual_units`,`amount_scale`,`final_revision`,`status`,`reconciled_at` "
                        + "FROM `skit_ad_reconciliation_revision` WHERE `tenant_id`=? "
                        + "AND `reconciliation_bucket_id`=? AND `deleted`=b'0' "
                        + "ORDER BY `revision_no` DESC,`id` DESC",
                new Object[]{tenantId, bucketId}, (rs, rowNum) -> {
                    SkitReconciliationDetailRespVO.Revision item =
                            new SkitReconciliationDetailRespVO.Revision();
                    item.setId(rs.getLong("id"));
                    item.setRevisionNo(rs.getInt("revision_no"));
                    int scale = rs.getInt("amount_scale");
                    item.setTargetActualAmount(money(rs.getLong("target_actual_units"), scale));
                    item.setUnmatchedActualAmount(money(rs.getLong("unmatched_actual_units"), scale));
                    item.setFinalRevision(rs.getBoolean("final_revision"));
                    item.setStatus(rs.getString("status"));
                    item.setReconciledAt(timezone.fromDatabase(
                            dateTime(rs.getTimestamp("reconciled_at"))));
                    return item;
                });
    }

    private String selectBase() {
        return "SELECT `b`.`tenant_id`,`b`.`id`,`b`.`ad_account_id`,`b`.`report_date`,`b`.`report_timezone`,"
                + "`b`.`app_id`,`b`.`placement_id`,`b`.`ad_format`,`b`.`network_firm_id`,"
                + "`b`.`network_account_id`,`b`.`adsource_id`,`b`.`status`,`b`.`currency`,"
                + "`b`.`amount_scale`,`b`.`estimate_units`,`b`.`report_actual_units`,"
                + "`b`.`report_impressions`,`b`.`matched_impressions`,`r`.`revision_no`,"
                + "`r`.`reconciled_at` FROM `skit_ad_reconciliation_bucket` `b` "
                + "LEFT JOIN `skit_ad_reconciliation_revision` `r` ON `r`.`tenant_id`=`b`.`tenant_id` "
                + "AND `r`.`id`=(SELECT `r2`.`id` FROM `skit_ad_reconciliation_revision` `r2` "
                + "WHERE `r2`.`tenant_id`=`b`.`tenant_id` AND `r2`.`reconciliation_bucket_id`=`b`.`id` "
                + "AND `r2`.`deleted`=b'0' ORDER BY `r2`.`revision_no` DESC,`r2`.`id` DESC LIMIT 1) ";
    }

    private <T extends SkitReconciliationRespVO> T reconciliation(
            ResultSet rs, T result, SkitManagementTimezone timezone) throws SQLException {
        result.setTenantId(rs.getLong("tenant_id"));
        result.setId(rs.getLong("id"));
        result.setAdAccountId(rs.getLong("ad_account_id"));
        result.setReportDate(rs.getObject("report_date", LocalDate.class));
        result.setStatus(rs.getString("status"));
        result.setCurrency(rs.getString("currency"));
        int scale = rs.getInt("amount_scale");
        long estimate = rs.getLong("estimate_units");
        long actual = rs.getLong("report_actual_units");
        result.setEstimatedAmount(money(estimate, scale));
        result.setActualAmount(money(actual, scale));
        result.setDifferenceAmount(money(Math.subtractExact(actual, estimate), scale));
        result.setReportImpressions(rs.getLong("report_impressions"));
        result.setMatchedImpressions(rs.getLong("matched_impressions"));
        result.setLatestRevisionNo(nullableInt(rs, "revision_no"));
        result.setReconciledAt(timezone.fromDatabase(dateTime(rs.getTimestamp("reconciled_at"))));
        return result;
    }

    private Filter filter(Long tenantId, SkitReconciliationPageReqVO query,
                          LocalDate start, LocalDate end) {
        StringBuilder where = new StringBuilder("WHERE ");
        List<Object> args = new ArrayList<>();
        if (tenantId != null) {
            where.append("`b`.`tenant_id`=? AND ");
            args.add(tenantId);
        }
        where.append("`b`.`report_date`>=? AND `b`.`report_date`<=? AND `b`.`deleted`=b'0'");
        args.add(start);
        args.add(end);
        append(where, args, "`b`.`ad_account_id`", query.getAdAccountId());
        append(where, args, "`b`.`status`", query.getStatus());
        append(where, args, "`b`.`currency`", query.getCurrency());
        return new Filter(" " + where, args);
    }

    private void append(StringBuilder sql, List<Object> args, String column, Object value) {
        if (value != null && !value.toString().trim().isEmpty()) {
            sql.append(" AND ").append(column).append("=?");
            args.add(value);
        }
    }

    private Integer nullableInt(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private LocalDateTime dateTime(Timestamp value) {
        return value == null ? null : value.toLocalDateTime();
    }

    private String money(long units, int scale) {
        BigDecimal value = BigDecimal.valueOf(units, scale).stripTrailingZeros();
        return value.scale() < 0 ? value.setScale(0).toPlainString() : value.toPlainString();
    }

    private static final class Filter {
        private final String where;
        private final List<Object> args;

        private Filter(String where, List<Object> args) {
            this.where = where;
            this.args = args;
        }
    }

}
