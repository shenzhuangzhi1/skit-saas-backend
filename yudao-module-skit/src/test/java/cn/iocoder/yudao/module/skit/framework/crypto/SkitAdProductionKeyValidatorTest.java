package cn.iocoder.yudao.module.skit.framework.crypto;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.io.support.SpringFactoriesLoader;
import org.springframework.mock.env.MockEnvironment;

import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SkitAdProductionKeyValidatorTest {

    private static final String FIELD_KEY = "12345678901234567890123456789012";
    private static final String CREDENTIAL_KEY = "abcdefghijklmnopqrstuvwx12345678";
    private static final String SESSION_KEY = "session-token-key-123456789012345";

    @Test
    void acceptsIndependentCurrentKeysWithValidIdentifiers() {
        assertThatCode(() -> new SkitAdProductionKeyValidator(validEnvironment()).validate())
                .doesNotThrowAnyException();
    }

    @Test
    void environmentPostProcessorValidatesProdEvenWhenItIsTheSecondProfile() {
        MockEnvironment nonProduction = new MockEnvironment();
        nonProduction.setActiveProfiles("runtime");
        assertThatCode(() -> new SkitAdProductionKeyEnvironmentPostProcessor()
                .postProcessEnvironment(nonProduction, null)).doesNotThrowAnyException();

        MockEnvironment production = new MockEnvironment();
        production.setActiveProfiles("runtime", "prod");
        assertThatThrownBy(() -> new SkitAdProductionKeyEnvironmentPostProcessor()
                .postProcessEnvironment(production, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("production advertising key configuration");
    }

    @Test
    void environmentPostProcessorIsRegisteredForRealApplicationStartup() {
        assertThat(SpringFactoriesLoader.loadFactoryNames(EnvironmentPostProcessor.class,
                SkitAdProductionKeyValidatorTest.class.getClassLoader()))
                .contains(SkitAdProductionKeyEnvironmentPostProcessor.class.getName());
    }

    @Test
    void rejectsMissingMalformedOrReusedCurrentMaterialWithoutEchoingValues() {
        List<Consumer<MockEnvironment>> invalidCases = Arrays.asList(
                environment -> environment.setProperty("mybatis-plus.encryptor.password", ""),
                environment -> environment.setProperty("mybatis-plus.encryptor.password", "too-short"),
                environment -> environment.setProperty("mybatis-plus.encryptor.password",
                        "123456789012345678901234567890中"),
                environment -> environment.setProperty("skit.ad.credential-encryption.current-key", FIELD_KEY),
                environment -> environment.setProperty("skit.ad.credential-encryption.current-key-id", "bad key id"),
                environment -> environment.setProperty("skit.ad.session-token.current-key", "short-session-key"),
                environment -> environment.setProperty("skit.ad.session-token.current-key", CREDENTIAL_KEY),
                environment -> environment.setProperty("skit.ad.session-token.current-key-version", "0")
        );

        for (Consumer<MockEnvironment> invalidCase : invalidCases) {
            MockEnvironment environment = validEnvironment();
            invalidCase.accept(environment);
            assertThatThrownBy(() -> new SkitAdProductionKeyValidator(environment).validate())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("production advertising key configuration")
                    .satisfies(throwable -> assertThat(throwable.getMessage())
                            .doesNotContain(FIELD_KEY, CREDENTIAL_KEY, SESSION_KEY, "too-short",
                                    "123456789012345678901234567890中", "short-session-key"));
        }
    }

    private static MockEnvironment validEnvironment() {
        return new MockEnvironment()
                .withProperty("mybatis-plus.encryptor.password", FIELD_KEY)
                .withProperty("skit.ad.credential-encryption.current-key-id", "primary")
                .withProperty("skit.ad.credential-encryption.current-key", CREDENTIAL_KEY)
                .withProperty("skit.ad.session-token.current-key-version", "1")
                .withProperty("skit.ad.session-token.current-key", SESSION_KEY);
    }

}
