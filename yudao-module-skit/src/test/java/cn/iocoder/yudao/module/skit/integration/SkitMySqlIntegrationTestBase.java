package cn.iocoder.yudao.module.skit.integration;

import cn.iocoder.yudao.module.skit.framework.schema.SkitSchemaInitializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;

import javax.sql.DataSource;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * Reusable real-MySQL foundation for Skit integration tests.
 *
 * <p>This base intentionally does not extend the project's H2-backed unit-test
 * base or activate the {@code unit-test} profile. It creates a dedicated MySQL
 * schema for each concrete test class and applies the production Skit schema
 * initializer after installing only its prerequisite system tables.</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class SkitMySqlIntegrationTestBase {

    private static final String MYSQL_IMAGE = "mysql:8.0.36";

    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>(MYSQL_IMAGE)
            .withDatabaseName("skit_integration_bootstrap")
            .withUsername("root")
            .withPassword("skit-integration");

    private static final Object MYSQL_LOCK = new Object();

    /**
     * Starts one MySQL container for the whole Failsafe JVM instead of letting
     * the JUnit Testcontainers extension stop and restart it for every test
     * class. Each class still receives an isolated database schema below.
     */
    private static void ensureMySqlStarted() {
        if (MYSQL.isRunning()) {
            return;
        }
        synchronized (MYSQL_LOCK) {
            if (!MYSQL.isRunning()) {
                MYSQL.start();
            }
        }
    }

    private String schemaName;
    private DriverManagerDataSource dataSource;
    private JdbcTemplate jdbcTemplate;
    private TransactionTemplate transactionTemplate;

    @BeforeAll
    final void prepareMySqlSchema() {
        ensureMySqlStarted();
        schemaName = schemaNameFor(getClass());
        JdbcTemplate adminJdbc = new JdbcTemplate(dataSource(jdbcUrlFor("mysql")));
        adminJdbc.execute("DROP DATABASE IF EXISTS `" + schemaName + "`");
        adminJdbc.execute("CREATE DATABASE `" + schemaName
                + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");

        dataSource = dataSource(jdbcUrlFor(schemaName));
        jdbcTemplate = new JdbcTemplate(dataSource);
        transactionTemplate = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        try {
            SkitMySqlPrerequisiteFixture.install(jdbcTemplate);
            beforeSkitSchemaInitialization(jdbcTemplate);
            if (initializeSkitSchemaInBeforeAll()) {
                initializeSkitSchema();
            }
        } catch (RuntimeException | Error exception) {
            adminJdbc.execute("DROP DATABASE IF EXISTS `" + schemaName + "`");
            throw exception;
        }
    }

    @AfterAll
    final void dropMySqlSchema() {
        if (schemaName == null || !MYSQL.isRunning()) {
            return;
        }
        new JdbcTemplate(dataSource(jdbcUrlFor("mysql")))
                .execute("DROP DATABASE IF EXISTS `" + schemaName + "`");
    }

    protected final JdbcTemplate jdbc() {
        return jdbcTemplate;
    }

    /**
     * Installs a legacy fixture after system prerequisites but before the production initializer.
     * Normal integration tests intentionally inherit the no-op implementation.
     */
    protected void beforeSkitSchemaInitialization(JdbcTemplate jdbc) {
    }

    /**
     * Lets a migration-failure test invoke the initializer inside its assertion.
     */
    protected boolean initializeSkitSchemaInBeforeAll() {
        return true;
    }

    protected final void initializeSkitSchema() {
        new SkitSchemaInitializer(jdbcTemplate).run(null);
    }

    protected final DataSource dataSource() {
        return dataSource;
    }

    protected final <T> T inTransaction(Supplier<T> work) {
        return transactionTemplate.execute(status -> work.get());
    }

    protected final void inTransaction(Runnable work) {
        transactionTemplate.executeWithoutResult(status -> work.run());
    }

    private DriverManagerDataSource dataSource(String jdbcUrl) {
        DriverManagerDataSource result = new DriverManagerDataSource();
        result.setUrl(jdbcUrl);
        result.setUsername(MYSQL.getUsername());
        result.setPassword(MYSQL.getPassword());
        return result;
    }

    private String jdbcUrlFor(String databaseName) {
        String containerJdbcUrl = MYSQL.getJdbcUrl();
        int queryIndex = containerJdbcUrl.indexOf('?');
        String baseUrl = queryIndex < 0 ? containerJdbcUrl : containerJdbcUrl.substring(0, queryIndex);
        String query = queryIndex < 0 ? "" : containerJdbcUrl.substring(queryIndex);
        int databaseSeparator = baseUrl.lastIndexOf('/');
        return baseUrl.substring(0, databaseSeparator + 1) + databaseName + query;
    }

    private static String schemaNameFor(Class<?> testClass) {
        String simpleName = testClass.getSimpleName().replaceAll("[^A-Za-z0-9]", "_")
                .toLowerCase(Locale.ROOT);
        if (simpleName.length() > 40) {
            simpleName = simpleName.substring(0, 40);
        }
        return "skit_it_" + simpleName + "_" + Integer.toHexString(testClass.getName().hashCode());
    }

}
