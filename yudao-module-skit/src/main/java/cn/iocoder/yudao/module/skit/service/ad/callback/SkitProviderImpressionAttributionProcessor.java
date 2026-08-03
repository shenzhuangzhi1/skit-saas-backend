package cn.iocoder.yudao.module.skit.service.ad.callback;

/** Converts one leased provider Inbox row into the existing tenant impression pipeline. */
public interface SkitProviderImpressionAttributionProcessor {

    ProcessResult process(long providerConnectionId, long inboxId, String leaseOwner);

    enum ProcessResult {
        SUCCEEDED,
        QUARANTINED,
        STALE
    }
}
