package cn.iocoder.yudao.module.skit.integration;

import cn.iocoder.yudao.framework.mybatis.core.handler.DefaultDBFieldHandler;
import cn.iocoder.yudao.framework.tenant.config.TenantProperties;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.framework.tenant.core.db.TenantDatabaseInterceptor;
import cn.iocoder.yudao.module.skit.dal.dataobject.ad.SkitAdAccountDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.ad.SkitAdCallbackAttemptDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.ad.SkitAdCallbackEdgeAttemptDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.ad.SkitAdCallbackInboxDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.ad.SkitAdNetworkCapabilityDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.ad.SkitAdSessionDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.agent.SkitAgentDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.commission.SkitAdPolicySnapshotDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.commission.SkitCommissionPlanDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.commission.SkitCommissionRuleDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.member.SkitMemberClosureDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.member.SkitMemberDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.member.SkitEntitlementGrantDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.member.SkitNativePlayerGrantDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.record.SkitAdminRecordDO;
import cn.iocoder.yudao.module.skit.dal.mysql.ad.SkitAdAccountMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.ad.SkitAdCallbackAttemptMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.ad.SkitAdCallbackEdgeAttemptMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.ad.SkitAdCallbackInboxMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.ad.SkitAdClientEventMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.ad.SkitAdNetworkCapabilityMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.ad.SkitAdSessionMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.agent.SkitAgentMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.commission.SkitAdPolicySnapshotMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.commission.SkitCommissionPlanMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.commission.SkitCommissionRuleMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.member.SkitContentEntitlementMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.member.SkitEntitlementGrantMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.member.SkitMemberClosureMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.member.SkitMemberMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.member.SkitNativePlayerGrantMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.record.SkitAdminRecordMapper;
import cn.iocoder.yudao.module.skit.framework.crypto.SkitCallbackPayloadCryptoService;
import cn.iocoder.yudao.module.skit.service.ad.SkitAdCredentialVersionService;
import cn.iocoder.yudao.module.skit.service.ad.SkitAdSessionCreateTransactionExecutor;
import cn.iocoder.yudao.module.skit.service.ad.SkitAdSessionService;
import cn.iocoder.yudao.module.skit.service.ad.SkitAdSessionServiceImpl;
import cn.iocoder.yudao.module.skit.service.ad.SkitAdSessionTokenService;
import cn.iocoder.yudao.module.skit.service.ad.SkitHmacAdSessionTokenService;
import cn.iocoder.yudao.module.skit.service.ad.SkitTenantAdCapabilityService;
import cn.iocoder.yudao.module.skit.service.ad.callback.SkitCallbackIngressService;
import cn.iocoder.yudao.module.skit.service.ad.callback.SkitCallbackIngressServiceImpl;
import cn.iocoder.yudao.module.skit.service.ad.callback.SkitCallbackRateLimiter;
import cn.iocoder.yudao.module.skit.service.ad.callback.SkitCallbackRoutingService;
import cn.iocoder.yudao.module.skit.service.ad.callback.TakuCallbackCanonicalizer;
import cn.iocoder.yudao.module.skit.service.ad.callback.TakuRewardSignatureVerifier;
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
import java.net.URLEncoder;
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
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
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

    private static final byte[] CALLBACK_REWARD_SECRET =
            "taku-reward-secret-32-bytes-value".getBytes(StandardCharsets.US_ASCII);

    private AnnotationConfigApplicationContext context;
    private SkitAdSessionService sessionService;
    private SkitCallbackIngressService callbackIngressService;
    private SkitAdSessionMapper sessionMapper;
    private SkitNativePlayerGrantMapper nativeGrantMapper;
    private SourceMemberLockGate sourceMemberLockGate;
    private SessionRowLockRaceProbe sessionRowLockRaceProbe;
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
        callbackIngressService = context.getBean(SkitCallbackIngressService.class);
        sessionMapper = context.getBean(SkitAdSessionMapper.class);
        nativeGrantMapper = context.getBean(SkitNativePlayerGrantMapper.class);
        sourceMemberLockGate = context.getBean(SourceMemberLockGate.class);
        sessionRowLockRaceProbe = context.getBean(SessionRowLockRaceProbe.class);
        assertTrue(AopUtils.isAopProxy(sessionService),
                "the session service must execute through Spring's transaction proxy");
        assertTrue(AopUtils.isAopProxy(callbackIngressService),
                "the callback ingress must execute through Spring's transaction proxy");
    }

    @AfterEach
    void clearTenantContext() {
        TenantContextHolder.clear();
        sourceMemberLockGate.disarm();
        sessionRowLockRaceProbe.disarm();
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
    void freshPureCreatedSessionIsReusedBeforeStartupLeaseExpires() {
        SessionFixture fixture = insertSessionFixture(951301L, 951302L, 951303L, 951304L, 951305L);
        long dramaId = 951306L;
        int episodeNo = 8;
        insertCatalog(fixture.tenantId, dramaId, 30, episodeNo - 1);
        SkitAdSessionService.CreateCommand command = command(dramaId, episodeNo);

        SkitAdSessionService.CreateResult first = inTenant(fixture.tenantId,
                () -> sessionService.createForMember(fixture.memberId, command));
        SkitAdSessionService.SessionView fresh = inTenant(fixture.tenantId,
                () -> sessionService.getForMember(fixture.memberId, first.getSessionId(),
                        new SkitTenantAdCapabilityService.ClientRuntime(null, null)));
        SkitAdSessionService.CreateResult second = inTenant(fixture.tenantId,
                () -> sessionService.createForMember(fixture.memberId, command));

        assertEquals("CREATED", first.getOutcome());
        assertEquals("CREATED", fresh.getClientLifecycleStatus());
        assertEquals("PENDING", fresh.getRewardVerificationStatus());
        assertEquals("REUSED", second.getOutcome());
        assertEquals(first.getSessionId(), second.getSessionId());
        assertEquals(1, sessionCount(fixture.tenantId, fixture.memberId, dramaId, episodeNo));
        assertEquals(1, activeSessionCount(fixture.tenantId, fixture.memberId, dramaId, episodeNo));
        assertEquals(1, snapshotCount(fixture.tenantId, fixture.memberId));
    }

    @Test
    void stalePureCreatedStatusPollRejectsScopeAndNextCreateReplacesIt() {
        SessionFixture fixture = insertSessionFixture(951401L, 951402L, 951403L, 951404L, 951405L);
        long dramaId = 951406L;
        int episodeNo = 9;
        insertCatalog(fixture.tenantId, dramaId, 30, episodeNo - 1);
        SkitAdSessionService.CreateCommand command = command(dramaId, episodeNo);

        SkitAdSessionService.CreateResult first = inTenant(fixture.tenantId,
                () -> sessionService.createForMember(fixture.memberId, command));
        assertEquals(1, jdbc().update("UPDATE skit_ad_session "
                        + "SET create_time=DATE_SUB(CURRENT_TIMESTAMP, INTERVAL 10 SECOND) "
                        + "WHERE tenant_id=? AND session_id=? AND deleted=b'0'",
                fixture.tenantId, first.getSessionId()));

        SkitAdSessionService.SessionView rejected = inTenant(fixture.tenantId,
                () -> sessionService.getForMember(fixture.memberId, first.getSessionId(),
                        new SkitTenantAdCapabilityService.ClientRuntime(null, null)));
        SkitAdSessionService.CreateResult replacement = inTenant(fixture.tenantId,
                () -> sessionService.createForMember(fixture.memberId, command));

        assertEquals("LOAD_EXPIRED", rejected.getClientLifecycleStatus());
        assertEquals("REJECTED", rejected.getRewardVerificationStatus());
        assertEquals("CREATED", replacement.getOutcome());
        assertNotEquals(first.getSessionId(), replacement.getSessionId());
        assertEquals(2, sessionCount(fixture.tenantId, fixture.memberId, dramaId, episodeNo));
        assertEquals(1, activeSessionCount(fixture.tenantId, fixture.memberId, dramaId, episodeNo));
        assertEquals(2, snapshotCount(fixture.tenantId, fixture.memberId));
        assertEquals("ORPHAN_CREATED_REPLACED", jdbc().queryForObject(
                "SELECT failure_reason FROM skit_ad_session WHERE tenant_id=? AND session_id=?",
                String.class, fixture.tenantId, first.getSessionId()));
    }

    @Test
    void stalePureCreatedDirectCreateRejectsAndReplacesInOneRequest() {
        SessionFixture fixture = insertSessionFixture(951701L, 951702L, 951703L, 951704L, 951705L);
        long dramaId = 951706L;
        int episodeNo = 12;
        insertCatalog(fixture.tenantId, dramaId, 30, episodeNo - 1);
        SkitAdSessionService.CreateCommand command = command(dramaId, episodeNo);

        SkitAdSessionService.CreateResult first = inTenant(fixture.tenantId,
                () -> sessionService.createForMember(fixture.memberId, command));
        assertEquals(1, jdbc().update("UPDATE skit_ad_session "
                        + "SET create_time=DATE_SUB(CURRENT_TIMESTAMP, INTERVAL 10 SECOND) "
                        + "WHERE tenant_id=? AND session_id=? AND deleted=b'0'",
                fixture.tenantId, first.getSessionId()));

        SkitAdSessionService.CreateResult replacement = inTenant(fixture.tenantId,
                () -> sessionService.createForMember(fixture.memberId, command));

        assertEquals("CREATED", replacement.getOutcome());
        assertNotEquals(first.getSessionId(), replacement.getSessionId());
        assertEquals(2, sessionCount(fixture.tenantId, fixture.memberId, dramaId, episodeNo));
        assertEquals(1, activeSessionCount(fixture.tenantId, fixture.memberId, dramaId, episodeNo));
        assertEquals(2, snapshotCount(fixture.tenantId, fixture.memberId));
        assertEquals("LOAD_EXPIRED", jdbc().queryForObject(
                "SELECT client_lifecycle_status FROM skit_ad_session WHERE tenant_id=? AND session_id=?",
                String.class, fixture.tenantId, first.getSessionId()));
        assertEquals("REJECTED", jdbc().queryForObject(
                "SELECT reward_verification_status FROM skit_ad_session WHERE tenant_id=? AND session_id=?",
                String.class, fixture.tenantId, first.getSessionId()));
    }

    @Test
    void pureCreatedRecoveryCasUsesStrictDatabaseCutoffBoundary() {
        SessionFixture fixture = insertSessionFixture(951801L, 951802L, 951803L, 951804L, 951805L);
        long dramaId = 951806L;
        int episodeNo = 13;
        insertCatalog(fixture.tenantId, dramaId, 30, episodeNo - 1);
        SkitAdSessionService.CreateResult created = inTenant(fixture.tenantId,
                () -> sessionService.createForMember(
                        fixture.memberId, command(dramaId, episodeNo)));
        SkitAdSessionDO row = inTenant(fixture.tenantId,
                () -> sessionMapper.selectByTenantMemberAndSessionId(
                        fixture.tenantId, fixture.memberId, created.getSessionId()));
        LocalDateTime exactCreateTime = row.getCreateTime();

        int atBoundary = inTenant(fixture.tenantId,
                () -> sessionMapper.rejectPureCreatedAndReleaseScopeCas(
                        fixture.tenantId, row.getId(), fixture.memberId, row.getVersion(),
                        exactCreateTime, exactCreateTime));
        int afterBoundary = inTenant(fixture.tenantId,
                () -> sessionMapper.rejectPureCreatedAndReleaseScopeCas(
                        fixture.tenantId, row.getId(), fixture.memberId, row.getVersion(),
                        exactCreateTime.plusSeconds(1), exactCreateTime.plusSeconds(1)));

        assertEquals(0, atBoundary, "create_time equal to cutoff must remain reusable");
        assertEquals(1, afterBoundary, "only create_time strictly before cutoff may be reclaimed");
        assertEquals("ORPHAN_CREATED_REPLACED", jdbc().queryForObject(
                "SELECT failure_reason FROM skit_ad_session WHERE tenant_id=? AND session_id=?",
                String.class, fixture.tenantId, created.getSessionId()));
    }

    @Test
    void loadStartedFactPreventsOldSessionFromBeingReclaimedAsPureCreated() {
        SessionFixture fixture = insertSessionFixture(951501L, 951502L, 951503L, 951504L, 951505L);
        long dramaId = 951506L;
        int episodeNo = 10;
        insertCatalog(fixture.tenantId, dramaId, 30, episodeNo - 1);
        SkitAdSessionService.CreateCommand command = command(dramaId, episodeNo);

        SkitAdSessionService.CreateResult first = inTenant(fixture.tenantId,
                () -> sessionService.createForMember(fixture.memberId, command));
        SkitAdSessionService.SessionView loading = inTenant(fixture.tenantId,
                () -> sessionService.recordClientEvents(fixture.memberId, first.getSessionId(),
                        Collections.singletonList(clientEvent(first, "startup-loading", 0,
                                "LOAD_STARTED", "LOADING", "request-startup-loading")),
                        new SkitTenantAdCapabilityService.ClientRuntime(null, null)));
        assertEquals(1, jdbc().update("UPDATE skit_ad_session "
                        + "SET create_time=DATE_SUB(CURRENT_TIMESTAMP, INTERVAL 10 SECOND) "
                        + "WHERE tenant_id=? AND session_id=? AND deleted=b'0'",
                fixture.tenantId, first.getSessionId()));

        SkitAdSessionService.CreateResult second = inTenant(fixture.tenantId,
                () -> sessionService.createForMember(fixture.memberId, command));

        assertEquals("LOADING", loading.getClientLifecycleStatus());
        assertEquals("REUSED", second.getOutcome());
        assertEquals(first.getSessionId(), second.getSessionId());
        assertEquals(1, sessionCount(fixture.tenantId, fixture.memberId, dramaId, episodeNo));
        assertEquals(1, activeSessionCount(fixture.tenantId, fixture.memberId, dramaId, episodeNo));
        assertEquals(1, snapshotCount(fixture.tenantId, fixture.memberId));
    }

    @Test
    void lateLoadStartIsAcceptedWhenNoRecoveryTransitionHasCommitted() {
        SessionFixture fixture = insertSessionFixture(951601L, 951602L, 951603L, 951604L, 951605L);
        long dramaId = 951606L;
        int episodeNo = 11;
        insertCatalog(fixture.tenantId, dramaId, 30, episodeNo - 1);
        SkitAdSessionService.CreateCommand command = command(dramaId, episodeNo);

        SkitAdSessionService.CreateResult first = inTenant(fixture.tenantId,
                () -> sessionService.createForMember(fixture.memberId, command));
        assertEquals(1, jdbc().update("UPDATE skit_ad_session "
                        + "SET create_time=DATE_SUB(CURRENT_TIMESTAMP, INTERVAL 10 SECOND) "
                        + "WHERE tenant_id=? AND session_id=? AND deleted=b'0'",
                fixture.tenantId, first.getSessionId()));

        SkitAdSessionService.SessionView loading = inTenant(fixture.tenantId,
                () -> sessionService.recordClientEvents(fixture.memberId, first.getSessionId(),
                        Collections.singletonList(clientEvent(first, "late-startup-loading", 0,
                                "LOAD_STARTED", "LOADING", "request-late-startup-loading")),
                        new SkitTenantAdCapabilityService.ClientRuntime(null, null)));
        SkitAdSessionService.CreateResult reused = inTenant(fixture.tenantId,
                () -> sessionService.createForMember(fixture.memberId, command));

        assertEquals("LOADING", loading.getClientLifecycleStatus());
        assertEquals("PENDING", loading.getRewardVerificationStatus());
        assertEquals("REUSED", reused.getOutcome());
        assertEquals(first.getSessionId(), reused.getSessionId());
        assertEquals(1, sessionCount(fixture.tenantId, fixture.memberId, dramaId, episodeNo));
        assertEquals(1, activeSessionCount(fixture.tenantId, fixture.memberId, dramaId, episodeNo));
        assertEquals(1, jdbc().queryForObject(
                "SELECT COUNT(*) FROM skit_ad_client_event e JOIN skit_ad_session s "
                        + "ON s.tenant_id=e.tenant_id AND s.id=e.ad_session_id "
                        + "WHERE s.tenant_id=? AND s.session_id=?",
                Integer.class, fixture.tenantId, first.getSessionId()));
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

    @Test
    void preShowClientFailureReleasesScopeAndAllowsImmediateReplacement() {
        SessionFixture fixture = insertSessionFixture(953101L, 953102L, 953103L, 953104L, 953105L);
        long dramaId = 953106L;
        int episodeNo = 12;
        insertCatalog(fixture.tenantId, dramaId, 30, episodeNo - 1);
        SkitAdSessionService.CreateCommand command = command(dramaId, episodeNo);

        SkitAdSessionService.CreateResult first = inTenant(fixture.tenantId,
                () -> sessionService.createForMember(fixture.memberId, command));
        assertEquals("CREATED", first.getOutcome());
        SkitAdSessionService.ClientEventCommand failed = new SkitAdSessionService.ClientEventCommand();
        failed.setProtocolVersion(1);
        failed.setClientEventId("pre-show-failure-1");
        failed.setCallbackSequence(0);
        failed.setSessionId(first.getSessionId());
        failed.setProvider("TAKU");
        failed.setPlacementId("placement-" + fixture.tenantId);
        failed.setEventType("FAILED");
        failed.setNativeState("ERROR");
        failed.setSdkRequestId("request-pre-show-failure-1");
        failed.setClientRewardObserved(false);
        failed.setClosed(false);

        SkitAdSessionService.SessionView terminal = inTenant(fixture.tenantId,
                () -> sessionService.recordClientEvents(fixture.memberId, first.getSessionId(),
                        Collections.singletonList(failed),
                        new SkitTenantAdCapabilityService.ClientRuntime(null, null)));
        SkitAdSessionService.CreateResult second = inTenant(fixture.tenantId,
                () -> sessionService.createForMember(fixture.memberId, command));

        assertEquals("FAILED", terminal.getClientLifecycleStatus());
        assertEquals("REJECTED", terminal.getRewardVerificationStatus());
        assertEquals("NONE", terminal.getEntitlementStatus());
        assertEquals("NONE", terminal.getRevenueStatus());
        assertEquals("CREATED", second.getOutcome());
        assertNotEquals(first.getSessionId(), second.getSessionId());
        assertEquals(2, sessionCount(fixture.tenantId, fixture.memberId, dramaId, episodeNo));
        assertEquals(1, activeSessionCount(fixture.tenantId, fixture.memberId, dramaId, episodeNo));
        assertEquals("REWARD_REJECTED", jdbc().queryForObject(
                "SELECT active_scope_release_reason FROM skit_ad_session "
                        + "WHERE tenant_id=? AND session_id=?", String.class,
                fixture.tenantId, first.getSessionId()));
        assertEquals("CLIENT_PRE_SHOW_FAILED", jdbc().queryForObject(
                "SELECT failure_reason FROM skit_ad_session WHERE tenant_id=? AND session_id=?",
                String.class, fixture.tenantId, first.getSessionId()));
    }

    @Test
    void signedRewardIngressAndPreShowFailureRaceHasExactlyOneAuthoritativeOutcome() throws Exception {
        SessionFixture fixture = insertSessionFixture(953201L, 953202L, 953203L, 953204L, 953205L);
        long dramaId = 953206L;
        int episodeNo = 13;
        insertCatalog(fixture.tenantId, dramaId, 30, episodeNo - 1);
        jdbc().update("INSERT INTO skit_ad_network_capability "
                        + "(tenant_id,ad_account_id,network_firm_id,reward_authority,supports_user_id,"
                        + "supports_custom_data,supports_stable_transaction,supports_impression_revenue,"
                        + "supports_reporting,enabled,verified_at) "
                        + "VALUES (?,?,66,'SIGNED_REWARD',b'1',b'1',b'1',b'1',b'1',b'1',NOW())",
                fixture.tenantId, fixture.accountId);

        SkitAdSessionService.CreateResult created = inTenant(fixture.tenantId,
                () -> sessionService.createForMember(fixture.memberId, command(dramaId, episodeNo)));
        assertEquals("CREATED", created.getOutcome());
        String sdkRequestId = "request-reward-failure-race";
        SkitAdSessionService.SessionView loading = inTenant(fixture.tenantId,
                () -> sessionService.recordClientEvents(fixture.memberId, created.getSessionId(),
                        Collections.singletonList(clientEvent(created, "race-loading", 0,
                                "LOAD_STARTED", "LOADING", sdkRequestId)),
                        new SkitTenantAdCapabilityService.ClientRuntime(null, null)));
        assertEquals("LOADING", loading.getClientLifecycleStatus());

        String showId = "signed-race-show";
        String rawQuery = signedRewardQuery(created, showId);
        SkitAdSessionService.ClientEventCommand failed = clientEvent(created,
                "race-pre-show-failed", 1, "FAILED", "ERROR", sdkRequestId);
        sessionRowLockRaceProbe.arm(fixture.tenantId,
                "SkitAdSessionMapper.selectByTokenHashForUpdate");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        SkitCallbackIngressService.IngressResponse callbackResult;
        SkitAdSessionService.SessionView failedResult;
        try {
            Future<SkitCallbackIngressService.IngressResponse> callback = workers.submit(() -> {
                awaitRaceStart(ready, start);
                return callbackIngressService.receiveReward(callbackKey(fixture), rawQuery,
                        "203.0.113.29");
            });
            Future<SkitAdSessionService.SessionView> clientFailure = workers.submit(() -> {
                awaitRaceStart(ready, start);
                return inTenant(fixture.tenantId,
                        () -> sessionService.recordClientEvents(fixture.memberId,
                                created.getSessionId(), Collections.singletonList(failed),
                                new SkitTenantAdCapabilityService.ClientRuntime(null, null)));
            });
            assertTrue(ready.await(10, TimeUnit.SECONDS), "race workers did not become ready");
            start.countDown();
            callbackResult = callback.get(30, TimeUnit.SECONDS);
            failedResult = clientFailure.get(30, TimeUnit.SECONDS);
        } finally {
            start.countDown();
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(10, TimeUnit.SECONDS));
        }
        sessionRowLockRaceProbe.assertConcurrentTransactionBoundary();

        Map<String, Object> session = jdbc().queryForMap("SELECT client_lifecycle_status,"
                        + "reward_verification_status,entitlement_status,revenue_status,"
                        + "reward_callback_inbox_id,reward_callback_received_at,active_scope_hash,"
                        + "active_scope_released_at,active_scope_release_reason,failure_reason,version "
                        + "FROM skit_ad_session WHERE tenant_id=? AND session_id=?",
                fixture.tenantId, created.getSessionId());
        boolean callbackReceiptAuthoritative = session.get("reward_callback_inbox_id") != null
                && session.get("reward_callback_received_at") != null;
        boolean clientFailureAuthoritative = "REJECTED".equals(
                session.get("reward_verification_status"));
        assertEquals(1, (callbackReceiptAuthoritative ? 1 : 0)
                        + (clientFailureAuthoritative ? 1 : 0),
                "a signed receipt and a pre-show rejection cannot both become authoritative");
        assertEquals(SkitCallbackIngressService.IngressResponse.OK, callbackResult,
                "the signed callback that locks the session first must remain authoritative");
        assertEquals("FAILED", failedResult.getClientLifecycleStatus());
        assertEquals("PENDING", failedResult.getRewardVerificationStatus());
        assertEquals("FAILED", session.get("client_lifecycle_status"));
        assertEquals("PENDING", session.get("reward_verification_status"));
        assertEquals("NONE", session.get("entitlement_status"));
        assertEquals("NONE", session.get("revenue_status"));
        assertNotNull(session.get("reward_callback_inbox_id"));
        assertNotNull(session.get("reward_callback_received_at"));
        assertNotNull(session.get("active_scope_hash"));
        assertNull(session.get("active_scope_released_at"));
        assertNull(session.get("active_scope_release_reason"));
        assertNull(session.get("failure_reason"));
        assertEquals(3, ((Number) session.get("version")).intValue(),
                "load, signed receipt and telemetry failure must commit as three serial changes");
        assertEquals(2, jdbc().queryForObject("SELECT COUNT(*) FROM skit_ad_client_event "
                        + "WHERE tenant_id=? AND ad_session_id=(SELECT id FROM skit_ad_session "
                        + "WHERE tenant_id=? AND session_id=?)",
                Integer.class, fixture.tenantId, fixture.tenantId, created.getSessionId()));
        assertEquals(1, callbackInboxCount(fixture.tenantId));
        assertEquals(1, callbackAttemptCount(fixture.tenantId));
        assertEquals(0, callbackEdgeAttemptCount(fixture.tenantId));
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

    private static void awaitRaceStart(CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        assertTrue(start.await(10, TimeUnit.SECONDS), "race start barrier was not released");
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
        return new SessionFixture(tenantId, memberId, accountId);
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

    private int callbackInboxCount(long tenantId) {
        return jdbc().queryForObject(
                "SELECT COUNT(*) FROM skit_ad_callback_inbox WHERE tenant_id=?",
                Integer.class, tenantId);
    }

    private int callbackAttemptCount(long tenantId) {
        return jdbc().queryForObject(
                "SELECT COUNT(*) FROM skit_ad_callback_attempt WHERE tenant_id=?",
                Integer.class, tenantId);
    }

    private int callbackEdgeAttemptCount(long tenantId) {
        return jdbc().queryForObject(
                "SELECT COUNT(*) FROM skit_ad_callback_edge_attempt WHERE tenant_id=?",
                Integer.class, tenantId);
    }

    private static SkitAdSessionService.CreateCommand command(long dramaId, int episodeNo) {
        SkitAdSessionService.CreateCommand command = new SkitAdSessionService.CreateCommand();
        command.setDramaId(dramaId);
        command.setEpisodeNo(episodeNo);
        return command;
    }

    private static SkitAdSessionService.ClientEventCommand clientEvent(
            SkitAdSessionService.CreateResult session, String eventId, int callbackSequence,
            String eventType, String nativeState, String sdkRequestId) {
        SkitAdSessionService.ClientEventCommand event = new SkitAdSessionService.ClientEventCommand();
        event.setProtocolVersion(1);
        event.setClientEventId(eventId);
        event.setCallbackSequence(callbackSequence);
        event.setSessionId(session.getSessionId());
        event.setProvider(session.getProvider());
        event.setPlacementId(session.getPlacementId());
        event.setEventType(eventType);
        event.setNativeState(nativeState);
        event.setSdkRequestId(sdkRequestId);
        event.setClientRewardObserved(false);
        event.setClosed(false);
        return event;
    }

    private static String callbackKey(SessionFixture fixture) {
        return "mysql_it_" + fixture.tenantId + '_' + fixture.accountId;
    }

    private static String signedRewardQuery(SkitAdSessionService.CreateResult session,
                                            String showId) {
        String adsourceId = "7";
        String ilrd = "{\"network_firm_id\":66,\"adsource_id\":\"" + adsourceId
                + "\",\"id\":\"" + showId + "\",\"adunit_id\":\""
                + session.getPlacementId() + "\"}";
        String preimage = "trans_id=" + showId + "&placement_id=" + session.getPlacementId()
                + "&adsource_id=" + adsourceId + "&reward_amount=1&reward_name=coin&sec_key="
                + new String(CALLBACK_REWARD_SECRET, StandardCharsets.US_ASCII) + "&ilrd=" + ilrd;
        return "user_id=" + encode(session.getUserId()) + "&trans_id=" + showId
                + "&reward_amount=1&reward_name=coin&placement_id="
                + encode(session.getPlacementId()) + "&extra_data=" + encode(session.getCustomData())
                + "&network_firm_id=66&adsource_id=" + adsourceId
                + "&scenario_id=drama_unlock&sign=" + md5Hex(preimage)
                + "&ilrd=" + encode(ilrd);
    }

    private static String md5Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("MD5")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte item : digest) {
                result.append(String.format("%02x", item & 0xff));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Taku-required MD5 is unavailable", exception);
        }
    }

    private static String encode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (Exception exception) {
            throw new IllegalStateException("UTF-8 URL encoding is unavailable", exception);
        }
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
        private final long accountId;

        private SessionFixture(long tenantId, long memberId, long accountId) {
            this.tenantId = tenantId;
            this.memberId = memberId;
            this.accountId = accountId;
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

    @Intercepts(@Signature(type = Executor.class, method = "query", args = {
            MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class}))
    static final class SessionRowLockRaceProbe implements Interceptor {

        private volatile RaceState state;

        synchronized void arm(long tenantId, String firstLockStatement) {
            state = new RaceState(tenantId, firstLockStatement);
        }

        synchronized void disarm() {
            RaceState current = state;
            if (current != null) {
                while (current.arrived.getCount() > 0) {
                    current.arrived.countDown();
                }
                current.firstLockAcquired.countDown();
            }
            state = null;
        }

        void assertConcurrentTransactionBoundary() {
            RaceState current = state;
            assertNotNull(current, "session row-lock race probe was not armed");
            assertEquals(0L, current.arrived.getCount(),
                    "both transaction paths must reach their production SELECT ... FOR UPDATE");
            assertEquals(2, current.activeTransactionCount.get(),
                    "both row-lock queries must execute inside active Spring transactions");
            assertEquals(new HashSet<>(Arrays.asList(
                            java.sql.Connection.TRANSACTION_READ_COMMITTED,
                            java.sql.Connection.TRANSACTION_REPEATABLE_READ)),
                    current.isolationLevels,
                    "callback ingress and client telemetry must keep their production isolation levels");
            assertEquals(new HashSet<>(Arrays.asList(
                            "SkitAdSessionMapper.selectByTokenHashForUpdate",
                            "SkitAdSessionMapper.selectByTenantMemberAndSessionIdForUpdate")),
                    current.lockStatements,
                    "the race must contend through both production session row-lock paths");
        }

        @Override
        public Object intercept(Invocation invocation) throws Throwable {
            RaceState current = state;
            if (current == null) {
                return invocation.proceed();
            }
            MappedStatement statement = (MappedStatement) invocation.getArgs()[0];
            String lockStatement;
            if (statement.getId().endsWith("SkitAdSessionMapper.selectByTokenHashForUpdate")) {
                lockStatement = "SkitAdSessionMapper.selectByTokenHashForUpdate";
            } else if (statement.getId().endsWith(
                    "SkitAdSessionMapper.selectByTenantMemberAndSessionIdForUpdate")) {
                lockStatement = "SkitAdSessionMapper.selectByTenantMemberAndSessionIdForUpdate";
            } else {
                return invocation.proceed();
            }
            Object parameter = invocation.getArgs()[1];
            if (!(parameter instanceof Map)) {
                return invocation.proceed();
            }
            Object tenantValue = ((Map<?, ?>) parameter).get("tenantId");
            if (!(tenantValue instanceof Number)
                    || ((Number) tenantValue).longValue() != current.tenantId) {
                return invocation.proceed();
            }
            if (!TransactionSynchronizationManager.isActualTransactionActive()) {
                throw new AssertionError("session row lock executed outside a Spring transaction");
            }
            Integer isolation = TransactionSynchronizationManager.getCurrentTransactionIsolationLevel();
            if (isolation == null) {
                throw new AssertionError("session row lock transaction isolation is unavailable");
            }
            current.activeTransactionCount.incrementAndGet();
            current.isolationLevels.add(isolation);
            current.lockStatements.add(lockStatement);
            current.arrived.countDown();
            if (!current.arrived.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("both race transactions did not reach the row lock together");
            }
            if (current.firstLockStatement.equals(lockStatement)) {
                try {
                    return invocation.proceed();
                } finally {
                    current.firstLockAcquired.countDown();
                }
            }
            if (!current.firstLockAcquired.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("preferred race transaction did not acquire the row lock");
            }
            return invocation.proceed();
        }

        private static final class RaceState {
            private final long tenantId;
            private final String firstLockStatement;
            private final CountDownLatch arrived = new CountDownLatch(2);
            private final CountDownLatch firstLockAcquired = new CountDownLatch(1);
            private final AtomicInteger activeTransactionCount = new AtomicInteger();
            private final Set<Integer> isolationLevels =
                    Collections.synchronizedSet(new HashSet<Integer>());
            private final Set<String> lockStatements =
                    Collections.synchronizedSet(new HashSet<String>());

            private RaceState(long tenantId, String firstLockStatement) {
                this.tenantId = tenantId;
                if (!"SkitAdSessionMapper.selectByTokenHashForUpdate".equals(firstLockStatement)
                        && !"SkitAdSessionMapper.selectByTenantMemberAndSessionIdForUpdate"
                        .equals(firstLockStatement)) {
                    throw new IllegalArgumentException("unsupported first row-lock path");
                }
                this.firstLockStatement = firstLockStatement;
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
                                                       SourceMemberLockGate sourceMemberLockGate,
                                                       SessionRowLockRaceProbe sessionRowLockRaceProbe) {
            MybatisConfiguration configuration = new MybatisConfiguration();
            configuration.setMapUnderscoreToCamelCase(true);
            GlobalConfig globalConfig = GlobalConfigUtils.defaults();
            globalConfig.setMetaObjectHandler(new DefaultDBFieldHandler());
            GlobalConfigUtils.setGlobalConfig(configuration, globalConfig);
            MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration,
                    "skit-ad-session-mysql-it");
            initializeTableInfo(assistant, SkitAdAccountDO.class);
            initializeTableInfo(assistant, SkitAdCallbackAttemptDO.class);
            initializeTableInfo(assistant, SkitAdCallbackEdgeAttemptDO.class);
            initializeTableInfo(assistant, SkitAdCallbackInboxDO.class);
            initializeTableInfo(assistant, SkitAdNetworkCapabilityDO.class);
            initializeTableInfo(assistant, SkitAgentDO.class);
            initializeTableInfo(assistant, SkitCommissionPlanDO.class);
            initializeTableInfo(assistant, SkitCommissionRuleDO.class);
            initializeTableInfo(assistant, SkitMemberClosureDO.class);
            initializeTableInfo(assistant, SkitMemberDO.class);
            initializeTableInfo(assistant, SkitAdSessionDO.class);
            initializeTableInfo(assistant, SkitEntitlementGrantDO.class);
            initializeTableInfo(assistant, SkitNativePlayerGrantDO.class);
            initializeTableInfo(assistant, SkitAdminRecordDO.class);
            initializeTableInfo(assistant, TenantDO.class);
            TableInfoHelper.remove(SkitAdPolicySnapshotDO.class);

            MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
            factory.setDataSource(dataSource);
            factory.setConfiguration(configuration);
            factory.setPlugins(interceptor, sourceMemberLockGate, sessionRowLockRaceProbe);
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
        MapperFactoryBean<SkitAdCallbackInboxMapper> adCallbackInboxMapperFactory(
                SqlSessionFactory sqlSessionFactory) {
            return mapperFactory(SkitAdCallbackInboxMapper.class, sqlSessionFactory);
        }

        @Bean
        MapperFactoryBean<SkitAdCallbackAttemptMapper> adCallbackAttemptMapperFactory(
                SqlSessionFactory sqlSessionFactory) {
            return mapperFactory(SkitAdCallbackAttemptMapper.class, sqlSessionFactory);
        }

        @Bean
        MapperFactoryBean<SkitAdCallbackEdgeAttemptMapper> adCallbackEdgeAttemptMapperFactory(
                SqlSessionFactory sqlSessionFactory) {
            return mapperFactory(SkitAdCallbackEdgeAttemptMapper.class, sqlSessionFactory);
        }

        @Bean
        MapperFactoryBean<SkitAdNetworkCapabilityMapper> adNetworkCapabilityMapperFactory(
                SqlSessionFactory sqlSessionFactory) {
            return mapperFactory(SkitAdNetworkCapabilityMapper.class, sqlSessionFactory);
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
        MapperFactoryBean<SkitEntitlementGrantMapper> entitlementGrantMapperFactory(
                SqlSessionFactory sqlSessionFactory) {
            return mapperFactory(SkitEntitlementGrantMapper.class, sqlSessionFactory);
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
        SessionRowLockRaceProbe sessionRowLockRaceProbe() {
            return new SessionRowLockRaceProbe();
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
                SkitEntitlementGrantMapper entitlementGrantMapper,
                SkitContentScopeService contentScopeService,
                SkitMemberMapper memberMapper,
                SkitAgentMapper agentMapper,
                TenantService tenantService,
                SkitTenantAdCapabilityService capabilityService) {
            return new SkitContentEntitlementServiceImpl(nativeGrantMapper, entitlementMapper,
                    entitlementGrantMapper,
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
        TakuCallbackCanonicalizer takuCallbackCanonicalizer() {
            return new TakuCallbackCanonicalizer();
        }

        @Bean
        TakuRewardSignatureVerifier takuRewardSignatureVerifier(ObjectMapper objectMapper) {
            return new TakuRewardSignatureVerifier(objectMapper);
        }

        @Bean
        SkitCallbackRoutingService callbackRoutingService(
                SkitAdCredentialVersionService credentialService) {
            return new SkitCallbackRoutingService(credentialService);
        }

        @Bean
        SkitCallbackRateLimiter callbackRateLimiter() {
            return mock(SkitCallbackRateLimiter.class);
        }

        @Bean
        SkitCallbackPayloadCryptoService callbackPayloadCryptoService() {
            SkitCallbackPayloadCryptoService service = mock(SkitCallbackPayloadCryptoService.class);
            when(service.encrypt(any(SkitCallbackPayloadCryptoService.Context.class), any(byte[].class)))
                    .thenAnswer(invocation -> {
                        byte[] plaintext = invocation.getArgument(1);
                        return new SkitCallbackPayloadCryptoService.PayloadEnvelope(
                                sha256(new String(plaintext, StandardCharsets.US_ASCII)),
                                new byte[12], "mysql-it-key", 1);
                    });
            return service;
        }

        @Bean
        SkitCallbackIngressService callbackIngressService(
                SkitCallbackRoutingService routingService,
                TakuCallbackCanonicalizer canonicalizer,
                TakuRewardSignatureVerifier signatureVerifier,
                SkitAdCredentialVersionService credentialService,
                SkitAdSessionTokenService tokenService,
                SkitAdSessionMapper sessionMapper,
                SkitAdCallbackInboxMapper inboxMapper,
                SkitAdCallbackAttemptMapper attemptMapper,
                SkitAdCallbackEdgeAttemptMapper edgeAttemptMapper,
                SkitAdNetworkCapabilityMapper networkCapabilityMapper,
                SkitCallbackPayloadCryptoService payloadCryptoService,
                SkitCallbackRateLimiter rateLimiter) {
            return new SkitCallbackIngressServiceImpl(routingService, canonicalizer,
                    signatureVerifier, credentialService, tokenService, sessionMapper,
                    inboxMapper, attemptMapper, edgeAttemptMapper, networkCapabilityMapper,
                    payloadCryptoService, rateLimiter);
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
            if (callbackKey == null || authoritativeReceivedAt == null
                    || !callbackKey.startsWith("mysql_it_")) {
                throw new CredentialUnavailableException();
            }
            String[] identity = callbackKey.substring("mysql_it_".length()).split("_", -1);
            if (identity.length != 2) {
                throw new CredentialUnavailableException();
            }
            try {
                long tenantId = Long.parseLong(identity[0]);
                long accountId = Long.parseLong(identity[1]);
                if (tenantId <= 0 || accountId <= 0) {
                    throw new CredentialUnavailableException();
                }
                return new CallbackKeyResolution(tenantId, accountId, 1, true, null);
            } catch (NumberFormatException invalid) {
                throw new CredentialUnavailableException();
            }
        }

        @Override
        public ResolvedRewardSecret resolveRewardSecret(long tenantId, long adAccountId,
                                                        int secretVersion,
                                                        LocalDateTime sessionRewardAcceptUntil,
                                                        LocalDateTime authoritativeReceivedAt) {
            if (tenantId <= 0 || adAccountId <= 0 || secretVersion != 1
                    || sessionRewardAcceptUntil == null || authoritativeReceivedAt == null
                    || authoritativeReceivedAt.isAfter(sessionRewardAcceptUntil)) {
                throw new CredentialUnavailableException();
            }
            return new ResolvedRewardSecret(tenantId, adAccountId, 1, true, null,
                    CALLBACK_REWARD_SECRET);
        }

        private static CredentialMetadata active(long tenantId, long accountId) {
            return new CredentialMetadata(tenantId, accountId, 1, true, null);
        }

        private static UnsupportedOperationException unsupported() {
            return new UnsupportedOperationException("credential rotation is outside this fixture");
        }
    }
}
