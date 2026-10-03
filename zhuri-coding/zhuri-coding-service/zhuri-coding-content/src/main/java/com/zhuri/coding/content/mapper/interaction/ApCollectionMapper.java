package com.zhuri.coding.content.mapper.interaction;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zhuri.coding.model.behavior.pojos.ApCollection;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ApCollectionMapper extends BaseMapper<ApCollection> {

    /**
     * 分页查询收藏某文章的用户ID，按收藏时间由近及远（供更新提醒分批投递）。
     *
     * <p><b>为什么按 created_time 倒序</b>：更新提醒对"最近收藏的读者"最有价值
     * （他大概率还记得这篇文章），大批量投递时也要保证近端优先送达；
     * 依赖索引 {@code idx_article_created(article_id, created_time)} 避免全表扫描。</p>
     *
     * <p>注意：翻页期间新增的收藏可能造成轻微漂移（重复/漏掉个别用户），
     * 重复由调用方 7 天去重键吸收，漏掉只是少一次提醒，可接受。</p>
     *
     * @param articleId 文章ID
     * @param offset    起始偏移
     * @param limit     单批条数
     * @return 用户ID列表（同一用户对同一文章唯一，无需去重）
     */
    @Select("SELECT user_id FROM ap_collection WHERE article_id = #{articleId} "
        + "ORDER BY created_time DESC, id DESC LIMIT #{offset}, #{limit}")
    List<Long> selectUserIdsByArticleId(@Param("articleId") Long articleId,
                                        @Param("offset") int offset,
                                        @Param("limit") int limit);

    /**
     * 删除收藏夹时把其中收藏回退到"默认收藏夹"（folder_id 置空，收藏记录本身不删除）。
     *
     * <p>注意：MyBatis-Plus 全局 update-strategy=not_null，updateById 无法把字段更新为 NULL，
     * 故此处必须用显式 SQL 置空。</p>
     *
     * @param userId   用户ID（限定归属，防止越权）
     * @param folderId 被删除的收藏夹ID
     * @return 回退的收藏条数
     */
    @Update("UPDATE ap_collection SET folder_id = NULL WHERE user_id = #{userId} AND folder_id = #{folderId}")
    int resetFolder(@Param("userId") Integer userId, @Param("folderId") Long folderId);

    /**
     * 收藏列表分页查询（F4：支持按收藏夹筛选 + 标题关键词检索）。
     *
     * <p>筛选语义：{@code folderId != null} 按指定收藏夹过滤；{@code defaultFolder=true} 过滤"默认收藏夹"
     * （folder_id IS NULL，对应前端 folderId=0 约定）；两者互斥，均为空则返回全部收藏。</p>
     *
     * <p>关键词检索走 EXISTS 子查询匹配文章标题（参数化 LIKE，文章软删与否与原列表口径保持一致——
     * 原实现不判断 is_deleted，仅依赖后续批量加载文章时自然跳过缺失项）。</p>
     *
     * @param offset 起始偏移
     * @param limit  单页条数
     */
    @Select("<script>"
        + "SELECT c.id, c.user_id, c.article_id, c.folder_id, c.created_time "
        + "FROM ap_collection c "
        + "WHERE c.user_id = #{userId} "
        + "<if test='folderId != null'>AND c.folder_id = #{folderId} </if>"
        + "<if test='defaultFolder'>AND c.folder_id IS NULL </if>"
        + "<if test='keyword != null'>AND EXISTS (SELECT 1 FROM ap_article a WHERE a.id = c.article_id "
        + "AND a.title LIKE CONCAT('%', #{keyword}, '%')) </if>"
        + "ORDER BY c.created_time DESC, c.id DESC "
        + "LIMIT #{offset}, #{limit}"
        + "</script>")
    List<ApCollection> selectCollectedPage(@Param("userId") Integer userId,
                                           @Param("folderId") Long folderId,
                                           @Param("defaultFolder") boolean defaultFolder,
                                           @Param("keyword") String keyword,
                                           @Param("offset") int offset,
                                           @Param("limit") int limit);

    /**
     * 与 {@link #selectCollectedPage} 同条件的总数统计（手写分页需要配套 count，
     * 不能复用 MyBatis-Plus 的 selectPage 是因为关键词检索需要跨表条件）。
     */
    @Select("<script>"
        + "SELECT COUNT(*) FROM ap_collection c "
        + "WHERE c.user_id = #{userId} "
        + "<if test='folderId != null'>AND c.folder_id = #{folderId} </if>"
        + "<if test='defaultFolder'>AND c.folder_id IS NULL </if>"
        + "<if test='keyword != null'>AND EXISTS (SELECT 1 FROM ap_article a WHERE a.id = c.article_id "
        + "AND a.title LIKE CONCAT('%', #{keyword}, '%')) </if>"
        + "</script>")
    long countCollectedPage(@Param("userId") Integer userId,
                            @Param("folderId") Long folderId,
                            @Param("defaultFolder") boolean defaultFolder,
                            @Param("keyword") String keyword);
}