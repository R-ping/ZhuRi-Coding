package com.zhuri.coding.content.mapper.article;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zhuri.coding.model.article.dtos.ArticleHomeDto;
import com.zhuri.coding.model.article.dtos.TagCountDTO;
import com.zhuri.coding.model.article.pojos.ApArticle;
import java.util.Date;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ApArticleMapper extends BaseMapper<ApArticle> {

    /**
     * 幂等置文章为已发布（PUBLISHED=9）：仅当当前处于审核中(SUBMIT=1)才更新，防并发/重放重复置位。
     *
     * @return 受影响行数：1=本次完成置位；0=状态非 SUBMIT（可能已是 PUBLISHED 或其他终态，由调用方查状态区分）
     */
    @Update("UPDATE ap_article SET status = 9 WHERE id = #{articleId} AND status = 1")
    int markPublishedIfPending(@Param("articleId") Long articleId);

    /**
     * 扫描「审核中滞留」的文章 id（服务崩溃 / 重启导致异步审核线程丢失、无人再推进的 SUBMIT 文章）。
     *
     * <p><b>为什么用 updated_time 而不是 created_time</b>：提交审核会把 status 由草稿改为 SUBMIT（一次 UPDATE），
     * 而 {@code updated_time} 列为 {@code ON UPDATE CURRENT_TIMESTAMP}，该次更新会刷新它；审核过程本身
     * 不再写文章表，因此滞留文章的 {@code updated_time} 恰好停留在“提交那一刻”——正是需要的语义。
     * 用 {@code created_time} 会把“草稿放了很久、刚提交”的正常文章误判为滞留。
     *
     * @param before 早于该时间仍处于 SUBMIT 的视为滞留
     * @param limit  单批上限
     * @return 按 updated_time 升序（最早滞留的优先）的文章 id 列表
     */
    @Select("SELECT id FROM ap_article WHERE status = 1 AND is_deleted = 0 AND updated_time < #{before} "
        + "ORDER BY updated_time ASC LIMIT #{limit}")
    List<Long> selectStaleSubmitArticleIds(@Param("before") Date before, @Param("limit") int limit);

    /**
     * 加载文章列表
     * @param dto
     * @param type  1  加载更多   2记载最新
     * @return
     */
    public List<ApArticle> loadArticleList(ArticleHomeDto dto,Short type);

    List<ApArticle> selectRecommendCandidates(@Param("channelId") Integer channelId, @Param("maxCandidates") int maxCandidates, @Param("tagName") String tagName, @Param("windowDays") int windowDays, @Param("excludeIds") List<Long> excludeIds);

    /**
     * 按作者集合查询推荐候选（关注分栏）
     * @param authorIds 关注作者ID集合
     * @param maxCandidates 候选池上限
     * @param windowDays 候选时间窗口（天）
     * @param excludeIds 已读/已展示文章ID集合，null/空 表示不过滤，用于刷新时排除已看内容
     */
    List<ApArticle> selectRecommendCandidatesByAuthors(@Param("authorIds") List<Integer> authorIds, @Param("maxCandidates") int maxCandidates, @Param("windowDays") int windowDays, @Param("excludeIds") List<Long> excludeIds);

    /**
     * 分页查询最新文章（按发布时间倒序，latest 分栏）
     * @param channelId 频道ID，null 表示全站
     * @param tagName 标签名过滤，null/空 表示不过滤
     * @param authorIds 作者ID集合（关注分栏），null/空 表示不过滤
     * @param offset 起始偏移
     * @param limit 每页条数（传 size+1 用于探测是否还有更多）
     */
    List<ApArticle> selectLatestArticles(@Param("channelId") Integer channelId, @Param("tagName") String tagName, @Param("authorIds") List<Integer> authorIds, @Param("offset") int offset, @Param("limit") int limit);

    /**
     * 统计最新分栏（latest）符合条件的文章总数，与 selectLatestArticles 使用同一过滤条件
     * @param channelId 频道ID，null 表示全站
     * @param tagName 标签名过滤，null/空 表示不过滤
     * @param authorIds 作者ID集合（关注分栏），null/空 表示不过滤
     * @return 符合条件的文章总数
     */
    Long countLatestArticles(@Param("channelId") Integer channelId, @Param("tagName") String tagName, @Param("authorIds") List<Integer> authorIds);

    /**
     * 更新文章评论数（原子递增）
     * @param articleId 文章ID
     * @param increment 增量（+1 或 -1）
     */
    void updateCommentCount(@Param("articleId") Long articleId, @Param("increment") int increment);

    /**
     * 原子递增文章互动字段并同步重算热度分。
     * <p>
     * 使用单条 UPDATE（依赖 MySQL 从左到右赋值顺序），避免并发下"读-改-写"造成的计数丢失，
     * 并将原来的 3 次 DB 往返（读+写计数+读+写评分）收敛为 1 次。
     * </p>
     * @param articleId  文章ID
     * @param field      待递增的计数字段，仅允许 likes/views/collection/comment（由调用方白名单限定，防止 SQL 注入）
     * @param increment  增量（+1 或 -1）
     */
    void updateInteractionAndScore(@Param("articleId") Long articleId, @Param("field") String field, @Param("increment") int increment);

    /**
     * 仅按最新互动计数重算热度分（不递增任何计数）。
     * <p>
     * 公式与 {@link #updateInteractionAndScore} 完全一致（likes×3 + views + comment×3 + collection×6），
     * 供"计数已由调用方原子更新、只需重算 score"的场景使用（如行为服务点赞/浏览后），
     * 保证热度分全局只有一套口径，且原子执行无"读-改-写"竞态。
     * </p>
     * @param articleId 文章ID
     */
    void recalculateScore(@Param("articleId") Long articleId);

    /**
     * 查询推荐文章列表（is_recommend=1），需关联 ap_article_config 表
     */
    List<ApArticle> selectRecommendArticles(@Param("excludeId") Long excludeId, @Param("cursor") Long cursor, @Param("size") int size);

    /**
     * 分页查询某个标签（JSON_CONTAINS 匹配 tags 字段）下的已发布文章
     * @param tagName 标签名
     * @param sort 排序方式：hot-热门（热度分）、latest-最新（发布时间）、hottest-最热（点赞+评论）
     * @param offset 起始偏移（从 0 开始）
     * @param size 每页条数
     * @return 文章列表
     */
    List<ApArticle> selectTagArticleList(@Param("tagName") String tagName, @Param("sort") String sort, @Param("offset") int offset, @Param("size") int size);

    /**
     * 统计某个标签（JSON_CONTAINS 匹配 tags 字段）下已发布文章的总数
     * @param tagName 标签名
     * @return 文章总数
     */
    Long countTagArticles(@Param("tagName") String tagName);

    /**
     * 分类文章标签 TopN 聚合：统计某分类（channel_id=categoryId）下已发布文章（status=9）各标签出现次数，按次数降序、标签名升序返回前 size 条
     * @param categoryId 分类ID（频道ID）
     * @param keyword 标签名模糊过滤，null/空 表示不过滤
     * @param size TopN 条数
     * @return 标签名与数量的聚合列表
     */
    List<TagCountDTO> selectTopTagsByCategory(@Param("categoryId") Integer categoryId,
                                              @Param("keyword") String keyword,
                                              @Param("size") int size);

}