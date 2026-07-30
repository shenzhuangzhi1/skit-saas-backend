package cn.iocoder.yudao.module.skit.dal.mysql.ad;

import cn.iocoder.yudao.module.skit.dal.dataobject.ad.SkitAdCallbackKeyDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.ad.SkitAdRewardSecretVersionDO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkitAdCredentialVersionMapperContractTest {

    @Test
    void credentialVersionInsertsMustBindTenantExplicitly() throws Exception {
        assertTenantInsert(SkitAdCallbackKeyMapper.class, SkitAdCallbackKeyDO.class);
        assertTenantInsert(SkitAdRewardSecretVersionMapper.class, SkitAdRewardSecretVersionDO.class);
    }

    @Test
    void rewardSecretClearRevokesEveryUnrevokedVersionInTheExactTenantAccount() throws Exception {
        Method method = SkitAdRewardSecretVersionMapper.class.getMethod(
                "revokeAllUnrevokedVersions", Long.class, Long.class, LocalDateTime.class);
        Update update = method.getAnnotation(Update.class);
        assertNotNull(update);
        String sql = String.join(" ", update.value()).toLowerCase().replaceAll("\\s+", "");

        assertTrue(sql.contains("tenant_id`=#{tenantid}"));
        assertTrue(sql.contains("ad_account_id`=#{adaccountid}"));
        assertTrue(sql.contains("revoked_at`isnull"));
        assertTrue(sql.contains("active`=b'0'"));
        assertTrue(sql.contains("revoked_at`=#{revokedat}"));
    }

    private static void assertTenantInsert(Class<?> mapperType, Class<?> rowType) throws Exception {
        Method method = mapperType.getMethod("insert", rowType);
        Insert insert = method.getAnnotation(Insert.class);
        assertNotNull(insert);
        String sql = String.join(" ", insert.value()).toLowerCase().replaceAll("\\s+", "");
        assertTrue(sql.contains("(tenant_id,"),
                mapperType.getSimpleName() + " insert must declare tenant_id explicitly");
        assertTrue(sql.contains("(#{tenantid},"),
                mapperType.getSimpleName() + " insert must bind the row tenant id");
    }

}
