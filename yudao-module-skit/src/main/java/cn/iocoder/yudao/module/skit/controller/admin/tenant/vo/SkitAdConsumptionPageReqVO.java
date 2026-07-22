package cn.iocoder.yudao.module.skit.controller.admin.tenant.vo;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.springframework.format.annotation.DateTimeFormat;

import javax.validation.constraints.Pattern;
import javax.validation.constraints.Positive;
import javax.validation.constraints.Size;
import java.time.LocalDateTime;

import static cn.iocoder.yudao.module.skit.enums.SkitDomainConstants.MANAGEMENT_TIMEZONE_DEFAULT;
import static cn.iocoder.yudao.module.skit.enums.SkitDomainConstants.MANAGEMENT_TIMEZONE_PATTERN;

/** Filters for the session-centric ad consumption ledger. */
@Data
@EqualsAndHashCode(callSuper = true)
public class SkitAdConsumptionPageReqVO extends PageParam {

    @Positive(message = "租户编号必须大于 0")
    private Long tenantId;
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime startTime;
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime endTime;
    @Positive(message = "剧目编号必须大于 0")
    private Long dramaId;
    @Positive(message = "集数必须大于 0")
    private Integer episodeNo;
    @Size(max = 32)
    @Pattern(regexp = "PANGLE|TAKU", message = "广告平台仅支持 PANGLE 或 TAKU")
    private String provider;
    @Positive(message = "广告源编号必须大于 0")
    private Integer networkFirmId;
    @Size(max = 32)
    @Pattern(regexp = "CREATED|LOAD_STARTED|SHOWN|REWARD_OBSERVED|CLOSED|FAILED|LOAD_EXPIRED|"
            + "REWARD_VERIFIED|REWARD_REJECTED|VERIFY_TIMEOUT|UNLOCKED",
            message = "广告消费状态不合法")
    private String status;
    @Size(max = 128, message = "用户查询条件不能超过 128 个字符")
    private String memberKeyword;
    @Size(max = 128, message = "广告交易编号不能超过 128 个字符")
    private String providerTransactionId;
    @Pattern(regexp = MANAGEMENT_TIMEZONE_PATTERN,
            message = "时区仅支持 UTC-8、UTC+8 或 UTC+0")
    private String timezone = MANAGEMENT_TIMEZONE_DEFAULT;

}
