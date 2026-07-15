package cn.iocoder.yudao.module.skit.service.analytics;

import cn.iocoder.yudao.module.skit.controller.admin.tenant.vo.SkitAdEventDetailRespVO;
import cn.iocoder.yudao.module.skit.controller.admin.tenant.vo.SkitAdEventPageReqVO;
import cn.iocoder.yudao.module.skit.controller.admin.tenant.vo.SkitAdEventRespVO;
import cn.iocoder.yudao.module.skit.controller.admin.tenant.vo.SkitStablePageRespVO;

public interface SkitAdEventQueryService {

    SkitStablePageRespVO<SkitAdEventRespVO> getPage(long tenantId, SkitAdEventPageReqVO query);

    SkitStablePageRespVO<SkitAdEventRespVO> getGlobalPage(SkitAdEventPageReqVO query);

    SkitAdEventDetailRespVO get(long tenantId, long eventId, String timezone);

    SkitAdEventDetailRespVO getGlobal(long eventId, String timezone);

}
