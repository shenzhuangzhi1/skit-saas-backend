package cn.iocoder.yudao.module.skit.service.management;

import cn.iocoder.yudao.module.skit.controller.admin.tenant.vo.SkitStablePageRespVO;
import cn.iocoder.yudao.module.skit.controller.admin.tenant.vo.ledger.SkitCommissionLedgerPageReqVO;
import cn.iocoder.yudao.module.skit.controller.admin.tenant.vo.ledger.SkitCommissionLedgerRespVO;

public interface SkitCommissionLedgerQueryService {

    SkitStablePageRespVO<SkitCommissionLedgerRespVO> getPage(
            long tenantId, SkitCommissionLedgerPageReqVO query);
}
