package cn.iocoder.yudao.module.skit.controller.admin.tenant.vo;

import cn.iocoder.yudao.framework.common.validation.Mobile;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

@Schema(description = "管理后台 - 代理商管理员手机号换绑 Request VO")
@Data
public class SkitAgentMobileUpdateReqVO {

    @NotNull(message = "租户编号不能为空")
    private Long tenantId;
    @NotBlank(message = "登录手机号不能为空")
    @Mobile
    private String mobile;

    public void setMobile(String mobile) {
        this.mobile = SkitAgentCreateReqVO.normalizeMobile(mobile);
    }
}
