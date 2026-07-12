package cn.iocoder.yudao.module.skit.service.app;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.enums.CommonStatusEnum;
import cn.iocoder.yudao.module.skit.dal.dataobject.app.SkitAppReleaseProfileDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.agent.SkitAgentDO;
import cn.iocoder.yudao.module.skit.dal.mysql.app.SkitAppReleaseProfileMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.agent.SkitAgentMapper;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Locale;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.skit.enums.ErrorCodeConstants.AGENT_NOT_EXISTS;
import static cn.iocoder.yudao.module.skit.enums.ErrorCodeConstants.APP_RELEASE_PROFILE_INVALID;

@Service
public class SkitAppReleaseServiceImpl implements SkitAppReleaseService {

    @Resource
    private SkitAppReleaseProfileMapper profileMapper;
    @Resource
    private SkitAgentMapper agentMapper;

    @Override
    public Manifest current(String profileCode, String nativeVersion) {
        Manifest result = new Manifest();
        String normalizedCode = normalizeCode(profileCode);
        if (normalizedCode == null) {
            return result;
        }
        SkitAppReleaseProfileDO profile = profileMapper.selectByProfileCode(normalizedCode);
        if (profile == null || CommonStatusEnum.isDisable(profile.getStatus())
                || StrUtil.isBlank(profile.getHotVersion()) || StrUtil.isBlank(profile.getHotBundleUrl())
                || StrUtil.isBlank(profile.getHotBundleSha256())) {
            return result;
        }
        SkitAgentDO agent = agentMapper.selectByTenantId(profile.getTenantId());
        if (agent == null || CommonStatusEnum.isDisable(agent.getStatus())
                || !normalizedCode.equalsIgnoreCase(agent.getTenantCode())) {
            return result;
        }
        if (compareVersions(nativeVersion, profile.getMinNativeVersion()) < 0) {
            return result;
        }
        result.setUpdateAvailable(true);
        result.setHotVersion(profile.getHotVersion());
        result.setBundleUrl(profile.getHotBundleUrl());
        result.setSha256(profile.getHotBundleSha256());
        result.setMinNativeVersion(profile.getMinNativeVersion());
        return result;
    }

    @Override
    public ProfileView getProfile(Long tenantId) {
        SkitAppReleaseProfileDO profile = profileMapper.selectByTenantId(tenantId);
        if (profile == null) {
            SkitAgentDO agent = agentMapper.selectByTenantId(tenantId);
            if (agent == null) {
                return null;
            }
            ensureProfile(tenantId, agent.getTenantCode());
            profile = profileMapper.selectByTenantId(tenantId);
        }
        return toView(profile);
    }

    @Override
    public ProfileView saveProfile(ProfileView profile) {
        SkitAgentDO agent = agentMapper.selectByTenantId(profile.getTenantId());
        if (agent == null) {
            throw exception(AGENT_NOT_EXISTS);
        }
        ensureProfile(profile.getTenantId(), agent.getTenantCode());
        SkitAppReleaseProfileDO entity = profileMapper.selectByTenantId(profile.getTenantId());
        entity.setChannel(normalizeChannel(profile.getChannel()));
        entity.setMinNativeVersion(StrUtil.nullToEmpty(profile.getMinNativeVersion()));
        entity.setHotVersion(StrUtil.nullToEmpty(profile.getHotVersion()));
        entity.setHotBundleUrl(StrUtil.nullToEmpty(profile.getHotBundleUrl()));
        entity.setHotBundleSha256(StrUtil.nullToEmpty(profile.getHotBundleSha256()).toLowerCase(Locale.ROOT));
        entity.setNativeVersion(StrUtil.nullToEmpty(profile.getNativeVersion()));
        entity.setNativePackage(StrUtil.nullToEmpty(profile.getNativePackage()));
        entity.setStatus(profile.getStatus());
        validateProfile(entity);
        profileMapper.updateById(entity);
        return toView(entity);
    }

    @Override
    public void ensureProfile(Long tenantId, String profileCode) {
        if (profileMapper.selectByTenantId(tenantId) != null) {
            return;
        }
        profileMapper.insert(SkitAppReleaseProfileDO.builder().tenantId(tenantId)
                .profileCode(requireCode(profileCode)).channel("production")
                .status(CommonStatusEnum.DISABLE.getStatus()).build());
    }

    @Override
    public void renameProfile(Long tenantId, String profileCode) {
        ensureProfile(tenantId, profileCode);
        SkitAppReleaseProfileDO profile = profileMapper.selectByTenantId(tenantId);
        profile.setProfileCode(requireCode(profileCode));
        profileMapper.updateById(profile);
    }

    private String normalizeCode(String profileCode) {
        if (StrUtil.isBlank(profileCode)) {
            return null;
        }
        return StrUtil.trim(profileCode).toUpperCase(Locale.ROOT);
    }

    private String requireCode(String profileCode) {
        String normalized = normalizeCode(profileCode);
        if (normalized == null || !normalized.matches("[A-Z0-9_-]{3,32}")) {
            throw exception(APP_RELEASE_PROFILE_INVALID, "档案代码格式不正确");
        }
        return normalized;
    }

    private String normalizeChannel(String channel) {
        String value = StrUtil.blankToDefault(StrUtil.trim(channel), "production").toLowerCase(Locale.ROOT);
        if (!"production".equals(value) && !"staging".equals(value)) {
            throw exception(APP_RELEASE_PROFILE_INVALID, "发布渠道必须为 production 或 staging");
        }
        return value;
    }

    private void validateProfile(SkitAppReleaseProfileDO profile) {
        if (profile.getStatus() == null) {
            throw exception(APP_RELEASE_PROFILE_INVALID, "状态不能为空");
        }
        if (!CommonStatusEnum.isEnable(profile.getStatus()) && !CommonStatusEnum.isDisable(profile.getStatus())) {
            throw exception(APP_RELEASE_PROFILE_INVALID, "状态不正确");
        }
        if (CommonStatusEnum.isDisable(profile.getStatus())) {
            return;
        }
        if (!StrUtil.isAllNotBlank(profile.getMinNativeVersion(), profile.getHotVersion(),
                profile.getHotBundleUrl(), profile.getHotBundleSha256())) {
            throw exception(APP_RELEASE_PROFILE_INVALID, "启用前必须填写最低原生版本、热更新版本、公开地址和 SHA-256");
        }
        if (!profile.getHotBundleUrl().startsWith("https://")) {
            throw exception(APP_RELEASE_PROFILE_INVALID, "热更新包地址必须使用 HTTPS");
        }
        if (!profile.getHotBundleSha256().matches("[0-9a-f]{64}")) {
            throw exception(APP_RELEASE_PROFILE_INVALID, "热更新包 SHA-256 格式不正确");
        }
        if (!isVersion(profile.getMinNativeVersion()) || !isVersion(profile.getHotVersion())) {
            throw exception(APP_RELEASE_PROFILE_INVALID, "版本号格式不正确");
        }
    }

    private boolean isVersion(String value) {
        return value != null && value.matches("[0-9]+(\\.[0-9]+){1,3}([-.][A-Za-z0-9._-]+)?");
    }

    private ProfileView toView(SkitAppReleaseProfileDO profile) {
        if (profile == null) {
            return null;
        }
        ProfileView result = new ProfileView();
        result.setTenantId(profile.getTenantId());
        result.setProfileCode(profile.getProfileCode());
        result.setChannel(profile.getChannel());
        result.setMinNativeVersion(profile.getMinNativeVersion());
        result.setHotVersion(profile.getHotVersion());
        result.setHotBundleUrl(profile.getHotBundleUrl());
        result.setHotBundleSha256(profile.getHotBundleSha256());
        result.setNativeVersion(profile.getNativeVersion());
        result.setNativePackage(profile.getNativePackage());
        result.setStatus(profile.getStatus());
        return result;
    }

    private int compareVersions(String current, String minimum) {
        if (StrUtil.isBlank(minimum)) {
            return 1;
        }
        if (StrUtil.isBlank(current)) {
            return -1;
        }
        String[] currentParts = current.trim().split("\\.");
        String[] minimumParts = minimum.trim().split("\\.");
        int length = Math.max(currentParts.length, minimumParts.length);
        for (int index = 0; index < length; index++) {
            int currentPart = versionPart(currentParts, index);
            int minimumPart = versionPart(minimumParts, index);
            if (currentPart != minimumPart) {
                return Integer.compare(currentPart, minimumPart);
            }
        }
        return 0;
    }

    private int versionPart(String[] parts, int index) {
        if (index >= parts.length) {
            return 0;
        }
        String value = parts[index].replaceAll("[^0-9].*$", "");
        if (StrUtil.isBlank(value)) {
            return 0;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

}
