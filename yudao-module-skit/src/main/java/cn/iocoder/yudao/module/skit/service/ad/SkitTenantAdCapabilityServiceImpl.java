package cn.iocoder.yudao.module.skit.service.ad;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.enums.CommonStatusEnum;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.skit.dal.dataobject.ad.SkitTenantAdCapabilityDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.app.SkitAppReleaseProfileDO;
import cn.iocoder.yudao.module.skit.dal.mysql.ad.SkitAdAccountMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.ad.SkitTenantAdCapabilityMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.ad.SkitAdSessionMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.app.SkitAppReleaseProfileMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.member.SkitNativePlayerGrantMapper;
import cn.iocoder.yudao.module.skit.service.app.SkitRuntimeUpdateManifestVerifier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.skit.enums.ErrorCodeConstants.AD_ROLLOUT_CONFIG_INVALID;
import static cn.iocoder.yudao.module.skit.enums.ErrorCodeConstants.AD_ROLLOUT_NOT_READY;
import static cn.iocoder.yudao.module.skit.enums.ErrorCodeConstants.AD_ROLLOUT_STATE_INVALID;
import static cn.iocoder.yudao.module.skit.enums.ErrorCodeConstants.AD_ROLLOUT_VERSION_CONFLICT;

@Service
public class SkitTenantAdCapabilityServiceImpl implements SkitTenantAdCapabilityService {

    static final String OFF = "OFF";
    static final String SHADOW = "SHADOW_TEST_USERS";
    static final String ENFORCED = "ENFORCED";
    private static final Pattern STABLE_VERSION = Pattern.compile("[0-9]{1,9}(\\.[0-9]{1,9}){1,3}");
    private static final Pattern RUNTIME_VERSION = Pattern.compile(
            "[0-9]{1,9}(\\.[0-9]{1,9}){1,3}([-.][A-Za-z0-9._-]{1,32})?");
    private static final Pattern PLACEMENT = Pattern.compile("[A-Za-z0-9._:-]{1,128}");
    private static final Set<Integer> PHASE_ONE_AUTHORITATIVE_NETWORKS =
            Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(35, 66, 67)));

    private final SkitTenantAdCapabilityMapper capabilityMapper;
    private final SkitAdAccountMapper adAccountMapper;
    private final SkitTenantAdReadinessEvidenceReader evidenceReader;
    private final SkitAppReleaseProfileMapper releaseProfileMapper;
    private final SkitNativePlayerGrantMapper nativePlayerGrantMapper;
    private final SkitAdSessionMapper adSessionMapper;
    private final SkitRuntimeUpdateManifestVerifier manifestVerifier;

    public SkitTenantAdCapabilityServiceImpl(SkitTenantAdCapabilityMapper capabilityMapper,
                                             SkitAdAccountMapper adAccountMapper,
                                             SkitTenantAdReadinessEvidenceReader evidenceReader,
                                             SkitAppReleaseProfileMapper releaseProfileMapper,
                                             SkitNativePlayerGrantMapper nativePlayerGrantMapper,
                                             SkitAdSessionMapper adSessionMapper,
                                             SkitRuntimeUpdateManifestVerifier manifestVerifier) {
        this.capabilityMapper = Objects.requireNonNull(capabilityMapper, "capabilityMapper");
        this.adAccountMapper = Objects.requireNonNull(adAccountMapper, "adAccountMapper");
        this.evidenceReader = Objects.requireNonNull(evidenceReader, "evidenceReader");
        this.releaseProfileMapper = Objects.requireNonNull(releaseProfileMapper, "releaseProfileMapper");
        this.nativePlayerGrantMapper = Objects.requireNonNull(nativePlayerGrantMapper,
                "nativePlayerGrantMapper");
        this.adSessionMapper = Objects.requireNonNull(adSessionMapper, "adSessionMapper");
        this.manifestVerifier = Objects.requireNonNull(manifestVerifier, "manifestVerifier");
    }

    @Override
    @Transactional(readOnly = true)
    public ReadinessView getReadiness() {
        Long tenantId = TenantContextHolder.getRequiredTenantId();
        SkitTenantAdCapabilityDO capability = capabilityMapper.selectByTenantForShare(tenantId);
        if (capability == null) {
            capability = defaultCapability(tenantId);
        }
        requireEnvelope(capability, tenantId, capability.getId() != null);
        return readiness(capability, evidenceReader.read(tenantId, capability));
    }

    @Override
    @Transactional(isolation = Isolation.REPEATABLE_READ, rollbackFor = Exception.class)
    public CapabilityView configure(ConfigurationCommand command) {
        Long tenantId = TenantContextHolder.getRequiredTenantId();
        String dedicatedPlacementId = resolveDedicatedPlacementId(tenantId, command);
        validateConfiguration(command, dedicatedPlacementId);
        SkitTenantAdCapabilityDO locked = lockOrCreate(tenantId);
        requireEnvelope(locked, tenantId);
        requireExpectedVersion(locked, command.getExpectedReadinessVersion());
        if (ENFORCED.equals(locked.getRolloutState())) {
            throw exception(AD_ROLLOUT_STATE_INVALID);
        }

        SkitTenantAdCapabilityDO prospective = copy(locked)
                .setAdAccountId(command.getAdAccountId())
                .setDedicatedUnlockPlacementId(dedicatedPlacementId)
                .setDedicatedPlacementVerifiedAt(marker(command.getDedicatedPlacementVerified()))
                .setRewardCallbackTemplateVerifiedAt(marker(command.getRewardCallbackTemplateVerified()))
                .setImpressionCallbackTemplateVerifiedAt(marker(command.getImpressionCallbackTemplateVerified()))
                .setUnlockNetworkFirmIdsJson(integerJson(PHASE_ONE_AUTHORITATIVE_NETWORKS))
                .setShadowTestMemberIdsJson(longJson(command.getShadowTestMemberIds()))
                .setMinNativeVersion(command.getMinNativeVersion().trim())
                .setMinProtocolVersion(CURRENT_PROTOCOL_VERSION);
        SkitTenantAdReadinessEvidence evidence = evidenceReader.read(tenantId, prospective);
        if (evidence == null || !evidence.isAccountBelongsToTenant()) {
            throw exception(AD_ROLLOUT_NOT_READY, "CROSS_TENANT_CONFIGURATION");
        }
        if (!evidence.isShadowMembersBelongToTenant()) {
            throw exception(AD_ROLLOUT_NOT_READY, "SHADOW_MEMBER_TENANT_MISMATCH");
        }

        int updated = capabilityMapper.updateConfigurationCas(tenantId, locked.getId(),
                command.getExpectedReadinessVersion(), command.getAdAccountId(),
                prospective.getDedicatedUnlockPlacementId(), command.getDedicatedPlacementVerified(),
                command.getRewardCallbackTemplateVerified(), command.getImpressionCallbackTemplateVerified(),
                prospective.getUnlockNetworkFirmIdsJson(), prospective.getShadowTestMemberIdsJson(),
                prospective.getMinNativeVersion(), prospective.getMinProtocolVersion());
        if (updated != 1) {
            throw exception(AD_ROLLOUT_VERSION_CONFLICT);
        }
        return requireCurrentView(tenantId);
    }

    @Override
    @Transactional(isolation = Isolation.REPEATABLE_READ, rollbackFor = Exception.class)
    public CapabilityView transition(TransitionCommand command) {
        validateTransition(command);
        Long tenantId = TenantContextHolder.getRequiredTenantId();
        SkitTenantAdCapabilityDO locked = capabilityMapper.selectByTenantForUpdate(tenantId);
        if (locked == null) {
            throw exception(AD_ROLLOUT_STATE_INVALID);
        }
        requireEnvelope(locked, tenantId);
        requireExpectedVersion(locked, command.getExpectedReadinessVersion());
        String target = normalizeState(command.getTargetState());
        if (!isLegalTransition(locked.getRolloutState(), target)) {
            throw exception(AD_ROLLOUT_STATE_INVALID);
        }

        locked.setMinNativeVersion(command.getMinNativeVersion().trim());
        locked.setMinProtocolVersion(command.getMinProtocolVersion());
        if (!OFF.equals(target)) {
            ReadinessView readiness = readiness(locked, evidenceReader.read(tenantId, locked));
            boolean ready = SHADOW.equals(target) ? readiness.isShadowReady() : readiness.isProductionReady();
            if (!ready) {
                throw exception(AD_ROLLOUT_NOT_READY, String.join(",", readiness.getBlockers()));
            }
        }

        if (ENFORCED.equals(target)) {
            SkitAppReleaseProfileDO release = releaseProfileMapper.selectByTenantIdForUpdate(tenantId);
            requireReleaseEnvelope(release, tenantId, command.getMinNativeVersion(),
                    command.getMinProtocolVersion());
            if (releaseProfileMapper.updateMinNativeVersionForRollout(
                    tenantId, release.getId(), command.getMinNativeVersion().trim()) != 1) {
                throw exception(AD_ROLLOUT_VERSION_CONFLICT);
            }
            // These revocations and the capability CAS share this transaction. A failed CAS rolls
            // them all back, so no tenant can observe half of an ENFORCED cut-over.
            nativePlayerGrantMapper.revokeActiveForTenantRollout(tenantId);
            adSessionMapper.rejectPendingForTenantRollout(tenantId);
        }

        if (capabilityMapper.transitionCas(tenantId, locked.getId(), command.getExpectedReadinessVersion(),
                target, command.getMinNativeVersion().trim(), command.getMinProtocolVersion()) != 1) {
            throw exception(AD_ROLLOUT_VERSION_CONFLICT);
        }
        return requireCurrentView(tenantId);
    }

    @Override
    @Transactional(isolation = Isolation.REPEATABLE_READ, rollbackFor = Exception.class)
    public void lockCredentialMutationTarget(Long adAccountId, Integer expectedReadinessVersion) {
        Long tenantId = TenantContextHolder.getRequiredTenantId();
        SkitTenantAdCapabilityDO locked = capabilityMapper.selectByTenantForUpdate(tenantId);
        if (locked == null) {
            throw exception(AD_ROLLOUT_STATE_INVALID);
        }
        requireEnvelope(locked, tenantId);
        requireExpectedVersion(locked, expectedReadinessVersion);
        if (adAccountId == null || !adAccountId.equals(locked.getAdAccountId())) {
            throw exception(AD_ROLLOUT_CONFIG_INVALID, "AD_ACCOUNT_TARGET_MISMATCH");
        }
    }

    @Override
    @Transactional(readOnly = true)
    public void checkClientAccess(Long memberId, ClientRuntime runtime, AccessOperation operation) {
        if (memberId == null || memberId <= 0 || runtime == null || operation == null) {
            throw exception(AD_ROLLOUT_NOT_READY, "INVALID_CLIENT_CONTEXT");
        }
        Long tenantId = TenantContextHolder.getRequiredTenantId();
        SkitTenantAdCapabilityDO capability = capabilityMapper.selectByTenantForShare(tenantId);
        if (capability == null || OFF.equals(capability.getRolloutState())) {
            throw exception(AD_ROLLOUT_NOT_READY, "ROLLOUT_OFF");
        }
        requireEnvelope(capability, tenantId);
        if (!clientRuntimeAccepted(capability, runtime)) {
            throw exception(AD_ROLLOUT_NOT_READY, "CLIENT_VERSION_REVOKED");
        }
        if (SHADOW.equals(capability.getRolloutState())) {
            if (!parseLongSet(capability.getShadowTestMemberIdsJson()).contains(memberId)) {
                throw exception(AD_ROLLOUT_NOT_READY, "MEMBER_NOT_IN_SHADOW");
            }
            return;
        }
        if (!ENFORCED.equals(capability.getRolloutState())) {
            throw exception(AD_ROLLOUT_NOT_READY, "UNKNOWN_ROLLOUT_STATE");
        }
    }

    private SkitTenantAdCapabilityDO lockOrCreate(Long tenantId) {
        SkitTenantAdCapabilityDO locked = capabilityMapper.selectByTenantForUpdate(tenantId);
        if (locked != null) {
            return locked;
        }
        SkitTenantAdCapabilityDO created = defaultCapability(tenantId);
        try {
            capabilityMapper.insertDefault(created);
        } catch (DuplicateKeyException ignored) {
            // A concurrent first configuration won the tenant singleton. Lock and continue from its version.
        }
        locked = capabilityMapper.selectByTenantForUpdate(tenantId);
        if (locked == null) {
            throw new IllegalStateException("Tenant ad capability singleton was not created");
        }
        return locked;
    }

    private CapabilityView requireCurrentView(Long tenantId) {
        SkitTenantAdCapabilityDO current = capabilityMapper.selectByTenantForShare(tenantId);
        if (current == null) {
            throw new IllegalStateException("Tenant ad capability disappeared after a successful CAS");
        }
        requireEnvelope(current, tenantId);
        return toView(current);
    }

    private ReadinessView readiness(SkitTenantAdCapabilityDO capability,
                                    SkitTenantAdReadinessEvidence evidence) {
        if (evidence == null) {
            evidence = new SkitTenantAdReadinessEvidence();
        }
        List<String> shadowBlockers = new ArrayList<>();
        require(shadowBlockers, evidence.isTenantActive(), "TENANT_ARCHIVED_OR_DISABLED");
        require(shadowBlockers, evidence.isAccountBelongsToTenant(), "AD_ACCOUNT_TENANT_MISMATCH");
        require(shadowBlockers, evidence.isAccountReady(), "AD_ACCOUNT_INCOMPLETE");
        require(shadowBlockers, evidence.isCallbackKeyConfigured(), "CALLBACK_KEY_MISSING");
        require(shadowBlockers, evidence.isRewardSecretConfigured(), "REWARD_SECRET_MISSING");
        require(shadowBlockers, evidence.isDedicatedUnlockPlacement(), "UNLOCK_PLACEMENT_NOT_DEDICATED");
        require(shadowBlockers, evidence.isRewardCallbackTemplateVerified(),
                "REWARD_CALLBACK_TEMPLATE_UNVERIFIED");
        require(shadowBlockers, evidence.isImpressionCallbackTemplateVerified(),
                "IMPRESSION_CALLBACK_TEMPLATE_UNVERIFIED");
        require(shadowBlockers, evidence.isUnlockNetworksAuthoritative(),
                "UNLOCK_NETWORK_WITHOUT_AUTHORITATIVE_S2S");
        require(shadowBlockers, evidence.isReportingCredentialConfigured(),
                "REPORTING_CREDENTIAL_MISSING");
        require(shadowBlockers, evidence.isReportingPermissionVerified(),
                "REPORTING_PERMISSION_UNVERIFIED");
        require(shadowBlockers, evidence.isNativeReleaseReady(), "NATIVE_RELEASE_NOT_READY");
        require(shadowBlockers, evidence.isProtocolReady(), "NATIVE_PROTOCOL_NOT_READY");
        require(shadowBlockers, evidence.isShadowMembersBelongToTenant(), "SHADOW_MEMBER_TENANT_MISMATCH");
        require(shadowBlockers, evidence.isShadowMembersValid(), "SHADOW_TEST_MEMBERS_MISSING");

        List<String> productionBlockers = new ArrayList<>(shadowBlockers);
        require(productionBlockers, evidence.isReportFresh(), "OFFICIAL_REPORT_STALE");
        require(productionBlockers, evidence.isSignedRewardCallbackObserved(),
                "REAL_SIGNED_REWARD_CALLBACK_MISSING");
        require(productionBlockers, evidence.isImpressionCallbackObserved(),
                "REAL_IMPRESSION_CALLBACK_MISSING");
        require(productionBlockers, evidence.isCallbackPublicUrlHttps(),
                "CALLBACK_PUBLIC_URL_HTTPS_REQUIRED");

        ReadinessView view = new ReadinessView();
        view.setTenantId(capability.getTenantId());
        view.setAdAccountId(capability.getAdAccountId());
        view.setRolloutState(capability.getRolloutState());
        view.setReadinessVersion(capability.getReadinessVersion());
        view.setExpectedReadinessVersion(capability.getReadinessVersion());
        view.setDedicatedUnlockPlacementId(capability.getDedicatedUnlockPlacementId());
        view.setDedicatedPlacementVerified(capability.getDedicatedPlacementVerifiedAt() != null);
        view.setUnlockNetworkFirmIds(parseIntegerSet(capability.getUnlockNetworkFirmIdsJson()));
        view.setShadowTestMemberIds(parseLongSet(capability.getShadowTestMemberIdsJson()));
        view.setMinNativeVersion(capability.getMinNativeVersion());
        view.setMinProtocolVersion(capability.getMinProtocolVersion());
        view.setEnforcedAt(capability.getEnforcedAt());
        view.setTenantActive(evidence.isTenantActive());
        view.setAccountReady(evidence.isAccountReady());
        view.setCallbackKeyConfigured(evidence.isCallbackKeyConfigured());
        view.setCallbackKeyVersion(evidence.getCallbackKeyVersion());
        view.setCallbackKeyIssuedAt(evidence.getCallbackKeyIssuedAt());
        view.setRewardSecretConfigured(evidence.isRewardSecretConfigured());
        view.setRewardSecretVersion(evidence.getRewardSecretVersion());
        view.setRewardSecretIssuedAt(evidence.getRewardSecretIssuedAt());
        view.setCallbackPublicUrlHttps(evidence.isCallbackPublicUrlHttps());
        view.setDedicatedUnlockPlacement(evidence.isDedicatedUnlockPlacement());
        view.setRewardCallbackTemplateVerified(evidence.isRewardCallbackTemplateVerified());
        view.setImpressionCallbackTemplateVerified(evidence.isImpressionCallbackTemplateVerified());
        view.setUnlockNetworksAuthoritative(evidence.isUnlockNetworksAuthoritative());
        view.setReportingCredentialConfigured(evidence.isReportingCredentialConfigured());
        view.setReportingPermissionVerified(evidence.isReportingPermissionVerified());
        view.setReportFresh(evidence.isReportFresh());
        view.setSignedRewardCallbackObserved(evidence.isSignedRewardCallbackObserved());
        view.setImpressionCallbackObserved(evidence.isImpressionCallbackObserved());
        view.setNativeReleaseReady(evidence.isNativeReleaseReady());
        view.setProtocolReady(evidence.isProtocolReady());
        view.setShadowMembersValid(evidence.isShadowMembersValid());
        view.setShadowReady(shadowBlockers.isEmpty());
        view.setProductionReady(productionBlockers.isEmpty());
        view.setBlockers(Collections.unmodifiableList(productionBlockers));
        view.setLastSignedRewardCallbackAt(evidence.getLastSignedRewardCallbackAt());
        view.setLastImpressionCallbackAt(evidence.getLastImpressionCallbackAt());
        view.setLastReportSuccessAt(evidence.getLastReportSuccessAt());
        return view;
    }

    private void validateConfiguration(ConfigurationCommand command, String dedicatedPlacementId) {
        if (command == null || command.getAdAccountId() == null || command.getAdAccountId() <= 0
                || StrUtil.isBlank(dedicatedPlacementId)
                || !PLACEMENT.matcher(dedicatedPlacementId).matches()
                || command.getExpectedReadinessVersion() == null || command.getExpectedReadinessVersion() < 0
                || !stableVersion(command.getMinNativeVersion())
                || command.getDedicatedPlacementVerified() == null
                || command.getRewardCallbackTemplateVerified() == null
                || command.getImpressionCallbackTemplateVerified() == null) {
            throw exception(AD_ROLLOUT_CONFIG_INVALID, "REQUIRED_FIELD_INVALID");
        }
        positiveLongSet(command.getShadowTestMemberIds(), 100, "SHADOW_MEMBERS");
    }

    private String resolveDedicatedPlacementId(Long tenantId, ConfigurationCommand command) {
        if (command == null || command.getAdAccountId() == null || command.getAdAccountId() <= 0) {
            return "";
        }
        String placementId = adAccountMapper.selectEnabledTakuPlacementId(tenantId, command.getAdAccountId());
        return StrUtil.isBlank(placementId) ? "" : placementId.trim();
    }

    private void validateTransition(TransitionCommand command) {
        if (command == null || StrUtil.isBlank(command.getTargetState())
                || command.getExpectedReadinessVersion() == null || command.getExpectedReadinessVersion() < 0
                || command.getMinProtocolVersion() == null
                || command.getMinProtocolVersion() != CURRENT_PROTOCOL_VERSION
                || !stableVersion(command.getMinNativeVersion())) {
            throw exception(AD_ROLLOUT_CONFIG_INVALID, "TRANSITION_FIELD_INVALID");
        }
        normalizeState(command.getTargetState());
    }

    private void requireExpectedVersion(SkitTenantAdCapabilityDO capability, Integer expected) {
        if (!Objects.equals(capability.getReadinessVersion(), expected)) {
            throw exception(AD_ROLLOUT_VERSION_CONFLICT);
        }
    }

    private void requireEnvelope(SkitTenantAdCapabilityDO capability, Long tenantId) {
        requireEnvelope(capability, tenantId, true);
    }

    private void requireEnvelope(SkitTenantAdCapabilityDO capability, Long tenantId,
                                 boolean requirePersistentId) {
        if ((requirePersistentId && capability.getId() == null)
                || !tenantId.equals(capability.getTenantId())
                || capability.getReadinessVersion() == null || capability.getReadinessVersion() < 0
                || !Arrays.asList(OFF, SHADOW, ENFORCED).contains(capability.getRolloutState())) {
            throw new IllegalStateException("Tenant ad capability escaped its tenant/version envelope");
        }
    }

    private void requireReleaseEnvelope(SkitAppReleaseProfileDO release, Long tenantId,
                                        String minimumVersion, Integer minimumProtocol) {
        if (release == null || release.getId() == null || !tenantId.equals(release.getTenantId())
                || !"production".equals(release.getChannel())
                || !CommonStatusEnum.ENABLE.getStatus().equals(release.getStatus())
                || release.getNativeProtocolVersion() == null
                || release.getNativeProtocolVersion() < minimumProtocol
                || release.getHotReleaseNo() == null || release.getHotReleaseNo() <= 0
                || StrUtil.isBlank(release.getHotManifestSignature())
                || StrUtil.isBlank(release.getRuntimeUpdatePublicKey())
                || StrUtil.isBlank(release.getRuntimeUpdateKeyFingerprint())
                || StrUtil.isBlank(release.getProfileCode())
                || StrUtil.isBlank(release.getNativePackage())
                || StrUtil.isBlank(release.getHotBundleSha256())
                || compareVersions(release.getNativeVersion(), minimumVersion) < 0) {
            throw exception(AD_ROLLOUT_NOT_READY, "NATIVE_RELEASE_NOT_READY");
        }
        try {
            manifestVerifier.verify(release.getRuntimeUpdatePublicKey(),
                    release.getProfileCode(), release.getNativePackage(),
                    release.getHotBundleSha256(), release.getNativeProtocolVersion(),
                    release.getHotReleaseNo(), release.getHotManifestSignature());
        } catch (SecurityException invalidManifest) {
            throw exception(AD_ROLLOUT_NOT_READY, "NATIVE_RELEASE_NOT_READY");
        }
    }

    private boolean clientRuntimeAccepted(SkitTenantAdCapabilityDO capability, ClientRuntime runtime) {
        return runtime.getProtocolVersion() != null
                && runtime.getProtocolVersion() == CURRENT_PROTOCOL_VERSION
                && runtime.getProtocolVersion() >= capability.getMinProtocolVersion()
                && compareVersions(runtime.getNativeVersion(), capability.getMinNativeVersion()) >= 0;
    }

    static int compareVersions(String left, String right) {
        if (StrUtil.isBlank(left) || StrUtil.isBlank(right)
                || !RUNTIME_VERSION.matcher(left.trim()).matches()
                || !RUNTIME_VERSION.matcher(right.trim()).matches()) {
            return -1;
        }
        String[] leftVersion = left.trim().split("-", 2);
        String[] rightVersion = right.trim().split("-", 2);
        String[] leftParts = leftVersion[0].split("\\.");
        String[] rightParts = rightVersion[0].split("\\.");
        int length = Math.max(leftParts.length, rightParts.length);
        for (int index = 0; index < length; index++) {
            long leftPart = index < leftParts.length ? parsePart(leftParts[index]) : 0L;
            long rightPart = index < rightParts.length ? parsePart(rightParts[index]) : 0L;
            if (leftPart != rightPart) {
                return Long.compare(leftPart, rightPart);
            }
        }
        boolean leftPrerelease = leftVersion.length > 1;
        boolean rightPrerelease = rightVersion.length > 1;
        if (leftPrerelease != rightPrerelease) {
            return leftPrerelease ? -1 : 1;
        }
        return leftPrerelease ? leftVersion[1].compareTo(rightVersion[1]) : 0;
    }

    private static long parsePart(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return -1L;
        }
    }

    private boolean stableVersion(String value) {
        return StrUtil.isNotBlank(value) && STABLE_VERSION.matcher(value.trim()).matches();
    }

    private boolean isLegalTransition(String current, String target) {
        return (OFF.equals(current) && SHADOW.equals(target))
                || (SHADOW.equals(current) && ENFORCED.equals(target))
                || ((SHADOW.equals(current) || ENFORCED.equals(current)) && OFF.equals(target));
    }

    private String normalizeState(String state) {
        String normalized = state == null ? "" : state.trim().toUpperCase(Locale.ROOT);
        if (!Arrays.asList(OFF, SHADOW, ENFORCED).contains(normalized)) {
            throw exception(AD_ROLLOUT_STATE_INVALID);
        }
        return normalized;
    }

    private static void require(List<String> blockers, boolean ready, String blocker) {
        if (!ready) {
            blockers.add(blocker);
        }
    }

    private static LocalDateTime marker(Boolean value) {
        return Boolean.TRUE.equals(value) ? LocalDateTime.of(1970, 1, 1, 0, 0) : null;
    }

    private SkitTenantAdCapabilityDO defaultCapability(Long tenantId) {
        SkitTenantAdCapabilityDO result = new SkitTenantAdCapabilityDO();
        result.setTenantId(tenantId);
        return result.setRolloutState(OFF)
                .setDedicatedUnlockPlacementId("")
                .setUnlockNetworkFirmIdsJson(integerJson(PHASE_ONE_AUTHORITATIVE_NETWORKS))
                .setShadowTestMemberIdsJson("[]").setMinNativeVersion("")
                .setMinProtocolVersion(CURRENT_PROTOCOL_VERSION).setReadinessVersion(0);
    }

    private SkitTenantAdCapabilityDO copy(SkitTenantAdCapabilityDO source) {
        SkitTenantAdCapabilityDO result = new SkitTenantAdCapabilityDO().setId(source.getId());
        result.setTenantId(source.getTenantId());
        return result.setAdAccountId(source.getAdAccountId()).setRolloutState(source.getRolloutState())
                .setDedicatedUnlockPlacementId(source.getDedicatedUnlockPlacementId())
                .setDedicatedPlacementVerifiedAt(source.getDedicatedPlacementVerifiedAt())
                .setRewardCallbackTemplateVerifiedAt(source.getRewardCallbackTemplateVerifiedAt())
                .setImpressionCallbackTemplateVerifiedAt(source.getImpressionCallbackTemplateVerifiedAt())
                .setUnlockNetworkFirmIdsJson(source.getUnlockNetworkFirmIdsJson())
                .setShadowTestMemberIdsJson(source.getShadowTestMemberIdsJson())
                .setMinNativeVersion(source.getMinNativeVersion())
                .setMinProtocolVersion(source.getMinProtocolVersion())
                .setReadinessVersion(source.getReadinessVersion()).setEnforcedAt(source.getEnforcedAt());
    }

    private CapabilityView toView(SkitTenantAdCapabilityDO source) {
        CapabilityView view = new CapabilityView();
        view.setTenantId(source.getTenantId());
        view.setAdAccountId(source.getAdAccountId());
        view.setRolloutState(source.getRolloutState());
        view.setDedicatedUnlockPlacementId(source.getDedicatedUnlockPlacementId());
        view.setUnlockNetworkFirmIds(parseIntegerSet(source.getUnlockNetworkFirmIdsJson()));
        view.setShadowTestMemberIds(parseLongSet(source.getShadowTestMemberIdsJson()));
        view.setMinNativeVersion(source.getMinNativeVersion());
        view.setMinProtocolVersion(source.getMinProtocolVersion());
        view.setReadinessVersion(source.getReadinessVersion());
        view.setEnforcedAt(source.getEnforcedAt());
        return view;
    }

    private static Set<Integer> positiveIntegerSet(Set<Integer> values, int maximum, String label) {
        TreeSet<Integer> result = new TreeSet<>();
        if (values != null) {
            for (Integer value : values) {
                if (value == null || value <= 0) {
                    throw exception(AD_ROLLOUT_CONFIG_INVALID, label);
                }
                result.add(value);
            }
        }
        if (result.size() > maximum) {
            throw exception(AD_ROLLOUT_CONFIG_INVALID, label);
        }
        return result;
    }

    private static Set<Long> positiveLongSet(Set<Long> values, int maximum, String label) {
        TreeSet<Long> result = new TreeSet<>();
        if (values != null) {
            for (Long value : values) {
                if (value == null || value <= 0) {
                    throw exception(AD_ROLLOUT_CONFIG_INVALID, label);
                }
                result.add(value);
            }
        }
        if (result.size() > maximum) {
            throw exception(AD_ROLLOUT_CONFIG_INVALID, label);
        }
        return result;
    }

    private static String integerJson(Set<Integer> values) {
        return numericJson(positiveIntegerSet(values, 16, "UNLOCK_NETWORKS"));
    }

    private static String longJson(Set<Long> values) {
        return numericJson(positiveLongSet(values, 100, "SHADOW_MEMBERS"));
    }

    private static String numericJson(Set<? extends Number> values) {
        StringBuilder result = new StringBuilder("[");
        boolean first = true;
        for (Number value : values) {
            if (!first) {
                result.append(',');
            }
            result.append(value);
            first = false;
        }
        return result.append(']').toString();
    }

    static Set<Long> parseLongSet(String json) {
        LinkedHashSet<Long> result = new LinkedHashSet<>();
        for (String value : numericTokens(json)) {
            try {
                long parsed = Long.parseLong(value);
                if (parsed <= 0 || !result.add(parsed)) {
                    throw new IllegalArgumentException("invalid positive unique member id");
                }
            } catch (NumberFormatException ex) {
                throw new IllegalStateException("Invalid stored shadow member JSON", ex);
            }
        }
        return Collections.unmodifiableSet(result);
    }

    static Set<Integer> parseIntegerSet(String json) {
        LinkedHashSet<Integer> result = new LinkedHashSet<>();
        for (String value : numericTokens(json)) {
            try {
                int parsed = Integer.parseInt(value);
                if (parsed <= 0 || !result.add(parsed)) {
                    throw new IllegalArgumentException("invalid positive unique network id");
                }
            } catch (NumberFormatException ex) {
                throw new IllegalStateException("Invalid stored unlock network JSON", ex);
            }
        }
        return Collections.unmodifiableSet(result);
    }

    private static List<String> numericTokens(String json) {
        if (json == null || !json.matches("\\[[0-9,]*]")) {
            throw new IllegalStateException("Invalid stored numeric JSON array");
        }
        String body = json.substring(1, json.length() - 1);
        return body.isEmpty() ? Collections.emptyList() : Arrays.asList(body.split(",", -1));
    }

}
