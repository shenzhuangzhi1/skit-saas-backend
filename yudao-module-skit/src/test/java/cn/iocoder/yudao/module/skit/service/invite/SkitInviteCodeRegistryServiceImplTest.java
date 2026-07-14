package cn.iocoder.yudao.module.skit.service.invite;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.skit.dal.dataobject.invite.SkitInviteCodeRegistryDO;
import cn.iocoder.yudao.module.skit.dal.mysql.invite.SkitInviteCodeRegistryMapper;
import com.baomidou.dynamic.datasource.tx.TransactionContext;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.LocalDateTime;
import java.util.Arrays;

import static cn.iocoder.yudao.module.skit.enums.ErrorCodeConstants.INVITE_CODE_EXISTS;
import static cn.iocoder.yudao.module.skit.service.invite.SkitInviteCodeRegistryService.OwnerType.AGENT;
import static cn.iocoder.yudao.module.skit.service.invite.SkitInviteCodeRegistryService.OwnerType.MEMBER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SkitInviteCodeRegistryServiceImplTest {

    private static final Long TENANT_ID = 41L;

    @Mock
    private SkitInviteCodeRegistryMapper mapper;

    @AfterEach
    void clearContexts() {
        TenantContextHolder.clear();
        TransactionSynchronizationManager.clear();
        TransactionContext.remove();
    }

    @Test
    void globalResolutionNormalizesCodeAndReturnsOnlyActiveRows() {
        SkitInviteCodeRegistryDO active = agentRow(7L, TENANT_ID, 51L, " Agent-51 ", "ACTIVE");
        when(mapper.selectGlobalByNormalizedCode("AGENT-51")).thenAnswer(invocation -> {
            assertTrue(TenantContextHolder.isIgnore());
            return active;
        });

        SkitInviteCodeRegistryService.ResolvedOwner resolved = service().resolveActive("  agent-51  ");

        assertEquals(7L, resolved.getId());
        assertEquals(TENANT_ID, resolved.getTenantId());
        assertEquals(AGENT, resolved.getOwnerType());
        assertEquals(51L, resolved.getOwnerId());
        assertEquals("Agent-51", resolved.getCode());
        verify(mapper).selectGlobalByNormalizedCode("AGENT-51");

        active.setStatus("ROTATED");
        assertNull(service().resolveActive("agent-51"));
    }

    @Test
    void terminalRowsRemainClaimedAndCannotBeReused() {
        when(mapper.selectGlobalByNormalizedCode("USED-CODE"))
                .thenReturn(memberRow(9L, TENANT_ID, 61L, "USED-CODE", "DISABLED"));

        assertTrue(service().isClaimed(" used-code "));
        assertFalse(service().isClaimed("   "));
        assertFalse(service().isClaimed(null));
        verify(mapper).selectGlobalByNormalizedCode("USED-CODE");
    }

    @Test
    void globalLookupFailsClosedWhenMapperReturnsAnotherCode() {
        when(mapper.selectGlobalByNormalizedCode("REQUESTED"))
                .thenReturn(agentRow(7L, TENANT_ID, 51L, "DIFFERENT", "ACTIVE"));

        assertThrows(IllegalStateException.class, () -> service().resolveActive("requested"));
    }

    @Test
    void claimNormalizesInsideExplicitTenantAndMapsUniqueConflict() {
        beginSpringTransaction();
        when(mapper.insert(any(SkitInviteCodeRegistryDO.class))).thenAnswer(invocation -> {
            assertEquals(TENANT_ID, TenantContextHolder.getTenantId());
            assertFalse(TenantContextHolder.isIgnore());
            SkitInviteCodeRegistryDO row = invocation.getArgument(0);
            row.setId(88L);
            row.setNormalizedCode("MEMBER-61");
            return 1;
        });

        SkitInviteCodeRegistryService.ResolvedOwner claimed =
                service().claimMember(TENANT_ID, 61L, " member-61 ");

        ArgumentCaptor<SkitInviteCodeRegistryDO> inserted =
                ArgumentCaptor.forClass(SkitInviteCodeRegistryDO.class);
        verify(mapper).insert(inserted.capture());
        assertEquals("MEMBER-61", inserted.getValue().getCode());
        assertEquals("ACTIVE", inserted.getValue().getStatus());
        assertEquals(TENANT_ID, inserted.getValue().getTenantId());
        assertEquals(MEMBER, claimed.getOwnerType());
        assertEquals(61L, claimed.getOwnerId());
        assertEquals(88L, claimed.getId());

        when(mapper.insert(any(SkitInviteCodeRegistryDO.class)))
                .thenThrow(new DuplicateKeyException("global code collision"));
        ServiceException conflict = assertThrows(ServiceException.class,
                () -> service().claimAgent(TENANT_ID, 51L, "member-61"));
        assertEquals(INVITE_CODE_EXISTS.getCode(), conflict.getCode());
    }

    @Test
    void exactOwnerLockUsesTenantTupleAndRejectsMismatchedRows() {
        beginSpringTransaction();
        SkitInviteCodeRegistryDO exact = agentRow(7L, TENANT_ID, 51L, "AGENT-51", "ACTIVE");
        when(mapper.selectActiveForUpdate(TENANT_ID, "AGENT", 51L, "AGENT-51"))
                .thenAnswer(invocation -> {
                    assertEquals(TENANT_ID, TenantContextHolder.getTenantId());
                    return exact;
                });

        SkitInviteCodeRegistryService.ResolvedOwner locked =
                service().lockActive(AGENT, TENANT_ID, 51L, " agent-51 ");

        assertEquals(7L, locked.getId());
        verify(mapper).selectActiveForUpdate(TENANT_ID, "AGENT", 51L, "AGENT-51");

        exact.setTenantId(99L);
        assertThrows(IllegalStateException.class,
                () -> service().lockActive(AGENT, TENANT_ID, 51L, "AGENT-51"));
    }

    @Test
    void rotateIsExactActiveToRotatedAndRequiresOneAffectedRow() {
        beginSpringTransaction();
        LocalDateTime rotatedAt = LocalDateTime.of(2026, 7, 14, 12, 0);
        SkitInviteCodeRegistryService.ResolvedOwner owner = agentOwner();
        when(mapper.rotateActive(7L, TENANT_ID, "AGENT", 51L, "AGENT-51", rotatedAt)).thenReturn(1);

        service().rotate(owner, rotatedAt);

        verify(mapper).rotateActive(7L, TENANT_ID, "AGENT", 51L, "AGENT-51", rotatedAt);

        when(mapper.rotateActive(7L, TENANT_ID, "AGENT", 51L, "AGENT-51", rotatedAt)).thenReturn(0);
        assertThrows(IllegalStateException.class, () -> service().rotate(owner, rotatedAt));
    }

    @Test
    void locksAndMutatorsFailClosedWithoutAnOuterTransaction() {
        SkitInviteCodeRegistryServiceImpl registry = service();

        assertThrows(IllegalStateException.class,
                () -> registry.claimAgent(TENANT_ID, 51L, "AGENT-51"));
        assertThrows(IllegalStateException.class,
                () -> registry.claimMember(TENANT_ID, 61L, "MEMBER-61"));
        assertThrows(IllegalStateException.class,
                () -> registry.lockActive(AGENT, TENANT_ID, 51L, "AGENT-51"));
        assertThrows(IllegalStateException.class,
                () -> registry.rotate(agentOwner(),
                        LocalDateTime.now()));

        verifyNoInteractions(mapper);
    }

    @Test
    void mapperAndServiceExposeNoGenericOwnerMutationOrIndependentTransaction() {
        assertFalse(BaseMapper.class.isAssignableFrom(SkitInviteCodeRegistryMapper.class));
        assertFalse(Arrays.stream(SkitInviteCodeRegistryMapper.class.getMethods())
                .map(Method::getName)
                .anyMatch(name -> name.equals("delete") || name.equals("update")
                        || name.equals("deleteById") || name.equals("updateById")));
        assertFalse(Arrays.stream(SkitInviteCodeRegistryServiceImpl.class.getDeclaredMethods())
                .anyMatch(method -> method.isAnnotationPresent(Transactional.class)));
        assertFalse(SkitInviteCodeRegistryServiceImpl.class.isAnnotationPresent(Transactional.class));
        for (Field field : SkitInviteCodeRegistryService.ResolvedOwner.class.getDeclaredFields()) {
            if (!field.isSynthetic()) {
                assertTrue(Modifier.isFinal(field.getModifiers()), field.getName() + " must be immutable");
            }
        }
        assertFalse(Arrays.stream(SkitInviteCodeRegistryService.ResolvedOwner.class.getMethods())
                .map(Method::getName).anyMatch(name -> name.startsWith("set")));
    }

    @Test
    void dynamicDatasourceTransactionAlsoSatisfiesTheOuterBoundary() {
        String xid = TransactionContext.bind("task-3-xid");
        assertEquals("task-3-xid", xid);
        when(mapper.insert(any(SkitInviteCodeRegistryDO.class))).thenAnswer(invocation -> {
            SkitInviteCodeRegistryDO row = invocation.getArgument(0);
            row.setId(91L);
            return 1;
        });

        assertEquals(91L, service().claimAgent(TENANT_ID, 51L, "AGENT-51").getId());
    }

    private SkitInviteCodeRegistryServiceImpl service() {
        return new SkitInviteCodeRegistryServiceImpl(mapper);
    }

    private static void beginSpringTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
    }

    private static SkitInviteCodeRegistryService.ResolvedOwner agentOwner() {
        return new SkitInviteCodeRegistryService.ResolvedOwner(7L, TENANT_ID, AGENT,
                51L, null, "AGENT-51", "AGENT-51", "ACTIVE", null);
    }

    private static SkitInviteCodeRegistryDO agentRow(Long id, Long tenantId, Long agentId,
                                                     String code, String status) {
        SkitInviteCodeRegistryDO row = new SkitInviteCodeRegistryDO()
                .setId(id).setCode(code).setNormalizedCode(code.trim().toUpperCase())
                .setOwnerType("AGENT").setAgentId(agentId).setStatus(status);
        row.setTenantId(tenantId);
        return row;
    }

    private static SkitInviteCodeRegistryDO memberRow(Long id, Long tenantId, Long memberId,
                                                      String code, String status) {
        SkitInviteCodeRegistryDO row = new SkitInviteCodeRegistryDO()
                .setId(id).setCode(code).setNormalizedCode(code.trim().toUpperCase())
                .setOwnerType("MEMBER").setMemberId(memberId).setStatus(status);
        row.setTenantId(tenantId);
        return row;
    }

}
