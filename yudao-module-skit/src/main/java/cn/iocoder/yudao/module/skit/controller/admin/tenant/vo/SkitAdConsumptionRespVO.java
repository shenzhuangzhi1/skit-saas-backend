package cn.iocoder.yudao.module.skit.controller.admin.tenant.vo;

import lombok.Data;

import java.time.LocalDateTime;

/** One real ad attempt, whether or not it produced revenue. */
@Data
public class SkitAdConsumptionRespVO {

    private Long tenantId;
    private Long id;
    private String sessionId;
    private Long memberId;
    private String memberNickname;
    private String memberMobileMasked;
    private Long dramaId;
    private Integer episodeFrom;
    private Integer episodeTo;
    private Long adAccountId;
    private String provider;
    private String placementId;
    private Integer networkFirmId;
    private String adsourceId;
    private String sdkRequestId;
    private String providerShowId;
    private String providerTransactionId;
    /** Stable list status alias used by the management UI. */
    private String status;
    private String consumptionStatus;
    private String clientLifecycleStatus;
    private String rewardVerificationStatus;
    private String entitlementStatus;
    private String revenueStatus;
    private String failureReason;
    private String currency;
    private String estimatedAmount;
    private String reconciledAmount;
    private String estimatedEcpm;
    private String reconciledEcpm;
    /** Input access evidence only; the current protocol does not observe Activity launch. */
    private String playerAccessEvidence = "NOT_TRACKED";
    private Integer episodeNo;
    private LocalDateTime requestedAt;
    private LocalDateTime lastEventAt;
    private LocalDateTime createdAt;
    private LocalDateTime rewardVerifiedAt;
    private LocalDateTime entitledAt;

}
