package cn.iocoder.yudao.framework.common.util.spring;

import cn.hutool.extra.spring.SpringUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpringUtilsTest {

    private GenericApplicationContext context;

    @AfterEach
    void tearDown() {
        new SpringUtil().setApplicationContext(null);
        if (context != null) {
            context.close();
        }
    }

    @Test
    void detectsProdWhenItIsNotTheFirstActiveProfile() {
        context = new GenericApplicationContext();
        context.getEnvironment().setActiveProfiles("runtime", "prod");
        context.refresh();
        new SpringUtil().setApplicationContext(context);

        assertTrue(SpringUtils.isProd());
    }

    @Test
    void doesNotTreatAProfileContainingTheWordProdAsProduction() {
        context = new GenericApplicationContext();
        context.getEnvironment().setActiveProfiles("runtime", "production-preview");
        context.refresh();
        new SpringUtil().setApplicationContext(context);

        assertFalse(SpringUtils.isProd());
    }

}
