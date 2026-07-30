package cn.iocoder.yudao.module.skit.controller.app.ad;

import cn.iocoder.yudao.framework.tenant.core.aop.TenantIgnore;
import cn.iocoder.yudao.module.skit.framework.security.SkitTrustedProxyClientIpResolver;
import cn.iocoder.yudao.module.skit.service.ad.callback.SkitPangleCallbackIngressService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.security.PermitAll;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Objects;

/** Pangle requires a literal JSON boolean response rather than the platform result envelope. */
@RestController
@RequestMapping("/skit/ad-callback/pangle/{callbackKey}")
@PermitAll
@TenantIgnore
public class SkitPangleCallbackController {

    private final SkitPangleCallbackIngressService ingressService;
    private final SkitTrustedProxyClientIpResolver clientIpResolver;

    public SkitPangleCallbackController(
            SkitPangleCallbackIngressService ingressService,
            SkitTrustedProxyClientIpResolver clientIpResolver) {
        this.ingressService = Objects.requireNonNull(ingressService, "ingressService");
        this.clientIpResolver = Objects.requireNonNull(clientIpResolver, "clientIpResolver");
    }

    @GetMapping("/reward")
    public void reward(@PathVariable("callbackKey") String callbackKey,
                       HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        response.setHeader("Cache-Control", "no-store");
        boolean accepted;
        try {
            accepted = ingressService.receiveReward(callbackKey, request.getQueryString(),
                    clientIpResolver.resolve(request));
        } catch (RuntimeException infrastructureFailure) {
            // GlobalExceptionHandler serializes the error envelope but does not choose an HTTP
            // status. Mark transient callback failures explicitly so Pangle retries them.
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            throw infrastructureFailure;
        }
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(accepted
                ? "{\"isValid\":true}" : "{\"isValid\":false}");
    }
}
