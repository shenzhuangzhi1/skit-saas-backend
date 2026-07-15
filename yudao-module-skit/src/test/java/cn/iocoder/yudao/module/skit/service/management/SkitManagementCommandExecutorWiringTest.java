package cn.iocoder.yudao.module.skit.service.management;

import cn.iocoder.yudao.module.skit.dal.mysql.management.SkitManagementCommandAuditMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class SkitManagementCommandExecutorWiringTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(SkitManagementCommandExecutor.class)
            .withBean(SkitManagementCommandAuditMapper.class,
                    () -> mock(SkitManagementCommandAuditMapper.class));

    @Test
    void springUsesTheRuntimeConstructor() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(SkitManagementCommandExecutor.class);
        });
    }

}
