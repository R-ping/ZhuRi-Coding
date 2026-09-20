package com.zhuri.coding.content.mapper.article;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zhuri.coding.model.article.pojos.ApArticleConfig;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ApArticleConfigMapper extends BaseMapper<ApArticleConfig> {

    /**
     * 按 article_id 幂等写入推荐状态（INSERT ... ON DUPLICATE KEY UPDATE）。
     * <p>
     * 依赖 uk_article_id 唯一索引兜底：并发首次创建配置时不会因"先查后插"产生重复行，
     * 已存在时仅更新 is_recommend，其余字段（可评论/转发/上下架等）保持不变。
     * </p>
     * @param config 至少需包含 articleId 与 isRecommend
     * @return 1=插入，2=更新，0=未变化
     */
    @Insert("INSERT INTO ap_article_config (article_id, is_comment, is_forward, is_down, is_delete, is_recommend) " +
            "VALUES (#{articleId}, #{isComment}, #{isForward}, #{isDown}, #{isDelete}, #{isRecommend}) " +
            "ON DUPLICATE KEY UPDATE is_recommend = VALUES(is_recommend)")
    int insertOrUpdateRecommend(ApArticleConfig config);
}