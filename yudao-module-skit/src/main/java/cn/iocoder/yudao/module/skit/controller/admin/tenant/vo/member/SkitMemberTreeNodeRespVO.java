package cn.iocoder.yudao.module.skit.controller.admin.tenant.vo.member;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class SkitMemberTreeNodeRespVO {

    private Long id;
    private Long parentId;
    private String nickname;
    private String inviteCode;
    private Integer depth;
    private String status;
    private Long directChildCount;
    private Integer distance;
    private LocalDateTime createdAt;
}
