package com.zhuri.coding.content.mapper.article;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zhuri.coding.model.article.pojos.ArticleEvent;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ApArticleEventMapper extends BaseMapper<ArticleEvent> {


    public void insertArticleEvent(@Param("articleEvent") ArticleEvent articleEvent);

    public void updateArticleEvent(@Param("articleEvent") ArticleEvent articleEvent);

    /**
     * 加载待补偿事件（单 status 状态机）：
     * <ul>
     *   <li>status=1(INIT) 滞留超过 60s：视为消费线程崩溃，扫描重放整段流程</li>
     *   <li>status=2(DB 置位失败)：无条件待重试（幂等自愈，不计 retry_count）</li>
     *   <li>status=3(ES 同步失败)：到达 retry_time 后重试</li>
     * </ul>
     * 已完成(status=4)的行不在此列。
     *
     * <p><b>为什么不再前置过滤 {@code retry_count < max_retry_count}（2026-09-26 修复）</b>：
     * 该条件本意是「已达 ES 重试上限的行不再重试」，但它被加在整条 WHERE 的<b>最外层</b>，
     * 于是同时作用于 INIT 与 DB_SET_FAIL 两个分支 —— 而这两个分支<b>本就不该受计数约束</b>
     * （INIT 是「待重放」、DB_SET_FAIL 是「幂等自愈」，都不累加 retry_count）。
     * 后果：{@code retry_count} 已达上限的 INIT 行<b>既不会被重试、也不会被清理</b>
     * （清理只删 status=4），成为永久滞留的脏数据 —— 实测本地库有 4 条滞留 33~47 天，
     * 且修复前该查询命中 0 条、修复后命中 4 条。
     *
     * <p>修正后：INIT / DB_SET_FAIL 分支不再看计数；ES_SYNC_FAIL 分支保留「到期才重试」，
     * 其超限判死由 {@code ApArticleEventServiceImpl#retryEsSync} 内部完成
     * （于是超限行会被捞出一次、再试失败后删除，而不是静默滞留）。
     */
    @Select("SELECT * FROM article_event WHERE " +
            "(status = 1 AND update_time <= DATE_SUB(NOW(), INTERVAL 60 SECOND)) " +
            "OR status = 2 " +
            "OR (status = 3 AND retry_time IS NOT NULL AND retry_time <= NOW())")
    public List<ArticleEvent> loadUnfinishedEvents();

    /**
     * 删除单条事件（死信/不可发布终态）
     */
    @Delete("DELETE FROM article_event WHERE article_id = #{articleId}")
    public void deleteByArticleId(@Param("articleId") Long articleId);

    /**
     * 清理全部已完成事件（status=4）
     */
    @Delete("DELETE FROM article_event WHERE status = 4")
    public void deleteCompletedEvents();

    // 批量删除
    public void deleteArticleEvent(@Param("articleIds") List<Long> articleIds);
}
