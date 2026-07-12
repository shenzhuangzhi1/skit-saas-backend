package cn.iocoder.yudao.module.skit.service.member;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import lombok.Data;

import java.time.LocalDateTime;

public interface SkitMemberService {

    AuthResult register(RegisterCommand command);

    AuthResult login(LoginCommand command);

    AuthResult refreshToken(String refreshToken);

    void logout(String accessToken);

    InvitationView resolveInvitation(String inviteCode);

    ProfileView getProfile(Long memberId);

    PageResult<MemberView> getChildren(Long memberId, PageParam pageParam);

    PageResult<MemberView> getMemberPage(PageParam pageParam, String keyword, Integer status);

    @Data
    class RegisterCommand {
        private Long tenantId;
        private String mobile;
        private String password;
        private String nickname;
        private String inviteCode;
        private String registerIp;
    }

    @Data
    class LoginCommand {
        private Long tenantId;
        private String mobile;
        private String password;
        private String loginIp;
    }

    @Data
    class AuthResult {
        private String accessToken;
        private String refreshToken;
        private LocalDateTime expiresTime;
        private Long userId;
        private Long tenantId;
        private String inviteCode;
    }

    @Data
    class InvitationView {
        private Boolean valid;
        private String type;
        private Long tenantId;
        private String tenantCode;
        private String tenantName;
        private Long inviterId;
        private String inviterNickname;
    }

    @Data
    class ProfileView {
        private Long id;
        private Long userId;
        private String username;
        private String mobile;
        private String nickname;
        private String inviteCode;
        private Long inviterId;
        private Long parentId;
        private Long parentUserId;
        private String parentName;
        private String parentNickname;
        private Integer level;
        private Integer depth;
        private Integer status;
        private Long tenantId;
        private String tenantCode;
        private String tenantName;
        private String agentName;
        private Long directChildren;
    }

    @Data
    class MemberView {
        private Long id;
        private Long userId;
        private String username;
        private String mobile;
        private String nickname;
        private String inviteCode;
        private Long inviterId;
        private Long parentId;
        private Long parentUserId;
        private String parentName;
        private String parentNickname;
        private Integer level;
        private Integer depth;
        private Integer status;
        private Long childCount;
        private LocalDateTime createTime;
        private LocalDateTime loginTime;
    }
}
