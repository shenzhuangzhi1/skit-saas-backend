package cn.iocoder.yudao.module.skit.dal.dataobject.provider;

import cn.iocoder.yudao.framework.tenant.core.aop.TenantIgnore;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;
import lombok.ToString;

import java.time.LocalDateTime;

/** Global callback-key owner. The key hash is deliberately write-only to ordinary serializers and logs. */
@TenantIgnore
@TableName("skit_ad_callback_route_registry")
@KeySequence("skit_ad_callback_route_registry_seq")
@Data
public class SkitAdCallbackRouteRegistryDO {

    @TableId
    private Long id;
    @JsonIgnore
    @ToString.Exclude
    private byte[] keyHash;
    private String routeType;
    private Long providerCallbackRouteId;
    private Long tenantCallbackKeyId;
    private LocalDateTime registeredAt;
    private LocalDateTime tombstonedAt;

    /** Tenant-key projection populated only by explicit registry lookup/verification joins. */
    @TableField(exist = false)
    private Long tenantId;
    @TableField(exist = false)
    private Long adAccountId;
    @TableField(exist = false)
    private Integer keyVersion;
    @TableField(exist = false)
    private Boolean active;
    @TableField(exist = false)
    private LocalDateTime acceptUntil;
    @TableField(exist = false)
    private LocalDateTime revokedAt;

}
