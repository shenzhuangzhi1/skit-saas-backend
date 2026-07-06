package cn.iocoder.yudao.module.skit.service.record;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.skit.controller.admin.record.vo.SkitAdminRecordPageReqVO;
import cn.iocoder.yudao.module.skit.controller.admin.record.vo.SkitAdminRecordRespVO;
import cn.iocoder.yudao.module.skit.controller.admin.record.vo.SkitAdminRecordSaveReqVO;
import cn.iocoder.yudao.module.skit.controller.admin.record.vo.SkitDashboardSummaryRespVO;

import javax.validation.Valid;
import java.util.List;

public interface SkitAdminRecordService {

    Long createRecord(@Valid SkitAdminRecordSaveReqVO createReqVO);

    void updateRecord(@Valid SkitAdminRecordSaveReqVO updateReqVO);

    void deleteRecord(Long id);

    void deleteRecordList(List<Long> ids);

    SkitAdminRecordRespVO getRecord(Long id);

    PageResult<SkitAdminRecordRespVO> getRecordPage(SkitAdminRecordPageReqVO pageReqVO);

    Integer seedPage(String pageKey);

    SkitDashboardSummaryRespVO getDashboardSummary();

}
