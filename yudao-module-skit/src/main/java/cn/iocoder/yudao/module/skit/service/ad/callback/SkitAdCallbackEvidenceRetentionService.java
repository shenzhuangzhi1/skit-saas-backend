package cn.iocoder.yudao.module.skit.service.ad.callback;

public interface SkitAdCallbackEvidenceRetentionService {

    RetentionResult runOnce();

    final class RetentionResult {
        private final int erasedPayloadCount;
        private final int deletedAttemptCount;
        private final int deletedEdgeAttemptCount;

        public RetentionResult(int erasedPayloadCount, int deletedAttemptCount,
                               int deletedEdgeAttemptCount) {
            this.erasedPayloadCount = erasedPayloadCount;
            this.deletedAttemptCount = deletedAttemptCount;
            this.deletedEdgeAttemptCount = deletedEdgeAttemptCount;
        }

        public int getErasedPayloadCount() {
            return erasedPayloadCount;
        }

        public int getDeletedAttemptCount() {
            return deletedAttemptCount;
        }

        public int getDeletedEdgeAttemptCount() {
            return deletedEdgeAttemptCount;
        }
    }
}
