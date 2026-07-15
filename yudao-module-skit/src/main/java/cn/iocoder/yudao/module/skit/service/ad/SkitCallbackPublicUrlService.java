package cn.iocoder.yudao.module.skit.service.ad;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Builds provider callback templates from one trusted deployment setting. Request Host and
 * X-Forwarded-* headers are deliberately outside this seam.
 */
@Component
public class SkitCallbackPublicUrlService {

    private static final Pattern CALLBACK_KEY = Pattern.compile("[A-Za-z0-9_-]{43}");
    private static final String REWARD_QUERY = "?user_id={user_id}&trans_id={trans_id}"
            + "&reward_amount={reward_amount}&reward_name={reward_name}"
            + "&placement_id={placement_id}&extra_data={extra_data}"
            + "&network_firm_id={network_firm_id}&adsource_id={adsource_id}"
            + "&sign={sign}&ilrd={ilrd}";
    private static final String IMPRESSION_QUERY = "?user_id={user_id}&req_id={req_id}"
            + "&package_name={package_name}&adformat={adformat}&placement_id={placement_id}"
            + "&nw_firm_id={nw_firm_id}&adsource_id={adsource_id}"
            + "&adsource_price={adsource_price}&currency={currency}&timestamp={timestamp}"
            + "&show_custom_ext={show_custom_ext}";

    private final String publicBaseUrl;
    private final boolean https;

    public SkitCallbackPublicUrlService(
            @Value("${skit.ad.callback.public-base-url}") String configuredBaseUrl) {
        URI uri = parseAndValidate(configuredBaseUrl);
        this.https = "https".equalsIgnoreCase(uri.getScheme());
        this.publicBaseUrl = uri.toString();
    }

    public String getPublicBaseUrl() {
        return publicBaseUrl;
    }

    public boolean isHttps() {
        return https;
    }

    public String rewardCallbackUrl(String callbackKey) {
        return route(callbackKey, "/reward") + REWARD_QUERY;
    }

    public String impressionCallbackUrl(String callbackKey) {
        return route(callbackKey, "/impression") + IMPRESSION_QUERY;
    }

    private String route(String callbackKey, String suffix) {
        if (callbackKey == null || !CALLBACK_KEY.matcher(callbackKey).matches()) {
            throw new IllegalArgumentException("Callback key format is invalid");
        }
        return publicBaseUrl + "/skit/ad-callback/taku/" + callbackKey + suffix;
    }

    private static URI parseAndValidate(String configuredBaseUrl) {
        String value = configuredBaseUrl == null ? "" : configuredBaseUrl.trim();
        if (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        try {
            URI uri = new URI(value).normalize();
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!uri.isAbsolute() || !("http".equals(scheme) || "https".equals(scheme))
                    || uri.getHost() == null || uri.getHost().trim().isEmpty()
                    || uri.getPort() > 65535
                    || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                    || !"/app-api".equals(uri.getPath())) {
                throw invalidBaseUrl();
            }
            return new URI(scheme, null, uri.getHost(), uri.getPort(), "/app-api", null, null);
        } catch (URISyntaxException malformed) {
            throw invalidBaseUrl();
        }
    }

    private static IllegalArgumentException invalidBaseUrl() {
        return new IllegalArgumentException(
                "Callback public base URL must be an absolute http(s) URL ending in /app-api");
    }

}
