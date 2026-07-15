package cn.iocoder.yudao.module.skit.controller.admin.tenant.vo.commission;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
public class SkitCommissionPlanRespVO {

    private Long tenantId;
    private LocalDateTime asOf;
    private String timezone;
    private Long id;
    private Integer version;
    /** UNCONFIGURED, ACTIVE or ARCHIVED. */
    private String status;
    private LocalDateTime publishedAt;
    private Integer totalMemberRateBps;
    private Integer agentRateBps;
    private List<SkitCommissionRuleVO> rules = new ArrayList<>();
}
