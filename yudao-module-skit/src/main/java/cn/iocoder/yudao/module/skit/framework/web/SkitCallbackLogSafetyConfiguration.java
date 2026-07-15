package cn.iocoder.yudao.module.skit.framework.web;

import cn.iocoder.yudao.framework.common.enums.WebFilterOrderEnum;
import cn.iocoder.yudao.framework.web.config.WebProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class SkitCallbackLogSafetyConfiguration {

    @Bean
    public FilterRegistrationBean<SkitCallbackSecretSanitizingFilter> skitCallbackSecretSanitizingFilter(
            WebProperties webProperties) {
        SkitCallbackSecretSanitizingFilter filter = new SkitCallbackSecretSanitizingFilter(webProperties);
        FilterRegistrationBean<SkitCallbackSecretSanitizingFilter> registration =
                new FilterRegistrationBean<>(filter);
        registration.setName("skitCallbackSecretSanitizingFilter");
        registration.setOrder(WebFilterOrderEnum.SENSITIVE_REQUEST_SANITIZER_FILTER);
        return registration;
    }

}
