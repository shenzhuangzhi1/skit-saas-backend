package cn.iocoder.yudao.module.skit.service.member;

import cn.iocoder.yudao.framework.common.enums.CommonStatusEnum;
import cn.iocoder.yudao.framework.common.exception.ErrorCode;
import cn.iocoder.yudao.module.skit.dal.dataobject.agent.SkitAgentDO;
import cn.iocoder.yudao.module.skit.dal.mysql.agent.SkitAgentMapper;
import cn.iocoder.yudao.module.system.service.tenant.TenantService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static cn.iocoder.yudao.framework.test.core.util.AssertUtils.assertServiceException;
import static cn.iocoder.yudao.module.skit.enums.ErrorCodeConstants.MEMBER_APP_CONTEXT_INVALID;
import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.system.enums.ErrorCodeConstants.TENANT_DISABLE;
import static cn.iocoder.yudao.module.system.enums.ErrorCodeConstants.TENANT_EXPIRE;
import static cn.iocoder.yudao.module.system.enums.ErrorCodeConstants.TENANT_NOT_EXISTS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import java.util.Arrays;

@ExtendWith(MockitoExtension.class)
class SkitMemberAppContextServiceImplTest {

    @InjectMocks
    private SkitMemberAppContextServiceImpl contextService;
    @Mock
    private SkitAgentMapper agentMapper;
    @Mock
    private TenantService tenantService;
    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;

    @Test
    void issueAndRequireTenantIdRoundTrip() {
        when(agentMapper.selectByTenantCode("AGENT42")).thenReturn(enabledAgent());
        when(agentMapper.selectByTenantId(42L)).thenReturn(enabledAgent());
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn("42");

        SkitMemberAppContextService.AppContext context = contextService.issue("agent42");

        assertEquals(42L, context.getTenantId());
        assertEquals(42L, contextService.requireTenantId(context.getToken()));
    }

    @Test
    void requireTenantIdRejectsUnknownToken() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn(null);

        assertServiceException(() -> contextService.requireTenantId("unknown"), MEMBER_APP_CONTEXT_INVALID);
    }

    @Test
    void issueUsesCanonicalTenantAndIgnoresLegacyAgentStatus() {
        SkitAgentDO agent = enabledAgent().setStatus(CommonStatusEnum.DISABLE.getStatus());
        when(agentMapper.selectByTenantCode("AGENT42")).thenReturn(agent);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);

        SkitMemberAppContextService.AppContext result = contextService.issue("agent42");

        assertEquals(42L, result.getTenantId());
        verify(tenantService).validTenant(42L);
        verify(valueOperations).set(anyString(), eq("42"), eq(30L), any());
    }

    @Test
    void issueRejectsDisabledExpiredAndDeletedTenantWithoutWritingContext() {
        when(agentMapper.selectByTenantCode("AGENT42")).thenReturn(enabledAgent());
        doThrow(exception(TENANT_DISABLE, "agent"),
                exception(TENANT_EXPIRE, "agent"),
                exception(TENANT_NOT_EXISTS)).when(tenantService).validTenant(42L);

        for (ErrorCode errorCode : Arrays.asList(TENANT_DISABLE, TENANT_EXPIRE, TENANT_NOT_EXISTS)) {
            if (errorCode == TENANT_NOT_EXISTS) {
                assertServiceException(() -> contextService.issue("agent42"), errorCode);
            } else {
                assertServiceException(() -> contextService.issue("agent42"), errorCode, "agent");
            }
        }

        verifyNoInteractions(stringRedisTemplate, valueOperations);
    }

    @Test
    void cachedContextRevalidatesDisabledExpiredAndDeletedTenant() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("skit:member:app-context:cached")).thenReturn("42");
        doThrow(exception(TENANT_DISABLE, "agent"),
                exception(TENANT_EXPIRE, "agent"),
                exception(TENANT_NOT_EXISTS)).when(tenantService).validTenant(42L);

        for (ErrorCode errorCode : Arrays.asList(TENANT_DISABLE, TENANT_EXPIRE, TENANT_NOT_EXISTS)) {
            if (errorCode == TENANT_NOT_EXISTS) {
                assertServiceException(() -> contextService.requireTenantId("cached"), errorCode);
            } else {
                assertServiceException(() -> contextService.requireTenantId("cached"), errorCode, "agent");
            }
        }

        verify(tenantService, times(3)).validTenant(42L);
    }

    private SkitAgentDO enabledAgent() {
        return SkitAgentDO.builder().tenantId(42L).tenantCode("AGENT42")
                .status(CommonStatusEnum.ENABLE.getStatus()).build();
    }
}
