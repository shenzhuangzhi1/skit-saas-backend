package cn.iocoder.yudao.module.skit.dal.dataobject.record;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import cn.iocoder.yudao.framework.tenant.core.aop.TenantIgnore;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

/**
 * 短剧 SaaS 后台通用记录 DO。
 *
 * <p>线上 FastAdmin 后台页面多而字段差异大，复刻阶段使用 pageKey + JSON 的方式先打通
 * 所有页面的真实持久化 CRUD，后续再按高频业务拆分强类型表。</p>
 */
@TableName("skit_admin_record")
@KeySequence("skit_admin_record_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TenantIgnore
public class SkitAdminRecordDO extends BaseDO {

    @TableId
    private Long id;

    /**
     * 前端页面键，例如 adRecord、withdraw、douyinMiniProgram。
     */
    private String pageKey;

    /**
     * 业务行键，便于前端保持选中状态。
     */
    private String rowKey;

    /**
     * 页面字段 JSON。
     */
    private String recordData;

    /**
     * 状态：0 正常，1 待处理，2 禁用。
     */
    private Integer status;

    /**
     * 页面内排序。
     */
    private Integer sort;

}
