package cn.iocoder.yudao.module.skit.service.ad.callback;

/** One public Taku transport dispatch boundary. Implementations own the authoritative ingress time. */
public interface SkitTakuCallbackIngressDispatcher {

    DispatchResponse dispatch(CallbackType callbackType, String callbackKey, String rawQuery,
                              SkitCallbackRequestMetadata requestMetadata);

    enum CallbackType {
        REWARD,
        IMPRESSION
    }

    enum DispatchResponse {
        ACK_200(200),
        INVALID_SIGNATURE_601(601),
        REJECT_602(602),
        FAILURE_503(503);

        private final int httpStatus;

        DispatchResponse(int httpStatus) {
            this.httpStatus = httpStatus;
        }

        public int getHttpStatus() {
            return httpStatus;
        }
    }
}
