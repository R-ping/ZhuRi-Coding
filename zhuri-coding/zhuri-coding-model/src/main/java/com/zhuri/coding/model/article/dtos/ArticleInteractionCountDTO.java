package com.heima.model.article.dtos;

import lombok.Data;

import java.io.Serializable;

/**
 * 文章近期互动聚合结果（用于推荐评分中的「近期热度」信号）。
 * <p>
 * 数据回流闭环：用户在浏览历史(ap_browse_history)中的真实阅读会被聚合成本 DTO，
 * 推荐侧据此对近期被高频阅读的文章做加成，让新内容因真实反馈而上浮。
 * </p>
 */
@Data
public class ArticleInteractionCountDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 文章ID */
    private Long articleId;

    /** 窗口内互动（阅读）次数 */
    private Long cnt;
}