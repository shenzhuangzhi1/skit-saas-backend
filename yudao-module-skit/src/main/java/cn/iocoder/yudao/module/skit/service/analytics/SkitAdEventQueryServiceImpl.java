package cn.iocoder.yudao.module.skit.service.analytics;

import cn.iocoder.yudao.module.skit.controller.admin.tenant.vo.SkitAdEventDetailRespVO;
import cn.iocoder.yudao.module.skit.controller.admin.tenant.vo.SkitAdEventPageReqVO;
import cn.iocoder.yudao.module.skit.controller.admin.tenant.vo.SkitAdEventRespVO;
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
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.skit.enums.ErrorCodeConstants.MANAGEMENT_QUERY_RANGE_INVALID;
import static cn.iocoder.yudao.module.skit.enums.ErrorCodeConstants.MANAGEMENT_RESOURCE_NOT_FOUND;

@Service
public class SkitAdEventQueryServiceImpl implements SkitAdEventQueryService {

    private static final Duration DEFAULT_RANGE = Duration.ofDays(7);
    private static final Duration MAX_RANGE = Duration.ofDays(90);
    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    @Autowired
    public SkitAdEventQueryServiceImpl(JdbcTemplate jdbcTemplate) {
        this(jdbcTemplate, Clock.systemDefaultZone());
    }

    SkitAdEventQueryServiceImpl(JdbcTemplate jdbcTemplate, Clock clock) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public SkitStablePageRespVO<SkitAdEventRespVO> getPage(long tenantId,
                                                            SkitAdEventPageReqVO query) {
        return getPageInternal(tenantId, query);
    }

    @Override
    public SkitStablePageRespVO<SkitAdEventRespVO> getGlobalPage(SkitAdEventPageReqVO query) {
        return getPageInternal(null, query);
    }

    private SkitStablePageRespVO<SkitAdEventRespVO> getPageInternal(
            Long tenantId, SkitAdEventPageReqVO query) {
        SkitManagementTimezone timezone = SkitManagementTimezone.of(query.getTimezone());
        LocalDateTime asOf = timezone.now(clock).withNano(0);
        LocalDateTime end = query.getEndTime() == null || query.getEndTime().isAfter(asOf)
                ? asOf : query.getEndTime();
        LocalDateTime start = query.getStartTime() == null ? end.minus(DEFAULT_RANGE) : query.getStartTime();
        if (!start.isBefore(end) || Duration.between(start, end).compareTo(MAX_RANGE) > 0) {
            throw exception(MANAGEMENT_QUERY_RANGE_INVALID);
        }
        Filter filter = filter(tenantId, query, timezone.toDatabase(start),
                timezone.toDatabase(end));
        Long total = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM `skit_ad_revenue_event` `e` "
                + filter.where, Long.class, filter.args.toArray());
        List<Object> pageArgs = new ArrayList<>(filter.args);
        pageArgs.add(query.getPageSize());
        pageArgs.add((query.getPageNo() - 1) * query.getPageSize());
        List<SkitAdEventRespVO> rows = jdbcTemplate.query("SELECT `e`.`tenant_id`,`e`.`id`,`s`.`session_id`,"
                        + "`e`.`source_member_id`,`e`.`ad_account_id`,`e`.`provider`,`e`.`placement_id`,"
                        + "`e`.`match_status`,`e`.`source_verification_status`,"
                        + "`e`.`reward_qualification_status`,`e`.`reconciliation_status`,"
                        + "`e`.`source_currency`,`e`.`estimated_amount_units`,"
                        + "`e`.`reconciled_amount_units`,`e`.`amount_scale`,`e`.`occurred_time` "
                        + "FROM `skit_ad_revenue_event` `e` LEFT JOIN `skit_ad_session` `s` "
                        + "ON `s`.`tenant_id`=`e`.`tenant_id` AND `s`.`id`=`e`.`ad_session_id` "
                        + "AND `s`.`deleted`=b'0' " + filter.where
                        + " ORDER BY `e`.`occurred_time` DESC,`e`.`id` DESC LIMIT ? OFFSET ?",
                pageArgs.toArray(), (rs, rowNum) -> event(rs, new SkitAdEventRespVO(), timezone));

        SkitStablePageRespVO<SkitAdEventRespVO> response = new SkitStablePageRespVO<>();
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
    public SkitAdEventDetailRespVO get(long tenantId, long eventId, String timezone) {
        return getInternal(tenantId, eventId, timezone);
    }

    @Override
    public SkitAdEventDetailRespVO getGlobal(long eventId, String timezone) {
        return getInternal(null, eventId, timezone);
    }

    private SkitAdEventDetailRespVO getInternal(Long tenantId, long eventId, String timezone) {
        SkitManagementTimezone selectedTimezone = SkitManagementTimezone.of(timezone);
        String scope = tenantId == null ? "WHERE `e`.`id`=? "
                : "WHERE `e`.`tenant_id`=? AND `e`.`id`=? ";
        Object[] args = tenantId == null ? new Object[]{eventId} : new Object[]{tenantId, eventId};
        List<SkitAdEventDetailRespVO> events = jdbcTemplate.query("SELECT `e`.*,`s`.`session_id` "
                        + "FROM `skit_ad_revenue_event` `e` LEFT JOIN `skit_ad_session` `s` "
                        + "ON `s`.`tenant_id`=`e`.`tenant_id` AND `s`.`id`=`e`.`ad_session_id` "
                        + "AND `s`.`deleted`=b'0' " + scope
                        + "AND `e`.`deleted`=b'0' LIMIT 1",
                args, (rs, rowNum) -> {
                    SkitAdEventDetailRespVO detail = event(rs, new SkitAdEventDetailRespVO(),
                            selectedTimezone);
                    detail.setProviderTransactionId(rs.getString("provider_transaction_id"));
                    detail.setProviderShowId(rs.getString("provider_show_id"));
                    detail.setPolicySnapshotId(nullableLong(rs, "policy_snapshot_id"));
                    return detail;
                });
        if (events.isEmpty()) throw exception(MANAGEMENT_RESOURCE_NOT_FOUND);
        SkitAdEventDetailRespVO result = events.get(0);
        result.setAsOf(selectedTimezone.now(clock).withNano(0));
        result.setTimezone(selectedTimezone.getName());
        result.setCallbackAttempts(callbackAttempts(result.getTenantId(), eventId, selectedTimezone));
        result.setLedgerEntries(ledgerEntries(result.getTenantId(), eventId, selectedTimezone));
        return result;
    }

    private List<SkitAdEventDetailRespVO.CallbackAttempt> callbackAttempts(
            long tenantId, long eventId, SkitManagementTimezone timezone) {
        return jdbcTemplate.query(callbackTraceSql(),
                new Object[]{tenantId, eventId, tenantId, eventId}, (rs, rowNum) -> {
                    SkitAdEventDetailRespVO.CallbackAttempt item =
                            new SkitAdEventDetailRespVO.CallbackAttempt();
                    item.setId(rs.getLong("trace_id"));
                    item.setSource(rs.getString("callback_type"));
                    String attemptResult = rs.getString("result_code");
                    item.setStatus(attemptResult == null ? rs.getString("processing_status") : attemptResult);
                    item.setSignatureStatus(rs.getString("signature_status"));
                    item.setAuthenticationLevel(rs.getString("authentication_level"));
                    item.setReceivedAt(timezone.fromDatabase(
                            dateTime(rs.getTimestamp("trace_received_at"))));
                    item.setErrorCode(rs.getString("error_code"));
                    return item;
                });
    }

    static String callbackTraceSql() {
        return "SELECT COALESCE(`a`.`id`,`i`.`id`) AS `trace_id`,`i`.`id` AS `inbox_id`,"
                + "`i`.`callback_type`,`i`.`processing_status`,`i`.`authentication_level`,"
                + "`i`.`signature_status`,COALESCE(`a`.`received_at`,`i`.`received_at`) "
                + "AS `trace_received_at`,`i`.`error_code`,`a`.`result_code`,`a`.`attempt_no` "
                + "FROM (SELECT `e`.`tenant_id`,`e`.`id` AS `event_id`,"
                + "`e`.`callback_inbox_id` AS `inbox_id` FROM `skit_ad_revenue_event` `e` "
                + "WHERE `e`.`tenant_id`=? AND `e`.`id`=? AND `e`.`callback_inbox_id` IS NOT NULL "
                + "AND `e`.`deleted`=b'0' UNION SELECT `e`.`tenant_id`,`e`.`id` AS `event_id`,"
                + "`s`.`reward_callback_inbox_id` AS `inbox_id` FROM `skit_ad_revenue_event` `e` "
                + "JOIN `skit_ad_session` `s` ON `s`.`tenant_id`=`e`.`tenant_id` "
                + "AND `s`.`id`=`e`.`ad_session_id` AND `s`.`ad_account_id`=`e`.`ad_account_id` "
                + "AND `s`.`deleted`=b'0' WHERE `e`.`tenant_id`=? AND `e`.`id`=? "
                + "AND `s`.`reward_callback_inbox_id` IS NOT NULL AND `e`.`deleted`=b'0') `b` "
                + "JOIN `skit_ad_callback_inbox` `i` ON `i`.`tenant_id`=`b`.`tenant_id` "
                + "AND `i`.`id`=`b`.`inbox_id` LEFT JOIN `skit_ad_callback_attempt` `a` "
                + "ON `a`.`tenant_id`=`i`.`tenant_id` AND `a`.`callback_inbox_id`=`i`.`id` "
                + "WHERE `i`.`deleted`=b'0' ORDER BY `trace_received_at`,`inbox_id`,"
                + "COALESCE(`a`.`attempt_no`,0),`trace_id` LIMIT 500";
    }

    private List<SkitAdEventDetailRespVO.LedgerEntry> ledgerEntries(
            long tenantId, long eventId, SkitManagementTimezone timezone) {
        return jdbcTemplate.query("SELECT `id`,`beneficiary_type`,`beneficiary_member_id`,`level_no`,"
                        + "`entry_type`,`balance_bucket`,`currency`,`amount_units`,`amount_scale`,`create_time` "
                        + "FROM `skit_commission_ledger` WHERE `tenant_id`=? AND `event_id`=? "
                        + "AND `legacy_unverified`=b'0' AND `deleted`=b'0' ORDER BY `id` LIMIT 500",
                new Object[]{tenantId, eventId}, (rs, rowNum) -> {
                    SkitAdEventDetailRespVO.LedgerEntry item =
                            new SkitAdEventDetailRespVO.LedgerEntry();
                    item.setId(rs.getLong("id"));
                    item.setBeneficiaryType(rs.getInt("beneficiary_type") == 2 ? "AGENT" : "MEMBER");
                    item.setBeneficiaryMemberId(rs.getLong("beneficiary_member_id"));
                    item.setLevelNo(rs.getInt("level_no"));
                    item.setEntryType(rs.getString("entry_type"));
                    item.setBalanceBucket(rs.getString("balance_bucket"));
                    item.setCurrency(rs.getString("currency"));
                    item.setAmount(money(rs.getLong("amount_units"), rs.getInt("amount_scale")));
                    item.setCreatedAt(timezone.fromDatabase(dateTime(rs.getTimestamp("create_time"))));
                    return item;
                });
    }

    private <T extends SkitAdEventRespVO> T event(ResultSet rs, T result,
                                                   SkitManagementTimezone timezone)
            throws SQLException {
        result.setTenantId(rs.getLong("tenant_id"));
        result.setId(rs.getLong("id"));
        result.setSessionId(rs.getString("session_id"));
        result.setMemberId(nullableLong(rs, "source_member_id"));
        result.setAdAccountId(rs.getLong("ad_account_id"));
        result.setProvider(rs.getString("provider"));
        result.setPlacementId(rs.getString("placement_id"));
        result.setMatchStatus(rs.getString("match_status"));
        result.setSourceVerificationStatus(rs.getString("source_verification_status"));
        result.setRewardQualificationStatus(rs.getString("reward_qualification_status"));
        result.setReconciliationStatus(rs.getString("reconciliation_status"));
        result.setCurrency(rs.getString("source_currency"));
        int scale = rs.getInt("amount_scale");
        result.setEstimatedAmount(money(rs.getLong("estimated_amount_units"), scale));
        Long reconciled = nullableLong(rs, "reconciled_amount_units");
        result.setReconciledAmount(reconciled == null ? null : money(reconciled, scale));
        result.setOccurredTime(timezone.fromDatabase(dateTime(rs.getTimestamp("occurred_time"))));
        return result;
    }

    private Filter filter(Long tenantId, SkitAdEventPageReqVO query,
                          LocalDateTime start, LocalDateTime end) {
        StringBuilder where = new StringBuilder("WHERE ");
        List<Object> args = new ArrayList<>();
        if (tenantId != null) {
            where.append("`e`.`tenant_id`=? AND ");
            args.add(tenantId);
        }
        where.append("`e`.`occurred_time`>=? AND `e`.`occurred_time`<? AND `e`.`deleted`=b'0'");
        args.add(start);
        args.add(end);
        append(where, args, "`e`.`ad_account_id`", query.getAdAccountId());
        append(where, args, "`e`.`source_member_id`", query.getMemberId());
        append(where, args, "`e`.`provider`", query.getProvider());
        append(where, args, "`e`.`match_status`", query.getMatchStatus());
        append(where, args, "`e`.`source_verification_status`", query.getSourceVerificationStatus());
        append(where, args, "`e`.`reconciliation_status`", query.getReconciliationStatus());
        append(where, args, "`e`.`source_currency`", query.getCurrency());
        return new Filter(" " + where, args);
    }

    private void append(StringBuilder sql, List<Object> args, String column, Object value) {
        if (value != null && !value.toString().trim().isEmpty()) {
            sql.append(" AND ").append(column).append("=?");
            args.add(value);
        }
    }

    private Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
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
