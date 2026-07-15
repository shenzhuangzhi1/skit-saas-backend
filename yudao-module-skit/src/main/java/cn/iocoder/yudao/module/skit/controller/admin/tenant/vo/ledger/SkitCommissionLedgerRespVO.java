package cn.iocoder.yudao.module.skit.controller.admin.tenant.vo.ledger;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class SkitCommissionLedgerRespVO {

    private Long tenantId;
    private Long id;
    private Long eventId;
    private Long sourceMemberId;
    private String sourceMemberName;
    private String provider;
    private String placementId;
    private String beneficiaryType;
    private Long beneficiaryMemberId;
    private String beneficiaryMemberName;
    private Integer levelNo;
    private Integer rateBps;
    private Integer ruleVersion;
    private String entryType;
    private String balanceBucket;
    private String currency;
    private Integer amountScale;
    private String grossAmount;
    private String grossAmountUnits;
    private String amount;
    private String amountUnits;
    private Integer revisionNo;
    private Long reversalOfId;
    private LocalDateTime occurredAt;
    private LocalDateTime createdAt;
}
