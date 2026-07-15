package cn.iocoder.yudao.module.skit.integration;

import cn.iocoder.yudao.framework.mybatis.core.handler.DefaultDBFieldHandler;
import cn.iocoder.yudao.framework.tenant.config.TenantProperties;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.framework.tenant.core.db.TenantDatabaseInterceptor;
import cn.iocoder.yudao.module.skit.dal.dataobject.ad.SkitAdAccountDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.ad.SkitAdSessionDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.agent.SkitAgentDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.commission.SkitAdPolicySnapshotDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.commission.SkitCommissionPlanDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.commission.SkitCommissionRuleDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.member.SkitMemberClosureDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.member.SkitMemberDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.member.SkitNativePlayerGrantDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.record.SkitAdminRecordDO;
import cn.iocoder.yudao.module.skit.dal.mysql.ad.SkitAdAccountMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.ad.SkitAdClientEventMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.ad.SkitAdSessionMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.agent.SkitAgentMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.commission.SkitAdPolicySnapshotMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.commission.SkitCommissionPlanMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.commission.SkitCommissionRuleMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.member.SkitContentEntitlementMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.member.SkitMemberClosureMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.member.SkitMemberMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.member.SkitNativePlayerGrantMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.record.SkitAdminRecordMapper;
import cn.iocoder.yudao.module.skit.service.ad.SkitAdCredentialVersionService;
import cn.iocoder.yudao.module.skit.service.ad.SkitAdSessionCreateTransactionExecutor;
import cn.iocoder.yudao.module.skit.service.ad.SkitAdSessionService;
import cn.iocoder.yudao.module.skit.service.ad.SkitAdSessionServiceImpl;
import cn.iocoder.yudao.module.skit.service.ad.SkitAdSessionTokenService;
import cn.iocoder.yudao.module.skit.service.ad.SkitHmacAdSessionTokenService;
import cn.iocoder.yudao.module.skit.service.ad.SkitTenantAdCapabilityService;
import cn.iocoder.yudao.module.skit.service.commission.SkitPolicySnapshotService;
import cn.iocoder.yudao.module.skit.service.commission.SkitPolicySnapshotServiceImpl;
import cn.iocoder.yudao.module.skit.service.member.SkitContentEntitlementService;
import cn.iocoder.yudao.module.skit.service.member.SkitContentEntitlementServiceImpl;
import cn.iocoder.yudao.module.skit.service.member.SkitContentScopeService;
import cn.iocoder.yudao.module.skit.service.member.SkitContentScopeServiceImpl;
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
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Runs the production ad-session transaction boundary against real MySQL.
 *
 * <p>The concurrency assertions deliberately call the proxied service rather than reproducing
 * its locking algorithm in test code. JDBC is used only to install fixtures and inspect the
 * committed invariant.</p>
 */
class SkitAdSessionMySqlIT extends SkitMySqlIntegrationTestBase {

    private AnnotationConfigApplicationContext context;
    private SkitAdSessionService sessionService;
    private SkitAdSessionMapper sessionMapper;
    private SkitNativePlayerGrantMapper nativeGrantMapper;
    private SourceMemberLockGate sourceMemberLockGate;
    private TimeZone previousJvmTimeZone;
    private String previousGlobalTimeZone;

    @BeforeAll
    void startRealSessionBoundary() {
        previousJvmTimeZone = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));
        previousGlobalTimeZone = jdbc().queryForObject("SELECT @@GLOBAL.time_zone", String.class);
        jdbc().execute("SET GLOBAL time_zone='+08:00'");
        assertEquals("+08:00", jdbc().queryForObject("SELECT @@SESSION.time_zone", String.class));
        context = new AnnotationConfigApplicationContext();
        context.registerBean("dataSource", DataSource.class, this::dataSource);
        context.register(RealSessionConfiguration.class);
        context.refresh();
        sessionService = context.getBean(SkitAdSessionService.class);
        sessionMapper = context.getBean(SkitAdSessionMapper.class);
        nativeGrantMapper = context.getBean(SkitNativePlayerGrantMapper.class);
        sourceMemberLockGate = context.getBean(SourceMemberLockGate.class);
        assertTrue(AopUtils.isAopProxy(sessionService),
                "the session service must execute through Spring's transaction proxy");
    }

    @AfterEach
    void clearTenantContext() {
        TenantContextHolder.clear();
        sourceMemberLockGate.disarm();
    }

    @AfterAll
    void closeRealSessionBoundary() {
        if (context != null) {
            context.close();
        }
        restoreGlobalTimeZone();
        if (previousJvmTimeZone != null) {
            TimeZone.setDefault(previousJvmTimeZone);
        }
    }

    @Test
    void twentyConcurrentCreatesReuseOneActiveSessionAndOnePolicySnapshot() throws Exception {
        SessionFixture fixture = insertSessionFixture(951001L, 951002L, 951003L, 951004L, 951005L);
        long dramaId = 951006L;
        int episodeNo = 7;
        insertCatalog(fixture.tenantId, dramaId, 30, episodeNo - 1);

        List<SkitAdSessionService.CreateResult> results = concurrentCreates(
                20, fixture.tenantId, fixture.memberId, command(dramaId, episodeNo));

        assertEquals(20, results.size());
        Set<String> sessionIds = new HashSet<>();
        long created = 0;
        long reused = 0;
        for (SkitAdSessionService.CreateResult result : results) {
            assertNotNull(result);
            sessionIds.add(result.getSessionId());
            if ("CREATED".equals(result.getOutcome())) {
                created++;
            } else if ("REUSED".equals(result.getOutcome())) {
                reused++;
            }
        }
        assertEquals(1, sessionIds.size(), "all callers must receive the same session id");
        assertEquals(1, created, "exactly one transaction may create the active scope");
        assertEquals(19, reused, "every follower must reuse the committed active scope");
        assertEquals(1, sessionCount(fixture.tenantId, fixture.memberId, dramaId, episodeNo));
        assertEquals(1, activeSessionCount(fixture.tenantId, fixture.memberId, dramaId, episodeNo));
        assertEquals(1, snapshotCount(fixture.tenantId, fixture.memberId));
        assertEquals(1, jdbc().queryForObject("SELECT COUNT(DISTINCT policy_snapshot_id) "
                        + "FROM skit_ad_session WHERE tenant_id=? AND member_id=? AND drama_id=? "
                        + "AND episode_from=? AND deleted=b'0'", Integer.class,
                fixture.tenantId, fixture.memberId, dramaId, episodeNo));
    }

    @Test
    void twoMembersInOneTenantReachTheirExclusiveCoordinationLocksConcurrently() throws Exception {
        SessionFixture firstMember = insertSessionFixture(
                951101L, 951102L, 951103L, 951104L, 951105L);
        long secondMemberId = 951106L;
        insertAdditionalMember(firstMember.tenantId, secondMemberId);
        insertCatalog(firstMember.tenantId, 951107L, 30, 2);
        sourceMemberLockGate.arm(firstMember.tenantId,
                new HashSet<>(Arrays.asList(firstMember.memberId, secondMemberId)));

        ExecutorService workers = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<SkitAdSessionService.CreateResult> first = workers.submit(() -> createAfterBarrier(
                    firstMember.tenantId, firstMember.memberId, command(951107L, 3), ready, start));
            Future<SkitAdSessionService.CreateResult> second = workers.submit(() -> createAfterBarrier(
                    firstMember.tenantId, secondMemberId, command(951107L, 3), ready, start));
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();

            assertEquals("CREATED", first.get(30, TimeUnit.SECONDS).getOutcome());
            assertEquals("CREATED", second.get(30, TimeUnit.SECONDS).getOutcome());
            sourceMemberLockGate.assertReadCommittedProgress();
        } finally {
            start.countDown();
            sourceMemberLockGate.release();
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(10, TimeUnit.SECONDS));
        }

        assertEquals(1, activeSessionCount(
                firstMember.tenantId, firstMember.memberId, 951107L, 3));
        assertEquals(1, activeSessionCount(firstMember.tenantId, secondMemberId, 951107L, 3));
    }

    @Test
    void tenantInterceptorKeepsEveryTask5ExclusiveLockQueryValidForMySql() {
        SessionFixture fixture = insertSessionFixture(
                951201L, 951202L, 951203L, 951204L, 951205L);
        insertCatalog(fixture.tenantId, 951206L, 30, 4);
        SkitAdSessionService.CreateResult created = inTenant(fixture.tenantId,
                () -> sessionService.createForMember(fixture.memberId, command(951206L, 5)));

        assertNotNull(inTenant(fixture.tenantId, () ->
                sessionMapper.selectByTenantMemberAndSessionIdForUpdate(
                        fixture.tenantId, fixture.memberId, created.getSessionId())));

        long grantId = 951207L;
        jdbc().update("INSERT INTO skit_native_player_grant "
                        + "(id,tenant_id,member_id,drama_id,grant_token_hash,status,expires_at,version) "
                        + "VALUES (?,?,?,?,?,'ACTIVE',DATE_ADD(CURRENT_TIMESTAMP, INTERVAL 5 MINUTE),0)",
                grantId, fixture.tenantId, fixture.memberId, 951206L,
                sha256("native-lock-query-" + grantId));
        assertNotNull(inTenant(fixture.tenantId, () -> nativeGrantMapper.selectExactForUpdate(
                fixture.tenantId, grantId, fixture.memberId, 951206L)));
    }

    @Test
    void identicalContentScopesRemainIndependentAcrossTenants() throws Exception {
        SessionFixture tenantA = insertSessionFixture(952001L, 952002L, 952003L, 952004L, 952005L);
        SessionFixture tenantB = insertSessionFixture(952101L, 952102L, 952103L, 952104L, 952105L);
        long dramaId = 952201L;
        int episodeNo = 9;
        insertCatalog(tenantA.tenantId, dramaId, 30, episodeNo - 1);
        insertCatalog(tenantB.tenantId, dramaId, 30, episodeNo - 1);
        SkitAdSessionService.CreateCommand command = command(dramaId, episodeNo);

        ExecutorService workers = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<SkitAdSessionService.CreateResult> first = workers.submit(
                    () -> createAfterBarrier(tenantA.tenantId, tenantA.memberId, command, ready, start));
            Future<SkitAdSessionService.CreateResult> second = workers.submit(
                    () -> createAfterBarrier(tenantB.tenantId, tenantB.memberId, command, ready, start));
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();

            SkitAdSessionService.CreateResult resultA = first.get(30, TimeUnit.SECONDS);
            SkitAdSessionService.CreateResult resultB = second.get(30, TimeUnit.SECONDS);
            assertEquals("CREATED", resultA.getOutcome());
            assertEquals("CREATED", resultB.getOutcome());
            assertNotEquals(resultA.getSessionId(), resultB.getSessionId());
        } finally {
            start.countDown();
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(10, TimeUnit.SECONDS));
        }

        assertEquals(1, activeSessionCount(tenantA.tenantId, tenantA.memberId, dramaId, episodeNo));
        assertEquals(1, activeSessionCount(tenantB.tenantId, tenantB.memberId, dramaId, episodeNo));
        assertEquals(1, snapshotCount(tenantA.tenantId, tenantA.memberId));
        assertEquals(1, snapshotCount(tenantB.tenantId, tenantB.memberId));
    }

    @Test
    void expiredRewardWindowReleasesScopeAndAllowsOneNewSession() {
        SessionFixture fixture = insertSessionFixture(953001L, 953002L, 953003L, 953004L, 953005L);
        long dramaId = 953006L;
        int episodeNo = 11;
        insertCatalog(fixture.tenantId, dramaId, 30, episodeNo - 1);
        SkitAdSessionService.CreateCommand command = command(dramaId, episodeNo);

        SkitAdSessionService.CreateResult first = inTenant(fixture.tenantId,
                () -> sessionService.createForMember(fixture.memberId, command));
        assertEquals("CREATED", first.getOutcome());
        LocalDateTime databaseNow = jdbc().queryForObject("SELECT NOW()", LocalDateTime.class);
        long clockDeltaSeconds = Math.abs(Duration.between(LocalDateTime.now(), databaseNow).getSeconds());
        assertTrue(clockDeltaSeconds <= 5,
                "JVM Asia/Shanghai wall clock must match MySQL CURRENT_TIMESTAMP; delta="
                        + clockDeltaSeconds + " seconds");
        Integer loadWindowSeconds = jdbc().queryForObject(
                "SELECT TIMESTAMPDIFF(SECOND, CURRENT_TIMESTAMP, load_expires_at) "
                        + "FROM skit_ad_session WHERE tenant_id=? AND session_id=?",
                Integer.class, fixture.tenantId, first.getSessionId());
        Integer rewardWindowSeconds = jdbc().queryForObject(
                "SELECT TIMESTAMPDIFF(SECOND, CURRENT_TIMESTAMP, reward_accept_until) "
                        + "FROM skit_ad_session WHERE tenant_id=? AND session_id=?",
                Integer.class, fixture.tenantId, first.getSessionId());
        assertTrue(Math.abs(loadWindowSeconds - 300) <= 5,
                "load window must be about 300 seconds from MySQL CURRENT_TIMESTAMP, actual="
                        + loadWindowSeconds);
        assertTrue(Math.abs(rewardWindowSeconds - 1200) <= 5,
                "reward window must be about 1200 seconds from MySQL CURRENT_TIMESTAMP, actual="
                        + rewardWindowSeconds);
        assertEquals(1, jdbc().update("UPDATE skit_ad_session "
                        + "SET load_expires_at=DATE_SUB(NOW(), INTERVAL 10 MINUTE), "
                        + "reward_accept_until=DATE_SUB(NOW(), INTERVAL 1 MINUTE) "
                        + "WHERE tenant_id=? AND member_id=? AND session_id=? AND deleted=b'0'",
                fixture.tenantId, fixture.memberId, first.getSessionId()));

        SkitAdSessionService.CreateResult second = inTenant(fixture.tenantId,
                () -> sessionService.createForMember(fixture.memberId, command));

        assertEquals("CREATED", second.getOutcome());
        assertNotEquals(first.getSessionId(), second.getSessionId());
        assertEquals(2, sessionCount(fixture.tenantId, fixture.memberId, dramaId, episodeNo));
        assertEquals(1, activeSessionCount(fixture.tenantId, fixture.memberId, dramaId, episodeNo));
        assertEquals(2, snapshotCount(fixture.tenantId, fixture.memberId));
        assertEquals("VERIFY_TIMEOUT", jdbc().queryForObject(
                "SELECT reward_verification_status FROM skit_ad_session "
                        + "WHERE tenant_id=? AND session_id=?", String.class,
                fixture.tenantId, first.getSessionId()));
        assertEquals("VERIFY_TIMEOUT", jdbc().queryForObject(
                "SELECT active_scope_release_reason FROM skit_ad_session "
                        + "WHERE tenant_id=? AND session_id=?", String.class,
                fixture.tenantId, first.getSessionId()));
        assertEquals(0, jdbc().queryForObject(
                "SELECT COUNT(*) FROM skit_ad_session WHERE tenant_id=? AND session_id=? "
                        + "AND active_scope_hash IS NOT NULL", Integer.class,
                fixture.tenantId, first.getSessionId()));
    }

    private List<SkitAdSessionService.CreateResult> concurrentCreates(
            int concurrency, long tenantId, long memberId, SkitAdSessionService.CreateCommand command)
            throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(concurrency);
        CountDownLatch ready = new CountDownLatch(concurrency);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<SkitAdSessionService.CreateResult>> futures = new ArrayList<>();
        try {
            for (int index = 0; index < concurrency; index++) {
                futures.add(workers.submit(
                        () -> createAfterBarrier(tenantId, memberId, command, ready, start)));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS), "workers did not reach the start barrier");
            start.countDown();
            List<SkitAdSessionService.CreateResult> results = new ArrayList<>(concurrency);
            for (Future<SkitAdSessionService.CreateResult> future : futures) {
                results.add(future.get(45, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            start.countDown();
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    private SkitAdSessionService.CreateResult createAfterBarrier(
            long tenantId, long memberId, SkitAdSessionService.CreateCommand command,
            CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        assertTrue(start.await(10, TimeUnit.SECONDS), "start barrier was not released");
        return inTenant(tenantId, () -> sessionService.createForMember(memberId, command));
    }

    private SessionFixture insertSessionFixture(long tenantId, long memberId, long agentId,
                                                long accountId, long planId) {
        jdbc().update("INSERT INTO system_tenant (id,name,package_id,status,expire_time) "
                        + "VALUES (?, ?, 0, 0, '2099-01-01 00:00:00')",
                tenantId, "Session tenant " + tenantId);
        jdbc().update("INSERT INTO skit_agent "
                        + "(id,tenant_id,tenant_code,root_invite_code,status) VALUES (?,?,?,?,0)",
                agentId, tenantId, "TEN" + tenantId, "ROOT" + tenantId);
        jdbc().update("INSERT INTO skit_ad_account "
                        + "(id,tenant_id,provider,account_name,account_id,app_id,config_data,status) "
                        + "VALUES (?,?,'TAKU',?,?,?, ?,0)",
                accountId, tenantId, "TAKU-" + tenantId, "account-" + tenantId,
                "app-" + tenantId, "{\"placementId\":\"placement-" + tenantId + "\"}");
        jdbc().update("INSERT INTO skit_member "
                        + "(id,tenant_id,mobile,password,nickname,invite_code,depth,status) "
                        + "VALUES (?,?,?,?,?,?,1,0)",
                memberId, tenantId, Long.toString(memberId), "encoded-password",
                "member-" + memberId, "INV" + memberId);
        jdbc().update("INSERT INTO skit_member_closure "
                        + "(tenant_id,ancestor_id,descendant_id,distance) VALUES (?,?,?,0)",
                tenantId, memberId, memberId);
        jdbc().update("INSERT INTO skit_commission_plan "
                        + "(id,tenant_id,version,status,published_time) VALUES (?,?,1,0,NOW())",
                planId, tenantId);
        jdbc().update("INSERT INTO skit_commission_rule "
                        + "(tenant_id,plan_id,level_no,rate_bps) VALUES (?,?,0,7000)",
                tenantId, planId);
        jdbc().update("INSERT INTO skit_ad_callback_key "
                        + "(tenant_id,ad_account_id,key_version,callback_key_hash,active) "
                        + "VALUES (?,?,1,?,b'1')",
                tenantId, accountId, sha256("callback-" + tenantId));
        jdbc().update("INSERT INTO skit_ad_reward_secret_version "
                        + "(tenant_id,ad_account_id,secret_version,ciphertext,nonce,encryption_key_id,"
                        + "envelope_version,active) VALUES (?,?,1,?,?,?,1,b'1')",
                tenantId, accountId, sha256("reward-" + tenantId),
                Arrays.copyOf(sha256("nonce-" + tenantId), 12), "mysql-it-key");
        return new SessionFixture(tenantId, memberId);
    }

    private void insertAdditionalMember(long tenantId, long memberId) {
        jdbc().update("INSERT INTO skit_member "
                        + "(id,tenant_id,mobile,password,nickname,invite_code,depth,status) "
                        + "VALUES (?,?,?,?,?,?,1,0)",
                memberId, tenantId, Long.toString(memberId), "encoded-password",
                "member-" + memberId, "INV" + memberId);
        jdbc().update("INSERT INTO skit_member_closure "
                        + "(tenant_id,ancestor_id,descendant_id,distance) VALUES (?,?,?,0)",
                tenantId, memberId, memberId);
    }

    private void insertCatalog(long tenantId, long dramaId, int totalEpisodes, int freeEpisodes) {
        jdbc().update("INSERT INTO skit_admin_record "
                        + "(tenant_id,page_key,row_key,record_data,status,sort) "
                        + "VALUES (?,'drama',?,?,0,0)", tenantId, "drama-" + dramaId,
                "{\"id\":" + dramaId + ",\"episodes\":" + totalEpisodes
                        + ",\"freeEpisodes\":" + freeEpisodes
                        + ",\"unlockSize\":1,\"status\":\"上架\"}");
    }

    private int sessionCount(long tenantId, long memberId, long dramaId, int episodeNo) {
        return jdbc().queryForObject("SELECT COUNT(*) FROM skit_ad_session "
                        + "WHERE tenant_id=? AND member_id=? AND drama_id=? AND episode_from=? "
                        + "AND episode_to=? AND deleted=b'0'", Integer.class,
                tenantId, memberId, dramaId, episodeNo, episodeNo);
    }

    private int activeSessionCount(long tenantId, long memberId, long dramaId, int episodeNo) {
        return jdbc().queryForObject("SELECT COUNT(*) FROM skit_ad_session "
                        + "WHERE tenant_id=? AND member_id=? AND drama_id=? AND episode_from=? "
                        + "AND episode_to=? AND active_scope_hash IS NOT NULL "
                        + "AND active_scope_released_at IS NULL AND deleted=b'0'", Integer.class,
                tenantId, memberId, dramaId, episodeNo, episodeNo);
    }

    private int snapshotCount(long tenantId, long memberId) {
        return jdbc().queryForObject("SELECT COUNT(*) FROM skit_ad_policy_snapshot "
                        + "WHERE tenant_id=? AND source_member_id=? AND deleted=b'0'", Integer.class,
                tenantId, memberId);
    }

    private static SkitAdSessionService.CreateCommand command(long dramaId, int episodeNo) {
        SkitAdSessionService.CreateCommand command = new SkitAdSessionService.CreateCommand();
        command.setDramaId(dramaId);
        command.setEpisodeNo(episodeNo);
        return command;
    }

    private static <T> T inTenant(long tenantId, TenantWork<T> work) {
        TenantContextHolder.setTenantId(tenantId);
        try {
            return work.execute();
        } finally {
            TenantContextHolder.clear();
        }
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private void restoreGlobalTimeZone() {
        if (previousGlobalTimeZone == null) {
            return;
        }
        if (!"SYSTEM".equals(previousGlobalTimeZone)
                && !previousGlobalTimeZone.matches("[+-]\\d{2}:\\d{2}")) {
            throw new IllegalStateException("Unexpected MySQL global time zone: "
                    + previousGlobalTimeZone);
        }
        jdbc().execute("SET GLOBAL time_zone='" + previousGlobalTimeZone + "'");
    }

    @FunctionalInterface
    private interface TenantWork<T> {
        T execute();
    }

    private static final class SessionFixture {
        private final long tenantId;
        private final long memberId;

        private SessionFixture(long tenantId, long memberId) {
            this.tenantId = tenantId;
            this.memberId = memberId;
        }
    }

    @Intercepts(@Signature(type = Executor.class, method = "query", args = {
            MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class}))
    static final class SourceMemberLockGate implements Interceptor {

        private volatile GateState state;

        synchronized void arm(long tenantId, Set<Long> memberIds) {
            if (memberIds == null || memberIds.size() < 2) {
                throw new IllegalArgumentException("at least two distinct members are required");
            }
            state = new GateState(tenantId, memberIds);
        }

        void release() {
            GateState current = state;
            if (current != null) {
                current.release.countDown();
            }
        }

        synchronized void disarm() {
            release();
            state = null;
        }

        void assertReadCommittedProgress() {
            GateState current = state;
            assertNotNull(current, "source-member coordination gate was not armed");
            assertEquals(0L, current.arrived.getCount(),
                    "both members must acquire their distinct coordination rows before either proceeds");
            assertEquals(Collections.singleton(java.sql.Connection.TRANSACTION_READ_COMMITTED),
                    current.isolationLevels,
                    "every create attempt must run in a READ_COMMITTED transaction");
        }

        @Override
        public Object intercept(Invocation invocation) throws Throwable {
            Object result = invocation.proceed();
            GateState current = state;
            if (current == null) {
                return result;
            }
            MappedStatement statement = (MappedStatement) invocation.getArgs()[0];
            if (!statement.getId().endsWith("SkitMemberMapper.selectByTenantAndIdForUpdate")) {
                return result;
            }
            Object parameter = invocation.getArgs()[1];
            if (!(parameter instanceof Map)) {
                return result;
            }
            Map<?, ?> values = (Map<?, ?>) parameter;
            Object tenantValue = values.get("tenantId");
            Object memberValue = values.get("id");
            if (!(tenantValue instanceof Number) || !(memberValue instanceof Number)
                    || ((Number) tenantValue).longValue() != current.tenantId
                    || !current.memberIds.contains(((Number) memberValue).longValue())) {
                return result;
            }
            Integer isolation = TransactionSynchronizationManager.getCurrentTransactionIsolationLevel();
            if (isolation != null) {
                current.isolationLevels.add(isolation);
            }
            current.arrived.countDown();
            if (!current.arrived.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("same-tenant members were serialized before their source-member locks");
            }
            current.release.countDown();
            return result;
        }

        private static final class GateState {
            private final long tenantId;
            private final Set<Long> memberIds;
            private final Set<Integer> isolationLevels =
                    Collections.synchronizedSet(new HashSet<Integer>());
            private final CountDownLatch arrived;
            private final CountDownLatch release = new CountDownLatch(1);

            private GateState(long tenantId, Set<Long> memberIds) {
                this.tenantId = tenantId;
                this.memberIds = Collections.unmodifiableSet(new HashSet<>(memberIds));
                this.arrived = new CountDownLatch(memberIds.size());
            }
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement(proxyTargetClass = true)
    static class RealSessionConfiguration {

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
                                                       MybatisPlusInterceptor interceptor,
                                                       SourceMemberLockGate sourceMemberLockGate) {
            MybatisConfiguration configuration = new MybatisConfiguration();
            configuration.setMapUnderscoreToCamelCase(true);
            GlobalConfig globalConfig = GlobalConfigUtils.defaults();
            globalConfig.setMetaObjectHandler(new DefaultDBFieldHandler());
            GlobalConfigUtils.setGlobalConfig(configuration, globalConfig);
            MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration,
                    "skit-ad-session-mysql-it");
            initializeTableInfo(assistant, SkitAdAccountDO.class);
            initializeTableInfo(assistant, SkitAgentDO.class);
            initializeTableInfo(assistant, SkitCommissionPlanDO.class);
            initializeTableInfo(assistant, SkitCommissionRuleDO.class);
            initializeTableInfo(assistant, SkitMemberClosureDO.class);
            initializeTableInfo(assistant, SkitMemberDO.class);
            initializeTableInfo(assistant, SkitAdSessionDO.class);
            initializeTableInfo(assistant, SkitNativePlayerGrantDO.class);
            initializeTableInfo(assistant, SkitAdminRecordDO.class);
            initializeTableInfo(assistant, TenantDO.class);
            TableInfoHelper.remove(SkitAdPolicySnapshotDO.class);

            MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
            factory.setDataSource(dataSource);
            factory.setConfiguration(configuration);
            factory.setPlugins(interceptor, sourceMemberLockGate);
            factory.setGlobalConfig(globalConfig);
            return factory;
        }

        @Bean
        MapperFactoryBean<SkitAdSessionMapper> adSessionMapperFactory(
                SqlSessionFactory sqlSessionFactory) {
            return mapperFactory(SkitAdSessionMapper.class, sqlSessionFactory);
        }

        @Bean
        MapperFactoryBean<SkitAdClientEventMapper> adClientEventMapperFactory(
                SqlSessionFactory sqlSessionFactory) {
            return mapperFactory(SkitAdClientEventMapper.class, sqlSessionFactory);
        }

        @Bean
        MapperFactoryBean<SkitAdAccountMapper> adAccountMapperFactory(
                SqlSessionFactory sqlSessionFactory) {
            return mapperFactory(SkitAdAccountMapper.class, sqlSessionFactory);
        }

        @Bean
        MapperFactoryBean<SkitAgentMapper> agentMapperFactory(SqlSessionFactory sqlSessionFactory) {
            return mapperFactory(SkitAgentMapper.class, sqlSessionFactory);
        }

        @Bean
        MapperFactoryBean<SkitCommissionPlanMapper> commissionPlanMapperFactory(
                SqlSessionFactory sqlSessionFactory) {
            return mapperFactory(SkitCommissionPlanMapper.class, sqlSessionFactory);
        }

        @Bean
        MapperFactoryBean<SkitCommissionRuleMapper> commissionRuleMapperFactory(
                SqlSessionFactory sqlSessionFactory) {
            return mapperFactory(SkitCommissionRuleMapper.class, sqlSessionFactory);
        }

        @Bean
        MapperFactoryBean<SkitAdPolicySnapshotMapper> adPolicySnapshotMapperFactory(
                SqlSessionFactory sqlSessionFactory) {
            return mapperFactory(SkitAdPolicySnapshotMapper.class, sqlSessionFactory);
        }

        @Bean
        MapperFactoryBean<SkitMemberClosureMapper> memberClosureMapperFactory(
                SqlSessionFactory sqlSessionFactory) {
            return mapperFactory(SkitMemberClosureMapper.class, sqlSessionFactory);
        }

        @Bean
        MapperFactoryBean<SkitMemberMapper> memberMapperFactory(SqlSessionFactory sqlSessionFactory) {
            return mapperFactory(SkitMemberMapper.class, sqlSessionFactory);
        }

        @Bean
        MapperFactoryBean<SkitNativePlayerGrantMapper> nativePlayerGrantMapperFactory(
                SqlSessionFactory sqlSessionFactory) {
            return mapperFactory(SkitNativePlayerGrantMapper.class, sqlSessionFactory);
        }

        @Bean
        MapperFactoryBean<SkitContentEntitlementMapper> contentEntitlementMapperFactory(
                SqlSessionFactory sqlSessionFactory) {
            return mapperFactory(SkitContentEntitlementMapper.class, sqlSessionFactory);
        }

        @Bean
        MapperFactoryBean<SkitAdminRecordMapper> adminRecordMapperFactory(
                SqlSessionFactory sqlSessionFactory) {
            return mapperFactory(SkitAdminRecordMapper.class, sqlSessionFactory);
        }

        @Bean
        MapperFactoryBean<TenantMapper> tenantMapperFactory(SqlSessionFactory sqlSessionFactory) {
            return mapperFactory(TenantMapper.class, sqlSessionFactory);
        }

        private static <T> MapperFactoryBean<T> mapperFactory(
                Class<T> mapperType, SqlSessionFactory sqlSessionFactory) {
            MapperFactoryBean<T> factory = new MapperFactoryBean<>(mapperType);
            factory.setSqlSessionFactory(sqlSessionFactory);
            return factory;
        }

        private static void initializeTableInfo(MapperBuilderAssistant assistant, Class<?> type) {
            TableInfoHelper.remove(type);
            TableInfoHelper.initTableInfo(assistant, type);
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        SourceMemberLockGate sourceMemberLockGate() {
            return new SourceMemberLockGate();
        }

        @Bean
        TenantService tenantService(TenantMapper tenantMapper) {
            TenantService service = mock(TenantService.class);
            when(service.getTenantForUpdate(anyLong())).thenAnswer(
                    invocation -> tenantMapper.selectByIdForUpdate(invocation.getArgument(0)));
            when(service.getTenantForShare(anyLong())).thenAnswer(
                    invocation -> tenantMapper.selectByIdForShare(invocation.getArgument(0)));
            doNothing().when(service).validTenant(anyLong());
            return service;
        }

        @Bean
        SkitAdSessionCreateTransactionExecutor adSessionCreateTransactionExecutor(
                PlatformTransactionManager transactionManager) {
            return new SkitAdSessionCreateTransactionExecutor(transactionManager);
        }

        @Bean
        SkitAdCredentialVersionService credentialVersionService() {
            return new FixtureCredentialVersionService();
        }

        @Bean
        SkitPolicySnapshotService policySnapshotService(
                SkitCommissionPlanMapper planMapper,
                SkitCommissionRuleMapper ruleMapper,
                SkitMemberClosureMapper closureMapper,
                SkitMemberMapper memberMapper,
                SkitAdPolicySnapshotMapper snapshotMapper,
                TenantService tenantService) {
            return new SkitPolicySnapshotServiceImpl(planMapper, ruleMapper, closureMapper,
                    memberMapper, snapshotMapper, tenantService);
        }

        @Bean
        SkitContentScopeService contentScopeService(
                SkitAdminRecordMapper recordMapper,
                SkitContentEntitlementMapper entitlementMapper,
                ObjectMapper objectMapper) {
            return new SkitContentScopeServiceImpl(recordMapper, entitlementMapper, objectMapper);
        }

        @Bean
        SkitContentEntitlementService contentEntitlementService(
                SkitNativePlayerGrantMapper nativeGrantMapper,
                SkitContentEntitlementMapper entitlementMapper,
                SkitContentScopeService contentScopeService,
                SkitMemberMapper memberMapper,
                SkitAgentMapper agentMapper,
                TenantService tenantService,
                SkitTenantAdCapabilityService capabilityService) {
            return new SkitContentEntitlementServiceImpl(nativeGrantMapper, entitlementMapper,
                    contentScopeService,
                    memberMapper, agentMapper, tenantService, capabilityService);
        }

        @Bean
        SkitTenantAdCapabilityService tenantAdCapabilityService() {
            return mock(SkitTenantAdCapabilityService.class);
        }

        @Bean
        SkitAdSessionTokenService adSessionTokenService() {
            byte[] key = "0123456789abcdef0123456789abcdef"
                    .getBytes(StandardCharsets.US_ASCII);
            return new SkitHmacAdSessionTokenService(1, Collections.singletonMap(1, key));
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        SkitAdSessionService adSessionService(
                SkitAdSessionMapper sessionMapper,
                SkitAdClientEventMapper clientEventMapper,
                SkitAdAccountMapper accountMapper,
                SkitAgentMapper agentMapper,
                SkitMemberMapper memberMapper,
                SkitAdCredentialVersionService credentialService,
                SkitPolicySnapshotService snapshotService,
                SkitContentEntitlementService entitlementService,
                SkitContentScopeService contentScopeService,
                TenantService tenantService,
                SkitAdSessionTokenService tokenService,
                ObjectMapper objectMapper,
                SkitAdSessionCreateTransactionExecutor createTransactionExecutor,
                SkitTenantAdCapabilityService capabilityService) {
            return new SkitAdSessionServiceImpl(sessionMapper, clientEventMapper, accountMapper,
                    agentMapper, memberMapper, credentialService, snapshotService,
                    entitlementService, contentScopeService, tenantService, tokenService, objectMapper,
                    createTransactionExecutor, capabilityService);
        }
    }

    private static final class FixtureCredentialVersionService
            implements SkitAdCredentialVersionService {

        @Override
        public CallbackKeyIssue rotateCallbackKey(long tenantId, long adAccountId,
                                                  Duration priorAcceptanceWindow) {
            throw unsupported();
        }

        @Override
        public CredentialMetadata rotateRewardSecret(long tenantId, long adAccountId,
                                                       byte[] rewardSecret,
                                                       Duration priorAcceptanceWindow) {
            throw unsupported();
        }

        @Override
        public CredentialMetadata getActiveCallbackKeyVersion(long tenantId, long adAccountId) {
            return active(tenantId, adAccountId);
        }

        @Override
        public CredentialMetadata getActiveRewardSecretVersion(long tenantId, long adAccountId) {
            return active(tenantId, adAccountId);
        }

        @Override
        public CallbackKeyResolution resolveCallbackKey(String callbackKey,
                                                        LocalDateTime authoritativeReceivedAt) {
            throw unsupported();
        }

        @Override
        public ResolvedRewardSecret resolveRewardSecret(long tenantId, long adAccountId,
                                                        int secretVersion,
                                                        LocalDateTime sessionRewardAcceptUntil,
                                                        LocalDateTime authoritativeReceivedAt) {
            throw unsupported();
        }

        private static CredentialMetadata active(long tenantId, long accountId) {
            return new CredentialMetadata(tenantId, accountId, 1, true, null);
        }

        private static UnsupportedOperationException unsupported() {
            return new UnsupportedOperationException("credential rotation is outside this fixture");
        }
    }
}
