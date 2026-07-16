package cn.iocoder.yudao.module.skit.dal.mysql.invite;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.Insert;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkitInviteCodeRegistryMapperContractTest {

    @Test
    void claimInsertMustBindTenantExplicitlyAndBypassTenantLineRewrite() throws Exception {
        Insert insert = SkitInviteCodeRegistryMapper.class.getMethod(
                "insert", cn.iocoder.yudao.module.skit.dal.dataobject.invite.SkitInviteCodeRegistryDO.class)
                .getAnnotation(Insert.class);
        assertNotNull(insert);
        String sql = String.join(" ", insert.value()).toLowerCase();
        assertTrue(sql.contains("`tenant_id`"), "invite registry insert must bind tenant_id explicitly");
        assertTrue(sql.contains("#{tenantid}"), "invite registry insert must use the row tenant id");

        InterceptorIgnore ignore = SkitInviteCodeRegistryMapper.class.getMethod(
                "insert", cn.iocoder.yudao.module.skit.dal.dataobject.invite.SkitInviteCodeRegistryDO.class)
                .getAnnotation(InterceptorIgnore.class);
        assertNotNull(ignore, "explicit tenant SQL must opt out of tenant-line rewriting");
        assertTrue("true".equalsIgnoreCase(ignore.tenantLine()));
    }
}
