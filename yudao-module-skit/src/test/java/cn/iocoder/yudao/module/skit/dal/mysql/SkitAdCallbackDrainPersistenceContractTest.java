package cn.iocoder.yudao.module.skit.dal.mysql;

import cn.iocoder.yudao.module.skit.dal.mysql.ad.SkitAdCallbackInboxMapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkitAdCallbackDrainPersistenceContractTest {

    @Test
    void globalClaimLocksOnlyImmutableRoutingProjectionInStableOrder() throws Exception {
        String sql = sql(SkitAdCallbackInboxMapper.class
                .getMethod("selectReadyClaimsForUpdate", int.class), Select.class);

        assertContains(sql, "select tenant_id,ad_account_id,id",
                "processing_status='pending'",
                "processing_status='retry_wait' and next_attempt_at<=current_timestamp",
                "processing_status='processing' and lease_until<=current_timestamp",
                "order by id", "limit #{limit}", "for update skip locked");
        assertFalse(sql.contains("select *"), "global claiming must not expose callback payloads");
        assertFalse(sql.contains("payload_"), "global claiming must return routing fields only");
    }

    @Test
    void claimCasOwnsLeaseAndIncrementsAttemptExactlyOnce() throws Exception {
        String sql = sql(SkitAdCallbackInboxMapper.class.getMethod("claimForProcessingCas",
                Long.class, Long.class, Long.class, String.class, int.class), Update.class);

        assertContains(sql, "processing_status='processing'", "error_code=null",
                "lease_owner=#{leaseowner}", "timestampadd(second,#{leaseseconds},current_timestamp)",
                "processing_attempt_count=processing_attempt_count+1", "next_attempt_at=null",
                "tenant_id=#{tenantid}", "ad_account_id=#{adaccountid}", "id=#{id}",
                "processing_status='pending'", "processing_status='retry_wait'",
                "next_attempt_at<=current_timestamp", "processing_status='processing'",
                "lease_until<=current_timestamp");
    }

    @Test
    void retryAndDeadLetterAreLeaseBoundMonotonicCasTransitions() throws Exception {
        String retry = sql(SkitAdCallbackInboxMapper.class.getMethod("markRetryWaitCas",
                Long.class, Long.class, Long.class, String.class, String.class,
                int.class, int.class, int.class), Update.class);
        assertContains(retry, "processing_status='retry_wait'", "error_code=#{errorcode}",
                "lease_owner=null", "lease_until=null", "processed_at=null",
                "timestampadd(second", "least(#{maxbackoffseconds}", "pow(2",
                "processing_status='processing'", "lease_owner=#{leaseowner}",
                "lease_until>=current_timestamp", "processing_attempt_count<#{maxattempts}");

        String dead = sql(SkitAdCallbackInboxMapper.class.getMethod("markDeadLetterCas",
                Long.class, Long.class, Long.class, String.class, String.class, int.class), Update.class);
        assertContains(dead, "processing_status='dead_letter'", "error_code=#{errorcode}",
                "lease_owner=null", "lease_until=null", "next_attempt_at=null",
                "processed_at=current_timestamp", "processing_status='processing'",
                "lease_owner=#{leaseowner}", "lease_until>=current_timestamp",
                "processing_attempt_count>=#{maxattempts}");
    }

    @Test
    void pangleAttestationWakeCasOnlyRequeuesTheExactLivePendingReward() throws Exception {
        String sql = sql(SkitAdCallbackInboxMapper.class.getMethod(
                "wakePangleAttestationPendingRewardCas", Long.class, Long.class,
                Long.class, Integer.class), Update.class);

        assertContains(sql, "update skit_ad_callback_inbox i",
                "join skit_ad_session s on s.tenant_id=i.tenant_id",
                "s.ad_account_id=i.ad_account_id", "s.id=i.ad_session_id",
                "s.callback_key_version=i.callback_key_version",
                "join skit_pangle_reward_attestation a on a.tenant_id=i.tenant_id",
                "a.taku_ad_account_id=i.ad_account_id", "a.ad_session_id=i.ad_session_id",
                "a.callback_key_version=i.callback_key_version",
                "set i.processing_status='pending'", "i.error_code=null",
                "i.lease_owner=null", "i.lease_until=null", "i.next_attempt_at=null",
                "i.processed_at=null", "i.tenant_id=#{tenantid}",
                "i.ad_account_id=#{adaccountid}", "i.ad_session_id=#{adsessionid}",
                "i.callback_key_version=#{callbackkeyversion}", "i.provider='taku'",
                "i.callback_type='reward'", "i.network_firm_id=15",
                "i.processing_status='retry_wait'",
                "i.error_code='pangle_attestation_pending'",
                "s.reward_verification_status='pending'", "s.entitlement_status='none'",
                "s.reward_accept_until>=current_timestamp",
                "s.reward_callback_inbox_id=i.id", "a.provider='pangle'");
        assertFalse(sql.contains("processing_status in"),
                "wake must never broaden into terminal or unrelated states");
        assertFalse(sql.contains("processing_attempt_count="),
                "wake must preserve the accumulated attempt count");
    }

    @Test
    void panglePrerequisiteRetryUsesSessionExpiryInsteadOfTheGenericAttemptLimit()
            throws Exception {
        String sql = sql(SkitAdCallbackInboxMapper.class.getMethod(
                "markPanglePrerequisiteRetryWaitCas", Long.class, Long.class,
                Long.class, String.class, int.class, int.class), Update.class);

        assertContains(sql, "update skit_ad_callback_inbox i",
                "join skit_ad_session s on s.tenant_id=i.tenant_id",
                "s.ad_account_id=i.ad_account_id", "s.id=i.ad_session_id",
                "s.callback_key_version=i.callback_key_version",
                "set i.processing_status='retry_wait'",
                "i.error_code='pangle_attestation_pending'",
                "case when exists(select 1 from skit_pangle_reward_attestation a",
                "a.tenant_id=i.tenant_id", "a.taku_ad_account_id=i.ad_account_id",
                "a.ad_session_id=i.ad_session_id",
                "a.callback_key_version=i.callback_key_version",
                "then current_timestamp else timestampadd(second",
                "i.tenant_id=#{tenantid}", "i.ad_account_id=#{adaccountid}",
                "i.id=#{id}", "i.processing_status='processing'",
                "i.lease_owner=#{leaseowner}", "i.lease_until>=current_timestamp",
                "i.provider='taku'", "i.callback_type='reward'", "i.network_firm_id=15",
                "s.reward_verification_status='pending'", "s.entitlement_status='none'",
                "s.reward_accept_until>=current_timestamp",
                "s.reward_callback_inbox_id=i.id");
        assertFalse(sql.contains("maxattempt"),
                "a live prerequisite must not dead-letter because a generic attempt budget elapsed");
        assertFalse(sql.contains("processing_attempt_count="),
                "scheduling prerequisite retry must not rewrite its attempt count");
    }

    @Test
    void expiredLiveFirm15LeaseRecoversBeforeTheGenericAttemptLimit() {
        Method recovery = Arrays.stream(SkitAdCallbackInboxMapper.class.getMethods())
                .filter(method -> "recoverExpiredPanglePrerequisiteRetryWaitCas"
                        .equals(method.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "missing expired firm15 prerequisite recovery CAS"));
        String sql = sql(recovery, Update.class);

        assertContains(sql, "update skit_ad_callback_inbox i",
                "join skit_ad_session s on s.tenant_id=i.tenant_id",
                "s.ad_account_id=i.ad_account_id", "s.id=i.ad_session_id",
                "s.callback_key_version=i.callback_key_version",
                "set i.processing_status='retry_wait'",
                "i.error_code='pangle_attestation_pending'",
                "i.lease_owner=null", "i.lease_until=null",
                "case when exists(select 1 from skit_pangle_reward_attestation a",
                "a.tenant_id=i.tenant_id", "a.taku_ad_account_id=i.ad_account_id",
                "a.ad_session_id=i.ad_session_id",
                "then current_timestamp else timestampadd(second",
                "i.tenant_id=#{tenantid}", "i.ad_account_id=#{adaccountid}",
                "i.id=#{id}", "i.processing_status='processing'",
                "i.lease_until<=current_timestamp", "i.provider='taku'",
                "i.callback_type='reward'", "i.network_firm_id=15",
                "s.reward_verification_status='pending'", "s.entitlement_status='none'",
                "s.reward_accept_until>=current_timestamp",
                "s.reward_callback_inbox_id=i.id",
                "i.payload_expires_at>current_timestamp");
        assertFalse(sql.contains("maxattempt"),
                "live firm15 recovery must not use the generic attempt budget");
        assertFalse(sql.contains("processing_attempt_count="),
                "lease recovery must preserve the accumulated attempt count");
        assertFalse(sql.contains("processing_status='dead_letter'"),
                "the prerequisite recovery CAS must never terminalize a live callback");
    }

    @Test
    void anExpiredLeaseAtTheLimitIsDeadLetteredWithoutChangingItsAttemptCount() throws Exception {
        String sql = sql(SkitAdCallbackInboxMapper.class.getMethod(
                "markExpiredProcessingDeadLetterCas", Long.class, Long.class, Long.class,
                String.class, int.class), Update.class);

        assertContains(sql, "processing_status='dead_letter'", "error_code=#{errorcode}",
                "lease_owner=null", "lease_until=null", "next_attempt_at=null",
                "processed_at=current_timestamp", "tenant_id=#{tenantid}",
                "ad_account_id=#{adaccountid}", "id=#{id}",
                "processing_status='processing'", "lease_until<=current_timestamp",
                "processing_attempt_count>=#{maxattempts}");
        assertFalse(sql.contains("processing_attempt_count=processing_attempt_count+1"));
    }

    @Test
    void deadLetterAlertsUseARouteOnlyBacklogAndOneShotDatabaseClockCas() throws Exception {
        String backlog = sql(SkitAdCallbackInboxMapper.class.getMethod(
                "selectUnalertedDeadLetterClaims", int.class), Select.class);
        assertContains(backlog, "select tenant_id,ad_account_id,id",
                "processing_status='dead_letter'", "dead_letter_alerted_at is null",
                "order by id", "limit #{limit}");
        assertFalse(backlog.contains("select*"));
        assertFalse(backlog.contains("payload_"));
        assertFalse(backlog.contains("provider_user_id"));

        String alert = sql(SkitAdCallbackInboxMapper.class.getMethod(
                "markDeadLetterAlertedCas", Long.class, Long.class, Long.class), Update.class);
        assertContains(alert, "dead_letter_alerted_at=current_timestamp",
                "update_time=current_timestamp", "tenant_id=#{tenantid}",
                "ad_account_id=#{adaccountid}", "id=#{id}",
                "processing_status='dead_letter'", "dead_letter_alerted_at is null");
    }

    private static <A extends java.lang.annotation.Annotation> String sql(
            Method method, Class<A> annotationType) {
        A annotation = method.getAnnotation(annotationType);
        assertNotNull(annotation, method.toString());
        String[] fragments = annotation instanceof Select
                ? ((Select) annotation).value() : ((Update) annotation).value();
        return String.join(" ", fragments).toLowerCase(Locale.ROOT).replace("`", "")
                .replaceAll("\\s+", "").replace(", ", ",");
    }

    private static void assertContains(String sql, String... expected) {
        for (String fragment : expected) {
            String normalized = fragment.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
            assertTrue(sql.contains(normalized), () -> "missing '" + normalized + "' in " + sql);
        }
    }

}
