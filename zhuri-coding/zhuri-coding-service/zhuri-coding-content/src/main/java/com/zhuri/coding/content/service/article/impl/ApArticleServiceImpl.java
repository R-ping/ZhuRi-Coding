package com.zhuri.coding.content.service.article.impl;

import com.alibaba.fastjson.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zhuri.coding.common.constants.ArticleConstants;
import com.zhuri.coding.content.mapper.article.ApArticleMapper;
import com.zhuri.coding.content.service.article.ApArticleService;
import com.zhuri.coding.content.service.outbox.OutboxService;
import com.zhuri.coding.content.service.outbox.handler.ArticlePublishHandler;
import com.zhuri.coding.model.article.dtos.ArticleDto;
import com.zhuri.coding.model.article.dtos.ArticleHomeDto;
import com.zhuri.coding.model.article.pojos.ApArticle;
import com.zhuri.coding.model.common.dtos.ResponseResult;
import com.zhuri.coding.model.mess.UpdateArticleMess;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(rollbackFor = Exception.class)
@Slf4j
public class ApArticleServiceImpl extends ServiceImpl<ApArticleMapper, ApArticle> implements ApArticleService {

    @Autowired
    private ApArticleMapper apArticleMapper;

    private final static short MAX_PAGE_SIZE = 50;

    /**
     * 统一 Outbox —— 迁移阶段 2 起它是文章发布的**唯一**执行依据。
     *
     * <p>阶段 1 时该字段只是「双写对照」的旁路（article_event 才是执行依据）；
     * 切读后 article_event 与 ArticlePublishEvent 的写入均已移除，本字段转为主路径。
     */
    @Autowired
    private OutboxService outboxService;

    /**
     * 加载文章列表
     *
     * @param type 1 加载更多   2 加载最新
     */
    @Override
    public ResponseResult load(ArticleHomeDto dto, Short type) {
        //1.检验参数
        //分页条数的校验
        Integer size = dto.getSize();
        if (size == null || size == 0) {
            size = 10;
        }
        //分页的值不超过50
        size = Math.min(size, MAX_PAGE_SIZE);
        dto.setSize(size);
        //校验参数  -->type
        if (!type.equals(ArticleConstants.LOADTYPE_LOAD_MORE) && !type.equals(ArticleConstants.LOADTYPE_LOAD_NEW)) {
            type = ArticleConstants.LOADTYPE_LOAD_MORE;
        }
        //频道参数校验
        if (StringUtils.isBlank(dto.getTag())) {
            dto.setTag(ArticleConstants.DEFAULT_TAG);
        }
        //时间校验
        if (dto.getMaxBehotTime() == null) {
            dto.setMaxBehotTime(new Date());
        }
        if (dto.getMinBehotTime() == null) {
            dto.setMinBehotTime(new Date());
        }
        //2.查询
        List<ApArticle> articleList = apArticleMapper.loadArticleList(dto, type);
        //3.结果返回 - null-safe 处理
        List<Map<String, Object>> resultList = articleList.stream().map(ApArticle::nullSafeToMap).collect(Collectors.toList());
        return ResponseResult.okResult(resultList);
    }

    /**
     * 创建文章发布事件（延迟任务消费的同步部分，仅落锚）
     * <p>单延迟方案 · 异步解耦版：本方法只负责「校验 + 本地消息表落锚(INIT) + 发布执行事件」，
     * 置 DB 发布态与 ES 同步由 {@link com.zhuri.coding.content.event.ArticlePublishEventListener} 异步执行，
     * 未完成事件由 20s 扫描补偿收敛。落锚失败返回 false 由调用方记日志（任务仍会消费完成，不回滚重投）。
     */
    @Override
    public boolean createArticleEvent(ApArticle article) {
        //1.检查参数
        if (article == null) {
            log.error("文章保存失败，参数为空");
            return false;
        }
        Long articleId = article.getId();
        if (articleId == null || getById(articleId) == null) {
            log.error("文章不存在，可能有由于审核逻辑出问题，导致文章回滚掉了，文章id：{}", articleId);
            return false;
        }
        // 落统一 Outbox 锚点（status=PENDING）。本类带类级 @Transactional，
        // 事件与业务同事务提交；提交后 5s 内即被 OutboxDispatcher 抢占执行
        // （幂等键 "article_publish:{articleId}" + uk_event_key 保证同一文章只有一条在途事件）。
        //
        // 【迁移阶段 2 · 切读】本方法原先还做两件事，已一并移除：
        //   ① 写 article_event 锚点 —— 旧状态机的载体，切读后无人再读它；
        //   ② 发布 ArticlePublishEvent —— 旧执行入口（@Async 监听器 → executePublish）。
        // 移除这两处写入后，旧链路的「异步监听器 + 20s 补偿扫描」因**无数据可扫、无事件可消费**
        // 而自然失效，那两个类无需改动（代码留待阶段 3 与 article_event 表一并清理）。
        //
        // 返回值语义随之收紧：阶段 1 时 Outbox 写失败是可容忍的 best-effort（旧链路兜底），
        // 切读后**落库失败即本次发布失败**，没有第二条路径兜底，故必须 ERROR 告警供人工察觉。
        try {
            boolean recorded = outboxService.record(
                    ArticlePublishHandler.eventKey(articleId),
                    ArticlePublishHandler.EVENT_TYPE,
                    ArticlePublishHandler.payload(articleId));
            if (recorded) {
                log.info("文章发布事件落 Outbox 成功，文章id：{}", articleId);
            } else {
                // uk_event_key 冲突 = 同一篇文章已有在途发布事件（重复投递），幂等短路按成功处理
                log.info("发布事件已存在（幂等短路，无需重复投递）, articleId={}", articleId);
            }
            // 两种情况都算落锚成功：事件已存在同样满足「有执行依据」这一前提
            return true;
        } catch (Exception e) {
            log.error("文章发布事件落 Outbox 失败，该文章本次将不会发布，需人工排查, articleId={}", articleId, e);
            return false;
        }
    }

    @Override
    public void updateScoreByBehavior(Long articleId, UpdateArticleMess.UpdateArticleType type, Integer add) {
        if (articleId == null) {
            return;
        }
        // 统一走原子 SQL 重算热度分：公式与事件总线的 updateInteractionAndScore 完全一致
        // （likes×3 + views + comment×3 + collection×6），消除 Java"读-改-写"整行覆盖的
        // 时序竞态，保证 ap_article.score 全局只有一套计算口径。
        apArticleMapper.recalculateScore(articleId);
        log.info("文章:{} 热度分数已按最新计数重算", articleId);
    }

    @Override
    public List<Map<String, Object>> listByAuthorId(ArticleDto dto) {
        // 构建查询条件
        LambdaQueryWrapper<ApArticle> wrapper = new LambdaQueryWrapper<>();

        // 固定条件：作者ID（如果必填）
        wrapper.eq(dto.getAuthorId() != null, ApArticle::getAuthorId, dto.getAuthorId());

        // 可选条件：频道ID
        wrapper.eq(dto.getChannelId() != null, ApArticle::getChannelId, dto.getChannelId());

        // 可选条件：JSON 标签重叠查询（修复点：将 List 转为 JSON 字符串）
        if (dto.getTags() != null && !dto.getTags().isEmpty()) {
            String tagsJson = JSON.toJSONString(dto.getTags()); // 得到 ["44","45"]
            wrapper.apply("JSON_OVERLAPS(tags, {0})", tagsJson);
        }

        // 可选条件：删除状态
        wrapper.eq(dto.getIsDeleted() != null, ApArticle::getIsDeleted, dto.getIsDeleted());

        List<ApArticle> articles = list(wrapper);
        return articles.stream().map(ApArticle::nullSafeToMap).collect(Collectors.toList());
    }
}