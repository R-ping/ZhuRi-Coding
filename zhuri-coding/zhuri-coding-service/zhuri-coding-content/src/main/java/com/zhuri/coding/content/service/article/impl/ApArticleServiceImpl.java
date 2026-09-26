package com.zhuri.coding.content.service.article.impl;

import cn.hutool.json.JSONUtil;
import com.alibaba.fastjson.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zhuri.coding.common.constants.ArticleConstants;
import com.zhuri.coding.content.event.ArticlePublishEvent;
import com.zhuri.coding.content.mapper.article.ApArticleEventMapper;
import com.zhuri.coding.content.mapper.article.ApArticleMapper;
import com.zhuri.coding.content.service.article.ApArticleService;
import com.zhuri.coding.content.service.outbox.OutboxService;
import com.zhuri.coding.content.service.outbox.handler.ArticlePublishHandler;
import com.zhuri.coding.model.article.dtos.ArticleDto;
import com.zhuri.coding.model.article.dtos.ArticleHomeDto;
import com.zhuri.coding.model.article.pojos.ApArticle;
import com.zhuri.coding.model.article.pojos.ArticleEvent;
import com.zhuri.coding.model.common.dtos.ResponseResult;
import com.zhuri.coding.model.mess.UpdateArticleMess;
import com.zhuri.coding.model.search.vos.SearchArticleVo;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(rollbackFor = Exception.class)
@Slf4j
public class ApArticleServiceImpl extends ServiceImpl<ApArticleMapper, ApArticle> implements ApArticleService {

    @Autowired
    private ApArticleMapper apArticleMapper;

    private final static short MAX_PAGE_SIZE = 50;
    @Autowired
    private ApArticleEventMapper apArticleEventMapper;
    @Autowired
    private ApplicationEventPublisher eventPublisher;

    /** 统一 Outbox（迁移阶段 1：双写对照用；article_event 仍是执行依据） */
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
        // ① 落本地消息表锚点（status=INIT）。顺序保证：锚点先落定，异步置位/同步失败均可被 20s 扫描补偿；
        //    event 落库失败属本地异常，直接返回 false（文章滞留 SUBMIT，error 日志供人工排查）。
        try {
            ArticleEvent event = buildArticleEvent();
            event.setArticleId(articleId);
            SearchArticleVo searchArticleVo = new SearchArticleVo();
            searchArticleVo.setId(articleId);
            event.setParameter(JSONUtil.toJsonStr(searchArticleVo));
            apArticleEventMapper.insertArticleEvent(event);
            log.info("文章本地消息表保存成功，文章id：{}", articleId);

            // 【迁移阶段 1 · 双写】同时写统一 Outbox。
            //   - 幂等：eventKey = "article_publish:{articleId}"，同一文章只保留一条在途事件；
            //   - 阶段 1 中 article_event 仍是执行依据，outbox 只作对照 ——
            //     目的是在真实发布流量下观察新链路行为，确认无误后再进入阶段 2（切读）；
            //   - best-effort：双写失败不影响主流程（老链路才是当前的正确性来源），
            //     仅记 WARN，用于尽早暴露新链路的接入问题。
            try {
                outboxService.record(
                        ArticlePublishHandler.eventKey(articleId),
                        ArticlePublishHandler.EVENT_TYPE,
                        ArticlePublishHandler.payload(articleId));
            } catch (Exception ex) {
                log.warn("Outbox 双写失败（阶段 1 不影响主流程）, articleId={}", articleId, ex);
            }
        } catch (Exception e) {
            log.error("文章本地消息表保存失败", e);
            return false;
        }
        // ② 发布异步执行事件：置位 + ES 同步交由 @Async 监听器（内存构造事件，不依赖本事务提交后的可见性）
        eventPublisher.publishEvent(new ArticlePublishEvent(articleId));
        return true;
    }

    /**
     * 构建文章事件
     */
    private static ArticleEvent buildArticleEvent() {
        ArticleEvent event = new ArticleEvent();
        event.setStatus(ArticleConstants.EVENT_STATUS_INIT);
        event.setMaxRetryCount(ArticleConstants.EVENT_ES_MAX_RETRY);
        event.setRetryCount((byte) 0);
        event.setCreateTime(new Date());
        event.setUpdateTime(new Date());
        return event;
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