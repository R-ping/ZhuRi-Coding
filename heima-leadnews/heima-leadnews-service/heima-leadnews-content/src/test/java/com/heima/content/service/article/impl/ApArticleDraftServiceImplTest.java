package com.heima.content.service.article.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.heima.content.mapper.article.ApArticleConfigMapper;
import com.heima.content.mapper.article.ApArticleContentMapper;
import com.heima.content.mapper.article.ApArticleDraftMapper;
import com.heima.content.mapper.article.ApArticleMapper;
import com.heima.content.service.article.ArticleAutoScanService;
import com.heima.content.service.level.LevelPermissionService;
import com.heima.content.utils.MarkdownUtils;
import com.heima.model.article.pojos.ApArticle;
import com.heima.model.article.pojos.ApArticleConfig;
import com.heima.model.article.pojos.ApArticleContent;
import com.heima.model.article.pojos.ApArticleDraft;
import com.heima.model.common.dtos.ResponseResult;
import com.heima.model.common.enums.AppHttpCodeEnum;
import com.heima.model.user.pojos.ApUser;
import com.heima.utils.thread.AppThreadLocalUtil;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.MockitoAnnotations;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Date;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ApArticleDraftServiceImpl 单元测试（草稿 CRUD + 草稿发布为文章）
 *
 * 继承 ServiceImpl(草稿Mapper)，私有 baseMapper 反射注入；其余依赖 @InjectMocks。提供 getApArticle@NotNull。
 * 覆盖：
 * - createDraft：写入作者ID/时间并 save；
 * - updateDraft：id 为空、草稿不存在、成功(补作者ID/时间)；
 * - publishFromDraft：id 为空、草稿不存在、成功创建 article/config/content + 删除草稿(返回 article)；
 * - getDraftById：不存在/正常；
 * - listDrafts：按作者过滤分页；
 * - deleteDraft：id 为空、未登录、草稿不存在、越权、本人成功；
 * - getApArticle：publishTime/layout 缺省、authorId 回退当前用户、作者信息填充。
 */
class ApArticleDraftServiceImplTest {

    @Mock private ApArticleDraftMapper apArticleDraftMapper;
    @Mock private ApArticleMapper apArticleMapper;
    @Mock private ApArticleConfigMapper apArticleConfigMapper;
    @Mock private ApArticleContentMapper apArticleContentMapper;
    @Mock private ArticleAutoScanService articleAutoScanService;
    // 发布权限校验：生产 publishFromDraft 在发布前会校验用户是否拥有发布文章权限
    @Mock private LevelPermissionService levelPermissionService;

    @InjectMocks
    private ApArticleDraftServiceImpl draftService;

    @BeforeEach
    void setUp() throws Exception {
        MockitoAnnotations.openMocks(this);
        Field f = com.baomidou.mybatisplus.extension.service.IService.class.getClassLoader()
                .loadClass("com.baomidou.mybatisplus.extension.service.impl.ServiceImpl")
                .getDeclaredField("baseMapper");
        f.setAccessible(true);
        f.set(draftService, apArticleDraftMapper);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), ApArticleDraft.class);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), ApArticle.class);
    }

    @AfterEach
    void tearDown() {
        AppThreadLocalUtil.clear();
    }

    private ApUser user(Integer id) {
        ApUser u = new ApUser();
        // ApUser.id 为 Integer；deleteDraft 归属校验已将其与 authorId(Long) 统一为 long 比较
        u.setId(id);
        u.setNickname("小明");
        u.setImage("avatar.png");
        return u;
    }

    private ApArticleDraft draft(Long id, String title) {
        ApArticleDraft d = new ApArticleDraft();
        d.setId(id);
        d.setTitle(title);
        d.setAuthorId(5L);
        d.setChannelId(1);
        d.setLayout((short) 1);
        d.setTags(Arrays.asList("a"));
        d.setContent("![img](http://x.png?sig=1) 正文");
        d.setSummary("摘要");
        return d;
    }

    // ==================== createDraft ====================

    @Test
    @DisplayName("createDraft - 写入作者/时间并保存")
    void testCreate() {
        AppThreadLocalUtil.setUser(user(1));
        when(apArticleDraftMapper.insert(any(ApArticleDraft.class))).thenReturn(1);
        ApArticleDraft d = new ApArticleDraft();
        d.setTitle("新草稿");
        ResponseResult r = draftService.createDraft(d);
        assertEquals(200, r.getCode());
        assertEquals(1L, d.getAuthorId());
        assertNotNull(d.getCreatedTime());
        verify(apArticleDraftMapper).insert((ApArticleDraft) any(ApArticleDraft.class));
    }

    // ==================== updateDraft ====================

    @Test
    @DisplayName("updateDraft - id 为空返回 PARAM_INVALID")
    void testUpdateNoId() {
        AppThreadLocalUtil.setUser(user(1));
        assertEquals(AppHttpCodeEnum.PARAM_INVALID.getCode(),
                draftService.updateDraft(new ApArticleDraft()).getCode());
    }

    @Test
    @DisplayName("updateDraft - 草稿不存在返回 DATA_NOT_EXIST")
    void testUpdateMissing() {
        AppThreadLocalUtil.setUser(user(1));
        ApArticleDraft d = new ApArticleDraft();
        d.setId(1L);
        when(apArticleDraftMapper.selectById(1L)).thenReturn(null);
        assertEquals(AppHttpCodeEnum.DATA_NOT_EXIST.getCode(), draftService.updateDraft(d).getCode());
    }

    @Test
    @DisplayName("updateDraft - 成功补充作者与更新时间")
    void testUpdateOk() {
        AppThreadLocalUtil.setUser(user(1));
        ApArticleDraft d = new ApArticleDraft();
        d.setId(1L);
        d.setTitle("改后");
        when(apArticleDraftMapper.selectById(1L)).thenReturn(draft(1L, "旧"));
        when(apArticleDraftMapper.updateById(any(ApArticleDraft.class))).thenReturn(1);
        ResponseResult r = draftService.updateDraft(d);
        assertEquals(200, r.getCode());
        assertEquals(1L, d.getAuthorId());
        assertNotNull(d.getUpdatedTime());
    }

    // ==================== publishFromDraft ====================

    @Test
    @DisplayName("publishFromDraft - id 为空返回 PARAM_INVALID")
    void testPublishNoId() {
        AppThreadLocalUtil.setUser(user(1));
        when(levelPermissionService.hasPermission(anyLong(), anyString())).thenReturn(true);
        assertEquals(AppHttpCodeEnum.PARAM_INVALID.getCode(), draftService.publishFromDraft(null).getCode());
    }

    @Test
    @DisplayName("publishFromDraft - 草稿不存在返回 DATA_NOT_EXIST")
    void testPublishMissing() {
        AppThreadLocalUtil.setUser(user(1));
        when(levelPermissionService.hasPermission(anyLong(), anyString())).thenReturn(true);
        when(apArticleDraftMapper.selectById(1L)).thenReturn(null);
        assertEquals(AppHttpCodeEnum.DATA_NOT_EXIST.getCode(), draftService.publishFromDraft(1L).getCode());
    }

    @Test
    @DisplayName("publishFromDraft - 成功创建文章/config/content并删草稿")
    void testPublishOk() {
        AppThreadLocalUtil.setUser(user(5));
        when(levelPermissionService.hasPermission(anyLong(), anyString())).thenReturn(true);
        ApArticleDraft d = draft(1L, "草稿标题");
        when(apArticleDraftMapper.selectById(1L)).thenReturn(d);
        org.mockito.Mockito.doAnswer(inv -> {
            ((ApArticle) inv.getArgument(0)).setId(100L);
            return 1;
        }).when(apArticleMapper).insert(any(ApArticle.class));

        // mockStatic 使 registerSynchronization 变成 no-op（不再抛 "事务同步未激活"），
        // 用 captor 捕获注册的 Synchronization 后手动 afterCommit，覆盖 autoScanArticle 异步分支。
        org.mockito.ArgumentCaptor<TransactionSynchronization> captor =
                org.mockito.ArgumentCaptor.forClass(TransactionSynchronization.class);
        try (MockedStatic<TransactionSynchronizationManager> ts =
                org.mockito.Mockito.mockStatic(TransactionSynchronizationManager.class)) {
            ResponseResult r = draftService.publishFromDraft(1L);
            assertEquals(200, r.getCode());
            ApArticle article = (ApArticle) r.getData();
            assertEquals(100L, article.getId());
            assertEquals(5L, article.getAuthorId());
            verify(apArticleConfigMapper).insert(any(ApArticleConfig.class));
            verify(apArticleContentMapper).insert(any(ApArticleContent.class));
            verify(apArticleDraftMapper).deleteById(1L);

            ts.verify(() -> TransactionSynchronizationManager.registerSynchronization(captor.capture()));
            captor.getValue().afterCommit();
            verify(articleAutoScanService).autoScanArticle(100L);
            // 强转指明 insert(T) 重载，并显式给 lambda 标类型避免泛型推断为 Object
            verify(apArticleContentMapper).insert((ApArticleContent) org.mockito.ArgumentMatchers.argThat(
                    (ApArticleContent c) -> c.getContent() != null && !c.getContent().contains("?sig=1")));
        }
    }

    @Test
    @DisplayName("publishFromDraft - 删除草稿失败抛异常")
    void testPublishDeleteFails() {
        AppThreadLocalUtil.setUser(user(5));
        when(levelPermissionService.hasPermission(anyLong(), anyString())).thenReturn(true);
        ApArticleDraft d = draft(1L, "标题");
        when(apArticleDraftMapper.selectById(1L)).thenReturn(d);
        org.mockito.Mockito.doAnswer(inv -> { ((ApArticle) inv.getArgument(0)).setId(1L); return 1; })
                .when(apArticleMapper).insert(any(ApArticle.class));
        org.mockito.Mockito.doThrow(new RuntimeException("delete fail"))
                .when(apArticleDraftMapper).deleteById(1L);
        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                () -> draftService.publishFromDraft(1L));
    }

    // ==================== getDraftById / listDrafts / deleteDraft ====================

    @Test
    @DisplayName("getDraftById - 不存在/正常")
    void testGetDraft() {
        when(apArticleDraftMapper.selectById(1L)).thenReturn(null);
        assertEquals(AppHttpCodeEnum.DATA_NOT_EXIST.getCode(), draftService.getDraftById(1L).getCode());
        when(apArticleDraftMapper.selectById(1L)).thenReturn(draft(1L, "D"));
        assertEquals(200, draftService.getDraftById(1L).getCode());
    }

    @Test
    @DisplayName("listDrafts - 按作者过滤分页")
    void testListDrafts() {
        Page<ApArticleDraft> p = new Page<>(1, 10);
        p.setRecords(java.util.Collections.singletonList(draft(1L, "D")));
        when(apArticleDraftMapper.selectPage(any(IPage.class), any(Wrapper.class))).thenReturn(p);
        ResponseResult r = draftService.listDrafts(5L, 1, 10);
        assertEquals(200, r.getCode());
        assertEquals(1, ((Page<?>) r.getData()).getRecords().size());
    }

    @Test
    @DisplayName("deleteDraft - 守卫：空id/不存在")
    void testDeleteGuards() {
        assertEquals(AppHttpCodeEnum.PARAM_INVALID.getCode(), draftService.deleteDraft(null).getCode());
        // 不存在草稿需先登录，deleteDraft 的 NEED_LOGIN 检查在 DATA_NOT_EXIST 之前
        AppThreadLocalUtil.setUser(user(99));
        when(apArticleDraftMapper.selectById(2L)).thenReturn(null);
        assertEquals(AppHttpCodeEnum.DATA_NOT_EXIST.getCode(), draftService.deleteDraft(2L).getCode());
    }

    @Test
    @DisplayName("deleteDraft - 未登录返回 NEED_LOGIN")
    void testDeleteNeedLogin() {
        when(apArticleDraftMapper.selectById(1L)).thenReturn(draft(1L, "D"));
        assertEquals(AppHttpCodeEnum.NEED_LOGIN.getCode(), draftService.deleteDraft(1L).getCode());
    }

    @Test
    @DisplayName("deleteDraft - 越权拒绝")
    void testDeleteNoAuth() {
        AppThreadLocalUtil.setUser(user(99));
        when(apArticleDraftMapper.selectById(1L)).thenReturn(draft(1L, "D")); // authorId=5
        assertEquals(AppHttpCodeEnum.NO_OPERATOR_AUTH.getCode(), draftService.deleteDraft(1L).getCode());
    }

    @Test
    @DisplayName("deleteDraft - 本人成功")
    void testDeleteOk() {
        AppThreadLocalUtil.setUser(user(5));
        when(apArticleDraftMapper.selectById(1L)).thenReturn(draft(1L, "D"));
        assertEquals(200, draftService.deleteDraft(1L).getCode());
        verify(apArticleDraftMapper).deleteById(1L);
    }
}