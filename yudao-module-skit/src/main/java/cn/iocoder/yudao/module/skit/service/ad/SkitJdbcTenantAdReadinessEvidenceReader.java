package cn.iocoder.yudao.module.skit.service.ad;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.module.skit.dal.dataobject.ad.SkitTenantAdCapabilityDO;
import cn.iocoder.yudao.module.skit.service.app.SkitRuntimeUpdateManifestVerifier;
import cn.iocoder.yudao.module.skit.service.reconciliation.SkitReportRequestScopeFingerprint;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;

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
        List<Map<String, Object>> networkCapabilities = networkCapabilityRows(
                tenantId, capability.getAdAccountId());
        List<Map<String, Object>> rewardCallbacks = callbackRows(tenantId,
                capability.getAdAccountId(), capability.getDedicatedUnlockPlacementId(),
                networkFirmIds, true);
        List<Map<String, Object>> impressionCallbacks = callbackRows(tenantId,
                capability.getAdAccountId(), capability.getDedicatedUnlockPlacementId(),
                networkFirmIds, false);
        List<SkitTenantAdReadinessEvidence.NetworkEvidence> networkReadiness =
                evaluateNetworkEvidence(tenantId, capability.getAdAccountId(), networkFirmIds,
                        networkCapabilities, rewardCallbacks, impressionCallbacks);
        result.setAvailableNetworkCapabilities(evaluateAvailableCapabilities(
                tenantId, capability.getAdAccountId(), networkCapabilities));
        result.setNetworkReadiness(networkReadiness);
        result.setUnlockNetworksAuthoritative(allSelectedNetworksPass(
                networkReadiness, SkitTenantAdReadinessEvidence.NetworkEvidence::isAuthoritative));

        ReportingEvidence reporting = reportingEvidence(tenantId, capability.getAdAccountId(), account);
        result.setReportingCredentialConfigured(reporting.credentialConfigured);
        result.setReportingPermissionVerified(reporting.permissionVerified);
        result.setReportFresh(reporting.reportFresh);
        result.setLastReportSuccessAt(reporting.lastReportSuccessAt);

        result.setSignedRewardCallbackObserved(allSelectedNetworksPass(networkReadiness,
                SkitTenantAdReadinessEvidence.NetworkEvidence::isSignedRewardObserved));
        result.setImpressionCallbackObserved(allSelectedNetworksPass(networkReadiness,
                SkitTenantAdReadinessEvidence.NetworkEvidence::isImpressionObserved));
        result.setPairedSourceEvidenceObserved(allSelectedNetworksPass(networkReadiness,
                SkitTenantAdReadinessEvidence.NetworkEvidence::isPairedSourceObserved));
        result.setMissingSignedRewardNetworkFirmIds(missingNetworks(networkReadiness,
                SkitTenantAdReadinessEvidence.NetworkEvidence::isSignedRewardObserved));
        result.setMissingImpressionNetworkFirmIds(missingNetworks(networkReadiness,
                SkitTenantAdReadinessEvidence.NetworkEvidence::isImpressionObserved));
        result.setMissingPairedSourceNetworkFirmIds(missingNetworks(networkReadiness,
                SkitTenantAdReadinessEvidence.NetworkEvidence::isPairedSourceObserved));
        result.setLastSignedRewardCallbackAt(oldestCompleteObservation(
                networkReadiness, true));
        result.setLastImpressionCallbackAt(oldestCompleteObservation(
                networkReadiness, false));

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

    private List<Map<String, Object>> networkCapabilityRows(Long tenantId, Long accountId) {
        if (tenantId == null || accountId == null || accountId <= 0) {
            return Collections.emptyList();
        }
        return jdbcTemplate.queryForList(
                "SELECT `tenant_id`,`ad_account_id`,`network_firm_id`,`reward_authority`,"
                        + "`supports_user_id`,`supports_custom_data`,`supports_stable_transaction`,"
                        + "`supports_impression_revenue`,`supports_reporting`,`enabled`,`verified_at` "
                        + "FROM `skit_ad_network_capability` WHERE `tenant_id`=? AND `ad_account_id`=? "
                        + "AND `deleted`=b'0' ORDER BY `network_firm_id`",
                tenantId, accountId);
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

    private List<Map<String, Object>> callbackRows(Long tenantId, Long accountId, String placementId,
                                                    Set<Integer> networkFirmIds, boolean reward) {
        if (accountId == null || StrUtil.isBlank(placementId) || networkFirmIds.isEmpty()) {
            return Collections.emptyList();
        }
        List<Object> parameters = new ArrayList<>();
        parameters.add(tenantId);
        parameters.add(accountId);
        parameters.add(placementId);
        parameters.addAll(networkFirmIds);
        parameters.add(CALLBACK_MAX_AGE_DAYS);
        String networkClause = placeholders(networkFirmIds.size());
        String authorityClause = reward
                ? "AND `callback_type`='REWARD' AND `authentication_level`='SIGNED_REWARD' "
                + "AND `signature_status`='VALID' AND `evidence_provenance`='SIGNED_ILRD' "
                + "AND `signed_field_mask`=63 AND `adsource_id` IS NOT NULL "
                + "AND `adsource_id`<>'' "
                : "AND `callback_type`='IMPRESSION' AND `provider_request_id` IS NOT NULL "
                + "AND `adsource_id` IS NOT NULL AND `adsource_id`<>'' "
                + "AND `authentication_level`='UNSIGNED_PROVIDER_OBSERVATION' ";
        return jdbcTemplate.queryForList(
                "SELECT `network_firm_id`,`adsource_id`,MAX(`received_at`) AS `observed_at` "
                        + "FROM `skit_ad_callback_inbox` WHERE `tenant_id`=? "
                        + "AND `ad_account_id`=? AND `placement_id`=? AND `network_firm_id` IN ("
                        + networkClause + ") AND `ad_session_id` IS NOT NULL " + authorityClause
                        + "AND `delivery_integrity_status`='CANONICAL' "
                        + "AND `processing_status`='SUCCEEDED' "
                        + "AND `received_at`>=TIMESTAMPADD(DAY,-?,CURRENT_TIMESTAMP) "
                        + "AND `deleted`=b'0' GROUP BY `network_firm_id`,`adsource_id` "
                        + "ORDER BY `network_firm_id`,`adsource_id`",
                parameters.toArray());
    }

    static List<SkitTenantAdReadinessEvidence.NetworkEvidence> evaluateNetworkEvidence(
            Long tenantId, Long accountId, Set<Integer> selectedNetworkIds,
            List<Map<String, Object>> capabilityRows, List<Map<String, Object>> rewardRows,
            List<Map<String, Object>> impressionRows) {
        Map<Integer, Map<String, Object>> capabilities = new HashMap<>();
        for (Map<String, Object> row : safeRows(capabilityRows)) {
            int networkFirmId = intValue(row.get("network_firm_id"), -1);
            if (tenantId != null && tenantId.equals(longValue(row.get("tenant_id")))
                    && accountId != null && accountId.equals(longValue(row.get("ad_account_id")))
                    && networkFirmId > 0) {
                capabilities.put(networkFirmId, row);
            }
        }
        List<SkitTenantAdReadinessEvidence.NetworkEvidence> result = new ArrayList<>();
        for (Integer networkFirmId : new TreeSet<>(selectedNetworkIds == null
                ? Collections.emptySet() : selectedNetworkIds)) {
            if (networkFirmId == null || networkFirmId <= 0) {
                continue;
            }
            Map<String, Object> capability = capabilities.get(networkFirmId);
            SkitTenantAdReadinessEvidence.NetworkEvidence evidence =
                    new SkitTenantAdReadinessEvidence.NetworkEvidence();
            evidence.setNetworkFirmId(networkFirmId);
            evidence.setRewardAuthority(capability == null ? null
                    : stringValue(capability.get("reward_authority")));
            evidence.setEnabled(capability != null && booleanValue(capability.get("enabled")));
            evidence.setVerifiedAt(capability == null ? null
                    : localDateTime(capability.get("verified_at")));
            evidence.setVerified(evidence.getVerifiedAt() != null);
            evidence.setSupportsUserId(capability != null
                    && booleanValue(capability.get("supports_user_id")));
            evidence.setSupportsCustomData(capability != null
                    && booleanValue(capability.get("supports_custom_data")));
            evidence.setSupportsStableTransaction(capability != null
                    && booleanValue(capability.get("supports_stable_transaction")));
            evidence.setSupportsImpressionRevenue(capability != null
                    && booleanValue(capability.get("supports_impression_revenue")));
            evidence.setSupportsReporting(capability != null
                    && booleanValue(capability.get("supports_reporting")));

            List<String> capabilityBlockers = capabilityBlockers(capability, evidence);
            evidence.setCapabilityBlockers(Collections.unmodifiableList(capabilityBlockers));
            evidence.setAuthoritative(capabilityBlockers.isEmpty());
            evidence.setSelectable(evidence.isAuthoritative());

            NetworkObservations rewards = observations(tenantId, accountId, networkFirmId,
                    rewardRows, evidence.getVerifiedAt());
            NetworkObservations impressions = observations(tenantId, accountId, networkFirmId,
                    impressionRows, evidence.getVerifiedAt());
            boolean rewardObserved = evidence.isAuthoritative() && !rewards.sourceRefs.isEmpty();
            boolean impressionObserved = evidence.isAuthoritative()
                    && evidence.isSupportsImpressionRevenue()
                    && !impressions.sourceRefs.isEmpty();
            evidence.setSignedRewardObserved(rewardObserved);
            evidence.setImpressionObserved(impressionObserved);
            evidence.setLastSignedRewardCallbackAt(rewardObserved ? rewards.latest : null);
            evidence.setLastImpressionCallbackAt(impressionObserved ? impressions.latest : null);
            evidence.setSignedRewardSourceRefs(rewardObserved
                    ? immutableStrings(rewards.sourceRefs) : Collections.emptyList());
            evidence.setImpressionSourceRefs(impressionObserved
                    ? immutableStrings(impressions.sourceRefs) : Collections.emptyList());
            TreeSet<String> pairedSourceRefs = new TreeSet<>(
                    evidence.getSignedRewardSourceRefs());
            pairedSourceRefs.retainAll(evidence.getImpressionSourceRefs());
            boolean pairedSourceObserved = evidence.isAuthoritative()
                    && evidence.isSupportsImpressionRevenue() && !pairedSourceRefs.isEmpty();
            evidence.setPairedSourceObserved(pairedSourceObserved);
            evidence.setPairedSourceRefs(pairedSourceObserved
                    ? immutableStrings(pairedSourceRefs) : Collections.emptyList());
            TreeSet<String> sourceRefs = new TreeSet<>();
            sourceRefs.addAll(evidence.getSignedRewardSourceRefs());
            sourceRefs.addAll(evidence.getImpressionSourceRefs());
            evidence.setSourceRefs(immutableStrings(sourceRefs));

            List<String> blockers = new ArrayList<>(capabilityBlockers);
            addBlocker(blockers, evidence.isSupportsImpressionRevenue(),
                    "IMPRESSION_REVENUE_UNSUPPORTED");
            addBlocker(blockers, evidence.isSupportsReporting(), "REPORTING_UNSUPPORTED");
            addBlocker(blockers, rewardObserved, "REAL_SIGNED_REWARD_CALLBACK_MISSING");
            addBlocker(blockers, impressionObserved, "REAL_IMPRESSION_CALLBACK_MISSING");
            addBlocker(blockers, pairedSourceObserved, "PAIRED_SOURCE_EVIDENCE_MISSING");
            evidence.setBlockers(Collections.unmodifiableList(blockers));
            result.add(evidence);
        }
        return Collections.unmodifiableList(result);
    }

    private static List<SkitTenantAdReadinessEvidence.NetworkEvidence> evaluateAvailableCapabilities(
            Long tenantId, Long accountId, List<Map<String, Object>> capabilityRows) {
        Set<Integer> networkIds = new TreeSet<>();
        for (Map<String, Object> row : safeRows(capabilityRows)) {
            int networkFirmId = intValue(row.get("network_firm_id"), -1);
            if (tenantId != null && tenantId.equals(longValue(row.get("tenant_id")))
                    && accountId != null && accountId.equals(longValue(row.get("ad_account_id")))
                    && networkFirmId > 0) {
                networkIds.add(networkFirmId);
            }
        }
        return evaluateNetworkEvidence(tenantId, accountId, networkIds, capabilityRows,
                Collections.emptyList(), Collections.emptyList());
    }

    static boolean allSelectedNetworksPass(
            List<SkitTenantAdReadinessEvidence.NetworkEvidence> evidence,
            Predicate<SkitTenantAdReadinessEvidence.NetworkEvidence> predicate) {
        if (evidence == null || evidence.isEmpty() || predicate == null) {
            return false;
        }
        for (SkitTenantAdReadinessEvidence.NetworkEvidence network : evidence) {
            if (network == null || !predicate.test(network)) {
                return false;
            }
        }
        return true;
    }

    static Set<Integer> missingNetworks(
            List<SkitTenantAdReadinessEvidence.NetworkEvidence> evidence,
            Predicate<SkitTenantAdReadinessEvidence.NetworkEvidence> predicate) {
        if (evidence == null || evidence.isEmpty() || predicate == null) {
            return Collections.emptySet();
        }
        TreeSet<Integer> result = new TreeSet<>();
        for (SkitTenantAdReadinessEvidence.NetworkEvidence network : evidence) {
            if (network != null && network.getNetworkFirmId() != null
                    && network.getNetworkFirmId() > 0 && !predicate.test(network)) {
                result.add(network.getNetworkFirmId());
            }
        }
        return result.isEmpty() ? Collections.emptySet()
                : Collections.unmodifiableSet(new LinkedHashSet<>(result));
    }

    private static LocalDateTime oldestCompleteObservation(
            List<SkitTenantAdReadinessEvidence.NetworkEvidence> evidence, boolean reward) {
        LocalDateTime oldest = null;
        if (evidence == null || evidence.isEmpty()) {
            return null;
        }
        for (SkitTenantAdReadinessEvidence.NetworkEvidence network : evidence) {
            LocalDateTime observed = reward ? network.getLastSignedRewardCallbackAt()
                    : network.getLastImpressionCallbackAt();
            if (observed == null) {
                return null;
            }
            if (oldest == null || observed.isBefore(oldest)) {
                oldest = observed;
            }
        }
        return oldest;
    }

    private static List<String> capabilityBlockers(
            Map<String, Object> capability,
            SkitTenantAdReadinessEvidence.NetworkEvidence evidence) {
        List<String> blockers = new ArrayList<>();
        if (capability == null) {
            blockers.add("NETWORK_CAPABILITY_MISSING");
            return blockers;
        }
        addBlocker(blockers, evidence.isEnabled(), "NETWORK_CAPABILITY_DISABLED");
        addBlocker(blockers, evidence.isVerified(), "NETWORK_CAPABILITY_UNVERIFIED");
        addBlocker(blockers, "SIGNED_REWARD".equals(evidence.getRewardAuthority()),
                "SIGNED_REWARD_AUTHORITY_MISSING");
        addBlocker(blockers, evidence.isSupportsUserId(), "STABLE_USER_ID_UNSUPPORTED");
        addBlocker(blockers, evidence.isSupportsCustomData(), "STABLE_CUSTOM_DATA_UNSUPPORTED");
        addBlocker(blockers, evidence.isSupportsStableTransaction(),
                "STABLE_TRANSACTION_UNSUPPORTED");
        return blockers;
    }

    private static void addBlocker(List<String> blockers, boolean passes, String blocker) {
        if (!passes) {
            blockers.add(blocker);
        }
    }

    private static NetworkObservations observations(Long tenantId, Long accountId,
                                                    Integer expectedNetworkFirmId,
                                                    List<Map<String, Object>> rows,
                                                    LocalDateTime verifiedAt) {
        NetworkObservations result = new NetworkObservations();
        if (tenantId == null || accountId == null || expectedNetworkFirmId == null
                || expectedNetworkFirmId <= 0 || verifiedAt == null) {
            return result;
        }
        for (Map<String, Object> row : safeRows(rows)) {
            int networkFirmId = intValue(row.get("network_firm_id"), -1);
            LocalDateTime observedAt = localDateTime(row.get("observed_at"));
            String adsourceId = stringValue(row.get("adsource_id"));
            if (networkFirmId != expectedNetworkFirmId || StrUtil.isBlank(adsourceId)
                    || observedAt == null || observedAt.isBefore(verifiedAt)) {
                continue;
            }
            result.sourceRefs.add(sourceRef(tenantId, accountId, networkFirmId, adsourceId));
            if (result.latest == null || observedAt.isAfter(result.latest)) {
                result.latest = observedAt;
            }
        }
        return result;
    }

    static String sourceRef(Long tenantId, Long accountId, Integer networkFirmId,
                            String adsourceId) {
        if (tenantId == null || tenantId <= 0 || accountId == null || accountId <= 0
                || networkFirmId == null || networkFirmId <= 0 || StrUtil.isBlank(adsourceId)) {
            throw new IllegalArgumentException("Source reference scope is invalid");
        }
        String canonical = "skit-readiness-source-v1:" + tenantId + ':' + accountId + ':'
                + networkFirmId + ':' + adsourceId.trim();
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(12);
            for (int index = 0; index < 6; index++) {
                hex.append(Character.forDigit((digest[index] >>> 4) & 0x0f, 16));
                hex.append(Character.forDigit(digest[index] & 0x0f, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static List<String> immutableStrings(Set<String> values) {
        return values == null || values.isEmpty() ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(new TreeSet<>(values)));
    }

    private static List<Map<String, Object>> safeRows(List<Map<String, Object>> rows) {
        return rows == null ? Collections.emptyList() : rows;
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

    private static final class NetworkObservations {
        private LocalDateTime latest;
        private final Set<String> sourceRefs = new TreeSet<>();
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
