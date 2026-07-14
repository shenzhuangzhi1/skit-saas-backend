package cn.iocoder.yudao.module.skit.dal.dataobject.ad;

import cn.iocoder.yudao.framework.tenant.core.db.TenantBaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

import java.time.LocalDateTime;

@TableName("skit_ad_client_event")
@KeySequence("skit_ad_client_event_seq")
@Data
@EqualsAndHashCode(callSuper = true)
public class SkitAdClientEventDO extends TenantBaseDO {

    @TableId
    private Long id;
    private Long adSessionId;
    private Integer protocolVersion;
    private String clientEventId;
    private Integer callbackSequence;
    private String eventType;
    private String nativeState;
    private String sdkRequestId;
    private String providerShowId;
    private Integer networkFirmId;
    private String adsourceId;
    private Boolean clientRewardObserved;
    private Boolean closed;
    @JsonIgnore
    @ToString.Exclude
    private byte[] payloadHash;
    private LocalDateTime occurredAt;

}
