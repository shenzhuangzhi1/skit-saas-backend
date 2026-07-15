package cn.iocoder.yudao.module.skit.controller.app.member.vo.ad;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Schema(description = "用户 APP - 播放器权限所绑定短剧的逐集权益 Response VO")
public final class SkitGrantedEpisodesRespVO {

    private final List<Integer> grantedEpisodeNos;

    private SkitGrantedEpisodesRespVO(List<Integer> grantedEpisodeNos) {
        if (grantedEpisodeNos == null || grantedEpisodeNos.isEmpty()) {
            this.grantedEpisodeNos = Collections.emptyList();
        } else {
            this.grantedEpisodeNos = Collections.unmodifiableList(new ArrayList<>(grantedEpisodeNos));
        }
    }

    public static SkitGrantedEpisodesRespVO of(List<Integer> grantedEpisodeNos) {
        return new SkitGrantedEpisodesRespVO(grantedEpisodeNos);
    }

    public List<Integer> getGrantedEpisodeNos() {
        return grantedEpisodeNos;
    }

}
