package cn.iocoder.yudao.module.skit.controller.app.member.vo.point;

import cn.iocoder.yudao.module.skit.dal.dataobject.member.SkitMemberPointRecordDO;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class SkitMemberPointRecordRespVO {

    private Long id;
    private String title;
    private String description;
    private Integer pointDelta;
    private Integer balanceAfter;
    private LocalDateTime createTime;

    public static SkitMemberPointRecordRespVO from(SkitMemberPointRecordDO source) {
        SkitMemberPointRecordRespVO result = new SkitMemberPointRecordRespVO();
        result.setId(source.getId());
        result.setTitle(source.getTitle());
        result.setDescription(source.getDescription());
        result.setPointDelta(source.getPointDelta());
        result.setBalanceAfter(source.getBalanceAfter());
        result.setCreateTime(source.getCreateTime());
        return result;
    }

}
