package cn.iocoder.yudao.module.skit.service.ad.callback;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class SkitProviderImpressionCaptureConfiguration {

    @Bean
    public SkitProviderImpressionWireParser skitProviderImpressionWireParser() {
        return new SkitProviderImpressionWireParser();
    }
}
