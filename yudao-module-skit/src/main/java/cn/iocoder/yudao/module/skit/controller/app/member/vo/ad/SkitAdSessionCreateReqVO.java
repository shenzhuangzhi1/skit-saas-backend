package cn.iocoder.yudao.module.skit.controller.app.member.vo.ad;

import cn.iocoder.yudao.module.skit.service.ad.SkitAdSessionService;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import javax.validation.constraints.NotNull;
import javax.validation.constraints.Positive;

@Schema(description = "用户 APP - 创建广告会话 Request VO")
@Data
public class SkitAdSessionCreateReqVO {

    @Schema(description = "短剧编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "901")
    @NotNull(message = "短剧编号不能为空")
    @Positive(message = "短剧编号必须大于 0")
    private Long dramaId;

    @Schema(description = "解锁集数", requiredMode = Schema.RequiredMode.REQUIRED, example = "12")
    @NotNull(message = "解锁集数不能为空")
    @Positive(message = "解锁集数必须大于 0")
    private Integer episodeNo;

    public SkitAdSessionService.CreateCommand toCommand() {
        SkitAdSessionService.CreateCommand command = new SkitAdSessionService.CreateCommand();
        command.setDramaId(dramaId);
        command.setEpisodeNo(episodeNo);
        return command;
    }

}
