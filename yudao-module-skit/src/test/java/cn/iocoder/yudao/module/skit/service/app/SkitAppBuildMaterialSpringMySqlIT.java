package cn.iocoder.yudao.module.skit.service.app;

import cn.iocoder.yudao.framework.tenant.config.TenantProperties;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.framework.tenant.core.db.TenantDatabaseInterceptor;
import cn.iocoder.yudao.framework.tenant.core.util.TenantUtils;
import cn.iocoder.yudao.module.skit.dal.dataobject.app.SkitAppBuildMaterialDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.app.SkitAppReleaseProfileDO;
import cn.iocoder.yudao.module.skit.dal.mysql.app.SkitAppBuildMaterialMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.app.SkitAppReleaseProfileMapper;
import cn.iocoder.yudao.module.skit.framework.crypto.SkitAdCredentialCryptoService;
import cn.iocoder.yudao.module.skit.framework.crypto.SkitAesGcmCredentialCryptoService;
import cn.iocoder.yudao.module.skit.integration.SkitMySqlIntegrationTestBase;
import cn.iocoder.yudao.module.skit.service.ad.SkitAdAccountService;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SkitAppBuildMaterialSpringMySqlIT extends SkitMySqlIntegrationTestBase {

    private static final long TENANT_ID = 162L;
    private static final String PACKAGE_NAME = "top.neoshen.xingheyingguan";
    private static final String KEY_ID = "app-build-material-it";
    private static final byte[] AT_REST_KEY =
            "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.US_ASCII);

    private AnnotationConfigApplicationContext context;
    private SkitAppBuildMaterialService service;

    @BeforeAll
    void startSpringMyBatisContext() {
        jdbc().update("INSERT INTO system_tenant (id,name,package_id,status) VALUES (?,?,1,0)",
                TENANT_ID, "AG162");
        jdbc().update("INSERT INTO skit_app_release_profile "
                        + "(tenant_id,profile_code,channel,min_native_version,hot_version,hot_bundle_url,"
                        + "hot_bundle_sha256,native_version,native_package,native_protocol_version,status) "
                        + "VALUES (?,?, 'production','2026.7.18.4','2026.7.18.4',?,?,'2026.7.18.4',?,1,0)",
                TENANT_ID, "AG162", "https://www.yunque8.top/updates/AG162/2026.7.18.4.zip",
                "024e650d70e8eff650edc3c4bdc3fc4e7c5208272e79dbaf0a2f5f4941c4fbd0", PACKAGE_NAME);

        context = new AnnotationConfigApplicationContext();
        context.registerBean("dataSource", DataSource.class, this::dataSource);
        context.register(RealAppBuildMaterialConfiguration.class);
        context.refresh();
        service = context.getBean(SkitAppBuildMaterialService.class);
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
    void firstSavePersistsAndDecryptsTheRealProductionPayloadShape() {
        SkitAppBuildMaterialService.MaterialView saved = TenantUtils.execute(TENANT_ID,
                () -> service.saveMaterial(command(1L)));

        assertEquals(1, saved.getMaterialVersion());
        assertTrue(saved.getPangleSettingsConfigured());
        assertTrue(saved.getSigningConfigured());
        assertEquals(1, jdbc().queryForObject("SELECT COUNT(*) FROM skit_app_build_material "
                + "WHERE tenant_id=? AND material_version=1 AND active=b'1'", Integer.class, TENANT_ID));

        SkitAppBuildMaterialService.MaterialView reloaded = TenantUtils.execute(TENANT_ID,
                () -> service.getMaterial(TENANT_ID));
        assertEquals(saved.getMaterialVersion(), reloaded.getMaterialVersion());
        assertEquals(saved.getNativeVersionName(), reloaded.getNativeVersionName());
        assertNotNull(reloaded.getVerifiedAt());
    }

    private SkitAppBuildMaterialService.MaterialCommand command(long releaseNo) {
        return new SkitAppBuildMaterialService.MaterialCommand()
                .setTenantId(TENANT_ID)
                .setApiBaseUrl("https://www.yunque8.top")
                .setAppName("短剧 SaaS")
                .setNativeVersionCode(6L)
                .setNativeVersionName("2026.7.18.4")
                .setRuntimeReleaseNo(releaseNo)
                .setPangleSettingsJson("{\"init\":{\"site_id\":\"5850994\",\"app_id\":\"1037672\"},"
                        + "\"license_config\":[{\"PackageName\":\"" + PACKAGE_NAME + "\"}]}")
                .setReleaseKeystoreBase64(Base64.getEncoder().encodeToString(
                        "production-shaped-pkcs12-payload".getBytes(StandardCharsets.UTF_8)))
                .setStorePassword("store-password")
                .setKeyAlias("ag162-release")
                .setKeyPassword("key-password")
                .setReason("保存 AG162 独立签名与真实短剧生产构建资料版本");
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement(proxyTargetClass = true)
    @MapperScan(basePackages = "cn.iocoder.yudao.module.skit.dal.mysql.app",
            annotationClass = Mapper.class)
    static class RealAppBuildMaterialConfiguration {

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
                    "skit-app-build-material-spring-mysql-it");
            TableInfoHelper.initTableInfo(assistant, SkitAppBuildMaterialDO.class);
            TableInfoHelper.initTableInfo(assistant, SkitAppReleaseProfileDO.class);

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
            return new SkitAesGcmCredentialCryptoService(KEY_ID,
                    Collections.singletonMap(KEY_ID, AT_REST_KEY));
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        SkitAdAccountService adAccountService() {
            SkitAdAccountService service = mock(SkitAdAccountService.class);
            SkitAdAccountService.Settings settings = new SkitAdAccountService.Settings();
            settings.setTakuUsername("taku-account");
            settings.setTakuAppId("taku-app");
            settings.setTakuPlacementId("taku-reward");
            settings.setTakuAppKeyConfigured(true);
            when(service.getSettings()).thenReturn(settings);
            return service;
        }

        @Bean
        SkitAppBuildMaterialService appBuildMaterialService(
                SkitAppBuildMaterialMapper materialMapper,
                SkitAppReleaseProfileMapper releaseProfileMapper,
                SkitAdAccountService adAccountService,
                SkitAdCredentialCryptoService credentialCryptoService,
                ObjectMapper objectMapper) {
            SkitAppBuildMaterialServiceImpl service = new SkitAppBuildMaterialServiceImpl();
            ReflectionTestUtils.setField(service, "materialMapper", materialMapper);
            ReflectionTestUtils.setField(service, "releaseProfileMapper", releaseProfileMapper);
            ReflectionTestUtils.setField(service, "adAccountService", adAccountService);
            ReflectionTestUtils.setField(service, "credentialCrypto", credentialCryptoService);
            ReflectionTestUtils.setField(service, "objectMapper", objectMapper);
            return service;
        }
    }
}
