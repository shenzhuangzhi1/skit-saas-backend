package cn.iocoder.yudao.module.skit.service.ad.callback;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Parses the exact bounded servlet query representation used by Taku provider impressions.
 *
 * <p>The parser deliberately does not use Servlet parameter APIs or a generic URL decoder. It
 * preserves the ASCII wire representation while producing one strict UTF-8 semantic view.</p>
 */
public final class SkitProviderImpressionWireParser {

    public static final String ENCODING_VERSION = "SERVLET_QUERY_ASCII_V1";
    public static final int MAX_WIRE_BYTES = 32768;
    public static final int MAX_PARAMETERS = 64;
    public static final int MAX_PARAMETER_NAME_LENGTH = 64;
    public static final int MAX_VALUE_BYTES = 24576;

    private static final byte[] OFFICIAL_DOMAIN =
            "TAKU_IMPRESSION_OFFICIAL_V1".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] FALLBACK_DOMAIN =
            "TAKU_IMPRESSION_FALLBACK_WIRE_V1".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] MATERIAL_DOMAIN =
            "TAKU_IMPRESSION_MATERIAL_V1".getBytes(StandardCharsets.US_ASCII);
    private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9_.-]{1,64}");
    private static final Pattern POSITIVE_ADSOURCE = Pattern.compile("0*[1-9][0-9]*");
    private static final Pattern UNSIGNED_DECIMAL = Pattern.compile("[0-9]+");
    private static final Pattern PRICE = Pattern.compile("[0-9]+(?:\\.[0-9]{1,12})?");
    private static final Pattern CURRENCY = Pattern.compile("[A-Z]{3}");
    private static final BigDecimal MAX_PRICE = new BigDecimal("1000000");
    private static final BigInteger MAX_INT = BigInteger.valueOf(Integer.MAX_VALUE);
    private static final BigInteger MAX_LONG = BigInteger.valueOf(Long.MAX_VALUE);
    private static final String[] MATERIAL_FIELDS = {
            "package_name", "adformat", "placement_id", "nw_firm_id", "adsource_id",
            "adsource_price", "currency", "timestamp", "show_custom_ext"
    };

    private static final byte STATE_MISSING = 0;
    private static final byte STATE_PRESENT = 1;
    private static final byte STATE_EMPTY = 2;
    private static final byte STATE_UNDECODABLE = 3;
    private static final byte STATE_INVALID = 4;
    private static final byte STATE_DUPLICATE = 5;
    private static final byte STATE_UNKNOWN_ENUM = 6;

    public WirePayload parseBounded(String rawQuery) {
        String query = rawQuery == null ? "" : rawQuery;
        validateWireCharacters(query);
        byte[] wireBytes = query.getBytes(StandardCharsets.US_ASCII);
        byte[] wireHash = sha256(wireBytes);
        List<WireParameter> parameters = null;
        byte[] dedupeHash = null;
        byte[] materialHash = null;
        try {
            parameters = parseParameters(query);
            Map<String, List<WireParameter>> grouped = group(parameters);
            OfficialIdentity official = officialIdentity(grouped, wireHash);
            dedupeHash = official.dedupeHash;
            official.dedupeHash = null;
            MaterialIdentity material = materialIdentity(grouped);
            materialHash = material.hash;
            material.hash = null;
            String quarantineReason = official.quarantineReason;
            if (quarantineReason == null) {
                if (hasUndecodableValue(parameters)) {
                    quarantineReason = "WIRE_VALUE_UNDECODABLE";
                } else if (material.hasDuplicate) {
                    quarantineReason = "MATERIAL_FIELD_DUPLICATE";
                } else if (material.hasUnknownAdformat) {
                    quarantineReason = "UNKNOWN_ADFORMAT";
                } else if (material.hasInvalid) {
                    quarantineReason = "MATERIAL_FIELD_INVALID";
                }
            }
            return new WirePayload(wireBytes, wireHash, parameters, official.scheme,
                    dedupeHash, official.requestIdLexical, official.adsourceIdLexical,
                    materialHash, quarantineReason);
        } catch (RuntimeException exception) {
            closeParameters(parameters);
            throw exception;
        } finally {
            Arrays.fill(wireBytes, (byte) 0);
            Arrays.fill(wireHash, (byte) 0);
            if (dedupeHash != null) {
                Arrays.fill(dedupeHash, (byte) 0);
            }
            if (materialHash != null) {
                Arrays.fill(materialHash, (byte) 0);
            }
        }
    }

    private static void validateWireCharacters(String query) {
        if (query.length() > MAX_WIRE_BYTES) {
            throw boundary("Provider callback query exceeds the wire boundary");
        }
        for (int index = 0; index < query.length(); index++) {
            char current = query.charAt(index);
            if (current < 0x21 || current > 0x7e) {
                throw boundary("Provider callback query is not visible ASCII");
            }
        }
    }

    private static List<WireParameter> parseParameters(String query) {
        if (query.isEmpty()) {
            return new ArrayList<>();
        }
        List<WireParameter> result = new ArrayList<>();
        int start = 0;
        while (start <= query.length()) {
            int delimiter = query.indexOf('&', start);
            int end = delimiter < 0 ? query.length() : delimiter;
            if (result.size() == MAX_PARAMETERS) {
                closeParameters(result);
                throw boundary("Provider callback has too many parameters");
            }
            String segment = query.substring(start, end);
            if (segment.isEmpty()) {
                closeParameters(result);
                throw boundary("Provider callback contains an empty parameter name");
            }
            int equals = segment.indexOf('=');
            String encodedName = equals < 0 ? segment : segment.substring(0, equals);
            String encodedValue = equals < 0 ? "" : segment.substring(equals + 1);
            DecodedComponent name = decode(encodedName);
            try {
                if (!name.decodable) {
                    throw boundary("Provider callback parameter name is not decodable");
                }
                String decodedName = strictUtf8(name.bytes);
                if (!SAFE_NAME.matcher(decodedName).matches()
                        || decodedName.length() > MAX_PARAMETER_NAME_LENGTH) {
                    throw boundary("Provider callback parameter name is unsafe");
                }
                DecodedComponent value = decode(encodedValue);
                int valueSize = value.decodable
                        ? value.bytes.length : encodedValue.length();
                if (valueSize > MAX_VALUE_BYTES) {
                    value.close();
                    throw boundary("Provider callback parameter value exceeds the boundary");
                }
                result.add(new WireParameter(decodedName, value.bytes,
                        encodedValue.getBytes(StandardCharsets.US_ASCII), value.decodable));
                value.close();
            } catch (RuntimeException exception) {
                closeParameters(result);
                throw exception;
            } finally {
                name.close();
            }
            if (delimiter < 0) {
                break;
            }
            start = delimiter + 1;
        }
        return result;
    }

    private static DecodedComponent decode(String encoded) {
        byte[] scratch = new byte[encoded.length()];
        int size = 0;
        boolean decodable = true;
        for (int index = 0; index < encoded.length(); index++) {
            char current = encoded.charAt(index);
            if (current == '+') {
                scratch[size++] = 0x20;
            } else if (current == '%') {
                if (index + 2 >= encoded.length()) {
                    decodable = false;
                    break;
                }
                int high = hex(encoded.charAt(index + 1));
                int low = hex(encoded.charAt(index + 2));
                if (high < 0 || low < 0) {
                    decodable = false;
                    break;
                }
                scratch[size++] = (byte) ((high << 4) | low);
                index += 2;
            } else {
                scratch[size++] = (byte) current;
            }
        }
        byte[] decoded = Arrays.copyOf(scratch, size);
        Arrays.fill(scratch, (byte) 0);
        if (decodable) {
            try {
                strictUtf8(decoded);
            } catch (WireBoundaryException invalidUtf8) {
                decodable = false;
            }
        }
        if (!decodable) {
            Arrays.fill(decoded, (byte) 0);
            decoded = new byte[0];
        }
        return new DecodedComponent(decoded, decodable);
    }

    private static String strictUtf8(byte[] value) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(value)).toString();
        } catch (CharacterCodingException exception) {
            throw boundary("Provider callback contains invalid UTF-8");
        }
    }

    private static int hex(char value) {
        if (value >= '0' && value <= '9') {
            return value - '0';
        }
        if (value >= 'a' && value <= 'f') {
            return value - 'a' + 10;
        }
        if (value >= 'A' && value <= 'F') {
            return value - 'A' + 10;
        }
        return -1;
    }

    private static Map<String, List<WireParameter>> group(List<WireParameter> parameters) {
        Map<String, List<WireParameter>> result = new LinkedHashMap<>();
        for (WireParameter parameter : parameters) {
            result.computeIfAbsent(parameter.name, ignored -> new ArrayList<>()).add(parameter);
        }
        return result;
    }

    private static OfficialIdentity officialIdentity(
            Map<String, List<WireParameter>> grouped, byte[] wireHash) {
        List<WireParameter> requestIds = grouped.get("req_id");
        List<WireParameter> adsourceIds = grouped.get("adsource_id");
        String reason = null;
        if (requestIds == null || adsourceIds == null) {
            reason = "OFFICIAL_FIELD_MISSING";
        } else if (requestIds.size() != 1 || adsourceIds.size() != 1) {
            reason = "OFFICIAL_FIELD_DUPLICATE";
        } else if (!requestIds.get(0).decodable || !adsourceIds.get(0).decodable) {
            reason = "OFFICIAL_FIELD_UNDECODABLE";
        }

        String requestId = null;
        String adsourceId = null;
        byte[] normalizedAdsource = null;
        if (reason == null) {
            byte[] requestBytes = requestIds.get(0).decodedValue;
            requestId = strictUtf8(requestBytes);
            if (requestBytes.length < 1 || requestBytes.length > 512 || hasControl(requestId)) {
                reason = "OFFICIAL_REQ_ID_INVALID";
                requestId = null;
            }
        }
        if (reason == null) {
            byte[] adsourceBytes = adsourceIds.get(0).decodedValue;
            adsourceId = ascii(adsourceBytes);
            if (adsourceId == null || adsourceId.length() < 1 || adsourceId.length() > 19
                    || !POSITIVE_ADSOURCE.matcher(adsourceId).matches()) {
                reason = "OFFICIAL_ADSOURCE_ID_INVALID";
                adsourceId = null;
            } else {
                normalizedAdsource = stripLeadingZeroes(adsourceId)
                        .getBytes(StandardCharsets.US_ASCII);
            }
        }

        byte[] dedupeHash;
        String scheme;
        if (reason == null) {
            MessageDigest digest = digest();
            digest.update(OFFICIAL_DOMAIN);
            updateFrame(digest, requestIds.get(0).decodedValue);
            updateFrame(digest, normalizedAdsource);
            dedupeHash = digest.digest();
            scheme = "OFFICIAL_V1";
        } else {
            MessageDigest digest = digest();
            digest.update(FALLBACK_DOMAIN);
            digest.update(wireHash);
            dedupeHash = digest.digest();
            scheme = "FALLBACK_WIRE_V1";
        }
        if (normalizedAdsource != null) {
            Arrays.fill(normalizedAdsource, (byte) 0);
        }
        return new OfficialIdentity(scheme, dedupeHash, requestId, adsourceId, reason);
    }

    private static boolean hasControl(String value) {
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            if (Character.isISOControl(codePoint) || codePoint == 0x2028 || codePoint == 0x2029) {
                return true;
            }
            offset += Character.charCount(codePoint);
        }
        return false;
    }

    private static MaterialIdentity materialIdentity(
            Map<String, List<WireParameter>> grouped) {
        MessageDigest digest = digest();
        digest.update(MATERIAL_DOMAIN);
        boolean duplicate = false;
        boolean invalid = false;
        boolean unknownAdformat = false;
        for (String field : MATERIAL_FIELDS) {
            byte[] fieldBytes = field.getBytes(StandardCharsets.US_ASCII);
            updateFrame(digest, fieldBytes);
            List<WireParameter> values = grouped.get(field);
            if (values == null || values.isEmpty()) {
                digest.update(STATE_MISSING);
                updateInt(digest, 0);
                continue;
            }
            List<CanonicalOccurrence> occurrences = new ArrayList<>();
            try {
                for (WireParameter value : values) {
                    CanonicalOccurrence occurrence = canonicalOccurrence(field, value);
                    occurrences.add(occurrence);
                    invalid |= occurrence.state == STATE_INVALID
                            || occurrence.state == STATE_UNDECODABLE;
                    unknownAdformat |= "adformat".equals(field)
                            && occurrence.state == STATE_UNKNOWN_ENUM;
                }
                occurrences.sort(CanonicalOccurrence.UNSIGNED_ORDER);
                byte aggregate = occurrences.size() > 1
                        ? STATE_DUPLICATE : occurrences.get(0).state;
                duplicate |= occurrences.size() > 1;
                digest.update(aggregate);
                updateInt(digest, occurrences.size());
                for (CanonicalOccurrence occurrence : occurrences) {
                    digest.update(occurrence.encoded);
                }
            } finally {
                for (CanonicalOccurrence occurrence : occurrences) {
                    occurrence.close();
                }
            }
        }
        return new MaterialIdentity(digest.digest(), duplicate, invalid, unknownAdformat);
    }

    private static CanonicalOccurrence canonicalOccurrence(
            String field, WireParameter parameter) {
        if (!parameter.decodable) {
            byte[] rawHash = sha256(parameter.rawValue);
            return ownedOccurrence(STATE_UNDECODABLE, rawHash);
        }
        byte[] value = parameter.decodedValue;
        if (value.length == 0) {
            return new CanonicalOccurrence(STATE_EMPTY, new byte[0]);
        }
        if ("package_name".equals(field) || "placement_id".equals(field)
                || "show_custom_ext".equals(field)) {
            return new CanonicalOccurrence(STATE_PRESENT, value);
        }
        String lexical = ascii(value);
        if (lexical == null) {
            return new CanonicalOccurrence(STATE_INVALID, value);
        }
        if ("adformat".equals(field)) {
            if (!UNSIGNED_DECIMAL.matcher(lexical).matches()) {
                return new CanonicalOccurrence(STATE_INVALID, value);
            }
            String normalized = stripLeadingZeroesKeepingZero(lexical);
            byte state = normalized.length() == 1 && normalized.charAt(0) >= '0'
                    && normalized.charAt(0) <= '4' ? STATE_PRESENT : STATE_UNKNOWN_ENUM;
            return ownedOccurrence(state, normalized.getBytes(StandardCharsets.US_ASCII));
        }
        if ("nw_firm_id".equals(field)) {
            String normalized = normalizedPositiveInteger(lexical, MAX_INT, 10);
            return normalized == null
                    ? new CanonicalOccurrence(STATE_INVALID, value)
                    : ownedOccurrence(STATE_PRESENT,
                    normalized.getBytes(StandardCharsets.US_ASCII));
        }
        if ("adsource_id".equals(field)) {
            if (lexical.length() > 19 || !POSITIVE_ADSOURCE.matcher(lexical).matches()) {
                return new CanonicalOccurrence(STATE_INVALID, value);
            }
            return ownedOccurrence(STATE_PRESENT,
                    stripLeadingZeroes(lexical).getBytes(StandardCharsets.US_ASCII));
        }
        if ("adsource_price".equals(field)) {
            String normalized = normalizedPrice(lexical);
            return normalized == null
                    ? new CanonicalOccurrence(STATE_INVALID, value)
                    : ownedOccurrence(STATE_PRESENT,
                    normalized.getBytes(StandardCharsets.US_ASCII));
        }
        if ("currency".equals(field)) {
            return CURRENCY.matcher(lexical).matches()
                    ? new CanonicalOccurrence(STATE_PRESENT, value)
                    : new CanonicalOccurrence(STATE_INVALID, value);
        }
        if ("timestamp".equals(field)) {
            String normalized = normalizedPositiveInteger(lexical, MAX_LONG, 19);
            return normalized == null
                    ? new CanonicalOccurrence(STATE_INVALID, value)
                    : ownedOccurrence(STATE_PRESENT,
                    normalized.getBytes(StandardCharsets.US_ASCII));
        }
        throw new IllegalStateException("Unknown provider material field");
    }

    private static CanonicalOccurrence ownedOccurrence(byte state, byte[] token) {
        try {
            return new CanonicalOccurrence(state, token);
        } finally {
            Arrays.fill(token, (byte) 0);
        }
    }

    private static String normalizedPositiveInteger(
            String lexical, BigInteger maximum, int maximumDigits) {
        if (lexical.length() > maximumDigits || !POSITIVE_ADSOURCE.matcher(lexical).matches()) {
            return null;
        }
        String normalized = stripLeadingZeroes(lexical);
        try {
            BigInteger value = new BigInteger(normalized);
            return value.signum() > 0 && value.compareTo(maximum) <= 0 ? normalized : null;
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static String normalizedPrice(String lexical) {
        if (lexical.length() > 64 || !PRICE.matcher(lexical).matches()) {
            return null;
        }
        try {
            BigDecimal value = new BigDecimal(lexical);
            if (value.signum() < 0 || value.compareTo(MAX_PRICE) > 0) {
                return null;
            }
            BigDecimal stripped = value.stripTrailingZeros();
            return stripped.signum() == 0 ? "0" : stripped.toPlainString();
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static String ascii(byte[] value) {
        for (byte current : value) {
            if ((current & 0x80) != 0) {
                return null;
            }
        }
        return new String(value, StandardCharsets.US_ASCII);
    }

    private static String stripLeadingZeroes(String value) {
        int index = 0;
        while (index < value.length() - 1 && value.charAt(index) == '0') {
            index++;
        }
        return value.substring(index);
    }

    private static String stripLeadingZeroesKeepingZero(String value) {
        if (value.isEmpty()) {
            return value;
        }
        return stripLeadingZeroes(value);
    }

    private static boolean hasUndecodableValue(List<WireParameter> parameters) {
        for (WireParameter parameter : parameters) {
            if (!parameter.decodable) {
                return true;
            }
        }
        return false;
    }

    private static void updateFrame(MessageDigest digest, byte[] value) {
        updateInt(digest, value.length);
        digest.update(value);
    }

    private static void updateInt(MessageDigest digest, int value) {
        digest.update((byte) (value >>> 24));
        digest.update((byte) (value >>> 16));
        digest.update((byte) (value >>> 8));
        digest.update((byte) value);
    }

    private static byte[] sha256(byte[] value) {
        return digest().digest(value);
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void closeParameters(List<WireParameter> parameters) {
        if (parameters == null) {
            return;
        }
        for (WireParameter parameter : parameters) {
            parameter.close();
        }
    }

    private static WireBoundaryException boundary(String message) {
        return new WireBoundaryException(message);
    }

    public static final class WirePayload implements AutoCloseable {

        private byte[] wireBytes;
        private byte[] wirePayloadHash;
        private final List<WireParameter> parameters;
        private final String dedupeScheme;
        private byte[] dedupeKeyHash;
        private final String providerRequestIdLexical;
        private final String adsourceIdLexical;
        private byte[] materialIntegrityHash;
        private final String quarantineReason;
        private boolean closed;

        private WirePayload(byte[] wireBytes, byte[] wirePayloadHash,
                            List<WireParameter> parameters, String dedupeScheme,
                            byte[] dedupeKeyHash, String providerRequestIdLexical,
                            String adsourceIdLexical, byte[] materialIntegrityHash,
                            String quarantineReason) {
            this.wireBytes = wireBytes.clone();
            this.wirePayloadHash = wirePayloadHash.clone();
            this.parameters = Collections.unmodifiableList(new ArrayList<>(parameters));
            this.dedupeScheme = dedupeScheme;
            this.dedupeKeyHash = dedupeKeyHash.clone();
            this.providerRequestIdLexical = providerRequestIdLexical;
            this.adsourceIdLexical = adsourceIdLexical;
            this.materialIntegrityHash = materialIntegrityHash.clone();
            this.quarantineReason = quarantineReason;
        }

        public String getEncodingVersion() {
            ensureOpen();
            return ENCODING_VERSION;
        }

        public int getWireSizeBytes() {
            ensureOpen();
            return wireBytes.length;
        }

        public int getParameterCount() {
            ensureOpen();
            return parameters.size();
        }

        public String getDedupeScheme() {
            ensureOpen();
            return dedupeScheme;
        }

        public String getQuarantineReason() {
            ensureOpen();
            return quarantineReason;
        }

        public byte[] getWirePayloadHash() {
            ensureOpen();
            return wirePayloadHash.clone();
        }

        public byte[] getDedupeKeyHash() {
            ensureOpen();
            return dedupeKeyHash.clone();
        }

        public List<WireParameter> getParameters() {
            ensureOpen();
            return parameters;
        }

        public byte[] getWireBytes() {
            ensureOpen();
            return wireBytes.clone();
        }

        public String getProviderRequestIdLexical() {
            ensureOpen();
            return providerRequestIdLexical;
        }

        public String getAdsourceIdLexical() {
            ensureOpen();
            return adsourceIdLexical;
        }

        public byte[] getMaterialIntegrityHash() {
            ensureOpen();
            return materialIntegrityHash.clone();
        }

        public boolean isClosed() {
            return closed;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            Arrays.fill(wireBytes, (byte) 0);
            Arrays.fill(wirePayloadHash, (byte) 0);
            Arrays.fill(dedupeKeyHash, (byte) 0);
            Arrays.fill(materialIntegrityHash, (byte) 0);
            closeParameters(parameters);
            wireBytes = new byte[0];
            wirePayloadHash = new byte[0];
            dedupeKeyHash = new byte[0];
            materialIntegrityHash = new byte[0];
        }

        private void ensureOpen() {
            if (closed) {
                throw new IllegalStateException("Provider wire payload is closed");
            }
        }

        @Override
        public String toString() {
            return "WirePayload{encodingVersion='" + ENCODING_VERSION
                    + "', wireSizeBytes=" + (closed ? 0 : wireBytes.length)
                    + ", parameterCount=" + (closed ? 0 : parameters.size())
                    + ", dedupeScheme='" + dedupeScheme
                    + "', wire=<redacted>, hashes=<redacted>, closed=" + closed + '}';
        }
    }

    public static final class WireParameter implements AutoCloseable {

        private final String name;
        private byte[] decodedValue;
        private byte[] rawValue;
        private final boolean decodable;
        private boolean closed;

        private WireParameter(String name, byte[] decodedValue, byte[] rawValue,
                              boolean decodable) {
            this.name = name;
            this.decodedValue = decodedValue.clone();
            this.rawValue = rawValue.clone();
            this.decodable = decodable;
        }

        public String getName() {
            ensureOpen();
            return name;
        }

        public byte[] getDecodedValueUtf8() {
            ensureOpen();
            return decodedValue.clone();
        }

        public boolean isDecodable() {
            ensureOpen();
            return decodable;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            Arrays.fill(decodedValue, (byte) 0);
            Arrays.fill(rawValue, (byte) 0);
            decodedValue = new byte[0];
            rawValue = new byte[0];
        }

        private void ensureOpen() {
            if (closed) {
                throw new IllegalStateException("Provider wire parameter is closed");
            }
        }

        @Override
        public String toString() {
            return "WireParameter{name='" + name + "', value=<redacted>, decodable="
                    + decodable + ", closed=" + closed + '}';
        }
    }

    public static final class WireBoundaryException extends IllegalArgumentException {

        public WireBoundaryException(String message) {
            super(message);
        }
    }

    private static final class DecodedComponent implements AutoCloseable {

        private byte[] bytes;
        private final boolean decodable;

        private DecodedComponent(byte[] bytes, boolean decodable) {
            this.bytes = bytes;
            this.decodable = decodable;
        }

        @Override
        public void close() {
            Arrays.fill(bytes, (byte) 0);
            bytes = new byte[0];
        }
    }

    private static final class OfficialIdentity {

        private final String scheme;
        private byte[] dedupeHash;
        private final String requestIdLexical;
        private final String adsourceIdLexical;
        private final String quarantineReason;

        private OfficialIdentity(String scheme, byte[] dedupeHash,
                                 String requestIdLexical, String adsourceIdLexical,
                                 String quarantineReason) {
            this.scheme = scheme;
            this.dedupeHash = dedupeHash;
            this.requestIdLexical = requestIdLexical;
            this.adsourceIdLexical = adsourceIdLexical;
            this.quarantineReason = quarantineReason;
        }
    }

    private static final class MaterialIdentity {

        private byte[] hash;
        private final boolean hasDuplicate;
        private final boolean hasInvalid;
        private final boolean hasUnknownAdformat;

        private MaterialIdentity(byte[] hash, boolean hasDuplicate,
                                 boolean hasInvalid, boolean hasUnknownAdformat) {
            this.hash = hash;
            this.hasDuplicate = hasDuplicate;
            this.hasInvalid = hasInvalid;
            this.hasUnknownAdformat = hasUnknownAdformat;
        }
    }

    private static final class CanonicalOccurrence implements AutoCloseable {

        private static final Comparator<CanonicalOccurrence> UNSIGNED_ORDER =
                (left, right) -> compareUnsigned(left.encoded, right.encoded);

        private final byte state;
        private byte[] encoded;

        private CanonicalOccurrence(byte state, byte[] token) {
            this.state = state;
            this.encoded = new byte[1 + 4 + token.length];
            this.encoded[0] = state;
            this.encoded[1] = (byte) (token.length >>> 24);
            this.encoded[2] = (byte) (token.length >>> 16);
            this.encoded[3] = (byte) (token.length >>> 8);
            this.encoded[4] = (byte) token.length;
            System.arraycopy(token, 0, this.encoded, 5, token.length);
        }

        @Override
        public void close() {
            Arrays.fill(encoded, (byte) 0);
            encoded = new byte[0];
        }

        private static int compareUnsigned(byte[] first, byte[] second) {
            int length = Math.min(first.length, second.length);
            for (int index = 0; index < length; index++) {
                int left = first[index] & 0xff;
                int right = second[index] & 0xff;
                if (left != right) {
                    return Integer.compare(left, right);
                }
            }
            return Integer.compare(first.length, second.length);
        }
    }

}
