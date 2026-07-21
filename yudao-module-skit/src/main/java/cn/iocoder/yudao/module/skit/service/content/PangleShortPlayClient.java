package cn.iocoder.yudao.module.skit.service.content;

public interface PangleShortPlayClient {

    Drama fetchDrama(String siteId, String serverKey, long dramaId);

    final class Drama {
        private final long dramaId;
        private final String title;
        private final String description;
        private final String coverImage;
        private final long categoryId;
        private final String categoryName;
        private final int totalEpisodes;
        private final long createTime;
        private final int completionStatus;

        public Drama(long dramaId, String title, String description, String coverImage,
                     long categoryId, String categoryName, int totalEpisodes,
                     long createTime, int completionStatus) {
            this.dramaId = dramaId;
            this.title = title;
            this.description = description;
            this.coverImage = coverImage;
            this.categoryId = categoryId;
            this.categoryName = categoryName;
            this.totalEpisodes = totalEpisodes;
            this.createTime = createTime;
            this.completionStatus = completionStatus;
        }

        public long getDramaId() { return dramaId; }
        public String getTitle() { return title; }
        public String getDescription() { return description; }
        public String getCoverImage() { return coverImage; }
        public long getCategoryId() { return categoryId; }
        public String getCategoryName() { return categoryName; }
        public int getTotalEpisodes() { return totalEpisodes; }
        public long getCreateTime() { return createTime; }
        public int getCompletionStatus() { return completionStatus; }
    }
}
