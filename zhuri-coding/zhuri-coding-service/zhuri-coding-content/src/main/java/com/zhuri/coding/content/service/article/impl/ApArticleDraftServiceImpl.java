package com.zhuri.coding.content.service.article.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zhuri.coding.common.com.ImageHandle;
import com.zhuri.coding.content.mapper.article.ApArticleConfigMapper;
import com.zhuri.coding.content.mapper.article.ApArticleContentMapper;
import com.zhuri.coding.content.mapper.article.ApArticleDraftMapper;
import com.zhuri.coding.content.mapper.article.ApArticleMapper;
import com.zhuri.coding.content.service.article.ApArticleDraftService;
import com.zhuri.coding.content.service.article.ArticleAutoScanService;
import com.zhuri.coding.content.service.level.LevelPermissionService;
import com.zhuri.coding.content.utils.MarkdownUtils;
import com.zhuri.coding.model.article.pojos.ApArticle;
import com.zhuri.coding.model.article.pojos.ApArticleConfig;
import com.zhuri.coding.model.article.pojos.ApArticleContent;
import com.zhuri.coding.model.article.pojos.ApArticleDraft;
import com.zhuri.coding.model.common.dtos.ResponseResult;
import com.zhuri.coding.model.common.enums.AppHttpCodeEnum;
import com.zhuri.coding.model.user.pojos.ApUser;
import com.zhuri.coding.utils.thread.AppThreadLocalUtil;
import java.util.Date;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
@Slf4j
public class ApArticleDraftServiceImpl extends ServiceImpl<ApArticleDraftMapper, ApArticleDraft> implements ApArticleDraftService {

    @Autowired
    private ApArticleMapper apArticleMapper;

    @Autowired
    private ApArticleConfigMapper apArticleConfigMapper;

    @Autowired
    private ApArticleContentMapper apArticleContentMapper;

    @Autowired
    private ArticleAutoScanService articleAutoScanService;

    @Autowired
    private LevelPermissionService levelPermissionService;

    /** 发布文章所需的权限码 */
    private static final String PERMISSION_PUBLISH_ARTICLE = "can_publish_article";

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ResponseResult createDraft(ApArticleDraft draft) {
        ApUser user = AppThreadLocalUtil.getUser();
        draft.setCreatedTime(new Date());
        draft.setUpdatedTime(new Date());
        draft.setAuthorId(user.getId().longValue());
        save(draft);
        log.info("草稿创建成功, draftId: {}", draft.getId());
        return ResponseResult.okResult(draft);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ResponseResult updateDraft(ApArticleDraft draft) {
        ApUser user = AppThreadLocalUtil.getUser();
        if (draft.getId() == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "草稿ID不能为空");
        }
        ApArticleDraft existing = getById(draft.getId());
        if (existing == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.DATA_NOT_EXIST, "草稿不存在");
        }
        draft.setUpdatedTime(new Date());
        if (draft.getAuthorId() == null) {
            draft.setAuthorId(user.getId().longValue());
        }
        updateById(draft);
        return ResponseResult.okResult(draft);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ResponseResult publishFromDraft(Long draftId) {
        ApUser user = AppThreadLocalUtil.getUser();
        if (user == null || user.getId() == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NEED_LOGIN);
        }
        // 权限硬校验：后端强制检查发布权限（新用户已由 assignBasicPermissions 下发基础权限，正常流程不受影响）
        if (!levelPermissionService.hasPermission(user.getId().longValue(), PERMISSION_PUBLISH_ARTICLE)) {
            log.warn("用户无发布文章权限: userId={}", user.getId());
            return ResponseResult.errorResult(AppHttpCodeEnum.NO_OPERATOR_AUTH, "暂无发布文章权限");
        }
        if (draftId == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "草稿ID不能为空");
        }
        ApArticleDraft draft = getById(draftId);
        if (draft == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.DATA_NOT_EXIST, "草稿不存在");
        }

        // 从草稿直接创建 ap_article 记录
        ApArticle article = getApArticle(draft, user);

        apArticleMapper.insert(article);

        // 保存文章配置
        ApArticleConfig apArticleConfig = new ApArticleConfig(article.getId());
        apArticleConfigMapper.insert(apArticleConfig);

        // 保存文章内容（清洗正文图片URL：去掉?签名参数，保留图片固定位置）
        ApArticleContent apArticleContent = new ApArticleContent();
        apArticleContent.setArticleId(article.getId());
        apArticleContent.setContent(MarkdownUtils.cleanImageUrls(draft.getContent()));
        apArticleContentMapper.insert(apArticleContent);

        log.info("从草稿发布文章成功, draftId: {}, articleId: {}", draftId, article.getId());

        // 删除草稿
        try {
            removeById(draftId);
        } catch (Exception e) {
            log.error("删除草稿失败, draftId: {}", draftId, e);
            throw new RuntimeException("删除草稿失败", e);
        }

        // 事务提交后异步提交审核（避免异步线程在事务未提交时查不到数据）
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                articleAutoScanService.autoScanArticle(article.getId());
                // Step4 内容诚信：AIGC 检测已「并入审核责任链」（AigcDetectProcessor，Order 在向量入库之前），
                // 由链内处理器同步 L1 快检并回填实体；此处不再并发调用，避免"入库判断读到过期实体快照"的时序窗口。
                log.info("文章已提交审核（异步，含 AIGC 诚信检测）, articleId: {}", article.getId());
            }
        });

        return ResponseResult.okResult(article);
    }

    @NotNull
    private static ApArticle getApArticle(ApArticleDraft draft, ApUser user) {
        ApArticle article = new ApArticle();
        article.setTitle(draft.getTitle());
        article.setSummary(draft.getSummary());
        article.setAuthorId(draft.getAuthorId() != null ? draft.getAuthorId() : user.getId().longValue());
        article.setChannelId(draft.getChannelId());
        article.setLayout(draft.getLayout() != null ? draft.getLayout().byteValue() : (byte) 0);
        article.setCoverImage(ImageHandle.handleUrlSuffix(draft.getCoverImage()));
        article.setTags(draft.getTags());
        article.setCreatedTime(new Date());
        // 获取发布时间，如果为null（不延迟时）则与创建时间相等
        article.setPublishTime(draft.getPublishTime() != null ? draft.getPublishTime() : new Date());
        article.setStatus(ApArticle.Status.SUBMIT.getCode()); // 审核中

        // 初始化统计字段默认值
        article.setViews(0);
        article.setLikes(0);
        article.setCollection(0);
        article.setComment(0);
        article.setScore(0);

        // 查询作者信息
        ApUser apUser = AppThreadLocalUtil.getUser();
        if (apUser != null) {
            article.setAuthorName(apUser.getNickname() != null ? apUser.getNickname() : "");
            article.setAuthorImage(apUser.getImage() != null ? apUser.getImage() : "");
        }
        return article;
    }

    @Override
    public ResponseResult getDraftById(Long id) {
        ApArticleDraft draft = getById(id);
        if (draft == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.DATA_NOT_EXIST);
        }
        return ResponseResult.okResult(draft);
    }

    @Override
    public ResponseResult listDrafts(Long authorId, Integer page, Integer size) {
        Page<ApArticleDraft> pageParam = new Page<>(page, size);
        LambdaQueryWrapper<ApArticleDraft> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(authorId != null, ApArticleDraft::getAuthorId, authorId);
        queryWrapper.orderByDesc(ApArticleDraft::getUpdatedTime);
        IPage<ApArticleDraft> result = page(pageParam, queryWrapper);
        return ResponseResult.okResult(result);
    }

    @Override
    public ResponseResult deleteDraft(Long id) {
        if (id == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID);
        }
        // 归属校验：仅允许删除本人草稿，防止越权删除他人草稿
        // 注意：authorId 为 Long，user.getId() 为 Integer，不能用 equals（Long.equals(Integer) 恒 false），须统一为 long 再比
        ApUser user = AppThreadLocalUtil.getUser();
        if (user == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NEED_LOGIN);
        }
        ApArticleDraft draft = getById(id);
        if (draft == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.DATA_NOT_EXIST, "草稿不存在");
        }
        if (draft.getAuthorId() == null || user.getId() == null
                || draft.getAuthorId().longValue() != user.getId().longValue()) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NO_OPERATOR_AUTH, "无权删除该草稿");
        }
        removeById(id);
        return ResponseResult.okResult();
    }
}