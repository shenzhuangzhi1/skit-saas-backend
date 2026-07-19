package cn.iocoder.yudao.module.skit.service.ad;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkitAdSessionStateMachineTest {

    private final SkitAdSessionStateMachine machine = new SkitAdSessionStateMachine();

    @Test
    void acceptsOnlyStrictForwardClientLifecycleAndIdempotentReplay() {
        assertEquals(SkitAdSessionStateMachine.ClientLifecycle.LOADING,
                machine.applyClientEvent(SkitAdSessionStateMachine.ClientLifecycle.CREATED,
                        SkitAdSessionStateMachine.ClientEvent.LOAD_STARTED));
        assertEquals(SkitAdSessionStateMachine.ClientLifecycle.SHOWN,
                machine.applyClientEvent(SkitAdSessionStateMachine.ClientLifecycle.LOADING,
                        SkitAdSessionStateMachine.ClientEvent.SHOWN));
        assertEquals(SkitAdSessionStateMachine.ClientLifecycle.CLIENT_REWARDED,
                machine.applyClientEvent(SkitAdSessionStateMachine.ClientLifecycle.SHOWN,
                        SkitAdSessionStateMachine.ClientEvent.REWARD_OBSERVED));
        assertEquals(SkitAdSessionStateMachine.ClientLifecycle.CLOSED,
                machine.applyClientEvent(SkitAdSessionStateMachine.ClientLifecycle.CLIENT_REWARDED,
                        SkitAdSessionStateMachine.ClientEvent.CLOSED));
        assertEquals(SkitAdSessionStateMachine.ClientLifecycle.CLOSED,
                machine.applyClientEvent(SkitAdSessionStateMachine.ClientLifecycle.CLOSED,
                        SkitAdSessionStateMachine.ClientEvent.CLOSED));

        assertThrows(IllegalStateException.class,
                () -> machine.applyClientEvent(SkitAdSessionStateMachine.ClientLifecycle.CREATED,
                        SkitAdSessionStateMachine.ClientEvent.REWARD_OBSERVED));
        assertThrows(IllegalStateException.class,
                () -> machine.applyClientEvent(SkitAdSessionStateMachine.ClientLifecycle.CLOSED,
                        SkitAdSessionStateMachine.ClientEvent.SHOWN));
    }

    @Test
    void lifecycleOnlyOverloadNeverReleasesActiveScope() {
        LocalDateTime deadline = LocalDateTime.of(2026, 7, 14, 12, 20);

        assertEquals(SkitAdSessionStateMachine.ClientLifecycle.CLOSED,
                machine.applyClientEvent(SkitAdSessionStateMachine.ClientLifecycle.SHOWN,
                        SkitAdSessionStateMachine.ClientEvent.CLOSED));
        assertEquals(SkitAdSessionStateMachine.ClientLifecycle.FAILED,
                machine.applyClientEvent(SkitAdSessionStateMachine.ClientLifecycle.LOADING,
                        SkitAdSessionStateMachine.ClientEvent.FAILED));
        assertFalse(machine.shouldReleaseActiveScope(
                SkitAdSessionStateMachine.RewardVerification.PENDING,
                SkitAdSessionStateMachine.Entitlement.NONE, deadline, deadline.minusSeconds(1)));
        assertFalse(machine.shouldReleaseActiveScope(
                SkitAdSessionStateMachine.RewardVerification.SIGNED_VERIFIED,
                SkitAdSessionStateMachine.Entitlement.NONE, deadline, deadline.plusHours(1)));
    }

    @Test
    void preShowFailureRemainsTelemetryOnlyInSessionFacts() {
        SkitAdSessionStateMachine.SessionFacts before = new SkitAdSessionStateMachine.SessionFacts(
                SkitAdSessionStateMachine.ClientLifecycle.LOADING,
                SkitAdSessionStateMachine.RewardVerification.PENDING,
                SkitAdSessionStateMachine.Entitlement.NONE,
                SkitAdSessionStateMachine.Revenue.NONE);

        SkitAdSessionStateMachine.SessionFacts after = machine.applyClientEvent(
                before, SkitAdSessionStateMachine.ClientEvent.FAILED);

        assertEquals(SkitAdSessionStateMachine.ClientLifecycle.FAILED, after.getClientLifecycle());
        assertEquals(SkitAdSessionStateMachine.RewardVerification.PENDING,
                after.getRewardVerification());
        assertEquals(SkitAdSessionStateMachine.Entitlement.NONE, after.getEntitlement());
        assertEquals(SkitAdSessionStateMachine.Revenue.NONE, after.getRevenue());
    }

    @Test
    void onlyServerAuthorityCanExpireLoadRewardAndReleaseScope() {
        LocalDateTime loadDeadline = LocalDateTime.of(2026, 7, 14, 12, 5);
        LocalDateTime rewardDeadline = LocalDateTime.of(2026, 7, 14, 12, 20);

        assertEquals(SkitAdSessionStateMachine.ClientLifecycle.CREATED,
                machine.expireLoad(SkitAdSessionStateMachine.ClientLifecycle.CREATED,
                        loadDeadline, loadDeadline));
        assertEquals(SkitAdSessionStateMachine.ClientLifecycle.LOAD_EXPIRED,
                machine.expireLoad(SkitAdSessionStateMachine.ClientLifecycle.CREATED,
                        loadDeadline, loadDeadline.plusNanos(1)));
        assertEquals(SkitAdSessionStateMachine.ClientLifecycle.SHOWN,
                machine.expireLoad(SkitAdSessionStateMachine.ClientLifecycle.SHOWN,
                        loadDeadline, loadDeadline.plusMinutes(1)));

        assertEquals(SkitAdSessionStateMachine.RewardVerification.PENDING,
                machine.expireReward(SkitAdSessionStateMachine.RewardVerification.PENDING,
                        rewardDeadline, rewardDeadline));
        assertEquals(SkitAdSessionStateMachine.RewardVerification.VERIFY_TIMEOUT,
                machine.expireReward(SkitAdSessionStateMachine.RewardVerification.PENDING,
                        rewardDeadline, rewardDeadline.plusNanos(1)));
        assertEquals(SkitAdSessionStateMachine.RewardVerification.SIGNED_VERIFIED,
                machine.expireReward(SkitAdSessionStateMachine.RewardVerification.SIGNED_VERIFIED,
                        rewardDeadline, rewardDeadline.plusHours(1)));

        assertTrue(machine.shouldReleaseActiveScope(
                SkitAdSessionStateMachine.RewardVerification.VERIFY_TIMEOUT,
                SkitAdSessionStateMachine.Entitlement.NONE, rewardDeadline, rewardDeadline.plusNanos(1)));
        assertTrue(machine.shouldReleaseActiveScope(
                SkitAdSessionStateMachine.RewardVerification.REJECTED,
                SkitAdSessionStateMachine.Entitlement.NONE, rewardDeadline, rewardDeadline.minusMinutes(1)));
        assertTrue(machine.shouldReleaseActiveScope(
                SkitAdSessionStateMachine.RewardVerification.SIGNED_VERIFIED,
                SkitAdSessionStateMachine.Entitlement.GRANTED, rewardDeadline, rewardDeadline.minusMinutes(1)));
    }

    @Test
    void clientCannotMutateServerRewardEntitlementOrRevenueFacts() {
        for (SkitAdSessionStateMachine.ClientEvent event : SkitAdSessionStateMachine.ClientEvent.values()) {
            SkitAdSessionStateMachine.SessionFacts before = new SkitAdSessionStateMachine.SessionFacts(
                    SkitAdSessionStateMachine.ClientLifecycle.SHOWN,
                    SkitAdSessionStateMachine.RewardVerification.PENDING,
                    SkitAdSessionStateMachine.Entitlement.NONE,
                    SkitAdSessionStateMachine.Revenue.IMPRESSION_PENDING_REWARD);
            SkitAdSessionStateMachine.SessionFacts after;
            try {
                after = machine.applyClientEvent(before, event);
            } catch (IllegalStateException ignored) {
                continue;
            }
            assertEquals(before.getRewardVerification(), after.getRewardVerification());
            assertEquals(before.getEntitlement(), after.getEntitlement());
            assertEquals(before.getRevenue(), after.getRevenue());
        }
    }

}
