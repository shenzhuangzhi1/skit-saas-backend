package cn.iocoder.yudao.module.skit.service.ad;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.module.skit.dal.dataobject.ad.SkitTenantAdCapabilityDO;
import cn.iocoder.yudao.module.skit.service.app.SkitRuntimeUpdateManifestVerifier;
import cn.iocoder.yudao.module.skit.service.reconciliation.SkitReportRequestScopeFingerprint;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.security.MessageDigest;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads only readiness metadata. No credential ciphertext, nonce, key material, raw callback payload,
 * or report body is selected, returned, or logged.
 */
@Component
public class SkitJdbcTenantAdReadinessEvidenceReader implements SkitTenantAdReadinessEvidenceReader {

    private static final int REPORT_MAX_AGE_HOURS = 48;
    private static final int CALLBACK_MAX_AGE_DAYS = 30;

    private final JdbcTemplate jdbcTemplate;
    private final SkitRuntimeUpdateManifestVerifier manifestVerifier;
    private final SkitCallbackPublicUrlService callbackPublicUrlService;

    public SkitJdbcTenantAdReadinessEvidenceReader(
            JdbcTemplate jdbcTemplate,
            SkitRuntimeUpdateManifestVerifier manifestVerifier,
            SkitCallbackPublicUrlService callbackPublicUrlService) {
        this.jdbcTemplate = jdbcTemplate;
        this.manifestVerifier = manifestVerifier;
        this.callbackPublicUrlService = callbackPublicUrlService;
    }

    @Override
    public SkitTenantAdReadinessEvidence read(Long tenantId, SkitTenantAdCapabilityDO capability) {
        SkitTenantAdReadinessEvidence result = new SkitTenantAdReadinessEvidence();
        if (tenantId == null || capability == null || !tenantId.equals(capability.getTenantId())) {
            return result;
        }
        result.setTenantActive(tenantActive(tenantId));
        Map<String, Object> account = accountMetadata(tenantId, capability.getAdAccountId());
        result.setAccountBelongsToTenant(!account.isEmpty());
        String accountPlacement = stringValue(account.get("placement_id"));
        result.setAccountReady(!account.isEmpty()
                && "TAKU".equals(stringValue(account.get("provider")))
                && intValue(account.get("status"), -1) == 0
                && StrUtil.isNotBlank(stringValue(account.get("app_id")))
                && StrUtil.isNotBlank(accountPlacement));

        CredentialEvidence callbackKey = credentialEvidence(tenantId, capability.getAdAccountId(),
                "skit_ad_callback_key", "key_version");
        result.setCallbackKeyConfigured(callbackKey.configured);
        result.setCallbackKeyVersion(callbackKey.version);
        result.setCallbackKeyIssuedAt(callbackKey.issuedAt);
        CredentialEvidence rewardSecret = credentialEvidence(tenantId, capability.getAdAccountId(),
                "skit_ad_reward_secret_version", "secret_version");
        result.setRewardSecretConfigured(rewardSecret.configured);
        result.setRewardSecretVersion(rewardSecret.version);
        result.setRewardSecretIssuedAt(rewardSecret.issuedAt);
        result.setCallbackPublicUrlHttps(callbackPublicUrlService.isHttps());

        boolean placementMatches = StrUtil.isNotBlank(capability.getDedicatedUnlockPlacementId())
                && capability.getDedicatedUnlockPlacementId().equals(accountPlacement);
        result.setDedicatedUnlockPlacement(placementMatches
                && capability.getDedicatedPlacementVerifiedAt() != null
                && dedicatedPlacementOwnedExactlyOnce(
                tenantId, capability.getAdAccountId(), capability.getDedicatedUnlockPlacementId()));
        result.setRewardCallbackTemplateVerified(
                capability.getRewardCallbackTemplateVerifiedAt() != null);
        result.setImpressionCallbackTemplateVerified(
                capability.getImpressionCallbackTemplateVerifiedAt() != null);

        Set<Integer> networkFirmIds = safeNetworkIds(capability.getUnlockNetworkFirmIdsJson());
        result.setUnlockNetworksAuthoritative(authoritativeNetworks(
                tenantId, capability.getAdAccountId(), networkFirmIds));

        ReportingEvidence reporting = reportingEvidence(tenantId, capability.getAdAccountId(), account);
        result.setReportingCredentialConfigured(reporting.credentialConfigured);
        result.setReportingPermissionVerified(reporting.permissionVerified);
        result.setReportFresh(reporting.reportFresh);
        result.setLastReportSuccessAt(reporting.lastReportSuccessAt);

        CallbackEvidence callbacks = callbackEvidence(tenantId, capability.getAdAccountId(),
                capability.getDedicatedUnlockPlacementId(), networkFirmIds);
        result.setSignedRewardCallbackObserved(callbacks.lastSignedRewardAt != null);
        result.setImpressionCallbackObserved(callbacks.lastImpressionAt != null);
        result.setLastSignedRewardCallbackAt(callbacks.lastSignedRewardAt);
        result.setLastImpressionCallbackAt(callbacks.lastImpressionAt);

        MemberEvidence members = memberEvidence(tenantId, capability.getShadowTestMemberIdsJson());
        result.setShadowMembersBelongToTenant(members.allBelongToTenant);
        result.setShadowMembersValid(members.allActiveAndNonEmpty);

        ReleaseEvidence release = releaseEvidence(tenantId, capability.getMinNativeVersion(),
                capability.getMinProtocolVersion());
        result.setNativeReleaseReady(release.nativeReleaseReady);
        result.setProtocolReady(release.protocolReady);
        return result;
    }

    private boolean tenantActive(Long tenantId) {
        return exists("SELECT COUNT(*) FROM `system_tenant` `t` JOIN `skit_agent` `a` "
                        + "ON `a`.`tenant_id`=`t`.`id` AND `a`.`deleted`=b'0' "
                        + "WHERE `t`.`id`=? AND `t`.`status`=0 AND `t`.`deleted`=b'0' "
                        + "AND `a`.`status`=0 AND `a`.`archived_time` IS NULL",
                tenantId);
    }

    private CredentialEvidence credentialEvidence(Long tenantId, Long accountId,
                                                  String table, String versionColumn) {
        CredentialEvidence result = new CredentialEvidence();
        if (accountId == null || accountId <= 0) {
            return result;
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT `" + versionColumn + "` AS `version`,`create_time` FROM `" + table + "` "
                        + "WHERE `tenant_id`=? AND `ad_account_id`=? AND `active`=b'1' "
                        + "AND `revoked_at` IS NULL AND `deleted`=b'0'",
                tenantId, accountId);
        if (rows.size() != 1) {
            return result;
        }
        int version = intValue(rows.get(0).get("version"), -1);
        LocalDateTime issuedAt = localDateTime(rows.get(0).get("create_time"));
        if (version <= 0 || issuedAt == null) {
            return result;
        }
        result.configured = true;
        result.version = version;
        result.issuedAt = issuedAt;
        return result;
    }

    private boolean dedicatedPlacementOwnedExactlyOnce(Long tenantId, Long accountId, String placementId) {
        if (tenantId == null || accountId == null || StrUtil.isBlank(placementId)) {
            return false;
        }
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM `skit_ad_account` WHERE `provider`='TAKU' AND `status`=0 "
                        + "AND `deleted`=b'0' AND JSON_VALID(`config_data`) "
                        + "AND JSON_UNQUOTE(JSON_EXTRACT(`config_data`,'$.placementId'))=? "
                        + "AND NOT (`tenant_id`=? AND `id`=?)",
                Integer.class, placementId, tenantId, accountId);
        return count != null && count == 0;
    }

    private Map<String, Object> accountMetadata(Long tenantId, Long adAccountId) {
        if (adAccountId == null || adAccountId <= 0) {
            return Collections.emptyMap();
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT `provider`,`status`,`app_id`,CASE WHEN JSON_VALID(`config_data`) THEN "
                        + "JSON_UNQUOTE(JSON_EXTRACT(`config_data`,'$.placementId')) ELSE NULL END AS `placement_id`,"
                        + "CASE WHEN JSON_VALID(`config_data`) THEN "
                        + "JSON_UNQUOTE(JSON_EXTRACT(`config_data`,'$.adFormat')) ELSE NULL END AS `ad_format`,"
                        + "`report_timezone`,`report_currency`,`report_amount_scale`,`report_last_success_at` "
                        + "FROM `skit_ad_account` WHERE `tenant_id`=? AND `id`=? AND `deleted`=b'0'",
                tenantId, adAccountId);
        return rows.size() == 1 ? rows.get(0) : Collections.emptyMap();
    }

    private boolean authoritativeNetworks(Long tenantId, Long accountId, Set<Integer> networkFirmIds) {
        if (accountId == null || networkFirmIds.isEmpty()) {
            return false;
        }
        List<Object> parameters = new ArrayList<>();
        parameters.add(tenantId);
        parameters.add(accountId);
        parameters.addAll(networkFirmIds);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT `tenant_id`,`ad_account_id`,`network_firm_id`,`reward_authority`,"
                        + "`supports_user_id`,`supports_custom_data`,`supports_stable_transaction`,"
                        + "`supports_impression_revenue`,`supports_reporting`,`enabled`,`verified_at` "
                        + "FROM `skit_ad_network_capability` WHERE `tenant_id`=? AND `ad_account_id`=? "
                        + "AND `network_firm_id` IN (" + placeholders(networkFirmIds.size()) + ") "
                        + "AND `deleted`=b'0'",
                parameters.toArray());
        if (rows.size() != networkFirmIds.size()) {
            return false;
        }
        for (Map<String, Object> row : rows) {
            if (!tenantId.equals(longValue(row.get("tenant_id")))
                    || !accountId.equals(longValue(row.get("ad_account_id")))
                    || !networkFirmIds.contains(intValue(row.get("network_firm_id"), -1))
                    || !"SIGNED_REWARD".equals(stringValue(row.get("reward_authority")))
                    || !booleanValue(row.get("supports_user_id"))
                    || !booleanValue(row.get("supports_custom_data"))
                    || !booleanValue(row.get("supports_stable_transaction"))
                    || !booleanValue(row.get("supports_impression_revenue"))
                    || !booleanValue(row.get("supports_reporting"))
                    || !booleanValue(row.get("enabled")) || row.get("verified_at") == null) {
                return false;
            }
        }
        return true;
    }

    private ReportingEvidence reportingEvidence(Long tenantId, Long accountId, Map<String, Object> account) {
        ReportingEvidence result = new ReportingEvidence();
        if (accountId == null || account.isEmpty()) {
            return result;
        }
        List<Map<String, Object>> credentialRows = jdbcTemplate.queryForList(
                "SELECT `credential_version`,`permission_verified_at` "
                        + "FROM `skit_ad_reporting_credential_version` "
                        + "WHERE `tenant_id`=? AND `ad_account_id`=? AND `active`=b'1' "
                        + "AND `revoked_at` IS NULL AND `deleted`=b'0'",
                tenantId, accountId);
        result.credentialConfigured = credentialRows.size() == 1;
        result.permissionVerified = result.credentialConfigured
                && credentialRows.get(0).get("permission_verified_at") != null
                && StrUtil.isNotBlank(stringValue(account.get("report_timezone")))
                && stringValue(account.get("report_currency")).matches("[A-Z]{3}")
                && intValue(account.get("report_amount_scale"), -1) >= 0
                && intValue(account.get("report_amount_scale"), -1) <= 18;
        if (!result.permissionVerified) {
            return result;
        }
        int credentialVersion = intValue(credentialRows.get(0).get("credential_version"), -1);
        if (credentialVersion <= 0) {
            return result;
        }
        List<Map<String, Object>> pulls = jdbcTemplate.queryForList(
                "SELECT `report_date`,`request_hash`,`credential_version`,`pulled_at` "
                        + "FROM `skit_ad_report_pull` WHERE `tenant_id`=? AND `ad_account_id`=? "
                        + "AND `status`='SUCCEEDED' "
                        + "AND `pulled_at`>=TIMESTAMPADD(HOUR,-?,CURRENT_TIMESTAMP) "
                        + "AND `deleted`=b'0' ORDER BY `pulled_at` DESC,`id` DESC LIMIT 16",
                tenantId, accountId, REPORT_MAX_AGE_HOURS);
        result.lastReportSuccessAt = latestMatchingReportSuccess(
                tenantId, accountId, account, credentialVersion, pulls);
        result.reportFresh = result.lastReportSuccessAt != null;
        return result;
    }

    static LocalDateTime latestMatchingReportSuccess(Long tenantId, Long accountId,
                                                      Map<String, Object> account,
                                                      int credentialVersion,
                                                      List<Map<String, Object>> pulls) {
        for (Map<String, Object> pull : pulls) {
            if (intValue(pull.get("credential_version"), -1) != credentialVersion) {
                continue;
            }
            LocalDate reportDate = localDate(pull.get("report_date"));
            byte[] actualHash = bytes(pull.get("request_hash"));
            if (reportDate == null || actualHash == null || actualHash.length != 32) {
                continue;
            }
            try {
                byte[] expectedHash = SkitReportRequestScopeFingerprint.fingerprint(
                        tenantId, accountId, stringValue(account.get("app_id")),
                        stringValue(account.get("placement_id")),
                        stringValue(account.get("ad_format")), reportDate,
                        stringValue(account.get("report_timezone")),
                        stringValue(account.get("report_currency")),
                        intValue(account.get("report_amount_scale"), -1), credentialVersion);
                if (MessageDigest.isEqual(expectedHash, actualHash)) {
                    return localDateTime(pull.get("pulled_at"));
                }
            } catch (IllegalArgumentException invalidCurrentScope) {
                return null;
            }
        }
        return null;
    }

    private CallbackEvidence callbackEvidence(Long tenantId, Long accountId, String placementId,
                                                Set<Integer> networkFirmIds) {
        CallbackEvidence result = new CallbackEvidence();
        if (accountId == null || StrUtil.isBlank(placementId) || networkFirmIds.isEmpty()) {
            return result;
        }
        List<Object> common = new ArrayList<>();
        common.add(tenantId);
        common.add(accountId);
        common.add(placementId);
        common.addAll(networkFirmIds);
        String networkClause = placeholders(networkFirmIds.size());
        List<Object> rewardParameters = new ArrayList<>(common);
        rewardParameters.add(CALLBACK_MAX_AGE_DAYS);
        result.lastSignedRewardAt = jdbcTemplate.queryForObject(
                "SELECT MAX(`received_at`) FROM `skit_ad_callback_inbox` WHERE `tenant_id`=? "
                        + "AND `ad_account_id`=? AND `placement_id`=? AND `network_firm_id` IN ("
                        + networkClause + ") AND `callback_type`='REWARD' AND `ad_session_id` IS NOT NULL "
                        + "AND `authentication_level`='SIGNED_REWARD' AND `signature_status`='VALID' "
                        + "AND `evidence_provenance`='SIGNED_ILRD' AND `signed_field_mask`=63 "
                        + "AND `delivery_integrity_status`='CANONICAL' AND `processing_status`='SUCCEEDED' "
                        + "AND `received_at`>=TIMESTAMPADD(DAY,-?,CURRENT_TIMESTAMP) AND `deleted`=b'0'",
                (rs, rowNum) -> localDateTime(rs.getTimestamp(1)), rewardParameters.toArray());

        List<Object> impressionParameters = new ArrayList<>(common);
        impressionParameters.add(CALLBACK_MAX_AGE_DAYS);
        result.lastImpressionAt = jdbcTemplate.queryForObject(
                "SELECT MAX(`received_at`) FROM `skit_ad_callback_inbox` WHERE `tenant_id`=? "
                        + "AND `ad_account_id`=? AND `placement_id`=? AND `network_firm_id` IN ("
                        + networkClause + ") AND `callback_type`='IMPRESSION' AND `ad_session_id` IS NOT NULL "
                        + "AND `provider_request_id` IS NOT NULL AND `adsource_id` IS NOT NULL "
                        + "AND `authentication_level`='UNSIGNED_PROVIDER_OBSERVATION' "
                        + "AND `delivery_integrity_status`='CANONICAL' AND `processing_status`='SUCCEEDED' "
                        + "AND `received_at`>=TIMESTAMPADD(DAY,-?,CURRENT_TIMESTAMP) AND `deleted`=b'0'",
                (rs, rowNum) -> localDateTime(rs.getTimestamp(1)), impressionParameters.toArray());
        return result;
    }

    private MemberEvidence memberEvidence(Long tenantId, String memberJson) {
        MemberEvidence result = new MemberEvidence();
        Set<Long> members;
        try {
            members = SkitTenantAdCapabilityServiceImpl.parseLongSet(memberJson);
        } catch (RuntimeException ignored) {
            return result;
        }
        if (members.isEmpty()) {
            result.allBelongToTenant = true;
            return result;
        }
        List<Object> parameters = new ArrayList<>();
        parameters.add(tenantId);
        parameters.addAll(members);
        Map<String, Object> counts = jdbcTemplate.queryForMap(
                "SELECT COUNT(*) AS `tenant_count`,SUM(CASE WHEN `status`=0 THEN 1 ELSE 0 END) "
                        + "AS `active_count` FROM `skit_member` WHERE `tenant_id`=? AND `id` IN ("
                        + placeholders(members.size()) + ") AND `deleted`=b'0'",
                parameters.toArray());
        result.allBelongToTenant = intValue(counts.get("tenant_count"), 0) == members.size();
        result.allActiveAndNonEmpty = result.allBelongToTenant
                && intValue(counts.get("active_count"), 0) == members.size();
        return result;
    }

    private ReleaseEvidence releaseEvidence(Long tenantId, String minimumNativeVersion,
                                             Integer minimumProtocolVersion) {
        ReleaseEvidence result = new ReleaseEvidence();
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT `profile_code`,`channel`,`native_version`,`native_package`,"
                        + "`native_protocol_version`,`hot_bundle_sha256`,`hot_release_no`,"
                        + "`hot_manifest_signature`,`runtime_update_public_key`,"
                        + "`runtime_update_key_fingerprint`,`status` "
                        + "FROM `skit_app_release_profile` WHERE `tenant_id`=? AND `deleted`=b'0'",
                tenantId);
        if (rows.size() != 1) {
            return result;
        }
        Map<String, Object> row = rows.get(0);
        result.protocolReady = minimumProtocolVersion != null
                && minimumProtocolVersion == SkitTenantAdCapabilityService.CURRENT_PROTOCOL_VERSION
                && intValue(row.get("native_protocol_version"), -1) >= minimumProtocolVersion;
        result.nativeReleaseReady = "production".equals(stringValue(row.get("channel")))
                && intValue(row.get("status"), -1) == 0
                && StrUtil.isNotBlank(stringValue(row.get("native_package")))
                && longValue(row.get("hot_release_no")) != null
                && longValue(row.get("hot_release_no")) > 0
                && StrUtil.isNotBlank(stringValue(row.get("hot_manifest_signature")))
                && StrUtil.isNotBlank(stringValue(row.get("runtime_update_public_key")))
                && StrUtil.isNotBlank(stringValue(row.get("runtime_update_key_fingerprint")))
                && result.protocolReady
                && SkitTenantAdCapabilityServiceImpl.compareVersions(
                stringValue(row.get("native_version")), minimumNativeVersion) >= 0;
        if (result.nativeReleaseReady) {
            try {
                manifestVerifier.verify(stringValue(row.get("runtime_update_public_key")),
                        stringValue(row.get("profile_code")),
                        stringValue(row.get("native_package")),
                        stringValue(row.get("hot_bundle_sha256")),
                        intValue(row.get("native_protocol_version"), -1),
                        longValue(row.get("hot_release_no")),
                        stringValue(row.get("hot_manifest_signature")));
            } catch (SecurityException invalidManifest) {
                result.nativeReleaseReady = false;
            }
        }
        return result;
    }

    private boolean exists(String sql, Object... parameters) {
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class, parameters);
        return count != null && count > 0;
    }

    private static Set<Integer> safeNetworkIds(String json) {
        try {
            return SkitTenantAdCapabilityServiceImpl.parseIntegerSet(json);
        } catch (RuntimeException ignored) {
            return Collections.emptySet();
        }
    }

    private static String placeholders(int count) {
        return String.join(",", Collections.nCopies(count, "?"));
    }

    private static boolean booleanValue(Object value) {
        return value instanceof Boolean ? (Boolean) value
                : value instanceof Number && ((Number) value).intValue() == 1;
    }

    private static int intValue(Object value, int fallback) {
        return value instanceof Number ? ((Number) value).intValue() : fallback;
    }

    private static Long longValue(Object value) {
        return value instanceof Number ? ((Number) value).longValue() : null;
    }

    private static String stringValue(Object value) {
        return value == null ? "" : value.toString();
    }

    private static LocalDateTime localDateTime(Object value) {
        if (value instanceof LocalDateTime) {
            return (LocalDateTime) value;
        }
        if (value instanceof Timestamp) {
            return ((Timestamp) value).toLocalDateTime();
        }
        return null;
    }

    private static LocalDate localDate(Object value) {
        if (value instanceof LocalDate) {
            return (LocalDate) value;
        }
        if (value instanceof Date) {
            return ((Date) value).toLocalDate();
        }
        return null;
    }

    private static byte[] bytes(Object value) {
        return value instanceof byte[] ? ((byte[]) value).clone() : null;
    }

    private static final class ReportingEvidence {
        private boolean credentialConfigured;
        private boolean permissionVerified;
        private boolean reportFresh;
        private LocalDateTime lastReportSuccessAt;
    }

    private static final class CredentialEvidence {
        private boolean configured;
        private Integer version;
        private LocalDateTime issuedAt;
    }

    private static final class CallbackEvidence {
        private LocalDateTime lastSignedRewardAt;
        private LocalDateTime lastImpressionAt;
    }

    private static final class MemberEvidence {
        private boolean allBelongToTenant;
        private boolean allActiveAndNonEmpty;
    }

    private static final class ReleaseEvidence {
        private boolean nativeReleaseReady;
        private boolean protocolReady;
    }

}
