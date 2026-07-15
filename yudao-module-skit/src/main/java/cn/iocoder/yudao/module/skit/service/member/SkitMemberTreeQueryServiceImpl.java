package cn.iocoder.yudao.module.skit.service.member;

import cn.iocoder.yudao.module.skit.controller.admin.tenant.vo.member.SkitMemberAncestorsRespVO;
import cn.iocoder.yudao.module.skit.controller.admin.tenant.vo.member.SkitMemberChildrenRespVO;
import cn.iocoder.yudao.module.skit.controller.admin.tenant.vo.member.SkitMemberSubtreeSummaryReqVO;
import cn.iocoder.yudao.module.skit.controller.admin.tenant.vo.member.SkitMemberSubtreeSummaryRespVO;
import cn.iocoder.yudao.module.skit.controller.admin.tenant.vo.member.SkitMemberTreeNodeRespVO;
import cn.iocoder.yudao.module.skit.service.management.SkitManagementTimezone;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.skit.enums.ErrorCodeConstants.MANAGEMENT_QUERY_RANGE_INVALID;
import static cn.iocoder.yudao.module.skit.enums.ErrorCodeConstants.MANAGEMENT_RESOURCE_NOT_FOUND;
import static cn.iocoder.yudao.module.skit.enums.SkitDomainConstants.BENEFICIARY_AGENT;
import static cn.iocoder.yudao.module.skit.enums.SkitDomainConstants.BENEFICIARY_MEMBER;
import static cn.iocoder.yudao.module.skit.enums.SkitDomainConstants.LEDGER_BALANCE_AVAILABLE;
import static cn.iocoder.yudao.module.skit.enums.SkitDomainConstants.LEDGER_ENTRY_ADJUSTMENT;
import static cn.iocoder.yudao.module.skit.enums.SkitDomainConstants.LEDGER_ENTRY_SETTLEMENT;

@Service
public class SkitMemberTreeQueryServiceImpl implements SkitMemberTreeQueryService {

    private static final int MAX_CHILD_PAGE_SIZE = 100;
    private static final int MAX_ANCESTOR_DEPTH = 500;
    private static final Duration MAX_SUMMARY_RANGE = Duration.ofDays(366);

    private final JdbcTemplate jdbcTemplate;
    private final SkitMemberTreeCursorCodec cursorCodec;
    private final Clock clock;

    @Autowired
    public SkitMemberTreeQueryServiceImpl(JdbcTemplate jdbcTemplate,
                                           SkitMemberTreeCursorCodec cursorCodec) {
        this(jdbcTemplate, cursorCodec, Clock.systemUTC());
    }

    SkitMemberTreeQueryServiceImpl(JdbcTemplate jdbcTemplate,
                                   SkitMemberTreeCursorCodec cursorCodec, Clock clock) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
        this.cursorCodec = Objects.requireNonNull(cursorCodec, "cursorCodec");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    @Transactional(readOnly = true)
    public SkitMemberChildrenRespVO getChildren(long tenantId, long parentId, String cursor,
                                                 int pageSize, String requestedTimezone) {
        requireIds(tenantId, parentId);
        if (pageSize < 1 || pageSize > MAX_CHILD_PAGE_SIZE) {
            throw exception(MANAGEMENT_QUERY_RANGE_INVALID);
        }
        requireMember(tenantId, parentId);
        SkitManagementTimezone timezone = SkitManagementTimezone.of(requestedTimezone);
        LocalDateTime databaseAsOf;
        LocalDateTime lastCreatedAt = null;
        Long lastId = null;
        if (cursor == null || cursor.trim().isEmpty()) {
            databaseAsOf = timezone.toDatabase(timezone.now(clock).withNano(0));
        } else {
            SkitMemberTreeCursorCodec.Cursor decoded = cursorCodec.decode(cursor, tenantId, parentId);
            databaseAsOf = decoded.getAsOf();
            lastCreatedAt = decoded.getLastCreatedAt();
            lastId = decoded.getLastId();
        }
        StringBuilder sql = new StringBuilder("SELECT `m`.`id`,`m`.`inviter_id`,`m`.`nickname`,"
                + "`m`.`invite_code`,`m`.`depth`,`m`.`status`,`m`.`create_time`,"
                + "(SELECT COUNT(*) FROM `skit_member` `ch` WHERE `ch`.`tenant_id`=`m`.`tenant_id` "
                + "AND `ch`.`inviter_id`=`m`.`id` AND `ch`.`deleted`=b'0') AS `child_count` "
                + "FROM `skit_member` `m` WHERE `m`.`tenant_id`=? AND `m`.`inviter_id`=? "
                + "AND `m`.`create_time`<=? AND `m`.`deleted`=b'0'");
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        args.add(parentId);
        args.add(databaseAsOf);
        if (lastId != null) {
            sql.append(" AND (`m`.`create_time`<? OR (`m`.`create_time`=? AND `m`.`id`<?))");
            args.add(lastCreatedAt);
            args.add(lastCreatedAt);
            args.add(lastId);
        }
        sql.append(" ORDER BY `m`.`create_time` DESC,`m`.`id` DESC LIMIT ?");
        args.add(pageSize + 1);
        List<NodeRow> rows = jdbcTemplate.query(sql.toString(), args.toArray(),
                (rs, rowNum) -> node(rs, timezone, null));
        boolean hasMore = rows.size() > pageSize;
        if (hasMore) rows = new ArrayList<>(rows.subList(0, pageSize));
        String nextCursor = null;
        if (hasMore && !rows.isEmpty()) {
            NodeRow last = rows.get(rows.size() - 1);
            nextCursor = cursorCodec.encode(tenantId, parentId, databaseAsOf,
                    last.databaseCreatedAt, last.node.getId());
        }
        List<SkitMemberTreeNodeRespVO> nodes = new ArrayList<>(rows.size());
        for (NodeRow row : rows) nodes.add(row.node);
        SkitMemberChildrenRespVO response = new SkitMemberChildrenRespVO();
        response.setTenantId(tenantId);
        response.setParentId(parentId);
        response.setAsOf(timezone.fromDatabase(databaseAsOf));
        response.setTimezone(timezone.getName());
        response.setPageSize(pageSize);
        response.setNextCursor(nextCursor);
        response.setList(nodes);
        return response;
    }

    @Override
    @Transactional(readOnly = true)
    public SkitMemberAncestorsRespVO getAncestors(long tenantId, long memberId,
                                                   String requestedTimezone) {
        requireIds(tenantId, memberId);
        requireMember(tenantId, memberId);
        SkitManagementTimezone timezone = SkitManagementTimezone.of(requestedTimezone);
        List<NodeRow> rows = jdbcTemplate.query(
                "SELECT `m`.`id`,`m`.`inviter_id`,`m`.`nickname`,`m`.`invite_code`,"
                        + "`m`.`depth`,`m`.`status`,`m`.`create_time`,`c`.`distance`,"
                        + "(SELECT COUNT(*) FROM `skit_member` `ch` WHERE "
                        + "`ch`.`tenant_id`=`m`.`tenant_id` AND `ch`.`inviter_id`=`m`.`id` "
                        + "AND `ch`.`deleted`=b'0') AS `child_count` "
                        + "FROM `skit_member_closure` `c` JOIN `skit_member` `m` "
                        + "ON `m`.`tenant_id`=`c`.`tenant_id` AND `m`.`id`=`c`.`ancestor_id` "
                        + "AND `m`.`deleted`=b'0' WHERE `c`.`tenant_id`=? "
                        + "AND `c`.`descendant_id`=? AND `c`.`deleted`=b'0' "
                        + "ORDER BY `c`.`distance` DESC,`m`.`id` LIMIT ?",
                new Object[]{tenantId, memberId, MAX_ANCESTOR_DEPTH + 1},
                (rs, rowNum) -> node(rs, timezone, rs.getInt("distance")));
        if (rows.isEmpty() || rows.size() > MAX_ANCESTOR_DEPTH
                || !Long.valueOf(memberId).equals(rows.get(rows.size() - 1).node.getId())
                || !Integer.valueOf(0).equals(rows.get(rows.size() - 1).node.getDistance())) {
            throw exception(MANAGEMENT_QUERY_RANGE_INVALID);
        }
        for (int index = 0; index < rows.size(); index++) {
            int expectedDistance = rows.size() - index - 1;
            if (!Integer.valueOf(expectedDistance).equals(rows.get(index).node.getDistance())) {
                throw exception(MANAGEMENT_QUERY_RANGE_INVALID);
            }
        }
        List<SkitMemberTreeNodeRespVO> nodes = new ArrayList<>(rows.size());
        for (NodeRow row : rows) nodes.add(row.node);
        SkitMemberAncestorsRespVO response = new SkitMemberAncestorsRespVO();
        response.setTenantId(tenantId);
        response.setMemberId(memberId);
        response.setAsOf(timezone.now(clock).withNano(0));
        response.setTimezone(timezone.getName());
        response.setList(nodes);
        return response;
    }

    @Override
    @Transactional(readOnly = true)
    public SkitMemberSubtreeSummaryRespVO getSubtreeSummary(
            long tenantId, long memberId, SkitMemberSubtreeSummaryReqVO query) {
        requireIds(tenantId, memberId);
        requireMember(tenantId, memberId);
        Objects.requireNonNull(query, "query");
        if (!"RECONCILED_LEDGER".equals(query.getStatisticBasis())) {
            throw exception(MANAGEMENT_QUERY_RANGE_INVALID);
        }
        requireCurrency(query.getCurrency());
        SkitManagementTimezone timezone = SkitManagementTimezone.of(query.getTimezone());
        LocalDateTime asOf = timezone.now(clock).withNano(0);
        LocalDateTime end = query.getEndTime() == null || query.getEndTime().isAfter(asOf)
                ? asOf : query.getEndTime().withNano(0);
        LocalDateTime start = query.getStartTime() == null
                ? null : query.getStartTime().withNano(0);
        if (start == null || !start.isBefore(end)
                || Duration.between(start, end).compareTo(MAX_SUMMARY_RANGE) > 0) {
            throw exception(MANAGEMENT_QUERY_RANGE_INVALID);
        }
        LocalDateTime databaseStart = timezone.toDatabase(start);
        LocalDateTime databaseEnd = timezone.toDatabase(end);
        Long memberCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM `skit_member_closure` WHERE `tenant_id`=? "
                        + "AND `ancestor_id`=? AND `deleted`=b'0'",
                Long.class, tenantId, memberId);
        if (memberCount == null || memberCount <= 0L) {
            throw exception(MANAGEMENT_RESOURCE_NOT_FOUND);
        }
        EventFilter eventFilter = eventFilter(tenantId, memberId, query,
                databaseStart, databaseEnd);
        Map<String, Object> counts = jdbcTemplate.queryForMap(
                "SELECT COUNT(DISTINCT `e`.`id`) AS `event_count`,"
                        + "COUNT(DISTINCT `e`.`source_member_id`) AS `member_count` "
                        + eventFilter.fromWhere, eventFilter.args.toArray());
        long eventCount = number(counts.get("event_count"));
        long contributingMembers = number(counts.get("member_count"));
        String ledgerAggregate = "LEFT JOIN (SELECT `tenant_id`,`event_id`,`amount_scale`,"
                + "SUM(CASE WHEN `beneficiary_type`=" + BENEFICIARY_MEMBER
                + " THEN `amount_units` ELSE 0 END) AS `member_units`,"
                + "SUM(CASE WHEN `beneficiary_type`=" + BENEFICIARY_AGENT
                + " THEN `amount_units` ELSE 0 END) AS `agent_units` "
                + "FROM `skit_commission_ledger` WHERE `tenant_id`=? AND `currency`=? "
                + "AND `balance_bucket`='" + LEDGER_BALANCE_AVAILABLE + "' "
                + "AND `entry_type` IN ('" + LEDGER_ENTRY_SETTLEMENT + "','"
                + LEDGER_ENTRY_ADJUSTMENT + "') AND `legacy_unverified`=b'0' "
                + "AND `deleted`=b'0' GROUP BY `tenant_id`,`event_id`,`amount_scale`) `la` "
                + "ON `la`.`tenant_id`=`e`.`tenant_id` AND `la`.`event_id`=`e`.`id` "
                + "AND `la`.`amount_scale`=`e`.`amount_scale` ";
        List<Object> amountArgs = new ArrayList<>();
        amountArgs.add(tenantId);
        amountArgs.add(query.getCurrency());
        amountArgs.addAll(eventFilter.args);
        List<SkitMemberSubtreeSummaryRespVO.MoneySummary> amounts = jdbcTemplate.query(
                "SELECT `e`.`amount_scale`,SUM(`e`.`reconciled_amount_units`) AS `gross_units`,"
                        + "SUM(COALESCE(`la`.`member_units`,0)) AS `member_units`,"
                        + "SUM(COALESCE(`la`.`agent_units`,0)) AS `agent_units` "
                        + eventFilter.from + ledgerAggregate + eventFilter.where
                        + " GROUP BY `e`.`amount_scale` ORDER BY `e`.`amount_scale`",
                amountArgs.toArray(), (rs, rowNum) -> moneySummary(rs));

        SkitMemberSubtreeSummaryRespVO response = new SkitMemberSubtreeSummaryRespVO();
        response.setTenantId(tenantId);
        response.setMemberId(memberId);
        response.setAsOf(asOf);
        response.setTimezone(timezone.getName());
        response.setStartTime(start);
        response.setEndTime(end);
        response.setCurrency(query.getCurrency());
        response.setProvider(query.getProvider());
        response.setStatisticBasis("RECONCILED_LEDGER");
        response.setMemberCount(memberCount);
        response.setDescendantCount(Math.max(0L, memberCount - 1L));
        response.setContributingMemberCount(contributingMembers);
        response.setRewardedEventCount(eventCount);
        response.setAmounts(amounts);
        return response;
    }

    private EventFilter eventFilter(long tenantId, long memberId,
                                    SkitMemberSubtreeSummaryReqVO query,
                                    LocalDateTime databaseStart, LocalDateTime databaseEnd) {
        String from = "FROM `skit_ad_revenue_event` `e` JOIN `skit_member_closure` `c` "
                + "ON `c`.`tenant_id`=`e`.`tenant_id` AND `c`.`descendant_id`=`e`.`source_member_id` ";
        StringBuilder where = new StringBuilder("WHERE `e`.`tenant_id`=? "
                + "AND `c`.`ancestor_id`=? AND `c`.`deleted`=b'0' "
                + "AND `e`.`occurred_time`>=? AND `e`.`occurred_time`<? "
                + "AND `e`.`source_currency`=? AND `e`.`reward_qualification_status`='REWARDED' "
                + "AND `e`.`reconciled_amount_units` IS NOT NULL "
                + "AND `e`.`legacy_unverified`=b'0' AND `e`.`deleted`=b'0'");
        List<Object> args = new ArrayList<>();
        Collections.addAll(args, tenantId, memberId, databaseStart, databaseEnd,
                query.getCurrency());
        if (query.getProvider() != null && !query.getProvider().trim().isEmpty()) {
            where.append(" AND `e`.`provider`=?");
            args.add(query.getProvider());
        }
        return new EventFilter(from, " " + where, args);
    }

    private SkitMemberSubtreeSummaryRespVO.MoneySummary moneySummary(ResultSet rs)
            throws SQLException {
        int scale = rs.getInt("amount_scale");
        if (scale < 0 || scale > 18) throw new IllegalStateException("Invalid event amount scale");
        BigInteger gross = integer(rs.getBigDecimal("gross_units"));
        BigInteger members = integer(rs.getBigDecimal("member_units"));
        BigInteger agent = integer(rs.getBigDecimal("agent_units"));
        boolean conserved = members.add(agent).equals(gross);
        return new SkitMemberSubtreeSummaryRespVO.MoneySummary()
                .setAmountScale(scale)
                .setGrossRevenue(money(gross, scale)).setGrossRevenueUnits(gross.toString())
                .setMemberAllocation(money(members, scale))
                .setMemberAllocationUnits(members.toString())
                .setAgentRetention(money(agent, scale))
                .setAgentRetentionUnits(agent.toString()).setConserved(conserved);
    }

    private NodeRow node(ResultSet rs, SkitManagementTimezone timezone, Integer distance)
            throws SQLException {
        LocalDateTime databaseCreatedAt = dateTime(rs.getTimestamp("create_time"));
        if (databaseCreatedAt == null) throw new IllegalStateException("Member create time is missing");
        SkitMemberTreeNodeRespVO node = new SkitMemberTreeNodeRespVO();
        node.setId(rs.getLong("id"));
        node.setParentId(nullableLong(rs, "inviter_id"));
        node.setNickname(rs.getString("nickname"));
        node.setInviteCode(rs.getString("invite_code"));
        node.setDepth(rs.getInt("depth"));
        node.setStatus(status(rs.getInt("status")));
        node.setDirectChildCount(rs.getLong("child_count"));
        node.setDistance(distance);
        node.setCreatedAt(timezone.fromDatabase(databaseCreatedAt));
        return new NodeRow(node, databaseCreatedAt);
    }

    private void requireMember(long tenantId, long memberId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM `skit_member` WHERE `tenant_id`=? AND `id`=? "
                        + "AND `deleted`=b'0'", Long.class, tenantId, memberId);
        if (count == null || count != 1L) throw exception(MANAGEMENT_RESOURCE_NOT_FOUND);
    }

    private String status(int status) {
        if (status == 0) return "ENABLED";
        if (status == 1) return "DISABLED";
        return "UNKNOWN";
    }

    private void requireIds(long tenantId, long memberId) {
        if (tenantId <= 0L || memberId <= 0L) {
            throw new IllegalArgumentException("tenantId and memberId must be positive");
        }
    }

    private void requireCurrency(String currency) {
        if (currency == null || !currency.matches("[A-Z]{3}")) {
            throw new IllegalArgumentException("currency must be an ISO 4217 uppercase code");
        }
    }

    private long number(Object value) {
        return value == null ? 0L : ((Number) value).longValue();
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

    private BigInteger integer(BigDecimal value) {
        if (value == null) return BigInteger.ZERO;
        return value.toBigIntegerExact();
    }

    private String money(BigInteger units, int scale) {
        BigDecimal value = new BigDecimal(units, scale).stripTrailingZeros();
        return value.scale() < 0 ? value.setScale(0).toPlainString() : value.toPlainString();
    }

    private static final class NodeRow {
        private final SkitMemberTreeNodeRespVO node;
        private final LocalDateTime databaseCreatedAt;

        private NodeRow(SkitMemberTreeNodeRespVO node, LocalDateTime databaseCreatedAt) {
            this.node = node;
            this.databaseCreatedAt = databaseCreatedAt;
        }
    }

    private static final class EventFilter {
        private final String from;
        private final String where;
        private final String fromWhere;
        private final List<Object> args;

        private EventFilter(String from, String where, List<Object> args) {
            this.from = from;
            this.where = where;
            this.fromWhere = from + where;
            this.args = args;
        }
    }
}
