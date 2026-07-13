package cn.iocoder.yudao.module.skit.controller.admin.tenant.vo;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import cn.iocoder.yudao.framework.common.enums.CommonStatusEnum;
import cn.iocoder.yudao.framework.common.validation.InEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import javax.validation.constraints.Size;

@Schema(description = "管理后台 - 代理商分页 Request VO")
@Data
@EqualsAndHashCode(callSuper = true)
public class SkitAgentPageReqVO extends PageParam {

    @Schema(description = "代理商名称、编码、联系人或联系手机", example = "华东代理")
    @Size(max = 64, message = "搜索关键字长度不能超过 64 个字符")
    private String keyword;

    @Schema(description = "状态，0 开启、1 停用", example = "0")
    @InEnum(value = CommonStatusEnum.class, message = "状态必须是 {value}")
    private Integer status;

}
