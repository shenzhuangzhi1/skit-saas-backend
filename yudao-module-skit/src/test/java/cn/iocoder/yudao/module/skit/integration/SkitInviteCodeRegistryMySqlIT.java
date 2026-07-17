package cn.iocoder.yudao.module.skit.integration;

import cn.iocoder.yudao.framework.common.biz.system.oauth2.OAuth2TokenCommonApi;
import cn.iocoder.yudao.framework.common.biz.system.oauth2.dto.OAuth2AccessTokenCreateReqDTO;
import cn.iocoder.yudao.framework.common.biz.system.oauth2.dto.OAuth2AccessTokenRespDTO;
import cn.iocoder.yudao.framework.common.enums.CommonStatusEnum;
import cn.iocoder.yudao.framework.common.enums.UserTypeEnum;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.mybatis.core.handler.DefaultDBFieldHandler;
import cn.iocoder.yudao.framework.tenant.config.TenantProperties;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.framework.tenant.core.db.TenantDatabaseInterceptor;
import cn.iocoder.yudao.module.skit.dal.dataobject.agent.SkitAgentDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.invite.SkitInviteCodeRegistryDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.member.SkitMemberClosureDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.member.SkitMemberDO;
import cn.iocoder.yudao.module.skit.dal.mysql.agent.SkitAgentMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.member.SkitMemberClosureMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.member.SkitMemberMapper;
import cn.iocoder.yudao.module.skit.framework.security.SkitPlatformAdminGuard;
import cn.iocoder.yudao.module.skit.service.ad.SkitAdAccountService;
import cn.iocoder.yudao.module.skit.service.agent.SkitAgentServiceImpl;
import cn.iocoder.yudao.module.skit.service.app.SkitAppReleaseService;
import cn.iocoder.yudao.module.skit.service.commission.SkitCommissionService;
import cn.iocoder.yudao.module.skit.service.invite.SkitInviteCodeRegistryService;
import cn.iocoder.yudao.module.skit.service.invite.SkitInviteCodeRegistryService.OwnerType;
import cn.iocoder.yudao.module.skit.service.invite.SkitInviteCodeRegistryService.ResolvedOwner;
import cn.iocoder.yudao.module.skit.service.invite.SkitInviteCodeRegistryServiceImpl;
import cn.iocoder.yudao.module.skit.service.member.SkitMemberService;
import cn.iocoder.yudao.module.skit.service.member.SkitMemberServiceImpl;
import cn.iocoder.yudao.module.system.dal.dataobject.tenant.TenantDO;
import cn.iocoder.yudao.module.system.framework.security.SystemPlatformAdminGuard;
import cn.iocoder.yudao.module.system.service.oauth2.OAuth2TokenService;
import cn.iocoder.yudao.module.system.service.permission.PermissionService;
import cn.iocoder.yudao.module.system.service.tenant.TenantPackageService;
import cn.iocoder.yudao.module.system.service.tenant.TenantService;
import cn.iocoder.yudao.module.system.service.user.AdminUserService;
import com.baomidou.dynamic.datasource.annotation.DSTransactional;
import com.baomidou.dynamic.datasource.aop.DynamicDataSourceAnnotationAdvisor;
import com.baomidou.dynamic.datasource.aop.DynamicLocalTransactionInterceptor;
import com.baomidou.dynamic.datasource.ds.AbstractRoutingDataSource;
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
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Role;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static cn.iocoder.yudao.module.skit.enums.ErrorCodeConstants.INVITE_CODE_EXISTS;
import static cn.iocoder.yudao.module.skit.enums.ErrorCodeConstants.INVITE_CODE_INVALID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SkitInviteCodeRegistryMySqlIT extends SkitMySqlIntegrationTestBase {

    private AnnotationConfigApplicationContext context;
    private SkitInviteCodeRegistryService registryService;
    private SkitAgentMapper agentMapper;
    private SkitMemberMapper memberMapper;
    private SkitMemberClosureMapper closureMapper;
    private SkitAgentServiceImpl agentService;
    private SkitMemberServiceImpl memberService;
    private TransactionTemplate mapperTransactionTemplate;

    @BeforeAll
    void startRealMyBatisBoundary() {
        context = new AnnotationConfigApplicationContext();
        context.registerBean("dataSource", DataSource.class, this::dynamicDataSource);
        context.register(RealInviteConfiguration.class);
        context.refresh();

        registryService = context.getBean(SkitInviteCodeRegistryService.class);
        agentMapper = context.getBean(SkitAgentMapper.class);
        memberMapper = context.getBean(SkitMemberMapper.class);
        closureMapper = context.getBean(SkitMemberClosureMapper.class);
        agentService = context.getBean(SkitAgentServiceImpl.class);
        assertTrue(AopUtils.isAopProxy(agentService),
                "agent service must run through the production @DSTransactional proxy");
        memberService = context.getBean(SkitMemberServiceImpl.class);
        assertTrue(AopUtils.isAopProxy(memberService),
                "member service must run through the production @Transactional proxy");
        mapperTransactionTemplate = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
    }

    @AfterEach
    void clearTenantContext() {
        TenantContextHolder.clear();
    }

    @AfterAll
    void closeRealMyBatisBoundary() {
        if (context != null) {
            context.close();
        }
    }

    @Test
    void twentyConcurrentAgentAndMemberClaimsHaveExactlyOneCommittedOwner() throws Exception {
        int workers = 20;
        for (int index = 0; index < workers; index++) {
            long tenantId = 11000L + index;
            long ownerId = 12000L + index;
            if (index % 2 == 0) {
                insertAgent(tenantId, ownerId, "RACE-ROOT-" + index);
            } else {
                insertMember(tenantId, ownerId, "1391000" + fourDigits(index), "RACE-MEMBER-" + index, null, 0);
            }
        }

        ExecutorService executor = Executors.newFixedThreadPool(workers);
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> futures = new ArrayList<>();
        try {
            for (int index = 0; index < workers; index++) {
                final int worker = index;
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    await(start, "claim start");
                    try {
                        inTransaction(() -> {
                            long tenantId = 11000L + worker;
                            long ownerId = 12000L + worker;
                            if (worker % 2 == 0) {
                                registryService.claimAgent(tenantId, ownerId, "GLOBAL-RACE-CODE");
                            } else {
                                registryService.claimMember(tenantId, ownerId, "GLOBAL-RACE-CODE");
                            }
                        });
                        return true;
                    } catch (ServiceException collision) {
                        assertEquals(INVITE_CODE_EXISTS.getCode(), collision.getCode());
                        return false;
                    }
                }));
            }
            assertTrue(ready.await(30, TimeUnit.SECONDS), "claim workers were not ready");
            start.countDown();
            int committed = 0;
            for (Future<Boolean> future : futures) {
                if (future.get(30, TimeUnit.SECONDS)) {
                    committed++;
                }
            }
            assertEquals(1, committed);
        } finally {
            start.countDown();
            executor.shutdownNow();
        }

        assertEquals(1, count("SELECT COUNT(*) FROM skit_invite_code_registry "
                + "WHERE normalized_code='GLOBAL-RACE-CODE'"));
    }

    @Test
    void caseVariantsCollideUnderTheProductionNormalizationAndCollation() {
        insertAgent(12101L, 12102L, "CASE-ROOT-A");
        insertMember(12103L, 12104L, "13912100104", "CASE-MEMBER-B", null, 0);

        inTransaction(() -> registryService.claimAgent(12101L, 12102L, " Case-Shared "));
        ServiceException collision = assertThrows(ServiceException.class, () -> inTransaction(() ->
                registryService.claimMember(12103L, 12104L, "case-shared")));

        assertEquals(INVITE_CODE_EXISTS.getCode(), collision.getCode());
        assertEquals("CASE-SHARED", jdbc().queryForObject("SELECT code FROM skit_invite_code_registry "
                + "WHERE normalized_code='CASE-SHARED'", String.class));
    }

    @Test
    void rotatedAndDisabledCodesCannotEverBeRebound() {
        insertAgent(12201L, 12202L, "RETIRED-OWNER");
        insertMember(12203L, 12204L, "13912200204", "RETIRED-TARGET", null, 0);
        inTransaction(() -> registryService.claimAgent(12201L, 12202L, "RETIRED-CODE"));
        inTransaction(() -> {
            ResolvedOwner locked = registryService.lockActive(
                    OwnerType.AGENT, 12201L, 12202L, "RETIRED-CODE");
            assertNotNull(locked);
            registryService.rotate(locked, LocalDateTime.of(2026, 7, 14, 13, 0));
        });
        Map<String, Object> rotatedOwner = registryOwnerTuple("RETIRED-CODE");
        assertInviteCollision(() -> registryService.claimMember(12203L, 12204L, "retired-code"));
        assertEquals(rotatedOwner, registryOwnerTuple("RETIRED-CODE"));

        insertAgent(12211L, 12212L, "DISABLED-OWNER");
        insertMember(12213L, 12214L, "13912201214", "DISABLED-TARGET", null, 0);
        inTransaction(() -> registryService.claimAgent(12211L, 12212L, "DISABLED-CODE"));
        assertEquals(1, jdbc().update("UPDATE skit_invite_code_registry "
                + "SET status='DISABLED',rotated_at='2026-07-14 13:30:00' "
                + "WHERE normalized_code='DISABLED-CODE'"));
        Map<String, Object> disabledOwner = registryOwnerTuple("DISABLED-CODE");
        assertInviteCollision(() -> registryService.claimMember(12213L, 12214L, "disabled-code"));
        assertEquals(disabledOwner, registryOwnerTuple("DISABLED-CODE"));

        assertTrue(registryService.isClaimed("retired-code"));
        assertTrue(registryService.isClaimed("disabled-code"));
    }

    @Test
    void registrationFirstAndRotationFirstProduceDeterministicLinearizableOutcomes() throws Exception {
        long registrationFirstTenant = 12301L;
        long registrationFirstAgent = 12302L;
        String registrationFirstCode = "ORDER-REGISTER-FIRST";
        insertAgent(registrationFirstTenant, registrationFirstAgent, registrationFirstCode);
        inMapperTransaction(() -> registryService.claimAgent(
                registrationFirstTenant, registrationFirstAgent, registrationFirstCode));

        CountDownLatch registrationLockHeld = new CountDownLatch(1);
        CountDownLatch rotationReady = new CountDownLatch(1);
        ExecutorService registrationFirstExecutor = Executors.newFixedThreadPool(2);
        try {
            Future<SkitMemberService.AuthResult> registration = registrationFirstExecutor.submit(() ->
                    inMapperTransaction(() -> {
                        assertNotNull(agentMapper.selectByTenantIdForUpdate(registrationFirstTenant));
                        registrationLockHeld.countDown();
                        await(rotationReady, "registration-first rotation readiness");
                        return memberService.register(registerCommand(
                                registrationFirstTenant, "13912300001", registrationFirstCode));
                    }));
            Future<String> rotation = registrationFirstExecutor.submit(() -> {
                await(registrationLockHeld, "registration-first owner lock");
                rotationReady.countDown();
                return inMapperTransaction(() -> agentService.rotateRootInviteCode(registrationFirstTenant));
            });

            assertNotNull(registration.get(30, TimeUnit.SECONDS));
            String newCode = rotation.get(30, TimeUnit.SECONDS);
            assertNotEquals(registrationFirstCode, newCode);
            assertEquals("ROTATED", registryStatus(registrationFirstCode));
            assertEquals("ACTIVE", registryStatus(newCode));
        } finally {
            registrationLockHeld.countDown();
            rotationReady.countDown();
            registrationFirstExecutor.shutdownNow();
        }

        long rotationFirstTenant = 12311L;
        long rotationFirstAgent = 12312L;
        String rotationFirstCode = "ORDER-ROTATE-FIRST";
        insertAgent(rotationFirstTenant, rotationFirstAgent, rotationFirstCode);
        inMapperTransaction(() -> registryService.claimAgent(rotationFirstTenant, rotationFirstAgent, rotationFirstCode));

        CountDownLatch rotationLockHeld = new CountDownLatch(1);
        CountDownLatch registrationReady = new CountDownLatch(1);
        ExecutorService rotationFirstExecutor = Executors.newFixedThreadPool(2);
        try {
            Future<String> rotation = rotationFirstExecutor.submit(() -> inMapperTransaction(() -> {
                assertNotNull(agentMapper.selectByTenantIdForUpdate(rotationFirstTenant));
                rotationLockHeld.countDown();
                await(registrationReady, "rotation-first registration readiness");
                return agentService.rotateRootInviteCode(rotationFirstTenant);
            }));
            Future<Boolean> registration = rotationFirstExecutor.submit(() -> {
                await(rotationLockHeld, "rotation-first owner lock");
                registrationReady.countDown();
                try {
                    inMapperTransaction(() -> memberService.register(registerCommand(
                            rotationFirstTenant, "13912300011", rotationFirstCode)));
                    return true;
                } catch (ServiceException invalid) {
                    assertEquals(INVITE_CODE_INVALID.getCode(), invalid.getCode());
                    return false;
                }
            });

            String newCode = rotation.get(30, TimeUnit.SECONDS);
            assertFalse(registration.get(30, TimeUnit.SECONDS));
            assertEquals("ROTATED", registryStatus(rotationFirstCode));
            assertEquals("ACTIVE", registryStatus(newCode));
        } finally {
            rotationLockHeld.countDown();
            registrationReady.countDown();
            rotationFirstExecutor.shutdownNow();
        }
    }

    @Test
    void twoConcurrentRotationsLeaveOneActiveRegistryCodeEqualToTheAgentRoot() throws Exception {
        long tenantId = 12401L;
        long agentId = 12402L;
        String originalCode = "TWO-ROTATIONS-ROOT";
        insertAgent(tenantId, agentId, originalCode);
        inTransaction(() -> registryService.claimAgent(tenantId, agentId, originalCode));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> rotations = new ArrayList<>();
        try {
            for (int index = 0; index < 2; index++) {
                rotations.add(executor.submit(() -> {
                    ready.countDown();
                    await(start, "rotation start");
                    return inTransaction(() -> agentService.rotateRootInviteCode(tenantId));
                }));
            }
            assertTrue(ready.await(30, TimeUnit.SECONDS), "rotation workers were not ready");
            start.countDown();
            for (Future<String> rotation : rotations) {
                assertNotNull(rotation.get(30, TimeUnit.SECONDS));
            }
        } finally {
            start.countDown();
            executor.shutdownNow();
        }

        String currentRoot = jdbc().queryForObject(
                "SELECT root_invite_code FROM skit_agent WHERE tenant_id=?", String.class, tenantId);
        String activeRegistry = jdbc().queryForObject("SELECT code FROM skit_invite_code_registry "
                + "WHERE tenant_id=? AND agent_id=? AND status='ACTIVE'", String.class, tenantId, agentId);
        assertEquals(currentRoot, activeRegistry);
        assertEquals(1, count("SELECT COUNT(*) FROM skit_invite_code_registry WHERE tenant_id=" + tenantId
                + " AND agent_id=" + agentId + " AND status='ACTIVE'"));
        assertEquals(2, count("SELECT COUNT(*) FROM skit_invite_code_registry WHERE tenant_id=" + tenantId
                + " AND agent_id=" + agentId + " AND status='ROTATED'"));
    }

    @Test
    void forcedClaimFailureRollsBackTheBusinessOwnerInsert() {
        insertAgent(12501L, 12502L, "ROLLBACK-CODE");
        inTransaction(() -> registryService.claimAgent(12501L, 12502L, "ROLLBACK-CODE"));

        ServiceException collision = assertThrows(ServiceException.class, () -> inTransaction(() -> {
            jdbc().update("INSERT INTO skit_member "
                            + "(id,tenant_id,mobile,password,nickname,invite_code,depth,status) "
                            + "VALUES (12504,12503,'13912500004','hash','rollback','ROLLBACK-CODE',0,0)");
            registryService.claimMember(12503L, 12504L, "ROLLBACK-CODE");
        }));

        assertEquals(INVITE_CODE_EXISTS.getCode(), collision.getCode());
        assertEquals(0, count("SELECT COUNT(*) FROM skit_member WHERE id=12504"));
    }

    @Test
    void forcedClosureFailureRollsBackMemberClaimAndEveryClosureRow() {
        long tenantId = 12601L;
        long agentId = 12602L;
        String rootCode = "CLOSURE-ROLLBACK";
        long inviterId = 12603L;
        String inviterCode = "CLOSURE-PARENT";
        insertAgent(tenantId, agentId, rootCode);
        inTransaction(() -> registryService.claimAgent(tenantId, agentId, rootCode));
        insertMember(tenantId, inviterId, "13912600003", inviterCode, null, 0);
        inTransaction(() -> registryService.claimMember(tenantId, inviterId, inviterCode));
        jdbc().update("INSERT INTO skit_member_closure "
                + "(tenant_id,ancestor_id,descendant_id,distance) VALUES (?,?,?,0)",
                tenantId, inviterId, inviterId);
        int memberRegistryBefore = count("SELECT COUNT(*) FROM skit_invite_code_registry "
                + "WHERE tenant_id=" + tenantId + " AND owner_type='MEMBER'");
        List<Map<String, Object>> closureBefore = jdbc().queryForList(
                "SELECT ancestor_id,descendant_id,distance FROM skit_member_closure "
                        + "WHERE tenant_id=? ORDER BY ancestor_id,descendant_id,distance", tenantId);

        String trigger = "skit_it_force_closure_failure";
        jdbc().execute("DROP TRIGGER IF EXISTS " + trigger);
        jdbc().execute("CREATE TRIGGER " + trigger
                + " BEFORE INSERT ON skit_member_closure FOR EACH ROW BEGIN "
                + "IF NEW.distance=1 THEN "
                + "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='forced closure failure'; "
                + "END IF; END");
        try {
            RuntimeException failure = assertThrows(RuntimeException.class, () ->
                    memberService.register(registerCommand(tenantId, "13912600001", inviterCode)));
            assertTrue(rootMessage(failure).contains("forced closure failure"), rootMessage(failure));
        } finally {
            jdbc().execute("DROP TRIGGER IF EXISTS " + trigger);
        }

        assertEquals(0, count("SELECT COUNT(*) FROM skit_member WHERE tenant_id=" + tenantId
                + " AND mobile='13912600001'"));
        assertEquals(memberRegistryBefore, count("SELECT COUNT(*) FROM skit_invite_code_registry "
                + "WHERE tenant_id=" + tenantId + " AND owner_type='MEMBER'"));
        assertEquals(closureBefore, jdbc().queryForList(
                "SELECT ancestor_id,descendant_id,distance FROM skit_member_closure "
                        + "WHERE tenant_id=? ORDER BY ancestor_id,descendant_id,distance", tenantId));
    }

    @Test
    void corruptCrossTenantRegistryOwnerIsRejectedByTheMemberService() {
        long signedTenantId = 12701L;
        long foreignTenantId = 12702L;
        long foreignMemberId = 12703L;
        insertMember(foreignTenantId, foreignMemberId, "13912700003", "FOREIGN-MEMBER-CODE", null, 0);
        jdbc().execute((ConnectionCallback<Void>) connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute("SET FOREIGN_KEY_CHECKS=0");
                statement.executeUpdate("INSERT INTO skit_invite_code_registry "
                        + "(id,tenant_id,code,owner_type,member_id,status) "
                        + "VALUES (12704," + signedTenantId
                        + ",'CORRUPT-CROSS-TENANT','MEMBER'," + foreignMemberId + ",'ACTIVE')");
                statement.execute("SET FOREIGN_KEY_CHECKS=1");
                return null;
            }
        });

        ServiceException registerFailure = assertThrows(ServiceException.class, () -> inTransaction(() ->
                memberService.register(registerCommand(
                        signedTenantId, "13912700001", "CORRUPT-CROSS-TENANT"))));
        assertEquals(INVITE_CODE_INVALID.getCode(), registerFailure.getCode());
        ServiceException resolveFailure = assertThrows(ServiceException.class,
                () -> memberService.resolveInvitation("CORRUPT-CROSS-TENANT"));
        assertEquals(INVITE_CODE_INVALID.getCode(), resolveFailure.getCode());
        assertEquals(0, count("SELECT COUNT(*) FROM skit_member WHERE tenant_id=" + signedTenantId));
    }

    @Test
    void agentRotationNeverRewritesExistingInviterOrClosureHistory() {
        long tenantId = 12801L;
        long agentId = 12802L;
        String rootCode = "HISTORY-ROOT";
        insertAgent(tenantId, agentId, rootCode);
        inTransaction(() -> registryService.claimAgent(tenantId, agentId, rootCode));
        insertMember(tenantId, 12803L, "13912800003", "HISTORY-PARENT", null, 0);
        insertMember(tenantId, 12804L, "13912800004", "HISTORY-CHILD", 12803L, 1);
        inTransaction(() -> registryService.claimMember(tenantId, 12803L, "HISTORY-PARENT"));
        inTransaction(() -> registryService.claimMember(tenantId, 12804L, "HISTORY-CHILD"));
        jdbc().update("INSERT INTO skit_member_closure "
                + "(tenant_id,ancestor_id,descendant_id,distance) VALUES "
                + "(12801,12803,12803,0),(12801,12804,12804,0),(12801,12803,12804,1)");

        Long inviterBefore = jdbc().queryForObject(
                "SELECT inviter_id FROM skit_member WHERE id=12804", Long.class);
        List<Map<String, Object>> closureBefore = jdbc().queryForList("SELECT ancestor_id,descendant_id,distance "
                + "FROM skit_member_closure WHERE tenant_id=12801 ORDER BY ancestor_id,descendant_id");

        String newRoot = inTransaction(() -> agentService.rotateRootInviteCode(tenantId));

        assertNotEquals(rootCode, newRoot);
        assertEquals(inviterBefore, jdbc().queryForObject(
                "SELECT inviter_id FROM skit_member WHERE id=12804", Long.class));
        assertEquals(closureBefore, jdbc().queryForList("SELECT ancestor_id,descendant_id,distance "
                + "FROM skit_member_closure WHERE tenant_id=12801 ORDER BY ancestor_id,descendant_id"));
    }

    @Test
    void dynamicDataSourceAgentRotationRollsBackRegistryAndAgentTogether() {
        long tenantId = 12901L;
        long agentId = 12902L;
        String rootCode = "DS-ROLLBACK-ROOT";
        insertAgent(tenantId, agentId, rootCode);
        inTransaction(() -> registryService.claimAgent(tenantId, agentId, rootCode));

        String trigger = "skit_it_force_agent_root_failure";
        jdbc().execute("DROP TRIGGER IF EXISTS " + trigger);
        jdbc().execute("CREATE TRIGGER " + trigger
                + " BEFORE UPDATE ON skit_agent FOR EACH ROW BEGIN "
                + "IF OLD.tenant_id=" + tenantId + " AND NEW.root_invite_code<>OLD.root_invite_code THEN "
                + "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='forced agent root failure'; "
                + "END IF; END");
        try {
            RuntimeException failure = assertThrows(RuntimeException.class,
                    () -> agentService.rotateRootInviteCode(tenantId));
            assertTrue(rootMessage(failure).contains("forced agent root failure"), rootMessage(failure));
        } finally {
            jdbc().execute("DROP TRIGGER IF EXISTS " + trigger);
        }

        assertEquals(rootCode, jdbc().queryForObject(
                "SELECT root_invite_code FROM skit_agent WHERE tenant_id=?", String.class, tenantId));
        assertEquals("ACTIVE", registryStatus(rootCode));
        assertEquals(1, count("SELECT COUNT(*) FROM skit_invite_code_registry WHERE tenant_id=" + tenantId
                + " AND agent_id=" + agentId));
    }

    private void insertAgent(long tenantId, long agentId, String rootInviteCode) {
        jdbc().update("INSERT INTO skit_agent "
                        + "(id,tenant_id,tenant_code,root_invite_code,status) VALUES (?,?,?,?,?)",
                agentId, tenantId, "AG" + agentId, rootInviteCode,
                CommonStatusEnum.ENABLE.getStatus());
    }

    private void insertMember(long tenantId, long memberId, String mobile, String inviteCode,
                              Long inviterId, int depth) {
        jdbc().update("INSERT INTO skit_member "
                        + "(id,tenant_id,mobile,password,nickname,inviter_id,invite_code,depth,status) "
                        + "VALUES (?,?,?,?,?,?,?,?,?)",
                memberId, tenantId, mobile, "encoded-password", "member-" + memberId,
                inviterId, inviteCode, depth, CommonStatusEnum.ENABLE.getStatus());
    }

    private SkitMemberService.RegisterCommand registerCommand(long tenantId, String mobile, String inviteCode) {
        SkitMemberService.RegisterCommand command = new SkitMemberService.RegisterCommand();
        command.setTenantId(tenantId);
        command.setMobile(mobile);
        command.setPassword("secret123");
        command.setNickname("member-" + mobile);
        command.setInviteCode(inviteCode);
        command.setRegisterIp("127.0.0.1");
        return command;
    }

    private <T> T inMapperTransaction(java.util.function.Supplier<T> work) {
        return mapperTransactionTemplate.execute(status -> work.get());
    }

    private void inMapperTransaction(Runnable work) {
        mapperTransactionTemplate.executeWithoutResult(status -> work.run());
    }

    private void assertInviteCollision(Runnable claim) {
        ServiceException collision = assertThrows(ServiceException.class,
                () -> inTransaction(claim));
        assertEquals(INVITE_CODE_EXISTS.getCode(), collision.getCode());
    }

    private String registryStatus(String code) {
        return jdbc().queryForObject("SELECT status FROM skit_invite_code_registry "
                + "WHERE normalized_code=UPPER(TRIM(?))", String.class, code);
    }

    private Map<String, Object> registryOwnerTuple(String code) {
        return jdbc().queryForMap("SELECT tenant_id,owner_type,agent_id,member_id,status "
                + "FROM skit_invite_code_registry WHERE normalized_code=UPPER(TRIM(?))", code);
    }

    private DataSource dynamicDataSource() {
        DataSource target = dataSource();
        return new AbstractRoutingDataSource() {
            @Override
            protected DataSource determineDataSource() {
                return target;
            }

            @Override
            protected String getPrimary() {
                return "master";
            }
        };
    }

    private int count(String sql) {
        return Objects.requireNonNull(jdbc().queryForObject(sql, Integer.class));
    }

    private static String fourDigits(int value) {
        return String.format("%04d", value);
    }

    private static void await(CountDownLatch latch, String label) {
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException(label + " timed out");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(label + " interrupted", interrupted);
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
    @MapperScan(basePackages = {
            "cn.iocoder.yudao.module.skit.dal.mysql.invite",
            "cn.iocoder.yudao.module.skit.dal.mysql.agent",
            "cn.iocoder.yudao.module.skit.dal.mysql.member"
    }, annotationClass = Mapper.class)
    static class RealInviteConfiguration {

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
                    "skit-invite-registry-mysql-it");
            TableInfoHelper.initTableInfo(assistant, SkitInviteCodeRegistryDO.class);
            TableInfoHelper.initTableInfo(assistant, SkitAgentDO.class);
            TableInfoHelper.initTableInfo(assistant, SkitMemberDO.class);
            TableInfoHelper.initTableInfo(assistant, SkitMemberClosureDO.class);

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
        SkitInviteCodeRegistryService inviteCodeRegistryService(
                cn.iocoder.yudao.module.skit.dal.mysql.invite.SkitInviteCodeRegistryMapper mapper) {
            return new SkitInviteCodeRegistryServiceImpl(mapper);
        }

        @Bean
        @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
        DynamicDataSourceAnnotationAdvisor dsTransactionalAdvisor() {
            return new DynamicDataSourceAnnotationAdvisor(
                    new DynamicLocalTransactionInterceptor(false), DSTransactional.class);
        }

        @Bean
        SkitPlatformAdminGuard platformAdminGuard() {
            return mock(SkitPlatformAdminGuard.class);
        }

        @Bean
        SystemPlatformAdminGuard systemPlatformAdminGuard() {
            return mock(SystemPlatformAdminGuard.class);
        }

        @Bean
        PermissionService permissionService() {
            return mock(PermissionService.class);
        }

        @Bean
        TenantPackageService tenantPackageService() {
            return mock(TenantPackageService.class);
        }

        @Bean
        AdminUserService adminUserService() {
            return mock(AdminUserService.class);
        }

        @Bean
        SkitAdAccountService adAccountService() {
            return mock(SkitAdAccountService.class);
        }

        @Bean
        SkitCommissionService commissionService() {
            return mock(SkitCommissionService.class);
        }

        @Bean
        SkitAppReleaseService appReleaseService() {
            return mock(SkitAppReleaseService.class);
        }

        @Bean
        SkitAgentServiceImpl agentService() {
            return new SkitAgentServiceImpl();
        }

        @Bean
        TenantService tenantService() {
            TenantService service = mock(TenantService.class);
            when(service.getTenant(any())).thenAnswer(invocation -> new TenantDO()
                    .setId(invocation.getArgument(0)).setName("Tenant " + invocation.getArgument(0)));
            return service;
        }

        @Bean
        PasswordEncoder passwordEncoder() {
            PasswordEncoder encoder = mock(PasswordEncoder.class);
            when(encoder.encode(any())).thenReturn("encoded-password");
            return encoder;
        }

        @Bean
        OAuth2TokenCommonApi oauth2TokenCommonApi() {
            AtomicInteger tokenSequence = new AtomicInteger();
            OAuth2TokenCommonApi tokenApi = mock(OAuth2TokenCommonApi.class);
            when(tokenApi.createAccessToken(any(OAuth2AccessTokenCreateReqDTO.class))).thenAnswer(invocation -> {
                OAuth2AccessTokenCreateReqDTO request = invocation.getArgument(0);
                int sequence = tokenSequence.incrementAndGet();
                return new OAuth2AccessTokenRespDTO()
                        .setAccessToken("task3-access-" + sequence)
                        .setRefreshToken("task3-refresh-" + sequence)
                        .setUserId(request.getUserId())
                        .setUserType(UserTypeEnum.MEMBER.getValue())
                        .setExpiresTime(LocalDateTime.of(2026, 7, 15, 0, 0));
            });
            return tokenApi;
        }

        @Bean
        OAuth2TokenService oauth2TokenService() {
            return mock(OAuth2TokenService.class);
        }

        @Bean
        SkitMemberServiceImpl memberService() {
            return new SkitMemberServiceImpl();
        }
    }

}
