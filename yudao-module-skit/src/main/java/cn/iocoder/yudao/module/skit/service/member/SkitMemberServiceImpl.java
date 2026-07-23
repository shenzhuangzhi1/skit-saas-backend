package cn.iocoder.yudao.module.skit.service.member;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.biz.system.oauth2.OAuth2TokenCommonApi;
import cn.iocoder.yudao.framework.common.biz.system.oauth2.dto.OAuth2AccessTokenCheckRespDTO;
import cn.iocoder.yudao.framework.common.biz.system.oauth2.dto.OAuth2AccessTokenCreateReqDTO;
import cn.iocoder.yudao.framework.common.biz.system.oauth2.dto.OAuth2AccessTokenRespDTO;
import cn.iocoder.yudao.framework.common.enums.CommonStatusEnum;
import cn.iocoder.yudao.framework.common.enums.UserTypeEnum;
import cn.iocoder.yudao.framework.common.pojo.PageParam;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.framework.tenant.core.util.TenantUtils;
import cn.iocoder.yudao.module.skit.dal.dataobject.agent.SkitAgentDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.member.SkitMemberClosureDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.member.SkitMemberDO;
import cn.iocoder.yudao.module.skit.dal.mysql.agent.SkitAgentMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.member.SkitMemberClosureMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.member.SkitMemberMapper;
import cn.iocoder.yudao.module.skit.service.invite.SkitInviteCodeRegistryService;
import cn.iocoder.yudao.module.skit.service.invite.SkitInviteCodeRegistryService.OwnerType;
import cn.iocoder.yudao.module.skit.service.invite.SkitInviteCodeRegistryService.ResolvedOwner;
import cn.iocoder.yudao.module.system.dal.dataobject.tenant.TenantDO;
import cn.iocoder.yudao.module.system.enums.oauth2.OAuth2ClientConstants;
import cn.iocoder.yudao.module.system.service.oauth2.OAuth2TokenService;
import cn.iocoder.yudao.module.system.service.tenant.TenantService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.skit.enums.ErrorCodeConstants.*;

@Service
public class SkitMemberServiceImpl implements SkitMemberService {

    private static final char[] INVITE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();
    private static final String SKIT_MEMBER_SCOPE = "skit_member";
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    @Resource
    private SkitMemberMapper memberMapper;
    @Resource
    private SkitMemberClosureMapper closureMapper;
    @Resource
    private SkitAgentMapper agentMapper;
    @Resource
    private SkitInviteCodeRegistryService inviteCodeRegistryService;
    @Resource
    private TenantService tenantService;
    @Resource
    private PasswordEncoder passwordEncoder;
    @Resource
    private OAuth2TokenCommonApi oauth2TokenApi;
    @Resource
    private OAuth2TokenService oauth2TokenService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AuthResult register(RegisterCommand command) {
        String inviteCode = normalizeCode(command.getInviteCode());
        ResolvedOwner discovered = requireActiveOwner(inviteCode);
        if (!Objects.equals(discovered.getTenantId(), command.getTenantId())) {
            throw exception(MEMBER_APP_CONTEXT_INVALID);
        }
        return inTenant(discovered.getTenantId(), () -> {
            tenantService.validTenant(discovered.getTenantId());
            SkitMemberDO inviter = lockAndValidateInviter(discovered, inviteCode);
            ResolvedOwner locked = inviteCodeRegistryService.lockActive(discovered.getOwnerType(),
                    discovered.getTenantId(), discovered.getOwnerId(), inviteCode);
            validateLockedOwner(discovered, locked, inviteCode);
            validateOwningAgent(discovered, inviter);
            if (memberMapper.selectByMobile(command.getMobile()) != null) {
                throw exception(MEMBER_MOBILE_EXISTS);
            }
            String memberInviteCode = generateUniqueInviteCode();
            SkitMemberDO member = SkitMemberDO.builder()
                    .mobile(command.getMobile()).password(passwordEncoder.encode(command.getPassword()))
                    .nickname(StrUtil.blankToDefault(command.getNickname(), command.getMobile()))
                    .inviterId(inviter == null ? null : inviter.getId()).inviteCode(memberInviteCode)
                    .depth(inviter == null ? 0 : inviter.getDepth() + 1)
                    .status(CommonStatusEnum.ENABLE.getStatus()).registerIp(command.getRegisterIp()).build();
            memberMapper.insert(member);
            if (member.getId() == null) {
                throw new IllegalStateException("Member insert did not return an id");
            }
            inviteCodeRegistryService.claimMember(discovered.getTenantId(), member.getId(), memberInviteCode);
            buildClosure(member.getId(), inviter == null ? null : inviter.getId());
            return createToken(member, discovered.getTenantId());
        });
    }

    @Override
    public AuthResult login(LoginCommand command) {
        Long tenantId = command.getTenantId();
        if (tenantId == null) {
            throw exception(MEMBER_APP_CONTEXT_INVALID);
        }
        validateMemberTenant(tenantId);
        return inTenant(tenantId, () -> {
            SkitMemberDO member = memberMapper.selectByMobile(command.getMobile());
            if (member == null || !passwordEncoder.matches(command.getPassword(), member.getPassword())) {
                throw exception(MEMBER_LOGIN_FAILED);
            }
            if (CommonStatusEnum.isDisable(member.getStatus())) {
                throw exception(MEMBER_DISABLED);
            }
            memberMapper.updateById(new SkitMemberDO().setId(member.getId()).setLoginIp(command.getLoginIp())
                    .setLoginTime(LocalDateTime.now()));
            return createToken(member, tenantId);
        });
    }

    @Override
    public AuthResult refreshToken(String refreshToken) {
        OAuth2AccessTokenRespDTO token = oauth2TokenApi.refreshAccessToken(refreshToken,
                OAuth2ClientConstants.CLIENT_ID_DEFAULT);
        OAuth2AccessTokenCheckRespDTO checked = oauth2TokenApi.checkAccessToken(token.getAccessToken());
        if (checked == null || !Objects.equals(checked.getUserType(), UserTypeEnum.MEMBER.getValue())
                || checked.getScopes() == null || !checked.getScopes().contains(SKIT_MEMBER_SCOPE)
                || checked.getTenantId() == null) {
            oauth2TokenApi.removeAccessToken(token.getAccessToken());
            throw exception(MEMBER_TOKEN_SCOPE_INVALID);
        }
        try {
            validateMemberTenant(checked.getTenantId());
        } catch (RuntimeException ex) {
            oauth2TokenApi.removeAccessToken(token.getAccessToken());
            throw ex;
        }
        AuthResult result = fromToken(token, checked.getTenantId());
        SkitMemberDO member = inTenant(checked.getTenantId(), () -> memberMapper.selectById(token.getUserId()));
        if (member == null || CommonStatusEnum.isDisable(member.getStatus())) {
            oauth2TokenApi.removeAccessToken(token.getAccessToken());
            throw exception(member == null ? MEMBER_NOT_EXISTS : MEMBER_DISABLED);
        }
        result.setInviteCode(member.getInviteCode());
        return result;
    }

    @Override
    public void logout(String accessToken) {
        oauth2TokenApi.removeAccessToken(accessToken);
    }

    @Override
    public InvitationView resolveInvitation(String inviteCode) {
        InvitationTarget target = resolveTarget(normalizeCode(inviteCode));
        return inTenant(target.tenantId, () -> {
            TenantDO tenant = tenantService.getTenant(target.tenantId);
            SkitAgentDO agent = agentMapper.selectByTenantId(target.tenantId);
            validateTenantAgent(target.tenantId, agent);
            InvitationView result = new InvitationView();
            result.setValid(true);
            result.setType(target.inviterId == null ? "AGENT" : "MEMBER");
            result.setTenantId(target.tenantId);
            result.setTenantCode(agent.getTenantCode());
            result.setTenantName(tenant == null ? agent.getTenantCode() : tenant.getName());
            result.setInviterId(target.inviterId);
            if (target.inviterId != null) {
                SkitMemberDO inviter = memberMapper.selectById(target.inviterId);
                result.setInviterNickname(inviter == null ? null : inviter.getNickname());
            }
            return result;
        });
    }

    @Override
    public ProfileView getProfile(Long memberId) {
        SkitMemberDO member = requireMember(memberId);
        Long tenantId = TenantContextHolder.getRequiredTenantId();
        SkitAgentDO agent = agentMapper.selectByTenantId(tenantId);
        TenantDO tenant = tenantService.getTenant(tenantId);
        ProfileView result = new ProfileView();
        result.setId(member.getId());
        result.setUserId(member.getId());
        result.setUsername(member.getMobile());
        result.setMobile(member.getMobile());
        result.setNickname(member.getNickname());
        result.setInviteCode(member.getInviteCode());
        result.setInviterId(member.getInviterId());
        result.setParentId(member.getInviterId());
        result.setParentUserId(member.getInviterId());
        if (member.getInviterId() != null) {
            SkitMemberDO parent = memberMapper.selectById(member.getInviterId());
            result.setParentName(parent == null ? null : parent.getNickname());
            result.setParentNickname(parent == null ? null : parent.getNickname());
        }
        result.setLevel(member.getDepth());
        result.setDepth(member.getDepth());
        result.setStatus(member.getStatus());
        result.setPointBalance(member.getPointBalance() == null ? 0 : member.getPointBalance());
        result.setTenantId(tenantId);
        result.setTenantCode(agent == null ? null : agent.getTenantCode());
        result.setTenantName(tenant == null ? null : tenant.getName());
        result.setAgentName(tenant == null ? null : tenant.getName());
        result.setDirectChildren(memberMapper.selectCountByInviterId(memberId));
        return result;
    }

    @Override
    public PageResult<MemberView> getChildren(Long memberId, PageParam pageParam) {
        requireMember(memberId);
        return convertPage(memberMapper.selectChildrenPage(pageParam, memberId));
    }

    @Override
    public PageResult<MemberView> getMemberPage(PageParam pageParam, String keyword, Integer status) {
        return convertPage(memberMapper.selectPage(pageParam, keyword, status), false);
    }

    @Override
    public PageResult<MemberView> getGlobalMemberPage(PageParam pageParam, String keyword, Integer status) {
        return TenantUtils.executeIgnore(() ->
                convertPage(memberMapper.selectPage(pageParam, keyword, status), true));
    }

    @Override
    public MemberView getMember(Long memberId) {
        return toMemberView(requireMember(memberId));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateMemberStatus(Long memberId, Integer status) {
        if (status == null || (!CommonStatusEnum.isEnable(status) && !CommonStatusEnum.isDisable(status))) {
            throw exception(MEMBER_STATUS_INVALID);
        }
        SkitMemberDO member = requireMember(memberId);
        memberMapper.updateById(new SkitMemberDO().setId(member.getId()).setStatus(status));
        if (CommonStatusEnum.isDisable(status)) {
            revokeSkitMemberTokens(member.getId());
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void resetMemberPassword(Long memberId, String password) {
        if (StrUtil.isBlank(password) || password.length() < 6 || password.length() > 32) {
            throw exception(MEMBER_PASSWORD_INVALID);
        }
        SkitMemberDO member = requireMember(memberId);
        memberMapper.updateById(new SkitMemberDO().setId(member.getId())
                .setPassword(passwordEncoder.encode(password)));
        revokeSkitMemberTokens(member.getId());
    }

    private SkitMemberDO requireMember(Long memberId) {
        SkitMemberDO member = memberMapper.selectById(memberId);
        if (member == null) {
            throw exception(MEMBER_NOT_EXISTS);
        }
        return member;
    }

    private PageResult<MemberView> convertPage(PageResult<SkitMemberDO> page) {
        return convertPage(page, false);
    }

    private PageResult<MemberView> convertPage(PageResult<SkitMemberDO> page, boolean explicitTenant) {
        List<MemberView> list = new ArrayList<>();
        for (SkitMemberDO member : page.getList()) {
            list.add(toMemberView(member, explicitTenant));
        }
        return new PageResult<>(list, page.getTotal());
    }

    private MemberView toMemberView(SkitMemberDO member) {
        return toMemberView(member, false);
    }

    private MemberView toMemberView(SkitMemberDO member, boolean explicitTenant) {
        MemberView item = new MemberView();
        item.setId(member.getId());
        item.setUserId(member.getId());
        item.setUsername(member.getMobile());
        item.setMobile(member.getMobile());
        item.setNickname(member.getNickname());
        item.setInviteCode(member.getInviteCode());
        item.setInviterId(member.getInviterId());
        item.setParentId(member.getInviterId());
        item.setParentUserId(member.getInviterId());
        if (member.getInviterId() != null) {
            SkitMemberDO parent = explicitTenant
                    ? memberMapper.selectByTenantAndId(member.getTenantId(), member.getInviterId())
                    : memberMapper.selectById(member.getInviterId());
            item.setParentName(parent == null ? null : parent.getNickname());
            item.setParentNickname(parent == null ? null : parent.getNickname());
        }
        item.setLevel(member.getDepth());
        item.setDepth(member.getDepth());
        item.setStatus(member.getStatus());
        item.setChildCount(explicitTenant
                ? memberMapper.selectCountByTenantAndInviterId(member.getTenantId(), member.getId())
                : memberMapper.selectCountByInviterId(member.getId()));
        item.setCreateTime(member.getCreateTime());
        item.setLoginTime(member.getLoginTime());
        Long tenantId = member.getTenantId();
        item.setTenantId(tenantId);
        if (tenantId != null) {
            TenantDO tenant = tenantService.getTenant(tenantId);
            SkitAgentDO agent = agentMapper.selectByTenantId(tenantId);
            item.setTenantCode(agent == null ? null : agent.getTenantCode());
            item.setTenantName(tenant == null ? null : tenant.getName());
            item.setAgentName(tenant == null ? null : tenant.getName());
        }
        return item;
    }

    private void revokeSkitMemberTokens(Long memberId) {
        oauth2TokenService.removeAccessToken(memberId, UserTypeEnum.MEMBER.getValue(),
                OAuth2ClientConstants.CLIENT_ID_DEFAULT, SKIT_MEMBER_SCOPE);
    }

    private void buildClosure(Long memberId, Long inviterId) {
        closureMapper.insert(SkitMemberClosureDO.builder().ancestorId(memberId).descendantId(memberId)
                .distance(0).build());
        if (inviterId == null) {
            return;
        }
        for (SkitMemberClosureDO ancestor : closureMapper.selectAncestors(inviterId)) {
            closureMapper.insert(SkitMemberClosureDO.builder().ancestorId(ancestor.getAncestorId())
                    .descendantId(memberId).distance(ancestor.getDistance() + 1).build());
        }
    }

    private AuthResult createToken(SkitMemberDO member, Long tenantId) {
        validateMemberTenant(tenantId);
        OAuth2AccessTokenRespDTO token = oauth2TokenApi.createAccessToken(new OAuth2AccessTokenCreateReqDTO()
                .setUserId(member.getId()).setUserType(UserTypeEnum.MEMBER.getValue())
                .setClientId(OAuth2ClientConstants.CLIENT_ID_DEFAULT)
                .setScopes(Collections.singletonList(SKIT_MEMBER_SCOPE)));
        AuthResult result = fromToken(token, tenantId);
        result.setInviteCode(member.getInviteCode());
        return result;
    }

    private void validateMemberTenant(Long tenantId) {
        tenantService.validTenant(tenantId);
        SkitAgentDO agent = agentMapper.selectByTenantId(tenantId);
        if (agent == null) {
            throw exception(MEMBER_DISABLED);
        }
    }

    private AuthResult fromToken(OAuth2AccessTokenRespDTO token, Long tenantId) {
        AuthResult result = new AuthResult();
        result.setAccessToken(token.getAccessToken());
        result.setRefreshToken(token.getRefreshToken());
        result.setExpiresTime(token.getExpiresTime());
        result.setUserId(token.getUserId());
        result.setTenantId(tenantId);
        return result;
    }

    private ResolvedOwner requireActiveOwner(String inviteCode) {
        ResolvedOwner owner = inviteCodeRegistryService.resolveActive(inviteCode);
        if (!isActiveOwner(owner, inviteCode)) {
            throw exception(INVITE_CODE_INVALID);
        }
        return owner;
    }

    private SkitMemberDO lockAndValidateInviter(ResolvedOwner owner, String inviteCode) {
        if (owner.getOwnerType() == OwnerType.AGENT) {
            SkitAgentDO agent = agentMapper.selectByTenantIdForUpdate(owner.getTenantId());
            validateAgentOwner(owner, agent, inviteCode);
            return null;
        }
        if (owner.getOwnerType() == OwnerType.MEMBER) {
            SkitMemberDO member = memberMapper.selectByTenantAndIdForUpdate(owner.getTenantId(), owner.getOwnerId());
            validateMemberOwner(owner, member, inviteCode);
            return member;
        }
        throw exception(INVITE_CODE_INVALID);
    }

    private void validateOwningAgent(ResolvedOwner owner, SkitMemberDO inviter) {
        if (owner.getOwnerType() == OwnerType.AGENT) {
            return; // The agent row was already locked and validated first.
        }
        if (inviter == null) {
            throw exception(INVITE_CODE_INVALID);
        }
        validateTenantAgent(owner.getTenantId(), agentMapper.selectByTenantId(owner.getTenantId()));
    }

    private void validateAgentOwner(ResolvedOwner owner, SkitAgentDO agent, String inviteCode) {
        if (owner.getOwnerType() != OwnerType.AGENT || agent == null
                || !Objects.equals(owner.getTenantId(), agent.getTenantId())
                || !Objects.equals(owner.getOwnerId(), agent.getId())
                || agent.getArchivedTime() != null || !sameCode(agent.getRootInviteCode(), inviteCode)) {
            throw exception(INVITE_CODE_INVALID);
        }
    }

    private void validateMemberOwner(ResolvedOwner owner, SkitMemberDO member, String inviteCode) {
        if (owner.getOwnerType() != OwnerType.MEMBER || member == null
                || !Objects.equals(owner.getTenantId(), member.getTenantId())
                || !Objects.equals(owner.getOwnerId(), member.getId())
                || !CommonStatusEnum.isEnable(member.getStatus())
                || !sameCode(member.getInviteCode(), inviteCode)) {
            throw exception(INVITE_CODE_INVALID);
        }
    }

    private void validateTenantAgent(Long tenantId, SkitAgentDO agent) {
        if (agent == null || !Objects.equals(tenantId, agent.getTenantId()) || agent.getArchivedTime() != null) {
            throw exception(INVITE_CODE_INVALID);
        }
    }

    private void validateLockedOwner(ResolvedOwner discovered, ResolvedOwner locked, String inviteCode) {
        if (!isActiveOwner(locked, inviteCode)
                || !Objects.equals(discovered.getId(), locked.getId())
                || !Objects.equals(discovered.getTenantId(), locked.getTenantId())
                || discovered.getOwnerType() != locked.getOwnerType()
                || !Objects.equals(discovered.getOwnerId(), locked.getOwnerId())) {
            throw exception(INVITE_CODE_INVALID);
        }
    }

    private boolean isActiveOwner(ResolvedOwner owner, String inviteCode) {
        return owner != null && owner.getId() != null && owner.getId() > 0
                && owner.getTenantId() != null && owner.getTenantId() > 0
                && owner.getOwnerType() != null && owner.getOwnerId() != null && owner.getOwnerId() > 0
                && ((owner.getOwnerType() == OwnerType.AGENT && owner.getAgentId() != null
                        && owner.getMemberId() == null)
                    || (owner.getOwnerType() == OwnerType.MEMBER && owner.getMemberId() != null
                        && owner.getAgentId() == null))
                && "ACTIVE".equals(owner.getStatus()) && owner.getRotatedAt() == null
                && sameCode(owner.getNormalizedCode() == null ? owner.getCode() : owner.getNormalizedCode(), inviteCode);
    }

    private boolean sameCode(String left, String right) {
        if (StrUtil.isBlank(left) || StrUtil.isBlank(right)) {
            return false;
        }
        return left.trim().toUpperCase(Locale.ROOT).equals(right.trim().toUpperCase(Locale.ROOT));
    }

    private InvitationTarget resolveTarget(String inviteCode) {
        ResolvedOwner owner = requireActiveOwner(inviteCode);
        return inTenant(owner.getTenantId(), () -> {
            Long inviterId;
            if (owner.getOwnerType() == OwnerType.AGENT) {
                SkitAgentDO agent = agentMapper.selectByTenantId(owner.getTenantId());
                validateAgentOwner(owner, agent, inviteCode);
                inviterId = null;
            } else if (owner.getOwnerType() == OwnerType.MEMBER) {
                SkitMemberDO member = memberMapper.selectById(owner.getOwnerId());
                validateMemberOwner(owner, member, inviteCode);
                validateTenantAgent(owner.getTenantId(), agentMapper.selectByTenantId(owner.getTenantId()));
                inviterId = member.getId();
            } else {
                throw exception(INVITE_CODE_INVALID);
            }
            tenantService.validTenant(owner.getTenantId());
            return new InvitationTarget(owner.getTenantId(), inviterId);
        });
    }

    private String generateUniqueInviteCode() {
        for (int attempt = 0; attempt < 20; attempt++) {
            StringBuilder builder = new StringBuilder(8);
            for (int index = 0; index < 8; index++) {
                builder.append(INVITE_ALPHABET[SECURE_RANDOM.nextInt(INVITE_ALPHABET.length)]);
            }
            String code = builder.toString();
            if (!inviteCodeRegistryService.isClaimed(code)) {
                return code;
            }
        }
        throw exception(REVENUE_EVENT_INVALID, "无法生成唯一邀请码");
    }

    private String normalizeCode(String value) {
        if (StrUtil.isBlank(value)) {
            throw exception(INVITE_CODE_INVALID);
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private <T> T inTenant(Long tenantId, Supplier<T> supplier) {
        AtomicReference<T> result = new AtomicReference<>();
        TenantUtils.execute(tenantId, () -> result.set(supplier.get()));
        return result.get();
    }

    private static final class InvitationTarget {
        private final Long tenantId;
        private final Long inviterId;

        private InvitationTarget(Long tenantId, Long inviterId) {
            this.tenantId = tenantId;
            this.inviterId = inviterId;
        }
    }
}
