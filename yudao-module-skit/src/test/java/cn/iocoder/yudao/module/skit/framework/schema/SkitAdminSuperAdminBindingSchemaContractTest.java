package cn.iocoder.yudao.module.skit.framework.schema;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class SkitAdminSuperAdminBindingSchemaContractTest {

    @Test
    void shouldExposeIdempotentPlatformAdminBindingMigration() throws Exception {
        SkitSchemaInitializer initializer = new SkitSchemaInitializer(mock(JdbcTemplate.class));
        Field field = SkitSchemaInitializer.class.getDeclaredField("migrations");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<SkitSchemaInitializer.Migration> migrations =
                (List<SkitSchemaInitializer.Migration>) field.get(initializer);

        SkitSchemaInitializer.Migration migration = migrations.stream()
                .filter(item -> item.getVersion() == 2026071503)
                .findFirst()
                .orElse(null);

        assertNotNull(migration, "admin platform-role binding migration must be registered");
        String manifest = String.join("\n", migration.getManifest());
        assertTrue(manifest.contains("`u`.`username`='admin'")
                        && manifest.contains("`r`.`code`='super_admin'"),
                "migration must target the admin username and super_admin role");
        assertTrue(manifest.contains("NOT EXISTS") && manifest.contains("system_user_role"),
                "migration must be idempotent and avoid duplicate user-role rows");
        assertTrue(manifest.contains("package_id` = 0"),
                "migration must be restricted to the platform tenant");
    }
}
