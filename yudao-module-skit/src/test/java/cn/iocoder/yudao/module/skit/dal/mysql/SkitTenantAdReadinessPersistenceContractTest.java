package cn.iocoder.yudao.module.skit.dal.mysql;

import cn.iocoder.yudao.module.skit.dal.mysql.ad.SkitAdSessionMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.ad.SkitTenantAdCapabilityMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.app.SkitAppReleaseProfileMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.member.SkitNativePlayerGrantMapper;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkitTenantAdReadinessPersistenceContractTest {

    @Test
    void rolloutCasIsTenantVersionStateAndDatabaseClockBound() throws Exception {
        String sql = update(SkitTenantAdCapabilityMapper.class.getMethod("transitionCas",
                Long.class, Long.class, Integer.class, String.class, String.class, Integer.class));

        assertContains(sql, "tenant_id=#{tenantid}", "id=#{id}",
                "readiness_version=#{expectedreadinessversion}",
                "readiness_version=readiness_version+1", "update_time=current_timestamp",
                "#{targetstate}='shadow_test_users' and rollout_state='off'",
                "#{targetstate}='enforced' and rollout_state='shadow_test_users'",
                "#{targetstate}='off' and rollout_state in ('shadow_test_users','enforced')");
        assertFalse(sql.contains("now()"), "rollout audit time must use the canonical DB expression");
    }

    @Test
    void enforcedCutoverRevokesOnlyExactTenantActiveArtifactsUsingDatabaseTime() throws Exception {
        String grants = update(SkitNativePlayerGrantMapper.class.getMethod(
                "revokeActiveForTenantRollout", Long.class));
        assertContains(grants, "tenant_id=#{tenantid}", "status='active'", "status='revoked'",
                "revoked_at=current_timestamp", "expires_at>current_timestamp", "deleted=b'0'");

        String sessions = update(SkitAdSessionMapper.class.getMethod(
                "rejectPendingForTenantRollout", Long.class));
        assertContains(sessions, "tenant_id=#{tenantid}", "reward_verification_status='pending'",
                "reward_verification_status='rejected'", "entitlement_status='none'",
                "active_scope_hash is not null", "active_scope_hash=null",
                "active_scope_released_at=current_timestamp", "failure_reason='rollout_revoked'",
                "deleted=b'0'");
        assertFalse(sessions.contains("ad_account_id=#{"),
                "the tenant-wide cutover intentionally invalidates every old tenant session");
    }

    @Test
    void appMinimumRaiseIsTenantRowAndDatabaseClockBound() throws Exception {
        String sql = update(SkitAppReleaseProfileMapper.class.getMethod(
                "updateMinNativeVersionForRollout", Long.class, Long.class, String.class));

        assertContains(sql, "tenant_id=#{tenantid}", "id=#{id}", "deleted=b'0'",
                "min_native_version=#{minnativeversion}", "update_time=current_timestamp");
    }

    private static String update(Method method) {
        Update annotation = method.getAnnotation(Update.class);
        assertNotNull(annotation, method.toString());
        return String.join(" ", annotation.value()).toLowerCase(Locale.ROOT).replace("`", "")
                .replaceAll("\\s+", " ").replace(", ", ",");
    }

    private static void assertContains(String sql, String... expected) {
        for (String fragment : expected) {
            assertTrue(sql.contains(fragment), () -> "missing '" + fragment + "' in " + sql);
        }
    }

}
