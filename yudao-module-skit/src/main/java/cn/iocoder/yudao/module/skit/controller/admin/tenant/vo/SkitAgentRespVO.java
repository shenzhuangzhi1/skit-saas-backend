package cn.iocoder.yudao.module.skit.controller.admin.tenant.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Schema(description = "管理后台 - 代理商 Response VO")
@Data
public class SkitAgentRespVO {

    @Schema(description = "租户编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1024")
    private Long tenantId;
    @Schema(description = "代理商编码", requiredMode = Schema.RequiredMode.REQUIRED, example = "AG1024")
    private String tenantCode;
    @Schema(description = "代理商根邀请码", requiredMode = Schema.RequiredMode.REQUIRED,
            example = "A1B2C3D4E5F6")
    private String rootInviteCode;
    @Schema(description = "归档时间")
    private LocalDateTime archivedTime;
    @Schema(description = "归档操作人")
    private Long archivedBy;
    @Schema(description = "代理商名称", requiredMode = Schema.RequiredMode.REQUIRED)
    private String name;
    @Schema(description = "联系人")
    private String contactName;
    @Schema(description = "联系手机")
    private String contactMobile;
    @Schema(description = "管理员登录手机号")
    private String mobile;
    @Schema(description = "状态")
    private Integer status;
    @Schema(description = "绑定域名数组")
    private List<String> websites;
    @Schema(description = "租户套餐编号")
    private Long packageId;
    @Schema(description = "租户套餐名称")
    private String packageName;
    @Schema(description = "过期时间")
    private LocalDateTime expireTime;
    @Schema(description = "账号额度")
    private Integer accountCount;
    @Schema(description = "租户管理员账号")
    private String username;
    @Schema(description = "备注")
    private String remark;
    @Schema(description = "创建时间")
    private LocalDateTime createTime;

    private String pangleUsername;
    private String pangleAppId;
    private String panglePlacementId;
    private Boolean pangleEnabled;
    private Boolean pangleSecretConfigured;

    private String takuUsername;
    private String takuAppId;
    private String takuPlacementId;
    private Boolean takuEnabled;
    private Boolean takuAppKeyConfigured;
    private Boolean takuSecretConfigured;

}
