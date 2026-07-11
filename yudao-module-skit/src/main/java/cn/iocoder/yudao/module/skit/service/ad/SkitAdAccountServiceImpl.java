package cn.iocoder.yudao.module.skit.service.ad;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.enums.CommonStatusEnum;
import cn.iocoder.yudao.module.skit.dal.dataobject.ad.SkitAdAccountDO;
import cn.iocoder.yudao.module.skit.dal.mysql.ad.SkitAdAccountMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.*;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.skit.enums.ErrorCodeConstants.AD_ACCOUNT_CONFIG_INVALID;
import static cn.iocoder.yudao.module.skit.enums.SkitDomainConstants.PROVIDER_PANGLE;
import static cn.iocoder.yudao.module.skit.enums.SkitDomainConstants.PROVIDER_TAKU;

@Service
public class SkitAdAccountServiceImpl implements SkitAdAccountService {

    @Resource
    private SkitAdAccountMapper accountMapper;
    @Resource
    private ObjectMapper objectMapper;

    @Override
    public Settings getSettings() {
        Settings result = new Settings();
        fillSettings(result, accountMapper.selectByProvider(PROVIDER_PANGLE));
        fillSettings(result, accountMapper.selectByProvider(PROVIDER_TAKU));
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Settings saveSettings(Settings settings) {
        ensureDefaultAccounts();
        saveProvider(PROVIDER_PANGLE, settings.getPangleUsername(), settings.getPangleAppId(), null,
                settings.getPangleAppSecret(), settings.getPanglePlacementId(), settings.getPangleEnabled());
        saveProvider(PROVIDER_TAKU, settings.getTakuUsername(), settings.getTakuAppId(), settings.getTakuAppKey(),
                settings.getTakuAppSecret(), settings.getTakuPlacementId(), settings.getTakuEnabled());
        return getSettings();
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
        if (accountMapper.selectByProvider(provider) != null) {
            return;
        }
        accountMapper.insert(SkitAdAccountDO.builder().provider(provider).accountName("").accountId("")
                .appId("").appKey("").configData(writePlacementId(""))
                .status(CommonStatusEnum.DISABLE.getStatus()).build());
    }

    private void saveProvider(String provider, String username, String appId, String appKey, String appSecret,
                              String placementId, Boolean enabled) {
        SkitAdAccountDO account = accountMapper.selectByProvider(provider);
        account.setAccountName(StrUtil.nullToEmpty(username));
        account.setAppId(StrUtil.nullToEmpty(appId));
        account.setConfigData(writePlacementId(StrUtil.nullToEmpty(placementId)));
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
            if (!StrUtil.isAllNotBlank(account.getAccountName(), account.getAppId(), placementId)
                    || !credentialReady) {
                throw exception(AD_ACCOUNT_CONFIG_INVALID,
                        provider + " 启用前必须完整配置账号、App ID、广告位和凭证");
            }
        }
        account.setStatus(Boolean.TRUE.equals(enabled)
                ? CommonStatusEnum.ENABLE.getStatus() : CommonStatusEnum.DISABLE.getStatus());
        accountMapper.updateById(account);
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
            return objectMapper.writeValueAsString(Collections.singletonMap("placementId", placementId));
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

}
