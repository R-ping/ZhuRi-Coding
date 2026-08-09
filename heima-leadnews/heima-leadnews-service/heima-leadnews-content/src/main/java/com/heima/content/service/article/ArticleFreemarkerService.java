package com.heima.content.service.article;

import com.heima.model.article.pojos.ApArticle;

public interface ArticleFreemarkerService {

    /**
     * 生成静态文件上传到minIO中
     *
     * @param apArticle 文章信息
     * @param content 文章内容，为空时从数据库读取
     * @param taskId 任务ID，用于延迟发布完成后标记任务状态
     * @param lastExecuteInterval 延迟执行间隔（ms），&lt;=0 表示立即发布
     */
    void buildHTMLAndSend(ApArticle apArticle, String content, Long taskId, long lastExecuteInterval);
}