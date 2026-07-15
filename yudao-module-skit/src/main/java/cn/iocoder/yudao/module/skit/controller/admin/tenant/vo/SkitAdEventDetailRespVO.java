package cn.iocoder.yudao.module.skit.controller.admin.tenant.vo;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
@EqualsAndHashCode(callSuper = true)
public class SkitAdEventDetailRespVO extends SkitAdEventRespVO {

    private LocalDateTime asOf;
    private String timezone;
    private String providerTransactionId;
    private String providerShowId;
    private Long policySnapshotId;
    private List<CallbackAttempt> callbackAttempts = new ArrayList<>();
    private List<LedgerEntry> ledgerEntries = new ArrayList<>();

    @Data
    public static class CallbackAttempt {
        private Long id;
        private String source;
        private String status;
        private String authenticationLevel;
        private String signatureStatus;
        private LocalDateTime receivedAt;
        private String errorCode;
    }

    @Data
    public static class LedgerEntry {
        private Long id;
        private String beneficiaryType;
        private Long beneficiaryMemberId;
        private Integer levelNo;
        private String entryType;
        private String balanceBucket;
        private String currency;
        private String amount;
        private LocalDateTime createdAt;
    }

}
