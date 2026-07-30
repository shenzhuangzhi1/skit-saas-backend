package cn.iocoder.yudao.module.skit.service.ad.callback;

/** Public Pangle callback ingress. It records evidence and never grants content. */
public interface SkitPangleCallbackIngressService {

    boolean receiveReward(String callbackKey, String rawQuery, String clientIp);
}
