package com.heima.content.mapper.article;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.heima.model.article.pojos.ArticleEvent;
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
     *   <li>status=2(DB 置位失败)：无条件待重试（幂等自愈，不计 retry_count）</li>
     *   <li>status=3(ES 同步失败)：到达 retry_time 后重试</li>
     *   <li>status=1(INIT) 滞留超过 60s：视为消费线程崩溃，扫描重放整段流程</li>
     * </ul>
     * 已完成(status=4)与超过 ES 最大重试次数(max_retry_count)的行不在此列。
     */
    @Select("SELECT * FROM article_event WHERE retry_count < max_retry_count AND ( " +
            "(status = 3 AND retry_time IS NOT NULL AND retry_time <= NOW()) " +
            "OR status = 2 " +
            "OR (status = 1 AND update_time <= DATE_SUB(NOW(), INTERVAL 60 SECOND)) )")
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
