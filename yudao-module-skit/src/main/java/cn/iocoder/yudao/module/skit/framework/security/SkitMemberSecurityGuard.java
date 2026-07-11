package cn.iocoder.yudao.module.skit.framework.security;

import cn.iocoder.yudao.framework.common.enums.UserTypeEnum;
import cn.iocoder.yudao.framework.common.enums.CommonStatusEnum;
import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.skit.dal.dataobject.member.SkitMemberDO;
import cn.iocoder.yudao.module.skit.dal.mysql.member.SkitMemberMapper;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.Objects;

/**
 * 短剧会员认证域硬校验，不受 visit-tenant 跳过通用权限校验逻辑影响。
 */
@Component("skitMemberSecurityGuard")
public class SkitMemberSecurityGuard {

    @Resource
    private SkitMemberMapper memberMapper;

    public boolean isSkitMember() {
        LoginUser loginUser = SecurityFrameworkUtils.getLoginUser();
        if (loginUser == null
                || !Objects.equals(loginUser.getUserType(), UserTypeEnum.MEMBER.getValue())
                || loginUser.getScopes() == null
                || !loginUser.getScopes().contains("skit_member")) {
            return false;
        }
        SkitMemberDO member = memberMapper.selectById(loginUser.getId());
        return member != null && CommonStatusEnum.isEnable(member.getStatus())
                && Objects.equals(member.getTenantId(), loginUser.getTenantId());
    }
}
