package com.heima.content.mapper.interaction;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.heima.model.article.dtos.ArticleInteractionCountDTO;
import com.heima.model.behavior.pojos.ApArticleExposure;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Date;
import java.util.List;

@Mapper
public interface ApArticleExposureMapper extends BaseMapper<ApArticleExposure> {

    /**
     * 批量写入曝光记录（推荐分发后记录曝光，最佳努力，失败不影响主流程）。
     * @param list 曝光明细集合（非空；size 受单页大小上限控制，≤ MAX_SIZE）
     * @return 受影响行数
     */
    @Select("<script>" +
            "INSERT INTO ap_article_exposure " +
            "(user_id, article_id, channel, sub_tab, page, position, seed, create_time) VALUES " +
            "<foreach collection='list' item='e' separator=','>" +
            "(#{e.userId}, #{e.articleId}, #{e.channel}, #{e.subTab}, #{e.page}, " +
            "#{e.position}, #{e.seed}, #{e.createTime})" +
            "</foreach>" +
            "</script>")
    int insertBatch(@Param("list") List<ApArticleExposure> list);

    /**
     * 统计指定用户在 since 之后、对指定候选文章集合的曝光次数。
     * <p>负反馈闭环：曝光次数越高而未被消费，降权越显著。</p>
     * @param userId     用户ID
     * @param articleIds 候选文章ID集合（非空）
     * @param since      时间窗口起点
     * @return 各文章的曝光计数
     */
    @Select("<script>" +
            "SELECT article_id AS articleId, COUNT(*) AS cnt FROM ap_article_exposure " +
            "WHERE user_id = #{userId} AND create_time &gt;= #{since} AND article_id IN " +
            "<foreach collection='articleIds' item='id' open='(' separator=',' close=')'>#{id}</foreach> " +
            "GROUP BY article_id" +
            "</script>")
    List<ArticleInteractionCountDTO> selectUserExposureCounts(
            @Param("userId") Long userId,
            @Param("articleIds") List<Long> articleIds,
            @Param("since") Date since);
}