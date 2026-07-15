package cn.iocoder.yudao.module.skit.dal.dataobject.ad;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import cn.iocoder.yudao.framework.tenant.core.aop.TenantIgnore;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

import java.time.LocalDateTime;

@TableName("skit_ad_callback_edge_attempt")
@KeySequence("skit_ad_callback_edge_attempt_seq")
@TenantIgnore
@Data
@EqualsAndHashCode(callSuper = true)
public class SkitAdCallbackEdgeAttemptDO extends BaseDO {

    @TableId
    private Long id;
    private Long tenantId;
    private Long adAccountId;
    @JsonIgnore
    @ToString.Exclude
    private byte[] callbackKeyHash;
    private String provider;
    private String callbackType;
    @JsonIgnore
    @ToString.Exclude
    private byte[] clientIpHash;
    private String requestMethod;
    private String resultCode;
    private LocalDateTime receivedAt;

}
