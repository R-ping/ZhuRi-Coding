package com.heima.content.mapper.interaction;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.heima.model.article.dtos.ArticleInteractionCountDTO;
import com.heima.model.behavior.pojos.ApBrowseHistory;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Date;
import java.util.List;

@Mapper
public interface ApBrowseHistoryMapper extends BaseMapper<ApBrowseHistory> {

    /**
     * 统计指定文章集合在 since 之后的近期阅读次数（跨用户聚合）。
     * <p>数据回流闭环的度数来源：真实阅读行为 → 推荐热度信号。</p>
     * @param articleIds 候选文章ID集合（非空）
     * @param since      时间窗口起点
     * @return 各文章的聚合计数
     */
    @Select("<script>" +
            "SELECT article_id AS articleId, COUNT(*) AS cnt FROM ap_browse_history " +
            "WHERE browse_time &gt;= #{since} AND article_id IN " +
            "<foreach collection='articleIds' item='id' open='(' separator=',' close=')'>#{id}</foreach> " +
            "GROUP BY article_id" +
            "</script>")
    List<ArticleInteractionCountDTO> selectRecentInteractionCounts(
            @Param("articleIds") List<Long> articleIds,
            @Param("since") Date since);
}