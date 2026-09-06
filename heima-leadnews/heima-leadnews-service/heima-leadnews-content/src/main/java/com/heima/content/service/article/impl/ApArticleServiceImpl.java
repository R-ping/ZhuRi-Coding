package com.heima.content.service.article.impl;

import cn.hutool.json.JSONUtil;
import com.alibaba.fastjson.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.heima.common.constants.ArticleConstants;
import com.heima.content.mapper.article.ApArticleEventMapper;
import com.heima.content.mapper.article.ApArticleMapper;
import com.heima.content.service.article.ApArticleService;
import com.heima.content.service.article.ArticleFreemarkerService;
import com.heima.model.article.dtos.ArticleDto;
import com.heima.model.article.dtos.ArticleHomeDto;
import com.heima.model.article.pojos.ApArticle;
import com.heima.model.article.pojos.ApArticle.Status;
import com.heima.model.article.pojos.ArticleEvent;
import com.heima.model.common.dtos.ResponseResult;
import com.heima.model.mess.UpdateArticleMess;
import com.heima.model.search.vos.SearchArticleVo;
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
    @Autowired
    private ArticleFreemarkerService articleFreemarkerService;
    @Autowired
    private ApArticleEventMapper apArticleEventMapper;

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
     * 根据文章id生成文章事件
     * <p>单延迟方案：任务到点消费一次，本地消息表入库后异步同步 ES 并发布事件，
     * 由监听器统一置 DB/ES 发布态并消费任务（不再二次延迟）。
     */
    @Override
    public boolean generateArticleEvent(ApArticle article, Long taskId) {
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
        // ① 先落本地消息表（status=INIT）。顺序保证：即使后续置位/同步失败，记录一定存在可被补偿；
        //    event 落库失败属本地异常，直接返回 false 交由调度标记失败（重试/人工）。
        try {
            ArticleEvent event = buildArticleEvent();
            event.setArticleId(articleId);
            SearchArticleVo searchArticleVo = new SearchArticleVo();
            searchArticleVo.setId(articleId);
            event.setParameter(JSONUtil.toJsonStr(searchArticleVo));
            apArticleEventMapper.insertArticleEvent(event);
            log.info("文章本地消息表保存成功，文章id：{}", articleId);
        } catch (Exception e) {
            log.error("文章本地消息表保存失败", e);
            return false;
        }
        // ② 置 DB 可见态 PUBLISHED：幂等条件更新（仅 SUBMIT→PUBLISHED）
        boolean dbOk = apArticleMapper.markPublishedIfPending(articleId) == 1;
        if (!dbOk) {
            ApArticle latest = getById(articleId);
            byte status = latest == null || latest.getStatus() == null ? -1 : latest.getStatus().byteValue();
            if (status == Status.PUBLISHED.getCode()) {
                dbOk = true; // 已是发布态：并发/重放场景，幂等继续
            } else if (status == Status.SUBMIT.getCode()) {
                // ③ 瞬时抖动本地重试 1 次
                sleepQuietly(500L);
                if (apArticleMapper.markPublishedIfPending(articleId) == 1) {
                    dbOk = true;
                } else {
                    // 仍失败：落 DB_SET_FAIL，由 20s 扫描持续重试置位（幂等自愈，不进死信）
                    updateEventStatus(articleId, ArticleConstants.EVENT_STATUS_DB_SET_FAIL, null);
                    log.warn("文章置发布态失败(本地重试 1 次后仍失败)，落 DB_SET_FAIL 待扫描补偿, articleId={}", articleId);
                }
            } else {
                // 文章处于 FAIL 等不可发布终态：删除事件防滞留，返回 false 由调度标记失败
                log.error("文章状态非可发布态，终止发布流程, articleId={}, status={}", articleId, status);
                apArticleEventMapper.deleteByArticleId(articleId);
                return false;
            }
        }
        // ④ 置位成功 → 同步 ES（内部成功置 DONE / 失败置 ES_SYNC_FAIL，由扫描补偿）
        if (dbOk) {
            articleFreemarkerService.buildHTMLAndSend(article, taskId);
        }
        return true;
    }

    /** 状态机内短暂退避（重试 1 次前的瞬时抖动窗口），不入调用方请求线程 */
    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 更新本地消息表 status（单状态机），retryTime 为 null 表示不修改 */
    private void updateEventStatus(Long articleId, byte status, Date retryTime) {
        try {
            ArticleEvent event = apArticleEventMapper.selectOne(
                Wrappers.<ArticleEvent>lambdaQuery().eq(ArticleEvent::getArticleId, articleId));
            if (event != null) {
                event.setStatus(status);
                if (retryTime != null) {
                    event.setRetryTime(retryTime);
                }
                event.setUpdateTime(new Date());
                apArticleEventMapper.updateArticleEvent(event);
            }
        } catch (Exception e) {
            log.error("更新本地消息表状态失败, articleId={}", articleId, e);
        }
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