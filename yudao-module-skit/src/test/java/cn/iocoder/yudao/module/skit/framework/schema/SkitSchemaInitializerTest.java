package cn.iocoder.yudao.module.skit.framework.schema;

import cn.iocoder.yudao.module.skit.dal.dataobject.agent.SkitAgentDO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SkitSchemaInitializerTest {

    private JdbcTemplate jdbcTemplate;
    private Connection lockConnection;
    private PreparedStatement acquireLockStatement;
    private PreparedStatement releaseLockStatement;
    private ResultSet acquireLockResult;
    private ResultSet releaseLockResult;

    @BeforeEach
    void setUp() throws Exception {
        jdbcTemplate = mock(JdbcTemplate.class);
        lockConnection = mock(Connection.class);
        acquireLockStatement = mock(PreparedStatement.class);
        releaseLockStatement = mock(PreparedStatement.class);
        acquireLockResult = mock(ResultSet.class);
        releaseLockResult = mock(ResultSet.class);
        when(lockConnection.prepareStatement("SELECT GET_LOCK(?, ?)")).thenReturn(acquireLockStatement);
        when(lockConnection.prepareStatement("SELECT RELEASE_LOCK(?)")).thenReturn(releaseLockStatement);
        when(acquireLockStatement.executeQuery()).thenReturn(acquireLockResult);
        when(releaseLockStatement.executeQuery()).thenReturn(releaseLockResult);
        when(acquireLockResult.next()).thenReturn(true);
        when(acquireLockResult.getInt(1)).thenReturn(1);
        when(releaseLockResult.next()).thenReturn(true);
        when(releaseLockResult.getInt(1)).thenReturn(1);
        when(jdbcTemplate.queryForList(anyString())).thenReturn(Collections.emptyList());
        when(jdbcTemplate.execute(any(ConnectionCallback.class))).thenAnswer(invocation -> {
            ConnectionCallback<?> callback = invocation.getArgument(0);
            return callback.doInConnection(lockConnection);
        });
    }

    @Test
    void shouldExecutePendingMigrationsInVersionOrder() {
        List<String> executionOrder = new ArrayList<>();
        SkitSchemaInitializer.Migration third = migration(3, "third", executionOrder);
        SkitSchemaInitializer.Migration first = migration(1, "first", executionOrder);
        SkitSchemaInitializer.Migration second = migration(2, "second", executionOrder);
        when(jdbcTemplate.queryForList(anyString())).thenReturn(Collections.emptyList());

        new SkitSchemaInitializer(jdbcTemplate, Arrays.asList(third, first, second)).run(null);

        assertEquals(Arrays.asList("first", "second", "third"), executionOrder);
    }

    @Test
    void shouldAcquireAndReleaseAdvisoryLockAroundMigrations() throws Exception {
        List<String> events = new ArrayList<>();
        when(acquireLockStatement.executeQuery()).thenAnswer(invocation -> {
            events.add("acquire");
            return acquireLockResult;
        });
        when(releaseLockStatement.executeQuery()).thenAnswer(invocation -> {
            events.add("release");
            return releaseLockResult;
        });
        SkitSchemaInitializer.Migration migration = migration(1, "apply", events);

        new SkitSchemaInitializer(jdbcTemplate, Collections.singletonList(migration)).run(null);

        assertEquals(Arrays.asList("acquire", "apply", "release"), events);
        verify(acquireLockStatement).setString(1, "skit_schema_migration");
        verify(acquireLockStatement).setInt(2, 10);
        verify(releaseLockStatement).setString(1, "skit_schema_migration");
    }

    @Test
    void shouldReleaseAdvisoryLockWhenMigrationFails() throws Exception {
        SkitSchemaInitializer.Migration migration = new SkitSchemaInitializer.Migration(
                1, "fails", Collections.singletonList("SQL:migration-fails"), () -> {
            throw new IllegalStateException("migration failed");
        });

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> new SkitSchemaInitializer(jdbcTemplate, Collections.singletonList(migration)).run(null));

        assertEquals("migration failed", exception.getMessage());
        verify(releaseLockStatement).executeQuery();
    }

    @Test
    void shouldStopImmediatelyWhenAdvisoryLockCannotBeAcquired() throws Exception {
        when(acquireLockResult.getInt(1)).thenReturn(0);
        List<String> executionOrder = new ArrayList<>();
        SkitSchemaInitializer.Migration migration = migration(1, "must-not-run", executionOrder);

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> new SkitSchemaInitializer(jdbcTemplate, Collections.singletonList(migration)).run(null));

        assertTrue(exception.getMessage().contains("Could not acquire"));
        assertTrue(executionOrder.isEmpty());
        verify(jdbcTemplate, never()).execute(contains("CREATE TABLE IF NOT EXISTS `skit_schema_migration`"));
        verify(releaseLockStatement, never()).executeQuery();
    }

    @Test
    void shouldSkipAlreadyAppliedMigration() {
        List<String> executionOrder = new ArrayList<>();
        SkitSchemaInitializer.Migration first = migration(1, "first", executionOrder);
        SkitSchemaInitializer.Migration second = migration(2, "second", executionOrder);
        when(jdbcTemplate.queryForList(anyString())).thenReturn(Collections.singletonList(
                appliedMigration(first.getVersion(), first.getChecksum())));

        new SkitSchemaInitializer(jdbcTemplate, Arrays.asList(first, second)).run(null);

        assertEquals(Collections.singletonList("second"), executionOrder);
    }

    @Test
    void shouldFailBeforeExecutionWhenAppliedChecksumDiffers() {
        List<String> executionOrder = new ArrayList<>();
        SkitSchemaInitializer.Migration migration = migration(1, "first", executionOrder);
        when(jdbcTemplate.queryForList(anyString())).thenReturn(Collections.singletonList(
                appliedMigration(migration.getVersion(), "stored-checksum")));

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> new SkitSchemaInitializer(jdbcTemplate, Collections.singletonList(migration)).run(null));

        assertTrue(exception.getMessage().contains("version 1"));
        assertTrue(exception.getMessage().contains("stored-checksum"));
        assertTrue(exception.getMessage().contains(migration.getChecksum()));
        assertTrue(executionOrder.isEmpty());
    }

    @Test
    void shouldDetectChecksumMismatchWhenMigrationManifestChanges() {
        List<String> executionOrder = new ArrayList<>();
        SkitSchemaInitializer.Migration installed = new SkitSchemaInitializer.Migration(
                1, "same description", Arrays.asList("SQL:SELECT 1", "PARAM:String:first"),
                () -> executionOrder.add("installed"));
        SkitSchemaInitializer.Migration changed = new SkitSchemaInitializer.Migration(
                1, "same description", Arrays.asList("SQL:SELECT 2", "PARAM:String:first"),
                () -> executionOrder.add("changed"));
        when(jdbcTemplate.queryForList(anyString())).thenReturn(Collections.singletonList(
                appliedMigration(installed.getVersion(), installed.getChecksum())));

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> new SkitSchemaInitializer(jdbcTemplate, Collections.singletonList(changed)).run(null));

        assertNotEquals(installed.getChecksum(), changed.getChecksum());
        assertThrows(UnsupportedOperationException.class, () -> installed.getManifest().add("SQL:SELECT 3"));
        assertTrue(exception.getMessage().contains("checksum mismatch"));
        assertTrue(executionOrder.isEmpty());
    }

    @Test
    void shouldReportDuplicateActiveUserIdentityValuesAndIds() {
        when(jdbcTemplate.queryForList(contains("GROUP BY `username`"))).thenReturn(Collections.singletonList(
                duplicateIdentity("duplicate-user", "7,11")));
        when(jdbcTemplate.queryForList(contains("GROUP BY `mobile`"))).thenReturn(Collections.singletonList(
                duplicateIdentity("13800138000", "8,12")));
        SkitSchemaInitializer initializer = new SkitSchemaInitializer(jdbcTemplate, Collections.emptyList());

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                initializer::validateNoActiveUserIdentityDuplicates);

        assertTrue(exception.getMessage().contains("duplicate-user"));
        assertTrue(exception.getMessage().contains("7,11"));
        assertTrue(exception.getMessage().contains("13800138000"));
        assertTrue(exception.getMessage().contains("8,12"));
        assertTrue(exception.getMessage().contains("Resolve the duplicates"));
    }

    @Test
    void shouldAddPackageCodeAndAgentArchiveColumns() {
        when(jdbcTemplate.queryForObject(contains("information_schema.COLUMNS"), eq(Integer.class),
                anyString(), anyString())).thenReturn(0);
        when(jdbcTemplate.queryForObject(contains("information_schema.STATISTICS"), eq(Integer.class),
                anyString(), anyString())).thenReturn(0);
        SkitSchemaInitializer initializer = new SkitSchemaInitializer(jdbcTemplate, Collections.emptyList());

        initializer.migrateLifecycleColumns();

        verify(jdbcTemplate).execute(contains("ALTER TABLE `system_tenant_package` ADD COLUMN `code`"));
        verify(jdbcTemplate).execute(contains("ALTER TABLE `system_tenant_package` ADD COLUMN `active_code`"));
        verify(jdbcTemplate).execute(contains("UNIQUE INDEX `uk_system_tenant_package_active_code`"));
        verify(jdbcTemplate).execute(contains("ALTER TABLE `skit_agent` ADD COLUMN `archived_time`"));
        verify(jdbcTemplate).execute(contains("ALTER TABLE `skit_agent` ADD COLUMN `archived_by`"));
    }

    @Test
    void shouldRefuseIdentityIndexesBeforeCreatingThemWhenDuplicatesExist() {
        when(jdbcTemplate.queryForList(contains("GROUP BY `username`"))).thenReturn(Collections.singletonList(
                duplicateIdentity("duplicate-user", "7,11")));
        when(jdbcTemplate.queryForList(contains("GROUP BY `mobile`"))).thenReturn(Collections.emptyList());
        SkitSchemaInitializer initializer = new SkitSchemaInitializer(jdbcTemplate, Collections.emptyList());

        assertThrows(IllegalStateException.class, initializer::migrateActiveUserIdentityConstraints);

        verify(jdbcTemplate, never()).execute(contains("uk_system_users_active_username"));
        verify(jdbcTemplate, never()).execute(contains("uk_system_users_active_mobile"));
    }

    @Test
    void shouldCreateGeneratedActiveIdentityColumnsAndUniqueIndexes() {
        when(jdbcTemplate.queryForList(contains("GROUP BY `username`"))).thenReturn(Collections.emptyList());
        when(jdbcTemplate.queryForList(contains("GROUP BY `mobile`"))).thenReturn(Collections.emptyList());
        when(jdbcTemplate.queryForObject(contains("information_schema.COLUMNS"), eq(Integer.class),
                anyString(), anyString())).thenReturn(0);
        when(jdbcTemplate.queryForObject(contains("information_schema.STATISTICS"), eq(Integer.class),
                anyString(), anyString())).thenReturn(0);
        SkitSchemaInitializer initializer = new SkitSchemaInitializer(jdbcTemplate, Collections.emptyList());

        initializer.migrateActiveUserIdentityConstraints();

        verify(jdbcTemplate).execute(contains("ADD COLUMN `active_username` varchar(30) GENERATED ALWAYS AS"));
        verify(jdbcTemplate).execute(contains("ADD COLUMN `active_mobile` varchar(11) GENERATED ALWAYS AS"));
        verify(jdbcTemplate).execute(contains("UNIQUE INDEX `uk_system_users_active_username`"));
        verify(jdbcTemplate).execute(contains("UNIQUE INDEX `uk_system_users_active_mobile`"));
    }

    @Test
    void shouldSeedStableStandardPackageWithoutNumericId() {
        SkitSchemaInitializer initializer = new SkitSchemaInitializer(jdbcTemplate, Collections.emptyList());

        initializer.seedStandardAgentPackage();

        verify(jdbcTemplate).update(contains("INSERT INTO `system_tenant_package` (`code`, `name`, `status`, "
                        + "`menu_ids`)"), eq("SKIT_AGENT_STANDARD"), eq("代理商标准套餐"), eq(0), eq("[]"));
        verify(jdbcTemplate).update(contains("ON DUPLICATE KEY UPDATE `name` = VALUES(`name`)"),
                eq("SKIT_AGENT_STANDARD"), eq("代理商标准套餐"), eq(0), eq("[]"));
    }

    @Test
    void shouldExposeAgentArchiveAuditFieldsOnModel() {
        LocalDateTime archivedTime = LocalDateTime.of(2026, 7, 13, 12, 30);
        SkitAgentDO agent = new SkitAgentDO();

        agent.setArchivedTime(archivedTime);
        agent.setArchivedBy(42L);

        assertEquals(archivedTime, agent.getArchivedTime());
        assertEquals(42L, agent.getArchivedBy());
    }

    private static SkitSchemaInitializer.Migration migration(int version, String description,
                                                               List<String> executionOrder) {
        return new SkitSchemaInitializer.Migration(version, description,
                Collections.singletonList("EVENT:" + description),
                () -> executionOrder.add(description));
    }

    private static Map<String, Object> appliedMigration(int version, String checksum) {
        Map<String, Object> row = new HashMap<>();
        row.put("version", version);
        row.put("checksum", checksum);
        return row;
    }

    private static Map<String, Object> duplicateIdentity(String value, String ids) {
        Map<String, Object> row = new HashMap<>();
        row.put("duplicate_value", value);
        row.put("duplicate_ids", ids);
        return row;
    }

}
