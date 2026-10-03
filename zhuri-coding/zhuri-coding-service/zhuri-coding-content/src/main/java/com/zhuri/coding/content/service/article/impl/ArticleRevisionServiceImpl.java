package com.zhuri.coding.content.service.article.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.zhuri.coding.content.mapper.article.ApArticleContentMapper;
import com.zhuri.coding.content.mapper.article.ApArticleDraftMapper;
import com.zhuri.coding.content.mapper.article.ApArticleMapper;
import com.zhuri.coding.content.service.article.ArticleAutoScanService;
import com.zhuri.coding.content.service.article.ArticlePublishExecutor;
import com.zhuri.coding.content.service.article.ArticleRevisionService;
import com.zhuri.coding.content.service.article.ArticleUpdateNotifyService;
import com.zhuri.coding.content.utils.ArticleRevisionDiff;
import com.zhuri.coding.content.utils.MarkdownUtils;
import com.zhuri.coding.model.article.pojos.ApArticle;
import com.zhuri.coding.model.article.pojos.ApArticleContent;
import com.zhuri.coding.model.article.pojos.ApArticleDraft;
import com.zhuri.coding.model.common.dtos.ResponseResult;
import com.zhuri.coding.model.common.enums.AppHttpCodeEnum;
import com.zhuri.coding.model.user.pojos.ApUser;
import com.zhuri.coding.utils.thread.AppThreadLocalUtil;
import java.util.ArrayList;
import java.util.Date;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 已发布文章修订（编辑）服务实现
 *
 * <p>落地「审核期线上继续展示旧内容」：修订草稿独立存于 ap_article_draft（source_article_id 非空），
 * 文章表仅以 pending_revision_id 指向待审草稿，正文在审核通过前不改动，因此文章不会下线。</p>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ArticleRevisionServiceImpl implements ArticleRevisionService {

    private final ApArticleMapper apArticleMapper;
    private final ApArticleContentMapper apArticleContentMapper;
    private final ApArticleDraftMapper apArticleDraftMapper;
    private final ArticleAutoScanService articleAutoScanService;
    private final ArticlePublishExecutor articlePublishExecutor;
    private final ArticleUpdateNotifyService articleUpdateNotifyService;

    /** 实质更新阈值：新旧正文差异率（4-gram Jaccard）达到该值即视为实质更新 */
    @Value("${app.article.revision.significant-threshold:0.15}")
    private double significantThreshold;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ResponseResult createOrUpdateRevision(ApArticleDraft revision) {
        // ===== 修订草稿保存入口：校验登录/文章/作者，落库并挂到文章的待审指针 =====
        if (revision == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "修订内容不能为空");
        }
        ApUser user = AppThreadLocalUtil.getUser();
        if (user == null || user.getId() == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NEED_LOGIN);
        }
        // 目标文章ID：优先取 sourceArticleId（修订来源），兼容前端只传 articleId 的情况
        Long articleId = revision.getSourceArticleId() != null ? revision.getSourceArticleId() : revision.getArticleId();
        if (articleId == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "文章ID不能为空");
        }
        ApArticle article = apArticleMapper.selectById(articleId);
        if (article == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.DATA_NOT_EXIST, "文章不存在");
        }
        if (article.getStatus() == null || !article.getStatus().equals(ApArticle.Status.PUBLISHED.getCode())) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "仅已发布文章可提交修订");
        }
        long userId = user.getId().longValue();
        if (article.getAuthorId() == null || article.getAuthorId() != userId) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NO_OPERATOR_AUTH, "无权修订该文章");
        }
        if (revision.getContent() == null || revision.getContent().isBlank()) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "修订正文不能为空");
        }

        Date now = new Date();
        ApArticleDraft target = null;
        if (article.getPendingRevisionId() != null) {
            target = apArticleDraftMapper.selectById(article.getPendingRevisionId());
        }

        if (target != null) {
            // 已有待审修订：就地更新同一份草稿，避免重复挂载
            target.setTitle(revision.getTitle());
            target.setSummary(revision.getSummary());
            target.setContent(revision.getContent());
            target.setTags(revision.getTags());
            target.setCoverImage(revision.getCoverImage());
            target.setLayout(revision.getLayout());
            target.setUpdateNote(revision.getUpdateNote());
            target.setUpdatedTime(now);
            apArticleDraftMapper.updateById(target);
        } else {
            // 首次修订：新建修订草稿，并把草稿ID写入文章的待审指针
            ApArticleDraft draft = new ApArticleDraft();
            draft.setSourceArticleId(articleId);
            draft.setAuthorId(article.getAuthorId());
            draft.setChannelId(article.getChannelId());
            draft.setTitle(revision.getTitle());
            draft.setSummary(revision.getSummary());
            draft.setContent(revision.getContent());
            draft.setTags(revision.getTags());
            draft.setCoverImage(revision.getCoverImage());
            draft.setLayout(revision.getLayout());
            draft.setUpdateNote(revision.getUpdateNote());
            draft.setStatus(ApArticle.Status.DRAFT.getCode());
            draft.setIsDeleted(false);
            draft.setCreatedTime(now);
            draft.setUpdatedTime(now);
            apArticleDraftMapper.insert(draft);
            target = draft;

            apArticleMapper.update(null, new LambdaUpdateWrapper<ApArticle>()
                    .eq(ApArticle::getId, articleId)
                    .set(ApArticle::getPendingRevisionId, draft.getId()));
        }

        log.info("修订草稿已保存, articleId={}, draftId={}", articleId, target.getId());
        return ResponseResult.okResult(target);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ResponseResult submitRevision(Long articleId) {
        // ===== 提交修订审核：计算实质更新标记并落库，事务提交后异步触发修订审核 =====
        if (articleId == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "文章ID不能为空");
        }
        ApUser user = AppThreadLocalUtil.getUser();
        if (user == null || user.getId() == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NEED_LOGIN);
        }
        ApArticle article = apArticleMapper.selectById(articleId);
        if (article == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.DATA_NOT_EXIST, "文章不存在");
        }
        long userId = user.getId().longValue();
        if (article.getAuthorId() == null || article.getAuthorId() != userId) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NO_OPERATOR_AUTH, "无权修订该文章");
        }
        if (article.getStatus() == null || !article.getStatus().equals(ApArticle.Status.PUBLISHED.getCode())) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "仅已发布文章可提交修订");
        }
        if (article.getPendingRevisionId() == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.DATA_NOT_EXIST, "没有待提交的修订草稿");
        }
        ApArticleDraft draft = apArticleDraftMapper.selectById(article.getPendingRevisionId());
        if (draft == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.DATA_NOT_EXIST, "修订草稿不存在");
        }
        if (draft.getContent() == null || draft.getContent().isBlank()) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "修订正文不能为空");
        }

        // 计算实质更新：新旧正文差异率 ≥ 阈值即视为实质更新（达阈值才在生效时刷新更新时间）
        double ratio = ArticleRevisionDiff.diffRatio(loadArticleContent(articleId), draft.getContent());
        boolean significant = ratio >= significantThreshold;
        draft.setRevisionSignificant(significant ? 1 : 0);
        draft.setUpdatedTime(new Date());
        apArticleDraftMapper.updateById(draft);
        log.info("修订草稿已提交审核, articleId={}, draftId={}, diffRatio={}, significant={}",
                articleId, draft.getId(), ratio, significant);

        // 事务提交后异步提交修订审核（避免异步线程在事务未提交时读不到最新草稿/指针）
        final Long targetArticleId = articleId;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                articleAutoScanService.autoScanRevision(targetArticleId);
                log.info("修订已提交审核（异步）, articleId={}", targetArticleId);
            }
        });
        return ResponseResult.okResult(draft);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void applyRevision(Long articleId) {
        // ===== 修订生效：用修订正文覆盖线上正文，必要时刷新实质更新时间 =====
        if (articleId == null) {
            return;
        }
        ApArticle article = apArticleMapper.selectById(articleId);
        if (article == null || article.getPendingRevisionId() == null) {
            log.warn("应用修订时文章不存在或无待审修订, articleId={}", articleId);
            return;
        }
        ApArticleDraft draft = apArticleDraftMapper.selectById(article.getPendingRevisionId());
        if (draft == null) {
            log.warn("应用修订时待审草稿不存在，仅清理待审指针, articleId={}", articleId);
            clearPendingRevision(articleId);
            return;
        }

        // ① 覆盖正文（清洗图片URL签名参数，与草稿发布链路保持一致）
        String newContent = MarkdownUtils.cleanImageUrls(draft.getContent());
        LambdaQueryWrapper<ApArticleContent> contentQuery = new LambdaQueryWrapper<>();
        contentQuery.eq(ApArticleContent::getArticleId, articleId);
        ApArticleContent contentRow = apArticleContentMapper.selectOne(contentQuery);
        if (contentRow != null) {
            contentRow.setContent(newContent);
            apArticleContentMapper.updateById(contentRow);
        } else {
            ApArticleContent created = new ApArticleContent();
            created.setArticleId(articleId);
            created.setContent(newContent);
            apArticleContentMapper.insert(created);
        }

        // ② 草稿中有值的字段覆盖到文章（用实体更新以复用 JSON/TypeHandler 映射）
        boolean significant = draft.getRevisionSignificant() != null && draft.getRevisionSignificant() == 1;
        ApArticle patch = new ApArticle();
        patch.setId(articleId);
        boolean hasFields = false;
        if (draft.getTitle() != null) {
            patch.setTitle(draft.getTitle());
            hasFields = true;
        }
        if (draft.getSummary() != null) {
            patch.setSummary(draft.getSummary());
            hasFields = true;
        }
        if (draft.getTags() != null) {
            patch.setTags(draft.getTags());
            hasFields = true;
        }
        if (draft.getCoverImage() != null) {
            patch.setCoverImage(draft.getCoverImage());
            hasFields = true;
        }
        if (draft.getLayout() != null) {
            patch.setLayout(draft.getLayout().byteValue());
            hasFields = true;
        }
        // ③ 达实质更新阈值：刷新最后更新时间与更新说明（供详情页时效印章）
        Date updateTime = null;
        if (significant) {
            updateTime = new Date();
            patch.setUpdateTime(updateTime);
            hasFields = true;
            if (draft.getUpdateNote() != null) {
                patch.setUpdateNote(draft.getUpdateNote());
            }
        }
        if (hasFields) {
            apArticleMapper.updateById(patch);
        }

        // ④ 清空待审指针 + 删除草稿
        clearPendingRevision(articleId);
        apArticleDraftMapper.deleteById(draft.getId());

        // ⑤ 实质更新：登记「收藏者更新提醒」事件（本地消息表锚点，事务提交后异步投递，
        //     失败走既有重试/死信链路，不影响修订生效）。幂等键含更新时间，后续的合法再更新不会被误判为重复。
        if (significant && updateTime != null) {
            articleUpdateNotifyService.notifyCollectors(articleId, updateTime.getTime());
        }

        // ⑥ 同步 ES：事务提交后执行，避免 search 端反向拉取到未提交的旧正文
        syncToEsAfterCommit(articleId);
        log.info("修订已生效, articleId={}, draftId={}, significant={}", articleId, draft.getId(), significant);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void rejectRevision(Long articleId) {
        // ===== 修订驳回：仅放弃本次修订，线上正文与实质更新时间保持不变 =====
        if (articleId == null) {
            return;
        }
        ApArticle article = apArticleMapper.selectById(articleId);
        if (article == null || article.getPendingRevisionId() == null) {
            return;
        }
        Long draftId = article.getPendingRevisionId();
        clearPendingRevision(articleId);
        apArticleDraftMapper.deleteById(draftId);
        log.info("修订已驳回并放弃, articleId={}, draftId={}", articleId, draftId);
    }

    @Override
    public ResponseResult getPendingRevision(Long articleId) {
        // ===== 查询待审修订草稿 =====
        if (articleId == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "文章ID不能为空");
        }
        ApArticle article = apArticleMapper.selectById(articleId);
        if (article == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.DATA_NOT_EXIST, "文章不存在");
        }
        if (article.getPendingRevisionId() == null) {
            return ResponseResult.okResult(emptyDraft());
        }
        ApArticleDraft draft = apArticleDraftMapper.selectById(article.getPendingRevisionId());
        return ResponseResult.okResult(draft != null ? draft : emptyDraft());
    }

    /** 清空文章的待审修订指针（显式置 null，绕过 not_null 更新策略） */
    private void clearPendingRevision(Long articleId) {
        apArticleMapper.update(null, new LambdaUpdateWrapper<ApArticle>()
                .eq(ApArticle::getId, articleId)
                .set(ApArticle::getPendingRevisionId, null));
    }

    /** 事务提交后同步 ES；无活动事务时直接同步 */
    private void syncToEsAfterCommit(Long articleId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    articlePublishExecutor.syncToEs(articleId);
                }
            });
        } else {
            articlePublishExecutor.syncToEs(articleId);
        }
    }

    /** 读取文章线上正文，不存在时返回空串 */
    private String loadArticleContent(Long articleId) {
        LambdaQueryWrapper<ApArticleContent> query = new LambdaQueryWrapper<>();
        query.eq(ApArticleContent::getArticleId, articleId);
        ApArticleContent content = apArticleContentMapper.selectOne(query);
        return content != null && content.getContent() != null ? content.getContent() : "";
    }

    /** 无待审修订时返回的空草稿（对外字段不为 null） */
    private ApArticleDraft emptyDraft() {
        ApArticleDraft empty = new ApArticleDraft();
        empty.setTitle("");
        empty.setSummary("");
        empty.setContent("");
        empty.setCoverImage("");
        empty.setUpdateNote("");
        empty.setTags(new ArrayList<>());
        return empty;
    }
}