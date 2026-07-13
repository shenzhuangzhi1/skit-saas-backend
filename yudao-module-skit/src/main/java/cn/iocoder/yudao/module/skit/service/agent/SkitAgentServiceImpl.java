package cn.iocoder.yudao.module.skit.service.agent;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.validation.ValidationUtils;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.framework.tenant.core.util.TenantUtils;
import cn.iocoder.yudao.module.skit.controller.admin.tenant.vo.*;
import cn.iocoder.yudao.module.skit.dal.dataobject.agent.SkitAgentDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.agent.SkitAgentPageRow;
import cn.iocoder.yudao.module.skit.dal.mysql.agent.SkitAgentMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.member.SkitMemberMapper;
import cn.iocoder.yudao.module.skit.framework.security.SkitPlatformAdminGuard;
import cn.iocoder.yudao.module.skit.service.ad.SkitAdAccountService;
import cn.iocoder.yudao.module.skit.service.app.SkitAppReleaseService;
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
import org.springframework.validation.annotation.Validated;

import javax.annotation.Resource;
import java.util.*;
import java.util.stream.Collectors;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.skit.enums.ErrorCodeConstants.AGENT_ARCHIVED;
import static cn.iocoder.yudao.module.skit.enums.ErrorCodeConstants.AGENT_CODE_EXISTS;
import static cn.iocoder.yudao.module.skit.enums.ErrorCodeConstants.AGENT_NOT_ARCHIVED;
import static cn.iocoder.yudao.module.skit.enums.ErrorCodeConstants.AGENT_NOT_EXISTS;
import static cn.iocoder.yudao.module.system.enums.ErrorCodeConstants.TENANT_PACKAGE_NOT_EXISTS;
import static cn.iocoder.yudao.module.system.enums.ErrorCodeConstants.USER_MOBILE_EXISTS;
import static cn.iocoder.yudao.module.system.enums.ErrorCodeConstants.USER_USERNAME_EXISTS;

@Service
@Validated
public class SkitAgentServiceImpl implements SkitAgentService {

    private static final String AUTO_TENANT_CODE_PREFIX = "AG";
    private static final String AGENT_INVITE_CODE_PREFIX = "A";
    private static final String STANDARD_AGENT_PACKAGE_CODE = "SKIT_AGENT_STANDARD";

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
    @Resource
    private SkitAppReleaseService appReleaseService;

    @Override
    public PageResult<SkitAgentRespVO> getAgentPage(SkitAgentPageReqVO pageReqVO) {
        platformAdminGuard.check();
        ValidationUtils.validate(pageReqVO);
        PageResult<SkitAgentPageRow> page = agentMapper.selectPage(pageReqVO);
        if (page.getList().isEmpty()) {
            return new PageResult<>(Collections.emptyList(), page.getTotal());
        }
        Set<Long> packageIds = page.getList().stream().map(SkitAgentPageRow::getPackageId)
                .filter(Objects::nonNull).collect(Collectors.toCollection(LinkedHashSet::new));
        Map<Long, TenantPackageDO> packageMap = new HashMap<>();
        for (TenantPackageDO tenantPackage : tenantPackageService.getTenantPackageList(packageIds)) {
            packageMap.put(tenantPackage.getId(), tenantPackage);
        }
        Set<Long> administratorIds = page.getList().stream().map(SkitAgentPageRow::getContactUserId)
                .filter(Objects::nonNull).collect(Collectors.toCollection(LinkedHashSet::new));
        Map<Long, AdminUserDO> administratorMap = new HashMap<>();
        for (AdminUserDO administrator : adminUserService.getUserListIgnoreTenant(administratorIds)) {
            administratorMap.put(administrator.getId(), administrator);
        }
        Set<Long> tenantIds = page.getList().stream().map(SkitAgentPageRow::getTenantId)
                .filter(Objects::nonNull).collect(Collectors.toCollection(LinkedHashSet::new));
        Map<Long, TenantDO> tenantMap = new HashMap<>();
        for (TenantDO tenant : tenantService.getTenantList(tenantIds)) {
            tenantMap.put(tenant.getId(), tenant);
        }
        Map<Long, SkitAdAccountService.Settings> adSettingsMap =
                adAccountService.getSettingsMapForPlatform(tenantIds);
        List<SkitAgentRespVO> list = page.getList().stream()
                .map(row -> toPageRespVO(row, tenantMap, packageMap, administratorMap, adSettingsMap))
                .collect(Collectors.toList());
        return new PageResult<>(list, page.getTotal());
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
    public Long createAgent(SkitAgentCreateReqVO createReqVO) {
        platformAdminGuard.check();
        ValidationUtils.validate(createReqVO);
        String mobile = SkitAgentCreateReqVO.normalizeMobile(createReqVO.getMobile());
        validateAdministratorIdentityUnbound(mobile);
        TenantPackageDO standardPackage = tenantPackageService.getTenantPackageByCode(STANDARD_AGENT_PACKAGE_CODE);
        if (standardPackage == null) {
            throw exception(TENANT_PACKAGE_NOT_EXISTS);
        }

        Long tenantId = tenantService.createTenant(toTenantSaveReqVO(createReqVO, standardPackage));
        String tenantCode = AUTO_TENANT_CODE_PREFIX + tenantId;
        validateTenantCodeDuplicate(tenantCode, null);
        agentMapper.insert(SkitAgentDO.builder()
                .tenantId(tenantId)
                .tenantCode(tenantCode)
                .rootInviteCode(generateRootInviteCode())
                .status(createReqVO.getStatus())
                .remark("")
                .build());
        appReleaseService.ensureProfile(tenantId, tenantCode);
        TenantUtils.execute(tenantId, () -> {
            adAccountService.ensureDefaultAccounts();
            saveAdSettings(createReqVO, false);
            commissionService.ensureDefaultPlan();
        });
        if (cn.iocoder.yudao.framework.common.enums.CommonStatusEnum.isDisable(createReqVO.getStatus())) {
            TenantDO tenant = tenantService.getTenant(tenantId);
            if (tenant != null && tenant.getContactUserId() != null) {
                TenantUtils.execute(tenantId, () -> adminUserService.updateUserStatus(tenant.getContactUserId(),
                        cn.iocoder.yudao.framework.common.enums.CommonStatusEnum.DISABLE.getStatus()));
            }
        }
        return tenantId;
    }

    @Override
    @DSTransactional
    public void updateAgent(SkitAgentUpdateReqVO updateReqVO) {
        platformAdminGuard.check();
        ValidationUtils.validate(updateReqVO);
        SkitAgentDO agent = validateMutableAgent(updateReqVO.getTenantId());
        TenantDO tenant = tenantService.getTenant(updateReqVO.getTenantId());
        if (tenant == null) {
            throw exception(AGENT_NOT_EXISTS);
        }

        tenantService.updateTenant(toTenantSaveReqVO(updateReqVO, tenant));
        agentMapper.updateById(new SkitAgentDO()
                .setId(agent.getId())
                .setRemark(updateReqVO.getRemark() == null ? agent.getRemark() : updateReqVO.getRemark()));

        Long tenantId = agent.getTenantId();
        TenantUtils.execute(tenantId, () -> {
            adAccountService.ensureDefaultAccounts();
            saveAdSettings(updateReqVO, true);
            commissionService.ensureDefaultPlan();
        });
        if (!Objects.equals(tenant.getStatus(), updateReqVO.getStatus())) {
            syncBoundAdministratorStatus(tenant, updateReqVO.getStatus());
        }
    }

    @Override
    @DSTransactional
    public void updateAgentMobile(SkitAgentMobileUpdateReqVO updateReqVO) {
        platformAdminGuard.check();
        ValidationUtils.validate(updateReqVO);
        SkitAgentDO agent = validateMutableAgent(updateReqVO.getTenantId());
        TenantDO tenant = validateTenant(agent.getTenantId());
        Long userId = requireContactUserId(tenant);
        String mobile = SkitAgentCreateReqVO.normalizeMobile(updateReqVO.getMobile());
        validateAdministratorIdentityAvailable(mobile, userId);
        tenantService.updateTenant(toTenantMobileUpdateReqVO(tenant, mobile));
        TenantUtils.execute(agent.getTenantId(),
                () -> adminUserService.updateUserIdentity(userId, mobile, mobile));
    }

    @Override
    @DSTransactional
    public void resetAgentPassword(SkitAgentPasswordResetReqVO resetReqVO) {
        platformAdminGuard.check();
        ValidationUtils.validate(resetReqVO);
        SkitAgentDO agent = validateMutableAgent(resetReqVO.getTenantId());
        TenantDO tenant = validateTenant(agent.getTenantId());
        Long userId = requireContactUserId(tenant);
        TenantUtils.execute(agent.getTenantId(),
                () -> adminUserService.updateUserPassword(userId, resetReqVO.getPassword()));
    }

    @Override
    @DSTransactional
    public void archiveAgent(Long tenantId) {
        platformAdminGuard.check();
        SkitAgentDO agent = validateAgent(tenantId);
        TenantDO tenant = validateTenant(agent.getTenantId());
        if (agent.getArchivedTime() == null) {
            agentMapper.updateArchiveState(tenantId, java.time.LocalDateTime.now(), SecurityFrameworkUtils.getLoginUserId());
        }
        tenantService.updateTenantStatus(tenantId,
                cn.iocoder.yudao.framework.common.enums.CommonStatusEnum.DISABLE.getStatus());
        syncBoundAdministratorStatus(tenant,
                cn.iocoder.yudao.framework.common.enums.CommonStatusEnum.DISABLE.getStatus());
    }

    @Override
    @DSTransactional
    public void restoreAgent(Long tenantId) {
        platformAdminGuard.check();
        SkitAgentDO agent = validateAgent(tenantId);
        if (agent.getArchivedTime() == null) {
            throw exception(AGENT_NOT_ARCHIVED);
        }
        TenantDO tenant = validateTenant(agent.getTenantId());
        agentMapper.clearArchiveState(tenantId);
        tenantService.updateTenantStatus(tenantId,
                cn.iocoder.yudao.framework.common.enums.CommonStatusEnum.ENABLE.getStatus());
        syncBoundAdministratorStatus(tenant,
                cn.iocoder.yudao.framework.common.enums.CommonStatusEnum.ENABLE.getStatus());
    }

    @Override
    @DSTransactional
    public String rotateRootInviteCode(Long tenantId) {
        platformAdminGuard.check();
        validateMutableAgent(tenantId);
        String inviteCode = generateRootInviteCode();
        agentMapper.updateRootInviteCode(tenantId, inviteCode);
        return inviteCode;
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

    private SkitAgentDO validateMutableAgent(Long tenantId) {
        SkitAgentDO agent = validateAgent(tenantId);
        if (agent.getArchivedTime() != null) {
            throw exception(AGENT_ARCHIVED);
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

    private void validateAdministratorIdentityUnbound(String mobile) {
        validateAdministratorIdentityAvailable(mobile, null);
    }

    private void validateAdministratorIdentityAvailable(String mobile, Long excludeUserId) {
        if (adminUserService.getUserListByUsernameIgnoreTenant(mobile).stream()
                .anyMatch(user -> !Objects.equals(user.getId(), excludeUserId))) {
            throw exception(USER_USERNAME_EXISTS);
        }
        if (adminUserService.getUserListByMobileIgnoreTenant(mobile).stream()
                .anyMatch(user -> !Objects.equals(user.getId(), excludeUserId))) {
            throw exception(USER_MOBILE_EXISTS);
        }
    }

    private TenantSaveReqVO toTenantSaveReqVO(SkitAgentCreateReqVO source, TenantPackageDO standardPackage) {
        String mobile = SkitAgentCreateReqVO.normalizeMobile(source.getMobile());
        TenantSaveReqVO target = new TenantSaveReqVO();
        target.setName(source.getName());
        target.setContactName(source.getName());
        target.setContactMobile(mobile);
        target.setStatus(source.getStatus());
        target.setPackageId(standardPackage.getId());
        target.setExpireTime(source.getExpireTime());
        target.setAccountCount(1);
        target.setUsername(mobile);
        target.setPassword(source.getPassword());
        return target;
    }

    private TenantSaveReqVO toTenantSaveReqVO(SkitAgentUpdateReqVO source, TenantDO existing) {
        TenantSaveReqVO target = copyTenant(existing);
        target.setName(source.getName());
        target.setContactName(source.getName());
        target.setStatus(source.getStatus());
        target.setExpireTime(source.getExpireTime());
        target.setAccountCount(1);
        return target;
    }

    private TenantSaveReqVO toTenantMobileUpdateReqVO(TenantDO tenant, String mobile) {
        TenantSaveReqVO target = copyTenant(tenant);
        target.setContactMobile(mobile);
        return target;
    }

    private TenantSaveReqVO copyTenant(TenantDO tenant) {
        TenantSaveReqVO target = new TenantSaveReqVO();
        target.setId(tenant.getId());
        target.setName(tenant.getName());
        target.setContactName(tenant.getContactName());
        target.setContactMobile(tenant.getContactMobile());
        target.setStatus(tenant.getStatus());
        target.setWebsites(tenant.getWebsites());
        target.setPackageId(tenant.getPackageId());
        target.setExpireTime(tenant.getExpireTime());
        target.setAccountCount(tenant.getAccountCount());
        return target;
    }

    private TenantDO validateTenant(Long tenantId) {
        TenantDO tenant = tenantService.getTenant(tenantId);
        if (tenant == null) {
            throw exception(AGENT_NOT_EXISTS);
        }
        return tenant;
    }

    private Long requireContactUserId(TenantDO tenant) {
        if (tenant.getContactUserId() == null) {
            throw exception(AGENT_NOT_EXISTS);
        }
        return tenant.getContactUserId();
    }

    private void syncBoundAdministratorStatus(TenantDO tenant, Integer status) {
        if (tenant.getContactUserId() == null) {
            return;
        }
        TenantUtils.execute(tenant.getId(),
                () -> adminUserService.updateUserStatus(tenant.getContactUserId(), status));
    }

    private void saveAdSettings(SkitAgentUpdateReqVO source, boolean preserveMissing) {
        SkitAdAccountService.Settings settings = adAccountService.getSettings();
        settings.setPangleUsername(merge(trimNonSecret(source.getPangleUsername()), settings.getPangleUsername(), true));
        settings.setPangleAppId(merge(trimNonSecret(source.getPangleAppId()), settings.getPangleAppId(), true));
        settings.setPangleAppSecret(source.getPangleAppSecret());
        settings.setPanglePlacementId(merge(trimNonSecret(source.getPanglePlacementId()), settings.getPanglePlacementId(), true));
        settings.setPangleEnabled(resolveEnabled(source.getPangleEnabled(), settings.getPangleEnabled()));
        settings.setTakuUsername(merge(trimNonSecret(source.getTakuUsername()), settings.getTakuUsername(), true));
        settings.setTakuAppId(merge(trimNonSecret(source.getTakuAppId()), settings.getTakuAppId(), true));
        settings.setTakuAppKey(source.getTakuAppKey());
        settings.setTakuAppSecret(source.getTakuAppSecret());
        settings.setTakuPlacementId(merge(trimNonSecret(source.getTakuPlacementId()), settings.getTakuPlacementId(), true));
        settings.setTakuEnabled(resolveEnabled(source.getTakuEnabled(), settings.getTakuEnabled()));
        adAccountService.saveSettings(settings);
    }

    private void saveAdSettings(SkitAgentCreateReqVO source, boolean preserveMissing) {
        SkitAdAccountService.Settings settings = new SkitAdAccountService.Settings();
        settings.setPangleUsername(trimNonSecret(source.getPangleUsername()));
        settings.setPangleAppId(trimNonSecret(source.getPangleAppId()));
        settings.setPangleAppSecret(source.getPangleAppSecret());
        settings.setPanglePlacementId(trimNonSecret(source.getPanglePlacementId()));
        settings.setPangleEnabled(Boolean.TRUE.equals(source.getPangleEnabled()));
        settings.setTakuUsername(trimNonSecret(source.getTakuUsername()));
        settings.setTakuAppId(trimNonSecret(source.getTakuAppId()));
        settings.setTakuAppKey(source.getTakuAppKey());
        settings.setTakuAppSecret(source.getTakuAppSecret());
        settings.setTakuPlacementId(trimNonSecret(source.getTakuPlacementId()));
        settings.setTakuEnabled(Boolean.TRUE.equals(source.getTakuEnabled()));
        adAccountService.saveSettings(settings);
    }

    private String merge(String requested, String existing, boolean preserveMissing) {
        return preserveMissing && requested == null ? existing : requested;
    }

    private String trimNonSecret(String value) {
        return value == null ? null : StrUtil.trim(value);
    }

    private Boolean resolveEnabled(Boolean requested, Boolean existing) {
        return requested != null ? requested : Boolean.TRUE.equals(existing);
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
        result.setArchivedTime(agent.getArchivedTime());
        result.setArchivedBy(agent.getArchivedBy());
        result.setName(tenant.getName());
        result.setContactName(tenant.getContactName());
        result.setContactMobile(tenant.getContactMobile());
        result.setMobile(tenant.getContactMobile());
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
            AdminUserDO user = adminUserService.getUserIgnoreTenant(tenant.getContactUserId());
            result.setUsername(user == null ? null : user.getUsername());
        }
        SkitAdAccountService.Settings settings = TenantUtils.execute(tenant.getId(), adAccountService::getSettings);
        fillAdSettings(result, settings);
        return result;
    }

    private SkitAgentRespVO toPageRespVO(SkitAgentPageRow row,
                                         Map<Long, TenantDO> tenantMap,
                                         Map<Long, TenantPackageDO> packageMap,
                                         Map<Long, AdminUserDO> administratorMap,
                                         Map<Long, SkitAdAccountService.Settings> adSettingsMap) {
        SkitAgentRespVO result = new SkitAgentRespVO();
        result.setTenantId(row.getTenantId());
        result.setTenantCode(row.getTenantCode());
        result.setRootInviteCode(row.getRootInviteCode());
        result.setArchivedTime(row.getArchivedTime());
        result.setArchivedBy(row.getArchivedBy());
        result.setName(row.getName());
        result.setContactName(row.getContactName());
        result.setContactMobile(row.getContactMobile());
        result.setMobile(row.getContactMobile());
        result.setStatus(row.getStatus());
        TenantDO tenant = tenantMap.get(row.getTenantId());
        result.setWebsites(tenant == null ? null : tenant.getWebsites());
        result.setPackageId(row.getPackageId());
        TenantPackageDO tenantPackage = packageMap.get(row.getPackageId());
        result.setPackageName(tenantPackage == null ? null : tenantPackage.getName());
        result.setExpireTime(row.getExpireTime());
        result.setAccountCount(row.getAccountCount());
        AdminUserDO administrator = administratorMap.get(row.getContactUserId());
        result.setUsername(administrator == null ? null : administrator.getUsername());
        result.setRemark(row.getRemark());
        result.setCreateTime(row.getCreateTime());
        SkitAdAccountService.Settings settings = adSettingsMap.get(row.getTenantId());
        if (settings != null) {
            fillAdSettings(result, settings);
        }
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

}
