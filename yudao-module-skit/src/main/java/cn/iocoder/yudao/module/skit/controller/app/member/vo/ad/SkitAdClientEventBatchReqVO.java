package cn.iocoder.yudao.module.skit.controller.app.member.vo.ad;

import cn.iocoder.yudao.module.skit.service.ad.SkitAdSessionService;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import javax.validation.Valid;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;
import java.util.ArrayList;
import java.util.List;

@Schema(description = "用户 APP - 批量记录广告客户端遥测 Request VO")
@Data
public class SkitAdClientEventBatchReqVO {

    @Schema(description = "严格有序的客户端事件，单批最多 20 条", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotEmpty(message = "客户端事件不能为空")
    @Size(max = 20, message = "单批客户端事件不能超过 20 条")
    @Valid
    private List<@NotNull(message = "客户端事件不能为空") @Valid SkitAdClientEventReqVO> events;

    public List<SkitAdSessionService.ClientEventCommand> toCommands() {
        List<SkitAdSessionService.ClientEventCommand> commands = new ArrayList<>(events.size());
        for (SkitAdClientEventReqVO event : events) {
            commands.add(event.toCommand());
        }
        return commands;
    }

}
