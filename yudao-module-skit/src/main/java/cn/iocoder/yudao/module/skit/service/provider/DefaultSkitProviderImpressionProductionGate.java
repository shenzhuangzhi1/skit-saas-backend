package cn.iocoder.yudao.module.skit.service.provider;

import org.springframework.stereotype.Component;

/** Safe default. Task 8 replaces this only with an evidence-backed production adapter. */
@Component
public final class DefaultSkitProviderImpressionProductionGate implements SkitProviderImpressionProductionGate {

    @Override
    public void assertProductionIssueAllowed(long providerConnectionId, long providerRouteId, long actorUserId) {
        throw new IllegalStateException("Production provider callback issuance is gated");
    }
}
