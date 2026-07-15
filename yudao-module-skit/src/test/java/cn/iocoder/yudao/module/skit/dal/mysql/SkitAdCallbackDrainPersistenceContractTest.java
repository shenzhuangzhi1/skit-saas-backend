package cn.iocoder.yudao.module.skit.dal.mysql;

import cn.iocoder.yudao.module.skit.dal.mysql.ad.SkitAdCallbackInboxMapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
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
