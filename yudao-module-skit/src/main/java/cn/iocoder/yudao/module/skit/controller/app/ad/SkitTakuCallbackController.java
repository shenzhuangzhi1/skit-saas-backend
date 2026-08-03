package cn.iocoder.yudao.module.skit.controller.app.ad;

import cn.iocoder.yudao.framework.tenant.core.aop.TenantIgnore;
import cn.iocoder.yudao.module.skit.framework.security.SkitTrustedProxyClientIpResolver;
import cn.iocoder.yudao.module.skit.service.ad.callback.SkitCallbackRequestMetadata;
import cn.iocoder.yudao.module.skit.service.ad.callback.SkitTakuCallbackIngressDispatcher;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.security.PermitAll;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Objects;

/** Taku requires literal HTTP 200/601/602 rather than the platform's JSON result envelope. */
@RestController
@RequestMapping("/skit/ad-callback/taku/{callbackKey}")
@PermitAll
@TenantIgnore
public class SkitTakuCallbackController {

    private final SkitTakuCallbackIngressDispatcher dispatcher;
    private final SkitTrustedProxyClientIpResolver clientIpResolver;

    public SkitTakuCallbackController(SkitTakuCallbackIngressDispatcher dispatcher,
                                      SkitTrustedProxyClientIpResolver clientIpResolver) {
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.clientIpResolver = Objects.requireNonNull(clientIpResolver, "clientIpResolver");
    }

    @GetMapping("/reward")
    public void reward(@PathVariable("callbackKey") String callbackKey,
                       HttpServletRequest request, HttpServletResponse response) {
        dispatch(SkitTakuCallbackIngressDispatcher.CallbackType.REWARD,
                callbackKey, request, response);
    }

    @GetMapping("/impression")
    public void impression(@PathVariable("callbackKey") String callbackKey,
                           HttpServletRequest request, HttpServletResponse response) {
        dispatch(SkitTakuCallbackIngressDispatcher.CallbackType.IMPRESSION,
                callbackKey, request, response);
    }

    private void dispatch(SkitTakuCallbackIngressDispatcher.CallbackType callbackType,
                          String callbackKey, HttpServletRequest request,
                          HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        response.setContentType("text/plain;charset=UTF-8");
        SkitCallbackRequestMetadata metadata = null;
        SkitTakuCallbackIngressDispatcher.DispatchResponse result;
        try {
            metadata = SkitCallbackRequestMetadata.of(
                    clientIpResolver.resolve(request),
                    request.getHeader("Accept"),
                    request.getHeader("Accept-Encoding"),
                    request.getHeader("Content-Type"),
                    request.getHeader("User-Agent"));
            result = dispatcher.dispatch(callbackType, callbackKey,
                    request.getQueryString(), metadata);
            metadata = null; // Ownership was transferred to the dispatcher.
        } catch (IllegalArgumentException invalidTransportMetadata) {
            result = SkitTakuCallbackIngressDispatcher.DispatchResponse.REJECT_602;
        } finally {
            close(metadata);
        }
        response.setStatus(Objects.requireNonNull(result, "result").getHttpStatus());
    }

    private static void close(AutoCloseable value) {
        if (value == null) {
            return;
        }
        try {
            value.close();
        } catch (Exception ignored) {
            // Metadata cleanup is non-throwing and cannot alter the fixed transport response.
        }
    }

}
