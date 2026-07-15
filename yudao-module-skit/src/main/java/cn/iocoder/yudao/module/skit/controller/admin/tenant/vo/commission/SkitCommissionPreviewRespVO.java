package cn.iocoder.yudao.module.skit.controller.admin.tenant.vo.commission;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
public class SkitCommissionPreviewRespVO {

    private Long tenantId;
    private LocalDateTime asOf;
    private String timezone;
    private String currency;
    private Integer amountScale;
    private String grossAmount;
    private String grossAmountUnits;
    private Integer totalMemberRateBps;
    private String memberTotal;
    private String memberTotalUnits;
    private Integer agentRateBps;
    private String agentAmount;
    private String agentAmountUnits;
    private List<Allocation> allocations = new ArrayList<>();

    @Data
    public static class Allocation {
        private Integer levelNo;
        private Integer rateBps;
        private String amount;
        private String amountUnits;
    }
}
