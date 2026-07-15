package cn.iocoder.yudao.framework.encrypt.config;

import cn.iocoder.yudao.framework.web.config.WebProperties;
import cn.iocoder.yudao.framework.web.core.handler.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class YudaoApiEncryptAutoConfigurationSecurityTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(YudaoApiEncryptAutoConfiguration.class))
            .withPropertyValues("yudao.web.admin-ui.url=http://localhost")
            .withBean(WebProperties.class, WebProperties::new)
            .withBean(RequestMappingHandlerMapping.class,
                    () -> mock(RequestMappingHandlerMapping.class))
            .withBean(GlobalExceptionHandler.class,
                    () -> mock(GlobalExceptionHandler.class));

    @Test
    void disabledAllowsBlankKeys() {
        runner.withPropertyValues(
                        "yudao.api-encrypt.enable=false",
                        "yudao.api-encrypt.algorithm=AES",
                        "yudao.api-encrypt.request-key=",
                        "yudao.api-encrypt.response-key=")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(ApiEncryptProperties.class);
                    assertThat(context).doesNotHaveBean("apiEncryptFilter");
                });
    }

    @Test
    void enabledRejectsBlankKeys() {
        runner.withPropertyValues(
                        "yudao.api-encrypt.enable=true",
                        "yudao.api-encrypt.algorithm=AES",
                        "yudao.api-encrypt.request-key=",
                        "yudao.api-encrypt.response-key=")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(rootCause(context.getStartupFailure()))
                            .isInstanceOf(BindValidationException.class)
                            .hasMessageContaining("requestKey")
                            .hasMessageContaining("responseKey");
                });
    }

    @Test
    void enabledRegistersFilterWithValidKeys() {
        runner.withPropertyValues(
                        "yudao.api-encrypt.enable=true",
                        "yudao.api-encrypt.algorithm=AES",
                        "yudao.api-encrypt.request-key=" + key('a'),
                        "yudao.api-encrypt.response-key=" + key('b'))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(ApiEncryptProperties.class);
                    assertThat(context).hasBean("apiEncryptFilter");
                });
    }

    private static String key(char value) {
        char[] characters = new char[32];
        Arrays.fill(characters, value);
        return new String(characters);
    }

    private static Throwable rootCause(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

}
