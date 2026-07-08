package cn.iocoder.yudao.module.skit.service.config;

import java.util.Map;

public interface SkitSystemConfigService {

    Map<String, Object> getConfig();

    void updateConfig(Map<String, Object> config);

    Map<String, Object> resetConfig();

}
