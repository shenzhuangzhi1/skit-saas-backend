package cn.iocoder.yudao.module.skit.service.member;

import cn.iocoder.yudao.framework.common.enums.CommonStatusEnum;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.framework.tenant.core.util.TenantUtils;
import cn.iocoder.yudao.module.skit.dal.dataobject.agent.SkitAgentDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.member.SkitContentEntitlementDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.member.SkitMemberDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.member.SkitNativePlayerGrantDO;
import cn.iocoder.yudao.module.skit.dal.mysql.agent.SkitAgentMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.member.SkitContentEntitlementMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.member.SkitMemberMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.member.SkitNativePlayerGrantMapper;
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
    private static final int PLAYER_GRANT_MINUTES = 5;

    private final SkitNativePlayerGrantMapper nativeGrantMapper;
    private final SkitContentEntitlementMapper entitlementMapper;
    private final SkitMemberMapper memberMapper;
    private final SkitAgentMapper agentMapper;
    private final TenantService tenantService;
    private final Clock clock;
    private final SecureRandom secureRandom;

    @Autowired
    public SkitContentEntitlementServiceImpl(SkitNativePlayerGrantMapper nativeGrantMapper,
                                             SkitContentEntitlementMapper entitlementMapper,
                                             SkitMemberMapper memberMapper,
                                             SkitAgentMapper agentMapper,
                                             TenantService tenantService) {
        this(nativeGrantMapper, entitlementMapper, memberMapper, agentMapper,
                tenantService, Clock.systemDefaultZone(), new SecureRandom());
    }

    SkitContentEntitlementServiceImpl(SkitNativePlayerGrantMapper nativeGrantMapper,
                                      SkitContentEntitlementMapper entitlementMapper,
                                      SkitMemberMapper memberMapper,
                                      SkitAgentMapper agentMapper,
                                      TenantService tenantService,
                                      Clock clock,
                                      SecureRandom secureRandom) {
        this.nativeGrantMapper = Objects.requireNonNull(nativeGrantMapper, "nativeGrantMapper");
        this.entitlementMapper = Objects.requireNonNull(entitlementMapper, "entitlementMapper");
        this.memberMapper = Objects.requireNonNull(memberMapper, "memberMapper");
        this.agentMapper = Objects.requireNonNull(agentMapper, "agentMapper");
        this.tenantService = Objects.requireNonNull(tenantService, "tenantService");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.secureRandom = Objects.requireNonNull(secureRandom, "secureRandom");
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public PlayerGrantIssue issuePlayerGrant(Long memberId, Long dramaId) {
        requirePositive(memberId, "memberId");
        requirePositive(dramaId, "dramaId");
        Long tenantId = TenantContextHolder.getRequiredTenantId();
        requireEnabledTenant(tenantId, tenantService.getTenantForShare(tenantId));
        tenantService.validTenant(tenantId);
        requireEnabledAgent(tenantId, agentMapper.selectByTenantId(tenantId));
        requireEnabledMember(tenantId, memberId,
                memberMapper.selectByTenantAndIdForShare(tenantId, memberId));
        LocalDateTime expiresAt = now().plusMinutes(PLAYER_GRANT_MINUTES);
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
        SkitNativePlayerGrantDO row = nativeGrantMapper.selectExactForShare(tenantId,
                reference.getGrantId(), reference.getMemberId(), reference.getDramaId());
        if (!sameGrant(reference, row)) {
            throw exception(AD_PLAYER_GRANT_INVALID);
        }
        LocalDateTime now = now();
        if (!"ACTIVE".equals(row.getStatus()) || row.getRevokedAt() != null
                || row.getExpiresAt() == null || !row.getExpiresAt().isAfter(now)) {
            throw exception(AD_PLAYER_GRANT_INVALID);
        }
        return new PlayerGrantScope(tenantId, row.getId(), row.getMemberId(), row.getDramaId());
    }

    @Override
    @Transactional(readOnly = true)
    public List<Integer> listGrantedEpisodes(Long memberId, Long dramaId) {
        requirePositive(memberId, "memberId");
        requirePositive(dramaId, "dramaId");
        Long tenantId = TenantContextHolder.getRequiredTenantId();
        List<SkitContentEntitlementDO> rows = entitlementMapper.selectGrantedEpisodes(
                tenantId, memberId, dramaId);
        if (rows == null || rows.isEmpty()) {
            return Collections.emptyList();
        }
        List<Integer> episodes = new ArrayList<>(rows.size());
        for (SkitContentEntitlementDO row : rows) {
            validateEntitlementScope(row, tenantId, memberId, dramaId);
            episodes.add(row.getEpisodeNo());
        }
        return episodes;
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public List<Integer> listGrantedEpisodesForPlayerGrant(String grantToken) {
        PlayerGrantReference reference = resolvePlayerGrant(grantToken);
        AtomicReference<List<Integer>> result = new AtomicReference<>();
        TenantUtils.execute(reference.getTenantId(), () -> {
            PlayerGrantScope scope = lockAndUsePlayerGrant(reference, reference.getDramaId());
            result.set(listGrantedEpisodes(scope.getMemberId(), scope.getDramaId()));
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
        return "GRANTED".equals(row.getStatus());
    }

    private boolean sameGrant(PlayerGrantReference reference, SkitNativePlayerGrantDO row) {
        return row != null && reference.getTenantId().equals(row.getTenantId())
                && reference.getGrantId().equals(row.getId())
                && reference.getMemberId().equals(row.getMemberId())
                && reference.getDramaId().equals(row.getDramaId())
                && row.getGrantTokenHash() != null
                && MessageDigest.isEqual(reference.getGrantTokenHash(), row.getGrantTokenHash());
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

    private void requirePositive(Number value, String field) {
        if (value == null || value.longValue() <= 0) {
            throw new IllegalArgumentException(field + " must be positive");
        }
    }

}
