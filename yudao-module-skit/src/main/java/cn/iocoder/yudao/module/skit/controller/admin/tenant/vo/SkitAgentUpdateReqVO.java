package cn.iocoder.yudao.module.skit.controller.admin.tenant.vo;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.enums.CommonStatusEnum;
import cn.iocoder.yudao.framework.common.validation.InEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import javax.validation.constraints.Future;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;
import java.time.LocalDateTime;

@Schema(description = "管理后台 - 代理商普通更新 Request VO")
@Data
public class SkitAgentUpdateReqVO {

    @NotNull(message = "租户编号不能为空")
    private Long tenantId;
    @NotBlank(message = "代理商名称不能为空")
    @Size(max = 30, message = "代理商名称长度不能超过 30 个字符")
    private String name;
    @NotNull(message = "状态不能为空")
    @InEnum(value = CommonStatusEnum.class, message = "状态必须是 {value}")
    private Integer status;
    @NotNull(message = "过期时间不能为空")
    @Future(message = "过期时间必须晚于当前时间")
    private LocalDateTime expireTime;
    @Size(max = 500, message = "备注长度不能超过 500 个字符")
    private String remark;

    @Size(max = 128) private String pangleUsername;
    @Size(max = 128) private String pangleAppId;
    @Size(max = 2048) private String pangleAppSecret;
    @Size(max = 128) private String panglePlacementId;
    private Boolean pangleEnabled;
    @Size(max = 128) private String takuUsername;
    @Size(max = 128) private String takuAppId;
    @Size(max = 255) private String takuAppKey;
    @Size(max = 2048) private String takuAppSecret;
    @Size(max = 128) private String takuPlacementId;
    @Size(max = 128) private String checkInEntryInterstitialPlacementId;
    @Size(max = 128) private String postCheckInDramaInterstitialPlacementId;
    @Size(max = 128) private String homeBannerPlacementId;
    private Boolean takuEnabled;

    public void setName(String name) {
        this.name = StrUtil.trim(name);
    }

    public void setRemark(String remark) {
        this.remark = StrUtil.trim(remark);
    }
}
