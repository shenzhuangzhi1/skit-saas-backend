package cn.iocoder.yudao.module.skit.service.ad;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
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
    public static final int PROVIDER_IMPRESSION_PATH_VERSION = 1;
    public static final int PROVIDER_IMPRESSION_TEMPLATE_VERSION = 1;
    private static final String PROVIDER_IMPRESSION_PATH = "/skit/ad-callback/taku/";
    private static final String PROVIDER_IMPRESSION_SUFFIX = "/impression";
    private static final String PROVIDER_IMPRESSION_TEMPLATE = PROVIDER_IMPRESSION_PATH
            + "{callback_key}" + PROVIDER_IMPRESSION_SUFFIX + IMPRESSION_QUERY;

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

    /** Builds the provider-only URL in a mutable buffer; callers own and clear the returned chars. */
    public char[] providerImpressionCallbackUrl(char[] callbackKey) {
        if (callbackKey == null || callbackKey.length != 43 || !isProviderAccountKey(callbackKey)) {
            throw new IllegalArgumentException("Callback key format is invalid");
        }
        char[] prefix = (publicBaseUrl + PROVIDER_IMPRESSION_PATH).toCharArray();
        char[] query = IMPRESSION_QUERY.toCharArray();
        char[] suffix = PROVIDER_IMPRESSION_SUFFIX.toCharArray();
        char[] result = new char[prefix.length + callbackKey.length + suffix.length + query.length];
        try {
            int offset = 0;
            System.arraycopy(prefix, 0, result, offset, prefix.length); offset += prefix.length;
            System.arraycopy(callbackKey, 0, result, offset, callbackKey.length); offset += callbackKey.length;
            System.arraycopy(suffix, 0, result, offset, suffix.length); offset += suffix.length;
            System.arraycopy(query, 0, result, offset, query.length);
            return result;
        } finally {
            java.util.Arrays.fill(prefix, '\0');
            java.util.Arrays.fill(query, '\0');
            java.util.Arrays.fill(suffix, '\0');
        }
    }

    /** Canonical persisted callback contract: framing prevents ambiguous concatenation. */
    public byte[] providerImpressionContractFingerprint(byte[] callbackKeyHash) {
        if (callbackKeyHash == null || callbackKeyHash.length != 32) {
            throw new IllegalArgumentException("Callback key hash is invalid");
        }
        char[] origin = publicBaseUrl.toCharArray();
        char[] template = PROVIDER_IMPRESSION_TEMPLATE.toCharArray();
        try {
            return providerContractFingerprint(callbackKeyHash, origin,
                    PROVIDER_IMPRESSION_PATH_VERSION, PROVIDER_IMPRESSION_TEMPLATE_VERSION, template);
        } finally {
            Arrays.fill(origin, '\0');
            Arrays.fill(template, '\0');
        }
    }

    public int providerImpressionPathVersion() {
        return PROVIDER_IMPRESSION_PATH_VERSION;
    }

    public int providerImpressionTemplateVersion() {
        return PROVIDER_IMPRESSION_TEMPLATE_VERSION;
    }

    static byte[] providerContractFingerprint(byte[] callbackKeyHash, char[] origin,
                                               int pathVersion, int templateVersion, char[] template) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update((byte) 1);
            digest.update(callbackKeyHash);
            digest.update((byte) 0);
            updateAscii(digest, origin);
            digest.update((byte) 0);
            updateInt(digest, pathVersion);
            digest.update((byte) 0);
            updateInt(digest, templateVersion);
            digest.update((byte) 0);
            updateAscii(digest, template);
            return digest.digest();
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is unavailable", unavailable);
        }
    }

    static char[] canonicalProviderImpressionTemplate() {
        return PROVIDER_IMPRESSION_TEMPLATE.toCharArray();
    }

    public String pangleRewardCallbackUrl(String callbackKey) {
        if (callbackKey == null || !CALLBACK_KEY.matcher(callbackKey).matches()) {
            throw new IllegalArgumentException("Callback key format is invalid");
        }
        return publicBaseUrl + "/skit/ad-callback/pangle/" + callbackKey + "/reward";
    }

    private String route(String callbackKey, String suffix) {
        if (callbackKey == null || !CALLBACK_KEY.matcher(callbackKey).matches()) {
            throw new IllegalArgumentException("Callback key format is invalid");
        }
        return publicBaseUrl + "/skit/ad-callback/taku/" + callbackKey + suffix;
    }

    private static boolean isCallbackKey(char[] value) {
        for (char character : value) {
            if (!(character >= 'A' && character <= 'Z') && !(character >= 'a' && character <= 'z')
                    && !(character >= '0' && character <= '9') && character != '_' && character != '-') {
                return false;
            }
        }
        return true;
    }

    private static boolean isProviderAccountKey(char[] value) {
        return value[0] == 'a' && value[1] == 'c' && value[2] == 'c'
                && value[3] == 't' && value[4] == '_' && isCallbackKey(value);
    }

    private static void updateAscii(MessageDigest digest, char[] value) {
        for (char character : value) {
            if (character > 0x7f) {
                throw new IllegalArgumentException("Callback contract is not ASCII");
            }
            digest.update((byte) character);
        }
    }

    private static void updateInt(MessageDigest digest, int value) {
        digest.update((byte) (value >>> 24));
        digest.update((byte) (value >>> 16));
        digest.update((byte) (value >>> 8));
        digest.update((byte) value);
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
