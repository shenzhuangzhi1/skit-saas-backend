package cn.iocoder.yudao.module.skit.integration;

import cn.iocoder.yudao.framework.mybatis.core.handler.DefaultDBFieldHandler;
import cn.iocoder.yudao.framework.tenant.config.TenantProperties;
import cn.iocoder.yudao.framework.tenant.core.db.TenantDatabaseInterceptor;
import cn.iocoder.yudao.module.skit.dal.dataobject.record.SkitAdminRecordDO;
import cn.iocoder.yudao.module.skit.dal.mysql.record.SkitAdminRecordMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.mapper.MapperFactoryBean;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Real-MySQL proof that provider catalog writes remain idempotent and tenant isolated. */
class SkitAdminRecordCatalogMySqlIT extends SkitMySqlIntegrationTestBase {

    private static final long TENANT_A = 971_001L;
    private static final long TENANT_B = 971_002L;

    private AnnotationConfigApplicationContext context;
    private SkitAdminRecordMapper recordMapper;

    @BeforeAll
    void startMapperBoundary() {
        context = new AnnotationConfigApplicationContext();
        context.registerBean("dataSource", DataSource.class, this::dataSource);
        context.register(CatalogConfiguration.class);
        context.refresh();
        recordMapper = context.getBean(SkitAdminRecordMapper.class);
    }

    @AfterAll
    void closeMapperBoundary() {
        if (context != null) {
            context.close();
        }
    }

    @Test
    void pangleDramaUpsertIsIdempotentAndIsolatedByTenant() {
        SkitAdminRecordDO first = pangleDrama(TENANT_A, 1631L, "初始标题", 80);
        SkitAdminRecordDO refreshed = pangleDrama(TENANT_A, 1631L, "人间至味是", 88);
        SkitAdminRecordDO otherTenant = pangleDrama(TENANT_B, 1631L, "租户乙标题", 66);

        assertTrue(recordMapper.upsertPangleDramaCatalog(TENANT_A, first) > 0);
        assertTrue(recordMapper.upsertPangleDramaCatalog(TENANT_A, refreshed) > 0);
        assertTrue(recordMapper.upsertPangleDramaCatalog(TENANT_B, otherTenant) > 0);

        assertEquals(1, countPangleDramaRows(TENANT_A, 1631L));
        assertEquals(1, countPangleDramaRows(TENANT_B, 1631L));
        assertEquals("人间至味是", jdbc().queryForObject(
                "SELECT JSON_UNQUOTE(JSON_EXTRACT(record_data,'$.title')) "
                        + "FROM skit_admin_record WHERE tenant_id=? AND page_key='drama' "
                        + "AND row_key='pangle-1631' AND deleted=b'0'",
                String.class, TENANT_A));
        assertEquals(88, jdbc().queryForObject(
                "SELECT CAST(JSON_UNQUOTE(JSON_EXTRACT(record_data,'$.episodes')) AS UNSIGNED) "
                        + "FROM skit_admin_record WHERE tenant_id=? AND page_key='drama' "
                        + "AND row_key='pangle-1631' AND deleted=b'0'",
                Integer.class, TENANT_A));
    }

    @Test
    void pangleDramaRefreshRetiresLegacyAliasesOnlyInsideTheRequestedTenant() {
        long refreshedDramaId = 1731L;
        jdbc().update("INSERT INTO skit_admin_record "
                        + "(tenant_id,page_key,row_key,record_data,status,sort,deleted,creator,updater) "
                        + "VALUES (?,?,?,?,0,0,b'0','legacy','legacy'),"
                        + "(?,?,?,?,0,0,b'0','legacy','legacy')",
                TENANT_A, "drama", "legacy-1731",
                "{\"id\":1731,\"episodes\":1,\"publishStatus\":\"下架\"}",
                TENANT_B, "drama", "legacy-1731",
                "{\"id\":1731,\"episodes\":66,\"publishStatus\":\"上架\"}");
        SkitAdminRecordDO canonical = pangleDrama(TENANT_A, refreshedDramaId, "独立刷新测试剧", 88);

        assertTrue(recordMapper.upsertPangleDramaCatalog(TENANT_A, canonical) > 0);
        assertEquals(1, recordMapper.retirePangleDramaCatalogAliases(
                TENANT_A, String.valueOf(refreshedDramaId), "pangle-1731"));

        assertEquals(1, recordMapper.selectDramaCatalogByBusinessIdForShare(
                TENANT_A, String.valueOf(refreshedDramaId)).size());
        assertEquals("pangle-1731", recordMapper.selectDramaCatalogByBusinessIdForShare(
                TENANT_A, String.valueOf(refreshedDramaId)).get(0).getRowKey());
        assertEquals(1, recordMapper.selectDramaCatalogByBusinessIdForShare(
                TENANT_B, String.valueOf(refreshedDramaId)).size());
        assertEquals("legacy-1731", recordMapper.selectDramaCatalogByBusinessIdForShare(
                TENANT_B, String.valueOf(refreshedDramaId)).get(0).getRowKey());
    }

    private SkitAdminRecordDO pangleDrama(long tenantId, long dramaId,
                                           String title, int episodes) {
        SkitAdminRecordDO row = SkitAdminRecordDO.builder()
                .pageKey("drama")
                .rowKey("pangle-" + dramaId)
                .recordData("{\"pangleDramaId\":" + dramaId + ",\"title\":\"" + title
                        + "\",\"episodes\":" + episodes + ",\"publishStatus\":\"上架\"}")
                .status(0)
                .sort(0)
                .build();
        row.setTenantId(tenantId);
        row.setDeleted(false);
        return row;
    }

    private Integer countPangleDramaRows(long tenantId, long dramaId) {
        return jdbc().queryForObject(
                "SELECT COUNT(*) FROM skit_admin_record WHERE tenant_id=? AND page_key='drama' "
                        + "AND row_key=? AND deleted=b'0'",
                Integer.class, tenantId, "pangle-" + dramaId);
    }

    @Configuration(proxyBeanMethods = false)
    static class CatalogConfiguration {

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
                                                       MybatisPlusInterceptor tenantInterceptor) {
            MybatisConfiguration configuration = new MybatisConfiguration();
            configuration.setMapUnderscoreToCamelCase(true);
            GlobalConfig globalConfig = GlobalConfigUtils.defaults();
            globalConfig.setMetaObjectHandler(new DefaultDBFieldHandler());
            GlobalConfigUtils.setGlobalConfig(configuration, globalConfig);
            MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration,
                    "skit-admin-record-catalog-mysql-it");
            TableInfoHelper.remove(SkitAdminRecordDO.class);
            TableInfoHelper.initTableInfo(assistant, SkitAdminRecordDO.class);

            MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
            factory.setDataSource(dataSource);
            factory.setConfiguration(configuration);
            factory.setPlugins(tenantInterceptor);
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

    }

}
