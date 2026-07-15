package cn.iocoder.yudao.module.skit.framework.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** Forwarded client IP is disabled unless the immediate peer matches one of these CIDRs. */
@Component
@ConfigurationProperties(prefix = "skit.security.client-ip")
public class SkitTrustedProxyProperties {

    private List<String> trustedProxyCidrs = new ArrayList<>();

    public List<String> getTrustedProxyCidrs() {
        return trustedProxyCidrs;
    }

    public void setTrustedProxyCidrs(List<String> trustedProxyCidrs) {
        this.trustedProxyCidrs = trustedProxyCidrs == null
                ? new ArrayList<>() : new ArrayList<>(trustedProxyCidrs);
    }
}
