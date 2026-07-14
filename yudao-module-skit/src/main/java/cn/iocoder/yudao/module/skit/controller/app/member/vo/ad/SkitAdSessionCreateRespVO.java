package cn.iocoder.yudao.module.skit.controller.app.member.vo.ad;

import cn.iocoder.yudao.module.skit.service.ad.SkitAdSessionService;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.Objects;

@Schema(description = "用户 APP - 广告会话原生协议 Response VO")
public final class SkitAdSessionCreateRespVO {

    private final String outcome;
    private final Integer protocolVersion;
    private final String sessionId;
    private final String provider;
    private final String placementId;
    private final String userId;
    @Schema(description = "只返回给当前会话一次的回调关联令牌")
    private final String customData;
    private final String scene;
    private final LocalDateTime loadExpiresAt;
    private final LocalDateTime rewardAcceptUntil;

    private SkitAdSessionCreateRespVO(String outcome, Integer protocolVersion, String sessionId,
                                      String provider, String placementId, String userId,
                                      String customData, String scene, LocalDateTime loadExpiresAt,
                                      LocalDateTime rewardAcceptUntil) {
        this.outcome = outcome;
        this.protocolVersion = protocolVersion;
        this.sessionId = sessionId;
        this.provider = provider;
        this.placementId = placementId;
        this.userId = userId;
        this.customData = customData;
        this.scene = scene;
        this.loadExpiresAt = loadExpiresAt;
        this.rewardAcceptUntil = rewardAcceptUntil;
    }

    public static SkitAdSessionCreateRespVO from(SkitAdSessionService.CreateResult result) {
        Objects.requireNonNull(result, "result");
        return new SkitAdSessionCreateRespVO(result.getOutcome(), result.getProtocolVersion(),
                result.getSessionId(), result.getProvider(), result.getPlacementId(), result.getUserId(),
                result.getCustomData(), result.getScene(), result.getLoadExpiresAt(),
                result.getRewardAcceptUntil());
    }

    public String getOutcome() {
        return outcome;
    }

    public Integer getProtocolVersion() {
        return protocolVersion;
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getProvider() {
        return provider;
    }

    public String getPlacementId() {
        return placementId;
    }

    public String getUserId() {
        return userId;
    }

    public String getCustomData() {
        return customData;
    }

    public String getScene() {
        return scene;
    }

    public LocalDateTime getLoadExpiresAt() {
        return loadExpiresAt;
    }

    public LocalDateTime getRewardAcceptUntil() {
        return rewardAcceptUntil;
    }

    @Override
    public String toString() {
        return "SkitAdSessionCreateRespVO{outcome='" + outcome + "', protocolVersion="
                + protocolVersion + ", sessionId='" + sessionId + "', provider='" + provider
                + "', placementId='" + placementId + "', userId='" + userId
                + "', customData=<redacted>, scene='" + scene + "', loadExpiresAt="
                + loadExpiresAt + ", rewardAcceptUntil=" + rewardAcceptUntil + '}';
    }

}
