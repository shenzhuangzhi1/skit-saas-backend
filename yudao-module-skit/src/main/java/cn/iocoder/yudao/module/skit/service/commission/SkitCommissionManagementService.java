package cn.iocoder.yudao.module.skit.service.commission;

import cn.iocoder.yudao.module.skit.controller.admin.tenant.vo.SkitStablePageRespVO;
import cn.iocoder.yudao.module.skit.controller.admin.tenant.vo.commission.SkitCommissionPlanPageReqVO;
import cn.iocoder.yudao.module.skit.controller.admin.tenant.vo.commission.SkitCommissionPlanRespVO;
import cn.iocoder.yudao.module.skit.controller.admin.tenant.vo.commission.SkitCommissionPreviewReqVO;
import cn.iocoder.yudao.module.skit.controller.admin.tenant.vo.commission.SkitCommissionPreviewRespVO;
import cn.iocoder.yudao.module.skit.controller.admin.tenant.vo.commission.SkitCommissionPublishReqVO;
import cn.iocoder.yudao.module.skit.framework.security.SkitAdminTenantScope;

public interface SkitCommissionManagementService {

    SkitCommissionPlanRespVO getCurrent(long tenantId, String timezone);

    SkitStablePageRespVO<SkitCommissionPlanRespVO> getHistory(
            long tenantId, SkitCommissionPlanPageReqVO query);

    SkitCommissionPreviewRespVO preview(long tenantId, SkitCommissionPreviewReqVO request);

    SkitCommissionPlanRespVO publish(SkitAdminTenantScope scope,
                                     SkitCommissionPublishReqVO request);
}
