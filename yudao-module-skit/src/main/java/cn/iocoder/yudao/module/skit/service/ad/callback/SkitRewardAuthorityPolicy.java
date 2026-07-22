package cn.iocoder.yudao.module.skit.service.ad.callback;

import cn.iocoder.yudao.module.skit.dal.dataobject.ad.SkitAdCallbackInboxDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.ad.SkitAdNetworkCapabilityDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.ad.SkitAdSessionDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.ad.SkitTenantAdCapabilityDO;
import cn.iocoder.yudao.module.skit.dal.mysql.ad.SkitAdNetworkCapabilityMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.ad.SkitTenantAdCapabilityMapper;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Shared fail-closed authority policy for reward ingress and asynchronous processing.
 * Network authority is always derived from verified signed ILRD, then constrained by the
 * tenant/account selection and an exact independently verified capability row.
 */
@Component
public class SkitRewardAuthorityPolicy {

    private static final String SIGNED_REWARD = "SIGNED_REWARD";
    private static final String EPISODE_UNLOCK = "EPISODE_UNLOCK";
    private static final String SHADOW_TEST_USERS = "SHADOW_TEST_USERS";
    private static final String ENFORCED = "ENFORCED";

    private final SkitTenantAdCapabilityMapper tenantCapabilityMapper;
    private final SkitAdNetworkCapabilityMapper networkCapabilityMapper;

    public SkitRewardAuthorityPolicy(SkitTenantAdCapabilityMapper tenantCapabilityMapper,
                                     SkitAdNetworkCapabilityMapper networkCapabilityMapper) {
        this.tenantCapabilityMapper = Objects.requireNonNull(
                tenantCapabilityMapper, "tenantCapabilityMapper");
        this.networkCapabilityMapper = Objects.requireNonNull(
                networkCapabilityMapper, "networkCapabilityMapper");
    }

    public Decision authorize(Context context) {
        if (context == null || context.tenantId == null || context.tenantId <= 0
                || context.adAccountId == null || context.adAccountId <= 0
                || context.session == null || context.authority == null
                || context.authority.getSignedIlrdEvidence() == null
                || context.receivedAt == null) {
            return Decision.rejected("SIGNED_REWARD_AUTHORITY_MISSING");
        }
        TakuRewardSignatureVerifier.SignedIlrdEvidence signed =
                context.authority.getSignedIlrdEvidence();
        int networkFirmId = signed.getNetworkFirmId();
        if (context.observedNetworkFirmId == null
                || context.observedNetworkFirmId != networkFirmId) {
            return Decision.rejected("TOP_LEVEL_NETWORK_MISMATCH");
        }

        String sessionError = sessionError(context, signed);
        if (sessionError != null) {
            return Decision.rejected(sessionError);
        }
        if (context.inbox != null) {
            String inboxError = inboxError(context, signed);
            if (inboxError != null) {
                return Decision.rejected(inboxError);
            }
        }

        SkitTenantAdCapabilityDO selection =
                tenantCapabilityMapper.selectByTenantForShare(context.tenantId);
        if (selection == null || !Objects.equals(selection.getTenantId(), context.tenantId)
                || !Objects.equals(selection.getAdAccountId(), context.adAccountId)
                || !Objects.equals(selection.getDedicatedUnlockPlacementId(),
                context.session.getPlacementId())) {
            return Decision.rejected("REWARD_SELECTION_SCOPE_MISMATCH");
        }
        if (!SHADOW_TEST_USERS.equals(selection.getRolloutState())
                && !ENFORCED.equals(selection.getRolloutState())) {
            return Decision.rejected("REWARD_ROLLOUT_INACTIVE");
        }
        Set<Integer> selected;
        try {
            selected = parseSelectedNetworks(selection.getUnlockNetworkFirmIdsJson());
        } catch (IllegalArgumentException invalidStoredSelection) {
            return Decision.rejected("REWARD_SELECTION_INVALID");
        }
        if (!selected.contains(networkFirmId)) {
            return Decision.rejected("SIGNED_NETWORK_NOT_SELECTED");
        }

        SkitAdNetworkCapabilityDO capability = networkCapabilityMapper.selectForShare(
                context.tenantId, context.adAccountId, networkFirmId);
        if (!capabilityAllows(context, capability, networkFirmId)) {
            return Decision.rejected("NETWORK_CAPABILITY_REJECTED");
        }
        return Decision.authorized(networkFirmId);
    }

    private static String sessionError(Context context,
                                       TakuRewardSignatureVerifier.SignedIlrdEvidence signed) {
        SkitAdSessionDO session = context.session;
        if (!Objects.equals(session.getTenantId(), context.tenantId)
                || !Objects.equals(session.getAdAccountId(), context.adAccountId)) {
            return "REWARD_SESSION_SCOPE_MISMATCH";
        }
        if (!EPISODE_UNLOCK.equals(session.getBusinessType())
                || session.getDramaId() == null || session.getDramaId() <= 0
                || session.getEpisodeFrom() == null || session.getEpisodeFrom() <= 0
                || !session.getEpisodeFrom().equals(session.getEpisodeTo())
                || !Objects.equals(session.getUnlockScope(), "drama:" + session.getDramaId()
                + ":episode:" + session.getEpisodeFrom())) {
            return "REWARD_EPISODE_SCOPE_INVALID";
        }
        if (!Objects.equals(context.authority.getPlacementId(), session.getPlacementId())
                || (signed.getAdUnitId() != null
                && !Objects.equals(signed.getAdUnitId(), session.getPlacementId()))
                || signed.getAdsourceId() == null
                || !Objects.equals(signed.getAdsourceId(), context.authority.getAdsourceId())) {
            return "SIGNED_REWARD_AUTHORITY_MISMATCH";
        }
        if (signed.getShowCustomExt() != null
                && !Objects.equals(signed.getShowCustomExt(), session.getSessionId())) {
            return "SIGNED_SHOW_CUSTOM_EXT_MISMATCH";
        }
        if (signed.getShowId() != null && session.getProviderShowId() != null
                && !Objects.equals(signed.getShowId(), session.getProviderShowId())) {
            return "SIGNED_SHOW_MISMATCH";
        }
        return null;
    }

    private static String inboxError(Context context,
                                     TakuRewardSignatureVerifier.SignedIlrdEvidence signed) {
        SkitAdCallbackInboxDO inbox = context.inbox;
        if (!Objects.equals(inbox.getTenantId(), context.tenantId)
                || !Objects.equals(inbox.getAdAccountId(), context.adAccountId)
                || !Objects.equals(inbox.getAdSessionId(), context.session.getId())) {
            return "REWARD_INBOX_SCOPE_MISMATCH";
        }
        if (!Objects.equals(inbox.getProviderTransactionId(), context.authority.getTransactionId())
                || !Objects.equals(inbox.getPlacementId(), context.authority.getPlacementId())
                || !Objects.equals(inbox.getAdsourceId(), context.authority.getAdsourceId())
                || !Objects.equals(inbox.getNetworkFirmId(), signed.getNetworkFirmId())
                || !Objects.equals(inbox.getProviderShowId(), signed.getShowId())) {
            return "SIGNED_REWARD_AUTHORITY_MISMATCH";
        }
        boolean tokenMatchesSession = Arrays.equals(
                inbox.getExtraDataHash(), context.session.getSessionTokenHash());
        boolean signedSessionMatches = signed.getShowCustomExt() != null
                && Objects.equals(signed.getShowCustomExt(), context.session.getSessionId());
        if (!tokenMatchesSession && !signedSessionMatches) {
            return "REWARD_SESSION_BINDING_MISMATCH";
        }
        return null;
    }

    private static boolean capabilityAllows(Context context, SkitAdNetworkCapabilityDO capability,
                                             int networkFirmId) {
        return capability != null
                && Objects.equals(capability.getTenantId(), context.tenantId)
                && Objects.equals(capability.getAdAccountId(), context.adAccountId)
                && Objects.equals(capability.getNetworkFirmId(), networkFirmId)
                && SIGNED_REWARD.equals(capability.getRewardAuthority())
                && Boolean.TRUE.equals(capability.getSupportsUserId())
                && Boolean.TRUE.equals(capability.getSupportsCustomData())
                && Boolean.TRUE.equals(capability.getSupportsStableTransaction())
                && Boolean.TRUE.equals(capability.getEnabled())
                && capability.getVerifiedAt() != null
                && !capability.getVerifiedAt().isAfter(context.receivedAt);
    }

    private static Set<Integer> parseSelectedNetworks(String json) {
        if (json == null || !json.matches("\\[[0-9,]*]") || json.length() > 192) {
            throw new IllegalArgumentException("invalid selected network list");
        }
        String body = json.substring(1, json.length() - 1);
        if (body.isEmpty()) {
            return Collections.emptySet();
        }
        String[] tokens = body.split(",", -1);
        if (tokens.length > 16) {
            throw new IllegalArgumentException("too many selected networks");
        }
        Set<Integer> result = new LinkedHashSet<>();
        for (String token : tokens) {
            int parsed;
            try {
                parsed = Integer.parseInt(token);
            } catch (NumberFormatException invalid) {
                throw new IllegalArgumentException("invalid selected network", invalid);
            }
            if (parsed <= 0 || !result.add(parsed)) {
                throw new IllegalArgumentException("invalid selected network");
            }
        }
        return Collections.unmodifiableSet(result);
    }

    public static final class Context {
        private final Long tenantId;
        private final Long adAccountId;
        private final SkitAdSessionDO session;
        private final SkitAdCallbackInboxDO inbox;
        private final TakuRewardSignatureVerifier.SignedRewardAuthority authority;
        private final Integer observedNetworkFirmId;
        private final LocalDateTime receivedAt;

        private Context(Long tenantId, Long adAccountId, SkitAdSessionDO session,
                        SkitAdCallbackInboxDO inbox,
                        TakuRewardSignatureVerifier.SignedRewardAuthority authority,
                        Integer observedNetworkFirmId, LocalDateTime receivedAt) {
            this.tenantId = tenantId;
            this.adAccountId = adAccountId;
            this.session = session;
            this.inbox = inbox;
            this.authority = authority;
            this.observedNetworkFirmId = observedNetworkFirmId;
            this.receivedAt = receivedAt;
        }

        public static Context ingress(Long tenantId, Long adAccountId, SkitAdSessionDO session,
                                      TakuRewardSignatureVerifier.SignedRewardAuthority authority,
                                      Integer observedNetworkFirmId, LocalDateTime receivedAt) {
            return new Context(tenantId, adAccountId, session, null, authority,
                    observedNetworkFirmId, receivedAt);
        }

        public static Context processing(SkitAdCallbackInboxDO inbox, SkitAdSessionDO session,
                                         TakuRewardSignatureVerifier.SignedRewardAuthority authority,
                                         Integer observedNetworkFirmId) {
            return new Context(inbox == null ? null : inbox.getTenantId(),
                    inbox == null ? null : inbox.getAdAccountId(), session, inbox, authority,
                    observedNetworkFirmId, inbox == null ? null : inbox.getReceivedAt());
        }
    }

    public static final class Decision {
        private final boolean authorized;
        private final Integer networkFirmId;
        private final String errorCode;

        private Decision(boolean authorized, Integer networkFirmId, String errorCode) {
            this.authorized = authorized;
            this.networkFirmId = networkFirmId;
            this.errorCode = errorCode;
        }

        private static Decision authorized(int networkFirmId) {
            return new Decision(true, networkFirmId, null);
        }

        private static Decision rejected(String errorCode) {
            return new Decision(false, null, errorCode);
        }

        public boolean isAuthorized() {
            return authorized;
        }

        public Integer getNetworkFirmId() {
            return networkFirmId;
        }

        public String getErrorCode() {
            return errorCode;
        }
    }
}
