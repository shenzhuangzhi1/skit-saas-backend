package cn.iocoder.yudao.module.skit.dal.mysql.provider;

import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SkitProviderImpressionAttributionPersistenceContractTest {

    @Test
    void routeAndLeaseSqlUseOnlyExactServerOwnedBindings() throws Exception {
        Method routeMethod = SkitProviderImpressionAttributionMapper.class.getMethod(
                "selectExactRoute", long.class, String.class, String.class,
                String.class, String.class);
        String routeSql = String.join(" ", routeMethod.getAnnotation(Select.class).value());
        assertTrue(routeSql.contains("BINARY s.session_id=BINARY #{showCustomExt}"));
        assertTrue(routeSql.contains("BINARY s.pseudonymous_user_id=BINARY #{userId}"));
        assertTrue(routeSql.contains("BINARY rp.native_package=BINARY #{packageName}"));
        assertTrue(routeSql.contains("c.dedicated_unlock_placement_id"));
        assertTrue(routeSql.contains("p.account_mode='SHARED_MASTER'"));
        assertTrue(routeSql.contains("NOT EXISTS (SELECT 1 FROM skit_ad_provider_connection owned"));
        assertTrue(routeSql.contains("owned.owner_tenant_id=s.tenant_id"));
        assertTrue(routeSql.contains("owned.owner_ad_account_id=s.ad_account_id"));
        assertTrue(routeSql.contains("p.owner_tenant_id=s.tenant_id"));
        assertTrue(routeSql.contains("LIMIT 2"));
        assertTrue(routeSql.contains("FOR SHARE"));

        Method claimMethod = SkitProviderImpressionInboxMapper.class.getMethod(
                "claimForAttributionCas", long.class, long.class, String.class,
                int.class, int.class);
        String claimSql = String.join(" ", claimMethod.getAnnotation(Update.class).value());
        assertTrue(claimSql.contains("processing_attempt_count=processing_attempt_count+1"));
        assertTrue(claimSql.contains("integrity_status='CANONICAL'"));
        assertTrue(claimSql.contains("lease_until<=UTC_TIMESTAMP()"));

        Method successMethod = SkitProviderImpressionInboxMapper.class.getMethod(
                "markAttributionSucceededCas", long.class, long.class,
                String.class, java.time.LocalDateTime.class);
        String successSql = String.join(" ", successMethod.getAnnotation(Update.class).value());
        assertTrue(successSql.contains("lease_owner=#{leaseOwner}"));
        assertTrue(successSql.contains("lease_until>=UTC_TIMESTAMP()"));
        assertTrue(successSql.contains("integrity_revision=0"));

        Method deadLetterMethod = SkitProviderImpressionInboxMapper.class.getMethod(
                "markAttributionDeadLetterCas", long.class, long.class, String.class,
                String.class, int.class);
        String deadLetterSql = String.join(" ",
                deadLetterMethod.getAnnotation(Update.class).value());
        assertTrue(!deadLetterSql.contains("dead_letter_alerted_at"));
    }
}
