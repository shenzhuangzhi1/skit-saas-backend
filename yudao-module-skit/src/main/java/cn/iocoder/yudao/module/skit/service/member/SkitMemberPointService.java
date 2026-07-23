package cn.iocoder.yudao.module.skit.service.member;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.skit.controller.app.member.vo.point.SkitMemberPointRecordPageReqVO;
import cn.iocoder.yudao.module.skit.dal.dataobject.member.SkitMemberPointRecordDO;
import lombok.Data;

import java.time.LocalDate;

public interface SkitMemberPointService {

    CheckInResult checkIn(Long memberId);

    CheckInSummary getCheckInSummary(Long memberId);

    PageResult<SkitMemberPointRecordDO> getPointRecordPage(
            Long memberId, SkitMemberPointRecordPageReqVO request);

    @Data
    class CheckInResult {
        private LocalDate signInDate;
        private Integer awardedPoints;
        private Integer pointBalance;
        private Integer continuousDay;
        private Integer totalDay;
    }

    @Data
    class CheckInSummary {
        private Boolean todaySignIn;
        private Integer continuousDay;
        private Integer totalDay;
        private Integer pointBalance;
    }

}
