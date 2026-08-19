package com.heima.model.article.dtos;

import lombok.Data;

@Data
public class ArticleRecommendDto {
    private String channel;   // 频道ID，__all__表示全站；分类频道为具体数字ID字符串
    private Integer size;     // 每页大小，默认10
    private Long seed;        // 随机种子，null时服务端生成
    private Integer page;     // 页码，从0开始
    private String tagName;   // 标签名过滤
    private String subTab;    // 分栏：recommend-推荐(默认) / latest-最新
    private String type;      // 场景：all-综合 / follow-关注 / cate-分类（由各入口端点注入，用于分流与日志）
}