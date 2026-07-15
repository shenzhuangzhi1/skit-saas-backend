package cn.iocoder.yudao.module.skit.service.member;

/**
 * Resolves tenant-owned content and the immutable episode range an ad is allowed to unlock.
 */
public interface SkitContentScopeService {

    AccessibleDrama requireAccessibleDrama(Long dramaId);

    UnlockScope resolveUnlockScopeForUpdate(Long memberId, Long dramaId, Integer requestedEpisodeNo);

    final class AccessibleDrama {

        private final Long tenantId;
        private final Long catalogRecordId;
        private final Long dramaId;
        private final Integer totalEpisodes;
        private final Integer freeEpisodes;
        private final Integer unlockSize;

        public AccessibleDrama(Long tenantId, Long catalogRecordId, Long dramaId,
                               Integer totalEpisodes, Integer freeEpisodes, Integer unlockSize) {
            this.tenantId = tenantId;
            this.catalogRecordId = catalogRecordId;
            this.dramaId = dramaId;
            this.totalEpisodes = totalEpisodes;
            this.freeEpisodes = freeEpisodes;
            this.unlockSize = unlockSize;
        }

        public Long getTenantId() {
            return tenantId;
        }

        public Long getCatalogRecordId() {
            return catalogRecordId;
        }

        public Long getDramaId() {
            return dramaId;
        }

        public Integer getTotalEpisodes() {
            return totalEpisodes;
        }

        public Integer getFreeEpisodes() {
            return freeEpisodes;
        }

        public Integer getUnlockSize() {
            return unlockSize;
        }
    }

    final class UnlockScope {

        private final Long tenantId;
        private final Long catalogRecordId;
        private final Long dramaId;
        private final Integer episodeFrom;
        private final Integer episodeTo;
        private final String canonicalScope;
        private final boolean alreadyEntitled;

        public UnlockScope(Long tenantId, Long catalogRecordId, Long dramaId,
                           Integer episodeFrom, Integer episodeTo, String canonicalScope,
                           boolean alreadyEntitled) {
            this.tenantId = tenantId;
            this.catalogRecordId = catalogRecordId;
            this.dramaId = dramaId;
            this.episodeFrom = episodeFrom;
            this.episodeTo = episodeTo;
            this.canonicalScope = canonicalScope;
            this.alreadyEntitled = alreadyEntitled;
        }

        public Long getTenantId() {
            return tenantId;
        }

        public Long getCatalogRecordId() {
            return catalogRecordId;
        }

        public Long getDramaId() {
            return dramaId;
        }

        public Integer getEpisodeFrom() {
            return episodeFrom;
        }

        public Integer getEpisodeTo() {
            return episodeTo;
        }

        public String getCanonicalScope() {
            return canonicalScope;
        }

        public boolean isAlreadyEntitled() {
            return alreadyEntitled;
        }
    }
}
