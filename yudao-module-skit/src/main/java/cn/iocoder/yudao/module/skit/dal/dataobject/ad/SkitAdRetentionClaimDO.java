package cn.iocoder.yudao.module.skit.dal.dataobject.ad;

import lombok.Data;
import lombok.experimental.Accessors;

/** Stable routing projection for callback-evidence retention. */
@Data
@Accessors(chain = true)
public class SkitAdRetentionClaimDO {
    private Long tenantId;
    private Long adAccountId;
    private Long id;
}
