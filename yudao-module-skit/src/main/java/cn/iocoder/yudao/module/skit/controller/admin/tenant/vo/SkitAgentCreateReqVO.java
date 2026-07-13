package cn.iocoder.yudao.module.skit.controller.admin.tenant.vo;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.enums.CommonStatusEnum;
import cn.iocoder.yudao.framework.common.validation.InEnum;
import cn.iocoder.yudao.framework.common.validation.Mobile;
import com.fasterxml.jackson.annotation.JsonAlias;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import org.hibernate.validator.constraints.Length;

import javax.validation.constraints.Future;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;
import java.time.LocalDateTime;

@Schema(description = "管理后台 - 代理商创建 Request VO")
@Data
public class SkitAgentCreateReqVO {

    @Schema(description = "代理商名称", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "代理商名称不能为空")
    @Size(max = 30, message = "代理商名称长度不能超过 30 个字符")
    private String name;

    @Schema(description = "登录手机号", requiredMode = Schema.RequiredMode.REQUIRED, example = "13800000000")
    @NotBlank(message = "登录手机号不能为空")
    @Mobile
    @JsonAlias("contactMobile")
    private String mobile;

    @Schema(description = "初始密码", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "初始密码不能为空")
    @Length(min = 4, max = 16, message = "管理员密码长度为 4-16 位")
    private String password;

    @Schema(description = "租户状态", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "状态不能为空")
    @InEnum(value = CommonStatusEnum.class, message = "状态必须是 {value}")
    private Integer status;

    @Schema(description = "过期时间", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "过期时间不能为空")
    @Future(message = "过期时间必须晚于当前时间")
    private LocalDateTime expireTime;

    @Size(max = 128)
    private String pangleUsername;
    @Size(max = 128)
    private String pangleAppId;
    @Size(max = 2048)
    private String pangleAppSecret;
    @Size(max = 128)
    private String panglePlacementId;
    private Boolean pangleEnabled;

    @Size(max = 128)
    private String takuUsername;
    @Size(max = 128)
    private String takuAppId;
    @Size(max = 255)
    private String takuAppKey;
    @Size(max = 2048)
    private String takuAppSecret;
    @Size(max = 128)
    private String takuPlacementId;
    private Boolean takuEnabled;

    public void setName(String name) {
        this.name = StrUtil.trim(name);
    }

    public void setMobile(String mobile) {
        this.mobile = normalizeMobile(mobile);
    }

    public static String normalizeMobile(String mobile) {
        if (mobile == null) {
            return null;
        }
        String normalized = mobile.trim().replaceAll("[\\s-]", "");
        if (normalized.startsWith("+86")) {
            return normalized.substring(3);
        }
        if (normalized.startsWith("0086")) {
            return normalized.substring(4);
        }
        return normalized;
    }

}
