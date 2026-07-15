package cn.iocoder.yudao.module.skit.dal.dataobject.reconciliation;

import cn.iocoder.yudao.framework.tenant.core.db.TenantBaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

import java.time.LocalDate;
import java.time.LocalDateTime;

@TableName("skit_ad_report_pull")
@KeySequence("skit_ad_report_pull_seq")
@Data
@EqualsAndHashCode(callSuper = true)
public class SkitAdReportPullDO extends TenantBaseDO {

    @TableId
    private Long id;
    private Long adAccountId;
    private String provider;
    private LocalDateTime rangeStart;
    private LocalDateTime rangeEnd;
    private LocalDate reportDate;
    private String reportTimezone;
    private String currency;
    private Integer amountScale;
    private byte[] requestHash;
    private Integer credentialVersion;
    private byte[] responseHash;
    private String status;
    private Boolean finalWindow;
    @JsonIgnore
    @ToString.Exclude
    private byte[] responseCiphertext;
    @JsonIgnore
    @ToString.Exclude
    private byte[] responseNonce;
    private String responseKeyId;
    private Integer responseEnvelopeVersion;
    private LocalDateTime responseExpiresAt;
    private LocalDateTime pulledAt;
    private String errorCode;

}
