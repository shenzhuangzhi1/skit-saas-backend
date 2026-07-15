package cn.iocoder.yudao.module.skit.service.ad.callback;

/** Immutable lexical representation of Taku's unsigned impression observation. */
public final class TakuImpressionCallback {

    public enum AuthenticationLevel {
        UNSIGNED_PROVIDER_OBSERVATION
    }

    private final String userId;
    private final String requestId;
    private final String packageName;
    private final int adFormat;
    private final String placementId;
    private final Integer observedNetworkFirmId;
    private final String adsourceId;
    private final String adsourcePriceLexical;
    private final String currency;
    private final String timestampLexical;
    private final String showCustomExt;
    private final byte[] canonicalPayloadHash;

    TakuImpressionCallback(String userId, String requestId, String packageName, int adFormat,
                           String placementId,
                           Integer observedNetworkFirmId, String adsourceId,
                           String adsourcePriceLexical, String currency, String timestampLexical,
                           String showCustomExt, byte[] canonicalPayloadHash) {
        this.userId = userId;
        this.requestId = requestId;
        this.packageName = packageName;
        this.adFormat = adFormat;
        this.placementId = placementId;
        this.observedNetworkFirmId = observedNetworkFirmId;
        this.adsourceId = adsourceId;
        this.adsourcePriceLexical = adsourcePriceLexical;
        this.currency = currency;
        this.timestampLexical = timestampLexical;
        this.showCustomExt = showCustomExt;
        this.canonicalPayloadHash = canonicalPayloadHash.clone();
    }

    public AuthenticationLevel getAuthenticationLevel() {
        return AuthenticationLevel.UNSIGNED_PROVIDER_OBSERVATION;
    }

    public String getUserId() {
        return userId;
    }

    public String getRequestId() {
        return requestId;
    }

    public String getPackageName() {
        return packageName;
    }

    public int getAdFormat() {
        return adFormat;
    }

    public String getPlacementId() {
        return placementId;
    }

    public Integer getObservedNetworkFirmId() {
        return observedNetworkFirmId;
    }

    public String getAdsourceId() {
        return adsourceId;
    }

    /** Returns Taku's original eCPM lexical value. It is not a per-impression amount. */
    public String getAdsourcePriceLexical() {
        return adsourcePriceLexical;
    }

    public String getCurrency() {
        return currency;
    }

    public String getTimestampLexical() {
        return timestampLexical;
    }

    public String getShowCustomExt() {
        return showCustomExt;
    }

    public byte[] getCanonicalPayloadHash() {
        return canonicalPayloadHash.clone();
    }

}
