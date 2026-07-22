package cn.iocoder.yudao.module.skit.service.analytics;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkitAdConsumptionQueryServiceTest {

    private static final String SERVICE_NAME = "cn.iocoder.yudao.module.skit.service.analytics."
            + "SkitAdConsumptionQueryServiceImpl";
    private static final String QUERY_NAME = "cn.iocoder.yudao.module.skit.controller.admin.tenant.vo."
            + "SkitAdConsumptionPageReqVO";

    @Test
    void sessionCentricManagementServiceExists() {
        assertNotNull(loadClass(SERVICE_NAME),
                "Every ad session, including no-fill and early-close sessions, needs a real management query");
    }

    @Test
    void pageStartsFromAdSessionAndKeepsRevenueOptional() throws Exception {
        RecordingJdbcTemplate jdbc = new RecordingJdbcTemplate();
        Object service = newService(jdbc);
        Object query = newQuery();

        invoke(service, "getPage", new Class<?>[]{long.class, query.getClass()}, 42L, query);

        SqlCall count = jdbc.calls.get(0);
        assertTrue(count.sql.startsWith("SELECT COUNT(*) FROM `skit_ad_session` `s`"), count.sql);
        assertTrue(count.sql.contains("`s`.`tenant_id`=?"), count.sql);
        assertTrue(count.sql.contains("`s`.`deleted`=b'0'"), count.sql);
        SqlCall page = jdbc.calls.get(1);
        assertTrue(page.sql.contains("LEFT JOIN `skit_ad_revenue_event` `e`"), page.sql);
        assertTrue(page.sql.contains("`e`.`tenant_id`=`s`.`tenant_id`"), page.sql);
        assertTrue(page.sql.contains("`e`.`ad_session_id`=`s`.`id`"), page.sql);
        assertTrue(page.sql.contains("`e`.`legacy_unverified`=b'0'"), page.sql);
        assertTrue(page.sql.contains("`e`.`mock`=b'0'"), page.sql);
        assertTrue(page.sql.contains("LEFT JOIN `skit_native_player_grant` `g`"), page.sql);
        assertTrue(page.sql.contains("`g`.`tenant_id`=`s`.`tenant_id`"), page.sql);
        assertTrue(page.sql.contains("`g`.`id`=`s`.`native_player_grant_id`"), page.sql);
        assertTrue(page.sql.contains("`g`.`member_id`=`s`.`member_id`"), page.sql);
        assertTrue(page.sql.contains("`g`.`drama_id`=`s`.`drama_id`"), page.sql);
        assertFalse(page.sql.contains("JOIN `skit_ad_revenue_event` `e` ON")
                && !page.sql.contains("LEFT JOIN `skit_ad_revenue_event` `e` ON"), page.sql);
    }

    @Test
    void sessionWithoutRevenueStillMapsAsOneConsumptionWithNullMoney() throws Exception {
        SingleSessionJdbcTemplate jdbc = new SingleSessionJdbcTemplate(sessionWithoutRevenue());
        Object service = newService(jdbc);
        Object query = newQuery();

        Object page = invoke(service, "getPage", new Class<?>[]{long.class, query.getClass()},
                42L, query);

        assertEquals(1L, page.getClass().getMethod("getTotal").invoke(page));
        List<?> rows = (List<?>) page.getClass().getMethod("getList").invoke(page);
        assertEquals(1, rows.size());
        Object row = rows.get(0);
        assertEquals("session-no-revenue", row.getClass().getMethod("getSessionId").invoke(row));
        assertEquals("FAILED", row.getClass().getMethod("getConsumptionStatus").invoke(row));
        assertEquals("FAILED", row.getClass().getMethod("getStatus").invoke(row));
        assertEquals(41, row.getClass().getMethod("getEpisodeNo").invoke(row));
        assertEquals(row.getClass().getMethod("getCreatedAt").invoke(row),
                row.getClass().getMethod("getRequestedAt").invoke(row));
        assertNotNull(row.getClass().getMethod("getLastEventAt").invoke(row));
        assertEquals("199****4550", row.getClass().getMethod("getMemberMobileMasked").invoke(row));
        assertNull(row.getClass().getMethod("getEstimatedAmount").invoke(row));
        assertNull(row.getClass().getMethod("getReconciledAmount").invoke(row));
        assertEquals("NOT_TRACKED", row.getClass().getMethod("getPlayerAccessEvidence").invoke(row));
    }

    @Test
    void playerGrantStatusRequiresARealTenantBoundGrantRow() throws Exception {
        Map<String, Object> values = sessionWithoutRevenue();
        values.put("native_grant_evidence_id", 700L);
        SingleSessionJdbcTemplate jdbc = new SingleSessionJdbcTemplate(values);
        Object service = newService(jdbc);
        Object query = newQuery();

        Object page = invoke(service, "getPage", new Class<?>[]{long.class, query.getClass()},
                42L, query);

        List<?> rows = (List<?>) page.getClass().getMethod("getList").invoke(page);
        assertEquals("NATIVE_GRANT_REFERENCED",
                rows.get(0).getClass().getMethod("getPlayerAccessEvidence").invoke(rows.get(0)));
    }

    @Test
    void pageSupportsOperationalFiltersWithoutDroppingTenantScope() throws Exception {
        RecordingJdbcTemplate jdbc = new RecordingJdbcTemplate();
        Object service = newService(jdbc);
        Object query = newQuery();
        set(query, "setStartTime", LocalDateTime.class, LocalDateTime.of(2026, 7, 20, 0, 0));
        set(query, "setEndTime", LocalDateTime.class, LocalDateTime.of(2026, 7, 21, 0, 0));
        set(query, "setDramaId", Long.class, 9001L);
        set(query, "setEpisodeNo", Integer.class, 41);
        set(query, "setProvider", String.class, "TAKU");
        set(query, "setNetworkFirmId", Integer.class, 66);
        set(query, "setStatus", String.class, "UNLOCKED");
        set(query, "setMemberKeyword", String.class, "199****4550");
        set(query, "setProviderTransactionId", String.class, "tx-41");

        invoke(service, "getPage", new Class<?>[]{long.class, query.getClass()}, 42L, query);

        String sql = jdbc.calls.get(0).sql;
        assertTrue(sql.contains("`s`.`tenant_id`=?"), sql);
        assertTrue(sql.contains("`s`.`create_time`>=?"), sql);
        assertTrue(sql.contains("`s`.`create_time`<?"), sql);
        assertTrue(sql.contains("`s`.`drama_id`=?"), sql);
        assertTrue(sql.contains("`s`.`episode_from`<=? AND `s`.`episode_to`>=?"), sql);
        assertTrue(sql.contains("`s`.`provider`=?"), sql);
        assertTrue(sql.contains("`s`.`network_firm_id`=?"), sql);
        assertTrue(sql.contains("AND (CASE"), sql);
        assertTrue(sql.contains("`m`.`mobile` LIKE ?"), sql);
        assertTrue(sql.contains("`m`.`nickname` LIKE ?"), sql);
        assertTrue(sql.contains("COALESCE(NULLIF(`s`.`provider_transaction_id`,''),"
                + "`e`.`provider_transaction_id`)=?"), sql);
        assertFalse(sql.contains("199%4550"), "masked search must remain a prepared parameter");
        assertEquals("199****4550", jdbc.calls.get(0).args[9]);
        assertEquals("199%4550", jdbc.calls.get(0).args[10]);
        assertEquals("%199****4550%", jdbc.calls.get(0).args[11]);
        assertEquals("199****4550", jdbc.calls.get(0).args[12]);
    }

    @Test
    void ordinaryMemberKeywordKeepsExistingContainsSearch() throws Exception {
        RecordingJdbcTemplate jdbc = new RecordingJdbcTemplate();
        Object service = newService(jdbc);
        Object query = newQuery();
        set(query, "setMemberKeyword", String.class, "测试会员");

        invoke(service, "getPage", new Class<?>[]{long.class, query.getClass()}, 42L, query);

        SqlCall count = jdbc.calls.get(0);
        assertEquals("测试会员", count.args[3]);
        assertEquals("%测试会员%", count.args[4]);
        assertEquals("%测试会员%", count.args[5]);
        assertEquals("测试会员", count.args[6]);
    }

    @Test
    void globalMaskedKeywordUsesTheSamePreparedMobilePattern() throws Exception {
        RecordingJdbcTemplate jdbc = new RecordingJdbcTemplate();
        Object service = newService(jdbc);
        Object query = newQuery();
        set(query, "setMemberKeyword", String.class, "199****4550");

        invoke(service, "getGlobalPage", new Class<?>[]{query.getClass()}, query);

        SqlCall count = jdbc.calls.get(0);
        assertEquals("199****4550", count.args[2]);
        assertEquals("199%4550", count.args[3]);
        assertEquals("%199****4550%", count.args[4]);
        assertEquals("199****4550", count.args[5]);
    }

    @Test
    void rowsExposeLifecycleAndMaskedIdentityButNeverClaimPlayerLaunch() throws Exception {
        RecordingJdbcTemplate jdbc = new RecordingJdbcTemplate();
        Object service = newService(jdbc);
        Object query = newQuery();

        invoke(service, "getGlobalPage", new Class<?>[]{query.getClass()}, query);

        String sql = jdbc.calls.get(1).sql;
        assertTrue(sql.contains("`s`.`drama_id`"), sql);
        assertTrue(sql.contains("`s`.`episode_from`"), sql);
        assertTrue(sql.contains("`s`.`episode_to`"), sql);
        assertTrue(sql.contains("`s`.`client_lifecycle_status`"), sql);
        assertTrue(sql.contains("`s`.`reward_verification_status`"), sql);
        assertTrue(sql.contains("`s`.`entitlement_status`"), sql);
        assertTrue(sql.contains("`s`.`revenue_status`"), sql);
        assertTrue(sql.contains("AS `member_mobile_masked`"), sql);
        assertFalse(sql.contains("`m`.`password`"), sql);
        Method mask = requireClass(SERVICE_NAME).getDeclaredMethod("maskMobile", String.class);
        mask.setAccessible(true);
        assertEquals("199****4550", mask.invoke(null, "19984214550"));
        assertEquals("13***", mask.invoke(null, "13800"));
        assertEquals("", mask.invoke(null, ""));

        Class<?> rowType = requireClass("cn.iocoder.yudao.module.skit.controller.admin.tenant.vo."
                + "SkitAdConsumptionRespVO");
        Object row = rowType.getDeclaredConstructor().newInstance();
        assertEquals("NOT_TRACKED", rowType.getMethod("getPlayerAccessEvidence").invoke(row));
    }

    @Test
    void failedLifecycleRemainsVisibleThroughTheFailedStatusFilterAfterRewardObservation() throws Exception {
        RecordingJdbcTemplate jdbc = new RecordingJdbcTemplate();
        Object service = newService(jdbc);
        Object query = newQuery();

        invoke(service, "getGlobalPage", new Class<?>[]{query.getClass()}, query);

        String sql = jdbc.calls.get(1).sql;
        int failed = sql.indexOf("WHEN `s`.`client_lifecycle_status`='FAILED' THEN 'FAILED'");
        int rewardObserved = sql.indexOf("THEN 'REWARD_OBSERVED'");
        assertTrue(failed >= 0, sql);
        assertTrue(rewardObserved >= 0, sql);
        assertTrue(failed < rewardObserved,
                "failedCount must drill down through the FAILED list filter even after reward observation");
    }

    @Test
    void summarySeparatesEstimatedAndSettledMoneyWithoutInventingLaunchRate() throws Exception {
        RecordingJdbcTemplate jdbc = new RecordingJdbcTemplate();
        Object service = newService(jdbc);
        Object query = newQuery();

        invoke(service, "getSummary", new Class<?>[]{long.class, query.getClass()}, 42L, query);

        assertEquals(2, jdbc.calls.size());
        String lifecycleSql = jdbc.calls.get(0).sql;
        assertTrue(lifecycleSql.contains("COUNT(*) AS `session_count`"), lifecycleSql);
        assertTrue(lifecycleSql.contains("AS `client_shown_count`"), lifecycleSql);
        assertTrue(lifecycleSql.contains("AS `signed_verified_count`"), lifecycleSql);
        assertTrue(lifecycleSql.contains("AS `signed_verified_and_client_observed_count`"), lifecycleSql);
        assertTrue(lifecycleSql.contains("`s`.`reward_verification_status`='SIGNED_VERIFIED' AND EXISTS"),
                lifecycleSql);
        assertTrue(lifecycleSql.contains("AS `entitled_count`"), lifecycleSql);
        assertTrue(lifecycleSql.contains("AS `native_grant_access_count`"), lifecycleSql);
        assertTrue(lifecycleSql.contains("FROM `skit_native_player_grant` `grant_fact`"), lifecycleSql);
        assertTrue(lifecycleSql.contains("`grant_fact`.`tenant_id`=`s`.`tenant_id`"), lifecycleSql);
        assertTrue(lifecycleSql.contains("`grant_fact`.`id`=`s`.`native_player_grant_id`"), lifecycleSql);
        assertTrue(lifecycleSql.contains("AS `failed_count`"), lifecycleSql);
        assertTrue(lifecycleSql.contains("AS `early_closed_count`"), lifecycleSql);
        assertTrue(lifecycleSql.contains("COALESCE(`s`.`failure_reason`,'')"
                + "<>'CLIENT_CLOSED_UNREWARDED'"), lifecycleSql);
        assertTrue(lifecycleSql.contains("(`s`.`failure_reason`='CLIENT_CLOSED_UNREWARDED' OR "
                + "(`s`.`client_lifecycle_status`='CLOSED' "
                + "AND `s`.`reward_verification_status`='PENDING'))"), lifecycleSql);
        assertTrue(lifecycleSql.contains("AND NOT EXISTS (SELECT 1 FROM "
                + "`skit_ad_client_event` `rewarded`"), lifecycleSql);
        assertFalse(lifecycleSql.contains("PLAYER_OPENED"), lifecycleSql);
        assertFalse(lifecycleSql.contains("PLAYER_LAUNCHED"), lifecycleSql);

        Class<?> summaryType = requireClass("cn.iocoder.yudao.module.skit.controller.admin.tenant.vo."
                + "SkitAdConsumptionSummaryRespVO");
        for (String getter : new String[]{"getSessionCount", "getClientShownCount",
                "getSignedVerifiedCount", "getSignedVerifiedAndClientObservedCount",
                "getEntitledCount", "getNativeGrantAccessCount",
                "getFailedCount", "getCurrencyGroups"}) {
            assertNotNull(summaryType.getMethod(getter), getter);
        }

        String moneySql = jdbc.calls.get(1).sql;
        assertTrue(moneySql.contains("SUM(`e`.`estimated_amount_units`)"), moneySql);
        assertTrue(moneySql.contains("`e`.`reconciliation_status`='RECONCILED'"), moneySql);
        assertTrue(moneySql.contains("AS `reconciled_impression_count`"), moneySql);
        assertTrue(moneySql.contains("GROUP BY `e`.`source_currency`,`e`.`amount_scale`"), moneySql);
        assertTrue(moneySql.contains("`e`.`legacy_unverified`=b'0'"), moneySql);
        assertTrue(moneySql.contains("`e`.`mock`=b'0'"), moneySql);
    }

    @Test
    void detailTimelineUsesOnlyObservedFactsAndIsTenantBound() throws Exception {
        Class<?> service = requireClass(SERVICE_NAME);
        Method timeline = service.getDeclaredMethod("timelineSql");
        timeline.setAccessible(true);
        String sql = (String) timeline.invoke(null);

        assertTrue(sql.contains("'SESSION_CREATED' AS `event_type`,'CREATED' AS `event_status`"), sql);
        assertFalse(sql.contains("'SESSION_CREATED' AS `event_type`,`s`.`client_lifecycle_status` "
                + "AS `event_status`"), sql);
        assertTrue(sql.contains("FROM `skit_ad_client_event`"), sql);
        assertTrue(sql.contains("FROM `skit_ad_callback_inbox`"), sql);
        assertTrue(sql.contains("FROM `skit_ad_callback_attempt`"), sql);
        assertTrue(sql.contains("WHERE `a`.`tenant_id`=? AND `i`.`ad_session_id`=?"), sql);
        assertFalse(sql.contains("WHERE `a`.`tenant_id`=? AND `a`.`ad_session_id`=?"), sql);
        assertTrue(sql.contains("FROM `skit_entitlement_grant`"), sql);
        assertTrue(sql.contains("FROM `skit_native_player_grant`"), sql);
        assertTrue(sql.contains("NATIVE_GRANT_REFERENCED"), sql);
        assertTrue(sql.contains("NATIVE_GRANT_USED"), sql);
        assertFalse(sql.contains("PLAYER_OPENED"), sql);
        assertFalse(sql.contains("PLAYER_LAUNCHED"), sql);
        assertTrue(countOccurrences(sql, "`tenant_id`=?") >= 5, sql);
        assertTrue(sql.contains("ORDER BY `occurred_at`,`sequence_no`,`trace_id`"), sql);
    }

    @Test
    void tenantDetailAlwaysBindsTenantBeforeSessionId() throws Exception {
        RecordingJdbcTemplate jdbc = new RecordingJdbcTemplate();
        Object service = newService(jdbc);

        InvocationTargetException thrown = assertThrows(InvocationTargetException.class,
                () -> invoke(service, "get", new Class<?>[]{long.class, long.class, String.class},
                        42L, 99L, "UTC+8"));

        assertNotNull(thrown.getCause());
        String sql = jdbc.calls.get(0).sql;
        assertTrue(sql.contains("WHERE `s`.`tenant_id`=? AND `s`.`id`=?"), sql);
        assertEquals(42L, jdbc.calls.get(0).args[0]);
        assertEquals(99L, jdbc.calls.get(0).args[1]);
    }

    @Test
    void amountFormattingPreservesExactUnitsAndSeparatesEstimateFromSettlement() throws Exception {
        Method money = requireClass(SERVICE_NAME).getDeclaredMethod("money", Long.class, int.class);
        Method ecpm = requireClass(SERVICE_NAME).getDeclaredMethod(
                "ecpm", Long.class, int.class, long.class);
        money.setAccessible(true);
        ecpm.setAccessible(true);

        assertEquals("1.2345", money.invoke(null, 12345L, 4));
        assertEquals("1234.5", ecpm.invoke(null, 12345L, 4, 1L));
        assertEquals("617.25", ecpm.invoke(null, 12345L, 4, 2L));
        assertNull(money.invoke(null, null, 4));
    }

    @Test
    void responseSchemaNeverExposesRawIdentitySecretsOrPayloads() {
        Class<?> row = requireClass("cn.iocoder.yudao.module.skit.controller.admin.tenant.vo."
                + "SkitAdConsumptionRespVO");
        Class<?> detail = requireClass("cn.iocoder.yudao.module.skit.controller.admin.tenant.vo."
                + "SkitAdConsumptionDetailRespVO");
        for (Class<?> type : new Class<?>[]{row, detail}) {
            for (Class<?> cursor = type; cursor != null; cursor = cursor.getSuperclass()) {
                for (Field field : cursor.getDeclaredFields()) {
                    String name = field.getName().toLowerCase();
                    assertFalse(name.equals("mobile") || name.contains("password")
                                    || name.contains("token") || name.contains("secret")
                                    || name.contains("rawdata") || name.contains("payload")
                                    || name.contains("provideruserid"),
                            type.getSimpleName() + "." + field.getName());
                    if (name.contains("mobile")) {
                        assertTrue(name.contains("masked"), field.getName());
                    }
                }
            }
        }
    }

    @Test
    void managementClassesDoNotBypassTenantPlugin() {
        for (String name : new String[]{SERVICE_NAME,
                "cn.iocoder.yudao.module.skit.controller.admin.tenant.SkitAdConsumptionController"}) {
            Class<?> type = requireClass(name);
            for (Annotation annotation : type.getAnnotations()) {
                assertFalse("TenantIgnore".equals(annotation.annotationType().getSimpleName()), name);
            }
            for (Method method : type.getDeclaredMethods()) {
                for (Annotation annotation : method.getAnnotations()) {
                    assertFalse("TenantIgnore".equals(annotation.annotationType().getSimpleName()),
                            name + "#" + method.getName());
                }
            }
        }
    }

    @Test
    void controllerPublishesGuardedSummaryPageAndDetailRoutes() throws Exception {
        Class<?> controller = requireClass(
                "cn.iocoder.yudao.module.skit.controller.admin.tenant.SkitAdConsumptionController");
        RequestMapping mapping = controller.getAnnotation(RequestMapping.class);
        assertNotNull(mapping);
        assertEquals("/skit/tenant/ad-consumptions", mapping.value()[0]);
        PreAuthorize authorize = controller.getAnnotation(PreAuthorize.class);
        assertNotNull(authorize);
        assertTrue(authorize.value().contains("tenant_admin"));
        assertTrue(authorize.value().contains("super_admin"));
        for (String methodName : new String[]{"getSummary", "getPage", "get"}) {
            Method method = null;
            for (Method candidate : controller.getDeclaredMethods()) {
                if (methodName.equals(candidate.getName())) {
                    method = candidate;
                    break;
                }
            }
            assertNotNull(method, methodName);
            GetMapping get = method.getAnnotation(GetMapping.class);
            assertNotNull(get, methodName);
            assertEquals(methodName.equals("getSummary") ? "/summary"
                    : methodName.equals("getPage") ? "/page" : "/get", get.value()[0]);
        }
    }

    private Object newService(JdbcTemplate jdbc) throws Exception {
        Class<?> type = requireClass(SERVICE_NAME);
        Constructor<?> constructor = type.getDeclaredConstructor(JdbcTemplate.class, Clock.class);
        constructor.setAccessible(true);
        return constructor.newInstance(jdbc,
                Clock.fixed(Instant.parse("2026-07-23T03:00:00Z"), ZoneOffset.UTC));
    }

    private Object newQuery() throws Exception {
        return requireClass(QUERY_NAME).getDeclaredConstructor().newInstance();
    }

    private void set(Object target, String method, Class<?> parameterType, Object value) throws Exception {
        target.getClass().getMethod(method, parameterType).invoke(target, value);
    }

    private Object invoke(Object target, String method, Class<?>[] parameterTypes,
                          Object... args) throws Exception {
        return target.getClass().getMethod(method, parameterTypes).invoke(target, args);
    }

    private Class<?> requireClass(String name) {
        Class<?> type = loadClass(name);
        assertNotNull(type, name);
        return type;
    }

    private int countOccurrences(String source, String token) {
        int count = 0;
        int offset = 0;
        while ((offset = source.indexOf(token, offset)) >= 0) {
            count++;
            offset += token.length();
        }
        return count;
    }

    private Map<String, Object> sessionWithoutRevenue() {
        Map<String, Object> row = new java.util.LinkedHashMap<>();
        row.put("tenant_id", 42L);
        row.put("id", 99L);
        row.put("session_id", "session-no-revenue");
        row.put("member_id", 7L);
        row.put("member_nickname", "测试会员");
        row.put("member_mobile_masked", "199****4550");
        row.put("drama_id", 9001L);
        row.put("episode_from", 41);
        row.put("episode_to", 41);
        row.put("ad_account_id", 6L);
        row.put("provider", "TAKU");
        row.put("placement_id", "placement-1");
        row.put("network_firm_id", 66);
        row.put("adsource_id", "source-1");
        row.put("sdk_request_id", "request-1");
        row.put("provider_show_id", null);
        row.put("provider_transaction_id", null);
        row.put("consumption_status", "FAILED");
        row.put("client_lifecycle_status", "FAILED");
        row.put("reward_verification_status", "PENDING");
        row.put("entitlement_status", "NONE");
        row.put("revenue_status", "NONE");
        row.put("failure_reason", "NO_FILL");
        row.put("source_currency", null);
        row.put("estimated_amount_units", null);
        row.put("reconciled_amount_units", null);
        row.put("amount_scale", null);
        row.put("native_grant_evidence_id", null);
        row.put("create_time", Timestamp.valueOf("2026-07-23 09:00:00"));
        row.put("last_event_at", Timestamp.valueOf("2026-07-23 09:01:00"));
        row.put("reward_verified_at", null);
        row.put("entitled_at", null);
        return row;
    }

    private Class<?> loadClass(String name) {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException ignored) {
            return null;
        }
    }

    private static class RecordingJdbcTemplate extends JdbcTemplate {

        protected final List<SqlCall> calls = new ArrayList<>();

        @Override
        public <T> T queryForObject(String sql, Class<T> requiredType, Object... args) {
            calls.add(new SqlCall(sql, args));
            if (Long.class.equals(requiredType)) {
                return requiredType.cast(0L);
            }
            return null;
        }

        @Override
        public <T> List<T> query(String sql, Object[] args, RowMapper<T> rowMapper) {
            calls.add(new SqlCall(sql, args));
            return Collections.emptyList();
        }

        @Override
        public List<Map<String, Object>> queryForList(String sql, Object... args) {
            calls.add(new SqlCall(sql, args));
            return Collections.emptyList();
        }
    }

    private static final class SingleSessionJdbcTemplate extends RecordingJdbcTemplate {

        private final Map<String, Object> row;

        private SingleSessionJdbcTemplate(Map<String, Object> row) {
            this.row = row;
        }

        @Override
        public <T> T queryForObject(String sql, Class<T> requiredType, Object... args) {
            calls.add(new SqlCall(sql, args));
            return Long.class.equals(requiredType) ? requiredType.cast(1L) : null;
        }

        @Override
        public <T> List<T> query(String sql, Object[] args, RowMapper<T> rowMapper) {
            calls.add(new SqlCall(sql, args));
            try {
                return Collections.singletonList(rowMapper.mapRow(resultSet(row), 0));
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        }

        private ResultSet resultSet(Map<String, Object> values) {
            boolean[] wasNull = new boolean[1];
            return (ResultSet) Proxy.newProxyInstance(
                    SkitAdConsumptionQueryServiceTest.class.getClassLoader(),
                    new Class<?>[]{ResultSet.class}, (proxy, method, args) -> {
                        String methodName = method.getName();
                        if ("wasNull".equals(methodName)) {
                            return wasNull[0];
                        }
                        if ("getString".equals(methodName) || "getLong".equals(methodName)
                                || "getInt".equals(methodName) || "getTimestamp".equals(methodName)) {
                            Object value = values.get(String.valueOf(args[0]));
                            wasNull[0] = value == null;
                            if ("getString".equals(methodName)) {
                                return value == null ? null : value.toString();
                            }
                            if ("getLong".equals(methodName)) {
                                return value == null ? 0L : ((Number) value).longValue();
                            }
                            if ("getInt".equals(methodName)) {
                                return value == null ? 0 : ((Number) value).intValue();
                            }
                            return (Timestamp) value;
                        }
                        if ("toString".equals(methodName)) {
                            return "MapBackedResultSet";
                        }
                        if ("hashCode".equals(methodName)) {
                            return System.identityHashCode(proxy);
                        }
                        if ("equals".equals(methodName)) {
                            return proxy == args[0];
                        }
                        throw new UnsupportedOperationException("Unsupported ResultSet method: "
                                + methodName);
                    });
        }
    }

    private static final class SqlCall {
        private final String sql;
        private final Object[] args;

        private SqlCall(String sql, Object[] args) {
            this.sql = sql;
            this.args = args == null ? new Object[0] : args.clone();
        }
    }

}
