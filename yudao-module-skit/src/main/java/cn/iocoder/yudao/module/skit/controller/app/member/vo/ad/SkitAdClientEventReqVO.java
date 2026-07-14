package cn.iocoder.yudao.module.skit.controller.app.member.vo.ad;

import cn.iocoder.yudao.module.skit.service.ad.SkitAdSessionService;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Pattern;
import javax.validation.constraints.Positive;
import javax.validation.constraints.Size;

@Schema(description = "用户 APP - 严格版本化广告客户端事件")
@Data
public class SkitAdClientEventReqVO {

    @NotNull(message = "协议版本不能为空")
    @Min(value = 1, message = "仅支持协议版本 1")
    @Max(value = 1, message = "仅支持协议版本 1")
    private Integer protocolVersion;

    @NotBlank(message = "客户端事件编号不能为空")
    @Pattern(regexp = "[A-Za-z0-9._:-]{1,128}", message = "客户端事件编号格式错误")
    private String clientEventId;

    @NotNull(message = "回调序号不能为空")
    @Min(value = 0, message = "回调序号不能小于 0")
    private Integer callbackSequence;

    @NotBlank(message = "广告会话编号不能为空")
    @Pattern(regexp = "[A-Za-z0-9_-]{22}", message = "广告会话编号格式错误")
    private String sessionId;

    @NotBlank(message = "广告平台不能为空")
    @Pattern(regexp = "TAKU", message = "仅支持 TAKU 广告会话")
    private String provider;

    @NotBlank(message = "广告位不能为空")
    @Pattern(regexp = "[A-Za-z0-9._:-]{1,128}", message = "广告位格式错误")
    private String placementId;

    @NotBlank(message = "客户端事件类型不能为空")
    @Pattern(regexp = "LOAD_STARTED|SHOWN|REWARD_OBSERVED|CLOSED|FAILED",
            message = "客户端事件类型不受支持")
    private String eventType;

    @NotBlank(message = "原生广告状态不能为空")
    @Pattern(regexp = "LOADING|SHOWING|CLOSED|ERROR", message = "原生广告状态不受支持")
    private String nativeState;

    @NotBlank(message = "SDK 请求编号不能为空")
    @Pattern(regexp = "[A-Za-z0-9._:/-]{1,128}", message = "SDK 请求编号格式错误")
    private String sdkRequestId;

    @Pattern(regexp = "[A-Za-z0-9._:/-]{1,128}", message = "平台展示编号格式错误")
    private String providerShowId;

    @Positive(message = "广告网络编号必须大于 0")
    private Integer networkFirmId;

    @Size(max = 128, message = "广告源编号不能超过 128 个字符")
    @Pattern(regexp = "[A-Za-z0-9._:/-]{1,128}", message = "广告源编号格式错误")
    private String adsourceId;

    @NotNull(message = "客户端奖励观察标记不能为空")
    private Boolean clientRewardObserved;

    @NotNull(message = "关闭标记不能为空")
    private Boolean closed;

    public SkitAdSessionService.ClientEventCommand toCommand() {
        SkitAdSessionService.ClientEventCommand command = new SkitAdSessionService.ClientEventCommand();
        command.setProtocolVersion(protocolVersion);
        command.setClientEventId(clientEventId);
        command.setCallbackSequence(callbackSequence);
        command.setSessionId(sessionId);
        command.setProvider(provider);
        command.setPlacementId(placementId);
        command.setEventType(eventType);
        command.setNativeState(nativeState);
        command.setSdkRequestId(sdkRequestId);
        command.setProviderShowId(providerShowId);
        command.setNetworkFirmId(networkFirmId);
        command.setAdsourceId(adsourceId);
        command.setClientRewardObserved(clientRewardObserved);
        command.setClosed(closed);
        return command;
    }

}
