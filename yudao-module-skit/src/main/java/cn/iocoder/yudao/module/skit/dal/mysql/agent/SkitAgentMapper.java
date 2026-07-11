package cn.iocoder.yudao.module.skit.dal.mysql.agent;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.module.skit.dal.dataobject.agent.SkitAgentDO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface SkitAgentMapper extends BaseMapperX<SkitAgentDO> {

    default SkitAgentDO selectByTenantId(Long tenantId) {
        return selectOne(SkitAgentDO::getTenantId, tenantId);
    }

    default SkitAgentDO selectByTenantCode(String tenantCode) {
        return selectOne(SkitAgentDO::getTenantCode, tenantCode);
    }

    default SkitAgentDO selectByRootInviteCode(String inviteCode) {
        return selectOne(SkitAgentDO::getRootInviteCode, inviteCode);
    }

}
