package cn.iocoder.yudao.module.skit.integration;

import cn.iocoder.yudao.framework.common.enums.CommonStatusEnum;
import cn.iocoder.yudao.framework.mybatis.core.handler.DefaultDBFieldHandler;
import cn.iocoder.yudao.framework.tenant.config.TenantProperties;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.framework.tenant.core.db.TenantDatabaseInterceptor;
import cn.iocoder.yudao.module.skit.dal.dataobject.commission.SkitAdPolicySnapshotDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.commission.SkitCommissionPlanDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.commission.SkitCommissionRuleDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.member.SkitMemberClosureDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.member.SkitMemberDO;
import cn.iocoder.yudao.module.skit.service.commission.SkitMoneyAllocator;
import cn.iocoder.yudao.module.skit.service.commission.SkitPolicySnapshotService;
import cn.iocoder.yudao.module.skit.service.commission.SkitPolicySnapshotServiceImpl;
import cn.iocoder.yudao.module.system.dal.dataobject.tenant.TenantDO;
import cn.iocoder.yudao.module.system.dal.mysql.tenant.TenantMapper;
import cn.iocoder.yudao.module.system.service.tenant.TenantService;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
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
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SkitCommissionPolicySnapshotMySqlIT extends SkitMySqlIntegrationTestBase {

    private AnnotationConfigApplicationContext context;
    private SkitPolicySnapshotService snapshotService;
    private RollbackFacade rollbackFacade;
    private TenantLockGate tenantLockGate;

    @BeforeAll
    void startRealPolicyBoundary() {
        context = new AnnotationConfigApplicationContext();
        context.registerBean("dataSource", DataSource.class, this::dataSource);
        context.register(RealPolicyConfiguration.class);
        context.refresh();
        assertNull(TableInfoHelper.getTableInfo("skit_ad_policy_snapshot"),
                "the narrow custom mapper must work without BaseMapper TableInfo side effects");
        snapshotService = context.getBean(SkitPolicySnapshotService.class);
        rollbackFacade = context.getBean(RollbackFacade.class);
        tenantLockGate = context.getBean(TenantLockGate.class);
        assertTrue(AopUtils.isAopProxy(snapshotService));
        assertTrue(AopUtils.isAopProxy(rollbackFacade));
    }

    @AfterEach
    void clearTenant() {
        TenantContextHolder.clear();
    }

    @AfterAll
    void closePolicyBoundary() {
        if (context != null) {
            context.close();
        }
    }

    @Test
    void realTenantMapperRoundTripsFullHistoricalPolicyAfterCurrentStateChanges() {
        long tenantId = 9301L;
        long sourceMemberId = 930101L;
        long ancestorId = 930102L;
        insertPolicyFixture(tenantId, sourceMemberId, ancestorId, 930103L, 6_000, 2_000);
        TenantContextHolder.setTenantId(tenantId);

        SkitPolicySnapshotService.PolicySnapshot created = snapshotService.createSnapshot(sourceMemberId);
        SkitMoneyAllocator.Result before = new SkitMoneyAllocator().allocate("CNY", 8, 10_003L, created);

        assertNotNull(created.getId());
        assertEquals(tenantId, created.getTenantId().longValue());
        assertEquals(Arrays.asList(0, 1), Arrays.asList(
                created.getChain().get(0).getLevel(), created.getChain().get(1).getLevel()));
        assertEquals(8_000, created.getConfiguredRateBps());
        assertEquals(8_000, created.getEligibleRateBps());
        assertEquals(tenantId, jdbc().queryForObject(
                "SELECT tenant_id FROM skit_ad_policy_snapshot WHERE id=?", Long.class, created.getId()));
        assertArrayEquals(created.getSnapshotHash(), jdbc().queryForObject(
                "SELECT snapshot_hash FROM skit_ad_policy_snapshot WHERE id=?", byte[].class, created.getId()));

        jdbc().update("UPDATE skit_member SET status=1 WHERE tenant_id=? AND id=?", tenantId, ancestorId);
        jdbc().update("UPDATE skit_commission_plan SET status=1 WHERE tenant_id=? AND status=0", tenantId);
        long newPlanId = 930104L;
        jdbc().update("INSERT INTO skit_commission_plan "
                        + "(id,tenant_id,version,status,published_time) VALUES (?,?,2,0,CURRENT_TIMESTAMP)",
                newPlanId, tenantId);
        jdbc().update("INSERT INTO skit_commission_rule "
                        + "(tenant_id,plan_id,level_no,rate_bps) VALUES (?,?,0,1000)",
                tenantId, newPlanId);

        SkitPolicySnapshotService.PolicySnapshot historical = snapshotService.getRequired(created.getId());
        SkitMoneyAllocator.Result after = new SkitMoneyAllocator().allocate("CNY", 8, 10_003L, historical);

        assertEquals(created.getSnapshotJson(), historical.getSnapshotJson());
        assertArrayEquals(created.getSnapshotHash(), historical.getSnapshotHash());
        assertEquals(before.getAgentRetentionUnits(), after.getAgentRetentionUnits());
        assertEquals(before.getMemberAllocations().size(), after.getMemberAllocations().size());
        for (int index = 0; index < before.getMemberAllocations().size(); index++) {
            assertEquals(before.getMemberAllocations().get(index).getMemberId(),
                    after.getMemberAllocations().get(index).getMemberId());
            assertEquals(before.getMemberAllocations().get(index).getAmountUnits(),
                    after.getMemberAllocations().get(index).getAmountUnits());
        }
    }

    @Test
    void tenantInterceptorAndCompoundForeignKeysRejectCrossTenantAccess() {
        long tenantA = 9311L;
        long tenantB = 9312L;
        long sourceA = 931101L;
        long sourceB = 931201L;
        long planA = 931103L;
        insertPolicyFixture(tenantA, sourceA, 931102L, planA, 7_000, 1_000);
        insertPolicyFixture(tenantB, sourceB, 931202L, 931203L, 5_000, 2_000);
        TenantContextHolder.setTenantId(tenantA);
        SkitPolicySnapshotService.PolicySnapshot snapshotA = snapshotService.createSnapshot(sourceA);

        TenantContextHolder.setTenantId(tenantB);
        assertThrows(IllegalStateException.class, () -> snapshotService.getRequired(snapshotA.getId()));
        int before = snapshotCount();
        assertThrows(RuntimeException.class, () -> snapshotService.createSnapshot(sourceA));
        assertEquals(before, snapshotCount());

        assertThrows(DataAccessException.class, () -> jdbc().update(
                "INSERT INTO skit_ad_policy_snapshot "
                        + "(tenant_id,plan_id,source_member_id,rule_version,snapshot_schema_version,"
                        + "snapshot_json,snapshot_hash,policy_snapshot_at) "
                        + "VALUES (?,?,?,1,1,'{}',UNHEX(REPEAT('11',32)),CURRENT_TIMESTAMP)",
                tenantB, planA, sourceA));
    }

    @Test
    void downstreamFailureRollsBackTheSnapshotInsertFromTheSameSpringTransaction() {
        long tenantId = 9321L;
        long sourceMemberId = 932101L;
        insertPolicyFixture(tenantId, sourceMemberId, 932102L, 932103L, 6_000, 1_000);
        TenantContextHolder.setTenantId(tenantId);
        int before = snapshotCount();

        assertThrows(IllegalStateException.class,
                () -> rollbackFacade.createThenFail(sourceMemberId));

        assertEquals(before, snapshotCount());
    }

    @Test
    void tenantDisableCannotCommitInsideTheSnapshotEligibilityBoundary() throws Exception {
        long tenantId = 9331L;
        long sourceMemberId = 933101L;
        insertPolicyFixture(tenantId, sourceMemberId, 933102L, 933103L, 6_000, 1_000);
        tenantLockGate.arm();
        ExecutorService workers = Executors.newFixedThreadPool(2);
        CountDownLatch disableStarted = new CountDownLatch(1);
        try {
            Future<SkitPolicySnapshotService.PolicySnapshot> snapshot = workers.submit(() -> {
                TenantContextHolder.setTenantId(tenantId);
                try {
                    return snapshotService.createSnapshot(sourceMemberId);
                } finally {
                    TenantContextHolder.clear();
                }
            });
            assertTrue(tenantLockGate.awaitLocked(), "snapshot transaction did not lock the tenant row");

            Future<Integer> disable = workers.submit(() -> {
                disableStarted.countDown();
                return jdbc().update("UPDATE system_tenant SET status=1 WHERE id=?", tenantId);
            });
            assertTrue(disableStarted.await(10, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> disable.get(300, TimeUnit.MILLISECONDS),
                    "tenant disable committed while snapshot creation still held the eligibility lock");

            tenantLockGate.release();
            SkitPolicySnapshotService.PolicySnapshot created = snapshot.get(10, TimeUnit.SECONDS);
            assertNotNull(created.getId());
            assertEquals(1, disable.get(10, TimeUnit.SECONDS));
            assertEquals(CommonStatusEnum.DISABLE.getStatus(), jdbc().queryForObject(
                    "SELECT status FROM system_tenant WHERE id=?", Integer.class, tenantId));
        } finally {
            tenantLockGate.release();
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void aDisableThatWinsTheTenantLockPreventsSnapshotCreation() throws Exception {
        long tenantId = 9341L;
        long sourceMemberId = 934101L;
        insertPolicyFixture(tenantId, sourceMemberId, 934102L, 934103L, 6_000, 1_000);
        int snapshotsBefore = snapshotCount();
        ExecutorService workers = Executors.newFixedThreadPool(2);
        CountDownLatch disabledWhileLocked = new CountDownLatch(1);
        CountDownLatch allowDisableCommit = new CountDownLatch(1);
        try {
            Future<?> disable = workers.submit(() -> inTransaction(() -> {
                assertEquals(1, jdbc().update("UPDATE system_tenant SET status=1 WHERE id=?", tenantId));
                disabledWhileLocked.countDown();
                await(allowDisableCommit, "disable transaction was not released");
            }));
            assertTrue(disabledWhileLocked.await(10, TimeUnit.SECONDS));

            Future<?> snapshot = workers.submit(() -> {
                TenantContextHolder.setTenantId(tenantId);
                try {
                    return snapshotService.createSnapshot(sourceMemberId);
                } finally {
                    TenantContextHolder.clear();
                }
            });
            assertThrows(TimeoutException.class, () -> snapshot.get(300, TimeUnit.MILLISECONDS),
                    "snapshot did not wait for the tenant status writer");

            allowDisableCommit.countDown();
            disable.get(10, TimeUnit.SECONDS);
            ExecutionException rejected = assertThrows(ExecutionException.class,
                    () -> snapshot.get(10, TimeUnit.SECONDS));
            assertTrue(rejected.getCause() instanceof IllegalStateException);
            assertEquals(snapshotsBefore, snapshotCount());
        } finally {
            allowDisableCommit.countDown();
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    private void insertPolicyFixture(long tenantId, long sourceMemberId, long ancestorId,
                                     long planId, int viewerRateBps, int ancestorRateBps) {
        jdbc().update("INSERT INTO system_tenant (id,name,package_id,status,expire_time) "
                        + "VALUES (?,'Policy tenant',0,0,'2099-01-01 00:00:00') "
                        + "ON DUPLICATE KEY UPDATE status=0,expire_time=VALUES(expire_time),deleted=b'0'",
                tenantId);
        insertMember(tenantId, ancestorId, "ancestor");
        insertMember(tenantId, sourceMemberId, "source");
        jdbc().update("INSERT INTO skit_member_closure "
                        + "(tenant_id,ancestor_id,descendant_id,distance) VALUES (?,?,?,0),(?,?,?,1)",
                tenantId, sourceMemberId, sourceMemberId,
                tenantId, ancestorId, sourceMemberId);
        jdbc().update("INSERT INTO skit_commission_plan "
                        + "(id,tenant_id,version,status,published_time) VALUES (?,?,1,0,CURRENT_TIMESTAMP)",
                planId, tenantId);
        jdbc().update("INSERT INTO skit_commission_rule "
                        + "(tenant_id,plan_id,level_no,rate_bps) VALUES (?,?,0,?),(?,?,1,?)",
                tenantId, planId, viewerRateBps, tenantId, planId, ancestorRateBps);
    }

    private void insertMember(long tenantId, long memberId, String label) {
        jdbc().update("INSERT INTO skit_member "
                        + "(id,tenant_id,mobile,password,nickname,invite_code,depth,status) "
                        + "VALUES (?,?,?,?,?,?,1,0)",
                memberId, tenantId, Long.toString(memberId), "encoded-password",
                label + "-" + memberId, "POL" + memberId);
    }

    private int snapshotCount() {
        return jdbc().queryForObject("SELECT COUNT(*) FROM skit_ad_policy_snapshot", Integer.class);
    }

    private static void await(CountDownLatch latch, String timeoutMessage) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException(timeoutMessage);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while coordinating tenant lock test", interrupted);
        }
    }

    static class RollbackFacade {

        private final SkitPolicySnapshotService snapshotService;

        RollbackFacade(SkitPolicySnapshotService snapshotService) {
            this.snapshotService = snapshotService;
        }

        @Transactional(rollbackFor = Exception.class)
        public void createThenFail(Long sourceMemberId) {
            snapshotService.createSnapshot(sourceMemberId);
            throw new IllegalStateException("forced downstream session failure");
        }
    }

    static class TenantLockGate {

        private volatile CountDownLatch locked;
        private volatile CountDownLatch release;

        synchronized void arm() {
            locked = new CountDownLatch(1);
            release = new CountDownLatch(1);
        }

        void afterLock() {
            CountDownLatch currentLocked = locked;
            CountDownLatch currentRelease = release;
            if (currentLocked == null || currentRelease == null) {
                return;
            }
            currentLocked.countDown();
            await(currentRelease, "snapshot tenant lock was not released");
        }

        boolean awaitLocked() throws InterruptedException {
            CountDownLatch current = locked;
            return current != null && current.await(10, TimeUnit.SECONDS);
        }

        void release() {
            CountDownLatch current = release;
            if (current != null) {
                current.countDown();
            }
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement(proxyTargetClass = true)
    @MapperScan(basePackages = {
            "cn.iocoder.yudao.module.skit.dal.mysql.commission",
            "cn.iocoder.yudao.module.skit.dal.mysql.member",
            "cn.iocoder.yudao.module.system.dal.mysql.tenant"
    }, annotationClass = Mapper.class)
    static class RealPolicyConfiguration {

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
            GlobalConfig globalConfig = GlobalConfigUtils.defaults();
            globalConfig.setMetaObjectHandler(new DefaultDBFieldHandler());
            GlobalConfigUtils.setGlobalConfig(configuration, globalConfig);
            MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration,
                    "skit-policy-snapshot-mysql-it");
            TableInfoHelper.remove(SkitAdPolicySnapshotDO.class);
            TableInfoHelper.initTableInfo(assistant, SkitCommissionPlanDO.class);
            TableInfoHelper.initTableInfo(assistant, SkitCommissionRuleDO.class);
            TableInfoHelper.initTableInfo(assistant, SkitMemberDO.class);
            TableInfoHelper.initTableInfo(assistant, SkitMemberClosureDO.class);
            TableInfoHelper.initTableInfo(assistant, TenantDO.class);

            MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
            factory.setDataSource(dataSource);
            factory.setConfiguration(configuration);
            factory.setPlugins(interceptor);
            factory.setGlobalConfig(globalConfig);
            return factory;
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        TenantService tenantService(TenantMapper tenantMapper, TenantLockGate tenantLockGate) {
            TenantService service = mock(TenantService.class);
            when(service.getTenantForShare(anyLong())).thenAnswer(invocation -> {
                TenantDO tenant = tenantMapper.selectByIdForShare(invocation.getArgument(0));
                tenantLockGate.afterLock();
                return tenant;
            });
            doNothing().when(service).validTenant(anyLong());
            return service;
        }

        @Bean
        TenantLockGate tenantLockGate() {
            return new TenantLockGate();
        }

        @Bean
        SkitPolicySnapshotService policySnapshotService(
                cn.iocoder.yudao.module.skit.dal.mysql.commission.SkitCommissionPlanMapper planMapper,
                cn.iocoder.yudao.module.skit.dal.mysql.commission.SkitCommissionRuleMapper ruleMapper,
                cn.iocoder.yudao.module.skit.dal.mysql.member.SkitMemberClosureMapper closureMapper,
                cn.iocoder.yudao.module.skit.dal.mysql.member.SkitMemberMapper memberMapper,
                cn.iocoder.yudao.module.skit.dal.mysql.commission.SkitAdPolicySnapshotMapper snapshotMapper,
                TenantService tenantService) {
            return new SkitPolicySnapshotServiceImpl(planMapper, ruleMapper, closureMapper,
                    memberMapper, snapshotMapper, tenantService);
        }

        @Bean
        RollbackFacade rollbackFacade(SkitPolicySnapshotService snapshotService) {
            return new RollbackFacade(snapshotService);
        }
    }
}
