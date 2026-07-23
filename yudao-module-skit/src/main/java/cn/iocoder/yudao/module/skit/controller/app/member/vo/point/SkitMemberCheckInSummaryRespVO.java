package cn.iocoder.yudao.module.skit.controller.app.member.vo.point;

import cn.iocoder.yudao.module.skit.service.member.SkitMemberPointService;
import lombok.Data;

@Data
public class SkitMemberCheckInSummaryRespVO {

    private Boolean todaySignIn;
    private Integer continuousDay;
    private Integer totalDay;
    private Integer pointBalance;

    public static SkitMemberCheckInSummaryRespVO from(
            SkitMemberPointService.CheckInSummary source) {
        SkitMemberCheckInSummaryRespVO result = new SkitMemberCheckInSummaryRespVO();
        result.setTodaySignIn(source.getTodaySignIn());
        result.setContinuousDay(source.getContinuousDay());
        result.setTotalDay(source.getTotalDay());
        result.setPointBalance(source.getPointBalance());
        return result;
    }

}
