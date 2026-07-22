package cn.iocoder.yudao.module.skit.controller.admin.tenant.vo;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
@EqualsAndHashCode(callSuper = true)
public class SkitAdConsumptionDetailRespVO extends SkitAdConsumptionRespVO {

    private LocalDateTime asOf;
    private String timezone;
    private List<TimelineItem> timeline = new ArrayList<>();

    @Data
    public static class TimelineItem {
        private Long id;
        private String source;
        private String eventType;
        private String status;
        private String errorCode;
        private Integer sequenceNo;
        private Integer episodeNo;
        private LocalDateTime occurredAt;
    }

}
