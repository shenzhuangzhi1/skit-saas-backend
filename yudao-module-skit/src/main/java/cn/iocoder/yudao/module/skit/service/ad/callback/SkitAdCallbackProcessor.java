package cn.iocoder.yudao.module.skit.service.ad.callback;

/**
 * Applies one already claimed callback inbox item inside its derived tenant context.
 *
 * <p>The caller may supply only immutable routing metadata obtained by the inbox claim. The
 * processor locks and revalidates the complete tenant/account/session envelope before creating any
 * entitlement or financial fact.</p>
 */
public interface SkitAdCallbackProcessor {

    ProcessResult process(long tenantId, long adAccountId, long callbackInboxId, String leaseOwner);

    enum Outcome {
        SUCCEEDED,
        REJECTED,
        ALREADY_PROCESSED
    }

    final class ProcessResult {

        private final Outcome outcome;
        private final String errorCode;

        private ProcessResult(Outcome outcome, String errorCode) {
            this.outcome = outcome;
            this.errorCode = errorCode;
        }

        public static ProcessResult succeeded() {
            return new ProcessResult(Outcome.SUCCEEDED, null);
        }

        public static ProcessResult rejected(String errorCode) {
            return new ProcessResult(Outcome.REJECTED, errorCode);
        }

        public static ProcessResult alreadyProcessed() {
            return new ProcessResult(Outcome.ALREADY_PROCESSED, null);
        }

        public Outcome getOutcome() {
            return outcome;
        }

        public String getErrorCode() {
            return errorCode;
        }
    }
}
