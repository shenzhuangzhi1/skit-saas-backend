package cn.iocoder.yudao.module.skit.dal.mysql;

import cn.iocoder.yudao.module.skit.dal.mysql.ad.SkitAdCallbackAttemptMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.ad.SkitAdCallbackEdgeAttemptMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.ad.SkitAdCallbackInboxMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.ad.SkitAdSessionMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.revenue.SkitAdRevenueEventMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkitAdExpiryRetentionPersistenceContractTest {

    @Test
    void expiryProjectsOnlyStableTenantAccountSessionRoutes() throws Exception {
        String sql = sql(SkitAdSessionMapper.class.getMethod(
                "selectExpiredRewardClaims", int.class), Select.class);

        assertContains(sql, "select tenant_id,ad_account_id,id", "reward_verification_status='pending'",
                "reward_accept_until<current_timestamp", "reward_callback_inbox_id is null",
                "reward_callback_received_at is null", "active_scope_hash is not null",
                "active_scope_released_at is null", "order by reward_accept_until,id", "limit #{limit}");
        assertFalse(sql.contains("select *"));
        assertFalse(sql.contains("session_token_hash"));
    }

    @Test
    void timeoutCasIsTenantAccountVersionAndReceiptBoundAndFreezesOnlyExpectedRevenueState() throws Exception {
        String sql = sql(SkitAdSessionMapper.class.getMethod(
                "markRewardVerifyTimeoutByAccountCas", Long.class, Long.class, Long.class,
                Long.class, Integer.class, String.class, String.class), Update.class);

        assertContains(sql, "reward_verification_status='verify_timeout'",
                "active_scope_hash=null", "active_scope_release_reason='verify_timeout'",
                "revenue_status=#{nextrevenuestatus}", "tenant_id=#{tenantid}",
                "ad_account_id=#{adaccountid}", "id=#{id}", "member_id=#{memberid}",
                "version=#{expectedversion}", "revenue_status=#{expectedrevenuestatus}",
                "reward_verification_status='pending'", "reward_accept_until<current_timestamp",
                "reward_callback_inbox_id is null", "reward_callback_received_at is null",
                "active_scope_hash is not null", "active_scope_released_at is null",
                "active_scope_release_reason is null", "deleted=b'0'");
    }

    @Test
    void impressionTimeoutRemainsFrozenAndCannotRewriteAReconciledOrVerifiedFact() throws Exception {
        String sql = sql(SkitAdRevenueEventMapper.class.getMethod(
                "markNonRewardedFrozenOnTimeoutCas", Long.class, Long.class, Long.class,
                Long.class, Integer.class), Update.class);

        assertContains(sql, "reward_qualification_status='non_rewarded'",
                "reconciliation_status='frozen'", "tenant_id=#{tenantid}",
                "ad_session_id=#{adsessionid}", "ad_account_id=#{adaccountid}",
                "version=#{expectedversion}", "source_type='taku_impression'",
                "reward_qualification_status='pending_reward'",
                "source_verification_status='unsigned_observation'",
                "reconciliation_status='frozen'", "legacy_unverified=b'0'", "deleted=b'0'");
    }

    @Test
    void payloadEraseCanTouchOnlyExpiredTerminalEncryptionMetadata() throws Exception {
        String select = sql(SkitAdCallbackInboxMapper.class.getMethod(
                "selectExpiredTerminalPayloadClaims", int.class), Select.class);
        assertContains(select, "select tenant_id,ad_account_id,id",
                "processing_status in ('succeeded','rejected','dead_letter')",
                "payload_ciphertext is not null", "payload_expires_at<=current_timestamp",
                "order by payload_expires_at,id", "limit #{limit}");
        assertFalse(select.contains("select *"));

        String erase = sql(SkitAdCallbackInboxMapper.class.getMethod(
                "eraseExpiredTerminalPayloadCas", Long.class, Long.class, Long.class), Update.class);
        assertContains(erase, "payload_ciphertext=null", "payload_nonce=null", "payload_key_id=null",
                "payload_envelope_version=null", "payload_expires_at=null",
                "tenant_id=#{tenantid}", "ad_account_id=#{adaccountid}", "id=#{id}",
                "processing_status in ('succeeded','rejected','dead_letter')",
                "payload_ciphertext is not null", "payload_nonce is not null",
                "payload_key_id is not null", "payload_envelope_version is not null",
                "payload_expires_at<=current_timestamp");
        assertFalse(erase.contains("canonical_payload_hash=null"));
        assertFalse(erase.contains("signature_status="));
    }

    @Test
    void physicalRetentionDeletesOnlyDeliveryAndEdgeAttemptsByExactClaimAndCutoff() throws Exception {
        String attempt = sql(SkitAdCallbackAttemptMapper.class.getMethod(
                "deleteExpiredRetentionClaimCas", Long.class, Long.class, Long.class,
                int.class), Delete.class);
        assertContains(attempt, "delete from skit_ad_callback_attempt", "tenant_id=#{tenantid}",
                "ad_account_id=#{adaccountid}", "id=#{id}",
                "received_at<date_sub(current_timestamp,interval #{retentiondays} day)");

        String edge = sql(SkitAdCallbackEdgeAttemptMapper.class.getMethod(
                "deleteExpiredKnownRouteClaimCas", Long.class, Long.class, Long.class,
                int.class), Delete.class);
        assertContains(edge, "delete from skit_ad_callback_edge_attempt", "tenant_id=#{tenantid}",
                "ad_account_id=#{adaccountid}", "id=#{id}",
                "received_at<date_sub(current_timestamp,interval #{retentiondays} day)");

        for (Class<?> protectedMapper : new Class<?>[]{SkitAdCallbackInboxMapper.class,
                SkitAdSessionMapper.class, SkitAdRevenueEventMapper.class}) {
            for (Method method : protectedMapper.getDeclaredMethods()) {
                assertFalse(method.isAnnotationPresent(Delete.class),
                        protectedMapper.getSimpleName() + " must never physically delete durable facts");
            }
        }
    }

    private static <A extends java.lang.annotation.Annotation> String sql(Method method, Class<A> type) {
        A annotation = method.getAnnotation(type);
        assertNotNull(annotation, method.toString());
        String[] values;
        if (annotation instanceof Select) {
            values = ((Select) annotation).value();
        } else if (annotation instanceof Update) {
            values = ((Update) annotation).value();
        } else if (annotation instanceof Delete) {
            values = ((Delete) annotation).value();
        } else {
            throw new IllegalArgumentException(type.getName());
        }
        return String.join(" ", values).toLowerCase(Locale.ROOT).replace("`", "")
                .replaceAll("\\s+", " ").replace(", ", ",");
    }

    private static void assertContains(String sql, String... expected) {
        for (String fragment : expected) {
            assertTrue(sql.contains(fragment), () -> "missing '" + fragment + "' in " + sql);
        }
    }

}
