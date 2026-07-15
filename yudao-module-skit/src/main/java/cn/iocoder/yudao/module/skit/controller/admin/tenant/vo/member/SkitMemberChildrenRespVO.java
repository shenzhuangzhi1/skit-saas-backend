package cn.iocoder.yudao.module.skit.controller.admin.tenant.vo.member;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
public class SkitMemberChildrenRespVO {

    private Long tenantId;
    private Long parentId;
    private LocalDateTime asOf;
    private String timezone;
    private Integer pageSize;
    private String nextCursor;
    private List<SkitMemberTreeNodeRespVO> list = new ArrayList<>();
}
