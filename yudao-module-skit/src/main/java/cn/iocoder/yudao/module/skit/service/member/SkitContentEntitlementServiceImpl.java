package cn.iocoder.yudao.module.skit.service.member;

import cn.iocoder.yudao.framework.common.enums.CommonStatusEnum;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.framework.tenant.core.util.TenantUtils;
import cn.iocoder.yudao.module.skit.dal.dataobject.agent.SkitAgentDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.member.SkitContentEntitlementDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.member.SkitEntitlementGrantDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.member.SkitMemberDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.member.SkitNativePlayerGrantDO;
import cn.iocoder.yudao.module.skit.dal.mysql.agent.SkitAgentMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.member.SkitContentEntitlementMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.member.SkitEntitlementGrantMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.member.SkitMemberMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.member.SkitNativePlayerGrantMapper;
import cn.iocoder.yudao.module.skit.service.ad.SkitTenantAdCapabilityService;
import cn.iocoder.yudao.module.system.dal.dataobject.tenant.TenantDO;
import cn.iocoder.yudao.module.system.service.tenant.TenantService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.skit.enums.ErrorCodeConstants.AD_PLAYER_GRANT_INVALID;
import static cn.iocoder.yudao.module.skit.enums.ErrorCodeConstants.MEMBER_DISABLED;
import static cn.iocoder.yudao.module.skit.enums.ErrorCodeConstants.MEMBER_NOT_EXISTS;

@Service
public class SkitContentEntitlementServiceImpl implements SkitContentEntitlementService {

    private static final int PLAYER_GRANT_BYTES = 32;
    private static final int PLAYER_GRANT_RETRIES = 8;
    // A player grant is a short-lived bearer for the native ad flow, not content ownership.
    // Each legitimate use renews this idle window so continuous playback does not fail while
    // an abandoned player grant still expires promptly.
    private static final int PLAYER_GRANT_IDLE_MINUTES = 30;
    private static final int CONTENT_ENTITLEMENT_MINUTES = 5;

    private final SkitNativePlayerGrantMapper nativeGrantMapper;
    private final SkitContentEntitlementMapper entitlementMapper;
    private final SkitEntitlementGrantMapper entitlementGrantMapper;
    private final SkitContentScopeService contentScopeService;
    private final SkitMemberMapper memberMapper;
    private final SkitAgentMapper agentMapper;
    private final TenantService tenantService;
    private final SkitTenantAdCapabilityService capabilityService;
    private final Clock clock;
    private final SecureRandom secureRandom;

    @Autowired
    public SkitContentEntitlementServiceImpl(SkitNativePlayerGrantMapper nativeGrantMapper,
                                             SkitContentEntitlementMapper entitlementMapper,
                                             SkitEntitlementGrantMapper entitlementGrantMapper,
                                             SkitContentScopeService contentScopeService,
                                             SkitMemberMapper memberMapper,
                                             SkitAgentMapper agentMapper,
                                             TenantService tenantService,
                                             SkitTenantAdCapabilityService capabilityService) {
        this(nativeGrantMapper, entitlementMapper, entitlementGrantMapper, contentScopeService,
                memberMapper, agentMapper, tenantService, Clock.systemDefaultZone(),
                new SecureRandom(), capabilityService);
    }

    SkitContentEntitlementServiceImpl(SkitNativePlayerGrantMapper nativeGrantMapper,
                                      SkitContentEntitlementMapper entitlementMapper,
                                      SkitEntitlementGrantMapper entitlementGrantMapper,
                                      SkitContentScopeService contentScopeService,
                                      SkitMemberMapper memberMapper,
                                      SkitAgentMapper agentMapper,
                                      TenantService tenantService,
                                      Clock clock,
                                      SecureRandom secureRandom,
                                      SkitTenantAdCapabilityService capabilityService) {
        this.nativeGrantMapper = Objects.requireNonNull(nativeGrantMapper, "nativeGrantMapper");
        this.entitlementMapper = Objects.requireNonNull(entitlementMapper, "entitlementMapper");
        this.entitlementGrantMapper = Objects.requireNonNull(
                entitlementGrantMapper, "entitlementGrantMapper");
        this.contentScopeService = Objects.requireNonNull(contentScopeService, "contentScopeService");
        this.memberMapper = Objects.requireNonNull(memberMapper, "memberMapper");
        this.agentMapper = Objects.requireNonNull(agentMapper, "agentMapper");
        this.tenantService = Objects.requireNonNull(tenantService, "tenantService");
        this.capabilityService = Objects.requireNonNull(capabilityService, "capabilityService");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.secureRandom = Objects.requireNonNull(secureRandom, "secureRandom");
    }

    private PlayerGrantIssue issuePlayerGrantInsideTenant(Long memberId, Long dramaId) {
        requirePositive(memberId, "memberId");
        requirePositive(dramaId, "dramaId");
        Long tenantId = TenantContextHolder.getRequiredTenantId();
        requireEnabledTenant(tenantId, tenantService.getTenantForShare(tenantId));
        tenantService.validTenant(tenantId);
        requireEnabledAgent(tenantId, agentMapper.selectByTenantId(tenantId));
        requireEnabledMember(tenantId, memberId,
                memberMapper.selectByTenantAndIdForShare(tenantId, memberId));
        SkitContentScopeService.AccessibleDrama drama =
                contentScopeService.requireAccessibleDrama(dramaId);
        if (drama == null || !tenantId.equals(drama.getTenantId())
                || !dramaId.equals(drama.getDramaId()) || drama.getCatalogRecordId() == null
                || drama.getCatalogRecordId() <= 0) {
            throw exception(AD_PLAYER_GRANT_INVALID);
        }
        LocalDateTime expiresAt = now().plusMinutes(PLAYER_GRANT_IDLE_MINUTES);
        for (int attempt = 0; attempt < PLAYER_GRANT_RETRIES; attempt++) {
            byte[] random = new byte[PLAYER_GRANT_BYTES];
            secureRandom.nextBytes(random);
            String token = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
            Arrays.fill(random, (byte) 0);
            SkitNativePlayerGrantDO row = new SkitNativePlayerGrantDO()
                    .setMemberId(memberId).setDramaId(dramaId).setGrantTokenHash(sha256(token))
                    .setStatus("ACTIVE").setExpiresAt(expiresAt).setVersion(0);
            row.setTenantId(tenantId);
            try {
                if (nativeGrantMapper.insert(row) != 1 || row.getId() == null || row.getId() <= 0) {
                    throw new IllegalStateException("Native player grant was not inserted exactly once");
                }
                return new PlayerGrantIssue(row.getId(), dramaId, expiresAt, token);
            } catch (DuplicateKeyException collision) {
                if (attempt + 1 == PLAYER_GRANT_RETRIES) {
                    throw new IllegalStateException("Could not allocate a unique native player grant", collision);
                }
            }
        }
        throw new IllegalStateException("Could not allocate a native player grant");
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public PlayerGrantIssue issuePlayerGrant(
            Long memberId, Long dramaId, SkitTenantAdCapabilityService.ClientRuntime runtime) {
        requireClientAccess(memberId, runtime, SkitTenantAdCapabilityService.AccessOperation.PLAYER_GRANT);
        return issuePlayerGrantInsideTenant(memberId, dramaId);
    }

    @Override
    public PlayerGrantReference resolvePlayerGrant(String grantToken) {
        byte[] decoded = decodePlayerGrant(grantToken);
        Arrays.fill(decoded, (byte) 0);
        byte[] tokenHash = sha256(grantToken);
        SkitNativePlayerGrantDO row = TenantUtils.executeIgnore(
                () -> nativeGrantMapper.selectByTokenHash(tokenHash));
        if (row == null || row.getTenantId() == null || row.getTenantId() <= 0
                || row.getId() == null || row.getId() <= 0 || row.getMemberId() == null
                || row.getMemberId() <= 0 || row.getDramaId() == null || row.getDramaId() <= 0) {
            throw exception(AD_PLAYER_GRANT_INVALID);
        }
        return new PlayerGrantReference(row.getTenantId(), row.getId(), row.getMemberId(),
                row.getDramaId(), tokenHash);
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public PlayerGrantScope lockAndUsePlayerGrant(PlayerGrantReference reference, Long expectedDramaId) {
        Objects.requireNonNull(reference, "reference");
        requirePositive(expectedDramaId, "dramaId");
        Long tenantId = TenantContextHolder.getRequiredTenantId();
        if (!tenantId.equals(reference.getTenantId()) || !expectedDramaId.equals(reference.getDramaId())) {
            throw exception(AD_PLAYER_GRANT_INVALID);
        }
        requireEnabledTenant(tenantId, tenantService.getTenantForShare(tenantId));
        tenantService.validTenant(tenantId);
        requireEnabledAgent(tenantId, agentMapper.selectByTenantId(tenantId));
        requireEnabledMember(tenantId, reference.getMemberId(),
                memberMapper.selectByTenantAndIdForShare(tenantId, reference.getMemberId()));
        SkitNativePlayerGrantDO row = nativeGrantMapper.selectExactForUpdate(tenantId,
                reference.getGrantId(), reference.getMemberId(), reference.getDramaId());
        if (!sameGrant(reference, row)) {
            throw exception(AD_PLAYER_GRANT_INVALID);
        }
        LocalDateTime now = now();
        if (!"ACTIVE".equals(row.getStatus()) || row.getRevokedAt() != null
                || row.getExpiresAt() == null || !row.getExpiresAt().isAfter(now)
                || row.getVersion() == null || row.getVersion() < 0) {
            throw exception(AD_PLAYER_GRANT_INVALID);
        }
        LocalDateTime renewedExpiresAt = now.plusMinutes(PLAYER_GRANT_IDLE_MINUTES);
        if (nativeGrantMapper.recordActiveUseCas(tenantId, row.getId(), row.getMemberId(),
                row.getDramaId(), row.getVersion(), now, renewedExpiresAt) != 1) {
            throw exception(AD_PLAYER_GRANT_INVALID);
        }
        return new PlayerGrantScope(tenantId, row.getId(), row.getMemberId(), row.getDramaId());
    }

    private List<Integer> listGrantedEpisodesInsideTenant(Long memberId, Long dramaId) {
        requirePositive(memberId, "memberId");
        requirePositive(dramaId, "dramaId");
        Long tenantId = TenantContextHolder.getRequiredTenantId();
        List<SkitContentEntitlementDO> rows = entitlementMapper.selectGrantedEpisodes(
                tenantId, memberId, dramaId,
                now().minusMinutes(CONTENT_ENTITLEMENT_MINUTES));
        if (rows == null || rows.isEmpty()) {
            return Collections.emptyList();
        }
        List<Integer> episodes = new ArrayList<>(rows.size());
        for (SkitContentEntitlementDO row : rows) {
            validateEntitlementScope(row, tenantId, memberId, dramaId);
            if (isActiveGrantedEntitlement(row, now())) {
                episodes.add(row.getEpisodeNo());
            }
        }
        return episodes;
    }

    @Override
    @Transactional(readOnly = true)
    public List<Integer> listGrantedEpisodes(
            Long memberId, Long dramaId, SkitTenantAdCapabilityService.ClientRuntime runtime) {
        requireClientAccess(memberId, runtime,
                SkitTenantAdCapabilityService.AccessOperation.PROTECTED_CONTENT);
        return listGrantedEpisodesInsideTenant(memberId, dramaId);
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public List<Integer> listGrantedEpisodesForPlayerGrant(
            String grantToken, SkitTenantAdCapabilityService.ClientRuntime runtime) {
        PlayerGrantReference reference = resolvePlayerGrant(grantToken);
        AtomicReference<List<Integer>> result = new AtomicReference<>();
        TenantUtils.execute(reference.getTenantId(), () -> {
            requireClientAccess(reference.getMemberId(), runtime,
                    SkitTenantAdCapabilityService.AccessOperation.PLAYER_GRANT);
            PlayerGrantScope scope = lockAndUsePlayerGrant(reference, reference.getDramaId());
            result.set(listGrantedEpisodesInsideTenant(scope.getMemberId(), scope.getDramaId()));
        });
        return result.get();
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public VerifiedRewardProvenance findVerifiedRewardProvenanceForPlayerGrant(
            String grantToken, Integer episodeNo,
            SkitTenantAdCapabilityService.ClientRuntime runtime) {
        requirePositive(episodeNo, "episodeNo");
        PlayerGrantReference reference = resolvePlayerGrant(grantToken);
        AtomicReference<VerifiedRewardProvenance> result = new AtomicReference<>();
        TenantUtils.execute(reference.getTenantId(), () -> {
            requireClientAccess(reference.getMemberId(), runtime,
                    SkitTenantAdCapabilityService.AccessOperation.PLAYER_GRANT);
            PlayerGrantScope scope = lockAndUsePlayerGrant(reference, reference.getDramaId());
            List<SkitEntitlementGrantMapper.VerifiedRewardProvenanceRow> rows =
                    entitlementGrantMapper.selectVerifiedRewardProvenance(
                            scope.getTenantId(), scope.getMemberId(), scope.getDramaId(), episodeNo,
                            now().minusMinutes(CONTENT_ENTITLEMENT_MINUTES));
            if (rows == null || rows.size() != 1) {
                return;
            }
            result.set(validateVerifiedRewardProvenance(
                    rows.get(0), scope, episodeNo));
        });
        return result.get();
    }

    @Override
    public boolean ownsEpisodeForUpdate(Long memberId, Long dramaId, Integer episodeNo) {
        requirePositive(memberId, "memberId");
        requirePositive(dramaId, "dramaId");
        requirePositive(episodeNo, "episodeNo");
        Long tenantId = TenantContextHolder.getRequiredTenantId();
        List<SkitContentEntitlementDO> rows = entitlementMapper.selectEpisodesForUpdate(
                tenantId, memberId, dramaId, Collections.singletonList(episodeNo));
        if (rows == null || rows.isEmpty()) {
            return false;
        }
        if (rows.size() != 1) {
            throw new IllegalStateException("Entitlement uniqueness was violated");
        }
        SkitContentEntitlementDO row = rows.get(0);
        validateEntitlementEnvelope(row, tenantId, memberId, dramaId, episodeNo);
        return isActiveGrantedEntitlement(row, now());
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public void activateVerifiedRewardLeaseOnClose(Long memberId, Long adSessionId, Long dramaId,
                                                   Integer episodeNo, LocalDateTime closedAt) {
        requirePositive(memberId, "memberId");
        requirePositive(adSessionId, "adSessionId");
        requirePositive(dramaId, "dramaId");
        requirePositive(episodeNo, "episodeNo");
        Objects.requireNonNull(closedAt, "closedAt");
        Long tenantId = TenantContextHolder.getRequiredTenantId();
        List<SkitContentEntitlementDO> rows = entitlementMapper.selectEpisodesForUpdate(
                tenantId, memberId, dramaId, Collections.singletonList(episodeNo));
        if (rows == null || rows.size() != 1) {
            throw new IllegalStateException("Verified reward has no exact entitlement projection");
        }
        SkitContentEntitlementDO entitlement = rows.get(0);
        validateEntitlementEnvelope(entitlement, tenantId, memberId, dramaId, episodeNo);
        SkitEntitlementGrantDO grant = entitlementGrantMapper
                .selectBySessionAndEpisodeForUpdate(tenantId, adSessionId, episodeNo);
        if (grant == null || !tenantId.equals(grant.getTenantId())
                || !adSessionId.equals(grant.getAdSessionId())
                || !entitlement.getId().equals(grant.getEntitlementId())
                || !memberId.equals(grant.getMemberId()) || !dramaId.equals(grant.getDramaId())
                || !episodeNo.equals(grant.getEpisodeNo()) || grant.getGrantedAt() == null
                || entitlement.getGrantedAt() == null || entitlement.getLeaseActivatedAt() == null
                || entitlement.getVersion() == null || closedAt.isBefore(grant.getGrantedAt())) {
            throw new IllegalStateException("Rewarded close is not bound to the current signed grant");
        }
        if (!"CREATED".equals(grant.getGrantResult())) {
            return;
        }
        if (!grant.getGrantedAt().equals(entitlement.getGrantedAt())) {
            if (entitlement.getGrantedAt().isAfter(grant.getGrantedAt())) {
                // A newer verified reward won the projection. The old CLOSED evidence remains
                // canonical, but it must not extend or roll back the new session's lease.
                return;
            }
            throw new IllegalStateException("Rewarded close proof is newer than the entitlement projection");
        }
        if (!entitlement.getLeaseActivatedAt().isBefore(closedAt)) {
            return;
        }
        int expectedVersion = entitlement.getVersion();
        if (entitlementMapper.activateVerifiedRewardLeaseCas(
                tenantId, entitlement.getId(), memberId, dramaId, episodeNo, expectedVersion,
                grant.getGrantedAt(), closedAt) != 1) {
            throw new IllegalStateException("Verified reward lease changed before close settlement");
        }
        entitlement.setLeaseActivatedAt(closedAt).setVersion(expectedVersion + 1);
    }

    private boolean sameGrant(PlayerGrantReference reference, SkitNativePlayerGrantDO row) {
        return row != null && reference.getTenantId().equals(row.getTenantId())
                && reference.getGrantId().equals(row.getId())
                && reference.getMemberId().equals(row.getMemberId())
                && reference.getDramaId().equals(row.getDramaId())
                && row.getGrantTokenHash() != null
                && MessageDigest.isEqual(reference.getGrantTokenHash(), row.getGrantTokenHash());
    }

    private VerifiedRewardProvenance validateVerifiedRewardProvenance(
            SkitEntitlementGrantMapper.VerifiedRewardProvenanceRow row,
            PlayerGrantScope scope, Integer episodeNo) {
        if (row == null || !scope.getTenantId().equals(row.getTenantId())
                || !scope.getMemberId().equals(row.getMemberId())
                || !scope.getDramaId().equals(row.getDramaId())
                || !episodeNo.equals(row.getEpisodeNo())
                || !"TAKU".equals(row.getProvider())
                || row.getSessionId() == null
                || !row.getSessionId().matches("[A-Za-z0-9_-]{22}")
                || row.getProviderShowId() == null
                || !row.getProviderShowId().matches("[A-Za-z0-9._:/-]{1,128}")) {
            return null;
        }
        return new VerifiedRewardProvenance(
                episodeNo, row.getSessionId(), row.getProvider(), row.getProviderShowId());
    }

    private void requireEnabledAgent(Long tenantId, SkitAgentDO agent) {
        if (agent == null || !tenantId.equals(agent.getTenantId()) || agent.getArchivedTime() != null
                || !CommonStatusEnum.ENABLE.getStatus().equals(agent.getStatus())) {
            throw exception(AD_PLAYER_GRANT_INVALID);
        }
    }

    private void requireEnabledTenant(Long tenantId, TenantDO tenant) {
        if (tenant == null || !tenantId.equals(tenant.getId())
                || !CommonStatusEnum.ENABLE.getStatus().equals(tenant.getStatus())) {
            throw exception(AD_PLAYER_GRANT_INVALID);
        }
    }

    private void requireEnabledMember(Long tenantId, Long memberId, SkitMemberDO member) {
        if (member == null || !tenantId.equals(member.getTenantId()) || !memberId.equals(member.getId())) {
            throw exception(MEMBER_NOT_EXISTS);
        }
        if (!CommonStatusEnum.ENABLE.getStatus().equals(member.getStatus())) {
            throw exception(MEMBER_DISABLED);
        }
    }

    private void validateEntitlementScope(SkitContentEntitlementDO row, Long tenantId,
                                          Long memberId, Long dramaId) {
        if (row == null || row.getEpisodeNo() == null || row.getEpisodeNo() <= 0
                || !"GRANTED".equals(row.getStatus())) {
            throw new IllegalStateException("Granted entitlement row is malformed");
        }
        validateEntitlementEnvelope(row, tenantId, memberId, dramaId, row.getEpisodeNo());
    }

    private boolean isActiveGrantedEntitlement(SkitContentEntitlementDO row, LocalDateTime now) {
        return "GRANTED".equals(row.getStatus()) && row.getGrantedAt() != null
                && row.getLeaseActivatedAt() != null
                && row.getLeaseActivatedAt().isAfter(
                now.minusMinutes(CONTENT_ENTITLEMENT_MINUTES));
    }

    private void validateEntitlementEnvelope(SkitContentEntitlementDO row, Long tenantId,
                                             Long memberId, Long dramaId, Integer episodeNo) {
        if (row == null || !tenantId.equals(row.getTenantId()) || !memberId.equals(row.getMemberId())
                || !dramaId.equals(row.getDramaId()) || !episodeNo.equals(row.getEpisodeNo())) {
            throw new IllegalStateException("Entitlement escaped the tenant/member/content boundary");
        }
    }

    private byte[] decodePlayerGrant(String token) {
        if (token == null || token.length() != 43 || !token.matches("[A-Za-z0-9_-]{43}")) {
            throw exception(AD_PLAYER_GRANT_INVALID);
        }
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(token);
            if (decoded.length != PLAYER_GRANT_BYTES) {
                throw exception(AD_PLAYER_GRANT_INVALID);
            }
            return decoded;
        } catch (IllegalArgumentException ex) {
            throw exception(AD_PLAYER_GRANT_INVALID);
        }
    }

    private byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.US_ASCII));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock).withNano(0);
    }

    private void requireClientAccess(Long memberId, SkitTenantAdCapabilityService.ClientRuntime runtime,
                                     SkitTenantAdCapabilityService.AccessOperation operation) {
        capabilityService.checkClientAccess(memberId, runtime, operation);
    }

    private void requirePositive(Number value, String field) {
        if (value == null || value.longValue() <= 0) {
            throw new IllegalArgumentException(field + " must be positive");
        }
    }

}
