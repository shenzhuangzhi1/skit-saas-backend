package cn.iocoder.yudao.module.skit.framework.schema;

import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Produces a deterministic information-schema fingerprint for a Task 2-owned table.
 */
public final class SkitAdSchemaSignature {

    private static final Map<String, String> EXPECTED_FINGERPRINTS;

    static {
        Map<String, String> fingerprints = new LinkedHashMap<>();
        fingerprints.put("skit_ad_callback_key", "a1940103d6592ff823f8b589e743b3d9b4907d350dfa8b9b364e841d16b18e0a");
        fingerprints.put("skit_ad_reward_secret_version", "9ec21f98175712cf938a1057dd935882a3fd6eecc23c82b15302f4fa9f8ddac9");
        fingerprints.put("skit_ad_policy_snapshot", "2f0a918c54d81d5a565d4dab8d38a82b946f0dbd87d69ca4b321666a32e133a7");
        fingerprints.put("skit_ad_session", "4901f4d3c58fae2420047dc42d5d5f0ccd7f4f31cd2eea1a968ce7734794808a");
        fingerprints.put("skit_ad_client_event", "2d34e026d75a471c61874a900e21b3a2b1949116679a66b39c5f8774b1d3b299");
        fingerprints.put("skit_ad_callback_edge_attempt", "ab55d0b6fa8fe448d303ea695fd6005a4b471ea26cf1b53ae01f96afb107d1ee");
        fingerprints.put("skit_ad_callback_inbox", "fe44ff21dd5d08f1f9967f03bfa2974173a135b4d60b9fa2c2a6e0f234331f25");
        fingerprints.put("skit_ad_callback_attempt", "a9a9b145aa4783195e042919ed7ae3b38c7a09278b1504ecdae2bc9ebae999e2");
        fingerprints.put("skit_ad_network_capability", "ad7592141bd45254be8246a37a56057e1a8bae8dc1d71ae2b2df0a7a17d179d7");
        fingerprints.put("skit_content_entitlement", "36d73b7f1732a62892c32f25e602b460e686316c856d6e6dbe4f75aa5ea270a4");
        fingerprints.put("skit_entitlement_grant", "9877c3c2cd73839e07340ac2d928b6d39841bc2fab35d2d5bd2c127b63657a47");
        fingerprints.put("skit_native_player_grant", "37d5b347daffad7f0926fdbf4d22b4af00b51303a24a2fb4b103b6a85bb8d940");
        fingerprints.put("skit_ad_report_pull", "ea539388d6f24834fc75ae0acde66dee2dbc549791a059011ae7e46093191525");
        fingerprints.put("skit_ad_reconciliation_bucket", "68442b19f864e24ff19fde49e5637ff80cb6d321bd557516b8ac2553efa948e4");
        fingerprints.put("skit_ad_reconciliation_revision", "31d3209bc4cc6d0387ead445e296bb99ba7c0d9698a389442d25fb87c129b4a9");
        fingerprints.put("skit_tenant_ad_capability", "49fbe067f81d784e4b4b135988509b6783f2d46702c9f3f5e09d1dd5ae99e866");
        fingerprints.put("skit_invite_code_registry", "b51c7dd17b504e1d034ad3c50267e84713097209f7d1617229eeff2b19c8c42a");
        EXPECTED_FINGERPRINTS = Collections.unmodifiableMap(fingerprints);
    }

    private static final String TABLE_QUERY = "SELECT CONCAT(ENGINE,'|',TABLE_COLLATION) "
            + "FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=?";
    private static final String COLUMN_QUERY = "SELECT CONCAT(LPAD(ORDINAL_POSITION,4,'0'),'|',COLUMN_NAME,'|',"
            + "COLUMN_TYPE,'|',IS_NULLABLE,'|',COALESCE(COLUMN_DEFAULT,'<NULL>'),'|',EXTRA,'|',"
            + "COALESCE(GENERATION_EXPRESSION,'<NULL>')) FROM information_schema.COLUMNS "
            + "WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=? ORDER BY ORDINAL_POSITION";
    private static final String INDEX_QUERY = "SELECT CONCAT(INDEX_NAME,'|',NON_UNIQUE,'|',"
            + "LPAD(SEQ_IN_INDEX,4,'0'),'|',COALESCE(COLUMN_NAME,'<NULL>'),'|',COALESCE(SUB_PART,'<NULL>'),"
            + "'|',COALESCE(COLLATION,'<NULL>'),'|',INDEX_TYPE,'|',COALESCE(EXPRESSION,'<NULL>')) "
            + "FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=? "
            + "ORDER BY INDEX_NAME,SEQ_IN_INDEX";
    private static final String FOREIGN_KEY_QUERY = "SELECT CONCAT(k.CONSTRAINT_NAME,'|',"
            + "LPAD(k.ORDINAL_POSITION,4,'0'),'|',k.COLUMN_NAME,'|',k.REFERENCED_TABLE_NAME,'|',"
            + "k.REFERENCED_COLUMN_NAME,'|',r.UPDATE_RULE,'|',r.DELETE_RULE) "
            + "FROM information_schema.KEY_COLUMN_USAGE k JOIN information_schema.REFERENTIAL_CONSTRAINTS r "
            + "ON r.CONSTRAINT_SCHEMA=k.CONSTRAINT_SCHEMA AND r.CONSTRAINT_NAME=k.CONSTRAINT_NAME "
            + "AND r.TABLE_NAME=k.TABLE_NAME WHERE k.TABLE_SCHEMA=DATABASE() AND k.TABLE_NAME=? "
            + "AND k.REFERENCED_TABLE_NAME IS NOT NULL ORDER BY k.CONSTRAINT_NAME,k.ORDINAL_POSITION";
    private static final String CHECK_QUERY = "SELECT CONCAT(tc.CONSTRAINT_NAME,'|',cc.CHECK_CLAUSE) "
            + "FROM information_schema.TABLE_CONSTRAINTS tc JOIN information_schema.CHECK_CONSTRAINTS cc "
            + "ON cc.CONSTRAINT_SCHEMA=tc.CONSTRAINT_SCHEMA AND cc.CONSTRAINT_NAME=tc.CONSTRAINT_NAME "
            + "WHERE tc.TABLE_SCHEMA=DATABASE() AND tc.TABLE_NAME=? AND tc.CONSTRAINT_TYPE='CHECK' "
            + "ORDER BY tc.CONSTRAINT_NAME";

    private SkitAdSchemaSignature() {
    }

    public static String fingerprint(JdbcTemplate jdbc, String table) {
        List<String> manifest = new ArrayList<>();
        appendSection(manifest, "table", jdbc.queryForList(TABLE_QUERY, String.class, table));
        appendSection(manifest, "columns", jdbc.queryForList(COLUMN_QUERY, String.class, table));
        appendSection(manifest, "indexes", jdbc.queryForList(INDEX_QUERY, String.class, table));
        appendSection(manifest, "foreign-keys", jdbc.queryForList(FOREIGN_KEY_QUERY, String.class, table));
        appendSection(manifest, "checks", jdbc.queryForList(CHECK_QUERY, String.class, table));
        return sha256(manifest);
    }

    static Map<String, String> expectedFingerprints() {
        return EXPECTED_FINGERPRINTS;
    }

    private static void appendSection(List<String> manifest, String section, List<String> rows) {
        manifest.add(section + ":" + rows.size());
        manifest.addAll(rows);
    }

    private static String sha256(List<String> manifest) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String row : manifest) {
                byte[] value = row.getBytes(StandardCharsets.UTF_8);
                digest.update(Integer.toString(value.length).getBytes(StandardCharsets.US_ASCII));
                digest.update((byte) ':');
                digest.update(value);
            }
            byte[] hash = digest.digest();
            StringBuilder result = new StringBuilder(hash.length * 2);
            for (byte item : hash) {
                result.append(String.format("%02x", item & 0xff));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

}
