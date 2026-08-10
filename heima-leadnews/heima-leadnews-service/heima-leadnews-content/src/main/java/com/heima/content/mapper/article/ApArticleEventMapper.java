package com.heima.content.mapper.article;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.heima.model.article.pojos.ArticleEvent;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ApArticleEventMapper extends BaseMapper<ArticleEvent> {


    public void insertArticleEvent(@Param("articleEvent") ArticleEvent articleEvent);

    public void updateArticleEvent(@Param("articleEvent") ArticleEvent articleEvent);

    @Select("select * from article_event where retry_count < max_retry_count")
    public List<ArticleEvent> loadArticleEvent();

    // 批量删除
    public void deleteArticleEvent(@Param("articleIds") List<Long> articleIds);
}