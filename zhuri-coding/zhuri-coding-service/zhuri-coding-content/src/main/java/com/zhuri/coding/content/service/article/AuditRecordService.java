package com.zhuri.coding.content.service.article;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.zhuri.coding.common.constants.ArticleConstants;
import com.zhuri.coding.content.mapper.article.ApArticleAuditRecordMapper;
import com.zhuri.coding.content.mapper.article.ApArticleContentMapper;
import com.zhuri.coding.model.article.pojos.ApArticle;
import com.zhuri.coding.model.article.pojos.ApArticleAuditRecord;
import com.zhuri.coding.model.article.pojos.ApArticleContent;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 审核记录服务：写入文章审核轨迹（通过/失败共用），保证审计完整。
 * <p>
 * 审核失败由 {@code AuditFailProcessor} 调用 record(status=FAIL)，
 * 审核通过由 {@code ArticleAutoScanServiceImpl} 调用 record(status=PASS)。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuditRecordService {

    private final ApArticleAuditRecordMapper auditRecordMapper;
    private final ApArticleContentMapper apArticleContentMapper;

    /**
     * 写入一条审核记录（幂等性由调用方保证，本方法仅落库；失败不影响主流程）
     *
     * @param article 文章实体（id 必填）
     * @param content 文章内容（为空时内部兜底查询）
     * @param status  审核状态（{@link ArticleConstants#AUDIT_STATUS_PASS} / {@link ArticleConstants#AUDIT_STATUS_FAIL}）
     * @param reason  审核结果说明（失败原因或"审核通过"）
     */
    public void record(ApArticle article, String content, int status, String reason) {
        if (article == null || article.getId() == null) {
            return;
        }
        try {
            ApArticleAuditRecord record = new ApArticleAuditRecord();
            record.setArticleId(article.getId());
            record.setAuthorId(article.getAuthorId());
            record.setTitle(article.getTitle() != null ? article.getTitle() : "");
            record.setContent(content != null ? content : fetchContent(article.getId()));
            record.setReason(reason != null ? reason : "");
            record.setAuditType("text");
            record.setStatus(status);
            record.setCreatedAt(LocalDateTime.now());
            auditRecordMapper.insert(record);
            log.info("审核记录已写入审计表, articleId={}, status={}, reason={}", article.getId(), status, reason);
        } catch (Exception e) {
            log.error("写入审计记录失败, articleId={}", article.getId(), e);
        }
    }

    /** 兜底查询文章内容（失败返回空串，不阻断审计落库） */
    private String fetchContent(Long articleId) {
        try {
            QueryWrapper<ApArticleContent> query = new QueryWrapper<>();
            query.eq("article_id", articleId);
            ApArticleContent content = apArticleContentMapper.selectOne(query);
            return content != null ? content.getContent() : "";
        } catch (Exception e) {
            log.warn("查询文章内容失败, articleId={}", articleId, e);
            return "";
        }
    }
}
