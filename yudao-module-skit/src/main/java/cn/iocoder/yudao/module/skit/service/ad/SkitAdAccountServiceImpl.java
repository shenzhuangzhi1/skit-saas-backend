package cn.iocoder.yudao.module.skit.service.ad;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.enums.CommonStatusEnum;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.framework.tenant.core.util.TenantUtils;
import cn.iocoder.yudao.module.skit.dal.dataobject.ad.SkitAdAccountDO;
import cn.iocoder.yudao.module.skit.dal.mysql.ad.SkitAdAccountMapper;
import cn.iocoder.yudao.module.skit.framework.security.SkitPlatformAdminGuard;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.*;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.skit.enums.ErrorCodeConstants.AD_ACCOUNT_CONFIG_INVALID;
import static cn.iocoder.yudao.module.skit.enums.ErrorCodeConstants.AD_ACCOUNT_NOT_EXISTS;
import static cn.iocoder.yudao.module.skit.enums.ErrorCodeConstants.AD_ACCOUNT_REPORT_SCOPE_PENDING;
import static cn.iocoder.yudao.module.skit.enums.ErrorCodeConstants.AD_PROVIDER_INVALID;
import static cn.iocoder.yudao.module.skit.enums.SkitDomainConstants.PROVIDER_PANGLE;
import static cn.iocoder.yudao.module.skit.enums.SkitDomainConstants.PROVIDER_TAKU;

@Service
public class SkitAdAccountServiceImpl implements SkitAdAccountService {

    @Resource
    private SkitAdAccountMapper accountMapper;
    @Resource
    private ObjectMapper objectMapper;
    @Resource
    private SkitPlatformAdminGuard platformAdminGuard;

    @Override
    public Settings getSettings() {
        Settings result = new Settings();
        fillSettings(result, accountMapper.selectByProvider(PROVIDER_PANGLE));
        fillSettings(result, accountMapper.selectByProvider(PROVIDER_TAKU));
        return result;
    }

    @Override
    public Map<Long, Settings> getSettingsMapForPlatform(Collection<Long> tenantIds) {
        platformAdminGuard.check();
        Set<Long> requestedTenantIds = new LinkedHashSet<>();
        if (tenantIds != null) {
            tenantIds.stream().filter(Objects::nonNull).forEach(requestedTenantIds::add);
        }
        Map<Long, Settings> result = new LinkedHashMap<>();
        requestedTenantIds.forEach(tenantId -> result.put(tenantId, new Settings()));
        if (requestedTenantIds.isEmpty()) {
            return result;
        }
        List<SkitAdAccountDO> accounts = TenantUtils.executeIgnore(
                () -> accountMapper.selectListByTenantIds(requestedTenantIds));
        for (SkitAdAccountDO account : accounts) {
            Settings settings = result.get(account.getTenantId());
            if (settings != null) {
                fillSettings(settings, account);
            }
        }
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Settings saveSettings(Settings settings) {
        validateSettings(settings);
        ensureDefaultAccounts();
        saveProvider(PROVIDER_PANGLE, settings.getPangleUsername(), settings.getPangleAppId(), null,
                settings.getPangleAppSecret(), settings.getPanglePlacementId(), settings.getPangleEnabled());
        saveProvider(PROVIDER_TAKU, settings.getTakuUsername(), settings.getTakuAppId(), settings.getTakuAppKey(),
                settings.getTakuAppSecret(), settings.getTakuPlacementId(), settings.getTakuEnabled());
        return getSettings();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void clearCredentials(String provider) {
        if (StrUtil.isBlank(provider)) {
            throw exception(AD_PROVIDER_INVALID);
        }
        String normalizedProvider = StrUtil.trim(provider).toUpperCase(Locale.ROOT);
        if (!PROVIDER_PANGLE.equals(normalizedProvider) && !PROVIDER_TAKU.equals(normalizedProvider)) {
            throw exception(AD_PROVIDER_INVALID);
        }
        if (accountMapper.selectByProvider(normalizedProvider) == null) {
            throw exception(AD_ACCOUNT_NOT_EXISTS);
        }
        if (PROVIDER_PANGLE.equals(normalizedProvider)) {
            accountMapper.clearPangleCredentials();
        } else {
            accountMapper.clearTakuCredentials();
        }
    }

    @Override
    public List<PublicConfig> getEnabledPublicConfigs() {
        List<PublicConfig> result = new ArrayList<>();
        for (SkitAdAccountDO account : accountMapper.selectList()) {
            if (!CommonStatusEnum.ENABLE.getStatus().equals(account.getStatus())) {
                continue;
            }
            PublicConfig config = new PublicConfig();
            config.setProvider(account.getProvider());
            config.setAppId(account.getAppId());
            config.setPlacementId(readPlacementId(account.getConfigData()));
            config.setEnabled(true);
            // Provider client accounts are bound when the native SDK starts; each tenant needs its own build profile.
            config.setWhiteLabelRequired(true);
            result.add(config);
        }
        return result;
    }

    @Override
    public PublicConfig getEnabledPublicConfig(String provider) {
        return getEnabledPublicConfigs().stream()
                .filter(config -> config.getProvider().equalsIgnoreCase(provider))
                .findFirst().orElse(null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void ensureDefaultAccounts() {
        ensureProvider(PROVIDER_PANGLE);
        ensureProvider(PROVIDER_TAKU);
    }

    private void ensureProvider(String provider) {
        long tenantId = TenantContextHolder.getRequiredTenantId();
        // This method is also called while a platform admin is delegated into a
        // tenant. Use the explicit tenant-scoped lock instead of relying only on
        // the ambient MyBatis tenant interceptor, otherwise an older tenant with
        // no account rows can fail to initialize and the subsequent save reports
        // a generic "system error".
        if (accountMapper.selectByProviderForUpdate(tenantId, provider) != null) {
            return;
        }
        SkitAdAccountDO account = SkitAdAccountDO.builder().provider(provider).accountName("").accountId("")
                .appId("").appKey("").configData(writePlacementId(""))
                .status(CommonStatusEnum.DISABLE.getStatus()).build();
        // TenantLineInnerInterceptor fills SQL in normal requests, but the
        // entity must carry the tenant explicitly for delegated writes and for
        // deterministic tests/alternate mapper implementations.
        account.setTenantId(tenantId);
        accountMapper.insert(account);
    }

    private void saveProvider(String provider, String username, String appId, String appKey, String appSecret,
                              String placementId, Boolean enabled) {
        long tenantId = TenantContextHolder.getRequiredTenantId();
        SkitAdAccountDO account = accountMapper.selectByProviderForUpdate(tenantId, provider);
        if (account == null || !Objects.equals(account.getTenantId(), tenantId)
                || !Objects.equals(account.getProvider(), provider)) {
            throw exception(AD_ACCOUNT_NOT_EXISTS);
        }
        String normalizedUsername = trimToEmpty(username);
        String normalizedAppId = trimToEmpty(appId);
        String normalizedPlacementId = trimToEmpty(placementId);
        guardTakuReportScopeMutation(account, normalizedAppId, normalizedPlacementId);
        account.setAccountName(normalizedUsername);
        account.setAppId(normalizedAppId);
        account.setConfigData(writePlacementId(normalizedPlacementId));
        // 空值表示保留已经配置的凭证，避免编辑页面回显凭证。
        if (StrUtil.isNotBlank(appKey)) {
            account.setAppKey(appKey);
        }
        if (StrUtil.isNotBlank(appSecret)) {
            account.setSecret(appSecret);
        }
        if (Boolean.TRUE.equals(enabled)) {
            boolean credentialReady = PROVIDER_TAKU.equals(provider)
                    ? StrUtil.isNotBlank(account.getAppKey()) : StrUtil.isNotBlank(account.getSecret());
            if (!StrUtil.isAllNotBlank(account.getAccountName(), account.getAppId(), normalizedPlacementId)
                    || !credentialReady) {
                throw exception(AD_ACCOUNT_CONFIG_INVALID,
                        provider + " 启用前必须完整配置账号、App ID、广告位和凭证");
            }
        }
        account.setStatus(Boolean.TRUE.equals(enabled)
                ? CommonStatusEnum.ENABLE.getStatus() : CommonStatusEnum.DISABLE.getStatus());
        accountMapper.updateById(account);
    }

    private void guardTakuReportScopeMutation(SkitAdAccountDO account,
                                              String requestedAppId,
                                              String requestedPlacementId) {
        if (!PROVIDER_TAKU.equals(account.getProvider())) {
            return;
        }
        List<Object> current = reportScope(account.getAppId(), readPlacementId(account.getConfigData()),
                readAdFormat(account.getConfigData()), account.getReportTimezone(),
                account.getReportCurrency(), account.getReportAmountScale());
        List<Object> requested = reportScope(requestedAppId, requestedPlacementId,
                "rewarded_video", account.getReportTimezone(), account.getReportCurrency(),
                account.getReportAmountScale());
        if (!current.equals(requested)
                && accountMapper.hasHistoricalTakuReportFacts(account.getTenantId(), account.getId())) {
            throw exception(AD_ACCOUNT_REPORT_SCOPE_PENDING);
        }
    }

    private List<Object> reportScope(String appId, String placementId, String adFormat,
                                     String timezone, String currency, Integer amountScale) {
        return Arrays.asList(trimToEmpty(appId), trimToEmpty(placementId),
                StrUtil.blankToDefault(StrUtil.trim(adFormat), "rewarded_video"),
                StrUtil.blankToDefault(StrUtil.trim(timezone), "UTC+8"),
                StrUtil.blankToDefault(StrUtil.trim(currency), "USD"),
                amountScale == null ? 8 : amountScale);
    }

    private void validateSettings(Settings settings) {
        if (settings == null) {
            throw exception(AD_ACCOUNT_CONFIG_INVALID, "广告账号配置不能为空");
        }
        if (settings.getPangleEnabled() == null || settings.getTakuEnabled() == null) {
            throw exception(AD_ACCOUNT_CONFIG_INVALID, "广告平台启用状态不能为空");
        }
        validateLength(settings.getPangleUsername(), 128, "PANGLE 账号最长 128 个字符");
        validateLength(settings.getPangleAppId(), 128, "PANGLE App ID 最长 128 个字符");
        validateLength(settings.getPangleAppSecret(), 2048, "PANGLE 密钥最长 2048 个字符");
        validateLength(settings.getPanglePlacementId(), 128, "PANGLE 广告位最长 128 个字符");
        validateLength(settings.getTakuUsername(), 128, "TAKU 账号最长 128 个字符");
        validateLength(settings.getTakuAppId(), 128, "TAKU App ID 最长 128 个字符");
        validateLength(settings.getTakuAppKey(), 255, "TAKU App Key 最长 255 个字符");
        validateLength(settings.getTakuAppSecret(), 2048, "TAKU 服务端密钥最长 2048 个字符");
        validateLength(settings.getTakuPlacementId(), 128, "TAKU 广告位最长 128 个字符");
    }

    private void validateLength(String value, int maximum, String message) {
        if (value != null && value.length() > maximum) {
            throw exception(AD_ACCOUNT_CONFIG_INVALID, message);
        }
    }

    private String trimToEmpty(String value) {
        return StrUtil.nullToEmpty(StrUtil.trim(value));
    }

    private void fillSettings(Settings result, SkitAdAccountDO account) {
        if (account == null) {
            return;
        }
        boolean enabled = CommonStatusEnum.ENABLE.getStatus().equals(account.getStatus());
        boolean secretConfigured = StrUtil.isNotBlank(account.getSecret());
        if (PROVIDER_PANGLE.equals(account.getProvider())) {
            result.setPangleUsername(account.getAccountName());
            result.setPangleAppId(account.getAppId());
            result.setPanglePlacementId(readPlacementId(account.getConfigData()));
            result.setPangleEnabled(enabled);
            result.setPangleSecretConfigured(secretConfigured);
        } else if (PROVIDER_TAKU.equals(account.getProvider())) {
            result.setTakuUsername(account.getAccountName());
            result.setTakuAppId(account.getAppId());
            result.setTakuAppKeyConfigured(StrUtil.isNotBlank(account.getAppKey()));
            result.setTakuPlacementId(readPlacementId(account.getConfigData()));
            result.setTakuEnabled(enabled);
            result.setTakuSecretConfigured(secretConfigured);
        }
    }

    private String writePlacementId(String placementId) {
        try {
            Map<String, String> config = new LinkedHashMap<>();
            config.put("placementId", placementId);
            config.put("adFormat", "rewarded_video");
            return objectMapper.writeValueAsString(config);
        } catch (Exception ex) {
            throw new IllegalArgumentException("广告位配置序列化失败", ex);
        }
    }

    private String readPlacementId(String json) {
        if (StrUtil.isBlank(json)) {
            return "";
        }
        try {
            Map<String, Object> config = objectMapper.readValue(json, new TypeReference<Map<String, Object>>() { });
            return String.valueOf(config.getOrDefault("placementId", ""));
        } catch (Exception ex) {
            return "";
        }
    }

    private String readAdFormat(String json) {
        if (StrUtil.isBlank(json)) {
            return "rewarded_video";
        }
        try {
            Map<String, Object> config = objectMapper.readValue(json,
                    new TypeReference<Map<String, Object>>() { });
            return String.valueOf(config.getOrDefault("adFormat", "rewarded_video"));
        } catch (Exception ex) {
            return "rewarded_video";
        }
    }

}
