package cn.iocoder.yudao.module.skit.dal.mysql.ad;

import cn.iocoder.yudao.module.skit.dal.dataobject.ad.SkitAdCallbackKeyDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.ad.SkitAdRewardSecretVersionDO;
import org.apache.ibatis.annotations.Insert;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkitAdCredentialVersionMapperContractTest {

    @Test
    void credentialVersionInsertsMustBindTenantExplicitly() throws Exception {
        assertTenantInsert(SkitAdCallbackKeyMapper.class, SkitAdCallbackKeyDO.class);
        assertTenantInsert(SkitAdRewardSecretVersionMapper.class, SkitAdRewardSecretVersionDO.class);
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
