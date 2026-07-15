package cn.iocoder.yudao.module.skit.integration;

import cn.iocoder.yudao.framework.mybatis.core.handler.DefaultDBFieldHandler;
import cn.iocoder.yudao.framework.tenant.config.TenantProperties;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.framework.tenant.core.db.TenantDatabaseInterceptor;
import cn.iocoder.yudao.module.skit.dal.dataobject.record.SkitAdminRecordDO;
import cn.iocoder.yudao.module.skit.dal.mysql.record.SkitAdminRecordMapper;
import cn.iocoder.yudao.module.skit.service.record.SkitAdminRecordService;
import cn.iocoder.yudao.module.skit.service.record.SkitAdminRecordServiceImpl;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.mapper.MapperFactoryBean;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Real-MySQL proof that tenant seed acquisition is atomic under the Task 2 unique key. */
class SkitAdminRecordSeedMySqlIT extends SkitMySqlIntegrationTestBase {

    private static final String PAGE_KEY = "announcement";
    private static final long TENANT_A = 971_001L;
    private static final long TENANT_B = 971_002L;

    private AnnotationConfigApplicationContext context;
    private SkitAdminRecordService recordService;
    private SeedCountGate seedCountGate;

    @BeforeAll
    void startServiceBoundary() {
        context = new AnnotationConfigApplicationContext();
        context.registerBean("dataSource", DataSource.class, this::dataSource);
        context.register(RealSeedConfiguration.class);
        context.refresh();
        recordService = context.getBean(SkitAdminRecordService.class);
        seedCountGate = context.getBean(SeedCountGate.class);
    }

    @AfterEach
    void clearContext() {
        TenantContextHolder.clear();
        seedCountGate.disarm();
    }

    @AfterAll
    void closeServiceBoundary() {
        if (context != null) {
            context.close();
        }
    }

    @Test
    void concurrentSeedRequestsAreIdempotentAndIsolatedByTenant() throws Exception {
        seedTwiceAtTheSameSnapshot(TENANT_A);
        seedTwiceAtTheSameSnapshot(TENANT_B);

        assertEquals(2, countRows(TENANT_A));
        assertEquals(2, countRows(TENANT_B));
        assertEquals(4, jdbc().queryForObject(
                "SELECT COUNT(*) FROM skit_admin_record WHERE page_key=? AND deleted=b'0'",
                Integer.class, PAGE_KEY));
        assertEquals(Arrays.asList(TENANT_A, TENANT_B), jdbc().queryForList(
                "SELECT DISTINCT tenant_id FROM skit_admin_record WHERE page_key=? ORDER BY tenant_id",
                Long.class, PAGE_KEY));
        assertEquals(0, jdbc().queryForObject(
                "SELECT COUNT(*) FROM (SELECT tenant_id,page_key,row_key,COUNT(*) AS c "
                        + "FROM skit_admin_record WHERE page_key=? AND deleted=b'0' "
                        + "GROUP BY tenant_id,page_key,row_key HAVING c>1) duplicate_rows",
                Integer.class, PAGE_KEY));
    }

    private void seedTwiceAtTheSameSnapshot(long tenantId) throws Exception {
        seedCountGate.arm(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> first = executor.submit(() -> seedForTenant(tenantId));
            Future<Integer> second = executor.submit(() -> seedForTenant(tenantId));
            assertTrue(first.get(20, TimeUnit.SECONDS) >= 0);
            assertTrue(second.get(20, TimeUnit.SECONDS) >= 0);
        } finally {
            seedCountGate.disarm();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    private Integer seedForTenant(long tenantId) {
        TenantContextHolder.setTenantId(tenantId);
        try {
            return recordService.seedPage(PAGE_KEY);
        } finally {
            TenantContextHolder.clear();
        }
    }

    private Integer countRows(long tenantId) {
        return jdbc().queryForObject(
                "SELECT COUNT(*) FROM skit_admin_record WHERE tenant_id=? AND page_key=? AND deleted=b'0'",
                Integer.class, tenantId, PAGE_KEY);
    }

    @Intercepts(@Signature(type = Executor.class, method = "query", args = {
            MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class}))
    static final class SeedCountGate implements Interceptor {

        private volatile GateState state;

        synchronized void arm(int parties) {
            state = new GateState(parties);
        }

        synchronized void disarm() {
            state = null;
        }

        @Override
        public Object intercept(Invocation invocation) throws Throwable {
            Object result = invocation.proceed();
            GateState current = state;
            if (current == null) {
                return result;
            }
            MappedStatement statement = (MappedStatement) invocation.getArgs()[0];
            if (!statement.getId().endsWith("SkitAdminRecordMapper.selectCount")) {
                return result;
            }
            current.arrived.countDown();
            if (!current.arrived.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("concurrent seed requests did not observe the same empty snapshot");
            }
            return result;
        }

        private static final class GateState {
            private final CountDownLatch arrived;

            private GateState(int parties) {
                this.arrived = new CountDownLatch(parties);
            }
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement(proxyTargetClass = true)
    static class RealSeedConfiguration {

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
                                                       MybatisPlusInterceptor tenantInterceptor,
                                                       SeedCountGate seedCountGate) {
            MybatisConfiguration configuration = new MybatisConfiguration();
            configuration.setMapUnderscoreToCamelCase(true);
            GlobalConfig globalConfig = GlobalConfigUtils.defaults();
            globalConfig.setMetaObjectHandler(new DefaultDBFieldHandler());
            GlobalConfigUtils.setGlobalConfig(configuration, globalConfig);
            MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration,
                    "skit-admin-record-seed-mysql-it");
            TableInfoHelper.remove(SkitAdminRecordDO.class);
            TableInfoHelper.initTableInfo(assistant, SkitAdminRecordDO.class);

            MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
            factory.setDataSource(dataSource);
            factory.setConfiguration(configuration);
            factory.setPlugins(tenantInterceptor, seedCountGate);
            factory.setGlobalConfig(globalConfig);
            return factory;
        }

        @Bean
        MapperFactoryBean<SkitAdminRecordMapper> adminRecordMapperFactory(
                SqlSessionFactory sqlSessionFactory) {
            MapperFactoryBean<SkitAdminRecordMapper> factory =
                    new MapperFactoryBean<>(SkitAdminRecordMapper.class);
            factory.setSqlSessionFactory(sqlSessionFactory);
            return factory;
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        SeedCountGate seedCountGate() {
            return new SeedCountGate();
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        SkitAdminRecordService adminRecordService(SkitAdminRecordMapper recordMapper,
                                                   ObjectMapper objectMapper) {
            SkitAdminRecordServiceImpl service = new SkitAdminRecordServiceImpl();
            ReflectionTestUtils.setField(service, "skitAdminRecordMapper", recordMapper);
            ReflectionTestUtils.setField(service, "objectMapper", objectMapper);
            return service;
        }
    }

}
