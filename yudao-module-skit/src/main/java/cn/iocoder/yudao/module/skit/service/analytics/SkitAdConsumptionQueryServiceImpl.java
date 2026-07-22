package cn.iocoder.yudao.module.skit.service.analytics;

import cn.iocoder.yudao.module.skit.controller.admin.tenant.vo.SkitAdConsumptionDetailRespVO;
import cn.iocoder.yudao.module.skit.controller.admin.tenant.vo.SkitAdConsumptionPageReqVO;
import cn.iocoder.yudao.module.skit.controller.admin.tenant.vo.SkitAdConsumptionRespVO;
import cn.iocoder.yudao.module.skit.controller.admin.tenant.vo.SkitAdConsumptionSummaryRespVO;
import cn.iocoder.yudao.module.skit.controller.admin.tenant.vo.SkitStablePageRespVO;
import cn.iocoder.yudao.module.skit.service.management.SkitManagementTimezone;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.skit.enums.ErrorCodeConstants.MANAGEMENT_QUERY_RANGE_INVALID;
import static cn.iocoder.yudao.module.skit.enums.ErrorCodeConstants.MANAGEMENT_RESOURCE_NOT_FOUND;

/**
 * Read model for every ad attempt. The session is deliberately the root fact: failed loads,
 * early closes and sessions without a revenue callback must remain visible to operations.
 */
@Service
public class SkitAdConsumptionQueryServiceImpl implements SkitAdConsumptionQueryService {

    private static final Duration DEFAULT_RANGE = Duration.ofDays(7);
    private static final Duration MAX_RANGE = Duration.ofDays(90);
    private static final Pattern MASKED_MOBILE_SEARCH_PATTERN =
            Pattern.compile("^(\\d{2,3})\\*{3,4}(\\d{4})$");

    private static final String SESSION_FROM = "FROM `skit_ad_session` `s` "
            + "LEFT JOIN `skit_member` `m` ON `m`.`tenant_id`=`s`.`tenant_id` "
            + "AND `m`.`id`=`s`.`member_id` AND `m`.`deleted`=b'0' ";

    private static final String LATEST_REVENUE_JOIN = "LEFT JOIN `skit_ad_revenue_event` `e` ON "
            + "`e`.`tenant_id`=`s`.`tenant_id` AND `e`.`ad_session_id`=`s`.`id` "
            + "AND `e`.`deleted`=b'0' AND `e`.`legacy_unverified`=b'0' AND `e`.`mock`=b'0' "
            + "AND `e`.`id`=(SELECT `e2`.`id` FROM `skit_ad_revenue_event` `e2` "
            + "WHERE `e2`.`tenant_id`=`s`.`tenant_id` AND `e2`.`ad_session_id`=`s`.`id` "
            + "AND `e2`.`deleted`=b'0' AND `e2`.`legacy_unverified`=b'0' AND `e2`.`mock`=b'0' "
            + "ORDER BY `e2`.`occurred_time` DESC,`e2`.`id` DESC LIMIT 1) ";

    private static final String PLAYER_GRANT_JOIN = "LEFT JOIN `skit_native_player_grant` `g` ON "
            + "`g`.`tenant_id`=`s`.`tenant_id` AND `g`.`id`=`s`.`native_player_grant_id` "
            + "AND `g`.`member_id`=`s`.`member_id` AND `g`.`drama_id`=`s`.`drama_id` "
            + "AND `g`.`deleted`=b'0' ";

    private static final String CLIENT_REWARD_OBSERVED = "EXISTS (SELECT 1 FROM "
            + "`skit_ad_client_event` `rewarded` WHERE "
            + "`rewarded`.`tenant_id`=`s`.`tenant_id` "
            + "AND `rewarded`.`ad_session_id`=`s`.`id` "
            + "AND `rewarded`.`client_reward_observed`=b'1' "
            + "AND `rewarded`.`deleted`=b'0')";

    private static final String CONSUMPTION_STATUS = "CASE "
            + "WHEN `s`.`entitlement_status`='GRANTED' THEN 'UNLOCKED' "
            + "WHEN `s`.`reward_verification_status`='VERIFY_TIMEOUT' THEN 'VERIFY_TIMEOUT' "
            + "WHEN `s`.`reward_verification_status`='REJECTED' THEN 'REWARD_REJECTED' "
            + "WHEN `s`.`reward_verification_status`='SIGNED_VERIFIED' THEN 'REWARD_VERIFIED' "
            + "WHEN `s`.`client_lifecycle_status`='FAILED' THEN 'FAILED' "
            + "WHEN `s`.`client_lifecycle_status`='LOAD_EXPIRED' THEN 'LOAD_EXPIRED' "
            + "WHEN " + CLIENT_REWARD_OBSERVED + " THEN 'REWARD_OBSERVED' "
            + "WHEN `s`.`client_lifecycle_status`='CLOSED' THEN 'CLOSED' "
            + "WHEN `s`.`client_lifecycle_status`='SHOWN' THEN 'SHOWN' "
            + "WHEN `s`.`client_lifecycle_status`='LOADING' THEN 'LOAD_STARTED' "
            + "ELSE 'CREATED' END";

    private static final String ROW_SELECT = "SELECT `s`.`tenant_id`,`s`.`id`,`s`.`session_id`,"
            + "`s`.`member_id`,`m`.`nickname` AS `member_nickname`,"
            + "CASE WHEN `m`.`mobile` IS NULL THEN NULL WHEN `m`.`mobile`='' THEN '' "
            + "WHEN CHAR_LENGTH(`m`.`mobile`)<=7 THEN CONCAT(LEFT(`m`.`mobile`,2),'***') "
            + "ELSE CONCAT(LEFT(`m`.`mobile`,3),'****',RIGHT(`m`.`mobile`,4)) END "
            + "AS `member_mobile_masked`,`s`.`drama_id`,`s`.`episode_from`,`s`.`episode_to`,"
            + "`s`.`ad_account_id`,`s`.`provider`,`s`.`placement_id`,`s`.`network_firm_id`,"
            + "COALESCE(NULLIF(`s`.`adsource_id`,''),`e`.`adsource_id`) AS `adsource_id`,"
            + "COALESCE(NULLIF(`s`.`sdk_request_id`,''),`e`.`sdk_request_id`) AS `sdk_request_id`,"
            + "COALESCE(NULLIF(`s`.`provider_show_id`,''),`e`.`provider_show_id`) AS `provider_show_id`,"
            + "COALESCE(NULLIF(`s`.`provider_transaction_id`,''),`e`.`provider_transaction_id`) "
            + "AS `provider_transaction_id`," + CONSUMPTION_STATUS + " AS `consumption_status`,"
            + "`s`.`client_lifecycle_status`,`s`.`reward_verification_status`,"
            + "`s`.`entitlement_status`,`s`.`revenue_status`,`s`.`failure_reason`,"
            + "`e`.`source_currency`,`e`.`estimated_amount_units`,"
            + "CASE WHEN `e`.`reconciliation_status`='RECONCILED' "
            + "THEN `e`.`reconciled_amount_units` ELSE NULL END AS `reconciled_amount_units`,"
            + "`e`.`amount_scale`,`g`.`id` AS `native_grant_evidence_id`,`s`.`create_time`,"
            + "`s`.`update_time` AS `last_event_at`,`s`.`reward_verified_at`,`s`.`entitled_at` ";

    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    @Autowired
    public SkitAdConsumptionQueryServiceImpl(JdbcTemplate jdbcTemplate) {
        this(jdbcTemplate, Clock.systemDefaultZone());
    }

    SkitAdConsumptionQueryServiceImpl(JdbcTemplate jdbcTemplate, Clock clock) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public SkitStablePageRespVO<SkitAdConsumptionRespVO> getPage(
            long tenantId, SkitAdConsumptionPageReqVO query) {
        return getPageInternal(tenantId, query);
    }

    @Override
    public SkitStablePageRespVO<SkitAdConsumptionRespVO> getGlobalPage(
            SkitAdConsumptionPageReqVO query) {
        return getPageInternal(null, query);
    }

    private SkitStablePageRespVO<SkitAdConsumptionRespVO> getPageInternal(
            Long tenantId, SkitAdConsumptionPageReqVO query) {
        QueryWindow window = window(query);
        Filter filter = filter(tenantId, query, window);
        Long total = jdbcTemplate.queryForObject("SELECT COUNT(*) " + SESSION_FROM
                        + LATEST_REVENUE_JOIN + filter.where,
                Long.class, filter.args.toArray());
        List<Object> args = new ArrayList<>(filter.args);
        args.add(query.getPageSize());
        args.add((query.getPageNo() - 1) * query.getPageSize());
        List<SkitAdConsumptionRespVO> list = jdbcTemplate.query(ROW_SELECT + SESSION_FROM
                        + LATEST_REVENUE_JOIN + PLAYER_GRANT_JOIN + filter.where
                        + " ORDER BY `s`.`create_time` DESC,`s`.`id` DESC LIMIT ? OFFSET ?",
                args.toArray(), (rs, rowNum) -> row(rs, new SkitAdConsumptionRespVO(), window.timezone));

        SkitStablePageRespVO<SkitAdConsumptionRespVO> response = new SkitStablePageRespVO<>();
        response.setTenantId(tenantId);
        response.setAsOf(window.asOf);
        response.setTimezone(window.timezone.getName());
        response.setPageNo(query.getPageNo());
        response.setPageSize(query.getPageSize());
        response.setTotal(total == null ? 0L : total);
        response.setList(list);
        return response;
    }

    @Override
    public SkitAdConsumptionSummaryRespVO getSummary(
            long tenantId, SkitAdConsumptionPageReqVO query) {
        return getSummaryInternal(tenantId, query);
    }

    @Override
    public SkitAdConsumptionSummaryRespVO getGlobalSummary(SkitAdConsumptionPageReqVO query) {
        return getSummaryInternal(null, query);
    }

    private SkitAdConsumptionSummaryRespVO getSummaryInternal(
            Long tenantId, SkitAdConsumptionPageReqVO query) {
        QueryWindow window = window(query);
        Filter filter = filter(tenantId, query, window);
        String lifecycleSql = "SELECT COUNT(*) AS `session_count`,"
                + "SUM(CASE WHEN EXISTS (SELECT 1 FROM `skit_ad_client_event` `shown` WHERE "
                + "`shown`.`tenant_id`=`s`.`tenant_id` AND `shown`.`ad_session_id`=`s`.`id` "
                + "AND `shown`.`event_type`='SHOWN' AND `shown`.`deleted`=b'0') THEN 1 ELSE 0 END) "
                + "AS `client_shown_count`,"
                + "SUM(CASE WHEN " + CLIENT_REWARD_OBSERVED + " "
                + "THEN 1 ELSE 0 END) AS `client_reward_observed_count`,"
                + "SUM(CASE WHEN `s`.`reward_verification_status`='SIGNED_VERIFIED' THEN 1 ELSE 0 END) "
                + "AS `signed_verified_count`,"
                + "SUM(CASE WHEN `s`.`reward_verification_status`='SIGNED_VERIFIED' AND "
                + CLIENT_REWARD_OBSERVED + " THEN 1 ELSE 0 END) "
                + "AS `signed_verified_and_client_observed_count`,"
                + "SUM(CASE WHEN `s`.`entitlement_status`='GRANTED' THEN 1 ELSE 0 END) "
                + "AS `entitled_count`,"
                + "SUM(CASE WHEN EXISTS (SELECT 1 FROM `skit_native_player_grant` `grant_fact` "
                + "WHERE `grant_fact`.`tenant_id`=`s`.`tenant_id` "
                + "AND `grant_fact`.`id`=`s`.`native_player_grant_id` "
                + "AND `grant_fact`.`member_id`=`s`.`member_id` "
                + "AND `grant_fact`.`drama_id`=`s`.`drama_id` "
                + "AND `grant_fact`.`deleted`=b'0') THEN 1 ELSE 0 END) "
                + "AS `native_grant_access_count`,"
                + "SUM(CASE WHEN COALESCE(`s`.`failure_reason`,'')"
                + "<>'CLIENT_CLOSED_UNREWARDED' AND ("
                + "`s`.`client_lifecycle_status` IN ('FAILED','LOAD_EXPIRED') "
                + "OR `s`.`reward_verification_status` IN ('REJECTED','VERIFY_TIMEOUT')) "
                + "THEN 1 ELSE 0 END) "
                + "AS `failed_count`,"
                + "SUM(CASE WHEN (`s`.`failure_reason`='CLIENT_CLOSED_UNREWARDED' OR "
                + "(`s`.`client_lifecycle_status`='CLOSED' "
                + "AND `s`.`reward_verification_status`='PENDING')) "
                + "AND NOT " + CLIENT_REWARD_OBSERVED + " THEN 1 ELSE 0 END) "
                + "AS `early_closed_count` " + SESSION_FROM + LATEST_REVENUE_JOIN + filter.where;
        List<Map<String, Object>> lifecycleRows = jdbcTemplate.queryForList(
                lifecycleSql, filter.args.toArray());

        String moneySql = "SELECT `e`.`source_currency`,`e`.`amount_scale`,COUNT(`e`.`id`) "
                + "AS `platform_impression_count`,SUM(`e`.`estimated_amount_units`) "
                + "AS `estimated_amount_units`,"
                + "SUM(CASE WHEN `e`.`reconciliation_status`='RECONCILED' THEN 1 ELSE 0 END) "
                + "AS `reconciled_impression_count`,"
                + "SUM(CASE WHEN `e`.`reconciliation_status`='RECONCILED' "
                + "THEN `e`.`reconciled_amount_units` ELSE NULL END) "
                + "AS `reconciled_amount_units` " + SESSION_FROM + LATEST_REVENUE_JOIN
                + filter.where + " AND `e`.`id` IS NOT NULL "
                + "GROUP BY `e`.`source_currency`,`e`.`amount_scale` "
                + "ORDER BY `e`.`source_currency`,`e`.`amount_scale`";
        List<Map<String, Object>> moneyRows = jdbcTemplate.queryForList(moneySql,
                filter.args.toArray());

        SkitAdConsumptionSummaryRespVO response = new SkitAdConsumptionSummaryRespVO();
        response.setTenantId(tenantId);
        response.setAsOf(window.asOf);
        response.setTimezone(window.timezone.getName());
        if (!lifecycleRows.isEmpty()) {
            Map<String, Object> values = lifecycleRows.get(0);
            response.setSessionCount(longValue(values.get("session_count")));
            response.setClientShownCount(longValue(values.get("client_shown_count")));
            response.setClientRewardObservedCount(longValue(values.get("client_reward_observed_count")));
            response.setSignedVerifiedCount(longValue(values.get("signed_verified_count")));
            response.setSignedVerifiedAndClientObservedCount(
                    longValue(values.get("signed_verified_and_client_observed_count")));
            response.setEntitledCount(longValue(values.get("entitled_count")));
            response.setNativeGrantAccessCount(longValue(values.get("native_grant_access_count")));
            response.setFailedCount(longValue(values.get("failed_count")));
            response.setEarlyClosedCount(longValue(values.get("early_closed_count")));
        }
        long platformImpressionCount = 0L;
        List<SkitAdConsumptionSummaryRespVO.CurrencyAmount> amounts = new ArrayList<>();
        for (Map<String, Object> values : moneyRows) {
            SkitAdConsumptionSummaryRespVO.CurrencyAmount amount =
                    new SkitAdConsumptionSummaryRespVO.CurrencyAmount();
            amount.setCurrency(stringValue(values.get("source_currency")));
            int scale = intValue(values.get("amount_scale"));
            long impressionCount = longValue(values.get("platform_impression_count"));
            long reconciledImpressionCount = longValue(values.get("reconciled_impression_count"));
            Long estimatedUnits = nullableLongValue(values.get("estimated_amount_units"));
            Long reconciledUnits = nullableLongValue(values.get("reconciled_amount_units"));
            amount.setAmountScale(scale);
            amount.setPlatformImpressionCount(impressionCount);
            amount.setEstimatedAmount(money(estimatedUnits, scale));
            amount.setReconciledAmount(money(reconciledUnits, scale));
            amount.setEstimatedEcpm(ecpm(estimatedUnits, scale, impressionCount));
            amount.setReconciledEcpm(ecpm(reconciledUnits, scale, reconciledImpressionCount));
            amounts.add(amount);
            platformImpressionCount += impressionCount;
        }
        response.setPlatformImpressionCount(platformImpressionCount);
        response.setCurrencyGroups(amounts);
        return response;
    }

    @Override
    public SkitAdConsumptionDetailRespVO get(
            long tenantId, long sessionRecordId, String timezone) {
        return getInternal(tenantId, sessionRecordId, timezone);
    }

    @Override
    public SkitAdConsumptionDetailRespVO getGlobal(long sessionRecordId, String timezone) {
        return getInternal(null, sessionRecordId, timezone);
    }

    private SkitAdConsumptionDetailRespVO getInternal(
            Long tenantId, long sessionRecordId, String timezone) {
        SkitManagementTimezone selectedTimezone = SkitManagementTimezone.of(timezone);
        String where = tenantId == null
                ? "WHERE `s`.`id`=? AND `s`.`deleted`=b'0'"
                : "WHERE `s`.`tenant_id`=? AND `s`.`id`=? AND `s`.`deleted`=b'0'";
        Object[] args = tenantId == null ? new Object[]{sessionRecordId}
                : new Object[]{tenantId, sessionRecordId};
        List<SkitAdConsumptionDetailRespVO> rows = jdbcTemplate.query(
                ROW_SELECT + SESSION_FROM + LATEST_REVENUE_JOIN + PLAYER_GRANT_JOIN
                        + where + " LIMIT 1",
                args, (rs, rowNum) -> row(rs, new SkitAdConsumptionDetailRespVO(), selectedTimezone));
        if (rows.isEmpty()) {
            throw exception(MANAGEMENT_RESOURCE_NOT_FOUND);
        }
        SkitAdConsumptionDetailRespVO detail = rows.get(0);
        detail.setAsOf(selectedTimezone.now(clock).withNano(0));
        detail.setTimezone(selectedTimezone.getName());
        detail.setTimeline(timeline(detail.getTenantId(), detail.getId(), selectedTimezone));
        return detail;
    }

    private List<SkitAdConsumptionDetailRespVO.TimelineItem> timeline(
            long tenantId, long sessionRecordId, SkitManagementTimezone timezone) {
        List<Object> args = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            args.add(tenantId);
            args.add(sessionRecordId);
        }
        return jdbcTemplate.query(timelineSql(), args.toArray(), (rs, rowNum) -> {
            SkitAdConsumptionDetailRespVO.TimelineItem item =
                    new SkitAdConsumptionDetailRespVO.TimelineItem();
            item.setId(rs.getLong("trace_id"));
            item.setSource(rs.getString("trace_source"));
            item.setEventType(rs.getString("event_type"));
            item.setStatus(rs.getString("event_status"));
            item.setErrorCode(rs.getString("error_code"));
            item.setSequenceNo(nullableInteger(rs, "sequence_no"));
            item.setEpisodeNo(nullableInteger(rs, "episode_no"));
            item.setOccurredAt(timezone.fromDatabase(dateTime(rs.getTimestamp("occurred_at"))));
            return item;
        });
    }

    static String timelineSql() {
        return "SELECT `trace_id`,`trace_source`,`event_type`,`event_status`,`error_code`,"
                + "`sequence_no`,`episode_no`,`occurred_at` FROM ("
                + "SELECT `s`.`id` AS `trace_id`,'SESSION' AS `trace_source`,"
                + "'SESSION_CREATED' AS `event_type`,'CREATED' AS `event_status`,"
                + "NULL AS `error_code`,-1 AS `sequence_no`,NULL AS `episode_no`,"
                + "`s`.`create_time` AS `occurred_at` FROM `skit_ad_session` `s` "
                + "WHERE `s`.`tenant_id`=? AND `s`.`id`=? AND `s`.`deleted`=b'0' UNION ALL "
                + "SELECT `ce`.`id`,'CLIENT',`ce`.`event_type`,`ce`.`native_state`,NULL,"
                + "`ce`.`callback_sequence`,NULL,`ce`.`occurred_at` FROM `skit_ad_client_event` `ce` "
                + "WHERE `ce`.`tenant_id`=? AND `ce`.`ad_session_id`=? AND `ce`.`deleted`=b'0' UNION ALL "
                + "SELECT `i`.`id`,'CALLBACK',`i`.`callback_type`,`i`.`processing_status`,"
                + "`i`.`error_code`,NULL,NULL,`i`.`received_at` FROM `skit_ad_callback_inbox` `i` "
                + "WHERE `i`.`tenant_id`=? AND `i`.`ad_session_id`=? AND `i`.`deleted`=b'0' UNION ALL "
                + "SELECT `a`.`id`,'CALLBACK_ATTEMPT','CALLBACK_ATTEMPT',`a`.`result_code`,"
                + "`i`.`error_code`,`a`.`attempt_no`,NULL,`a`.`received_at` "
                + "FROM `skit_ad_callback_attempt` `a` JOIN `skit_ad_callback_inbox` `i` "
                + "ON `i`.`tenant_id`=`a`.`tenant_id` AND `i`.`id`=`a`.`callback_inbox_id` "
                + "WHERE `a`.`tenant_id`=? AND `i`.`ad_session_id`=? AND `a`.`deleted`=b'0' "
                + "AND `i`.`deleted`=b'0' UNION ALL "
                + "SELECT `s`.`id`,'SERVER','REWARD_VERIFIED',`s`.`reward_verification_status`,"
                + "NULL,NULL,NULL,`s`.`reward_verified_at` FROM `skit_ad_session` `s` "
                + "WHERE `s`.`tenant_id`=? AND `s`.`id`=? AND `s`.`reward_verified_at` IS NOT NULL "
                + "AND `s`.`deleted`=b'0' UNION ALL "
                + "SELECT `eg`.`id`,'ENTITLEMENT','ENTITLEMENT_GRANTED',`eg`.`grant_result`,"
                + "NULL,NULL,`eg`.`episode_no`,`eg`.`granted_at` FROM `skit_entitlement_grant` `eg` "
                + "WHERE `eg`.`tenant_id`=? AND `eg`.`ad_session_id`=? AND `eg`.`deleted`=b'0' UNION ALL "
                + "SELECT `g`.`id`,'PLAYER_GRANT','NATIVE_GRANT_REFERENCED',`g`.`status`,NULL,NULL,NULL,"
                + "`g`.`create_time` FROM `skit_native_player_grant` `g` JOIN `skit_ad_session` `s` "
                + "ON `s`.`tenant_id`=`g`.`tenant_id` AND `s`.`native_player_grant_id`=`g`.`id` "
                + "WHERE `s`.`tenant_id`=? AND `s`.`id`=? AND `s`.`deleted`=b'0' "
                + "AND `g`.`deleted`=b'0' UNION ALL "
                + "SELECT `g`.`id`,'PLAYER_GRANT','NATIVE_GRANT_USED',`g`.`status`,NULL,NULL,NULL,"
                + "`g`.`update_time` FROM `skit_native_player_grant` `g` JOIN `skit_ad_session` `s` "
                + "ON `s`.`tenant_id`=`g`.`tenant_id` AND `s`.`native_player_grant_id`=`g`.`id` "
                + "WHERE `s`.`tenant_id`=? AND `s`.`id`=? AND `s`.`deleted`=b'0' "
                + "AND `g`.`updater`='native-player-use' AND `g`.`deleted`=b'0') `trace` "
                + "ORDER BY `occurred_at`,`sequence_no`,`trace_id` LIMIT 1000";
    }

    private <T extends SkitAdConsumptionRespVO> T row(
            ResultSet rs, T result, SkitManagementTimezone timezone) throws SQLException {
        result.setTenantId(rs.getLong("tenant_id"));
        result.setId(rs.getLong("id"));
        result.setSessionId(rs.getString("session_id"));
        result.setMemberId(rs.getLong("member_id"));
        result.setMemberNickname(rs.getString("member_nickname"));
        result.setMemberMobileMasked(maskMobile(rs.getString("member_mobile_masked")));
        result.setDramaId(rs.getLong("drama_id"));
        result.setEpisodeFrom(rs.getInt("episode_from"));
        result.setEpisodeTo(rs.getInt("episode_to"));
        result.setAdAccountId(rs.getLong("ad_account_id"));
        result.setProvider(rs.getString("provider"));
        result.setPlacementId(rs.getString("placement_id"));
        result.setNetworkFirmId(nullableInteger(rs, "network_firm_id"));
        result.setAdsourceId(rs.getString("adsource_id"));
        result.setSdkRequestId(rs.getString("sdk_request_id"));
        result.setProviderShowId(rs.getString("provider_show_id"));
        result.setProviderTransactionId(rs.getString("provider_transaction_id"));
        result.setConsumptionStatus(rs.getString("consumption_status"));
        result.setStatus(result.getConsumptionStatus());
        result.setClientLifecycleStatus(rs.getString("client_lifecycle_status"));
        result.setRewardVerificationStatus(rs.getString("reward_verification_status"));
        result.setEntitlementStatus(rs.getString("entitlement_status"));
        result.setRevenueStatus(rs.getString("revenue_status"));
        result.setFailureReason(rs.getString("failure_reason"));
        result.setCurrency(rs.getString("source_currency"));
        int scale = nullableInteger(rs, "amount_scale") == null ? 0 : rs.getInt("amount_scale");
        Long estimatedUnits = nullableLong(rs, "estimated_amount_units");
        Long reconciledUnits = nullableLong(rs, "reconciled_amount_units");
        result.setEstimatedAmount(money(estimatedUnits, scale));
        result.setReconciledAmount(money(reconciledUnits, scale));
        result.setEstimatedEcpm(ecpm(estimatedUnits, scale, 1L));
        result.setReconciledEcpm(ecpm(reconciledUnits, scale, 1L));
        result.setCreatedAt(timezone.fromDatabase(dateTime(rs.getTimestamp("create_time"))));
        result.setRequestedAt(result.getCreatedAt());
        result.setLastEventAt(timezone.fromDatabase(dateTime(rs.getTimestamp("last_event_at"))));
        result.setEpisodeNo(result.getEpisodeFrom());
        result.setPlayerAccessEvidence(nullableLong(rs, "native_grant_evidence_id") == null
                ? "NOT_TRACKED" : "NATIVE_GRANT_REFERENCED");
        result.setRewardVerifiedAt(timezone.fromDatabase(
                dateTime(rs.getTimestamp("reward_verified_at"))));
        result.setEntitledAt(timezone.fromDatabase(dateTime(rs.getTimestamp("entitled_at"))));
        return result;
    }

    static String maskMobile(String mobile) {
        if (mobile == null || mobile.isEmpty() || mobile.indexOf('*') >= 0) {
            return mobile;
        }
        if (mobile.length() <= 7) {
            return mobile.substring(0, Math.min(2, mobile.length())) + "***";
        }
        return mobile.substring(0, 3) + "****" + mobile.substring(mobile.length() - 4);
    }

    private QueryWindow window(SkitAdConsumptionPageReqVO query) {
        Objects.requireNonNull(query, "query");
        SkitManagementTimezone timezone = SkitManagementTimezone.of(query.getTimezone());
        LocalDateTime asOf = timezone.now(clock).withNano(0);
        LocalDateTime end = query.getEndTime() == null || query.getEndTime().isAfter(asOf)
                ? asOf : query.getEndTime();
        LocalDateTime start = query.getStartTime() == null ? end.minus(DEFAULT_RANGE)
                : query.getStartTime();
        if (!start.isBefore(end) || Duration.between(start, end).compareTo(MAX_RANGE) > 0) {
            throw exception(MANAGEMENT_QUERY_RANGE_INVALID);
        }
        return new QueryWindow(timezone, asOf, timezone.toDatabase(start),
                timezone.toDatabase(end));
    }

    private Filter filter(Long tenantId, SkitAdConsumptionPageReqVO query, QueryWindow window) {
        StringBuilder where = new StringBuilder("WHERE ");
        List<Object> args = new ArrayList<>();
        if (tenantId != null) {
            where.append("`s`.`tenant_id`=? AND ");
            args.add(tenantId);
        }
        where.append("`s`.`create_time`>=? AND `s`.`create_time`<? AND `s`.`deleted`=b'0'");
        args.add(window.startDatabase);
        args.add(window.endDatabase);
        append(where, args, "`s`.`drama_id`", query.getDramaId());
        if (query.getEpisodeNo() != null) {
            where.append(" AND `s`.`episode_from`<=? AND `s`.`episode_to`>=?");
            args.add(query.getEpisodeNo());
            args.add(query.getEpisodeNo());
        }
        append(where, args, "`s`.`provider`", normalize(query.getProvider()));
        append(where, args, "`s`.`network_firm_id`", query.getNetworkFirmId());
        appendStatus(where, args, normalize(query.getStatus()));
        String memberKeyword = normalize(query.getMemberKeyword());
        if (memberKeyword != null) {
            where.append(" AND (CAST(`s`.`member_id` AS CHAR)=? OR `m`.`mobile` LIKE ? "
                    + "OR `m`.`nickname` LIKE ? OR `s`.`pseudonymous_user_id`=?)");
            args.add(memberKeyword);
            args.add(mobileLikePattern(memberKeyword));
            args.add("%" + memberKeyword + "%");
            args.add(memberKeyword);
        }
        append(where, args,
                "COALESCE(NULLIF(`s`.`provider_transaction_id`,''),`e`.`provider_transaction_id`)",
                normalize(query.getProviderTransactionId()));
        return new Filter(" " + where, args);
    }

    private static String mobileLikePattern(String memberKeyword) {
        Matcher matcher = MASKED_MOBILE_SEARCH_PATTERN.matcher(memberKeyword);
        if (matcher.matches()) {
            return matcher.group(1) + "%" + matcher.group(2);
        }
        return "%" + memberKeyword + "%";
    }

    private void appendStatus(StringBuilder where, List<Object> args, String status) {
        if (status == null) {
            return;
        }
        switch (status) {
            case "UNLOCKED":
            case "VERIFY_TIMEOUT":
            case "REWARD_REJECTED":
            case "REWARD_VERIFIED":
            case "REWARD_OBSERVED":
            case "LOAD_STARTED":
            case "CREATED":
            case "SHOWN":
            case "CLOSED":
            case "FAILED":
            case "LOAD_EXPIRED":
                break;
            default:
                throw new IllegalArgumentException("Unsupported ad consumption status: " + status);
        }
        where.append(" AND (").append(CONSUMPTION_STATUS).append(")=?");
        args.add(status);
    }

    private void append(StringBuilder sql, List<Object> args, String column, Object value) {
        if (value != null && !value.toString().trim().isEmpty()) {
            sql.append(" AND ").append(column).append("=?");
            args.add(value);
        }
    }

    private String normalize(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        return value.trim();
    }

    private Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private Integer nullableInteger(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private LocalDateTime dateTime(Timestamp value) {
        return value == null ? null : value.toLocalDateTime();
    }

    private static String money(Long units, int scale) {
        if (units == null) {
            return null;
        }
        return decimal(BigDecimal.valueOf(units, scale));
    }

    private static String ecpm(Long units, int scale, long impressionCount) {
        if (units == null || impressionCount <= 0) {
            return null;
        }
        BigDecimal value = BigDecimal.valueOf(units, scale)
                .multiply(BigDecimal.valueOf(1000L))
                .divide(BigDecimal.valueOf(impressionCount), Math.max(scale, 6), RoundingMode.HALF_UP);
        return decimal(value);
    }

    private static String decimal(BigDecimal value) {
        BigDecimal normalized = value.stripTrailingZeros();
        return normalized.scale() < 0 ? normalized.setScale(0).toPlainString()
                : normalized.toPlainString();
    }

    private long longValue(Object value) {
        return value == null ? 0L : ((Number) value).longValue();
    }

    private Long nullableLongValue(Object value) {
        return value == null ? null : ((Number) value).longValue();
    }

    private int intValue(Object value) {
        return value == null ? 0 : ((Number) value).intValue();
    }

    private String stringValue(Object value) {
        return value == null ? null : value.toString();
    }

    private static final class QueryWindow {
        private final SkitManagementTimezone timezone;
        private final LocalDateTime asOf;
        private final LocalDateTime startDatabase;
        private final LocalDateTime endDatabase;

        private QueryWindow(SkitManagementTimezone timezone, LocalDateTime asOf,
                            LocalDateTime startDatabase, LocalDateTime endDatabase) {
            this.timezone = timezone;
            this.asOf = asOf;
            this.startDatabase = startDatabase;
            this.endDatabase = endDatabase;
        }
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
