package cn.iocoder.yudao.module.skit.job.ad;

import cn.iocoder.yudao.framework.quartz.core.handler.JobHandler;
import cn.iocoder.yudao.module.skit.service.ad.callback.SkitAdCallbackInboxDrainService;
import org.springframework.stereotype.Component;

import java.util.Objects;

/** Global callback inbox drain. This job intentionally does not use {@code @TenantJob}. */
@Component
public class SkitAdCallbackInboxDrainJob implements JobHandler {

    private final SkitAdCallbackInboxDrainService drainService;

    public SkitAdCallbackInboxDrainJob(SkitAdCallbackInboxDrainService drainService) {
        this.drainService = Objects.requireNonNull(drainService, "drainService");
    }

    @Override
    public String execute(String param) {
        if (param != null && !param.trim().isEmpty()) {
            throw new IllegalArgumentException("Callback inbox drain does not accept routing parameters");
        }
        int handled = drainService.drainOnce();
        return String.format("处理广告回调收件箱 %s 条", handled);
    }

}
