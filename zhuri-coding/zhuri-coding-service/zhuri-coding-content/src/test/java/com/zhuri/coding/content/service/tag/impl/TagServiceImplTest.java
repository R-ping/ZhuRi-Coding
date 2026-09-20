package com.zhuri.coding.content.service.tag.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.zhuri.coding.content.mapper.article.ApArticleMapper;
import com.zhuri.coding.content.mapper.tag.TagMapper;
import com.zhuri.coding.model.article.dtos.TagCountDTO;
import com.zhuri.coding.model.article.pojos.ApArticle;
import com.zhuri.coding.model.common.dtos.ResponseResult;
import com.zhuri.coding.model.tag.pojos.ApTag;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.lang.reflect.Field;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TagServiceImpl 单元测试（标签：列表查询/分类聚合/标签文章分页）
 *
 * 继承 ServiceImpl，baseMapper(TagMapper) 需反射注入；apArticleMapper 由 @Mock 提供。
 * 覆盖：
 * - findList 关键字过滤与空关键字；
 * - findTagsByCategory 标签计数聚合与排序；
 * - getArticles 参数兜底、空标签、分页/排序透传。
 */
class TagServiceImplTest {

    @Mock
    private TagMapper baseMapper;
    @Mock
    private ApArticleMapper apArticleMapper;

    @InjectMocks
    private TagServiceImpl tagService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        injectBaseMapper(tagService, baseMapper);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), ApTag.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), ApArticle.class);
    }

    private void injectBaseMapper(Object service, Object mapper) {
        // 3.5.12 起 baseMapper 上移至父类 CrudRepository，ReflectionTestUtils 沿继承链查找
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
    }

    // ---------- findList ----------
    @Test
    @DisplayName("findList 带关键字过滤返回标签列表")
    void findListWithKeyword() {
        ApTag tag = new ApTag();
        tag.setName("Java");
        tag.setStatus(1);
        when(baseMapper.selectList(any())).thenReturn(List.of(tag));

        List<ApTag> list = tagService.findList("Java");
        assertEquals(1, list.size());
        assertEquals("Java", list.get(0).getName());
    }

    @Test
    @DisplayName("findList 空关键字不过滤")
    void findListNoKeyword() {
        when(baseMapper.selectList(any())).thenReturn(List.of());
        assertEquals(0, tagService.findList(null).size());
        assertEquals(0, tagService.findList("   ").size());
    }

    // ---------- findTagsByCategory ----------
    @Test
    @DisplayName("findTagsByCategory 聚合标签并按计数降序")
    void findTagsByCategory() {
        ApArticle a1 = new ApArticle();
        a1.setTags(List.of("java", "并发", "java"));
        ApArticle a2 = new ApArticle();
        a2.setTags(List.of("java", "spring"));
        ApArticle a3 = new ApArticle();
        a3.setTags(List.of());
        when(apArticleMapper.selectList(any())).thenReturn(List.of(a1, a2, a3));

        List<Map<String, Object>> result = tagService.findTagsByCategory(6);
        assertEquals(3, result.size());
        // java 出现 3 次排第一
        assertEquals("java", result.get(0).get("tagName"));
        assertEquals(3, result.get(0).get("count"));
    }

    // ---------- topByCategory ----------
    @Test
    @DisplayName("topByCategory 空关键字按计数降序返回 TopN")
    void topByCategory_ordersByCountWhenKeywordBlank() {
        TagCountDTO java = new TagCountDTO();
        java.setTagName("Java");
        java.setCount(3);
        TagCountDTO vue = new TagCountDTO();
        vue.setTagName("Vue");
        vue.setCount(2);
        when(apArticleMapper.selectTopTagsByCategory(eq(1), eq(null), eq(15)))
                .thenReturn(List.of(java, vue));

        List<TagCountDTO> result = tagService.topByCategory(1, null, 15);
        assertEquals(2, result.size());
        // 顺序应为 Java,Vue（按 count 降序）
        assertEquals("Java", result.get(0).getTagName());
        assertEquals("Vue", result.get(1).getTagName());
    }

    @Test
    @DisplayName("topByCategory 关键字体透传且默认 size 兜底为 15")
    void topByCategory_passesKeywordAndDefaultSize() {
        TagCountDTO java = new TagCountDTO();
        java.setTagName("Java");
        java.setCount(1);
        when(apArticleMapper.selectTopTagsByCategory(eq(1), eq("Ja"), eq(15)))
                .thenReturn(List.of(java));

        tagService.topByCategory(1, "Ja", 0);
        // size 兜底为 15，keyword 透传为 "Ja"
        verify(apArticleMapper).selectTopTagsByCategory(eq(1), eq("Ja"), eq(15));
    }

    @Test
    @DisplayName("topByCategory 分类为空返回空列表且不越界调用")
    void topByCategory_returnsEmpty_whenCategoryNull() {
        List<TagCountDTO> result = tagService.topByCategory(null, null, 15);
        assertEquals(0, result.size());
        verify(apArticleMapper, never()).selectTopTagsByCategory(any(), anyString(), org.mockito.ArgumentMatchers.anyInt());
    }

    // ---------- getArticles ----------
    @Test
    @DisplayName("getArticles 空标签返回空列表 total 0")
    void getArticlesEmptyTag() {
        ResponseResult r = tagService.getArticles("", 1, 20, "latest");
        Map<?, ?> data = (Map<?, ?>) r.getData();
        assertEquals(0L, ((Number) data.get("total")).longValue());
        assertEquals(0, ((List<?>) data.get("list")).size());
    }

    @Test
    @DisplayName("getArticles 参数兜底与排序映射")
    void getArticlesDefaults() {
        ApArticle article = new ApArticle();
        article.setId(1L);
        article.setTitle("t");
        when(apArticleMapper.selectTagArticleList(anyString(), anyString(),
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt())).thenReturn(List.of(article));
        when(apArticleMapper.countTagArticles("java")).thenReturn(1L);

        ResponseResult r = tagService.getArticles("java", null, null, "invalid");
        Map<?, ?> data = (Map<?, ?>) r.getData();
        assertEquals(1L, ((Number) data.get("total")).longValue());
        assertEquals(1, data.get("page"));
        assertEquals(20, data.get("size"));
        assertEquals(1, ((List<?>) data.get("list")).size());
    }
}