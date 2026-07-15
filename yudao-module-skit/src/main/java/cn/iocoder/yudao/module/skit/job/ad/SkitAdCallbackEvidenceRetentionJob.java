package cn.iocoder.yudao.module.skit.job.ad;

import cn.iocoder.yudao.framework.quartz.core.handler.JobHandler;
import cn.iocoder.yudao.module.skit.service.ad.callback.SkitAdCallbackEvidenceRetentionService;
import org.springframework.stereotype.Component;

@Component
public class SkitAdCallbackEvidenceRetentionJob implements JobHandler {
    private final SkitAdCallbackEvidenceRetentionService service;

    public SkitAdCallbackEvidenceRetentionJob(SkitAdCallbackEvidenceRetentionService service) {
        this.service = service;
    }

    @Override
    public String execute(String param) {
        if (param != null && !param.trim().isEmpty()) {
            throw new IllegalArgumentException("Callback retention job does not accept parameters");
        }
        SkitAdCallbackEvidenceRetentionService.RetentionResult result = service.runOnce();
        return String.format("清理广告回调密文 %s 条、投递证据 %s 条、边缘证据 %s 条",
                result.getErasedPayloadCount(), result.getDeletedAttemptCount(),
                result.getDeletedEdgeAttemptCount());
    }
}
