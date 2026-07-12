package cn.iocoder.yudao.module.skit.service.member;

import cn.iocoder.yudao.framework.common.enums.CommonStatusEnum;
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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

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

    private SkitAgentDO enabledAgent() {
        return SkitAgentDO.builder().tenantId(42L).tenantCode("AGENT42")
                .status(CommonStatusEnum.ENABLE.getStatus()).build();
    }
}
