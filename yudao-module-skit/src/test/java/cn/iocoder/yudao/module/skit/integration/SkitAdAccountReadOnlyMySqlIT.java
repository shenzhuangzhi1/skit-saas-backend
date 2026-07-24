package cn.iocoder.yudao.module.skit.integration;

import cn.hutool.crypto.SecureUtil;
import cn.hutool.crypto.symmetric.AES;
import cn.iocoder.yudao.framework.mybatis.core.type.EncryptTypeHandler;
import cn.iocoder.yudao.module.skit.dal.dataobject.ad.SkitAdAccountDO;
import cn.iocoder.yudao.module.skit.dal.mysql.ad.SkitAdAccountMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkitAdAccountReadOnlyMySqlIT extends SkitMySqlIntegrationTestBase {

    private static final long TENANT_ID = 97801L;
    private static final long ACCOUNT_ID = 9780101L;
    private static final String PLACEMENT_ID = "read-only-placement";
    private static final long ENCRYPTION_TENANT_ID = 97802L;
    private static final long ENCRYPTION_ACCOUNT_ID = 9780201L;
    private static final String LONG_APP_KEY =
            String.join("", Collections.nCopies(255, "\u754c"));
    private static final String INITIAL_CONFIG = "{\"placementId\":\"before-update\"}";
    private static final String UPDATED_CONFIG = "{\"placementId\":\"after-update\"}";
    private static final byte[] FIELD_ENCRYPTION_KEY =
            "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.US_ASCII);

    private SkitAdAccountMapper accountMapper;
    private TransactionTemplate readOnlyTransaction;
    private Field encryptorField;
    private AES previousEncryptor;

    @BeforeAll
    void createMapperAndFixture() throws Exception {
        encryptorField = EncryptTypeHandler.class.getDeclaredField("aes");
        encryptorField.setAccessible(true);
        previousEncryptor = (AES) encryptorField.get(null);
        encryptorField.set(null, SecureUtil.aes(FIELD_ENCRYPTION_KEY));

        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(
                configuration, "skit-ad-account-read-only-mysql-it");
        assistant.setCurrentNamespace(SkitAdAccountMapper.class.getName());
        TableInfoHelper.initTableInfo(assistant, SkitAdAccountDO.class);
        configuration.addMapper(SkitAdAccountMapper.class);
        MybatisSqlSessionFactoryBean factoryBean = new MybatisSqlSessionFactoryBean();
        factoryBean.setDataSource(dataSource());
        factoryBean.setConfiguration(configuration);
        factoryBean.afterPropertiesSet();
        SqlSessionFactory sqlSessionFactory = factoryBean.getObject();
        accountMapper = new SqlSessionTemplate(sqlSessionFactory)
                .getMapper(SkitAdAccountMapper.class);

        readOnlyTransaction = new TransactionTemplate(
                new DataSourceTransactionManager(dataSource()));
        readOnlyTransaction.setReadOnly(true);

        jdbc().update("INSERT INTO system_tenant "
                        + "(id,name,package_id,status,expire_time) "
                        + "VALUES (?,'Read only account lookup',0,0,'2099-01-01 00:00:00')",
                TENANT_ID);
        jdbc().update("INSERT INTO skit_ad_account "
                        + "(id,tenant_id,provider,account_name,account_id,app_id,config_data,status) "
                        + "VALUES (?,?,'TAKU','Read only account lookup','read-only-account',"
                        + "'read-only-app',?,0)",
                ACCOUNT_ID, TENANT_ID, "{\"placementId\":\"" + PLACEMENT_ID + "\"}");
    }

    @AfterAll
    void restoreEncryptor() throws IllegalAccessException {
        if (encryptorField != null) {
            encryptorField.set(null, previousEncryptor);
        }
    }

    @Test
    void enabledPlacementLookupDoesNotAcquireWriteLockInsideReadOnlyTransaction() {
        String placementId = assertDoesNotThrow(() -> readOnlyTransaction.execute(status ->
                accountMapper.selectEnabledTakuPlacementId(TENANT_ID, ACCOUNT_ID)));

        assertEquals(PLACEMENT_ID, placementId);
    }

    @Test
    void longEncryptedAppKeySurvivesProviderLockAndConfigOnlyUpdate() {
        jdbc().update("INSERT INTO system_tenant "
                        + "(id,name,package_id,status,expire_time) "
                        + "VALUES (?,'Encrypted account round trip',0,0,'2099-01-01 00:00:00')",
                ENCRYPTION_TENANT_ID);

        SkitAdAccountDO account = new SkitAdAccountDO();
        account.setId(ENCRYPTION_ACCOUNT_ID);
        account.setTenantId(ENCRYPTION_TENANT_ID);
        account.setProvider("TAKU");
        account.setAccountName("Encrypted account round trip");
        account.setAccountId("encrypted-account");
        account.setAppId("encrypted-app");
        account.setAppKey(LONG_APP_KEY);
        account.setConfigData(INITIAL_CONFIG);
        account.setStatus(0);
        account.setCreateTime(LocalDateTime.now());
        account.setUpdateTime(LocalDateTime.now());
        account.setCreator("mysql-it");
        account.setUpdater("mysql-it");
        assertEquals(1, accountMapper.insert(account));

        String originalCiphertext = jdbc().queryForObject(
                "SELECT app_key FROM skit_ad_account WHERE tenant_id=? AND id=?",
                String.class, ENCRYPTION_TENANT_ID, ENCRYPTION_ACCOUNT_ID);
        assertNotEquals(LONG_APP_KEY, originalCiphertext);
        assertTrue(originalCiphertext.length() > 512,
                "the Unicode boundary must exercise encrypted storage beyond varchar(512)");

        inTransaction(() -> {
            SkitAdAccountDO locked = accountMapper.selectByProviderForUpdate(
                    ENCRYPTION_TENANT_ID, "TAKU");
            assertEquals(LONG_APP_KEY, locked.getAppKey(),
                    "the row-lock query must apply EncryptTypeHandler on reads");
            locked.setConfigData(UPDATED_CONFIG);
            assertEquals(1, accountMapper.updateById(locked));
        });

        String updatedCiphertext = jdbc().queryForObject(
                "SELECT app_key FROM skit_ad_account WHERE tenant_id=? AND id=?",
                String.class, ENCRYPTION_TENANT_ID, ENCRYPTION_ACCOUNT_ID);
        assertEquals(originalCiphertext, updatedCiphertext,
                "a config-only update must not encrypt ciphertext a second time");

        SkitAdAccountDO reloaded = inTransaction(() ->
                accountMapper.selectByProviderForUpdate(ENCRYPTION_TENANT_ID, "TAKU"));
        assertEquals(LONG_APP_KEY, reloaded.getAppKey());
        assertEquals(UPDATED_CONFIG, reloaded.getConfigData());
    }
}
