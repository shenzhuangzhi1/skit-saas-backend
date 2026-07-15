package cn.iocoder.yudao.module.skit.service.analytics;

import cn.iocoder.yudao.module.skit.controller.admin.tenant.vo.SkitAdAnalyticsOverviewRespVO;
import cn.iocoder.yudao.module.skit.controller.admin.tenant.vo.SkitAdAnalyticsQueryReqVO;
import cn.iocoder.yudao.module.skit.controller.admin.tenant.vo.SkitAdAnalyticsTimeseriesReqVO;
import cn.iocoder.yudao.module.skit.controller.admin.tenant.vo.SkitAdAnalyticsTimeseriesRespVO;

public interface SkitAdAnalyticsService {

    SkitAdAnalyticsOverviewRespVO getOverview(long tenantId, SkitAdAnalyticsQueryReqVO query);

    SkitAdAnalyticsOverviewRespVO getGlobalOverview(SkitAdAnalyticsQueryReqVO query);

    SkitAdAnalyticsTimeseriesRespVO getTimeseries(long tenantId,
                                                  SkitAdAnalyticsTimeseriesReqVO query);

    SkitAdAnalyticsTimeseriesRespVO getGlobalTimeseries(SkitAdAnalyticsTimeseriesReqVO query);

}
