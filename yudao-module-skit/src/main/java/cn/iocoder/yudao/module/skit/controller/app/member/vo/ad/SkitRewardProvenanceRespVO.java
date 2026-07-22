package cn.iocoder.yudao.module.skit.controller.app.member.vo.ad;

import cn.iocoder.yudao.module.skit.service.member.SkitContentEntitlementService;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A narrow, player-grant-scoped bridge to a reward callback that the server has already verified.
 * It deliberately excludes callback payloads, custom data, account identities, and transactions.
 */
@Schema(description = "用户 APP - 已验签奖励展示凭证 Response VO")
public final class SkitRewardProvenanceRespVO {

    private final Integer episodeNo;
    private final boolean verified;
    private final String sessionId;
    private final String provider;
    private final String providerShowId;

    private SkitRewardProvenanceRespVO(Integer episodeNo, boolean verified, String sessionId,
                                       String provider, String providerShowId) {
        this.episodeNo = episodeNo;
        this.verified = verified;
        this.sessionId = sessionId;
        this.provider = provider;
        this.providerShowId = providerShowId;
    }

    public static SkitRewardProvenanceRespVO of(Integer episodeNo,
                                                 SkitContentEntitlementService.VerifiedRewardProvenance proof) {
        if (proof == null) {
            return new SkitRewardProvenanceRespVO(episodeNo, false, null, null, null);
        }
        return new SkitRewardProvenanceRespVO(proof.getEpisodeNo(), true,
                proof.getSessionId(), proof.getProvider(), proof.getProviderShowId());
    }

    public Integer getEpisodeNo() {
        return episodeNo;
    }

    public boolean isVerified() {
        return verified;
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getProvider() {
        return provider;
    }

    public String getProviderShowId() {
        return providerShowId;
    }
}
