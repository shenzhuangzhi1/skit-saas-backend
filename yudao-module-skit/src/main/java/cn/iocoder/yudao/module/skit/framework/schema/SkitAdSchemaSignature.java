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
    private static final Map<String, String> EXPECTED_TASK_5_HARDENED_FINGERPRINTS;
    private static final Map<String, String> EXPECTED_TASK_7_HARDENED_FINGERPRINTS;
    private static final Map<String, String> EXPECTED_TASK_10_FINAL_FINGERPRINTS;
    private static final Map<String, String> EXPECTED_TASK_12_FINAL_FINGERPRINTS;
    private static final Map<String, Map<String, List<String>>> RELEASED_ADDITIVE_INDEXES;
    private static final Map<String, Map<String, List<String>>> TASK_11_ADDITIVE_INDEXES;
    private static final Map<String, Map<String, List<String>>> RELEASED_COLUMN_DEFINITIONS;
    private static final Map<String, Map<String, List<String>>> RELEASED_FOREIGN_KEY_DEFINITIONS;
    private static final Map<String, Map<String, String>> RELEASED_CHECK_DEFINITIONS;

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

        Map<String, String> task5Fingerprints = new LinkedHashMap<>();
        task5Fingerprints.put("skit_ad_session",
                "f649db9f7a89ad00c82f5926dd73172bf000e949d0505349b98ab74fbd4f3882");
        task5Fingerprints.put("skit_content_entitlement",
                "c800aab0369a1b674589ca3acf7e0231a14daad3df81a39c17d7af080f8856a4");
        task5Fingerprints.put("skit_entitlement_grant",
                "fff30f3be8e05d4d41e9154b7943eca9d2ddc96af54c264b6b5e7cb755637566");
        task5Fingerprints.put("skit_native_player_grant",
                "db8743aa9673bb67d6e6aaff4ed116bae07952ba90e22760f0632b83eee48a93");
        EXPECTED_TASK_5_HARDENED_FINGERPRINTS = Collections.unmodifiableMap(task5Fingerprints);

        Map<String, String> task7Fingerprints = new LinkedHashMap<>();
        task7Fingerprints.put("skit_ad_session",
                "068e9a18a54efa91857d96dbcb73243e7d8fea306bb4816370a04e46624249db");
        task7Fingerprints.put("skit_ad_callback_edge_attempt",
                "fef7596faa28efe57aa474ceb1ac20c3d899c62e3039789b329ff57ad0f048fb");
        task7Fingerprints.put("skit_ad_callback_inbox",
                "c94ba8627c659562732ed5d17381ed11981f6f9e117bf693bdc406750e483fcc");
        task7Fingerprints.put("skit_ad_callback_attempt",
                "94225c801d0d4a5e9ed512dce713ae5b35379d0291bb0ea7f20165c0cd8ac8ee");
        task7Fingerprints.put("skit_ad_revenue_event",
                "9f6b4365dfd6ec1c689ab803226b6bd2387f493585f6376695ddb2131af19e90");
        task7Fingerprints.put("skit_commission_ledger",
                "1a3c14086caf50ecf92de439b03312faa2f25eae45ebd145801f1c2dd6b9f567");
        task7Fingerprints.put("skit_ad_network_capability",
                "5fcc44c3b1b401dfcb218f1b559baa22d9b2752f22653d073d6e4dc054a66eeb");
        task7Fingerprints.put("skit_entitlement_grant",
                "a6ec4bbcd5cdbb850f59153add2381c7e46697a296cb70fc4c8ae51a7af28e39");
        EXPECTED_TASK_7_HARDENED_FINGERPRINTS = Collections.unmodifiableMap(task7Fingerprints);

        Map<String, String> task10FinalFingerprints = new LinkedHashMap<>();
        task10FinalFingerprints.put("skit_ad_report_pull",
                "8f9500124e75c441cff2014401f8bb4b5c0745e81efab7eb1ee5f5529a2d4831");
        task10FinalFingerprints.put("skit_ad_reconciliation_bucket",
                "ca37f0219b62bdb4f4f2af63712198dadb59acad2ebbc98bf45f4d3c4fb09016");
        task10FinalFingerprints.put("skit_ad_reconciliation_revision",
                "de9bf597e6f3083031344462f6ea3f9f39dbf56d1ebd9b0f37888240a620d3f9");
        task10FinalFingerprints.put("skit_ad_revenue_event",
                "48c873ae195ea504c238f30f1987f3150f0a6d6dee87d96ee7a6df42d62e210c");
        task10FinalFingerprints.put("skit_ad_reporting_credential_version",
                "ceb9ace6a72a21828fe0982589e66d4826d1e63c2b08fb079bc3d011d9492986");
        task10FinalFingerprints.put("skit_ad_reconciliation_allocation",
                "5f796cf313bf446a39f1942771e3988a69a8a8bc5c6dac4e95b9e27e3fe45a35");
        task10FinalFingerprints.put("skit_ad_reconciliation_event_link",
                "25942741ea0a7ecfc1acd506590001afa6e63bb496a54c05b56a8d06da875ca4");
        EXPECTED_TASK_10_FINAL_FINGERPRINTS = Collections.unmodifiableMap(task10FinalFingerprints);

        Map<String, String> task12FinalFingerprints = new LinkedHashMap<>();
        task12FinalFingerprints.put("skit_tenant_ad_capability",
                "7a75a05eda968e7f2f32b8890cb2272c4de9f70c6f83ac6f5dfbd49fd95719c7");
        EXPECTED_TASK_12_FINAL_FINGERPRINTS = Collections.unmodifiableMap(task12FinalFingerprints);

        Map<String, Map<String, List<String>>> task11Indexes = new LinkedHashMap<>();
        addIndex(task11Indexes, "skit_ad_session", "idx_skit_ad_session_management_account", false,
                "tenant_id,ad_account_id,create_time,id");
        addIndex(task11Indexes, "skit_ad_session", "idx_skit_ad_session_management_reward", false,
                "tenant_id,reward_verification_status,create_time,id");
        addIndex(task11Indexes, "skit_ad_session", "idx_skit_ad_session_global_created", false,
                "create_time,id");
        addIndex(task11Indexes, "skit_ad_callback_inbox", "idx_skit_callback_inbox_management_account", false,
                "tenant_id,ad_account_id,received_at,id");
        addIndex(task11Indexes, "skit_ad_callback_inbox", "idx_skit_callback_inbox_management_status", false,
                "tenant_id,processing_status,received_at,id");
        addIndex(task11Indexes, "skit_ad_callback_inbox", "idx_skit_ad_callback_global_received", false,
                "received_at,id");
        addIndex(task11Indexes, "skit_ad_revenue_event", "idx_skit_revenue_management_time", false,
                "tenant_id,occurred_time,id");
        addIndex(task11Indexes, "skit_ad_revenue_event", "idx_skit_revenue_management_member", false,
                "tenant_id,source_member_id,occurred_time,id");
        addIndex(task11Indexes, "skit_ad_revenue_event", "idx_skit_revenue_management_reconciliation", false,
                "tenant_id,reconciliation_status,source_currency,occurred_time,id");
        addIndex(task11Indexes, "skit_ad_revenue_event", "idx_skit_ad_revenue_global_occurred", false,
                "occurred_time,id");
        addIndex(task11Indexes, "skit_commission_ledger", "idx_skit_ledger_management_balance", false,
                "tenant_id,currency,balance_bucket,create_time,id");
        addIndex(task11Indexes, "skit_commission_ledger", "idx_skit_ledger_management_event", false,
                "tenant_id,event_id,id");
        addIndex(task11Indexes, "skit_ad_report_pull", "idx_skit_report_pull_management_account", false,
                "tenant_id,ad_account_id,pulled_at,id");
        addIndex(task11Indexes, "skit_ad_report_pull", "idx_skit_report_pull_management_status", false,
                "tenant_id,status,pulled_at,id");
        addIndex(task11Indexes, "skit_ad_reconciliation_bucket",
                "idx_skit_recon_bucket_management_account", false,
                "tenant_id,ad_account_id,report_date,id");
        addIndex(task11Indexes, "skit_ad_reconciliation_bucket", "idx_skit_ad_recon_bucket_global_date", false,
                "report_date,id");
        addIndex(task11Indexes, "skit_ad_reconciliation_revision",
                "idx_skit_recon_revision_management_bucket", false,
                "tenant_id,reconciliation_bucket_id,revision_no,id");
        addIndex(task11Indexes, "skit_member_closure", "idx_skit_member_closure_ancestor_distance", false,
                "tenant_id,ancestor_id,distance,descendant_id");
        TASK_11_ADDITIVE_INDEXES = immutableIndexMap(task11Indexes);

        Map<String, Map<String, List<String>>> releasedIndexes = copyIndexMap(task11Indexes);
        addIndex(releasedIndexes, "skit_ad_report_pull", "idx_skit_report_pull_request", false,
                "tenant_id,ad_account_id,report_date,request_hash");
        addIndex(releasedIndexes, "skit_ad_report_pull", "idx_skit_report_pull_credential", false,
                "tenant_id,ad_account_id,credential_version");
        addIndex(releasedIndexes, "skit_ad_report_pull", "idx_skit_report_pull_final_window", false,
                "tenant_id,ad_account_id,report_date,final_window,status");
        addIndex(releasedIndexes, "skit_ad_revenue_event", "idx_skit_revenue_report_pending", false,
                "tenant_id,ad_account_id,reconciliation_revision_id,occurred_time,id");
        RELEASED_ADDITIVE_INDEXES = immutableIndexMap(releasedIndexes);

        Map<String, Map<String, List<String>>> columnDefinitions = new LinkedHashMap<>();
        addColumnDefinition(columnDefinitions, "skit_content_entitlement", "lease_activated_at",
                "datetime|NO|<NULL>||<NULL>", "datetime|YES|<NULL>||<NULL>",
                "datetime|NO|<NULL>||", "datetime|YES|<NULL>||");
        addColumnDefinition(columnDefinitions, "skit_ad_report_pull", "report_date",
                "date|NO|<NULL>||<NULL>", "date|YES|<NULL>||<NULL>", "date|YES|<NULL>||");
        addColumnDefinition(columnDefinitions, "skit_ad_report_pull", "report_timezone",
                "varchar(64)|NO|UTC+8||<NULL>");
        addColumnDefinition(columnDefinitions, "skit_ad_report_pull", "currency",
                "char(3)|NO|USD||<NULL>");
        addColumnDefinition(columnDefinitions, "skit_ad_report_pull", "amount_scale",
                "tinyint|NO|8||<NULL>");
        addColumnDefinition(columnDefinitions, "skit_ad_report_pull", "request_hash",
                "binary(32)|NO|<NULL>||<NULL>", "binary(32)|YES|<NULL>||<NULL>",
                "binary(32)|YES|<NULL>||");
        addColumnDefinition(columnDefinitions, "skit_ad_report_pull", "credential_version",
                "int|YES|<NULL>||<NULL>");
        addColumnDefinition(columnDefinitions, "skit_ad_report_pull", "final_window",
                "bit(1)|NO|b'0'||<NULL>", "bit(1)|NO|0||<NULL>");
        addColumnDefinition(columnDefinitions, "skit_ad_reconciliation_bucket", "app_id",
                "varchar(128)|NO|||<NULL>");
        addColumnDefinition(columnDefinitions, "skit_ad_reconciliation_bucket", "ad_format",
                "varchar(32)|NO|||<NULL>");
        addColumnDefinition(columnDefinitions, "skit_ad_reconciliation_bucket", "network_account_id",
                "varchar(128)|NO|||<NULL>");
        addColumnDefinition(columnDefinitions, "skit_ad_reconciliation_bucket", "attributable_actual_units",
                "bigint|NO|0||<NULL>");
        addColumnDefinition(columnDefinitions, "skit_ad_reconciliation_bucket", "suspense_units",
                "bigint|NO|0||<NULL>");
        addColumnDefinition(columnDefinitions, "skit_ad_reconciliation_bucket",
                "report_impressions_available", "bit(1)|NO|b'1'||<NULL>", "bit(1)|NO|1||<NULL>");
        addColumnDefinition(columnDefinitions, "skit_ad_reconciliation_revision",
                "source_report_impressions", "bigint|NO|0||<NULL>");
        addColumnDefinition(columnDefinitions, "skit_ad_reconciliation_revision",
                "source_report_impressions_available", "bit(1)|NO|b'1'||<NULL>",
                "bit(1)|NO|1||<NULL>");
        addColumnDefinition(columnDefinitions, "skit_ad_reconciliation_revision", "matched_event_count",
                "bigint|NO|0||<NULL>");
        addColumnDefinition(columnDefinitions, "skit_ad_reconciliation_revision", "status",
                "varchar(32)|NO|APPLIED||<NULL>");
        addColumnDefinition(columnDefinitions, "skit_tenant_ad_capability", "dedicated_unlock_placement_id",
                "varchar(128)|NO|||<NULL>");
        addColumnDefinition(columnDefinitions, "skit_tenant_ad_capability", "dedicated_placement_verified_at",
                "datetime|YES|<NULL>||<NULL>");
        addColumnDefinition(columnDefinitions, "skit_tenant_ad_capability",
                "reward_callback_template_verified_at", "datetime|YES|<NULL>||<NULL>");
        addColumnDefinition(columnDefinitions, "skit_tenant_ad_capability",
                "impression_callback_template_verified_at", "datetime|YES|<NULL>||<NULL>");
        addColumnDefinition(columnDefinitions, "skit_tenant_ad_capability", "unlock_network_firm_ids_json",
                "varchar(512)|NO|[]||<NULL>");
        addColumnDefinition(columnDefinitions, "skit_tenant_ad_capability", "shadow_test_member_ids_json",
                "varchar(4096)|NO|[]||<NULL>");
        addColumnDefinition(columnDefinitions, "skit_tenant_ad_capability", "min_protocol_version",
                "int|NO|1||<NULL>");
        RELEASED_COLUMN_DEFINITIONS = immutableIndexMap(columnDefinitions);

        Map<String, Map<String, List<String>>> foreignKeyDefinitions = new LinkedHashMap<>();
        foreignKeyDefinitions.computeIfAbsent("skit_ad_report_pull", ignored -> new LinkedHashMap<>())
                .put("fk_skit_report_pull_credential", Collections.unmodifiableList(foreignKeyRows(
                        "fk_skit_report_pull_credential", "tenant_id,ad_account_id,credential_version",
                        "skit_ad_reporting_credential_version",
                        "tenant_id,ad_account_id,credential_version")));
        RELEASED_FOREIGN_KEY_DEFINITIONS = immutableIndexMap(foreignKeyDefinitions);

        Map<String, Map<String, String>> checkDefinitions = new LinkedHashMap<>();
        addCheckDefinition(checkDefinitions, "skit_ad_report_pull", "ck_skit_report_pull_money",
                "REGEXP_LIKE(`currency`,'^[A-Z]{3}$') AND `amount_scale` BETWEEN 0 AND 18");
        addCheckDefinition(checkDefinitions, "skit_ad_report_pull", "ck_skit_report_pull_timezone",
                "`report_timezone` IN ('UTC-8','UTC+8','UTC+0')");
        addCheckDefinition(checkDefinitions, "skit_ad_report_pull", "ck_skit_report_pull_credential_version",
                "`credential_version` IS NULL OR `credential_version` > 0");
        addCheckDefinition(checkDefinitions, "skit_ad_report_pull", "ck_skit_report_pull_status",
                "(`status`='SUCCEEDED' AND `error_code` IS NULL) OR "
                        + "(`status`='FAILED' AND `error_code` IS NOT NULL)");
        addCheckDefinition(checkDefinitions, "skit_ad_reconciliation_bucket",
                "ck_skit_recon_bucket_task10_amounts",
                "`attributable_actual_units` >= 0 AND `suspense_units` >= 0 AND "
                        + "`attributable_actual_units` + `suspense_units` = `report_actual_units`");
        addCheckDefinition(checkDefinitions, "skit_ad_reconciliation_bucket",
                "ck_skit_recon_bucket_task10_impressions",
                "`report_impressions_available`=b'1' OR `report_impressions`=0");
        addCheckDefinition(checkDefinitions, "skit_ad_reconciliation_revision",
                "ck_skit_recon_revision_task10_counts",
                "`source_report_impressions` >= 0 AND `matched_event_count` >= 0");
        addCheckDefinition(checkDefinitions, "skit_ad_reconciliation_revision",
                "ck_skit_recon_revision_task10_impressions",
                "`source_report_impressions_available`=b'1' OR `source_report_impressions`=0");
        addCheckDefinition(checkDefinitions, "skit_ad_reconciliation_revision",
                "ck_skit_recon_revision_task10_status",
                "`status` IN ('APPLIED','PARTIAL','SUSPENSE','FAILED')");
        addCheckDefinition(checkDefinitions, "skit_tenant_ad_capability",
                "ck_skit_tenant_capability_network_json",
                "JSON_VALID(`unlock_network_firm_ids_json`) AND "
                        + "JSON_TYPE(JSON_EXTRACT(`unlock_network_firm_ids_json`,'$'))='ARRAY'");
        addCheckDefinition(checkDefinitions, "skit_tenant_ad_capability",
                "ck_skit_tenant_capability_shadow_json",
                "JSON_VALID(`shadow_test_member_ids_json`) AND "
                        + "JSON_TYPE(JSON_EXTRACT(`shadow_test_member_ids_json`,'$'))='ARRAY'");
        addCheckDefinition(checkDefinitions, "skit_tenant_ad_capability",
                "ck_skit_tenant_capability_protocol", "`min_protocol_version` > 0");
        Map<String, Map<String, String>> immutableChecks = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, String>> entry : checkDefinitions.entrySet()) {
            immutableChecks.put(entry.getKey(), Collections.unmodifiableMap(new LinkedHashMap<>(entry.getValue())));
        }
        RELEASED_CHECK_DEFINITIONS = Collections.unmodifiableMap(immutableChecks);
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
        return fingerprint(jdbc, table, FingerprintEnvelope.RELEASED_BASE);
    }

    static String rawFingerprint(JdbcTemplate jdbc, String table) {
        return fingerprint(jdbc, table, FingerprintEnvelope.RAW);
    }

    static String task10FinalFingerprint(JdbcTemplate jdbc, String table) {
        return fingerprint(jdbc, table, FingerprintEnvelope.TASK_10_FINAL);
    }

    private static String fingerprint(JdbcTemplate jdbc, String table, FingerprintEnvelope envelope) {
        List<String> manifest = new ArrayList<>();
        appendSection(manifest, "table", jdbc.queryForList(TABLE_QUERY, String.class, table));
        List<String> columns = jdbc.queryForList(COLUMN_QUERY, String.class, table);
        List<String> indexes = jdbc.queryForList(INDEX_QUERY, String.class, table);
        List<String> foreignKeys = jdbc.queryForList(FOREIGN_KEY_QUERY, String.class, table);
        List<String> checks = jdbc.queryForList(CHECK_QUERY, String.class, table);
        if (envelope == FingerprintEnvelope.RELEASED_BASE) {
            columns = releasedColumnRows(table, columns);
            indexes = releasedIndexRows(table, indexes);
            foreignKeys = releasedForeignKeyRows(table, foreignKeys);
            checks = releasedCheckRows(table, checks);
        } else if (envelope == FingerprintEnvelope.TASK_10_FINAL) {
            indexes = projectExactAdditiveIndexes(table, indexes, TASK_11_ADDITIVE_INDEXES);
        }
        appendSection(manifest, "columns", columns);
        appendSection(manifest, "indexes", indexes);
        appendSection(manifest, "foreign-keys", foreignKeys);
        appendSection(manifest, "checks", checks);
        return sha256(manifest);
    }

    /**
     * Projects a current table back onto the released Task 2/5/7 index envelope.
     *
     * <p>Task 10 and Task 11 added tenant-leading query indexes without changing any released table
     * contract. Only those exact, table-qualified index names are excluded here. Their definitions
     * remain mandatory and are validated independently by their owning migration validators. Unknown
     * indexes, and an allowlisted name attached to any other table, remain fingerprint-visible.</p>
     */
    static List<String> releasedIndexRows(String table, List<String> rows) {
        List<String> projected = projectExactAdditiveIndexes(table, rows, RELEASED_ADDITIVE_INDEXES);
        return projectTask10ReplacementIndex(table, projected);
    }

    static List<String> releasedColumnRows(String table, List<String> rows) {
        Map<String, List<String>> definitions = RELEASED_COLUMN_DEFINITIONS.get(table);
        if (definitions == null || definitions.isEmpty()) {
            return rows;
        }
        List<String> result = new ArrayList<>(rows.size());
        for (String row : rows) {
            String name = columnName(row);
            List<String> allowedShapes = definitions.get(name);
            if (allowedShapes == null) {
                result.add(row);
                continue;
            }
            String shape = columnShape(row);
            String comparableShape = normalizeEmptyGenerationExpression(shape);
            boolean compatible = allowedShapes.stream()
                    .map(SkitAdSchemaSignature::normalizeEmptyGenerationExpression)
                    .anyMatch(comparableShape::equals);
            if (!compatible) {
                throw new IllegalStateException("Incompatible additive column " + table + "." + name
                        + ": expected one of " + allowedShapes + ", actual=" + shape);
            }
        }
        // An additive migration may deliberately place its column beside the field it extends
        // (for example lease_activated_at AFTER granted_at). Once that column is projected out,
        // MySQL's physical ORDINAL_POSITION values for every following released column are one
        // position higher than in the immutable released fingerprint. Re-number the remaining
        // rows while preserving their physical order so both upgrade and fresh-bootstrap paths
        // project onto the same released envelope.
        return renumberColumnOrdinals(result);
    }

    private static List<String> renumberColumnOrdinals(List<String> rows) {
        List<String> result = new ArrayList<>(rows.size());
        for (int index = 0; index < rows.size(); index++) {
            String row = rows.get(index);
            int separator = row.indexOf('|');
            if (separator < 0) {
                result.add(row);
                continue;
            }
            result.add(String.format("%04d", index + 1) + row.substring(separator));
        }
        return result;
    }

    static List<String> releasedForeignKeyRows(String table, List<String> rows) {
        Map<String, List<String>> definitions = RELEASED_FOREIGN_KEY_DEFINITIONS.get(table);
        if (definitions == null || definitions.isEmpty()) {
            return rows;
        }
        Map<String, List<String>> actualGroups = groupByObjectName(rows);
        for (Map.Entry<String, List<String>> definition : definitions.entrySet()) {
            List<String> actual = actualGroups.get(definition.getKey());
            if (actual != null && !definition.getValue().equals(actual)) {
                throw new IllegalStateException("Incompatible additive foreign key " + table + "."
                        + definition.getKey() + ": expected=" + definition.getValue() + ", actual=" + actual);
            }
        }
        List<String> result = new ArrayList<>(rows.size());
        for (String row : rows) {
            if (!definitions.containsKey(objectName(row))) {
                result.add(row);
            }
        }
        return result;
    }

    static List<String> releasedCheckRows(String table, List<String> rows) {
        Map<String, String> definitions = RELEASED_CHECK_DEFINITIONS.get(table);
        if (definitions == null || definitions.isEmpty()) {
            return rows;
        }
        List<String> result = new ArrayList<>(rows.size());
        for (String row : rows) {
            String name = objectName(row);
            String expected = definitions.get(name);
            if (expected == null) {
                result.add(row);
                continue;
            }
            int separator = row.indexOf('|');
            String actualExpression = separator < 0 ? "" : row.substring(separator + 1);
            String expectedNormalized = SkitSchemaInitializer.normalizeCheckExpression(expected);
            String actualNormalized = SkitSchemaInitializer.normalizeCheckExpression(actualExpression);
            if (!expectedNormalized.equals(actualNormalized)) {
                throw new IllegalStateException("Incompatible additive check " + table + "." + name
                        + ": expected=" + expectedNormalized + ", actual=" + actualNormalized);
            }
        }
        return result;
    }

    private static List<String> projectExactAdditiveIndexes(String table, List<String> rows,
            Map<String, Map<String, List<String>>> definitionsByTable) {
        Map<String, List<String>> definitions = definitionsByTable.get(table);
        if (definitions == null || definitions.isEmpty()) {
            return rows;
        }
        Map<String, List<String>> actualGroups = groupByObjectName(rows);
        for (Map.Entry<String, List<String>> definition : definitions.entrySet()) {
            List<String> actual = actualGroups.get(definition.getKey());
            if (actual != null && !definition.getValue().equals(actual)) {
                throw new IllegalStateException("Incompatible additive index " + table + "."
                        + definition.getKey() + ": expected=" + definition.getValue() + ", actual=" + actual);
            }
        }
        List<String> result = new ArrayList<>(rows.size());
        for (String row : rows) {
            if (!definitions.containsKey(objectName(row))) {
                result.add(row);
            }
        }
        return result;
    }

    private static List<String> projectTask10ReplacementIndex(String table, List<String> rows) {
        String index;
        List<String> released;
        List<String> replacement;
        if ("skit_ad_report_pull".equals(table)) {
            index = "uk_skit_report_pull_response";
            released = indexRows(index, true,
                    "tenant_id,ad_account_id,range_start,range_end,response_hash");
            replacement = indexRows(index, true,
                    "tenant_id,ad_account_id,range_start,range_end,request_hash,response_hash,"
                            + "credential_version,final_window");
        } else if ("skit_ad_reconciliation_bucket".equals(table)) {
            index = "uk_skit_recon_bucket_identity";
            released = indexRows(index, true,
                    "tenant_id,ad_account_id,bucket_key,report_date,report_timezone,placement_id,"
                            + "network_firm_id,adsource_id,currency");
            replacement = indexRows(index, true,
                    "tenant_id,ad_account_id,bucket_key,report_date,report_timezone,app_id,placement_id,"
                            + "ad_format,network_account_id,network_firm_id,adsource_id,currency");
        } else {
            return rows;
        }
        List<String> actual = groupByObjectName(rows).get(index);
        if (!replacement.equals(actual)) {
            return rows;
        }
        List<String> result = new ArrayList<>(rows.size() - replacement.size() + released.size());
        boolean emitted = false;
        for (String row : rows) {
            if (index.equals(objectName(row))) {
                if (!emitted) {
                    result.addAll(released);
                    emitted = true;
                }
            } else {
                result.add(row);
            }
        }
        return result;
    }

    private static String columnName(String row) {
        int first = row.indexOf('|');
        int second = first < 0 ? -1 : row.indexOf('|', first + 1);
        return first < 0 ? row : second < 0 ? row.substring(first + 1) : row.substring(first + 1, second);
    }

    private static String columnShape(String row) {
        int first = row.indexOf('|');
        int second = first < 0 ? -1 : row.indexOf('|', first + 1);
        return second < 0 ? "" : row.substring(second + 1);
    }

    private static String normalizeEmptyGenerationExpression(String shape) {
        return shape.endsWith("|") ? shape + "<NULL>" : shape;
    }

    private static String objectName(String row) {
        int separator = row.indexOf('|');
        return separator < 0 ? row : row.substring(0, separator);
    }

    private static Map<String, List<String>> groupByObjectName(List<String> rows) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (String row : rows) {
            result.computeIfAbsent(objectName(row), ignored -> new ArrayList<>()).add(row);
        }
        return result;
    }

    static Map<String, String> expectedFingerprints() {
        return EXPECTED_FINGERPRINTS;
    }

    static Map<String, String> expectedTask5HardenedFingerprints() {
        return EXPECTED_TASK_5_HARDENED_FINGERPRINTS;
    }

    static Map<String, String> expectedTask7HardenedFingerprints() {
        return EXPECTED_TASK_7_HARDENED_FINGERPRINTS;
    }

    static Map<String, String> expectedTask10FinalFingerprints() {
        return EXPECTED_TASK_10_FINAL_FINGERPRINTS;
    }

    static Map<String, String> expectedTask12FinalFingerprints() {
        return EXPECTED_TASK_12_FINAL_FINGERPRINTS;
    }

    private static void addIndex(Map<String, Map<String, List<String>>> indexes, String table,
                                 String index, boolean unique, String columns) {
        indexes.computeIfAbsent(table, ignored -> new LinkedHashMap<>())
                .put(index, Collections.unmodifiableList(indexRows(index, unique, columns)));
    }

    private static void addColumnDefinition(Map<String, Map<String, List<String>>> definitions,
                                            String table, String column, String... allowedShapes) {
        definitions.computeIfAbsent(table, ignored -> new LinkedHashMap<>())
                .put(column, immutableList(allowedShapes));
    }

    private static List<String> foreignKeyRows(String constraint, String columns,
                                                String referencedTable, String referencedColumns) {
        String[] local = columns.split(",");
        String[] referenced = referencedColumns.split(",");
        List<String> result = new ArrayList<>();
        for (int sequence = 0; sequence < local.length; sequence++) {
            result.add(constraint + "|" + String.format("%04d", sequence + 1) + "|" + local[sequence]
                    + "|" + referencedTable + "|" + referenced[sequence] + "|RESTRICT|RESTRICT");
        }
        return result;
    }

    private static void addCheckDefinition(Map<String, Map<String, String>> definitions,
                                           String table, String constraint, String expression) {
        definitions.computeIfAbsent(table, ignored -> new LinkedHashMap<>()).put(constraint, expression);
    }

    private static List<String> indexRows(String index, boolean unique, String columns) {
        List<String> result = new ArrayList<>();
        String[] names = columns.split(",");
        for (int sequence = 0; sequence < names.length; sequence++) {
            result.add(index + "|" + (unique ? "0" : "1") + "|"
                    + String.format("%04d", sequence + 1) + "|" + names[sequence]
                    + "|<NULL>|A|BTREE|<NULL>");
        }
        return result;
    }

    private static Map<String, Map<String, List<String>>> copyIndexMap(
            Map<String, Map<String, List<String>>> source) {
        Map<String, Map<String, List<String>>> result = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, List<String>>> entry : source.entrySet()) {
            result.put(entry.getKey(), new LinkedHashMap<>(entry.getValue()));
        }
        return result;
    }

    private static Map<String, Map<String, List<String>>> immutableIndexMap(
            Map<String, Map<String, List<String>>> source) {
        Map<String, Map<String, List<String>>> result = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, List<String>>> entry : source.entrySet()) {
            result.put(entry.getKey(), Collections.unmodifiableMap(new LinkedHashMap<>(entry.getValue())));
        }
        return Collections.unmodifiableMap(result);
    }

    private static List<String> immutableList(String... values) {
        List<String> result = new ArrayList<>();
        Collections.addAll(result, values);
        return Collections.unmodifiableList(result);
    }

    private enum FingerprintEnvelope {
        RELEASED_BASE,
        TASK_10_FINAL,
        RAW
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
