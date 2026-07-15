package cn.iocoder.yudao.module.skit.service.app;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.enums.CommonStatusEnum;
import cn.iocoder.yudao.module.skit.dal.dataobject.app.SkitAppReleaseProfileDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.agent.SkitAgentDO;
import cn.iocoder.yudao.module.skit.dal.mysql.app.SkitAppReleaseProfileMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.agent.SkitAgentMapper;
import cn.iocoder.yudao.module.system.service.tenant.TenantService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.net.URI;
import java.util.Locale;
import java.util.Objects;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.skit.enums.ErrorCodeConstants.AGENT_NOT_EXISTS;
import static cn.iocoder.yudao.module.skit.enums.ErrorCodeConstants.APP_RELEASE_PROFILE_INVALID;

@Service
@Slf4j
public class SkitAppReleaseServiceImpl implements SkitAppReleaseService {

    @Resource
    private SkitAppReleaseProfileMapper profileMapper;
    @Resource
    private SkitAgentMapper agentMapper;
    @Resource
    private TenantService tenantService;
    @Resource
    private SkitRuntimeUpdateManifestVerifier manifestVerifier;

    @Override
    public Manifest current(String profileCode, String nativeVersion) {
        Manifest result = new Manifest();
        String normalizedCode = normalizeCode(profileCode);
        if (normalizedCode == null) {
            return result;
        }
        SkitAppReleaseProfileDO profile = profileMapper.selectByProfileCode(normalizedCode);
        if (profile == null) {
            return result;
        }
        tenantService.validTenant(profile.getTenantId());
        if (CommonStatusEnum.isDisable(profile.getStatus()) || !hasCompleteSignedManifest(profile)
                || !isHttpsBundleUrl(profile.getHotBundleUrl())) {
            return result;
        }
        SkitAgentDO agent = agentMapper.selectByTenantId(profile.getTenantId());
        if (agent == null || !normalizedCode.equalsIgnoreCase(agent.getTenantCode())) {
            return result;
        }
        if (compareVersions(nativeVersion, profile.getMinNativeVersion()) < 0) {
            return result;
        }
        try {
            String calculatedFingerprint = manifestVerifier.validateAndFingerprint(
                    profile.getRuntimeUpdatePublicKey());
            if (!calculatedFingerprint.equals(profile.getRuntimeUpdateKeyFingerprint())) {
                return result;
            }
            manifestVerifier.verify(profile.getRuntimeUpdatePublicKey(),
                    profile.getProfileCode(), profile.getNativePackage(),
                    profile.getHotBundleSha256(), profile.getNativeProtocolVersion(),
                    profile.getHotReleaseNo(), profile.getHotManifestSignature());
        } catch (SecurityException invalidManifest) {
            log.warn("[current][reject invalid signed runtime manifest tenantId={} releaseNo={}]",
                    profile.getTenantId(), profile.getHotReleaseNo());
            return result;
        }
        result.setUpdateAvailable(true);
        result.setHotVersion(profile.getHotVersion());
        result.setTenantId(profile.getProfileCode());
        result.setApplicationId(profile.getNativePackage());
        result.setBundleUrl(profile.getHotBundleUrl());
        result.setBundleSha256(profile.getHotBundleSha256());
        result.setProtocolVersion(profile.getNativeProtocolVersion());
        result.setReleaseNo(profile.getHotReleaseNo());
        result.setSignature(profile.getHotManifestSignature());
        result.setMinNativeVersion(profile.getMinNativeVersion());
        return result;
    }

    @Override
    public ProfileView getProfile(Long tenantId) {
        SkitAppReleaseProfileDO profile = profileMapper.selectByTenantId(tenantId);
        return profile == null ? null : toView(profile);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ProfileView saveProfile(ProfileView profile) {
        if (profile == null || profile.getTenantId() == null) {
            throw exception(APP_RELEASE_PROFILE_INVALID, "租户不能为空");
        }
        SkitAgentDO agent = agentMapper.selectByTenantId(profile.getTenantId());
        if (agent == null) {
            throw exception(AGENT_NOT_EXISTS);
        }
        ensureProfile(profile.getTenantId(), agent.getTenantCode());
        SkitAppReleaseProfileDO entity = profileMapper.selectByTenantIdForUpdate(profile.getTenantId());
        if (entity == null) {
            throw exception(APP_RELEASE_PROFILE_INVALID, "发布档案不存在");
        }
        String canonicalTenantCode = requireCode(agent.getTenantCode());
        String nextSha256 = StrUtil.nullToEmpty(profile.getHotBundleSha256()).toLowerCase(Locale.ROOT);
        String nextNativePackage = StrUtil.nullToEmpty(profile.getNativePackage()).trim();
        Integer nextProtocolVersion = profile.getNativeProtocolVersion();
        long previousReleaseNo = defaultReleaseNo(entity.getHotReleaseNo());
        long nextReleaseNo = defaultReleaseNo(profile.getHotReleaseNo());
        String nextSignature = StrUtil.nullToEmpty(profile.getHotManifestSignature()).trim();
        String nextRuntimeUpdatePublicKey = StrUtil.nullToEmpty(
                profile.getRuntimeUpdatePublicKey()).trim();
        String nextRuntimeUpdateKeyFingerprint = "";
        if (StrUtil.isNotBlank(nextRuntimeUpdatePublicKey)) {
            try {
                nextRuntimeUpdateKeyFingerprint = manifestVerifier.validateAndFingerprint(
                        nextRuntimeUpdatePublicKey);
            } catch (SecurityException invalidKey) {
                throw exception(APP_RELEASE_PROFILE_INVALID,
                        "租户热更新 RSA 公钥必须是至少 2048 位的 X.509 DER Base64");
            }
        }
        if (nextReleaseNo < previousReleaseNo) {
            throw exception(APP_RELEASE_PROFILE_INVALID, "热更新发布序号不能回退");
        }
        boolean signedScopeChanged = !Objects.equals(entity.getProfileCode(), canonicalTenantCode)
                || !Objects.equals(entity.getNativePackage(), nextNativePackage)
                || !Objects.equals(entity.getHotBundleSha256(), nextSha256)
                || !Objects.equals(entity.getNativeProtocolVersion(), nextProtocolVersion)
                || !Objects.equals(StrUtil.nullToEmpty(entity.getRuntimeUpdatePublicKey()),
                nextRuntimeUpdatePublicKey)
                || !Objects.equals(StrUtil.nullToEmpty(entity.getHotManifestSignature()), nextSignature);
        if (previousReleaseNo > 0 && nextReleaseNo == previousReleaseNo && signedScopeChanged) {
            throw exception(APP_RELEASE_PROFILE_INVALID, "签名字段变更必须提升热更新发布序号");
        }
        entity.setProfileCode(canonicalTenantCode);
        entity.setChannel(normalizeChannel(profile.getChannel()));
        entity.setMinNativeVersion(StrUtil.nullToEmpty(profile.getMinNativeVersion()).trim());
        entity.setHotVersion(StrUtil.nullToEmpty(profile.getHotVersion()).trim());
        entity.setHotBundleUrl(StrUtil.nullToEmpty(profile.getHotBundleUrl()).trim());
        entity.setHotBundleSha256(nextSha256);
        entity.setHotReleaseNo(nextReleaseNo);
        entity.setHotManifestSignature(nextSignature);
        entity.setNativeVersion(StrUtil.nullToEmpty(profile.getNativeVersion()).trim());
        entity.setNativePackage(nextNativePackage);
        entity.setNativeProtocolVersion(nextProtocolVersion);
        entity.setRuntimeUpdatePublicKey(nextRuntimeUpdatePublicKey);
        entity.setRuntimeUpdateKeyFingerprint(nextRuntimeUpdateKeyFingerprint);
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
                .hotReleaseNo(0L).hotManifestSignature("")
                .runtimeUpdatePublicKey("").runtimeUpdateKeyFingerprint("")
                .nativeProtocolVersion(1)
                .status(CommonStatusEnum.DISABLE.getStatus()).build());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void renameProfile(Long tenantId, String profileCode) {
        ensureProfile(tenantId, profileCode);
        SkitAppReleaseProfileDO profile = profileMapper.selectByTenantIdForUpdate(tenantId);
        String normalizedCode = requireCode(profileCode);
        if (!normalizedCode.equals(profile.getProfileCode())
                && defaultReleaseNo(profile.getHotReleaseNo()) > 0) {
            profile.setStatus(CommonStatusEnum.DISABLE.getStatus());
        }
        profile.setProfileCode(normalizedCode);
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
        long releaseNo = defaultReleaseNo(profile.getHotReleaseNo());
        if (releaseNo < 0) {
            throw exception(APP_RELEASE_PROFILE_INVALID, "热更新发布序号不能为负数");
        }
        boolean hasManifest = releaseNo > 0 || StrUtil.isNotBlank(profile.getHotManifestSignature())
                || StrUtil.isNotBlank(profile.getHotVersion())
                || StrUtil.isNotBlank(profile.getHotBundleUrl())
                || StrUtil.isNotBlank(profile.getHotBundleSha256());
        if (hasManifest) {
            if (releaseNo <= 0 || !StrUtil.isAllNotBlank(profile.getHotManifestSignature(),
                    profile.getHotVersion(), profile.getHotBundleUrl(), profile.getHotBundleSha256(),
                    profile.getNativePackage()) || profile.getNativeProtocolVersion() == null
                    || profile.getNativeProtocolVersion() <= 0) {
                throw exception(APP_RELEASE_PROFILE_INVALID,
                        "签名热更新清单必须一次提交完整字段");
            }
            if (!isHttpsBundleUrl(profile.getHotBundleUrl())) {
                throw exception(APP_RELEASE_PROFILE_INVALID, "热更新包地址必须是无凭证、无片段的 HTTPS URL");
            }
            if (!profile.getHotBundleSha256().matches("[0-9a-f]{64}")) {
                throw exception(APP_RELEASE_PROFILE_INVALID, "热更新包 SHA-256 格式不正确");
            }
            if (!isVersion(profile.getHotVersion())) {
                throw exception(APP_RELEASE_PROFILE_INVALID, "热更新版本号格式不正确");
            }
            if (!StrUtil.isAllNotBlank(profile.getRuntimeUpdatePublicKey(),
                    profile.getRuntimeUpdateKeyFingerprint())) {
                throw exception(APP_RELEASE_PROFILE_INVALID,
                        "签名热更新清单必须配置该租户的 RSA 公钥");
            }
            try {
                String calculatedFingerprint = manifestVerifier.validateAndFingerprint(
                        profile.getRuntimeUpdatePublicKey());
                if (!calculatedFingerprint.equals(profile.getRuntimeUpdateKeyFingerprint())) {
                    throw new SecurityException("Runtime update public key fingerprint mismatch");
                }
                manifestVerifier.verify(profile.getRuntimeUpdatePublicKey(),
                        profile.getProfileCode(), profile.getNativePackage(),
                        profile.getHotBundleSha256(), profile.getNativeProtocolVersion(), releaseNo,
                        profile.getHotManifestSignature());
            } catch (SecurityException invalidManifest) {
                throw exception(APP_RELEASE_PROFILE_INVALID, "热更新签名清单未通过受信公钥验证");
            }
        }
        if (CommonStatusEnum.isDisable(profile.getStatus())) {
            return;
        }
        if (!hasManifest || !StrUtil.isAllNotBlank(profile.getMinNativeVersion(),
                profile.getNativeVersion()) || !isVersion(profile.getMinNativeVersion())
                || !isVersion(profile.getNativeVersion())) {
            throw exception(APP_RELEASE_PROFILE_INVALID,
                    "启用前必须填写原生版本、最低版本和完整的 CI 签名热更新清单");
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
        result.setHotReleaseNo(profile.getHotReleaseNo());
        result.setHotManifestSignature(profile.getHotManifestSignature());
        result.setNativeVersion(profile.getNativeVersion());
        result.setNativePackage(profile.getNativePackage());
        result.setNativeProtocolVersion(profile.getNativeProtocolVersion());
        result.setRuntimeUpdatePublicKey(profile.getRuntimeUpdatePublicKey());
        result.setRuntimeUpdateKeyFingerprint(profile.getRuntimeUpdateKeyFingerprint());
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

    private boolean hasCompleteSignedManifest(SkitAppReleaseProfileDO profile) {
        return profile.getHotReleaseNo() != null && profile.getHotReleaseNo() > 0
                && profile.getNativeProtocolVersion() != null
                && profile.getNativeProtocolVersion() > 0
                && StrUtil.isAllNotBlank(profile.getHotVersion(), profile.getHotBundleUrl(),
                profile.getHotBundleSha256(), profile.getHotManifestSignature(),
                profile.getNativePackage(), profile.getMinNativeVersion(),
                profile.getRuntimeUpdatePublicKey(), profile.getRuntimeUpdateKeyFingerprint());
    }

    private boolean isHttpsBundleUrl(String value) {
        try {
            URI uri = URI.create(value);
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null
                    && uri.getUserInfo() == null && uri.getFragment() == null;
        } catch (RuntimeException invalidUrl) {
            return false;
        }
    }

    private long defaultReleaseNo(Long releaseNo) {
        return releaseNo == null ? 0L : releaseNo;
    }

}
