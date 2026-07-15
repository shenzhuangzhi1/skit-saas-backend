package cn.iocoder.yudao.module.skit.framework.crypto;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Profiles;

/** Validates production key material before the application context creates crypto or data beans. */
public final class SkitAdProductionKeyEnvironmentPostProcessor
        implements EnvironmentPostProcessor, Ordered {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (environment.acceptsProfiles(Profiles.of("prod"))) {
            new SkitAdProductionKeyValidator(environment).validate();
        }
    }

    @Override
    public int getOrder() {
        // Config data is loaded at HIGHEST_PRECEDENCE + 10. Run after it, before bean creation.
        return Ordered.HIGHEST_PRECEDENCE + 100;
    }

}
