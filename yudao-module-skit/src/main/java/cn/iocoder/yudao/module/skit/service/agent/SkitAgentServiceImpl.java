package cn.iocoder.yudao.module.skit.service.agent;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.tenant.core.util.TenantUtils;
import cn.iocoder.yudao.module.skit.controller.admin.tenant.vo.SkitAgentPageReqVO;
import cn.iocoder.yudao.module.skit.controller.admin.tenant.vo.SkitAgentRespVO;
import cn.iocoder.yudao.module.skit.controller.admin.tenant.vo.SkitAgentSaveReqVO;
import cn.iocoder.yudao.module.skit.dal.dataobject.agent.SkitAgentDO;
import cn.iocoder.yudao.module.skit.dal.mysql.agent.SkitAgentMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.member.SkitMemberMapper;
import cn.iocoder.yudao.module.skit.framework.security.SkitPlatformAdminGuard;
import cn.iocoder.yudao.module.skit.service.ad.SkitAdAccountService;
import cn.iocoder.yudao.module.skit.service.commission.SkitCommissionService;
import cn.iocoder.yudao.module.system.controller.admin.tenant.vo.tenant.TenantSaveReqVO;
import cn.iocoder.yudao.module.system.dal.dataobject.tenant.TenantDO;
import cn.iocoder.yudao.module.system.dal.dataobject.tenant.TenantPackageDO;
import cn.iocoder.yudao.module.system.dal.dataobject.user.AdminUserDO;
import cn.iocoder.yudao.module.system.service.tenant.TenantPackageService;
import cn.iocoder.yudao.module.system.service.tenant.TenantService;
import cn.iocoder.yudao.module.system.service.user.AdminUserService;
import com.baomidou.dynamic.datasource.annotation.DSTransactional;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Collectors;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.skit.enums.ErrorCodeConstants.AGENT_CODE_EXISTS;
import static cn.iocoder.yudao.module.skit.enums.ErrorCodeConstants.AGENT_NOT_EXISTS;

@Service
public class SkitAgentServiceImpl implements SkitAgentService {

    private static final String AUTO_TENANT_CODE_PREFIX = "AG";
    private static final String AGENT_INVITE_CODE_PREFIX = "A";

    @Resource
    private SkitPlatformAdminGuard platformAdminGuard;
    @Resource
    private SkitAgentMapper agentMapper;
    @Resource
    private SkitMemberMapper memberMapper;
    @Resource
    private TenantService tenantService;
    @Resource
    private TenantPackageService tenantPackageService;
    @Resource
    private AdminUserService adminUserService;
    @Resource
    private SkitAdAccountService adAccountService;
    @Resource
    private SkitCommissionService commissionService;

    @Override
    public PageResult<SkitAgentRespVO> getAgentPage(SkitAgentPageReqVO pageReqVO) {
        platformAdminGuard.check();
        String keyword = StrUtil.trim(pageReqVO.getKeyword());
        List<SkitAgentRespVO> all = agentMapper.selectList().stream()
                .map(this::toRespVO)
                .filter(Objects::nonNull)
                .filter(item -> matchesKeyword(item, keyword))
                .filter(item -> pageReqVO.getStatus() == null
                        || Objects.equals(pageReqVO.getStatus(), item.getStatus()))
                .sorted(Comparator.comparing(SkitAgentRespVO::getCreateTime,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .collect(Collectors.toList());
        long requestedOffset = (long) (pageReqVO.getPageNo() - 1) * pageReqVO.getPageSize();
        int fromIndex = (int) Math.min(requestedOffset, all.size());
        int toIndex = Math.min(fromIndex + pageReqVO.getPageSize(), all.size());
        return new PageResult<>(new ArrayList<>(all.subList(fromIndex, toIndex)), (long) all.size());
    }

    @Override
    public SkitAgentRespVO getAgent(Long tenantId) {
        platformAdminGuard.check();
        SkitAgentRespVO result = toRespVO(validateAgent(tenantId));
        if (result == null) {
            throw exception(AGENT_NOT_EXISTS);
        }
        return result;
    }

    @Override
    @DSTransactional
    public Long createAgent(SkitAgentSaveReqVO createReqVO) {
        platformAdminGuard.check();
        String requestedTenantCode = normalizeTenantCode(createReqVO.getTenantCode());
        validateTenantCodeDuplicate(requestedTenantCode, null);

        Long tenantId = tenantService.createTenant(toTenantSaveReqVO(createReqVO, null));
        String tenantCode = StrUtil.isNotBlank(requestedTenantCode)
                ? requestedTenantCode : AUTO_TENANT_CODE_PREFIX + tenantId;
        validateTenantCodeDuplicate(tenantCode, null);
        agentMapper.insert(SkitAgentDO.builder()
                .tenantId(tenantId)
                .tenantCode(tenantCode)
                .rootInviteCode(generateRootInviteCode())
                .status(createReqVO.getStatus())
                .remark(StrUtil.nullToEmpty(createReqVO.getRemark()))
                .build());
        TenantUtils.execute(tenantId, () -> {
            adAccountService.ensureDefaultAccounts();
            saveAdSettings(createReqVO, false);
            commissionService.ensureDefaultPlan();
        });
        return tenantId;
    }

    @Override
    @DSTransactional
    public void updateAgent(SkitAgentSaveReqVO updateReqVO) {
        platformAdminGuard.check();
        SkitAgentDO agent = validateAgent(updateReqVO.getTenantId());
        TenantDO tenant = tenantService.getTenant(updateReqVO.getTenantId());
        if (tenant == null) {
            throw exception(AGENT_NOT_EXISTS);
        }

        String tenantCode = normalizeTenantCode(updateReqVO.getTenantCode());
        if (StrUtil.isBlank(tenantCode)) {
            tenantCode = agent.getTenantCode();
        }
        validateTenantCodeDuplicate(tenantCode, agent.getTenantId());
        tenantService.updateTenant(toTenantSaveReqVO(updateReqVO, tenant));
        agentMapper.updateById(new SkitAgentDO()
                .setId(agent.getId())
                .setTenantCode(tenantCode)
                .setStatus(updateReqVO.getStatus())
                .setRemark(updateReqVO.getRemark() == null ? agent.getRemark() : updateReqVO.getRemark()));

        Long tenantId = agent.getTenantId();
        if (StrUtil.isNotBlank(updateReqVO.getPassword()) && tenant.getContactUserId() != null) {
            TenantUtils.execute(tenantId,
                    () -> adminUserService.updateUserPassword(tenant.getContactUserId(), updateReqVO.getPassword()));
        }
        TenantUtils.execute(tenantId, () -> {
            adAccountService.ensureDefaultAccounts();
            saveAdSettings(updateReqVO, true);
            commissionService.ensureDefaultPlan();
        });
    }

    private SkitAgentDO validateAgent(Long tenantId) {
        if (tenantId == null) {
            throw exception(AGENT_NOT_EXISTS);
        }
        SkitAgentDO agent = agentMapper.selectByTenantId(tenantId);
        if (agent == null) {
            throw exception(AGENT_NOT_EXISTS);
        }
        return agent;
    }

    private void validateTenantCodeDuplicate(String tenantCode, Long excludeTenantId) {
        if (StrUtil.isBlank(tenantCode)) {
            return;
        }
        SkitAgentDO existing = agentMapper.selectByTenantCode(tenantCode);
        if (existing != null && !Objects.equals(existing.getTenantId(), excludeTenantId)) {
            throw exception(AGENT_CODE_EXISTS);
        }
    }

    private TenantSaveReqVO toTenantSaveReqVO(SkitAgentSaveReqVO source, TenantDO existing) {
        TenantSaveReqVO target = new TenantSaveReqVO();
        target.setId(source.getTenantId());
        target.setName(source.getName());
        target.setContactName(source.getContactName());
        target.setContactMobile(source.getContactMobile());
        target.setStatus(source.getStatus());
        target.setWebsites(source.getWebsites() != null ? source.getWebsites()
                : existing == null ? null : existing.getWebsites());
        target.setPackageId(source.getPackageId());
        target.setExpireTime(source.getExpireTime());
        target.setAccountCount(source.getAccountCount());
        target.setUsername(source.getUsername());
        target.setPassword(source.getPassword());
        return target;
    }

    private void saveAdSettings(SkitAgentSaveReqVO source, boolean preserveMissing) {
        SkitAdAccountService.Settings settings = preserveMissing
                ? adAccountService.getSettings() : new SkitAdAccountService.Settings();
        settings.setPangleUsername(merge(source.getPangleUsername(), settings.getPangleUsername(), preserveMissing));
        settings.setPangleAppId(merge(source.getPangleAppId(), settings.getPangleAppId(), preserveMissing));
        settings.setPangleAppSecret(source.getPangleAppSecret());
        settings.setPanglePlacementId(merge(source.getPanglePlacementId(),
                settings.getPanglePlacementId(), preserveMissing));
        settings.setPangleEnabled(resolveEnabled(source.getPangleEnabled(), settings.getPangleEnabled(),
                settings.getPangleUsername(), settings.getPangleAppId(), settings.getPanglePlacementId()));
        settings.setTakuUsername(merge(source.getTakuUsername(), settings.getTakuUsername(), preserveMissing));
        settings.setTakuAppId(merge(source.getTakuAppId(), settings.getTakuAppId(), preserveMissing));
        settings.setTakuAppKey(source.getTakuAppKey());
        settings.setTakuAppSecret(source.getTakuAppSecret());
        settings.setTakuPlacementId(merge(source.getTakuPlacementId(), settings.getTakuPlacementId(), preserveMissing));
        settings.setTakuEnabled(resolveEnabled(source.getTakuEnabled(), settings.getTakuEnabled(),
                settings.getTakuUsername(), settings.getTakuAppId(), settings.getTakuPlacementId()));
        adAccountService.saveSettings(settings);
    }

    private String merge(String requested, String existing, boolean preserveMissing) {
        return preserveMissing && requested == null ? existing : requested;
    }

    private Boolean resolveEnabled(Boolean requested, Boolean existing, String username,
                                   String appId, String placementId) {
        if (requested != null) {
            return requested;
        }
        if (Boolean.TRUE.equals(existing)) {
            return true;
        }
        return StrUtil.isAllNotBlank(username, appId, placementId);
    }

    private String generateRootInviteCode() {
        for (int attempt = 0; attempt < 10; attempt++) {
            String inviteCode = AGENT_INVITE_CODE_PREFIX
                    + IdUtil.fastSimpleUUID().substring(0, 11).toUpperCase(Locale.ROOT);
            boolean agentExists = agentMapper.selectByRootInviteCode(inviteCode) != null;
            boolean memberExists = TenantUtils.executeIgnore(() -> memberMapper.selectByInviteCode(inviteCode)) != null;
            if (!agentExists && !memberExists) {
                return inviteCode;
            }
        }
        throw new IllegalStateException("生成代理商邀请码失败");
    }

    private SkitAgentRespVO toRespVO(SkitAgentDO agent) {
        TenantDO tenant = tenantService.getTenant(agent.getTenantId());
        if (tenant == null) {
            return null;
        }
        SkitAgentRespVO result = new SkitAgentRespVO();
        result.setTenantId(tenant.getId());
        result.setTenantCode(agent.getTenantCode());
        result.setRootInviteCode(agent.getRootInviteCode());
        result.setName(tenant.getName());
        result.setContactName(tenant.getContactName());
        result.setContactMobile(tenant.getContactMobile());
        result.setStatus(tenant.getStatus());
        result.setWebsites(tenant.getWebsites());
        result.setPackageId(tenant.getPackageId());
        result.setExpireTime(tenant.getExpireTime());
        result.setAccountCount(tenant.getAccountCount());
        result.setRemark(agent.getRemark());
        result.setCreateTime(agent.getCreateTime());
        TenantPackageDO tenantPackage = tenantPackageService.getTenantPackage(tenant.getPackageId());
        result.setPackageName(tenantPackage == null ? null : tenantPackage.getName());
        if (tenant.getContactUserId() != null) {
            AdminUserDO user = TenantUtils.execute(tenant.getId(),
                    () -> adminUserService.getUser(tenant.getContactUserId()));
            result.setUsername(user == null ? null : user.getUsername());
        }
        SkitAdAccountService.Settings settings = TenantUtils.execute(tenant.getId(), adAccountService::getSettings);
        fillAdSettings(result, settings);
        return result;
    }

    private void fillAdSettings(SkitAgentRespVO result, SkitAdAccountService.Settings settings) {
        result.setPangleUsername(settings.getPangleUsername());
        result.setPangleAppId(settings.getPangleAppId());
        result.setPanglePlacementId(settings.getPanglePlacementId());
        result.setPangleEnabled(settings.getPangleEnabled());
        result.setPangleSecretConfigured(settings.getPangleSecretConfigured());
        result.setTakuUsername(settings.getTakuUsername());
        result.setTakuAppId(settings.getTakuAppId());
        result.setTakuPlacementId(settings.getTakuPlacementId());
        result.setTakuEnabled(settings.getTakuEnabled());
        result.setTakuAppKeyConfigured(settings.getTakuAppKeyConfigured());
        result.setTakuSecretConfigured(settings.getTakuSecretConfigured());
    }

    private boolean matchesKeyword(SkitAgentRespVO item, String keyword) {
        if (StrUtil.isBlank(keyword)) {
            return true;
        }
        String normalized = keyword.toLowerCase(Locale.ROOT);
        return containsIgnoreCase(item.getName(), normalized)
                || containsIgnoreCase(item.getTenantCode(), normalized)
                || containsIgnoreCase(item.getContactName(), normalized)
                || containsIgnoreCase(item.getContactMobile(), normalized);
    }

    private boolean containsIgnoreCase(String value, String normalizedKeyword) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(normalizedKeyword);
    }

    private String normalizeTenantCode(String tenantCode) {
        return StrUtil.isBlank(tenantCode) ? null : StrUtil.trim(tenantCode).toUpperCase(Locale.ROOT);
    }

}
