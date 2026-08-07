package cn.iocoder.yudao.module.skit.service.analytics;

import cn.iocoder.yudao.module.skit.controller.admin.tenant.vo.SkitAdConsumptionDetailRespVO;
import cn.iocoder.yudao.module.skit.controller.admin.tenant.vo.SkitAdConsumptionMemberRespVO;
import cn.iocoder.yudao.module.skit.controller.admin.tenant.vo.SkitAdConsumptionMemberSummaryRespVO;
import cn.iocoder.yudao.module.skit.controller.admin.tenant.vo.SkitAdConsumptionPageReqVO;
import cn.iocoder.yudao.module.skit.controller.admin.tenant.vo.SkitAdConsumptionRespVO;
import cn.iocoder.yudao.module.skit.controller.admin.tenant.vo.SkitAdConsumptionSummaryRespVO;
import cn.iocoder.yudao.module.skit.controller.admin.tenant.vo.SkitStablePageRespVO;

public interface SkitAdConsumptionQueryService {

    SkitStablePageRespVO<SkitAdConsumptionRespVO> getPage(
            long tenantId, SkitAdConsumptionPageReqVO query);

    SkitStablePageRespVO<SkitAdConsumptionRespVO> getGlobalPage(
            SkitAdConsumptionPageReqVO query);

    SkitAdConsumptionSummaryRespVO getSummary(
            long tenantId, SkitAdConsumptionPageReqVO query);

    SkitAdConsumptionSummaryRespVO getGlobalSummary(
            SkitAdConsumptionPageReqVO query);

    SkitAdConsumptionDetailRespVO get(long tenantId, long sessionRecordId, String timezone);

    SkitAdConsumptionDetailRespVO getGlobal(long sessionRecordId, String timezone);

    /**
     * Per-member aggregate of settled (reconciled + rewarded) ad consumption,
     * one row per (member, currency, amountScale).
     */
    SkitStablePageRespVO<SkitAdConsumptionMemberRespVO> getMemberPage(
            long tenantId, SkitAdConsumptionPageReqVO query);

    SkitStablePageRespVO<SkitAdConsumptionMemberRespVO> getGlobalMemberPage(
            SkitAdConsumptionPageReqVO query);

    SkitAdConsumptionMemberSummaryRespVO getMemberSummary(
            long tenantId, SkitAdConsumptionPageReqVO query);

    SkitAdConsumptionMemberSummaryRespVO getGlobalMemberSummary(
            SkitAdConsumptionPageReqVO query);

}
