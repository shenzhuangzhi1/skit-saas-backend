package cn.iocoder.yudao.module.skit.job;

import cn.iocoder.yudao.module.skit.service.reconciliation.SkitAdReportPullService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Objects;

@Component
@ConditionalOnProperty(prefix = "skit.ad.reporting", name = "scheduling-enabled",
        havingValue = "true", matchIfMissing = true)
public class SkitAdReportPullScheduler {

    private final SkitAdReportPullService service;

    public SkitAdReportPullScheduler(SkitAdReportPullService service) {
        this.service = Objects.requireNonNull(service, "service");
    }

    @Scheduled(cron = "${skit.ad.reporting.cron:15 * * * * *}")
    public void scheduledPull() {
        service.pullDueAccounts();
    }

}
