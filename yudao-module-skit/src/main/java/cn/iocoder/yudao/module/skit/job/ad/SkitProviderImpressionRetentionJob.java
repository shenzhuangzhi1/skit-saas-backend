package cn.iocoder.yudao.module.skit.job.ad;

import cn.iocoder.yudao.framework.quartz.core.handler.JobHandler;
import cn.iocoder.yudao.module.skit.service.ad.callback.SkitProviderImpressionRetentionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Objects;

/** Global provider-impression ciphertext retention job. */
@Component
public class SkitProviderImpressionRetentionJob implements JobHandler {

    private final SkitProviderImpressionRetentionService service;
    private final String nodeId;
    private final Clock clock;

    @Autowired
    public SkitProviderImpressionRetentionJob(
            SkitProviderImpressionRetentionService service,
            @Value("${skit.ad.provider-impression.retention.node-id:${HOSTNAME:provider-retention}}")
            String nodeId) {
        this(service, nodeId, Clock.systemUTC());
    }

    SkitProviderImpressionRetentionJob(
            SkitProviderImpressionRetentionService service, String nodeId, Clock clock) {
        this.service = Objects.requireNonNull(service, "service");
        this.nodeId = Objects.requireNonNull(nodeId, "nodeId");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public String execute(String param) {
        if (param != null && !param.trim().isEmpty()) {
            throw new IllegalArgumentException(
                    "Provider impression retention job does not accept parameters");
        }
        return String.format("清理供应商广告展示回调密文 %s 条", runOnce());
    }

    int runOnce() {
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)
                .withNano(0);
        return service.purgeExpiredCiphertexts(nodeId, now);
    }
}
