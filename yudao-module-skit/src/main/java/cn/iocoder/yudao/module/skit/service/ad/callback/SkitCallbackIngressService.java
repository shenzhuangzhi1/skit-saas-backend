package cn.iocoder.yudao.module.skit.service.ad.callback;

/** Durable public callback ingress. Returning means the canonical result is committed. */
public interface SkitCallbackIngressService {

    IngressResponse receiveReward(String callbackKey, String rawQuery, String clientIp);

    IngressResponse receiveImpression(String callbackKey, String rawQuery, String clientIp);

    enum IngressResponse {
        OK(200),
        INVALID_SIGNATURE(601),
        REJECTED(602);

        private final int httpStatus;

        IngressResponse(int httpStatus) {
            this.httpStatus = httpStatus;
        }

        public int getHttpStatus() {
            return httpStatus;
        }
    }

}
