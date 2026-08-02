package cn.iocoder.yudao.module.skit.service.ad.callback;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkitProviderImpressionWireParserTest {

    private static final String COMPLETE = "req_id=A&adsource_id=01&package_name=com.skit.app"
            + "&adformat=1&placement_id=p1&nw_firm_id=66&adsource_price=3.2400"
            + "&currency=USD&timestamp=1783987200123&show_custom_ext=session";

    private final SkitProviderImpressionWireParser parser = new SkitProviderImpressionWireParser();

    @Test
    void mapsNullAndEmptyQueryToTheSameEncryptedFallbackWireIdentity() {
        try (SkitProviderImpressionWireParser.WirePayload absent = parser.parseBounded(null);
             SkitProviderImpressionWireParser.WirePayload empty = parser.parseBounded("")) {
            assertEquals("SERVLET_QUERY_ASCII_V1", absent.getEncodingVersion());
            assertEquals(0, absent.getWireSizeBytes());
            assertEquals(0, absent.getParameterCount());
            assertEquals("FALLBACK_WIRE_V1", absent.getDedupeScheme());
            assertEquals("OFFICIAL_FIELD_MISSING", absent.getQuarantineReason());
            assertArrayEquals(hex("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"),
                    absent.getWirePayloadHash());
            assertArrayEquals(hex("05662510425637fd4f109641c8ade47fbe3b6f84db8f641f36f3f2545439d0e4"),
                    absent.getDedupeKeyHash());
            assertArrayEquals(absent.getDedupeKeyHash(), empty.getDedupeKeyHash());
        }
    }

    @Test
    void enforcesExactWireByteBoundaryBeforeSemanticParsing() {
        String accepted = "a=" + repeat('x', 24576) + "&b=" + repeat('y', 8187);
        try (SkitProviderImpressionWireParser.WirePayload wire = parser.parseBounded(accepted)) {
            assertEquals(32768, wire.getWireSizeBytes());
        }
        assertBoundary("a=" + repeat('x', 24576) + "&b=" + repeat('y', 8188));
        assertBoundary("req_id=A adsource_id=1");
        assertBoundary("req_id=中&adsource_id=1");
        assertBoundary("req_id=A\nadsource_id=1");
    }

    @Test
    void enforcesExactParameterAndDecodedValueBoundaries() {
        String sixtyFour = java.util.stream.IntStream.range(0, 64)
                .mapToObj(index -> "x" + index + "=v").collect(Collectors.joining("&"));
        try (SkitProviderImpressionWireParser.WirePayload wire = parser.parseBounded(sixtyFour)) {
            assertEquals(64, wire.getParameterCount());
        }
        assertBoundary(sixtyFour + "&overflow=v");

        try (SkitProviderImpressionWireParser.WirePayload wire =
                     parser.parseBounded("future=" + repeat('z', 24576))) {
            assertEquals(24576, wire.getParameters().get(0).getDecodedValueUtf8().length);
        }
        assertBoundary("future=" + repeat('z', 24577));
    }

    @Test
    void decodesNamesAndValuesOnceWhilePreservingWireOrderAndRepeats() {
        String raw = "%72eq_id=A&adsource_id=01&future=a+b&future=a%2Bb&encoded=%E4%B8%AD";
        try (SkitProviderImpressionWireParser.WirePayload wire = parser.parseBounded(raw)) {
            assertEquals(Arrays.asList("req_id", "adsource_id", "future", "future", "encoded"),
                    wire.getParameters().stream().map(SkitProviderImpressionWireParser.WireParameter::getName)
                            .collect(Collectors.toList()));
            assertEquals("a b", utf8(wire.getParameters().get(2).getDecodedValueUtf8()));
            assertEquals("a+b", utf8(wire.getParameters().get(3).getDecodedValueUtf8()));
            assertEquals("中", utf8(wire.getParameters().get(4).getDecodedValueUtf8()));
            assertArrayEquals(raw.getBytes(StandardCharsets.US_ASCII), wire.getWireBytes());
        }
    }

    @Test
    void treatsMissingEqualsAsEmptyButRejectsEmptyOrUnsafeNames() {
        try (SkitProviderImpressionWireParser.WirePayload wire = parser.parseBounded("future")) {
            assertEquals(1, wire.getParameterCount());
            assertArrayEquals(new byte[0], wire.getParameters().get(0).getDecodedValueUtf8());
        }
        try (SkitProviderImpressionWireParser.WirePayload wire = parser.parseBounded("future=x;other=y")) {
            assertEquals(1, wire.getParameterCount());
            assertEquals("x;other=y", utf8(wire.getParameters().get(0).getDecodedValueUtf8()));
        }
        assertBoundary("=value");
        assertBoundary("a=1&&b=2");
        assertBoundary("bad%ZZ=value");
        assertBoundary("bad%20name=value");
    }

    @Test
    void capturesUndecodableValuesAndOnlyFallsBackWhenAnOfficialFieldIsAffected() {
        try (SkitProviderImpressionWireParser.WirePayload unknown = parser.parseBounded(
                COMPLETE + "&future=%ZZ")) {
            assertEquals("OFFICIAL_V1", unknown.getDedupeScheme());
            assertEquals("WIRE_VALUE_UNDECODABLE", unknown.getQuarantineReason());
            assertFalse(unknown.getParameters().get(unknown.getParameters().size() - 1).isDecodable());
        }
        try (SkitProviderImpressionWireParser.WirePayload official =
                     parser.parseBounded("req_id=%00&adsource_id=1")) {
            assertEquals("FALLBACK_WIRE_V1", official.getDedupeScheme());
            assertEquals("OFFICIAL_REQ_ID_INVALID", official.getQuarantineReason());
        }
        try (SkitProviderImpressionWireParser.WirePayload malformed =
                     parser.parseBounded("req_id=%ZZ&adsource_id=1")) {
            assertEquals("FALLBACK_WIRE_V1", malformed.getDedupeScheme());
            assertEquals("OFFICIAL_FIELD_UNDECODABLE", malformed.getQuarantineReason());
        }
    }

    @Test
    void computesFramedOfficialAndFallbackFixedVectors() {
        try (SkitProviderImpressionWireParser.WirePayload official =
                     parser.parseBounded("req_id=A&adsource_id=0001")) {
            assertEquals("OFFICIAL_V1", official.getDedupeScheme());
            assertEquals("A", official.getProviderRequestIdLexical());
            assertEquals("0001", official.getAdsourceIdLexical());
            assertArrayEquals(hex("ff060358fc30794c5db2251fd612ed8c3df7b8b5f85507ade74d5c38713be500"),
                    official.getDedupeKeyHash());
        }
        try (SkitProviderImpressionWireParser.WirePayload duplicate =
                     parser.parseBounded("req_id=A&req_id=A&adsource_id=1")) {
            assertEquals("FALLBACK_WIRE_V1", duplicate.getDedupeScheme());
            assertEquals("OFFICIAL_FIELD_DUPLICATE", duplicate.getQuarantineReason());
        }
        try (SkitProviderImpressionWireParser.WirePayload invalid =
                     parser.parseBounded("req_id=A&adsource_id=000")) {
            assertEquals("FALLBACK_WIRE_V1", invalid.getDedupeScheme());
            assertEquals("OFFICIAL_ADSOURCE_ID_INVALID", invalid.getQuarantineReason());
        }
    }

    @Test
    void materialHashIgnoresWireOrderEquivalentEncodingUnknownAndDeviceFields() {
        String first = COMPLETE + "&future=x&client_ip=1.2.3.4&gaid=device-a";
        String second = "gaid=device-b&future=y&adsource_id=1&req_id=A&currency=USD"
                + "&adsource_price=03.24000&nw_firm_id=066&placement_id=p1"
                + "&adformat=01&package_name=com.skit.app&timestamp=01783987200123"
                + "&show_custom_ext=session&client_ip=9.8.7.6";
        try (SkitProviderImpressionWireParser.WirePayload one = parser.parseBounded(first);
             SkitProviderImpressionWireParser.WirePayload two = parser.parseBounded(second)) {
            assertFalse(Arrays.equals(one.getWirePayloadHash(), two.getWirePayloadHash()));
            assertArrayEquals(one.getDedupeKeyHash(), two.getDedupeKeyHash());
            assertArrayEquals(one.getMaterialIntegrityHash(), two.getMaterialIntegrityHash());
        }
    }

    @Test
    void materialHashDistinguishesMissingEmptyInvalidAndBusinessChanges() {
        try (SkitProviderImpressionWireParser.WirePayload baseline = parser.parseBounded(COMPLETE);
             SkitProviderImpressionWireParser.WirePayload emptyShow =
                     parser.parseBounded(COMPLETE.replace("show_custom_ext=session", "show_custom_ext="));
             SkitProviderImpressionWireParser.WirePayload missingShow =
                     parser.parseBounded(COMPLETE.replace("&show_custom_ext=session", ""));
             SkitProviderImpressionWireParser.WirePayload changedPrice =
                     parser.parseBounded(COMPLETE.replace("3.2400", "3.25"))) {
            assertNotEquals(hexString(baseline.getMaterialIntegrityHash()),
                    hexString(emptyShow.getMaterialIntegrityHash()));
            assertNotEquals(hexString(emptyShow.getMaterialIntegrityHash()),
                    hexString(missingShow.getMaterialIntegrityHash()));
            assertNotEquals(hexString(baseline.getMaterialIntegrityHash()),
                    hexString(changedPrice.getMaterialIntegrityHash()));
            assertEquals(null, emptyShow.getQuarantineReason());
            assertEquals(null, missingShow.getQuarantineReason());
        }
    }

    @Test
    void repeatedKnownValuesAreOrderInvariantButQuarantined() {
        String prefix = "req_id=A&adsource_id=1&package_name=a&package_name=b&adformat=1"
                + "&nw_firm_id=66&adsource_price=1&currency=USD&timestamp=1783987200123";
        String reordered = prefix.replace("package_name=a&package_name=b", "package_name=b&package_name=a");
        try (SkitProviderImpressionWireParser.WirePayload one = parser.parseBounded(prefix);
             SkitProviderImpressionWireParser.WirePayload two = parser.parseBounded(reordered)) {
            assertArrayEquals(one.getMaterialIntegrityHash(), two.getMaterialIntegrityHash());
            assertEquals("MATERIAL_FIELD_DUPLICATE", one.getQuarantineReason());
        }
    }

    @Test
    void acceptsAllCurrentFormatsAndUnknownExtensionsButQuarantinesFutureFormat() {
        for (int format = 0; format <= 4; format++) {
            try (SkitProviderImpressionWireParser.WirePayload wire = parser.parseBounded(
                    COMPLETE.replace("adformat=1", "adformat=" + format) + "&future=x&future=y")) {
                assertEquals("OFFICIAL_V1", wire.getDedupeScheme());
                assertEquals(null, wire.getQuarantineReason());
            }
        }
        try (SkitProviderImpressionWireParser.WirePayload future =
                     parser.parseBounded(COMPLETE.replace("adformat=1", "adformat=5"))) {
            assertEquals("UNKNOWN_ADFORMAT", future.getQuarantineReason());
        }
    }

    @Test
    void closesAndRedactsSensitiveWireState() {
        String sentinel = "req_id=secret-sentinel&adsource_id=1";
        SkitProviderImpressionWireParser.WirePayload wire = parser.parseBounded(sentinel);
        assertFalse(wire.toString().contains("secret-sentinel"));
        assertFalse(wire.getParameters().get(0).toString().contains("secret-sentinel"));
        wire.close();
        assertTrue(wire.isClosed());
        assertThrows(IllegalStateException.class, wire::getWireBytes);
        assertThrows(IllegalStateException.class, wire::getMaterialIntegrityHash);
    }

    private void assertBoundary(String query) {
        assertThrows(SkitProviderImpressionWireParser.WireBoundaryException.class,
                () -> parser.parseBounded(query));
    }

    private static String utf8(byte[] value) {
        return new String(value, StandardCharsets.UTF_8);
    }

    private static String repeat(char value, int count) {
        char[] result = new char[count];
        Arrays.fill(result, value);
        return new String(result);
    }

    private static byte[] hex(String value) {
        byte[] result = new byte[value.length() / 2];
        for (int index = 0; index < result.length; index++) {
            result[index] = (byte) Integer.parseInt(value.substring(index * 2, index * 2 + 2), 16);
        }
        return result;
    }

    private static String hexString(byte[] value) {
        StringBuilder result = new StringBuilder(value.length * 2);
        for (byte current : value) {
            result.append(String.format("%02x", current & 0xff));
        }
        return result.toString();
    }
}
