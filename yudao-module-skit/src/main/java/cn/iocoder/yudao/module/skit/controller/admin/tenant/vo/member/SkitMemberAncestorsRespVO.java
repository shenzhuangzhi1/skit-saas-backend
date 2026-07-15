package cn.iocoder.yudao.module.skit.controller.admin.tenant.vo.member;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
public class SkitMemberAncestorsRespVO {

    private Long tenantId;
    private Long memberId;
    private LocalDateTime asOf;
    private String timezone;
    /** Root first and the selected member last. */
    private List<SkitMemberTreeNodeRespVO> list = new ArrayList<>();
}
