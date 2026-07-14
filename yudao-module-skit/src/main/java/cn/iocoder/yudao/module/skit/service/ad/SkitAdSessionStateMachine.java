package cn.iocoder.yudao.module.skit.service.ad;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * Keeps client telemetry separate from server-authoritative reward, entitlement and revenue facts.
 */
public final class SkitAdSessionStateMachine {

    public enum ClientLifecycle { CREATED, LOADING, SHOWN, CLIENT_REWARDED, CLOSED, FAILED, LOAD_EXPIRED }

    public enum ClientEvent { LOAD_STARTED, SHOWN, REWARD_OBSERVED, CLOSED, FAILED }

    public enum RewardVerification { PENDING, SIGNED_VERIFIED, REJECTED, VERIFY_TIMEOUT }

    public enum Entitlement { NONE, GRANTED, SECURITY_REVOKED }

    public enum Revenue { NONE, IMPRESSION_PENDING_REWARD, FROZEN, RECONCILING, RECONCILED, SUSPENSE }

    public ClientLifecycle applyClientEvent(ClientLifecycle current, ClientEvent event) {
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(event, "event");
        ClientLifecycle target = target(event);
        if (current == target) {
            return current;
        }
        if (!isAllowedClientTransition(current, target)) {
            throw new IllegalStateException("Invalid client lifecycle transition " + current + " -> " + target);
        }
        return target;
    }

    public SessionFacts applyClientEvent(SessionFacts current, ClientEvent event) {
        Objects.requireNonNull(current, "current");
        return new SessionFacts(applyClientEvent(current.getClientLifecycle(), event),
                current.getRewardVerification(), current.getEntitlement(), current.getRevenue());
    }

    public ClientLifecycle expireLoad(ClientLifecycle current, LocalDateTime loadExpiresAt, LocalDateTime now) {
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(loadExpiresAt, "loadExpiresAt");
        Objects.requireNonNull(now, "now");
        if (current == ClientLifecycle.CREATED && now.isAfter(loadExpiresAt)) {
            return ClientLifecycle.LOAD_EXPIRED;
        }
        return current;
    }

    public RewardVerification expireReward(RewardVerification current,
                                           LocalDateTime rewardAcceptUntil,
                                           LocalDateTime now) {
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(rewardAcceptUntil, "rewardAcceptUntil");
        Objects.requireNonNull(now, "now");
        if (current == RewardVerification.PENDING && now.isAfter(rewardAcceptUntil)) {
            return RewardVerification.VERIFY_TIMEOUT;
        }
        return current;
    }

    public boolean shouldReleaseActiveScope(RewardVerification reward,
                                            Entitlement entitlement,
                                            LocalDateTime rewardAcceptUntil,
                                            LocalDateTime now) {
        Objects.requireNonNull(reward, "reward");
        Objects.requireNonNull(entitlement, "entitlement");
        Objects.requireNonNull(rewardAcceptUntil, "rewardAcceptUntil");
        Objects.requireNonNull(now, "now");
        if (entitlement != Entitlement.NONE) {
            return true;
        }
        if (reward == RewardVerification.REJECTED || reward == RewardVerification.VERIFY_TIMEOUT) {
            return true;
        }
        return reward == RewardVerification.PENDING && now.isAfter(rewardAcceptUntil);
    }

    private static ClientLifecycle target(ClientEvent event) {
        switch (event) {
            case LOAD_STARTED:
                return ClientLifecycle.LOADING;
            case SHOWN:
                return ClientLifecycle.SHOWN;
            case REWARD_OBSERVED:
                return ClientLifecycle.CLIENT_REWARDED;
            case CLOSED:
                return ClientLifecycle.CLOSED;
            case FAILED:
                return ClientLifecycle.FAILED;
            default:
                throw new IllegalStateException("Unsupported client event " + event);
        }
    }

    private static boolean isAllowedClientTransition(ClientLifecycle current, ClientLifecycle target) {
        if (target == ClientLifecycle.FAILED) {
            return current == ClientLifecycle.CREATED || current == ClientLifecycle.LOADING
                    || current == ClientLifecycle.SHOWN || current == ClientLifecycle.CLIENT_REWARDED;
        }
        switch (current) {
            case CREATED:
                return target == ClientLifecycle.LOADING;
            case LOADING:
                return target == ClientLifecycle.SHOWN;
            case SHOWN:
                return target == ClientLifecycle.CLIENT_REWARDED || target == ClientLifecycle.CLOSED;
            case CLIENT_REWARDED:
                return target == ClientLifecycle.CLOSED;
            default:
                return false;
        }
    }

    public static final class SessionFacts {

        private final ClientLifecycle clientLifecycle;
        private final RewardVerification rewardVerification;
        private final Entitlement entitlement;
        private final Revenue revenue;

        public SessionFacts(ClientLifecycle clientLifecycle, RewardVerification rewardVerification,
                            Entitlement entitlement, Revenue revenue) {
            this.clientLifecycle = Objects.requireNonNull(clientLifecycle, "clientLifecycle");
            this.rewardVerification = Objects.requireNonNull(rewardVerification, "rewardVerification");
            this.entitlement = Objects.requireNonNull(entitlement, "entitlement");
            this.revenue = Objects.requireNonNull(revenue, "revenue");
        }

        public ClientLifecycle getClientLifecycle() {
            return clientLifecycle;
        }

        public RewardVerification getRewardVerification() {
            return rewardVerification;
        }

        public Entitlement getEntitlement() {
            return entitlement;
        }

        public Revenue getRevenue() {
            return revenue;
        }
    }

}
