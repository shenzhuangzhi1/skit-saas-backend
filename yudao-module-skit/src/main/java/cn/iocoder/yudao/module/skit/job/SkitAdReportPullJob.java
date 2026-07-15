package cn.iocoder.yudao.module.skit.job;

import cn.iocoder.yudao.framework.quartz.core.handler.JobHandler;
import cn.iocoder.yudao.module.skit.service.reconciliation.SkitAdReportPullService;
import org.springframework.stereotype.Component;

import java.util.Objects;

/** Global route-only trigger. Tenant/account transactions are derived by the service. */
@Component
public class SkitAdReportPullJob implements JobHandler {

    private final SkitAdReportPullService service;

    public SkitAdReportPullJob(SkitAdReportPullService service) {
        this.service = Objects.requireNonNull(service, "service");
    }

    @Override
    public String execute(String param) {
        if (param != null && !param.trim().isEmpty()) {
            throw new IllegalArgumentException("Taku report pull does not accept tenant routing parameters");
        }
        return String.format("处理 Taku 报表账号 %s 个", service.pullDueAccounts());
    }

}
