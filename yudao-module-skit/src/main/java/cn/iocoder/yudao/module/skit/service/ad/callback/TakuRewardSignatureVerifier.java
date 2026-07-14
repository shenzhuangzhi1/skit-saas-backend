package cn.iocoder.yudao.module.skit.service.ad.callback;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.Iterator;
import java.util.Map;

/** Verifies Taku's documented reward MD5 and derives narrowly scoped signed authority. */
@Component
public class TakuRewardSignatureVerifier {

    private static final int MAX_SECRET_LENGTH = 256;
    private static final int MAX_ILRD_DEPTH = 16;
    private static final int MAX_ILRD_NODES = 512;
    private static final int MAX_ILRD_IDENTIFIER_LENGTH = 512;

    private final ObjectMapper strictObjectMapper;

    public TakuRewardSignatureVerifier(ObjectMapper objectMapper) {
        if (objectMapper == null) {
            throw new IllegalArgumentException("objectMapper is required");
        }
        this.strictObjectMapper = objectMapper.copy()
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    }

    public VerificationResult verify(TakuRewardCallback callback, byte[] rewardSecret) {
        if (callback == null) {
            throw new IllegalArgumentException("callback is required");
        }
        if (callback.isHealthTestProbe()) {
            return new VerificationResult(Status.HEALTH_TEST_PROBE, false, null);
        }
        validateSecret(rewardSecret);
        byte[] expected = calculateDigest(callback, rewardSecret);
        byte[] provided = decodeHex(callback.getSignatureHex());
        boolean signatureValid;
        try {
            signatureValid = MessageDigest.isEqual(expected, provided);
        } finally {
            Arrays.fill(expected, (byte) 0);
            Arrays.fill(provided, (byte) 0);
        }
        if (!signatureValid) {
            return new VerificationResult(Status.INVALID_SIGNATURE, false, null);
        }

        if (callback.getExactIlrd() == null) {
            return new VerificationResult(Status.MISSING_SIGNED_ILRD, true, null);
        }

        SignedIlrdEvidence evidence;
        try {
            evidence = parseSignedIlrd(callback.getExactIlrd());
        } catch (InvalidIlrdException ex) {
            return new VerificationResult(Status.INVALID_SIGNED_ILRD, true, null);
        }
        if (!matchesCallback(evidence, callback)) {
            return new VerificationResult(Status.SIGNED_ILRD_MISMATCH, true, null);
        }
        SignedRewardAuthority authority = new SignedRewardAuthority(callback.getTransactionId(),
                callback.getPlacementId(), callback.getAdsourceId(),
                callback.getRewardAmountLexical(), callback.getRewardName(), evidence);
        return new VerificationResult(Status.SIGNED_REWARD, true, authority);
    }

    private static byte[] calculateDigest(TakuRewardCallback callback, byte[] rewardSecret) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5"); // Required by Taku's protocol.
            updateUtf8(digest, "trans_id=");
            updateUtf8(digest, callback.getTransactionId());
            updateUtf8(digest, "&placement_id=");
            updateUtf8(digest, callback.getPlacementId());
            updateUtf8(digest, "&adsource_id=");
            updateUtf8(digest, callback.getAdsourceId());
            updateUtf8(digest, "&reward_amount=");
            updateUtf8(digest, callback.getRewardAmountLexical());
            updateUtf8(digest, "&reward_name=");
            updateUtf8(digest, callback.getRewardName());
            updateUtf8(digest, "&sec_key=");
            digest.update(rewardSecret); // validateSecret restricts the secret to printable ASCII.
            if (callback.getExactIlrd() != null) {
                updateUtf8(digest, "&ilrd=");
                updateUtf8(digest, callback.getExactIlrd());
            }
            return digest.digest();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("Taku-required MD5 is unavailable", ex);
        }
    }

    private SignedIlrdEvidence parseSignedIlrd(String exactIlrd) {
        JsonNode root;
        try (JsonParser parser = strictObjectMapper.getFactory().createParser(exactIlrd)) {
            root = strictObjectMapper.readTree(parser);
            if (root == null || parser.nextToken() != null) {
                throw new InvalidIlrdException();
            }
        } catch (IOException | RuntimeException ex) {
            throw new InvalidIlrdException();
        }
        if (!root.isObject()) {
            throw new InvalidIlrdException();
        }
        validateTreeBounds(root);
        JsonNode networkNode = root.get("network_firm_id");
        if (networkNode == null || !networkNode.isIntegralNumber() || !networkNode.canConvertToInt()
                || networkNode.intValue() <= 0) {
            throw new InvalidIlrdException();
        }
        String adsourceId = optionalStrictIdentifier(root.get("adsource_id"));
        String showId = optionalStrictIdentifier(root.get("id"));
        String adUnitId = optionalStrictIdentifier(root.get("adunit_id"));
        return new SignedIlrdEvidence(networkNode.intValue(), adsourceId, showId, adUnitId);
    }

    private static void validateTreeBounds(JsonNode root) {
        Deque<NodeDepth> pending = new ArrayDeque<>();
        pending.add(new NodeDepth(root, 1));
        int nodes = 0;
        while (!pending.isEmpty()) {
            NodeDepth current = pending.removeFirst();
            nodes++;
            if (nodes > MAX_ILRD_NODES || current.depth > MAX_ILRD_DEPTH) {
                throw new InvalidIlrdException();
            }
            if (current.node.isObject()) {
                Iterator<Map.Entry<String, JsonNode>> fields = current.node.fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> field = fields.next();
                    if (field.getKey().length() > 128) {
                        throw new InvalidIlrdException();
                    }
                    pending.addLast(new NodeDepth(field.getValue(), current.depth + 1));
                }
            } else if (current.node.isArray()) {
                for (JsonNode item : current.node) {
                    pending.addLast(new NodeDepth(item, current.depth + 1));
                }
            } else if (current.node.isTextual()
                    && current.node.textValue().length() > TakuCallbackCanonicalizer.MAX_RAW_QUERY_LENGTH) {
                throw new InvalidIlrdException();
            }
        }
    }

    private static String strictIdentifier(JsonNode node) {
        if (node == null || !node.isTextual()) {
            throw new InvalidIlrdException();
        }
        String value = node.textValue();
        if (value.isEmpty() || value.length() > MAX_ILRD_IDENTIFIER_LENGTH) {
            throw new InvalidIlrdException();
        }
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (Character.isISOControl(current) || Character.isWhitespace(current)) {
                throw new InvalidIlrdException();
            }
        }
        return value;
    }

    private static String optionalStrictIdentifier(JsonNode node) {
        return node == null ? null : strictIdentifier(node);
    }

    private static boolean matchesCallback(SignedIlrdEvidence evidence, TakuRewardCallback callback) {
        if (callback.getObservedNetworkFirmId() == null
                || evidence.getNetworkFirmId() != callback.getObservedNetworkFirmId()) {
            return false;
        }
        if (evidence.getAdsourceId() != null
                && !evidence.getAdsourceId().equals(callback.getAdsourceId())) {
            return false;
        }
        return evidence.getAdUnitId() == null
                || evidence.getAdUnitId().equals(callback.getPlacementId());
    }

    private static void validateSecret(byte[] secret) {
        if (secret == null || secret.length == 0 || secret.length > MAX_SECRET_LENGTH) {
            throw new IllegalArgumentException("reward secret is invalid");
        }
        for (byte item : secret) {
            int unsigned = item & 0xff;
            if (unsigned < 0x21 || unsigned > 0x7e) {
                throw new IllegalArgumentException("reward secret is invalid");
            }
        }
    }

    private static byte[] decodeHex(String value) {
        if (value == null || value.length() != 32) {
            return new byte[0];
        }
        byte[] result = new byte[16];
        for (int index = 0; index < result.length; index++) {
            int high = Character.digit(value.charAt(index * 2), 16);
            int low = Character.digit(value.charAt(index * 2 + 1), 16);
            if (high < 0 || low < 0) {
                Arrays.fill(result, (byte) 0);
                return new byte[0];
            }
            result[index] = (byte) ((high << 4) | low);
        }
        return result;
    }

    private static void updateUtf8(MessageDigest digest, String value) {
        digest.update(value.getBytes(StandardCharsets.UTF_8));
    }

    public enum Status {
        SIGNED_REWARD,
        INVALID_SIGNATURE,
        MISSING_SIGNED_ILRD,
        INVALID_SIGNED_ILRD,
        SIGNED_ILRD_MISMATCH,
        HEALTH_TEST_PROBE
    }

    public static final class VerificationResult {

        private final Status status;
        private final boolean coreDigestValid;
        private final SignedRewardAuthority authority;

        private VerificationResult(Status status, boolean coreDigestValid,
                                   SignedRewardAuthority authority) {
            this.status = status;
            this.coreDigestValid = coreDigestValid;
            this.authority = authority;
        }

        public Status getStatus() {
            return status;
        }

        public boolean isCoreDigestValid() {
            return coreDigestValid;
        }

        public boolean hasSignedRewardAuthority() {
            return status == Status.SIGNED_REWARD && authority != null;
        }

        public SignedRewardAuthority getAuthority() {
            return authority;
        }
    }

    /** Contains only fields covered by Taku's MD5 and the separately parsed signed ILRD evidence. */
    public static final class SignedRewardAuthority {

        private final String transactionId;
        private final String placementId;
        private final String adsourceId;
        private final String rewardAmountLexical;
        private final String rewardName;
        private final SignedIlrdEvidence signedIlrdEvidence;

        private SignedRewardAuthority(String transactionId, String placementId, String adsourceId,
                                      String rewardAmountLexical, String rewardName,
                                      SignedIlrdEvidence signedIlrdEvidence) {
            this.transactionId = transactionId;
            this.placementId = placementId;
            this.adsourceId = adsourceId;
            this.rewardAmountLexical = rewardAmountLexical;
            this.rewardName = rewardName;
            this.signedIlrdEvidence = signedIlrdEvidence;
        }

        public String getTransactionId() {
            return transactionId;
        }

        public String getPlacementId() {
            return placementId;
        }

        public String getAdsourceId() {
            return adsourceId;
        }

        public String getRewardAmountLexical() {
            return rewardAmountLexical;
        }

        public String getRewardName() {
            return rewardName;
        }

        public SignedIlrdEvidence getSignedIlrdEvidence() {
            return signedIlrdEvidence;
        }
    }

    /** Signed ILRD is restricted to network classification/correlation identifiers, never money. */
    public static final class SignedIlrdEvidence {

        private final int networkFirmId;
        private final String adsourceId;
        private final String showId;
        private final String adUnitId;

        private SignedIlrdEvidence(int networkFirmId, String adsourceId,
                                   String showId, String adUnitId) {
            this.networkFirmId = networkFirmId;
            this.adsourceId = adsourceId;
            this.showId = showId;
            this.adUnitId = adUnitId;
        }

        public int getNetworkFirmId() {
            return networkFirmId;
        }

        public String getAdsourceId() {
            return adsourceId;
        }

        public String getShowId() {
            return showId;
        }

        public String getAdUnitId() {
            return adUnitId;
        }
    }

    private static final class NodeDepth {

        private final JsonNode node;
        private final int depth;

        private NodeDepth(JsonNode node, int depth) {
            this.node = node;
            this.depth = depth;
        }
    }

    private static final class InvalidIlrdException extends RuntimeException {
    }

}
