package cn.iocoder.yudao.module.skit.service.commission;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.skit.controller.admin.tenant.vo.commission.SkitCommissionPlanRespVO;
import cn.iocoder.yudao.module.skit.controller.admin.tenant.vo.commission.SkitCommissionPublishReqVO;
import cn.iocoder.yudao.module.skit.controller.admin.tenant.vo.commission.SkitCommissionRuleVO;
import cn.iocoder.yudao.module.skit.dal.dataobject.commission.SkitCommissionPlanDO;
import cn.iocoder.yudao.module.skit.dal.mysql.commission.SkitCommissionPlanMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.commission.SkitCommissionRuleMapper;
import cn.iocoder.yudao.module.skit.framework.security.SkitAdminTenantScope;
import cn.iocoder.yudao.module.skit.service.management.SkitManagementCommandExecutor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Collections;

import static cn.iocoder.yudao.module.skit.enums.ErrorCodeConstants.COMMISSION_PLAN_VERSION_CONFLICT;
import static cn.iocoder.yudao.module.skit.enums.SkitDomainConstants.COMMISSION_PLAN_ACTIVE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SkitCommissionManagementServiceImplTest {

    private static final long TENANT_ID = 42L;

    @Mock private SkitCommissionPlanMapper planMapper;
    @Mock private SkitCommissionRuleMapper ruleMapper;
    @Mock private SkitManagementCommandExecutor commandExecutor;
    @Mock private JdbcTemplate jdbcTemplate;
    @Mock private SkitAdminTenantScope scope;

    @Test
    void currentReturnsExplicitVersionZeroWhenTenantHasNoPlan() {
        when(planMapper.selectActiveForShare(TENANT_ID)).thenReturn(null);

        SkitCommissionPlanRespVO result = service().getCurrent(TENANT_ID, "UTC+8");

        assertEquals(TENANT_ID, result.getTenantId());
        assertEquals(0, result.getVersion());
        assertEquals("UNCONFIGURED", result.getStatus());
        assertEquals(10000, result.getAgentRateBps());
        assertNull(result.getId());
        verifyNoInteractions(ruleMapper, commandExecutor, jdbcTemplate);
    }

    @Test
    void staleExpectedVersionFailsBeforeAuditOrMutation() {
        when(scope.getTargetTenantId()).thenReturn(TENANT_ID);
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), eq(TENANT_ID)))
                .thenReturn(9L);
        SkitCommissionPlanDO active = SkitCommissionPlanDO.builder().id(88L).version(3)
                .status(COMMISSION_PLAN_ACTIVE)
                .publishedTime(LocalDateTime.of(2026, 7, 14, 10, 0)).build();
        active.setTenantId(TENANT_ID);
        when(planMapper.selectActiveForUpdate(TENANT_ID)).thenReturn(active);
        SkitCommissionPublishReqVO request = new SkitCommissionPublishReqVO()
                .setExpectedVersion(2).setReason("publish verified hierarchy allocation")
                .setRules(Collections.singletonList(
                        new SkitCommissionRuleVO().setLevelNo(0).setRateBps(6000)));

        ServiceException failure = assertThrows(ServiceException.class,
                () -> service().publish(scope, request));

        assertEquals(COMMISSION_PLAN_VERSION_CONFLICT.getCode(), failure.getCode());
        verify(planMapper, never()).insert(any(SkitCommissionPlanDO.class));
        verifyNoInteractions(ruleMapper, commandExecutor);
    }

    private SkitCommissionManagementServiceImpl service() {
        return new SkitCommissionManagementServiceImpl(planMapper, ruleMapper,
                new SkitCommissionPreviewAllocator(), commandExecutor, jdbcTemplate,
                Clock.fixed(Instant.parse("2026-07-15T02:00:00Z"), ZoneOffset.UTC));
    }
}
