package cn.iocoder.yudao.module.skit.framework.schema;

import cn.iocoder.yudao.module.skit.integration.SkitMySqlIntegrationTestBase;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

abstract class SkitPartialMigrationMySqlITBase extends SkitMySqlIntegrationTestBase {

    @Override
    protected final boolean initializeSkitSchemaInBeforeAll() {
        return false;
    }

    protected final void runThrough(int maxVersion) throws Exception {
        new SkitSchemaInitializer(jdbc(), migrationsThrough(maxVersion)).run(null);
    }

    @SuppressWarnings("unchecked")
    protected final SkitSchemaInitializer.Migration migration(int version) throws Exception {
        for (SkitSchemaInitializer.Migration migration : migrationsThrough(Integer.MAX_VALUE)) {
            if (migration.getVersion() == version) {
                return migration;
            }
        }
        throw new IllegalArgumentException("Unknown migration " + version);
    }

    @SuppressWarnings("unchecked")
    protected final void executePrefix(String stepFactory, int count) throws Exception {
        List<Object> steps = migrationSteps(stepFactory);
        assertTrue(steps.size() >= count, "not enough migration steps in " + stepFactory);
        for (int index = 0; index < count; index++) {
            execute(steps.get(index));
        }
    }

    protected final void executeOnly(String stepFactory, int index) throws Exception {
        List<Object> steps = migrationSteps(stepFactory);
        assertTrue(steps.size() > index, "missing migration step " + index + " in " + stepFactory);
        execute(steps.get(index));
    }

    protected final int migrationCount(int version) {
        return jdbc().queryForObject("SELECT COUNT(*) FROM skit_schema_migration WHERE version=?",
                Integer.class, version);
    }

    protected final int tableCount(String table) {
        return jdbc().queryForObject("SELECT COUNT(*) FROM information_schema.TABLES "
                + "WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=?", Integer.class, table);
    }

    protected final int columnCount(String table, String column) {
        return jdbc().queryForObject("SELECT COUNT(*) FROM information_schema.COLUMNS "
                        + "WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=? AND COLUMN_NAME=?",
                Integer.class, table, column);
    }

    protected final String nullable(String table, String column) {
        return jdbc().queryForObject("SELECT IS_NULLABLE FROM information_schema.COLUMNS "
                        + "WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=? AND COLUMN_NAME=?",
                String.class, table, column);
    }

    @SuppressWarnings("unchecked")
    private List<SkitSchemaInitializer.Migration> migrationsThrough(int maxVersion) throws Exception {
        SkitSchemaInitializer initializer = new SkitSchemaInitializer(jdbc());
        Field field = SkitSchemaInitializer.class.getDeclaredField("migrations");
        field.setAccessible(true);
        List<SkitSchemaInitializer.Migration> result = new ArrayList<>();
        for (SkitSchemaInitializer.Migration migration
                : (List<SkitSchemaInitializer.Migration>) field.get(initializer)) {
            if (migration.getVersion() <= maxVersion) {
                result.add(migration);
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private List<Object> migrationSteps(String stepFactory) throws Exception {
        SkitSchemaInitializer initializer = new SkitSchemaInitializer(jdbc());
        Method factory = SkitSchemaInitializer.class.getDeclaredMethod(stepFactory);
        factory.setAccessible(true);
        return (List<Object>) factory.invoke(initializer);
    }

    private void execute(Object step) throws Exception {
        Method execute = step.getClass().getDeclaredMethod("execute");
        execute.setAccessible(true);
        try {
            execute.invoke(step);
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            if (cause instanceof Error) {
                throw (Error) cause;
            }
            throw exception;
        }
    }

}
