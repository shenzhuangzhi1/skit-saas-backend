package cn.iocoder.yudao.module.skit.framework.schema;

import cn.iocoder.yudao.module.skit.dal.dataobject.agent.SkitAgentDO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
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
    void shouldKeepPreviouslyReleasedMigrationChecksumsStable() throws Exception {
        SkitSchemaInitializer initializer = new SkitSchemaInitializer(jdbcTemplate);
        Field field = SkitSchemaInitializer.class.getDeclaredField("migrations");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<SkitSchemaInitializer.Migration> migrations =
                (List<SkitSchemaInitializer.Migration>) field.get(initializer);
        Map<Integer, String> expected = new HashMap<>();
        expected.put(2026071201, "bddb923e2d6f5b5d1def6ff115499d2cc32b59aa68959a11ae04caf2af916651");
        expected.put(2026071250, "aa1000728588be79866149967180d83f6a97d83f02013cfd6db3bc70ebf9a34d");
        expected.put(2026071301, "b507341e6fbd47d275fba15494723510e7c3d410f4a6d1146ac1c594df0ce220");
        expected.put(2026071302, "18cdd108bae5ed0d8700aa31d2483b5030d8a7ac4143b18fa7d8310818f6c6c4");
        expected.put(2026071303, "ece068f30e1b6abe5344f1e6f1f179c55cae0a93c6e3e23bd8f511b7522a5a21");
        expected.put(2026071304, "666a0061892df38cb1cf0e4762d223580703e5f5682eb784775f53fe37f4f0ef");
        expected.put(2026071401, "64e450e4b8048a00b0ce7fbbe9f4b162ec519b5cd3f2c83d12470d92fe72fdbf");

        Set<Integer> verifiedVersions = new HashSet<>();
        for (SkitSchemaInitializer.Migration migration : migrations) {
            if (expected.containsKey(migration.getVersion())) {
                assertEquals(expected.get(migration.getVersion()), migration.getChecksum());
                verifiedVersions.add(migration.getVersion());
            }
        }
        assertEquals(expected.keySet(), verifiedVersions);
    }

    @Test
    void shouldDeclarePolicySnapshotImmutabilityAsSeparateChecksumManifest() throws Exception {
        SkitSchemaInitializer initializer = new SkitSchemaInitializer(jdbcTemplate);
        Field field = SkitSchemaInitializer.class.getDeclaredField("migrations");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<SkitSchemaInitializer.Migration> migrations =
                (List<SkitSchemaInitializer.Migration>) field.get(initializer);

        SkitSchemaInitializer.Migration task2 = null;
        SkitSchemaInitializer.Migration immutability = null;
        for (SkitSchemaInitializer.Migration migration : migrations) {
            if (migration.getVersion() == 2026071401) {
                task2 = migration;
            } else if (migration.getVersion() == 2026071402) {
                immutability = migration;
            }
        }

        assertEquals("64e450e4b8048a00b0ce7fbbe9f4b162ec519b5cd3f2c83d12470d92fe72fdbf",
                task2 == null ? null : task2.getChecksum(), "released Task 2 checksum must remain byte-stable");
        assertEquals("enforce ad policy snapshot immutability",
                immutability == null ? null : immutability.getDescription());
        assertEquals(2, immutability == null ? -1 : immutability.getManifest().size());
        String manifest = immutability == null ? "" : String.join("\n", immutability.getManifest());
        assertTrue(manifest.contains("skit_ad_policy_snapshot"));
        assertTrue(manifest.contains("trg_skit_policy_snapshot_immutable"));
        assertTrue(manifest.contains("trg_skit_policy_snapshot_no_delete"));
        assertTrue(manifest.contains("java.lang.String:UPDATE"));
        assertTrue(manifest.contains("java.lang.String:DELETE"));
        assertTrue(manifest.contains("policy snapshot rows are immutable"));
        assertEquals("11f815a76c7f15bacfc9d9a29a60121f6736ec18bbd712a02a0190ff69c8c18d",
                immutability == null ? null : immutability.getChecksum());
        assertNotEquals(task2 == null ? null : task2.getChecksum(),
                immutability == null ? null : immutability.getChecksum());
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
    void shouldApplyMissingMigrationBelowAlreadyAppliedVersion() {
        List<String> executionOrder = new ArrayList<>();
        SkitSchemaInitializer.Migration compatibility = migration(1250, "compatibility", executionOrder);
        SkitSchemaInitializer.Migration newer = migration(1301, "newer", executionOrder);
        when(jdbcTemplate.queryForList(anyString())).thenReturn(Collections.singletonList(
                appliedMigration(newer.getVersion(), newer.getChecksum())));

        new SkitSchemaInitializer(jdbcTemplate, Arrays.asList(newer, compatibility)).run(null);

        assertEquals(Collections.singletonList("compatibility"), executionOrder);
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
    void shouldValidateEveryInstalledChecksumBeforeExecutingPendingMigration() {
        List<String> executionOrder = new ArrayList<>();
        SkitSchemaInitializer.Migration pending = migration(1, "pending", executionOrder);
        SkitSchemaInitializer.Migration installed = migration(2, "installed", executionOrder);
        when(jdbcTemplate.queryForList(anyString())).thenReturn(Collections.singletonList(
                appliedMigration(installed.getVersion(), "tampered-checksum")));

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> new SkitSchemaInitializer(jdbcTemplate, Arrays.asList(pending, installed)).run(null));

        assertTrue(exception.getMessage().contains("version 2"));
        assertTrue(executionOrder.isEmpty(), "no pending migration may execute before full-history validation");
    }

    @Test
    void shouldRejectUnknownInstalledMigrationVersionBeforeDdl() {
        List<String> executionOrder = new ArrayList<>();
        SkitSchemaInitializer.Migration known = migration(1, "known", executionOrder);
        when(jdbcTemplate.queryForList(anyString())).thenReturn(Collections.singletonList(
                appliedMigration(99, "future-checksum")));

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> new SkitSchemaInitializer(jdbcTemplate, Collections.singletonList(known)).run(null));

        assertTrue(exception.getMessage().contains("Unknown installed schema migration version 99"));
        assertTrue(executionOrder.isEmpty(), "unknown history must stop migration before pending DDL");
    }

    @Test
    void shouldRejectSameNamedGeneratedColumnWithDifferentActiveSlotExpression() throws Exception {
        Map<String, Object> definition = new HashMap<>();
        definition.put("COLUMN_TYPE", "bigint");
        definition.put("IS_NULLABLE", "YES");
        definition.put("COLUMN_DEFAULT", null);
        definition.put("EXTRA", "STORED GENERATED");
        definition.put("GENERATION_EXPRESSION", "case when (`active` = 0) then `ad_account_id` else NULL end");
        when(jdbcTemplate.queryForList(contains("GENERATION_EXPRESSION"),
                eq("skit_ad_callback_key"), eq("active_account_id")))
                .thenReturn(Collections.singletonList(definition));
        SkitSchemaInitializer initializer = new SkitSchemaInitializer(jdbcTemplate, Collections.emptyList());
        Method validator = SkitSchemaInitializer.class.getDeclaredMethod("validateColumnDefinition",
                String.class, String.class, String.class);
        validator.setAccessible(true);

        InvocationTargetException exception = assertThrows(InvocationTargetException.class,
                () -> validator.invoke(initializer, "skit_ad_callback_key", "active_account_id",
                        "bigint GENERATED ALWAYS AS (CASE WHEN `active` = b'1' AND `revoked_at` IS NULL "
                                + "THEN `ad_account_id` ELSE NULL END) STORED"));

        assertTrue(exception.getCause() instanceof IllegalStateException);
        assertTrue(exception.getCause().getMessage().contains("generation"));
    }

    @Test
    void shouldPreserveMeaningfulParenthesesWhenComparingCheckDefinitions() throws Exception {
        when(jdbcTemplate.queryForList(contains("CHECK_CLAUSE"), eq(String.class),
                eq("skit_example"), eq("ck_skit_example")))
                .thenReturn(Collections.singletonList("(`a` = 1 AND `b` = 2) OR `c` = 3"));
        SkitSchemaInitializer initializer = new SkitSchemaInitializer(jdbcTemplate, Collections.emptyList());
        Method addCheck = SkitSchemaInitializer.class.getDeclaredMethod("addCheckIfMissing",
                String.class, String.class, String.class);
        addCheck.setAccessible(true);

        InvocationTargetException exception = assertThrows(InvocationTargetException.class,
                () -> addCheck.invoke(initializer, "skit_example", "ck_skit_example",
                        "`a` = 1 AND (`b` = 2 OR `c` = 3)"));

        assertTrue(exception.getCause() instanceof IllegalStateException);
        assertTrue(exception.getCause().getMessage().contains("Incompatible existing check constraint"));
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
    void shouldNormalizeProductionLegacyIdentitiesWhileKeepingPlatformSuperAdmin() {
        when(jdbcTemplate.queryForList(contains("MIN(`id`) AS `group_id`,`username`"))).thenReturn(Arrays.asList(
                identityMember(104L, 104L, 1L, "test"), identityMember(104L, 111L, 121L, "test")));
        when(jdbcTemplate.queryForList(contains("MIN(`id`) AS `group_id`,`mobile`"))).thenReturn(Arrays.asList(
                identityMember(100L, 100L, 1L, "15601691300"),
                identityMember(100L, 107L, 118L, "15601691300"),
                identityMember(100L, 108L, 119L, "15601691300"),
                identityMember(100L, 109L, 120L, "15601691300"),
                identityMember(100L, 110L, 121L, "15601691300"),
                identityMember(100L, 113L, 122L, "15601691300")));
        when(jdbcTemplate.queryForList(contains("JOIN `system_user_role`")))
                .thenReturn(Collections.singletonList(userIdentity(100L, null)));
        when(jdbcTemplate.queryForList(contains("SELECT DISTINCT `u`.`id` FROM `skit_agent`")))
                .thenReturn(Collections.emptyList());
        when(jdbcTemplate.queryForObject(contains("AND `username` = ?"), eq(Integer.class), anyString()))
                .thenReturn(0);
        stubIdentityRepairUpdates();
        when(jdbcTemplate.update(contains("UPDATE `system_users` SET `mobile` = NULL"),
                anyLong(), anyString())).thenReturn(1);
        SkitSchemaInitializer initializer = new SkitSchemaInitializer(jdbcTemplate, Collections.emptyList());

        initializer.normalizeLegacyActiveUserIdentities();

        verify(jdbcTemplate).update(contains("INSERT INTO `skit_identity_migration_audit`"),
                eq(2026071250), eq("USERNAME"), eq(111L), eq(121L), eq(104L), eq("LOWEST_ID"),
                eq("test"), eq("legacy111"));
        verify(jdbcTemplate).update(contains("UPDATE `system_users` SET `username`"),
                eq("legacy111"), eq(111L), eq("test"));
        verify(jdbcTemplate).update(contains("INSERT INTO `skit_identity_migration_audit`"),
                eq(2026071250), eq("MOBILE"), eq(107L), eq(118L), eq(100L),
                eq("PLATFORM_SUPER_ADMIN"), eq("15601691300"), isNull());
        verify(jdbcTemplate).update(contains("UPDATE `system_users` SET `mobile` = NULL"),
                eq(107L), eq("15601691300"));
        verify(jdbcTemplate, never()).update(contains("UPDATE `system_users` SET `mobile` = NULL"),
                eq(100L), anyString());
    }

    @Test
    void shouldKeepLowestIdWhenDuplicateGroupHasNoProtectedIdentity() {
        when(jdbcTemplate.queryForList(contains("MIN(`id`) AS `group_id`,`username`"))).thenReturn(Arrays.asList(
                identityMember(7L, 7L, 1L, "test"), identityMember(7L, 11L, 1L, "test")));
        when(jdbcTemplate.queryForList(contains("MIN(`id`) AS `group_id`,`mobile`")))
                .thenReturn(Collections.emptyList());
        when(jdbcTemplate.queryForList(contains("JOIN `system_user_role`"))).thenReturn(Collections.emptyList());
        when(jdbcTemplate.queryForList(contains("SELECT DISTINCT `u`.`id` FROM `skit_agent`")))
                .thenReturn(Collections.emptyList());
        when(jdbcTemplate.queryForObject(contains("AND `username` = ?"), eq(Integer.class), anyString()))
                .thenReturn(0);
        stubIdentityRepairUpdates();
        SkitSchemaInitializer initializer = new SkitSchemaInitializer(jdbcTemplate, Collections.emptyList());

        initializer.normalizeLegacyActiveUserIdentities();

        verify(jdbcTemplate).update(contains("UPDATE `system_users` SET `username`"),
                eq("legacy11"), eq(11L), eq("test"));
        verify(jdbcTemplate, never()).update(contains("UPDATE `system_users` SET `username`"),
                anyString(), eq(7L), anyString());
    }

    @Test
    void shouldFreeAgentContactMobileFromLegacyHolderAndRecordReason() {
        when(jdbcTemplate.queryForList(contains("AS `contact_mobile`,`u`.`username`")))
                .thenReturn(Collections.singletonList(agentBinding(200L, 12L, "13800138000",
                        "13800138000", "13800138000")));
        when(jdbcTemplate.queryForList(contains("MIN(`id`) AS `group_id`,`username`")))
                .thenReturn(Collections.emptyList());
        when(jdbcTemplate.queryForList(contains("MIN(`id`) AS `group_id`,`mobile`"))).thenReturn(Arrays.asList(
                identityMember(7L, 7L, 1L, "13800138000"),
                identityMember(7L, 12L, 200L, "13800138000")));
        when(jdbcTemplate.queryForList(contains("JOIN `system_user_role`"))).thenReturn(Collections.emptyList());
        when(jdbcTemplate.queryForList(contains("AND `username` = ?"), eq("13800138000")))
                .thenReturn(Collections.singletonList(identityHolder(12L, 200L, "13800138000")));
        when(jdbcTemplate.queryForList(contains("AND `mobile` = ?"), eq("13800138000")))
                .thenReturn(Arrays.asList(identityHolder(7L, 1L, "13800138000"),
                        identityHolder(12L, 200L, "13800138000")));
        stubIdentityRepairUpdates();
        SkitSchemaInitializer initializer = new SkitSchemaInitializer(jdbcTemplate, Collections.emptyList());

        initializer.normalizeLegacyActiveUserIdentities();

        verify(jdbcTemplate).update(contains("INSERT INTO `skit_identity_migration_audit`"),
                eq(2026071250), eq("MOBILE"), eq(7L), eq(1L), eq(12L),
                eq("AGENT_CONTACT_PHONE_TARGET"), eq("13800138000"), isNull());
    }

    @Test
    void shouldUseAlphanumericSuffixWhenLegacyUsernameCollidesUnderDatabaseCollation() {
        when(jdbcTemplate.queryForList(contains("MIN(`id`) AS `group_id`,`username`"))).thenReturn(Arrays.asList(
                identityMember(7L, 7L, 1L, "test"), identityMember(7L, 11L, 1L, "test")));
        when(jdbcTemplate.queryForList(contains("MIN(`id`) AS `group_id`,`mobile`")))
                .thenReturn(Collections.emptyList());
        when(jdbcTemplate.queryForList(contains("JOIN `system_user_role`"))).thenReturn(Collections.emptyList());
        when(jdbcTemplate.queryForList(contains("SELECT DISTINCT `u`.`id` FROM `skit_agent`")))
                .thenReturn(Collections.emptyList());
        when(jdbcTemplate.queryForObject(contains("AND `username` = ?"), eq(Integer.class), eq("legacy11")))
                .thenReturn(1);
        when(jdbcTemplate.queryForObject(contains("AND `username` = ?"), eq(Integer.class), eq("legacy11x1")))
                .thenReturn(0);
        stubIdentityRepairUpdates();
        SkitSchemaInitializer initializer = new SkitSchemaInitializer(jdbcTemplate, Collections.emptyList());

        initializer.normalizeLegacyActiveUserIdentities();

        verify(jdbcTemplate).update(contains("UPDATE `system_users` SET `username`"),
                eq("legacy11x1"), eq(11L), eq("test"));
    }

    @Test
    void shouldRejectDuplicateProtectedIdentitiesBeforeChangingAnyUser() {
        when(jdbcTemplate.queryForList(contains("AS `contact_mobile`,`u`.`username`")))
                .thenReturn(Collections.singletonList(agentBinding(200L, 12L, "13800138000",
                        "13900139000", "13900139000")));
        when(jdbcTemplate.queryForList(contains("MIN(`id`) AS `group_id`,`username`")))
                .thenReturn(Collections.emptyList());
        when(jdbcTemplate.queryForList(contains("MIN(`id`) AS `group_id`,`mobile`")))
                .thenReturn(Collections.emptyList());
        when(jdbcTemplate.queryForList(contains("JOIN `system_user_role`")))
                .thenReturn(Collections.singletonList(userIdentity(8L, null)));
        when(jdbcTemplate.queryForList(contains("AND `username` = ?"), eq("13800138000")))
                .thenReturn(Collections.singletonList(identityHolder(8L, 1L, "13800138000")));
        when(jdbcTemplate.queryForList(contains("AND `mobile` = ?"), eq("13800138000")))
                .thenReturn(Collections.emptyList());
        SkitSchemaInitializer initializer = new SkitSchemaInitializer(jdbcTemplate, Collections.emptyList());

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                initializer::normalizeLegacyActiveUserIdentities);

        assertTrue(exception.getMessage().contains("Protected duplicate identities"));
        assertTrue(exception.getMessage().contains("13800138000"));
        assertTrue(exception.getMessage().contains("12,8"));
        verify(jdbcTemplate, never()).update(contains("UPDATE `system_users`"), any(Object[].class));
        verify(jdbcTemplate, never()).update(contains("INSERT INTO `skit_identity_migration_audit`"),
                any(Object[].class));
    }

    @Test
    void shouldFailClosedForInvalidAgentAdministratorBinding() {
        Map<String, Object> invalidBinding = new HashMap<>();
        invalidBinding.put("agent_id", 9L);
        invalidBinding.put("tenant_id", 200L);
        invalidBinding.put("contact_user_id", 12L);
        invalidBinding.put("user_tenant_id", 201L);
        when(jdbcTemplate.queryForList(contains("LEFT JOIN `system_tenant`")))
                .thenReturn(Collections.singletonList(invalidBinding));
        SkitSchemaInitializer initializer = new SkitSchemaInitializer(jdbcTemplate, Collections.emptyList());

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                initializer::normalizeLegacyActiveUserIdentities);

        assertTrue(exception.getMessage().contains("incomplete or cross-tenant"));
        assertTrue(exception.getMessage().contains("agent_id=9"));
        verify(jdbcTemplate, never()).update(contains("UPDATE `system_users`"), any(Object[].class));
    }

    @Test
    void shouldSynchronizeUniqueLegacyAgentIdentityToTenantContactMobile() {
        when(jdbcTemplate.queryForList(contains("AS `contact_mobile`,`u`.`username`")))
                .thenReturn(Collections.singletonList(agentBinding(200L, 12L, "13800138000",
                        "legacyAgent", "13900139000")));
        when(jdbcTemplate.queryForList(contains("MIN(`id`) AS `group_id`,`username`")))
                .thenReturn(Collections.emptyList());
        when(jdbcTemplate.queryForList(contains("MIN(`id`) AS `group_id`,`mobile`")))
                .thenReturn(Collections.emptyList());
        when(jdbcTemplate.queryForList(contains("JOIN `system_user_role`"))).thenReturn(Collections.emptyList());
        when(jdbcTemplate.queryForList(contains("AND `username` = ?"), eq("13800138000")))
                .thenReturn(Collections.emptyList());
        when(jdbcTemplate.queryForList(contains("AND `mobile` = ?"), eq("13800138000")))
                .thenReturn(Collections.emptyList());
        stubIdentityRepairUpdates();
        SkitSchemaInitializer initializer = new SkitSchemaInitializer(jdbcTemplate, Collections.emptyList());

        initializer.normalizeLegacyActiveUserIdentities();

        verify(jdbcTemplate).update(contains("UPDATE `system_users` SET `username`"),
                eq("13800138000"), eq(12L), eq("legacyAgent"));
        verify(jdbcTemplate).update(contains("UPDATE `system_users` SET `mobile` = ?"),
                eq("13800138000"), eq(12L), eq("13900139000"));
        verify(jdbcTemplate).update(contains("INSERT INTO `skit_identity_migration_audit`"),
                eq(2026071250), eq("USERNAME"), eq(12L), eq(200L), eq(12L),
                eq("AGENT_CONTACT_PHONE_SYNC"), eq("legacyAgent"), eq("13800138000"));
    }

    @Test
    void shouldSynchronizeNullAgentMobileWithNullSafeGuard() {
        when(jdbcTemplate.queryForList(contains("AS `contact_mobile`,`u`.`username`")))
                .thenReturn(Collections.singletonList(agentBinding(200L, 12L, "13800138000",
                        "13800138000", null)));
        when(jdbcTemplate.queryForList(contains("MIN(`id`) AS `group_id`")))
                .thenReturn(Collections.emptyList());
        when(jdbcTemplate.queryForList(contains("JOIN `system_user_role`"))).thenReturn(Collections.emptyList());
        when(jdbcTemplate.queryForList(contains("AND `username` = ?"), eq("13800138000")))
                .thenReturn(Collections.singletonList(identityHolder(12L, 200L, "13800138000")));
        when(jdbcTemplate.queryForList(contains("AND `mobile` = ?"), eq("13800138000")))
                .thenReturn(Collections.emptyList());
        stubIdentityRepairUpdates();
        when(jdbcTemplate.update(contains("INSERT INTO `skit_identity_migration_audit`"),
                eq(2026071250), eq("MOBILE"), eq(12L), eq(200L), eq(12L),
                eq("AGENT_CONTACT_PHONE_SYNC"), isNull(), eq("13800138000"))).thenReturn(1);
        when(jdbcTemplate.update(contains("AND `mobile` <=> ?"),
                eq("13800138000"), eq(12L), isNull())).thenReturn(1);
        SkitSchemaInitializer initializer = new SkitSchemaInitializer(jdbcTemplate, Collections.emptyList());

        initializer.normalizeLegacyActiveUserIdentities();

        verify(jdbcTemplate).update(contains("AND `mobile` <=> ?"),
                eq("13800138000"), eq(12L), isNull());
        verify(jdbcTemplate).update(contains("INSERT INTO `skit_identity_migration_audit`"),
                eq(2026071250), eq("MOBILE"), eq(12L), eq(200L), eq(12L),
                eq("AGENT_CONTACT_PHONE_SYNC"), isNull(), eq("13800138000"));
    }

    @Test
    void shouldRejectTwoAgentsSharingOneContactMobileBeforeWrites() {
        when(jdbcTemplate.queryForList(contains("AS `contact_mobile`,`u`.`username`"))).thenReturn(Arrays.asList(
                agentBinding(200L, 12L, "13800138000", "agent12", "13900139000"),
                agentBinding(201L, 13L, "13800138000", "agent13", "13700137000")));
        SkitSchemaInitializer initializer = new SkitSchemaInitializer(jdbcTemplate, Collections.emptyList());

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                initializer::normalizeLegacyActiveUserIdentities);

        assertTrue(exception.getMessage().contains("same login mobile"));
        assertTrue(exception.getMessage().contains("12,13"));
        verify(jdbcTemplate, never()).update(contains("INSERT INTO `skit_identity_migration_audit`"),
                any(Object[].class));
        verify(jdbcTemplate, never()).update(contains("UPDATE `system_users`"), any(Object[].class));
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
    void shouldRefuseDomainSingletonIndexesWhenActiveDuplicatesExist() {
        when(jdbcTemplate.queryForList(contains("FROM `skit_app_release_profile`")))
                .thenReturn(Collections.singletonList(duplicateIdentity("20", "7,11")));
        when(jdbcTemplate.queryForList(contains("FROM `skit_commission_plan`")))
                .thenReturn(Collections.singletonList(duplicateIdentity("20", "8,12")));
        SkitSchemaInitializer initializer = new SkitSchemaInitializer(jdbcTemplate, Collections.emptyList());

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                initializer::migrateDomainIntegrityConstraints);

        assertTrue(exception.getMessage().contains("App release profiles"));
        assertTrue(exception.getMessage().contains("commission plans"));
        assertTrue(exception.getMessage().contains("7,11"));
        assertTrue(exception.getMessage().contains("8,12"));
        verify(jdbcTemplate, never()).execute(contains("uk_skit_app_release_profile_active_tenant"));
        verify(jdbcTemplate, never()).execute(contains("uk_skit_commission_plan_active_tenant"));
    }

    @Test
    void shouldCreateDomainSingletonConstraintsAndQueryIndexes() {
        when(jdbcTemplate.queryForList(contains("FROM `skit_app_release_profile`")))
                .thenReturn(Collections.emptyList());
        when(jdbcTemplate.queryForList(contains("FROM `skit_commission_plan`")))
                .thenReturn(Collections.emptyList());
        when(jdbcTemplate.queryForObject(contains("information_schema.COLUMNS"), eq(Integer.class),
                anyString(), anyString())).thenReturn(0);
        when(jdbcTemplate.queryForObject(contains("information_schema.STATISTICS"), eq(Integer.class),
                anyString(), anyString())).thenReturn(0);
        SkitSchemaInitializer initializer = new SkitSchemaInitializer(jdbcTemplate, Collections.emptyList());

        initializer.migrateDomainIntegrityConstraints();

        verify(jdbcTemplate).execute(contains("ALTER TABLE `skit_app_release_profile` ADD COLUMN `active_tenant_id`"));
        verify(jdbcTemplate).execute(contains("UNIQUE INDEX `uk_skit_app_release_profile_active_tenant`"));
        verify(jdbcTemplate).execute(contains("ALTER TABLE `skit_commission_plan` ADD COLUMN `active_tenant_id`"));
        verify(jdbcTemplate).execute(contains("UNIQUE INDEX `uk_skit_commission_plan_active_tenant`"));
        verify(jdbcTemplate).execute(contains("INDEX `idx_skit_member_tenant_status_id`"));
        verify(jdbcTemplate).execute(contains("INDEX `idx_skit_commission_plan_status_version`"));
        verify(jdbcTemplate).execute(contains("INDEX `idx_skit_ledger_member_type_time_id`"));
        verify(jdbcTemplate).execute(contains("INDEX `idx_skit_ledger_beneficiary_time_id`"));
        verify(jdbcTemplate).execute(contains("INDEX `idx_skit_revenue_provider_time_id`"));
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

    private static Map<String, Object> userIdentity(Long id, String username) {
        Map<String, Object> row = new HashMap<>();
        row.put("id", id);
        if (username != null) {
            row.put("username", username);
        }
        return row;
    }

    private static Map<String, Object> identityMember(Long groupId, Long id, Long tenantId, String value) {
        Map<String, Object> row = new HashMap<>();
        row.put("group_id", groupId);
        row.put("id", id);
        row.put("tenant_id", tenantId);
        row.put("identity_value", value);
        return row;
    }

    private static Map<String, Object> agentBinding(Long tenantId, Long userId, String contactMobile,
                                                    String username, String mobile) {
        Map<String, Object> row = new HashMap<>();
        row.put("tenant_id", tenantId);
        row.put("user_id", userId);
        row.put("contact_mobile", contactMobile);
        row.put("username", username);
        row.put("mobile", mobile);
        return row;
    }

    private static Map<String, Object> identityHolder(Long id, Long tenantId, String value) {
        Map<String, Object> row = new HashMap<>();
        row.put("id", id);
        row.put("tenant_id", tenantId);
        row.put("identity_value", value);
        return row;
    }

    private void stubIdentityRepairUpdates() {
        when(jdbcTemplate.update(contains("INSERT INTO `skit_identity_migration_audit`"),
                any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(1);
        when(jdbcTemplate.update(contains("UPDATE `system_users` SET `username`"),
                any(), any(), any())).thenReturn(1);
        when(jdbcTemplate.update(contains("UPDATE `system_users` SET `mobile` = NULL"),
                anyLong(), anyString())).thenReturn(1);
        when(jdbcTemplate.update(contains("UPDATE `system_users` SET `mobile` = ?"),
                any(), any(), any())).thenReturn(1);
    }

}
