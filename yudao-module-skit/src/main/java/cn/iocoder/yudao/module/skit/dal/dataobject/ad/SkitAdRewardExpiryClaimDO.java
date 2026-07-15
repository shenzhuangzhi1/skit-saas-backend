package cn.iocoder.yudao.module.skit.dal.dataobject.ad;

import lombok.Data;
import lombok.experimental.Accessors;

/** Stable cross-tenant route projection for reward-expiry work. */
@Data
@Accessors(chain = true)
public class SkitAdRewardExpiryClaimDO {
    private Long tenantId;
    private Long adAccountId;
    private Long id;
}
