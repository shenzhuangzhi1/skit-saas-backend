package cn.iocoder.yudao.module.skit.service.ad.callback;

/** Immutable, once-decoded representation of a Pangle reward callback query. */
public final class PangleRewardCallback {

    private final String userId;
    private final String transactionId;
    private final String rewardName;
    private final String rewardAmountLexical;
    private final String extra;
    private final String signatureHex;
    private final byte[] canonicalPayloadHash;

    PangleRewardCallback(String userId, String transactionId, String rewardName,
                         String rewardAmountLexical, String extra, String signatureHex,
                         byte[] canonicalPayloadHash) {
        this.userId = userId;
        this.transactionId = transactionId;
        this.rewardName = rewardName;
        this.rewardAmountLexical = rewardAmountLexical;
        this.extra = extra;
        this.signatureHex = signatureHex;
        this.canonicalPayloadHash = canonicalPayloadHash.clone();
    }

    public String getUserId() {
        return userId;
    }

    public String getTransactionId() {
        return transactionId;
    }

    public String getRewardName() {
        return rewardName;
    }

    public String getRewardAmountLexical() {
        return rewardAmountLexical;
    }

    public String getExtra() {
        return extra;
    }

    public String getSignatureHex() {
        return signatureHex;
    }

    public byte[] getCanonicalPayloadHash() {
        return canonicalPayloadHash.clone();
    }
}
