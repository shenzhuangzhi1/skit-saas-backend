package cn.iocoder.yudao.module.skit.service.config;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.module.skit.dal.dataobject.config.SkitSystemConfigDO;
import cn.iocoder.yudao.module.skit.dal.mysql.config.SkitSystemConfigMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import javax.annotation.Resource;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 短剧 SaaS 系统配置 Service 实现。
 */
@Service
@Validated
public class SkitSystemConfigServiceImpl implements SkitSystemConfigService {

    private static final Map<String, Object> DEFAULT_CONFIG = buildDefaultConfig();

    @Resource
    private SkitSystemConfigMapper skitSystemConfigMapper;
    @Resource
    private ObjectMapper objectMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> getConfig() {
        return new LinkedHashMap<>(fromJson(ensureConfig().getConfigData()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateConfig(Map<String, Object> config) {
        Map<String, Object> mergedConfig = normalizeConfig(getConfig());
        if (config != null) {
            config.forEach((key, value) -> {
                if (StrUtil.isNotBlank(key)) {
                    mergedConfig.put(key, value == null ? "" : value);
                }
            });
        }
        saveConfig(mergedConfig);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> resetConfig() {
        Map<String, Object> defaults = new LinkedHashMap<>(DEFAULT_CONFIG);
        saveConfig(defaults);
        return defaults;
    }

    private SkitSystemConfigDO ensureConfig() {
        SkitSystemConfigDO config = skitSystemConfigMapper.selectCurrentTenantConfig();
        if (config != null) {
            return config;
        }
        config = SkitSystemConfigDO.builder()
                .configData(toJson(DEFAULT_CONFIG))
                .build();
        skitSystemConfigMapper.insert(config);
        return config;
    }

    private void saveConfig(Map<String, Object> config) {
        SkitSystemConfigDO current = skitSystemConfigMapper.selectCurrentTenantConfig();
        SkitSystemConfigDO configDO = SkitSystemConfigDO.builder()
                .id(current != null ? current.getId() : null)
                .configData(toJson(normalizeConfig(config)))
                .build();
        if (current == null) {
            skitSystemConfigMapper.insert(configDO);
        } else {
            skitSystemConfigMapper.updateById(configDO);
        }
    }

    private Map<String, Object> normalizeConfig(Map<String, Object> config) {
        Map<String, Object> normalized = new LinkedHashMap<>(DEFAULT_CONFIG);
        if (config != null) {
            config.forEach((key, value) -> {
                if (StrUtil.isNotBlank(key)) {
                    normalized.put(key, value == null ? "" : value);
                }
            });
        }
        return normalized;
    }

    private String toJson(Map<String, Object> data) {
        try {
            return objectMapper.writeValueAsString(data == null ? DEFAULT_CONFIG : data);
        } catch (Exception e) {
            throw new IllegalArgumentException("短剧系统配置 JSON 序列化失败", e);
        }
    }

    private Map<String, Object> fromJson(String data) {
        if (StrUtil.isBlank(data)) {
            return new LinkedHashMap<>(DEFAULT_CONFIG);
        }
        try {
            return normalizeConfig(objectMapper.readValue(data, new TypeReference<LinkedHashMap<String, Object>>() {}));
        } catch (Exception e) {
            return new LinkedHashMap<>(DEFAULT_CONFIG);
        }
    }

    private static Map<String, Object> buildDefaultConfig() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("site_name", "短剧 SaaS 管理平台");
        config.put("site_title", "精准短剧");
        config.put("site_logo", "/favicon.ico");
        config.put("site_icp", "粤ICP备00000000号");
        config.put("site_copyright", "Copyright © 2026");
        config.put("upload_storage", "local");
        config.put("upload_max_size", "20MB");
        config.put("upload_exts", "jpg,png,gif,mp4,zip,pdf");
        config.put("upload_cdn_url", "");
        config.put("upload_callback_url", "/admin-api/skit/upload/callback");
        config.put("score_per_yuan", "1000");
        config.put("withdraw_min_amount", "1.00");
        config.put("withdraw_fee_rate", "0");
        config.put("withdraw_fixed_fee", "0");
        config.put("withdraw_review_mode", "人工审核");
        config.put("ad_base_score", "10");
        config.put("max_ad_score", "1000");
        config.put("self_commission_rate", "100");
        config.put("agent_commission_rate", "10");
        config.put("reward_enabled", "开启");
        config.put("sms_sign", "精准短剧");
        config.put("mail_host", "smtp.example.com");
        config.put("mail_username", "notice@example.com");
        config.put("mail_from", "notice@example.com");
        config.put("notify_webhook", "");
        return config;
    }

}
