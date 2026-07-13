package cn.iocoder.yudao.module.skit.service.agent;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.skit.controller.admin.tenant.vo.SkitAgentPageReqVO;
import cn.iocoder.yudao.module.skit.controller.admin.tenant.vo.SkitAgentRespVO;
import cn.iocoder.yudao.module.skit.controller.admin.tenant.vo.SkitAgentCreateReqVO;
import cn.iocoder.yudao.module.skit.controller.admin.tenant.vo.SkitAgentMobileUpdateReqVO;
import cn.iocoder.yudao.module.skit.controller.admin.tenant.vo.SkitAgentPasswordResetReqVO;
import cn.iocoder.yudao.module.skit.controller.admin.tenant.vo.SkitAgentUpdateReqVO;

import javax.validation.Valid;

/**
 * 代理商管理 Service。代理商本体复用 system_tenant，skit_agent 仅保存短剧域的全局标识。
 */
public interface SkitAgentService {

    PageResult<SkitAgentRespVO> getAgentPage(@Valid SkitAgentPageReqVO pageReqVO);

    SkitAgentRespVO getAgent(Long tenantId);

    Long createAgent(@Valid SkitAgentCreateReqVO createReqVO);

    void updateAgent(@Valid SkitAgentUpdateReqVO updateReqVO);

    void updateAgentMobile(@Valid SkitAgentMobileUpdateReqVO updateReqVO);

    void resetAgentPassword(@Valid SkitAgentPasswordResetReqVO resetReqVO);

    void archiveAgent(Long tenantId);

    void restoreAgent(Long tenantId);

    String rotateRootInviteCode(Long tenantId);

}
