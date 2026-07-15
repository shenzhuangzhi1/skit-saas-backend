package cn.iocoder.yudao.module.skit.service.member;

import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.skit.dal.dataobject.member.SkitContentEntitlementDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.record.SkitAdminRecordDO;
import cn.iocoder.yudao.module.skit.dal.mysql.member.SkitContentEntitlementMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.record.SkitAdminRecordMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.skit.enums.ErrorCodeConstants.AD_SESSION_INVALID;

/**
 * Uses the existing tenant-scoped {@code skit_admin_record(page_key = 'drama')} directory as the
 * content authority. Client SDK identifiers are only selectors; all episode boundaries are read
 * and locked by the server.
 */
@Service
public class SkitContentScopeServiceImpl implements SkitContentScopeService {

    private static final String DRAMA_PAGE_KEY = "drama";
    private static final int DEFAULT_FREE_EPISODES = 8;
    private static final int DEFAULT_UNLOCK_SIZE = 5;
    private static final int MAX_UNLOCK_SIZE = 100;
    private static final int MAX_TOTAL_EPISODES = 100_000;

    private final SkitAdminRecordMapper recordMapper;
    private final SkitContentEntitlementMapper entitlementMapper;
    private final ObjectMapper objectMapper;

    public SkitContentScopeServiceImpl(SkitAdminRecordMapper recordMapper,
                                       SkitContentEntitlementMapper entitlementMapper,
                                       ObjectMapper objectMapper) {
        this.recordMapper = Objects.requireNonNull(recordMapper, "recordMapper");
        this.entitlementMapper = Objects.requireNonNull(entitlementMapper, "entitlementMapper");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    @Override
    public AccessibleDrama requireAccessibleDrama(Long dramaId) {
        requirePositive(dramaId);
        Long tenantId = TenantContextHolder.getRequiredTenantId();
        List<SkitAdminRecordDO> matches = recordMapper.selectDramaCatalogByBusinessIdForShare(
                tenantId, Long.toString(dramaId));
        if (matches == null || matches.size() != 1) {
            throw exception(AD_SESSION_INVALID);
        }
        SkitAdminRecordDO row = matches.get(0);
        JsonNode data = readCatalog(row, tenantId);
        long authoritativeDramaId = firstPositiveLong(data,
                "pangleDramaId", "dramaId", "drama_id", "contentId", "nativeId", "id");
        int totalEpisodes = firstPositiveInt(data, "episodes", "totalEpisodes",
                "episodeCount", "total", "count");
        int freeEpisodes = firstNonNegativeInt(data, DEFAULT_FREE_EPISODES,
                "freeEpisodes", "freeSet", "free_set");
        int unlockSize = firstPositiveInt(data, DEFAULT_UNLOCK_SIZE,
                "unlockSize", "lockSet", "lock_set");
        if (authoritativeDramaId != dramaId || totalEpisodes > MAX_TOTAL_EPISODES
                || freeEpisodes > totalEpisodes || unlockSize > MAX_UNLOCK_SIZE
                || !isPublished(data.path("status"))) {
            throw exception(AD_SESSION_INVALID);
        }
        return new AccessibleDrama(tenantId, row.getId(), dramaId, totalEpisodes,
                freeEpisodes, unlockSize);
    }

    @Override
    public UnlockScope resolveUnlockScopeForUpdate(Long memberId, Long dramaId,
                                                   Integer requestedEpisodeNo) {
        requirePositive(memberId);
        requirePositive(requestedEpisodeNo);
        AccessibleDrama drama = requireAccessibleDrama(dramaId);
        if (requestedEpisodeNo <= drama.getFreeEpisodes()
                || requestedEpisodeNo > drama.getTotalEpisodes()) {
            throw exception(AD_SESSION_INVALID);
        }
        int lastCandidate = Math.min(drama.getTotalEpisodes(),
                Math.addExact(requestedEpisodeNo, drama.getUnlockSize() - 1));
        List<Integer> candidates = new ArrayList<>(lastCandidate - requestedEpisodeNo + 1);
        for (int episode = requestedEpisodeNo; episode <= lastCandidate; episode++) {
            candidates.add(episode);
        }
        List<SkitContentEntitlementDO> rows = entitlementMapper.selectEpisodesForUpdate(
                drama.getTenantId(), memberId, dramaId, candidates);
        Map<Integer, SkitContentEntitlementDO> existing = validateEntitlements(
                rows, drama.getTenantId(), memberId, dramaId, candidates);
        SkitContentEntitlementDO requested = existing.get(requestedEpisodeNo);
        if (requested != null) {
            if (!"GRANTED".equals(requested.getStatus())) {
                throw exception(AD_SESSION_INVALID);
            }
            return scope(drama, requestedEpisodeNo, requestedEpisodeNo, true);
        }
        requireContinuousUnlockFrontier(drama, memberId, requestedEpisodeNo);
        int episodeTo = lastCandidate;
        for (int episode = requestedEpisodeNo + 1; episode <= lastCandidate; episode++) {
            if (existing.containsKey(episode)) {
                episodeTo = episode - 1;
                break;
            }
        }
        return scope(drama, requestedEpisodeNo, episodeTo, false);
    }

    private void requireContinuousUnlockFrontier(AccessibleDrama drama, Long memberId,
                                                 int requestedEpisodeNo) {
        int firstPaidEpisode = drama.getFreeEpisodes() + 1;
        int priorPaidEpisodes = requestedEpisodeNo - firstPaidEpisode;
        if (priorPaidEpisodes == 0) {
            return;
        }
        Long granted = entitlementMapper.countGrantedEpisodesInRange(
                drama.getTenantId(), memberId, drama.getDramaId(),
                firstPaidEpisode, requestedEpisodeNo - 1);
        if (granted == null || granted.longValue() != priorPaidEpisodes) {
            throw exception(AD_SESSION_INVALID);
        }
    }

    private JsonNode readCatalog(SkitAdminRecordDO row, Long tenantId) {
        if (row == null || row.getId() == null || row.getId() <= 0
                || !tenantId.equals(row.getTenantId()) || !DRAMA_PAGE_KEY.equals(row.getPageKey())
                || Boolean.TRUE.equals(row.getDeleted()) || !Integer.valueOf(0).equals(row.getStatus())
                || row.getRecordData() == null || row.getRecordData().length() > 64_000) {
            throw exception(AD_SESSION_INVALID);
        }
        try {
            JsonNode data = objectMapper.readTree(row.getRecordData());
            if (data == null || !data.isObject()) {
                throw exception(AD_SESSION_INVALID);
            }
            return data;
        } catch (RuntimeException runtime) {
            throw runtime;
        } catch (Exception invalidJson) {
            throw exception(AD_SESSION_INVALID);
        }
    }

    private Map<Integer, SkitContentEntitlementDO> validateEntitlements(
            List<SkitContentEntitlementDO> rows, Long tenantId, Long memberId, Long dramaId,
            List<Integer> candidates) {
        Map<Integer, SkitContentEntitlementDO> result = new HashMap<>();
        if (rows == null) {
            return result;
        }
        for (SkitContentEntitlementDO row : rows) {
            if (row == null || row.getId() == null || row.getId() <= 0
                    || !tenantId.equals(row.getTenantId()) || !memberId.equals(row.getMemberId())
                    || !dramaId.equals(row.getDramaId()) || row.getEpisodeNo() == null
                    || !candidates.contains(row.getEpisodeNo()) || Boolean.TRUE.equals(row.getDeleted())
                    || !("GRANTED".equals(row.getStatus())
                    || "SECURITY_REVOKED".equals(row.getStatus()))
                    || result.put(row.getEpisodeNo(), row) != null) {
                throw new IllegalStateException(
                        "Content entitlement escaped the tenant/member/catalog boundary");
            }
        }
        return result;
    }

    private UnlockScope scope(AccessibleDrama drama, int episodeFrom, int episodeTo,
                              boolean alreadyEntitled) {
        String canonical = episodeFrom == episodeTo
                ? "drama:" + drama.getDramaId() + ":episode:" + episodeFrom
                : "drama:" + drama.getDramaId() + ":episodes:" + episodeFrom + '-' + episodeTo;
        return new UnlockScope(drama.getTenantId(), drama.getCatalogRecordId(), drama.getDramaId(),
                episodeFrom, episodeTo, canonical, alreadyEntitled);
    }

    private boolean isPublished(JsonNode status) {
        if (status == null || status.isMissingNode() || status.isNull()) {
            return false;
        }
        if (status.isIntegralNumber()) {
            return status.asInt(Integer.MIN_VALUE) == 0;
        }
        String normalized = status.asText("").trim().toLowerCase(Locale.ROOT);
        return "0".equals(normalized) || "正常".equals(normalized) || "上架".equals(normalized)
                || "已上架".equals(normalized) || "连载中".equals(normalized)
                || "已完结".equals(normalized) || "online".equals(normalized)
                || "enabled".equals(normalized) || "enable".equals(normalized)
                || "published".equals(normalized);
    }

    private long firstPositiveLong(JsonNode data, String... names) {
        for (String name : names) {
            JsonNode value = data.path(name);
            if (value.canConvertToLong() && value.asLong() > 0) {
                return value.asLong();
            }
            String lexical = value.asText("").trim();
            if (lexical.matches("[1-9][0-9]{0,18}")) {
                try {
                    return Long.parseLong(lexical);
                } catch (NumberFormatException ignored) {
                    // Continue to the next supported catalog alias.
                }
            }
        }
        throw exception(AD_SESSION_INVALID);
    }

    private int firstPositiveInt(JsonNode data, String... names) {
        long value = firstPositiveLong(data, names);
        if (value > Integer.MAX_VALUE) {
            throw exception(AD_SESSION_INVALID);
        }
        return (int) value;
    }

    private int firstPositiveInt(JsonNode data, int defaultValue, String... names) {
        for (String name : names) {
            if (!data.path(name).isMissingNode() && !data.path(name).isNull()
                    && !data.path(name).asText("").trim().isEmpty()) {
                return firstPositiveInt(data, name);
            }
        }
        return defaultValue;
    }

    private int firstNonNegativeInt(JsonNode data, int defaultValue, String... names) {
        for (String name : names) {
            JsonNode value = data.path(name);
            if (value.isMissingNode() || value.isNull() || value.asText("").trim().isEmpty()) {
                continue;
            }
            String lexical = value.asText("").trim();
            if (!lexical.matches("[0-9]{1,9}")) {
                throw exception(AD_SESSION_INVALID);
            }
            return Integer.parseInt(lexical);
        }
        return defaultValue;
    }

    private void requirePositive(Number value) {
        if (value == null || value.longValue() <= 0) {
            throw exception(AD_SESSION_INVALID);
        }
    }
}
