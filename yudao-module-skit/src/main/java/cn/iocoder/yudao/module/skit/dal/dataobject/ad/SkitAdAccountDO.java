package cn.iocoder.yudao.module.skit.dal.dataobject.ad;

import cn.iocoder.yudao.framework.mybatis.core.type.EncryptTypeHandler;
import cn.iocoder.yudao.framework.tenant.core.db.TenantBaseDO;
import com.baomidou.mybatisplus.annotation.*;
import lombok.*;

import java.time.LocalDateTime;

@TableName(value = "skit_ad_account", autoResultMap = true)
@KeySequence("skit_ad_account_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SkitAdAccountDO extends TenantBaseDO {

    @TableId
    private Long id;
    private String provider;
    private String accountName;
    private String accountId;
    private String appId;
    @TableField(typeHandler = EncryptTypeHandler.class)
    private String appKey;
    @TableField(typeHandler = EncryptTypeHandler.class)
    private String secret;
    private String configData;
    private Integer status;
    private String reportTimezone;
    private String reportCurrency;
    private Integer reportAmountScale;
    private String reportPullLeaseOwner;
    private LocalDateTime reportPullLeaseUntil;
    private LocalDateTime reportNextAllowedAt;
    private LocalDateTime reportLastSuccessAt;
    private Integer reportFailureCount;

}
