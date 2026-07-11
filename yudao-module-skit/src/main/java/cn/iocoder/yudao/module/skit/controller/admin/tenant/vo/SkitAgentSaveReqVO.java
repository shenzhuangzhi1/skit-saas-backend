package cn.iocoder.yudao.module.skit.controller.admin.tenant.vo;

import cn.hutool.core.util.ObjectUtil;
import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import org.hibernate.validator.constraints.Length;

import javax.validation.constraints.AssertTrue;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Pattern;
import javax.validation.constraints.Size;
import java.time.LocalDateTime;
import java.util.List;

@Schema(description = "管理后台 - 代理商创建/修改 Request VO")
@Data
public class SkitAgentSaveReqVO {

    @Schema(description = "租户编号；修改时必填", example = "1024")
    private Long tenantId;

    @Schema(description = "代理商编码；创建时不传则自动生成", example = "EAST_CHINA")
    @Pattern(regexp = "^[a-zA-Z0-9_-]{3,32}$",
            message = "代理商编码只能包含 3-32 位数字、字母、下划线或中划线")
    private String tenantCode;

    @Schema(description = "代理商名称", requiredMode = Schema.RequiredMode.REQUIRED, example = "华东代理")
    @NotBlank(message = "代理商名称不能为空")
    private String name;

    @Schema(description = "联系人", requiredMode = Schema.RequiredMode.REQUIRED, example = "张三")
    @NotBlank(message = "联系人不能为空")
    private String contactName;

    @Schema(description = "联系手机", example = "15601691300")
    private String contactMobile;

    @Schema(description = "状态", requiredMode = Schema.RequiredMode.REQUIRED, example = "0")
    @NotNull(message = "状态不能为空")
    private Integer status;

    @Schema(description = "绑定域名数组")
    private List<String> websites;

    @Schema(description = "租户套餐编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotNull(message = "租户套餐编号不能为空")
    private Long packageId;

    @Schema(description = "过期时间", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "过期时间不能为空")
    private LocalDateTime expireTime;

    @Schema(description = "账号额度", requiredMode = Schema.RequiredMode.REQUIRED, example = "10")
    @NotNull(message = "账号额度不能为空")
    @Min(value = 1, message = "账号额度不能小于 1")
    private Integer accountCount;

    @Schema(description = "租户管理员账号；仅创建时使用", example = "agent_admin")
    @Pattern(regexp = "^[a-zA-Z0-9]{4,30}$", message = "管理员账号由 4-30 位数字、字母组成")
    @Size(min = 4, max = 30, message = "管理员账号长度为 4-30 个字符")
    private String username;

    @Schema(description = "租户管理员密码；修改时留空表示不重置", example = "123456")
    @Length(min = 4, max = 16, message = "管理员密码长度为 4-16 位")
    private String password;

    @Schema(description = "备注")
    private String remark;

    @Schema(description = "穿山甲账号")
    private String pangleUsername;
    @Schema(description = "穿山甲 App ID")
    private String pangleAppId;
    @Schema(description = "穿山甲 App Secret；修改时留空表示不替换")
    private String pangleAppSecret;
    @Schema(description = "穿山甲广告位 ID")
    private String panglePlacementId;
    @Schema(description = "是否启用穿山甲；不传时按配置完整性自动判断")
    private Boolean pangleEnabled;

    @Schema(description = "Taku 账号")
    private String takuUsername;
    @Schema(description = "Taku App ID")
    private String takuAppId;
    @Schema(description = "Taku 客户端 SDK App Key；修改时留空表示不替换")
    private String takuAppKey;
    @Schema(description = "Taku App Secret；修改时留空表示不替换")
    private String takuAppSecret;
    @Schema(description = "Taku 广告位 ID")
    private String takuPlacementId;
    @Schema(description = "是否启用 Taku；不传时按配置完整性自动判断")
    private Boolean takuEnabled;

    @AssertTrue(message = "创建代理商时，管理员账号和密码不能为空")
    @JsonIgnore
    public boolean isAdminCredentialValid() {
        return tenantId != null || ObjectUtil.isAllNotEmpty(username, password);
    }

}
