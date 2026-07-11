package cn.iocoder.yudao.module.skit.framework.security;

import cn.iocoder.yudao.framework.web.config.WebProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Registers the Skit member scope firewall after token authentication and before controller dispatch. */
@Configuration(proxyBeanMethods = false)
public class SkitWebSecurityConfiguration implements WebMvcConfigurer {

    private final WebProperties webProperties;

    public SkitWebSecurityConfiguration(WebProperties webProperties) {
        this.webProperties = webProperties;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new SkitMemberScopeInterceptor(webProperties))
                .addPathPatterns("/**")
                .order(-100);
    }
}
