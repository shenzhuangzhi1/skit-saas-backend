package cn.iocoder.yudao.module.skit.controller.app.ad;

import cn.iocoder.yudao.framework.tenant.core.aop.TenantIgnore;
import cn.iocoder.yudao.module.skit.framework.security.SkitTrustedProxyClientIpResolver;
import cn.iocoder.yudao.module.skit.service.ad.callback.SkitCallbackIngressService;
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

    private final SkitCallbackIngressService ingressService;
    private final SkitTrustedProxyClientIpResolver clientIpResolver;

    public SkitTakuCallbackController(SkitCallbackIngressService ingressService,
                                      SkitTrustedProxyClientIpResolver clientIpResolver) {
        this.ingressService = Objects.requireNonNull(ingressService, "ingressService");
        this.clientIpResolver = Objects.requireNonNull(clientIpResolver, "clientIpResolver");
    }

    @GetMapping("/reward")
    public void reward(@PathVariable("callbackKey") String callbackKey,
                       HttpServletRequest request, HttpServletResponse response) {
        apply(response, ingressService.receiveReward(callbackKey, request.getQueryString(),
                clientIpResolver.resolve(request)));
    }

    @GetMapping("/impression")
    public void impression(@PathVariable("callbackKey") String callbackKey,
                           HttpServletRequest request, HttpServletResponse response) {
        apply(response, ingressService.receiveImpression(callbackKey, request.getQueryString(),
                clientIpResolver.resolve(request)));
    }

    private static void apply(HttpServletResponse response,
                              SkitCallbackIngressService.IngressResponse result) {
        response.setHeader("Cache-Control", "no-store");
        response.setStatus(Objects.requireNonNull(result, "result").getHttpStatus());
    }

}
