package cn.iocoder.yudao.module.skit.service.app;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.module.skit.dal.dataobject.app.SkitAppBuildMaterialDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.app.SkitAppReleaseProfileDO;
import cn.iocoder.yudao.module.skit.dal.mysql.app.SkitAppBuildMaterialMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.app.SkitAppReleaseProfileMapper;
import cn.iocoder.yudao.module.skit.framework.crypto.SkitAdCredentialCryptoService;
import cn.iocoder.yudao.module.skit.service.ad.SkitAdAccountService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Base64;
import java.util.Iterator;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.skit.enums.ErrorCodeConstants.AD_ACCOUNT_CONFIG_INVALID;
import static cn.iocoder.yudao.module.skit.enums.ErrorCodeConstants.APP_BUILD_MATERIAL_INVALID;
import static cn.iocoder.yudao.module.skit.enums.ErrorCodeConstants.APP_RELEASE_PROFILE_INVALID;

@Service
public class SkitAppBuildMaterialServiceImpl implements SkitAppBuildMaterialService {

    private static final int MAX_PANGLE_SETTINGS_LENGTH = 64 * 1024;
    private static final int MAX_KEYSTORE_BASE64_LENGTH = 16 * 1024 * 1024;

    @Resource
    private SkitAppBuildMaterialMapper materialMapper;
    @Resource
    private SkitAppReleaseProfileMapper releaseProfileMapper;
    @Resource
    private SkitAdAccountService adAccountService;
    @Resource
    private SkitAdCredentialCryptoService credentialCrypto;
    @Resource
    private ObjectMapper objectMapper;

    @Override
    public MaterialView getMaterial(Long tenantId) {
        requireTenantId(tenantId);
        SkitAppBuildMaterialDO active = materialMapper.selectActive(tenantId);
        SkitAppReleaseProfileDO profile = releaseProfileMapper.selectByTenantId(tenantId);
        SkitAdAccountService.Settings settings = adAccountService.getSettings();
        SecretBundle bundle = active == null ? new SecretBundle() : decryptBundle(tenantId, active);
        return toView(active, bundle, profile, settings, tenantId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MaterialView saveMaterial(MaterialCommand command) {
        if (command == null) {
            throw invalid("构建资料不能为空");
        }
        Long tenantId = requireTenantId(command.getTenantId());
        SkitAppReleaseProfileDO profile = releaseProfileMapper.selectByTenantIdForUpdate(tenantId);
        if (profile == null) {
            throw exception(APP_RELEASE_PROFILE_INVALID, "请先创建 App 发布档案");
        }
        SkitAdAccountService.Settings settings = adAccountService.getSettings();
        validateTenantAdAccounts(settings);
        SkitAppBuildMaterialDO active = materialMapper.selectActiveForUpdate(tenantId);
        SkitAppBuildMaterialDO latest = materialMapper.selectLatestForUpdate(tenantId);
        int nextVersion = latest == null || latest.getMaterialVersion() == null
                ? 1 : latest.getMaterialVersion() + 1;
        validateMetadata(command, active == null ? latest : active);

        SecretBundle bundle = active == null ? new SecretBundle() : decryptBundle(tenantId, active);
        mergeSecrets(bundle, command);
        validateSecretBundle(bundle, profile);
        SkitAdCredentialCryptoService.EncryptedSecret encrypted = encryptBundle(tenantId, nextVersion, bundle);

        if (active != null && materialMapper.retireActive(tenantId, active.getId()) != 1) {
            throw invalid("构建资料版本已发生变化，请刷新后重试");
        }
        SkitAppBuildMaterialDO row = new SkitAppBuildMaterialDO();
        row.setTenantId(tenantId);
        row.setMaterialVersion(nextVersion);
        row.setApiBaseUrl(normalize(command.getApiBaseUrl()));
        row.setAppName(normalize(command.getAppName()));
        row.setNativeVersionCode(command.getNativeVersionCode());
        row.setNativeVersionName(normalize(command.getNativeVersionName()));
        row.setRuntimeReleaseNo(command.getRuntimeReleaseNo());
        row.setSecretCiphertext(encrypted.getCiphertext());
        row.setSecretNonce(encrypted.getNonce());
        row.setEncryptionKeyId(encrypted.getKeyId());
        row.setEnvelopeVersion(encrypted.getEnvelopeVersion());
        row.setActive(true);
        row.setVerifiedAt(LocalDateTime.now());
        materialMapper.insert(row);
        return toView(row, bundle, profile, settings, tenantId);
    }

    private void validateMetadata(MaterialCommand command, SkitAppBuildMaterialDO previous) {
        String apiBaseUrl = normalize(command.getApiBaseUrl());
        if (!apiBaseUrl.matches("^https://[^\\s]+$")) {
            throw invalid("API 地址必须使用 HTTPS");
        }
        requireLength(command.getAppName(), 1, 128, "应用名称长度必须为 1 到 128 个字符");
        if (command.getNativeVersionCode() == null || command.getNativeVersionCode() < 1
                || command.getNativeVersionCode() > 2_100_000_000L) {
            throw invalid("原生 versionCode 必须在 1 到 2100000000 之间");
        }
        String nativeVersionName = normalize(command.getNativeVersionName());
        if (!nativeVersionName.matches("^[0-9]+(\\.[0-9]+){1,3}([.-][A-Za-z0-9._-]+)?$")) {
            throw invalid("原生版本名称格式不正确，例如 2.3.0");
        }
        if (command.getRuntimeReleaseNo() == null || command.getRuntimeReleaseNo() < 1) {
            throw invalid("运行时发布序号必须为正整数");
        }
        if (previous != null && previous.getRuntimeReleaseNo() != null
                && command.getRuntimeReleaseNo() <= previous.getRuntimeReleaseNo()) {
            throw invalid("运行时发布序号不能回退或重复");
        }
    }

    private void validateTenantAdAccounts(SkitAdAccountService.Settings settings) {
        if (settings == null || !Boolean.TRUE.equals(settings.getTakuAppKeyConfigured())
                || !allNotBlank(settings.getTakuUsername(), settings.getTakuAppId(), settings.getTakuPlacementId())) {
            throw exception(AD_ACCOUNT_CONFIG_INVALID, "Taku 客户端 App Key、账号、App ID 和广告位必须先配置");
        }
    }

    private void mergeSecrets(SecretBundle bundle, MaterialCommand command) {
        if (StrUtil.isNotBlank(command.getPangleSettingsJson())) {
            bundle.setPangleSettingsJson(command.getPangleSettingsJson().trim());
        }
        if (StrUtil.isNotBlank(command.getReleaseKeystoreBase64())) {
            bundle.setReleaseKeystoreBase64(command.getReleaseKeystoreBase64().replaceAll("\\s+", ""));
        }
        if (StrUtil.isNotBlank(command.getStorePassword())) {
            bundle.setStorePassword(command.getStorePassword());
        }
        if (StrUtil.isNotBlank(command.getKeyAlias())) {
            bundle.setKeyAlias(command.getKeyAlias().trim());
        }
        if (StrUtil.isNotBlank(command.getKeyPassword())) {
            bundle.setKeyPassword(command.getKeyPassword());
        }
    }

    private void validateSecretBundle(SecretBundle bundle, SkitAppReleaseProfileDO profile) {
        if (StrUtil.isBlank(bundle.getPangleSettingsJson())
                || bundle.getPangleSettingsJson().length() > MAX_PANGLE_SETTINGS_LENGTH) {
            throw invalid("穿山甲 SDK 设置文件未配置或超过 64KB");
        }
        validatePangleSettings(bundle.getPangleSettingsJson(), profile.getNativePackage());
        if (!allNotBlank(bundle.getReleaseKeystoreBase64(), bundle.getStorePassword(), bundle.getKeyAlias(),
                bundle.getKeyPassword())) {
            throw invalid("发布 keystore、store password、alias 和 key password 必须完整配置");
        }
        if (bundle.getReleaseKeystoreBase64().length() > MAX_KEYSTORE_BASE64_LENGTH) {
            throw invalid("发布 keystore 文件过大");
        }
        try {
            byte[] keystore = Base64.getDecoder().decode(bundle.getReleaseKeystoreBase64());
            if (keystore.length == 0) {
                throw invalid("发布 keystore 不能为空");
            }
        } catch (IllegalArgumentException exception) {
            throw invalid("发布 keystore 必须是合法 Base64");
        }
    }

    private void validatePangleSettings(String json, String nativePackage) {
        try {
            JsonNode root = objectMapper.readTree(json);
            if (root == null || !root.isObject()) {
                throw invalid("穿山甲设置文件不是 JSON 对象");
            }
            String siteId = root.path("init").path("site_id").asText("").trim();
            String appId = root.path("init").path("app_id").asText("").trim();
            if (siteId.isEmpty() || appId.isEmpty() || !root.path("license_config").isArray()) {
                throw invalid("穿山甲设置文件缺少 init.site_id、init.app_id 或 license_config");
            }
            if (StrUtil.isNotBlank(nativePackage)) {
                boolean packageMatched = false;
                Iterator<JsonNode> licenses = root.path("license_config").elements();
                while (licenses.hasNext()) {
                    if (nativePackage.equals(licenses.next().path("PackageName").asText(""))) {
                        packageMatched = true;
                        break;
                    }
                }
                if (!packageMatched) {
                    throw invalid("穿山甲 license_config 未包含当前原生包名");
                }
            }
        } catch (RuntimeException exception) {
            throw exception;
        } catch (Exception exception) {
            throw invalid("穿山甲 SDK 设置文件不是合法 JSON");
        }
    }

    private SecretBundle decryptBundle(Long tenantId, SkitAppBuildMaterialDO row) {
        if (row.getSecretCiphertext() == null || row.getSecretNonce() == null
                || row.getEncryptionKeyId() == null || row.getEnvelopeVersion() == null) {
            throw invalid("构建资料密文封装不完整");
        }
        SkitAdCredentialCryptoService.EncryptedSecret encrypted =
                new SkitAdCredentialCryptoService.EncryptedSecret(row.getSecretCiphertext(), row.getSecretNonce(),
                        row.getEncryptionKeyId(), row.getEnvelopeVersion());
        byte[] plaintext = credentialCrypto.decrypt(
                SkitAdCredentialCryptoService.Context.appBuildMaterial(tenantId,
                        row.getMaterialVersion(), row.getEnvelopeVersion()), encrypted);
        try {
            return objectMapper.readValue(plaintext, SecretBundle.class);
        } catch (Exception exception) {
            throw invalid("构建资料密文内容无法解析");
        } finally {
            Arrays.fill(plaintext, (byte) 0);
        }
    }

    private SkitAdCredentialCryptoService.EncryptedSecret encryptBundle(Long tenantId, int version,
                                                                          SecretBundle bundle) {
        byte[] plaintext;
        try {
            plaintext = objectMapper.writeValueAsBytes(bundle);
        } catch (Exception exception) {
            throw invalid("构建资料密文内容无法序列化");
        }
        try {
            return credentialCrypto.encrypt(
                    SkitAdCredentialCryptoService.Context.appBuildMaterial(tenantId, version,
                            SkitAdCredentialCryptoService.CURRENT_ENVELOPE_VERSION), plaintext);
        } finally {
            Arrays.fill(plaintext, (byte) 0);
        }
    }

    private MaterialView toView(SkitAppBuildMaterialDO row, SecretBundle bundle,
                                SkitAppReleaseProfileDO profile,
                                SkitAdAccountService.Settings settings, Long tenantId) {
        MaterialView view = new MaterialView();
        view.setTenantId(tenantId);
        if (row != null) {
            view.setMaterialVersion(row.getMaterialVersion());
            view.setApiBaseUrl(row.getApiBaseUrl());
            view.setAppName(row.getAppName());
            view.setNativeVersionCode(row.getNativeVersionCode());
            view.setNativeVersionName(row.getNativeVersionName());
            view.setRuntimeReleaseNo(row.getRuntimeReleaseNo());
            view.setVerifiedAt(row.getVerifiedAt());
        } else {
            view.setMaterialVersion(0);
            view.setApiBaseUrl("");
            view.setAppName("");
            view.setNativeVersionCode(1L);
            view.setNativeVersionName("");
            view.setRuntimeReleaseNo(1L);
        }
        view.setPangleSettingsConfigured(StrUtil.isNotBlank(bundle.getPangleSettingsJson()));
        view.setSigningConfigured(allNotBlank(bundle.getReleaseKeystoreBase64(), bundle.getStorePassword(),
                bundle.getKeyAlias(), bundle.getKeyPassword()));
        view.setTakuAppKeyConfigured(settings != null && Boolean.TRUE.equals(settings.getTakuAppKeyConfigured()));
        view.setTakuAccountConfigured(settings != null && allNotBlank(settings.getTakuUsername(),
                settings.getTakuAppId(), settings.getTakuPlacementId()));
        view.setAppReleaseProfileConfigured(profile != null && StrUtil.isNotBlank(profile.getNativePackage())
                && profile.getNativeProtocolVersion() != null && profile.getNativeProtocolVersion() > 0);
        return view;
    }

    private Long requireTenantId(Long tenantId) {
        if (tenantId == null || tenantId <= 0) {
            throw invalid("租户不能为空");
        }
        return tenantId;
    }

    private void requireLength(String value, int min, int max, String message) {
        String normalized = normalize(value);
        if (normalized.length() < min || normalized.length() > max) {
            throw invalid(message);
        }
    }

    private boolean allNotBlank(String... values) {
        for (String value : values) {
            if (StrUtil.isBlank(value)) {
                return false;
            }
        }
        return true;
    }

    private String normalize(String value) {
        return StrUtil.nullToEmpty(StrUtil.trim(value));
    }

    private RuntimeException invalid(String message) {
        return exception(APP_BUILD_MATERIAL_INVALID, message);
    }

    @Data
    private static class SecretBundle {
        private String pangleSettingsJson;
        private String releaseKeystoreBase64;
        private String storePassword;
        private String keyAlias;
        private String keyPassword;
    }
}
