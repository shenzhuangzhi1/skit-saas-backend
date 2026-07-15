package cn.iocoder.yudao.module.skit.job.ad;

import cn.iocoder.yudao.framework.quartz.core.handler.JobHandler;
import cn.iocoder.yudao.module.skit.service.ad.callback.SkitAdRewardVerificationExpiryService;
import org.springframework.stereotype.Component;

@Component
public class SkitAdRewardVerificationExpiryJob implements JobHandler {
    private final SkitAdRewardVerificationExpiryService service;

    public SkitAdRewardVerificationExpiryJob(SkitAdRewardVerificationExpiryService service) {
        this.service = service;
    }

    @Override
    public String execute(String param) {
        if (param != null && !param.trim().isEmpty()) {
            throw new IllegalArgumentException("Reward expiry job does not accept parameters");
        }
        return String.format("处理广告奖励验证超时 %s 条", service.sweepOnce());
    }
}
