package cn.iocoder.yudao.module.skit.controller.app.member.vo.ad;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import javax.validation.constraints.NotNull;
import javax.validation.constraints.Positive;

@Schema(description = "用户 APP - 签发原生播放器权限 Request VO")
@Data
public class SkitPlayerGrantCreateReqVO {

    @Schema(description = "短剧编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "901")
    @NotNull(message = "短剧编号不能为空")
    @Positive(message = "短剧编号必须大于 0")
    private Long dramaId;

}
