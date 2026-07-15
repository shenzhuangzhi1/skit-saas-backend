package cn.iocoder.yudao.module.skit.dal.dataobject.ad;

import lombok.Data;
import lombok.experimental.Accessors;

/**
 * Immutable routing projection used while globally claiming callback inbox rows.
 *
 * <p>It deliberately contains no callback payload, provider identity, monetary value or mutable
 * processing state. The complete inbox is loaded only after entering the derived tenant context.</p>
 */
@Data
@Accessors(chain = true)
public class SkitAdCallbackClaimDO {

    private Long tenantId;
    private Long adAccountId;
    private Long id;

}
