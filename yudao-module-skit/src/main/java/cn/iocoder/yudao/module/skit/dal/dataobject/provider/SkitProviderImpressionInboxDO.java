package cn.iocoder.yudao.module.skit.dal.dataobject.provider;

import cn.iocoder.yudao.framework.tenant.core.aop.TenantIgnore;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;
import lombok.ToString;

import java.time.LocalDateTime;

@TenantIgnore
@InterceptorIgnore(tenantLine = "true")
@TableName("skit_provider_impression_inbox")
@Data
public class SkitProviderImpressionInboxDO {

    @TableId
    private Long id;
    private Long providerConnectionId;
    private String dedupeScheme;
    @JsonIgnore
    @ToString.Exclude
    private byte[] dedupeKeyHash;
    private Long canonicalAttemptId;
    private String providerRequestIdLexical;
    private String adsourceIdLexical;
    @JsonIgnore
    @ToString.Exclude
    private byte[] materialIntegrityHash;
    private String authenticationLevel;
    private String integrityStatus;
    private Long integrityRevision;
    private LocalDateTime integrityConflictAt;
    private String processingStatus;
    private String quarantineReason;
    private String leaseOwner;
    private LocalDateTime leaseUntil;
    private Integer processingAttemptCount;
    private LocalDateTime nextAttemptAt;
    private LocalDateTime firstReceivedAt;
    private LocalDateTime lastReceivedAt;
    private LocalDateTime processedAt;
    private LocalDateTime deadLetterAlertedAt;
}
