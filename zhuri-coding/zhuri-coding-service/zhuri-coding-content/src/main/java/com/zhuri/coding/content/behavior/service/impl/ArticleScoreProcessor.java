package com.heima.content.behavior.service.impl;

import com.heima.content.behavior.service.BehaviorPostProcessor;
import com.heima.content.mapper.article.ApArticleMapper;
import com.heima.model.behavior.BehaviorContext;
import com.heima.model.behavior.BehaviorResult;
import com.heima.model.behavior.BehaviorType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 文章热度分后置处理器
 * 用户对文章/沸点进行互动后，更新对应的热度分数
 *
 * 行为 → 热度分影响：
 * - 点赞文章 → likes +1 → 重新计算热度分
 * - 收藏文章 → collection +1 → 重新计算热度分
 * - 评论文章 → comment +1 → 重新计算热度分
 * - 浏览文章 → views +1 → 重新计算热度分
 */
@Slf4j
@Component
public class ArticleScoreProcessor implements BehaviorPostProcessor {

    @Autowired
    private ApArticleMapper apArticleMapper;

    @Override
    public void postProcess(BehaviorContext context, BehaviorResult result) {
        BehaviorType type = context.getBehaviorType();

        // 仅处理文章相关行为（targetType=1）
        if (context.getTargetType() == null || context.getTargetType() != 1) {
            return;
        }

        Long targetId = context.getTargetId();
        if (targetId == null) {
            return;
        }

        // 判断是否为需要更新热度分的行为
        String field = mapToScoreField(type);
        if (field == null) {
            return;
        }

        try {
            // 单条原子 UPDATE：递增对应字段并同步重算热度分，避免并发下"读-改-写"丢计数
            apArticleMapper.updateInteractionAndScore(targetId, field, 1);
            log.debug("文章{}热度分已更新: action={}", targetId, type.getCode());
        } catch (Exception e) {
            log.error("文章{}热度分更新失败: action={}", targetId, type.getCode(), e);
        }
    }

    @Override
    public int getOrder() {
        return 2; // 在等级积分(1)之后，通知(4)之前执行
    }

    /**
     * 将行为类型映射为需要更新的文章字段名
     * 返回值作为 UPDATE 的列名，仅为固定白名单值（likes/collection/comment/views），杜绝 SQL 注入
     */
    private String mapToScoreField(BehaviorType type) {
        return switch (type) {
            case LIKE_ARTICLE -> "likes";
            case COLLECT_ARTICLE -> "collection";
            case COMMENT_ARTICLE -> "comment";
            case BROWSE_ARTICLE -> "views";
            default -> null;
        };
    }
}