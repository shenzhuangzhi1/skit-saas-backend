package cn.iocoder.yudao.module.skit.controller.admin.tenant.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import org.hibernate.validator.constraints.Length;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

@Schema(description = "管理后台 - 代理商管理员密码重置 Request VO")
@Data
public class SkitAgentPasswordResetReqVO {

    @NotNull(message = "租户编号不能为空")
    private Long tenantId;
    @NotBlank(message = "新密码不能为空")
    @Length(min = 4, max = 16, message = "管理员密码长度为 4-16 位")
    private String password;
}
