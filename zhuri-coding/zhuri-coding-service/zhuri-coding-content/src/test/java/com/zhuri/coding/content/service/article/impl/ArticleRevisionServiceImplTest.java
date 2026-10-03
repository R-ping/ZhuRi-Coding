package com.zhuri.coding.content.service.article.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.zhuri.coding.content.mapper.article.ApArticleContentMapper;
import com.zhuri.coding.content.mapper.article.ApArticleDraftMapper;
import com.zhuri.coding.content.mapper.article.ApArticleMapper;
import com.zhuri.coding.content.service.article.ArticleAutoScanService;
import com.zhuri.coding.content.service.article.ArticlePublishExecutor;
import com.zhuri.coding.content.service.article.ArticleUpdateNotifyService;
import com.zhuri.coding.model.article.pojos.ApArticle;
import com.zhuri.coding.model.article.pojos.ApArticleContent;
import com.zhuri.coding.model.article.pojos.ApArticleDraft;
import com.zhuri.coding.model.common.dtos.ResponseResult;
import com.zhuri.coding.model.common.enums.AppHttpCodeEnum;
import com.zhuri.coding.model.user.pojos.ApUser;
import com.zhuri.coding.utils.thread.AppThreadLocalUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * ArticleRevisionServiceImpl 单元测试
 *
 * 覆盖：修订草稿保存的登录/文章状态/作者越权校验，以及提交审核时"实质更新"阈值判定的两条分支。
 */
@ExtendWith(MockitoExtension.class)
class ArticleRevisionServiceImplTest {

    @Mock private ApArticleMapper apArticleMapper;
    @Mock private ApArticleContentMapper apArticleContentMapper;
    @Mock private ApArticleDraftMapper apArticleDraftMapper;
    @Mock private ArticleAutoScanService articleAutoScanService;
    @Mock private ArticlePublishExecutor articlePublishExecutor;
    @Mock private ArticleUpdateNotifyService articleUpdateNotifyService;

    @InjectMocks
    private ArticleRevisionServiceImpl revisionService;

    private static final Long ARTICLE_ID = 100L;
    private static final Long AUTHOR_ID = 5L;
    private static final Long DRAFT_ID = 77L;

    @BeforeEach
    void setUp() {
        // @Value 字段在单测中不会注入，显式设置阈值与生产默认值一致
        ReflectionTestUtils.setField(revisionService, "significantThreshold", 0.15);
        // 单元测试无 Spring 容器，需手动初始化 TableInfo，LambdaUpdateWrapper 才能解析列名
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), ApArticle.class);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), ApArticleDraft.class);
    }

    @AfterEach
    void tearDown() {
        AppThreadLocalUtil.clear();
    }

    private ApUser user(Integer id) {
        ApUser u = new ApUser();
        u.setId(id);
        u.setNickname("作者");
        u.setImage("avatar.png");
        return u;
    }

    private ApArticle article(Long authorId, byte status, Long pendingRevisionId) {
        ApArticle a = new ApArticle();
        a.setId(ARTICLE_ID);
        a.setAuthorId(authorId);
        a.setChannelId(1);
        a.setTitle("原标题");
        a.setStatus(status);
        a.setPendingRevisionId(pendingRevisionId);
        return a;
    }

    // ==================== createOrUpdateRevision ====================

    @Test
    @DisplayName("createOrUpdateRevision - 未登录返回 NEED_LOGIN")
    void testCreateNeedLogin() {
        assertEquals(AppHttpCodeEnum.NEED_LOGIN.getCode(),
                revisionService.createOrUpdateRevision(new ApArticleDraft()).getCode());
    }

    @Test
    @DisplayName("createOrUpdateRevision - 非已发布文章返回 PARAM_INVALID")
    void testCreateNotPublished() {
        AppThreadLocalUtil.setUser(user(AUTHOR_ID.intValue()));
        when(apArticleMapper.selectById(ARTICLE_ID))
                .thenReturn(article(AUTHOR_ID, ApArticle.Status.DRAFT.getCode(), null));
        ApArticleDraft rev = new ApArticleDraft();
        rev.setSourceArticleId(ARTICLE_ID);
        rev.setContent("新正文");
        assertEquals(AppHttpCodeEnum.PARAM_INVALID.getCode(),
                revisionService.createOrUpdateRevision(rev).getCode());
    }

    @Test
    @DisplayName("createOrUpdateRevision - 非作者越权返回 NO_OPERATOR_AUTH")
    void testCreateNoAuth() {
        AppThreadLocalUtil.setUser(user(AUTHOR_ID.intValue()));
        when(apArticleMapper.selectById(ARTICLE_ID))
                .thenReturn(article(99L, ApArticle.Status.PUBLISHED.getCode(), null));
        ApArticleDraft rev = new ApArticleDraft();
        rev.setSourceArticleId(ARTICLE_ID);
        rev.setContent("新正文");
        assertEquals(AppHttpCodeEnum.NO_OPERATOR_AUTH.getCode(),
                revisionService.createOrUpdateRevision(rev).getCode());
    }

    @Test
    @DisplayName("createOrUpdateRevision - 首次修订成功创建草稿")
    void testCreateFirstRevisionOk() {
        AppThreadLocalUtil.setUser(user(AUTHOR_ID.intValue()));
        when(apArticleMapper.selectById(ARTICLE_ID))
                .thenReturn(article(AUTHOR_ID, ApArticle.Status.PUBLISHED.getCode(), null));
        Mockito.doAnswer(inv -> {
            ((ApArticleDraft) inv.getArgument(0)).setId(DRAFT_ID);
            return 1;
        }).when(apArticleDraftMapper).insert(any(ApArticleDraft.class));

        ApArticleDraft rev = new ApArticleDraft();
        rev.setSourceArticleId(ARTICLE_ID);
        rev.setTitle("新标题");
        rev.setContent("新正文");
        ResponseResult r = revisionService.createOrUpdateRevision(rev);

        assertEquals(200, r.getCode());
        ApArticleDraft saved = (ApArticleDraft) r.getData();
        assertEquals(DRAFT_ID, saved.getId());
        assertEquals(ARTICLE_ID, saved.getSourceArticleId());
        assertNotNull(saved.getCreatedTime());
        verify(apArticleDraftMapper).insert(any(ApArticleDraft.class));
    }

    // ==================== submitRevision ====================

    @Test
    @DisplayName("submitRevision - 无待审修订返回 DATA_NOT_EXIST")
    void testSubmitNoPending() {
        AppThreadLocalUtil.setUser(user(AUTHOR_ID.intValue()));
        when(apArticleMapper.selectById(ARTICLE_ID))
                .thenReturn(article(AUTHOR_ID, ApArticle.Status.PUBLISHED.getCode(), null));
        assertEquals(AppHttpCodeEnum.DATA_NOT_EXIST.getCode(),
                revisionService.submitRevision(ARTICLE_ID).getCode());
    }

    @Test
    @DisplayName("submitRevision - 改动幅度达阈值 → revision_significant=1")
    void testSubmitSignificant() {
        AppThreadLocalUtil.setUser(user(AUTHOR_ID.intValue()));
        when(apArticleMapper.selectById(ARTICLE_ID))
                .thenReturn(article(AUTHOR_ID, ApArticle.Status.PUBLISHED.getCode(), DRAFT_ID));
        ApArticleDraft draft = new ApArticleDraft();
        draft.setId(DRAFT_ID);
        draft.setContent("这是一段与旧正文完全不同的新内容，覆盖了全新的主题与论述。");
        when(apArticleDraftMapper.selectById(DRAFT_ID)).thenReturn(draft);
        ApArticleContent oldContent = new ApArticleContent();
        oldContent.setContent("alpha beta gamma delta epsilon zeta eta theta");
        when(apArticleContentMapper.selectOne(any())).thenReturn(oldContent);

        assertSubmitUpdatesFlag(draft, 1);
    }

    @Test
    @DisplayName("submitRevision - 改动幅度低于阈值（改错别字）→ revision_significant=0")
    void testSubmitNotSignificant() {
        AppThreadLocalUtil.setUser(user(AUTHOR_ID.intValue()));
        when(apArticleMapper.selectById(ARTICLE_ID))
                .thenReturn(article(AUTHOR_ID, ApArticle.Status.PUBLISHED.getCode(), DRAFT_ID));
        ApArticleDraft draft = new ApArticleDraft();
        draft.setId(DRAFT_ID);
        draft.setContent("完全一致的正文内容，仅修正了一个错别字");
        when(apArticleDraftMapper.selectById(DRAFT_ID)).thenReturn(draft);
        ApArticleContent oldContent = new ApArticleContent();
        oldContent.setContent("完全一致的正文内容，仅修正了一个错别字");
        when(apArticleContentMapper.selectOne(any())).thenReturn(oldContent);

        assertSubmitUpdatesFlag(draft, 0);
    }

    /** 提交审核并断言实质更新标记；同时验证事务提交后异步触发修订审核 */
    private void assertSubmitUpdatesFlag(ApArticleDraft draft, int expectedFlag) {
        ArgumentCaptor<TransactionSynchronization> captor =
                ArgumentCaptor.forClass(TransactionSynchronization.class);
        try (MockedStatic<TransactionSynchronizationManager> ts =
                Mockito.mockStatic(TransactionSynchronizationManager.class)) {
            ResponseResult r = revisionService.submitRevision(ARTICLE_ID);
            assertEquals(200, r.getCode());
            assertEquals(expectedFlag, draft.getRevisionSignificant());
            verify(apArticleDraftMapper).updateById(draft);

            ts.verify(() -> TransactionSynchronizationManager.registerSynchronization(captor.capture()));
            captor.getValue().afterCommit();
            verify(articleAutoScanService).autoScanRevision(ARTICLE_ID);
        }
    }

    // ==================== applyRevision（实质更新 → 收藏者提醒） ====================

    private ApArticleDraft significantDraft() {
        ApArticleDraft draft = new ApArticleDraft();
        draft.setId(DRAFT_ID);
        draft.setTitle("新标题");
        draft.setContent("重写后的正文内容");
        draft.setRevisionSignificant(1);
        draft.setUpdateNote("适配 Spring Boot 3.5");
        return draft;
    }

    private void stubApplyContext(ApArticleDraft draft) {
        when(apArticleMapper.selectById(ARTICLE_ID))
                .thenReturn(article(AUTHOR_ID, ApArticle.Status.PUBLISHED.getCode(), DRAFT_ID));
        when(apArticleDraftMapper.selectById(DRAFT_ID)).thenReturn(draft);
        // 线上正文不存在 → 走 insert 分支，不与覆盖分支耦合
        when(apArticleContentMapper.selectOne(any())).thenReturn(null);
    }

    @Test
    @DisplayName("applyRevision - 实质更新：刷新 update_time 并登记收藏者提醒（幂等键含更新时间）")
    void testApplyRevisionNotifiesCollectors() {
        ApArticleDraft draft = significantDraft();
        stubApplyContext(draft);

        revisionService.applyRevision(ARTICLE_ID);

        ArgumentCaptor<ApArticle> patch = ArgumentCaptor.forClass(ApArticle.class);
        verify(apArticleMapper).updateById(patch.capture());
        assertNotNull(patch.getValue().getUpdateTime());
        assertEquals("适配 Spring Boot 3.5", patch.getValue().getUpdateNote());
        // 提醒事件登记：携带本次更新时间，后续合法再更新不会被幂等键误判为重复
        verify(articleUpdateNotifyService).notifyCollectors(eq(ARTICLE_ID), anyLong());
    }

    @Test
    @DisplayName("applyRevision - 非实质更新（改错别字）：只覆盖正文，不发提醒")
    void testApplyRevisionNotSignificantNoNotify() {
        ApArticleDraft draft = significantDraft();
        draft.setRevisionSignificant(0);
        stubApplyContext(draft);

        revisionService.applyRevision(ARTICLE_ID);

        verify(articleUpdateNotifyService, never()).notifyCollectors(anyLong(), anyLong());
    }
}