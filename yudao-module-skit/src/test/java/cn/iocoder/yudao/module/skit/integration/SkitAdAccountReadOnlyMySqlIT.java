package cn.iocoder.yudao.module.skit.integration;

import cn.iocoder.yudao.module.skit.dal.mysql.ad.SkitAdAccountMapper;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

class SkitAdAccountReadOnlyMySqlIT extends SkitMySqlIntegrationTestBase {

    private static final long TENANT_ID = 97801L;
    private static final long ACCOUNT_ID = 9780101L;
    private static final String PLACEMENT_ID = "read-only-placement";

    private SkitAdAccountMapper accountMapper;
    private TransactionTemplate readOnlyTransaction;

    @BeforeAll
    void createMapperAndFixture() throws Exception {
        Configuration configuration = new Configuration();
        configuration.addMapper(SkitAdAccountMapper.class);
        SqlSessionFactoryBean factoryBean = new SqlSessionFactoryBean();
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

    @Test
    void enabledPlacementLookupDoesNotAcquireWriteLockInsideReadOnlyTransaction() {
        String placementId = assertDoesNotThrow(() -> readOnlyTransaction.execute(status ->
                accountMapper.selectEnabledTakuPlacementId(TENANT_ID, ACCOUNT_ID)));

        assertEquals(PLACEMENT_ID, placementId);
    }
}
