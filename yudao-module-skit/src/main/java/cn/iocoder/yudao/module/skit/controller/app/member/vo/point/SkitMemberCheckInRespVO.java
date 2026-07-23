package cn.iocoder.yudao.module.skit.controller.app.member.vo.point;

import cn.iocoder.yudao.module.skit.service.member.SkitMemberPointService;
import lombok.Data;

import java.time.LocalDate;

@Data
public class SkitMemberCheckInRespVO {

    private LocalDate signInDate;
    private Integer awardedPoints;
    private Integer pointBalance;
    private Integer continuousDay;
    private Integer totalDay;

    public static SkitMemberCheckInRespVO from(
            SkitMemberPointService.CheckInResult source) {
        SkitMemberCheckInRespVO result = new SkitMemberCheckInRespVO();
        result.setSignInDate(source.getSignInDate());
        result.setAwardedPoints(source.getAwardedPoints());
        result.setPointBalance(source.getPointBalance());
        result.setContinuousDay(source.getContinuousDay());
        result.setTotalDay(source.getTotalDay());
        return result;
    }

}
