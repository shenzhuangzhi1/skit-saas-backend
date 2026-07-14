package cn.iocoder.yudao.module.skit.framework.schema;

import cn.iocoder.yudao.module.skit.integration.SkitMySqlIntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkitAdBootstrapSchemaMySqlIT extends SkitMySqlIntegrationTestBase {

    private static final String BEGIN = "-- SKIT_CANONICAL_SCHEMA_BEGIN";
    private static final String END = "-- SKIT_CANONICAL_SCHEMA_END";

    @Override
    protected boolean initializeSkitSchemaInBeforeAll() {
        return false;
    }

    @Test
    void standaloneAndMainBootstrapMatchInitializerCriticalSignature() throws Exception {
        Path repository = repositoryRoot();
        String standalone = canonicalBlock(repository.resolve("sql/mysql/skit-saas.sql"));
        String main = canonicalBlock(repository.resolve("sql/mysql/ruoyi-vue-pro.sql"));
        assertEquals(standalone, main,
                "the standalone and main bootstrap must carry one byte-identical canonical Skit schema block");
        for (String table : SkitAdSchemaSignature.expectedFingerprints().keySet()) {
            assertTrue(standalone.contains("CREATE TABLE IF NOT EXISTS `" + table + "`"),
                    "bootstrap is missing Task 2 table " + table);
        }

        installAndVerify(standalone);
        dropSkitTables();
        installAndVerify(main);
        dropSkitTables();

        // Runtime-first installation must expose the same singleton keys before either bootstrap is applied.
        initializeSkitSchema();
        assertLegacySingletonIndexes();
        assertPolicySnapshotImmutabilityTriggers();
        executeBootstrap(standalone);
        executeBootstrap(standalone);
        initializeSkitSchema();
        new SkitSchemaInitializer(jdbc()).validateTask2TableSignatures(true);
        assertLegacySingletonIndexes();
        assertPolicySnapshotImmutabilityTriggers();
    }

    private void installAndVerify(String script) throws Exception {
        executeBootstrap(script);
        executeBootstrap(script);
        new SkitSchemaInitializer(jdbc()).validateTask2TableSignatures(true);
        assertLegacySingletonIndexes();

        // A direct bootstrap and a runtime-managed install must converge. Running the initializer
        // twice also proves that its immutable manifest accepts the already-final SQL schema.
        initializeSkitSchema();
        initializeSkitSchema();
        new SkitSchemaInitializer(jdbc()).validateTask2TableSignatures(true);
        assertLegacySingletonIndexes();
        assertPolicySnapshotImmutabilityTriggers();
    }

    private void assertLegacySingletonIndexes() {
        assertExactUniqueIndex("skit_admin_record", "uk_skit_admin_record_tenant_page_row",
                "tenant_id,page_key,row_key");
        assertExactUniqueIndex("skit_system_config", "uk_skit_system_config_tenant", "tenant_id");
    }

    private void assertPolicySnapshotImmutabilityTriggers() {
        Integer triggerCount = jdbc().queryForObject("SELECT COUNT(*) FROM information_schema.TRIGGERS "
                        + "WHERE TRIGGER_SCHEMA=DATABASE() AND EVENT_OBJECT_TABLE='skit_ad_policy_snapshot' "
                        + "AND TRIGGER_NAME IN ('trg_skit_policy_snapshot_immutable',"
                        + "'trg_skit_policy_snapshot_no_delete')",
                Integer.class);
        assertEquals(2, triggerCount);
    }

    private void assertExactUniqueIndex(String table, String index, String columns) {
        String actual = jdbc().queryForObject("SELECT CONCAT(MIN(NON_UNIQUE),':',"
                        + "GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX SEPARATOR ',')) "
                        + "FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() "
                        + "AND TABLE_NAME=? AND INDEX_NAME=?",
                String.class, table, index);
        assertEquals("0:" + columns, actual, table + "." + index);
    }

    private void executeBootstrap(String script) throws Exception {
        try (Connection connection = dataSource().getConnection()) {
            int delimiterStart = script.indexOf("DELIMITER $$");
            if (delimiterStart < 0) {
                executeOrdinarySql(connection, script);
                return;
            }
            int triggerStart = delimiterStart + "DELIMITER $$".length();
            int delimiterEnd = script.indexOf("DELIMITER ;", triggerStart);
            assertTrue(delimiterEnd > triggerStart, "bootstrap trigger delimiter is not closed");

            executeOrdinarySql(connection, script.substring(0, delimiterStart));
            try (Statement statement = connection.createStatement()) {
                for (String trigger : script.substring(triggerStart, delimiterEnd).split("\\$\\$")) {
                    if (!trigger.trim().isEmpty()) {
                        statement.execute(trigger.trim());
                    }
                }
            }
            executeOrdinarySql(connection, script.substring(delimiterEnd + "DELIMITER ;".length()));
        }
    }

    private void executeOrdinarySql(Connection connection, String script) {
        if (script.replaceAll("(?m)^\\s*--.*$", "").trim().isEmpty()) {
            return;
        }
        ScriptUtils.executeSqlScript(connection, new EncodedResource(
                new ByteArrayResource(script.getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8));
    }

    private void dropSkitTables() throws Exception {
        List<String> tables = jdbc().queryForList("SELECT TABLE_NAME FROM information_schema.TABLES "
                + "WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME LIKE 'skit_%'", String.class);
        StringBuilder script = new StringBuilder("SET FOREIGN_KEY_CHECKS=0;\n");
        for (String table : tables) {
            script.append("DROP TABLE `").append(table.replace("`", "``")).append("`;\n");
        }
        script.append("SET FOREIGN_KEY_CHECKS=1;\n");
        executeBootstrap(script.toString());
    }

    private static Path repositoryRoot() {
        Path candidate = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        while (candidate != null && !Files.isRegularFile(candidate.resolve("sql/mysql/skit-saas.sql"))) {
            candidate = candidate.getParent();
        }
        assertNotNull(candidate, "could not locate repository SQL bootstraps from user.dir");
        return candidate;
    }

    private static String canonicalBlock(Path file) throws Exception {
        String source = new String(Files.readAllBytes(file), StandardCharsets.UTF_8)
                .replace("\r\n", "\n");
        int begin = source.indexOf(BEGIN);
        int end = source.indexOf(END, begin);
        assertTrue(begin >= 0 && end > begin, "canonical Skit schema markers are missing in " + file);
        return source.substring(begin, end + END.length());
    }

}
