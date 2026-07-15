package cn.iocoder.yudao.framework.common.util.spring;

import cn.hutool.extra.spring.SpringUtil;

import java.util.Arrays;

/**
 * Spring 工具类
 *
 * @author 芋道源码
 */
public class SpringUtils extends SpringUtil {

    /**
     * 是否为生产环境
     *
     * @return 是否生产环境
     */
    public static boolean isProd() {
        return Arrays.asList(getActiveProfiles()).contains("prod");
    }

}
