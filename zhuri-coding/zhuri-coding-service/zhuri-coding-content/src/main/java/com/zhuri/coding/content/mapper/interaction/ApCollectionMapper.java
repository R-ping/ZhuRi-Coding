package com.zhuri.coding.content.mapper.interaction;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zhuri.coding.model.behavior.pojos.ApCollection;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

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
}