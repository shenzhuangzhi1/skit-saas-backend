package cn.iocoder.yudao.module.skit.controller.admin.record.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;

@Schema(description = "管理后台 - 短剧 SaaS 控制台汇总 Response VO")
@Data
public class SkitDashboardSummaryRespVO {

    private Long totalMembers;
    private Long totalAdCount;
    private BigDecimal totalRevenue;
    private BigDecimal totalProfit;
    private Long todayRegisterCount;
    private Long todayAdCount;
    private BigDecimal todayRevenue;
    private BigDecimal todayProfit;
    private BigDecimal rewardExchange;
    private Long scorePerYuan;

}
