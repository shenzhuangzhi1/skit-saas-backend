package cn.iocoder.yudao.module.skit.service.provider;

/** Deliberate phase-1 seam: production route issuance stays disabled until Task 8 proves every gate. */
public interface SkitProviderImpressionProductionGate {

    void assertProductionIssueAllowed(long providerConnectionId, long providerRouteId, long actorUserId);
}
