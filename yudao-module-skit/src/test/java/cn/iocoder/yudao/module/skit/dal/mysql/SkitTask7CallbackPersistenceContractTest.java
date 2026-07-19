package cn.iocoder.yudao.module.skit.dal.mysql;

import cn.iocoder.yudao.module.skit.dal.dataobject.ad.SkitAdCallbackAttemptDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.ad.SkitAdCallbackEdgeAttemptDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.ad.SkitAdCallbackInboxDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.ad.SkitAdNetworkCapabilityDO;
import cn.iocoder.yudao.module.skit.dal.mysql.ad.SkitAdCallbackAttemptMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.ad.SkitAdCallbackEdgeAttemptMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.ad.SkitAdCallbackInboxMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.ad.SkitAdNetworkCapabilityMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.ad.SkitAdSessionMapper;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fasterxml.jackson.annotation.JsonIgnore;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkitTask7CallbackPersistenceContractTest {

    @Test
    void callbackEvidenceObjectsMapSensitiveAndLeaseFields() throws Exception {
        assertFields(SkitAdCallbackEdgeAttemptDO.class, Arrays.asList(
                "id", "tenantId", "adAccountId", "callbackKeyHash", "provider", "callbackType",
                "clientIpHash", "requestMethod", "resultCode", "receivedAt"));
        assertJsonIgnored(SkitAdCallbackEdgeAttemptDO.class, "callbackKeyHash");
        assertJsonIgnored(SkitAdCallbackEdgeAttemptDO.class, "clientIpHash");

        assertFields(SkitAdCallbackInboxDO.class, Arrays.asList(
                "id", "adAccountId", "adSessionId", "adSessionRefId", "callbackKeyVersion",
                "rewardSecretVersion", "provider", "callbackType", "idempotencyKey",
                "providerUserId", "extraDataHash", "providerTransactionId", "providerShowId",
                "providerRequestId", "placementId", "adsourceId", "networkFirmId",
                "sourceCurrency", "sourceAmountUnits", "amountScale", "signedFieldMask",
                "evidenceProvenance", "canonicalPayloadHash", "authenticationLevel",
                "signatureStatus", "deliveryIntegrityStatus", "integrityConflictAt",
                "processingStatus", "payloadCiphertext", "payloadNonce", "payloadKeyId",
                "payloadEnvelopeVersion", "payloadExpiresAt", "errorCode", "leaseOwner",
                "leaseUntil", "processingAttemptCount", "nextAttemptAt", "receivedAt",
                "processedAt", "deadLetterAlertedAt", "ingressResponseCode"));
        for (String field : Arrays.asList("extraDataHash", "canonicalPayloadHash", "payloadCiphertext",
                "payloadNonce")) {
            assertJsonIgnored(SkitAdCallbackInboxDO.class, field);
        }

        assertFields(SkitAdCallbackAttemptDO.class, Arrays.asList(
                "id", "callbackInboxId", "adAccountId", "adSessionId", "adSessionRefId",
                "attemptNo", "payloadHash", "resultCode", "receivedAt"));
        assertJsonIgnored(SkitAdCallbackAttemptDO.class, "payloadHash");

        assertFields(SkitAdNetworkCapabilityDO.class, Arrays.asList(
                "id", "adAccountId", "networkFirmId", "rewardAuthority", "supportsUserId",
                "supportsCustomData", "supportsStableTransaction", "supportsImpressionRevenue",
                "supportsReporting", "enabled", "verifiedAt"));
    }

    @Test
    void callbackMappersExposeOnlyAppendAndNarrowStateTransitions() throws Exception {
        for (Class<?> mapper : Arrays.asList(SkitAdCallbackEdgeAttemptMapper.class,
                SkitAdCallbackInboxMapper.class, SkitAdCallbackAttemptMapper.class,
                SkitAdNetworkCapabilityMapper.class)) {
            assertFalse(BaseMapper.class.isAssignableFrom(mapper),
                    mapper.getSimpleName() + " must not expose unrestricted CRUD");
        }
        assertNotNull(SkitAdCallbackEdgeAttemptMapper.class
                .getMethod("insert", SkitAdCallbackEdgeAttemptDO.class).getAnnotation(Insert.class));
        Method canonicalInboxInsert = SkitAdCallbackInboxMapper.class
                .getMethod("insertOrGetCanonical", SkitAdCallbackInboxDO.class);
        assertNotNull(canonicalInboxInsert.getAnnotation(Insert.class));
        InterceptorIgnore tenantRewriteIgnore = canonicalInboxInsert.getAnnotation(InterceptorIgnore.class);
        assertNotNull(tenantRewriteIgnore,
                "explicit callback tenant SQL must opt out of tenant-line rewriting");
        assertTrue("true".equalsIgnoreCase(tenantRewriteIgnore.tenantLine()));
        Method callbackAttemptInsert = SkitAdCallbackAttemptMapper.class
                .getMethod("insert", SkitAdCallbackAttemptDO.class);
        assertNotNull(callbackAttemptInsert.getAnnotation(Insert.class));
        InterceptorIgnore attemptTenantRewriteIgnore = callbackAttemptInsert.getAnnotation(InterceptorIgnore.class);
        assertNotNull(attemptTenantRewriteIgnore,
                "explicit callback-attempt tenant SQL must opt out of tenant-line rewriting");
        assertTrue("true".equalsIgnoreCase(attemptTenantRewriteIgnore.tenantLine()));

        String inboxLock = sql(SkitAdCallbackInboxMapper.class.getMethod(
                "selectByTenantAccountAndIdForUpdate", Long.class, Long.class, Long.class), Select.class);
        assertContains(inboxLock, "tenant_id=#{tenantid}", "ad_account_id=#{adaccountid}",
                "id=#{id}", "for update");
        assertFalse(inboxLock.contains("limit"));

        String capabilityLock = sql(SkitAdNetworkCapabilityMapper.class.getMethod(
                "selectForShare", Long.class, Long.class, Integer.class), Select.class);
        assertContains(capabilityLock, "tenant_id=#{tenantid}",
                "ad_account_id=#{adaccountid}", "network_firm_id=#{networkfirmid}",
                "deleted=b'0'", "for share");
        assertFalse(capabilityLock.contains("limit"));
    }

    @Test
    void sessionLookupAndReceiptMarkerAreFullyAccountBoundAndTimeoutCannotEraseReceipt() throws Exception {
        String lookup = sql(SkitAdSessionMapper.class.getMethod("selectByTokenHashForUpdate",
                Long.class, Long.class, byte[].class), Select.class);
        assertContains(lookup, "tenant_id=#{tenantid}", "ad_account_id=#{adaccountid}",
                "session_token_hash=#{sessiontokenhash}", "for update");
        assertFalse(lookup.contains("limit"));

        String marker = sql(SkitAdSessionMapper.class.getMethod("markRewardCallbackReceivedCas",
                Long.class, Long.class, Long.class, Long.class, LocalDateTime.class), Update.class);
        assertContains(marker, "reward_callback_inbox_id=#{callbackinboxid}",
                "reward_callback_received_at=#{receivedat}", "reward_verification_status='pending'",
                "reward_accept_until >= #{receivedat}", "ad_account_id=#{adaccountid}");

        String timeout = sql(SkitAdSessionMapper.class.getMethod(
                "markRewardVerifyTimeoutAndReleaseScopeCas", Long.class, Long.class, Long.class,
                Integer.class, LocalDateTime.class), Update.class);
        assertContains(timeout, "reward_callback_inbox_id is null",
                "reward_callback_received_at is null");
    }

    private static void assertFields(Class<?> type, List<String> expected) throws Exception {
        for (String field : expected) {
            assertNotNull(type.getDeclaredField(field), type.getSimpleName() + " missing " + field);
        }
    }

    private static void assertJsonIgnored(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        assertNotNull(field.getAnnotation(JsonIgnore.class), type.getSimpleName() + "." + name);
    }

    private static <A extends java.lang.annotation.Annotation> String sql(Method method, Class<A> type) {
        java.lang.annotation.Annotation annotation = method.getAnnotation(type);
        assertNotNull(annotation, method.toString());
        String[] values;
        if (annotation instanceof Select) {
            values = ((Select) annotation).value();
        } else if (annotation instanceof Update) {
            values = ((Update) annotation).value();
        } else {
            throw new IllegalArgumentException(type.getName());
        }
        return String.join(" ", values).toLowerCase(Locale.ROOT).replace("`", "")
                .replaceAll("\\s+", " ");
    }

    private static void assertContains(String sql, String... fragments) {
        for (String fragment : fragments) {
            assertTrue(sql.contains(fragment), () -> "missing '" + fragment + "' in " + sql);
        }
    }

}
