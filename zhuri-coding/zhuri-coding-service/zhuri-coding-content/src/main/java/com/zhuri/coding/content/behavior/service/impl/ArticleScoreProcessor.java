package com.zhuri.coding.content.behavior.service.impl;

import com.zhuri.coding.content.behavior.service.BehaviorPostProcessor;
import com.zhuri.coding.content.mapper.article.ApArticleMapper;
import com.zhuri.coding.model.behavior.BehaviorContext;
import com.zhuri.coding.model.behavior.BehaviorResult;
import com.zhuri.coding.model.behavior.BehaviorType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 文章热度分后置处理器
 * 用户对文章进行互动后，维护互动计数并重算热度分。
 *
 * <p><b>计数归属（重要）</b>：<b>评论的计数不由本处理器递增</b>——
 * 评论/回复在各自的写入链路里已经 +1（含作者自评，自评不发行为事件），
 * 这里再 +1 会让跨用户评论被算成两次。本处理器对评论只做"按最新计数重算热度分"。
 * 其余三类行为（点赞/收藏/浏览）的计数仍由这里递增，因为它们的入口就是事件总线。
 *
 * <p>行为 → 热度分影响：
 * <ul>
 *   <li>点赞文章 → likes +1 → 重算热度分</li>
 *   <li>收藏文章 → collection +1 → 重算热度分</li>
 *   <li>评论文章 → 计数已在评论链路 +1 → <b>只重算热度分</b></li>
 *   <li>浏览文章 → views +1 → 重算热度分</li>
 * </ul>
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

        // 评论：计数已由评论链路（一级评论与回复、含作者自评）原子 +1，这里只重算热度分，
        // 否则跨用户评论会「链路 +1、总线再 +1」被算成两次
        if (type == BehaviorType.COMMENT_ARTICLE) {
            try {
                apArticleMapper.recalculateScore(targetId);
                log.debug("文章{}评论后热度分已重算", targetId);
            } catch (Exception e) {
                log.error("文章{}热度分重算失败: action={}", targetId, type.getCode(), e);
            }
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
            case BROWSE_ARTICLE -> "views";
            default -> null;
        };
    }
}