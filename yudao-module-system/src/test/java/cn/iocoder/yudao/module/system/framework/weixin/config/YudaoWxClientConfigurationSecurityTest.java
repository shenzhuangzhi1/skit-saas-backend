package cn.iocoder.yudao.module.system.framework.weixin.config;

import cn.binarywang.wx.miniapp.config.WxMaConfig;
import cn.binarywang.wx.miniapp.config.impl.WxMaRedisBetterConfigImpl;
import me.chanjar.weixin.mp.config.WxMpConfigStorage;
import me.chanjar.weixin.mp.config.impl.WxMpRedisConfigImpl;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class YudaoWxClientConfigurationSecurityTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withClassLoader(new FilteredClassLoader("org.apache.http."))
            .withUserConfiguration(YudaoWxClientConfiguration.class)
            .withBean(StringRedisTemplate.class, () -> mock(StringRedisTemplate.class))
            .withPropertyValues(
                    "wx.mp.config-storage.type=RedisTemplate",
                    "wx.mp.config-storage.key-prefix=wx",
                    "wx.mp.config-storage.http-client-type=HttpComponents",
                    "wx.miniapp.config-storage.type=RedisTemplate",
                    "wx.miniapp.config-storage.key-prefix=wa",
                    "wx.miniapp.config-storage.http-client-type=HttpComponents");

    @Test
    void productionUsesRedisAndHttpClient5WithoutHttpClient4() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(WxMpConfigStorage.class)).isInstanceOf(WxMpRedisConfigImpl.class);
            assertThat(context.getBean(WxMaConfig.class)).isInstanceOf(WxMaRedisBetterConfigImpl.class);
        });
    }

}
