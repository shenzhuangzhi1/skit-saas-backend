package cn.iocoder.yudao.module.skit.controller.app.member.vo.point;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.springframework.format.annotation.DateTimeFormat;

import javax.validation.constraints.Size;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
public class SkitMemberPointRecordPageReqVO extends PageParam {

    private Boolean addStatus;

    @Size(min = 2, max = 2, message = "积分记录时间范围必须包含开始和结束时间")
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime[] createTime;

}
