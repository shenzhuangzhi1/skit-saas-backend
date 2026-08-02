package cn.iocoder.yudao.module.skit.dal.dataobject.provider;

import cn.iocoder.yudao.framework.tenant.core.aop.TenantIgnore;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;
import lombok.ToString;

import java.time.LocalDateTime;

/** Singleton durable state for the tenant callback-key registry cutover. */
@TenantIgnore
@TableName("skit_ad_callback_route_registry_migration")
@Data
public class SkitAdCallbackRouteRegistryMigrationDO {

    @TableId
    private Integer singletonId;
    private String migrationPhase;
    private Long phaseRevision;
    private Long lastCallbackKeyId;
    private Integer lastBatchSize;
    private Long expectedRowCount;
    private Long verifiedRowCount;
    private Long verificationMismatchCount;
    @JsonIgnore
    @ToString.Exclude
    private byte[] verificationHash;
    private LocalDateTime verifiedAt;
    @JsonIgnore
    @ToString.Exclude
    private byte[] blockedReasonHash;
    private LocalDateTime blockedAt;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

}
