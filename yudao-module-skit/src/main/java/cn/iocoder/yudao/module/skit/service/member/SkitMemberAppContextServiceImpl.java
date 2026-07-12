package cn.iocoder.yudao.module.skit.service.member;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.enums.CommonStatusEnum;
import cn.iocoder.yudao.module.skit.dal.dataobject.agent.SkitAgentDO;
import cn.iocoder.yudao.module.skit.dal.mysql.agent.SkitAgentMapper;
import cn.iocoder.yudao.module.system.service.tenant.TenantService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.skit.enums.ErrorCodeConstants.MEMBER_APP_CONTEXT_INVALID;

@Service
public class SkitMemberAppContextServiceImpl implements SkitMemberAppContextService {

    private static final String CACHE_KEY_PREFIX = "skit:member:app-context:";
    private static final long EXPIRE_MINUTES = 30L;

    @Resource
    private SkitAgentMapper agentMapper;
    @Resource
    private TenantService tenantService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public AppContext issue(String agentCode) {
        if (StrUtil.isBlank(agentCode)) {
            throw exception(MEMBER_APP_CONTEXT_INVALID);
        }
        String normalizedCode = StrUtil.trim(agentCode).toUpperCase(Locale.ROOT);
        SkitAgentDO agent = agentMapper.selectByTenantCode(normalizedCode);
        if (agent == null || CommonStatusEnum.isDisable(agent.getStatus())) {
            throw exception(MEMBER_APP_CONTEXT_INVALID);
        }
        tenantService.validTenant(agent.getTenantId());
        String token = IdUtil.fastSimpleUUID();
        stringRedisTemplate.opsForValue().set(CACHE_KEY_PREFIX + token, agent.getTenantId().toString(),
                EXPIRE_MINUTES, TimeUnit.MINUTES);
        return new AppContext(token, agent.getTenantId(), LocalDateTime.now().plusMinutes(EXPIRE_MINUTES));
    }

    @Override
    public Long requireTenantId(String token) {
        String tenantId = stringRedisTemplate.opsForValue().get(CACHE_KEY_PREFIX + token);
        if (StrUtil.isBlank(tenantId)) {
            throw exception(MEMBER_APP_CONTEXT_INVALID);
        }
        return Long.valueOf(tenantId);
    }
}
