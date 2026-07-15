package cn.iocoder.yudao.module.skit.service.reconciliation;

import cn.iocoder.yudao.module.skit.controller.admin.tenant.vo.SkitReconciliationDetailRespVO;
import cn.iocoder.yudao.module.skit.controller.admin.tenant.vo.SkitReconciliationPageReqVO;
import cn.iocoder.yudao.module.skit.controller.admin.tenant.vo.SkitReconciliationRespVO;
import cn.iocoder.yudao.module.skit.controller.admin.tenant.vo.SkitStablePageRespVO;

public interface SkitReconciliationQueryService {

    SkitStablePageRespVO<SkitReconciliationRespVO> getPage(
            long tenantId, SkitReconciliationPageReqVO query);

    SkitStablePageRespVO<SkitReconciliationRespVO> getGlobalPage(
            SkitReconciliationPageReqVO query);

    SkitReconciliationDetailRespVO get(long tenantId, long bucketId, String timezone);

    SkitReconciliationDetailRespVO getGlobal(long bucketId, String timezone);

}
