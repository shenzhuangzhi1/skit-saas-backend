package cn.iocoder.yudao.module.skit.service.agent;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.skit.controller.admin.tenant.vo.SkitAgentPageReqVO;
import cn.iocoder.yudao.module.skit.controller.admin.tenant.vo.SkitAgentRespVO;
import cn.iocoder.yudao.module.skit.controller.admin.tenant.vo.SkitAgentSaveReqVO;

import javax.validation.Valid;

/**
 * 代理商管理 Service。代理商本体复用 system_tenant，skit_agent 仅保存短剧域的全局标识。
 */
public interface SkitAgentService {

    PageResult<SkitAgentRespVO> getAgentPage(@Valid SkitAgentPageReqVO pageReqVO);

    SkitAgentRespVO getAgent(Long tenantId);

    Long createAgent(@Valid SkitAgentSaveReqVO createReqVO);

    void updateAgent(@Valid SkitAgentSaveReqVO updateReqVO);

}
