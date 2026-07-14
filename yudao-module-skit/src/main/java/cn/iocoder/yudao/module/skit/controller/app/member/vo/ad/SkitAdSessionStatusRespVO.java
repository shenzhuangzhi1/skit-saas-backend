package cn.iocoder.yudao.module.skit.controller.app.member.vo.ad;

import cn.iocoder.yudao.module.skit.service.ad.SkitAdSessionService;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.Objects;

@Schema(description = "用户 APP - 广告会话权威状态 Response VO")
public final class SkitAdSessionStatusRespVO {

    private final String sessionId;
    private final String clientLifecycleStatus;
    private final String rewardVerificationStatus;
    private final String entitlementStatus;
    private final String revenueStatus;
    private final String providerShowId;
    private final LocalDateTime loadExpiresAt;
    private final LocalDateTime rewardAcceptUntil;

    private SkitAdSessionStatusRespVO(String sessionId, String clientLifecycleStatus,
                                      String rewardVerificationStatus, String entitlementStatus,
                                      String revenueStatus, String providerShowId,
                                      LocalDateTime loadExpiresAt, LocalDateTime rewardAcceptUntil) {
        this.sessionId = sessionId;
        this.clientLifecycleStatus = clientLifecycleStatus;
        this.rewardVerificationStatus = rewardVerificationStatus;
        this.entitlementStatus = entitlementStatus;
        this.revenueStatus = revenueStatus;
        this.providerShowId = providerShowId;
        this.loadExpiresAt = loadExpiresAt;
        this.rewardAcceptUntil = rewardAcceptUntil;
    }

    public static SkitAdSessionStatusRespVO from(SkitAdSessionService.SessionView view) {
        Objects.requireNonNull(view, "view");
        return new SkitAdSessionStatusRespVO(view.getSessionId(), view.getClientLifecycleStatus(),
                view.getRewardVerificationStatus(), view.getEntitlementStatus(), view.getRevenueStatus(),
                view.getProviderShowId(), view.getLoadExpiresAt(), view.getRewardAcceptUntil());
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getClientLifecycleStatus() {
        return clientLifecycleStatus;
    }

    public String getRewardVerificationStatus() {
        return rewardVerificationStatus;
    }

    public String getEntitlementStatus() {
        return entitlementStatus;
    }

    public String getRevenueStatus() {
        return revenueStatus;
    }

    public String getProviderShowId() {
        return providerShowId;
    }

    public LocalDateTime getLoadExpiresAt() {
        return loadExpiresAt;
    }

    public LocalDateTime getRewardAcceptUntil() {
        return rewardAcceptUntil;
    }

}
