package cn.iocoder.yudao.module.skit.controller.app.member.vo.ad;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Schema(description = "用户 APP - 指定短剧的服务端逐集权益 Response VO")
public final class SkitEntitlementRespVO {

    private final Long dramaId;
    private final List<Integer> grantedEpisodeNos;

    private SkitEntitlementRespVO(Long dramaId, List<Integer> grantedEpisodeNos) {
        this.dramaId = dramaId;
        this.grantedEpisodeNos = immutableCopy(grantedEpisodeNos);
    }

    public static SkitEntitlementRespVO of(Long dramaId, List<Integer> grantedEpisodeNos) {
        return new SkitEntitlementRespVO(dramaId, grantedEpisodeNos);
    }

    public Long getDramaId() {
        return dramaId;
    }

    public List<Integer> getGrantedEpisodeNos() {
        return grantedEpisodeNos;
    }

    private static List<Integer> immutableCopy(List<Integer> source) {
        if (source == null || source.isEmpty()) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(new ArrayList<>(source));
    }

}
