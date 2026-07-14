package cn.iocoder.yudao.module.skit.service.ad.callback;

/**
 * Immutable lexical representation of one Taku reward GET callback.
 *
 * <p>The top-level user, custom-data and network fields are observations only. They are deliberately
 * not exposed as signed authority; {@link TakuRewardSignatureVerifier} produces that narrower type
 * only after verification.</p>
 */
public final class TakuRewardCallback {

    private final String userId;
    private final String transactionId;
    private final String rewardAmountLexical;
    private final String rewardName;
    private final String placementId;
    private final String extraData;
    private final Integer observedNetworkFirmId;
    private final String adsourceId;
    private final String scenarioId;
    private final String packageName;
    private final String platform;
    private final String signatureHex;
    private final String exactIlrd;
    private final String observedExchangeRateLexical;
    private final boolean healthTestProbe;
    private final byte[] canonicalPayloadHash;

    TakuRewardCallback(String userId, String transactionId, String rewardAmountLexical,
                       String rewardName, String placementId, String extraData,
                       Integer observedNetworkFirmId, String adsourceId, String scenarioId,
                       String packageName, String platform, String signatureHex, String exactIlrd,
                       String observedExchangeRateLexical, boolean healthTestProbe,
                       byte[] canonicalPayloadHash) {
        this.userId = userId;
        this.transactionId = transactionId;
        this.rewardAmountLexical = rewardAmountLexical;
        this.rewardName = rewardName;
        this.placementId = placementId;
        this.extraData = extraData;
        this.observedNetworkFirmId = observedNetworkFirmId;
        this.adsourceId = adsourceId;
        this.scenarioId = scenarioId;
        this.packageName = packageName;
        this.platform = platform;
        this.signatureHex = signatureHex;
        this.exactIlrd = exactIlrd;
        this.observedExchangeRateLexical = observedExchangeRateLexical;
        this.healthTestProbe = healthTestProbe;
        this.canonicalPayloadHash = canonicalPayloadHash.clone();
    }

    public String getUserId() {
        return userId;
    }

    public String getTransactionId() {
        return transactionId;
    }

    public String getRewardAmountLexical() {
        return rewardAmountLexical;
    }

    public String getRewardName() {
        return rewardName;
    }

    public String getPlacementId() {
        return placementId;
    }

    public String getExtraData() {
        return extraData;
    }

    public Integer getObservedNetworkFirmId() {
        return observedNetworkFirmId;
    }

    public String getAdsourceId() {
        return adsourceId;
    }

    public String getScenarioId() {
        return scenarioId;
    }

    public String getPackageName() {
        return packageName;
    }

    public String getPlatform() {
        return platform;
    }

    public String getSignatureHex() {
        return signatureHex;
    }

    /** Returns the exact, once-decoded ILRD lexical string that participates in Taku's MD5. */
    public String getExactIlrd() {
        return exactIlrd;
    }

    public String getObservedExchangeRateLexical() {
        return observedExchangeRateLexical;
    }

    public boolean isHealthTestProbe() {
        return healthTestProbe;
    }

    public byte[] getCanonicalPayloadHash() {
        return canonicalPayloadHash.clone();
    }

}
