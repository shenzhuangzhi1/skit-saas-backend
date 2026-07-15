package cn.iocoder.yudao.module.skit.integration;

import cn.iocoder.yudao.module.skit.framework.schema.SkitSchemaInitializer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SkitAdminSuperAdminBindingMySqlIT extends SkitMySqlIntegrationTestBase {

    @Override
    protected void beforeSkitSchemaInitialization(org.springframework.jdbc.core.JdbcTemplate jdbc) {
        jdbc.update("INSERT INTO system_tenant "
                        + "(id,name,package_id,status,expire_time,deleted) VALUES (1,?,?,?,?,b'0')",
                "Platform", 0L, 0, "2099-01-01 00:00:00");
        jdbc.update("INSERT INTO system_users "
                        + "(id,tenant_id,username,deleted) VALUES (1,1,'admin',b'0')");
        jdbc.update("INSERT INTO system_role "
                        + "(id,tenant_id,code,deleted) VALUES (1,1,'super_admin',b'0')");
    }

    @Test
    void bindsAdminToSuperAdminExactlyOnce() {
        assertEquals(1, countBinding(), "the first schema initialization must bind admin");

        new SkitSchemaInitializer(jdbc()).run(null);

        assertEquals(1, countBinding(), "re-running schema initialization must not duplicate binding");
    }

    private int countBinding() {
        return jdbc().queryForObject("SELECT COUNT(*) FROM system_user_role "
                        + "WHERE tenant_id=1 AND user_id=1 AND role_id=1 AND deleted=b'0'",
                Integer.class);
    }
}
