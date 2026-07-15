package cn.iocoder.yudao.module.skit.service.management;

import cn.iocoder.yudao.module.skit.controller.admin.tenant.vo.SkitStablePageRespVO;
import cn.iocoder.yudao.module.skit.controller.admin.tenant.vo.ledger.SkitCommissionLedgerPageReqVO;
import cn.iocoder.yudao.module.skit.controller.admin.tenant.vo.ledger.SkitCommissionLedgerRespVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
import static cn.iocoder.yudao.module.skit.enums.SkitDomainConstants.BENEFICIARY_AGENT;
import static cn.iocoder.yudao.module.skit.enums.SkitDomainConstants.BENEFICIARY_MEMBER;

/** Read-only, append-ledger projection for the tenant management workspace. */
@Service
public class SkitCommissionLedgerQueryServiceImpl implements SkitCommissionLedgerQueryService {

    private static final Duration DEFAULT_RANGE = Duration.ofDays(30);
    private static final Duration MAX_RANGE = Duration.ofDays(366);

    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    @Autowired
    public SkitCommissionLedgerQueryServiceImpl(JdbcTemplate jdbcTemplate) {
        this(jdbcTemplate, Clock.systemUTC());
    }

    SkitCommissionLedgerQueryServiceImpl(JdbcTemplate jdbcTemplate, Clock clock) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    @Transactional(readOnly = true)
    public SkitStablePageRespVO<SkitCommissionLedgerRespVO> getPage(
            long tenantId, SkitCommissionLedgerPageReqVO query) {
        if (tenantId <= 0L) throw new IllegalArgumentException("tenantId must be positive");
        Objects.requireNonNull(query, "query");
        requireCurrency(query.getCurrency());
        SkitManagementTimezone timezone = SkitManagementTimezone.of(query.getTimezone());
        LocalDateTime now = timezone.now(clock).withNano(0);
        LocalDateTime asOf = query.getAsOf() == null || query.getAsOf().isAfter(now)
                ? now : query.getAsOf().withNano(0);
        LocalDateTime end = query.getEndTime() == null || query.getEndTime().isAfter(asOf)
                ? asOf : query.getEndTime().withNano(0);
        LocalDateTime start = query.getStartTime() == null
                ? end.minus(DEFAULT_RANGE) : query.getStartTime().withNano(0);
        if (!start.isBefore(end) || Duration.between(start, end).compareTo(MAX_RANGE) > 0) {
            throw exception(MANAGEMENT_QUERY_RANGE_INVALID);
        }
        Filter filter = filter(tenantId, query, timezone.toDatabase(start),
                timezone.toDatabase(end));
        String from = " FROM `skit_commission_ledger` `l` "
                + "JOIN `skit_ad_revenue_event` `e` ON `e`.`tenant_id`=`l`.`tenant_id` "
                + "AND `e`.`id`=`l`.`event_id` AND `e`.`deleted`=b'0' "
                + "LEFT JOIN `skit_member` `sm` ON `sm`.`tenant_id`=`e`.`tenant_id` "
                + "AND `sm`.`id`=`e`.`source_member_id` AND `sm`.`deleted`=b'0' "
                + "LEFT JOIN `skit_member` `bm` ON `bm`.`tenant_id`=`l`.`tenant_id` "
                + "AND `bm`.`id`=`l`.`beneficiary_member_id` AND `bm`.`deleted`=b'0' ";
        Long total = jdbcTemplate.queryForObject("SELECT COUNT(*)" + from + filter.where,
                Long.class, filter.args.toArray());
        List<Object> pageArgs = new ArrayList<>(filter.args);
        pageArgs.add(query.getPageSize());
        pageArgs.add(Math.multiplyExact((long) query.getPageNo() - 1L,
                query.getPageSize()));
        List<SkitCommissionLedgerRespVO> rows = jdbcTemplate.query(
                "SELECT `l`.`tenant_id`,`l`.`id`,`l`.`event_id`,`e`.`source_member_id`,"
                        + "`sm`.`nickname` AS `source_member_name`,`e`.`provider`,`e`.`placement_id`,"
                        + "`l`.`beneficiary_type`,`l`.`beneficiary_member_id`,"
                        + "`bm`.`nickname` AS `beneficiary_member_name`,`l`.`level_no`,"
                        + "`l`.`rate_bps`,`l`.`rule_version`,`l`.`entry_type`,"
                        + "`l`.`balance_bucket`,`l`.`currency`,`l`.`amount_scale`,"
                        + "`l`.`gross_amount_units`,`l`.`amount_units`,`l`.`revision_no`,"
                        + "`l`.`reversal_of_id`,`e`.`occurred_time`,`l`.`create_time`"
                        + from + filter.where
                        + " ORDER BY `l`.`create_time` DESC,`l`.`id` DESC LIMIT ? OFFSET ?",
                pageArgs.toArray(), (rs, rowNum) -> row(rs, timezone));

        SkitStablePageRespVO<SkitCommissionLedgerRespVO> response = new SkitStablePageRespVO<>();
        response.setTenantId(tenantId);
        response.setAsOf(asOf);
        response.setTimezone(timezone.getName());
        response.setPageNo(query.getPageNo());
        response.setPageSize(query.getPageSize());
        response.setTotal(total == null ? 0L : total);
        response.setList(rows);
        return response;
    }

    private Filter filter(long tenantId, SkitCommissionLedgerPageReqVO query,
                          LocalDateTime databaseStart, LocalDateTime databaseEnd) {
        StringBuilder where = new StringBuilder(" WHERE `l`.`tenant_id`=? "
                + "AND `l`.`currency`=? AND `l`.`create_time`>=? AND `l`.`create_time`<=? "
                + "AND `l`.`legacy_unverified`=b'0' AND `l`.`deleted`=b'0'");
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        args.add(query.getCurrency());
        args.add(databaseStart);
        args.add(databaseEnd);
        append(where, args, "`l`.`id`", query.getId());
        append(where, args, "`l`.`event_id`", query.getEventId());
        append(where, args, "`e`.`source_member_id`", query.getSourceMemberId());
        append(where, args, "`l`.`beneficiary_member_id`", query.getBeneficiaryMemberId());
        append(where, args, "`e`.`provider`", query.getProvider());
        append(where, args, "`l`.`entry_type`", query.getEntryType());
        append(where, args, "`l`.`balance_bucket`", query.getBalanceBucket());
        if (query.getBeneficiaryType() != null) {
            append(where, args, "`l`.`beneficiary_type`",
                    "AGENT".equals(query.getBeneficiaryType())
                            ? BENEFICIARY_AGENT : BENEFICIARY_MEMBER);
        }
        return new Filter(where.toString(), args);
    }

    private void append(StringBuilder where, List<Object> args, String column, Object value) {
        if (value != null && !value.toString().trim().isEmpty()) {
            where.append(" AND ").append(column).append("=?");
            args.add(value);
        }
    }

    private SkitCommissionLedgerRespVO row(ResultSet rs, SkitManagementTimezone timezone)
            throws SQLException {
        int beneficiaryType = rs.getInt("beneficiary_type");
        if (beneficiaryType != BENEFICIARY_MEMBER && beneficiaryType != BENEFICIARY_AGENT) {
            throw new IllegalStateException("Unknown commission beneficiary type");
        }
        int scale = rs.getInt("amount_scale");
        if (scale < 0 || scale > 18) throw new IllegalStateException("Invalid ledger amount scale");
        long grossUnits = rs.getLong("gross_amount_units");
        long amountUnits = rs.getLong("amount_units");
        SkitCommissionLedgerRespVO result = new SkitCommissionLedgerRespVO();
        result.setTenantId(rs.getLong("tenant_id"));
        result.setId(rs.getLong("id"));
        result.setEventId(rs.getLong("event_id"));
        result.setSourceMemberId(rs.getLong("source_member_id"));
        result.setSourceMemberName(rs.getString("source_member_name"));
        result.setProvider(rs.getString("provider"));
        result.setPlacementId(rs.getString("placement_id"));
        result.setBeneficiaryType(beneficiaryType == BENEFICIARY_AGENT ? "AGENT" : "MEMBER");
        result.setBeneficiaryMemberId(rs.getLong("beneficiary_member_id"));
        result.setBeneficiaryMemberName(beneficiaryType == BENEFICIARY_AGENT
                ? "代理商" : rs.getString("beneficiary_member_name"));
        result.setLevelNo(rs.getInt("level_no"));
        result.setRateBps(rs.getInt("rate_bps"));
        result.setRuleVersion(rs.getInt("rule_version"));
        result.setEntryType(rs.getString("entry_type"));
        result.setBalanceBucket(rs.getString("balance_bucket"));
        result.setCurrency(rs.getString("currency"));
        result.setAmountScale(scale);
        result.setGrossAmount(money(grossUnits, scale));
        result.setGrossAmountUnits(Long.toString(grossUnits));
        result.setAmount(money(amountUnits, scale));
        result.setAmountUnits(Long.toString(amountUnits));
        result.setRevisionNo(rs.getInt("revision_no"));
        result.setReversalOfId(nullableLong(rs, "reversal_of_id"));
        result.setOccurredAt(timezone.fromDatabase(dateTime(rs.getTimestamp("occurred_time"))));
        result.setCreatedAt(timezone.fromDatabase(dateTime(rs.getTimestamp("create_time"))));
        return result;
    }

    private void requireCurrency(String currency) {
        if (currency == null || !currency.matches("[A-Z]{3}")) {
            throw new IllegalArgumentException("currency must be an ISO 4217 uppercase code");
        }
    }

    private Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private LocalDateTime dateTime(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime();
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
