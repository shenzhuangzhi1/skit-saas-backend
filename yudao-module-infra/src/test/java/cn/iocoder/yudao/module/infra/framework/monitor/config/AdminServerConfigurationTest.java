package cn.iocoder.yudao.module.infra.framework.monitor.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.context.annotation.Profile;

import static org.assertj.core.api.Assertions.assertThat;

class AdminServerConfigurationTest {

    @Test
    void embeddedAdminServerIsExcludedFromProductionProfile() {
        Profile profile = AnnotatedElementUtils.findMergedAnnotation(
                AdminServerConfiguration.class, Profile.class);

        assertThat(profile).isNotNull();
        assertThat(profile.value()).containsExactly("!prod");
    }

}
