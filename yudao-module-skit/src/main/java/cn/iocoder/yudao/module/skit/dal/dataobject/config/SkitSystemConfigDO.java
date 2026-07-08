package cn.iocoder.yudao.module.skit.dal.dataobject.config;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import cn.iocoder.yudao.framework.tenant.core.aop.TenantIgnore;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

/**
 * 短剧 SaaS 系统配置 DO。
 */
@TableName("skit_system_config")
@KeySequence("skit_system_config_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TenantIgnore
public class SkitSystemConfigDO extends BaseDO {

    @TableId
    private Long id;

    /**
     * 系统配置 JSON。
     */
    private String configData;

}
