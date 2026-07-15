package cn.iocoder.yudao.module.skit.framework.security;

import cn.iocoder.yudao.module.skit.service.ad.SkitTenantAdCapabilityService;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import javax.servlet.http.HttpServletRequest;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.skit.enums.ErrorCodeConstants.AD_ROLLOUT_NOT_READY;

@Component
public class SkitClientRuntimeResolver {

    public static final String NATIVE_VERSION_HEADER = "X-Skit-Native-Version";
    public static final String PROTOCOL_VERSION_HEADER = "X-Skit-Ad-Protocol-Version";

    public SkitTenantAdCapabilityService.ClientRuntime resolve() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (!(attributes instanceof ServletRequestAttributes)) {
            throw exception(AD_ROLLOUT_NOT_READY, "CLIENT_RUNTIME_HEADERS_REQUIRED");
        }
        HttpServletRequest request = ((ServletRequestAttributes) attributes).getRequest();
        String nativeVersion = request.getHeader(NATIVE_VERSION_HEADER);
        String protocol = request.getHeader(PROTOCOL_VERSION_HEADER);
        if (nativeVersion == null
                || !nativeVersion.matches("[0-9]{1,9}(\\.[0-9]{1,9}){1,3}([-.][A-Za-z0-9._-]{1,32})?")
                || protocol == null || !protocol.matches("[1-9][0-9]{0,4}")) {
            throw exception(AD_ROLLOUT_NOT_READY, "CLIENT_RUNTIME_HEADERS_INVALID");
        }
        int protocolVersion;
        try {
            protocolVersion = Integer.parseInt(protocol);
        } catch (NumberFormatException ignored) {
            throw exception(AD_ROLLOUT_NOT_READY, "CLIENT_RUNTIME_HEADERS_INVALID");
        }
        return new SkitTenantAdCapabilityService.ClientRuntime(nativeVersion, protocolVersion);
    }

}
