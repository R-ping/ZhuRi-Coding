package com.zhuri.coding.model.level.dtos;

import lombok.Data;
import java.math.BigDecimal;
import java.util.List;

@Data
public class JScoreDetailVO {
    private List<JScoreDetailItem> list;
    private String nextCursor;
    private Boolean hasMore;

    @Data
    public static class JScoreDetailItem {
        private String id;
        private String createdAt;
        private String actionCode;
        /** 行为展示名（如"点赞一篇文章"），取自 ap_behavior_config.action_name */
        private String actionName;
        private String actionDesc;
        private BigDecimal score;
        private String category;
    }
}