package cn.iocoder.yudao.module.skit.service.member;

import cn.iocoder.yudao.module.skit.controller.admin.tenant.vo.member.SkitMemberAncestorsRespVO;
import cn.iocoder.yudao.module.skit.controller.admin.tenant.vo.member.SkitMemberChildrenRespVO;
import cn.iocoder.yudao.module.skit.controller.admin.tenant.vo.member.SkitMemberSubtreeSummaryReqVO;
import cn.iocoder.yudao.module.skit.controller.admin.tenant.vo.member.SkitMemberSubtreeSummaryRespVO;

public interface SkitMemberTreeQueryService {

    SkitMemberChildrenRespVO getChildren(long tenantId, long parentId, String cursor,
                                         int pageSize, String timezone);

    SkitMemberAncestorsRespVO getAncestors(long tenantId, long memberId, String timezone);

    SkitMemberSubtreeSummaryRespVO getSubtreeSummary(
            long tenantId, long memberId, SkitMemberSubtreeSummaryReqVO query);
}
