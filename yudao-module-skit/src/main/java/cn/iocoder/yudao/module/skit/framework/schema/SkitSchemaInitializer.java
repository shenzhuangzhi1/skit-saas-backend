package cn.iocoder.yudao.module.skit.framework.schema;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

/**
 * 短剧 SaaS 模块的轻量级幂等建表。
 *
 * <p>线上 MySQL 容器只在首次初始化时执行 docker-entrypoint SQL，后续新增表需要随应用启动补齐。</p>
 */
@Component
@Slf4j
public class SkitSchemaInitializer implements ApplicationRunner {

    @Resource
    private JdbcTemplate jdbcTemplate;

    @Override
    public void run(ApplicationArguments args) {
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS `skit_system_config` ("
                + "`id` bigint NOT NULL AUTO_INCREMENT COMMENT '编号',"
                + "`config_data` longtext NOT NULL COMMENT '系统配置 JSON',"
                + "`creator` varchar(64) DEFAULT '' COMMENT '创建者',"
                + "`create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',"
                + "`updater` varchar(64) DEFAULT '' COMMENT '更新者',"
                + "`update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',"
                + "`deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',"
                + "PRIMARY KEY (`id`)"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='短剧 SaaS 系统配置表'");
        log.info("[run][skit_system_config table ready]");
    }

}
