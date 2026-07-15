package cn.iocoder.yudao.module.skit.service.ad;

import cn.iocoder.yudao.framework.tenant.config.TenantProperties;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.framework.tenant.core.db.TenantDatabaseInterceptor;
import cn.iocoder.yudao.framework.tenant.core.util.TenantUtils;
import cn.iocoder.yudao.module.skit.dal.dataobject.ad.SkitAdAccountDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.ad.SkitAdCallbackKeyDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.ad.SkitAdRewardSecretVersionDO;
import cn.iocoder.yudao.module.skit.dal.mysql.ad.SkitAdAccountMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.ad.SkitAdCallbackKeyMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.ad.SkitAdRewardSecretVersionMapper;
import cn.iocoder.yudao.module.skit.framework.crypto.SkitAdCredentialCryptoService;
import cn.iocoder.yudao.module.skit.framework.crypto.SkitAesGcmCredentialCryptoService;
import cn.iocoder.yudao.module.skit.integration.SkitMySqlIntegrationTestBase;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the credential service through the same Spring transaction and MyBatis tenant
 * boundaries used in production. Fixture setup and state assertions use JDBC, but every
 * credential mutation and resolution under test goes through the real service and mapper beans.
 */
class SkitAdCredentialVersionSpringMySqlIT extends SkitMySqlIntegrationTestBase {

    private static final String TEST_KEY_ID = "spring-mysql-primary";
    private static final byte[] TEST_AT_REST_KEY =
            "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.US_ASCII);

    private AnnotationConfigApplicationContext context;
    private SkitAdCredentialVersionServiceImpl service;
    private SkitAdAccountMapper accountMapper;
    private SkitAdCallbackKeyMapper callbackKeyMapper;
    private SkitAdRewardSecretVersionMapper rewardSecretMapper;

    @BeforeAll
    void startSpringMyBatisContext() {
        context = new AnnotationConfigApplicationContext();
        context.registerBean("dataSource", DataSource.class, this::dataSource);
        context.register(RealCredentialConfiguration.class);
        context.refresh();

        service = context.getBean(SkitAdCredentialVersionServiceImpl.class);
        accountMapper = context.getBean(SkitAdAccountMapper.class);
        callbackKeyMapper = context.getBean(SkitAdCallbackKeyMapper.class);
        rewardSecretMapper = context.getBean(SkitAdRewardSecretVersionMapper.class);
    }

    @AfterEach
    void clearTenantContext() {
        TenantContextHolder.clear();
    }

    @AfterAll
    void closeSpringMyBatisContext() {
        if (context != null) {
            context.close();
        }
    }

    @Test
    void serviceAndMappersAreRealSpringAndMyBatisProxies() {
        assertTrue(AopUtils.isAopProxy(service), "credential service must be transaction proxied");
        assertTrue(Proxy.isProxyClass(accountMapper.getClass()), "account mapper must be a MyBatis proxy");
        assertTrue(Proxy.isProxyClass(callbackKeyMapper.getClass()), "callback mapper must be a MyBatis proxy");
        assertTrue(Proxy.isProxyClass(rewardSecretMapper.getClass()), "reward mapper must be a MyBatis proxy");
        assertTrue(context.getBean(PlatformTransactionManager.class) instanceof DataSourceTransactionManager);
        assertTrue(context.getBean(MybatisPlusInterceptor.class).getInterceptors().stream()
                .anyMatch(TenantLineInnerInterceptor.class::isInstance),
                "the real MyBatis chain must include the tenant-line interceptor");
    }

    @Test
    void proxiedRotationRollsBackRetirementWhenRealMapperInsertFails() {
        long tenantId = 9101L;
        long accountId = 9102L;
        insertAccount(tenantId, accountId, "SPRING_ROLLBACK");
        service.rotateRewardSecret(tenantId, accountId,
                "first-real-secret".getBytes(StandardCharsets.UTF_8), Duration.ofMinutes(20));

        String trigger = "skit_it_force_reward_insert_failure";
        jdbc().execute("DROP TRIGGER IF EXISTS " + trigger);
        jdbc().execute("CREATE TRIGGER " + trigger
                + " BEFORE INSERT ON skit_ad_reward_secret_version FOR EACH ROW BEGIN "
                + "IF NEW.ad_account_id=" + accountId + " AND NEW.secret_version=2 THEN "
                + "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='forced real mapper insert failure'; "
                + "END IF; END");
        try {
            RuntimeException failure = assertThrows(RuntimeException.class, () ->
                    service.rotateRewardSecret(tenantId, accountId,
                            "must-not-commit".getBytes(StandardCharsets.UTF_8), Duration.ofMinutes(20)));
            assertTrue(rootMessage(failure).contains("forced real mapper insert failure"),
                    rootMessage(failure));
        } finally {
            jdbc().execute("DROP TRIGGER IF EXISTS " + trigger);
        }

        assertEquals(1, jdbc().queryForObject("SELECT COUNT(*) FROM skit_ad_reward_secret_version "
                + "WHERE tenant_id=? AND ad_account_id=?", Integer.class, tenantId, accountId));
        assertEquals(1, jdbc().queryForObject("SELECT secret_version FROM skit_ad_reward_secret_version "
                + "WHERE tenant_id=? AND ad_account_id=? AND active=b'1'", Integer.class, tenantId, accountId));
        assertNull(jdbc().queryForObject("SELECT accept_until FROM skit_ad_reward_secret_version "
                + "WHERE tenant_id=? AND ad_account_id=?", Timestamp.class, tenantId, accountId));
    }

    @Test
    void crossTenantRotationCannotMutateAnAccountOwnedByAnotherTenant() {
        long ownerTenantId = 9201L;
        long foreignTenantId = 9202L;
        long accountId = 9203L;
        insertAccount(ownerTenantId, accountId, "TENANT_OWNER");

        IllegalStateException failure = assertThrows(IllegalStateException.class, () ->
                service.rotateCallbackKey(foreignTenantId, accountId, Duration.ofMinutes(15)));
        assertTrue(failure.getMessage().contains("does not exist in the requested tenant"));
        assertEquals(0, jdbc().queryForObject("SELECT COUNT(*) FROM skit_ad_callback_key "
                + "WHERE ad_account_id=?", Integer.class, accountId));

        assertNull(TenantUtils.execute(foreignTenantId,
                () -> accountMapper.lockByTenantAndId(ownerTenantId, accountId)));
    }

    @Test
    void callbackHashLookupIsGlobalOnlyInsideTheResolutionBoundary() {
        long ownerTenantId = 9301L;
        long otherTenantId = 9302L;
        long accountId = 9303L;
        insertAccount(ownerTenantId, accountId, "CALLBACK_GLOBAL");

        String callbackKey = service.rotateCallbackKey(ownerTenantId, accountId,
                Duration.ofMinutes(15)).consumeCallbackKey();
        byte[] callbackHash = sha256(callbackKey);

        assertNull(TenantUtils.execute(otherTenantId,
                () -> callbackKeyMapper.selectByHash(callbackHash)));
        assertNull(TenantUtils.execute(otherTenantId,
                () -> callbackKeyMapper.selectActive(ownerTenantId, accountId)));
        assertNotNull(TenantUtils.execute(ownerTenantId,
                () -> callbackKeyMapper.selectActive(ownerTenantId, accountId)));

        SkitAdCredentialVersionService.CallbackKeyResolution resolution = TenantUtils.execute(otherTenantId, () -> {
            SkitAdCredentialVersionService.CallbackKeyResolution resolved = service.resolveCallbackKey(
                    callbackKey, LocalDateTime.now(ZoneOffset.UTC));
            assertEquals(otherTenantId, TenantContextHolder.getRequiredTenantId());
            assertFalse(TenantContextHolder.isIgnore(), "global lookup must restore the caller tenant boundary");
            return resolved;
        });
        assertEquals(ownerTenantId, resolution.getTenantId());
        assertEquals(accountId, resolution.getAdAccountId());
        assertEquals(1, resolution.getVersion());
    }

    @Test
    void rewardSecretUsesRealMapperStorageAndAuthenticatedDecryption() {
        long tenantId = 9401L;
        long otherTenantId = 9402L;
        long accountId = 9403L;
        byte[] plaintext = "real-mapper-reward-secret".getBytes(StandardCharsets.UTF_8);
        insertAccount(tenantId, accountId, "REWARD_ROUNDTRIP");

        SkitAdCredentialVersionService.CredentialMetadata metadata = service.rotateRewardSecret(
                tenantId, accountId, plaintext, Duration.ofMinutes(20));
        assertEquals(1, metadata.getVersion());

        SkitAdRewardSecretVersionDO stored = TenantUtils.execute(tenantId,
                () -> rewardSecretMapper.selectByVersion(tenantId, accountId, metadata.getVersion()));
        assertNotNull(stored);
        assertEquals(tenantId, stored.getTenantId());
        assertEquals(accountId, stored.getAdAccountId());
        assertEquals(TEST_KEY_ID, stored.getEncryptionKeyId());
        assertFalse(java.util.Arrays.equals(plaintext, stored.getCiphertext()),
                "the real mapper must persist ciphertext, not plaintext");
        assertNull(TenantUtils.execute(otherTenantId,
                () -> rewardSecretMapper.selectByVersion(tenantId, accountId, metadata.getVersion())));

        SkitAdCredentialVersionService.ResolvedRewardSecret resolved = service.resolveRewardSecret(
                tenantId, accountId, metadata.getVersion(),
                LocalDateTime.now(ZoneOffset.UTC).plusMinutes(20), LocalDateTime.now(ZoneOffset.UTC));
        byte[] roundTrip = resolved.withSecret(byte[]::clone);
        assertArrayEquals(plaintext, roundTrip);
    }

    private void insertAccount(long tenantId, long accountId, String provider) {
        jdbc().update("INSERT INTO skit_ad_account "
                        + "(id,tenant_id,provider,account_name,account_id,app_id,app_key,status) "
                        + "VALUES (?,?,?,?,?,?,?,1)",
                accountId, tenantId, provider, provider, provider, provider, "");
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.US_ASCII));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String rootMessage(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.toString() : current.getMessage();
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement(proxyTargetClass = true)
    @MapperScan(basePackages = "cn.iocoder.yudao.module.skit.dal.mysql.ad",
            annotationClass = Mapper.class)
    static class RealCredentialConfiguration {

        @Bean
        TenantProperties tenantProperties() {
            return new TenantProperties();
        }

        @Bean
        MybatisPlusInterceptor mybatisPlusInterceptor(TenantProperties tenantProperties) {
            MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
            interceptor.addInnerInterceptor(new TenantLineInnerInterceptor(
                    new TenantDatabaseInterceptor(tenantProperties)));
            return interceptor;
        }

        @Bean
        MybatisSqlSessionFactoryBean sqlSessionFactory(DataSource dataSource,
                                                       MybatisPlusInterceptor interceptor) {
            MybatisConfiguration configuration = new MybatisConfiguration();
            configuration.setMapUnderscoreToCamelCase(true);
            MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration,
                    "skit-ad-credential-spring-mysql-it");
            TableInfoHelper.initTableInfo(assistant, SkitAdAccountDO.class);
            TableInfoHelper.initTableInfo(assistant, SkitAdCallbackKeyDO.class);
            TableInfoHelper.initTableInfo(assistant, SkitAdRewardSecretVersionDO.class);

            MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
            factory.setDataSource(dataSource);
            factory.setConfiguration(configuration);
            factory.setPlugins(interceptor);
            return factory;
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        SkitAdCredentialCryptoService credentialCryptoService() {
            return new SkitAesGcmCredentialCryptoService(TEST_KEY_ID,
                    Collections.singletonMap(TEST_KEY_ID, TEST_AT_REST_KEY));
        }

        @Bean
        SkitAdCredentialVersionServiceImpl credentialVersionService(
                SkitAdAccountMapper accountMapper,
                SkitAdCallbackKeyMapper callbackKeyMapper,
                SkitAdRewardSecretVersionMapper rewardSecretMapper,
                SkitAdCredentialCryptoService cryptoService) {
            return new SkitAdCredentialVersionServiceImpl(accountMapper, callbackKeyMapper,
                    rewardSecretMapper, cryptoService);
        }
    }

}
