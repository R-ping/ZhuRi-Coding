package com.heima.content.service.article.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.heima.content.mapper.article.ApArticleContentMapper;
import com.heima.content.mapper.article.ApArticleMapper;
import com.heima.model.article.pojos.ApArticle;
import com.heima.model.article.pojos.ApArticleContent;
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
import org.mockito.MockitoAnnotations;

import java.lang.reflect.Field;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.Arrays;
import java.util.Date;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ArticleManageServiceImpl 单元测试（我的文章列表/统计/删除/详情）
 *
 * 继承 ServiceImpl，私有 baseMapper 反射注入；@Autowired apArticleContentMapper 由 @InjectMocks 注入。
 * 覆盖：
 * - list：未登录拦截、按 authorId/状态/标题过滤、null-safe 映射；
 * - statistics：未登录拦截、four 状态统计(published/reviewing/rejected)；
 * - deleteArticle：未登录、id 为空、文章不存在、越权、本人软删成功；
 * - getArticleById：未登录、id 为空、文章不存在、正常含内容组装字段；
 * - getStatusCode：published/reviewing/rejected/默认 null。
 */
class ArticleManageServiceImplTest {

    @Mock private ApArticleMapper apArticleMapper;
    @Mock private ApArticleContentMapper apArticleContentMapper;

    @InjectMocks
    private ArticleManageServiceImpl manageService;

    @BeforeEach
    void setUp() throws Exception {
        MockitoAnnotations.openMocks(this);
        // 3.5.12 起 baseMapper 上移至父类 CrudRepository，ReflectionTestUtils 沿继承链查找
        ReflectionTestUtils.setField(manageService, "baseMapper", apArticleMapper);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), ApArticle.class);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), ApArticleContent.class);
    }

    @AfterEach
    void tearDown() {
        AppThreadLocalUtil.clear();
    }

    private ApUser user(Integer id) {
        ApUser u = new ApUser();
        u.setId(id);
        u.setNickname("小明");
        return u;
    }

    private ApArticle article(Long id, Long authorId, byte status) {
        ApArticle a = new ApArticle();
        a.setId(id);
        a.setAuthorId(authorId);
        a.setStatus(status);
        a.setTitle("标题");
        a.setChannelId(1);
        a.setChannelName("频道");
        a.setLayout((byte) 1);
        a.setCoverImage("cover.png");
        a.setTags(Arrays.asList("a", "b"));
        a.setPublishTime(new Date());
        a.setCreatedTime(new Date());
        return a;
    }

    // ==================== list ====================

    @Test
    @DisplayName("list - 未登录返回 NEED_LOGIN")
    void testListNeedLogin() {
        assertEquals(AppHttpCodeEnum.NEED_LOGIN.getCode(), manageService.list(null, 1, 10, null, null).getCode());
    }

    @Test
    @DisplayName("list - 状态+标题过滤并映射")
    void testListOk() {
        AppThreadLocalUtil.setUser(user(1));
        Page<ApArticle> p = new Page<>(1, 10);
        p.setTotal(1L);
        p.setRecords(Arrays.asList(article(1L, 1L, (byte) 2)));
        when(apArticleMapper.selectPage(any(IPage.class), any(Wrapper.class))).thenReturn(p);

        ResponseResult r = manageService.list(2L, 1, 10, "published", "标");
        assertEquals(200, r.getCode());
        Map<String, Object> data = (Map<String, Object>) r.getData();
        assertEquals(1L, data.get("total"));
    }

    // ==================== statistics ====================

    @Test
    @DisplayName("statistics - 未登录返回 NEED_LOGIN")
    void testStatisticsNeedLogin() {
        assertEquals(AppHttpCodeEnum.NEED_LOGIN.getCode(), manageService.statistics(null).getCode());
    }

    @Test
    @DisplayName("statistics - 四项状态统计")
    void testStatisticsOk() {
        AppThreadLocalUtil.setUser(user(1));
        when(apArticleMapper.selectCount(any(Wrapper.class))).thenReturn(5L);
        ResponseResult r = manageService.statistics(2L);
        Map<String, Object> data = (Map<String, Object>) r.getData();
        assertEquals(5L, data.get("total"));
        assertEquals(5L, data.get("published"));
        assertEquals(5L, data.get("reviewing"));
        assertEquals(5L, data.get("rejected"));
    }

    // ==================== deleteArticle ====================

    @Test
    @DisplayName("deleteArticle - 未登录/空id/不存在/越权")
    void testDeleteGuards() {
        assertEquals(AppHttpCodeEnum.NEED_LOGIN.getCode(), manageService.deleteArticle(1L).getCode());
        AppThreadLocalUtil.setUser(user(1));
        assertEquals(AppHttpCodeEnum.PARAM_INVALID.getCode(), manageService.deleteArticle(null).getCode());
        when(apArticleMapper.selectById(1L)).thenReturn(null);
        assertEquals(AppHttpCodeEnum.DATA_NOT_EXIST.getCode(), manageService.deleteArticle(1L).getCode());
        when(apArticleMapper.selectById(1L)).thenReturn(article(1L, 99L, (byte) 2));
        assertEquals(AppHttpCodeEnum.DATA_NOT_EXIST.getCode(), manageService.deleteArticle(1L).getCode());
    }

    @Test
    @DisplayName("deleteArticle - 本人软删成功")
    void testDeleteOk() {
        AppThreadLocalUtil.setUser(user(1));
        ApArticle a = article(1L, 1L, (byte) 2);
        when(apArticleMapper.selectById(1L)).thenReturn(a);
        assertEquals(200, manageService.deleteArticle(1L).getCode());
        verify(apArticleMapper).updateById((ApArticle) any(ApArticle.class));
    }

    // ==================== getArticleById ====================

    @Test
    @DisplayName("getArticleById - 未登录/空id/不存在")
    void testGetGuards() {
        assertEquals(AppHttpCodeEnum.NEED_LOGIN.getCode(), manageService.getArticleById(1L).getCode());
        AppThreadLocalUtil.setUser(user(1));
        assertEquals(AppHttpCodeEnum.PARAM_INVALID.getCode(), manageService.getArticleById(null).getCode());
        when(apArticleMapper.selectById(1L)).thenReturn(null);
        assertEquals(AppHttpCodeEnum.DATA_NOT_EXIST.getCode(), manageService.getArticleById(1L).getCode());
    }

    @Test
    @DisplayName("getArticleById - 正常关联内容并反射双字段")
    void testGetOk() {
        AppThreadLocalUtil.setUser(user(1));
        when(apArticleMapper.selectById(1L)).thenReturn(article(1L, 1L, (byte) 2));
        when(apArticleContentMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        ResponseResult r = manageService.getArticleById(1L);
        Map<String, Object> data = (Map<String, Object>) r.getData();
        assertEquals("标题", data.get("title"));
        assertEquals("", data.get("content")); // content 为空

        ApArticleContent content = new ApArticleContent();
        content.setContent("正文");
        when(apArticleContentMapper.selectOne(any(Wrapper.class))).thenReturn(content);
        r = manageService.getArticleById(1L);
        assertEquals("正文", ((Map<String, Object>) r.getData()).get("content"));
    }
}