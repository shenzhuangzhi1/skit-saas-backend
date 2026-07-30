package cn.iocoder.yudao.module.skit.dal.mysql.ad;

import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Results;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkitAdAccountMapperLockContractTest {

    @Test
    void pangleSnapshotReadUsesASharedDatabaseLock() throws Exception {
        Method method = SkitAdAccountMapper.class.getMethod(
                "selectEnabledPangleSnapshotForShare", Long.class);
        Select select = method.getAnnotation(Select.class);
        assertTrue(select != null, "Pangle snapshot lookup must be explicit SQL");

        String sql = String.join(" ", select.value()).toUpperCase(Locale.ROOT);
        assertTrue(sql.startsWith("SELECT `ID`,`TENANT_ID`,`PROVIDER`,`CONFIG_DATA`,`STATUS`"));
        assertFalse(sql.contains("SELECT *"));
        assertTrue(sql.contains("`TENANT_ID`=#{TENANTID}"));
        assertTrue(sql.contains("`PROVIDER`='PANGLE'"));
        assertTrue(sql.contains("FOR SHARE"));
        assertNull(method.getAnnotation(Results.class),
                "Pangle session snapshots must not decrypt unrelated account credentials");
    }
}
