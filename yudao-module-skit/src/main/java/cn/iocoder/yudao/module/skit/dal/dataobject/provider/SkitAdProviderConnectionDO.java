package cn.iocoder.yudao.module.skit.dal.dataobject.provider;

import cn.iocoder.yudao.framework.tenant.core.aop.TenantIgnore;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;
import lombok.ToString;

import java.time.LocalDateTime;

@TenantIgnore
@TableName("skit_ad_provider_connection")
@Data
public class SkitAdProviderConnectionDO {
    @TableId private Long id;
    private String connectionCode;
    private String provider;
    private String accountMode;
    private Long ownerTenantId;
    private Long ownerAdAccountId;
    @JsonIgnore @ToString.Exclude private byte[] externalAccountRefHash;
    private Long activeCallbackRouteId;
    private String state;
    private LocalDateTime activatedAt;
    private LocalDateTime blockedAt;
    private LocalDateTime retiredAt;
    private Long createdByUserId;
    private LocalDateTime createdAt;
    private Long updatedByUserId;
    private LocalDateTime updatedAt;
}
