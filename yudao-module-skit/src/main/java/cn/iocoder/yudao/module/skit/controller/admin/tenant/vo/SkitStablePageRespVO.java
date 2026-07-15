package cn.iocoder.yudao.module.skit.controller.admin.tenant.vo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
public class SkitStablePageRespVO<T> {

    private Long tenantId;
    private LocalDateTime asOf;
    private String timezone;
    private Integer pageNo;
    private Integer pageSize;
    private Long total;
    private List<T> list = new ArrayList<>();

}
