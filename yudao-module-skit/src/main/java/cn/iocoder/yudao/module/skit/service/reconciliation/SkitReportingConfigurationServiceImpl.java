package cn.iocoder.yudao.module.skit.service.reconciliation;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.skit.dal.dataobject.ad.SkitAdAccountDO;
import cn.iocoder.yudao.module.skit.dal.mysql.ad.SkitAdAccountMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.skit.enums.ErrorCodeConstants.AD_ACCOUNT_CONFIG_INVALID;
import static cn.iocoder.yudao.module.skit.enums.ErrorCodeConstants.AD_ACCOUNT_NOT_EXISTS;
import static cn.iocoder.yudao.module.skit.enums.ErrorCodeConstants.AD_ACCOUNT_REPORT_SCOPE_PENDING;
import static cn.iocoder.yudao.module.skit.enums.ErrorCodeConstants.AD_REPORTING_CONFIG_VERSION_CONFLICT;

@Service
public class SkitReportingConfigurationServiceImpl implements SkitReportingConfigurationService {

    private static final String PROVIDER = "TAKU";
    private static final String AD_FORMAT = "rewarded_video";

    private final SkitAdAccountMapper accountMapper;
    private final SkitReportingCredentialService credentialService;
    private final ObjectMapper objectMapper;

    public SkitReportingConfigurationServiceImpl(SkitAdAccountMapper accountMapper,
                                                 SkitReportingCredentialService credentialService,
                                                 ObjectMapper objectMapper) {
        this.accountMapper = Objects.requireNonNull(accountMapper, "accountMapper");
        this.credentialService = Objects.requireNonNull(credentialService, "credentialService");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    @Override
    public View getConfiguration() {
        long tenantId = TenantContextHolder.getRequiredTenantId();
        SkitAdAccountDO account = accountMapper.selectByProvider(PROVIDER);
        requireAccount(account, tenantId);
        return view(account, credentialService.getMetadata(tenantId, account.getId()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public View configure(Command command) {
        validateCommand(command);
        long tenantId = TenantContextHolder.getRequiredTenantId();
        SkitAdAccountDO account = accountMapper.selectByProviderForUpdate(tenantId, PROVIDER);
        requireAccount(account, tenantId);
        if (account.getReportPullLeaseOwner() != null || account.getReportPullLeaseUntil() != null) {
            throw exception(AD_REPORTING_CONFIG_VERSION_CONFLICT);
        }

        SkitReportingCredentialService.Metadata current =
                credentialService.getMetadata(tenantId, account.getId());
        int currentVersion = current == null ? 0 : current.getVersion();
        if (!Objects.equals(command.getCredentialVersion(), currentVersion)) {
            throw exception(AD_REPORTING_CONFIG_VERSION_CONFLICT);
        }

        Scope currentScope = scope(account);
        Scope requestedScope = new Scope(command.getReportTimezone(), command.getCurrency(),
                command.getAmountScale(), command.getAdFormat());
        if (!currentScope.equals(requestedScope)
                && accountMapper.hasHistoricalTakuReportFacts(tenantId, account.getId())) {
            throw exception(AD_ACCOUNT_REPORT_SCOPE_PENDING);
        }

        account.setReportTimezone(requestedScope.timezone);
        account.setReportCurrency(requestedScope.currency);
        account.setReportAmountScale(requestedScope.amountScale);
        account.setConfigData(writeAdFormat(account.getConfigData(), requestedScope.adFormat));
        if (accountMapper.updateById(account) != 1) {
            throw new IllegalStateException("Taku reporting account configuration was not updated");
        }

        SkitReportingCredentialService.Metadata configured = current;
        if (command.getPublisherKey() != null) {
            String publisherKey = command.getPublisherKey();
            if (StrUtil.isBlank(publisherKey) || publisherKey.length() > 4096
                    || !publisherKey.equals(publisherKey.trim())) {
                throw exception(AD_ACCOUNT_CONFIG_INVALID,
                        "Publisher Key 必须为 1 到 4096 个非空字符且不能包含首尾空格");
            }
            byte[] plaintext = publisherKey.getBytes(StandardCharsets.UTF_8);
            try {
                configured = credentialService.configure(tenantId, account.getId(), plaintext);
            } finally {
                Arrays.fill(plaintext, (byte) 0);
            }
        } else if (current == null) {
            throw exception(AD_ACCOUNT_CONFIG_INVALID, "首次配置必须填写 Publisher Key");
        }
        return view(account, configured);
    }

    private void validateCommand(Command command) {
        if (command == null || command.getCredentialVersion() == null
                || command.getCredentialVersion() < 0
                || !Arrays.asList("UTC-8", "UTC+8", "UTC+0")
                .contains(command.getReportTimezone())
                || command.getCurrency() == null
                || !command.getCurrency().matches("[A-Z]{3}")
                || command.getAmountScale() == null || command.getAmountScale() < 0
                || command.getAmountScale() > 18
                || !AD_FORMAT.equals(command.getAdFormat())) {
            throw exception(AD_ACCOUNT_CONFIG_INVALID, "Taku 报表范围配置不合法");
        }
    }

    private View view(SkitAdAccountDO account, SkitReportingCredentialService.Metadata metadata) {
        Map<String, Object> config = readConfig(account.getConfigData());
        return new View().setTenantId(account.getTenantId()).setAdAccountId(account.getId())
                .setAppId(account.getAppId())
                .setPlacementId(canonicalConfig(config, "placementId", 128))
                .setAdFormat(canonicalConfig(config, "adFormat", 32))
                .setReportTimezone(account.getReportTimezone())
                .setCurrency(account.getReportCurrency())
                .setAmountScale(account.getReportAmountScale())
                .setCredentialConfigured(metadata != null && metadata.isActive())
                .setCredentialVersion(metadata == null ? 0 : metadata.getVersion())
                .setPermissionVerifiedAt(metadata == null ? null : metadata.getPermissionVerifiedAt());
    }

    private Scope scope(SkitAdAccountDO account) {
        Map<String, Object> config = readConfig(account.getConfigData());
        return new Scope(account.getReportTimezone(), account.getReportCurrency(),
                account.getReportAmountScale(), canonicalConfig(config, "adFormat", 32));
    }

    private String writeAdFormat(String configData, String adFormat) {
        Map<String, Object> config = readConfig(configData);
        config.put("adFormat", adFormat);
        try {
            return objectMapper.writeValueAsString(config);
        } catch (Exception failure) {
            throw exception(AD_ACCOUNT_CONFIG_INVALID, "Taku 广告账号配置无法序列化");
        }
    }

    private Map<String, Object> readConfig(String configData) {
        if (StrUtil.isBlank(configData)) {
            throw exception(AD_ACCOUNT_CONFIG_INVALID, "Taku 广告账号缺少广告位配置");
        }
        try {
            Map<String, Object> parsed = objectMapper.readValue(configData,
                    new TypeReference<Map<String, Object>>() { });
            return new LinkedHashMap<>(parsed);
        } catch (Exception failure) {
            throw exception(AD_ACCOUNT_CONFIG_INVALID, "Taku 广告账号配置格式不合法");
        }
    }

    private String canonicalConfig(Map<String, Object> config, String key, int maximum) {
        Object raw = config.get(key);
        String value = raw == null ? "" : String.valueOf(raw);
        if (value.isEmpty() || value.length() > maximum || !value.equals(value.trim())) {
            throw exception(AD_ACCOUNT_CONFIG_INVALID, "Taku 广告账号报表范围不完整");
        }
        return value;
    }

    private void requireAccount(SkitAdAccountDO account, long tenantId) {
        if (account == null || !Objects.equals(account.getTenantId(), tenantId)
                || !Objects.equals(account.getProvider(), PROVIDER)
                || account.getId() == null || account.getId() <= 0) {
            throw exception(AD_ACCOUNT_NOT_EXISTS);
        }
    }

    private static final class Scope {
        private final String timezone;
        private final String currency;
        private final int amountScale;
        private final String adFormat;

        private Scope(String timezone, String currency, Integer amountScale, String adFormat) {
            this.timezone = timezone;
            this.currency = currency;
            this.amountScale = amountScale == null ? -1 : amountScale;
            this.adFormat = adFormat;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof Scope)) {
                return false;
            }
            Scope value = (Scope) other;
            return amountScale == value.amountScale && Objects.equals(timezone, value.timezone)
                    && Objects.equals(currency, value.currency)
                    && Objects.equals(adFormat, value.adFormat);
        }

        @Override
        public int hashCode() {
            return Objects.hash(timezone, currency, amountScale, adFormat);
        }
    }
}
