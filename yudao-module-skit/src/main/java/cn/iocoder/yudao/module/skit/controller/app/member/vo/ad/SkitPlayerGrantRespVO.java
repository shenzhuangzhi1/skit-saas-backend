package cn.iocoder.yudao.module.skit.controller.app.member.vo.ad;

import cn.iocoder.yudao.module.skit.service.member.SkitContentEntitlementService;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.Objects;

@Schema(description = "用户 APP - 短时原生播放器权限 Response VO")
public final class SkitPlayerGrantRespVO {

    @Schema(description = "权限编号", requiredMode = Schema.RequiredMode.REQUIRED)
    private final Long grantId;
    @Schema(description = "固定短剧编号", requiredMode = Schema.RequiredMode.REQUIRED)
    private final Long dramaId;
    @Schema(description = "过期时间", requiredMode = Schema.RequiredMode.REQUIRED)
    private final LocalDateTime expiresAt;
    @Schema(description = "只返回一次的播放器权限令牌", requiredMode = Schema.RequiredMode.REQUIRED)
    private final String grantToken;

    private SkitPlayerGrantRespVO(Long grantId, Long dramaId, LocalDateTime expiresAt, String grantToken) {
        this.grantId = grantId;
        this.dramaId = dramaId;
        this.expiresAt = expiresAt;
        this.grantToken = grantToken;
    }

    public static SkitPlayerGrantRespVO from(SkitContentEntitlementService.PlayerGrantIssue issue) {
        Objects.requireNonNull(issue, "issue");
        return new SkitPlayerGrantRespVO(issue.getGrantId(), issue.getDramaId(), issue.getExpiresAt(),
                issue.consumeGrantToken());
    }

    public Long getGrantId() {
        return grantId;
    }

    public Long getDramaId() {
        return dramaId;
    }

    public LocalDateTime getExpiresAt() {
        return expiresAt;
    }

    public String getGrantToken() {
        return grantToken;
    }

    @Override
    public String toString() {
        return "SkitPlayerGrantRespVO{grantId=" + grantId + ", dramaId=" + dramaId
                + ", expiresAt=" + expiresAt + ", grantToken=<write-only>}";
    }

}
