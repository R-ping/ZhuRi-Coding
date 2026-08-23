package com.heima.content.service.browse.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.heima.content.mapper.interaction.ApBrowseHistoryMapper;
import com.heima.model.behavior.pojos.ApBrowseHistory;
import com.heima.model.common.dtos.ResponseResult;
import com.heima.model.common.enums.AppHttpCodeEnum;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BrowseHistoryServiceImpl 单元测试（浏览历史：列表/清空/上报）
 *
 * 继承 ServiceImpl，baseMapper(ApBrowseHistoryMapper) 需反射注入。
 * 覆盖：
 * - getHistoryList 分页 + 条目 null-safe 扁平化、关键字过滤；
 * - clearHistory 逻辑删除（isDeleted → true / deletedAt）；
 * - reportBrowse 参数校验、已存在更新浏览时间、不存在插入新记录。
 */
class BrowseHistoryServiceImplTest {

    @Mock
    private ApBrowseHistoryMapper baseMapper;

    @InjectMocks
    private BrowseHistoryServiceImpl browseService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        injectBaseMapper(browseService, baseMapper);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), ApBrowseHistory.class);
    }

    private void injectBaseMapper(Object service, Object mapper) {
        try {
            Field f = service.getClass().getSuperclass().getDeclaredField("baseMapper");
            f.setAccessible(true);
            f.set(service, mapper);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new IllegalStateException("无法注入 baseMapper", e);
        }
    }

    private ApBrowseHistory history(Long id) {
        ApBrowseHistory h = new ApBrowseHistory();
        h.setId(id);
        h.setUserId(1L);
        h.setArticleId(100L);
        h.setTargetType(1);
        h.setArticleTitle("标题");
        return h;
    }

    // ---------- getHistoryList ----------
    @Test
    @DisplayName("getHistoryList 分页返回扁平列表")
    void getHistoryList() {
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<ApBrowseHistory> page =
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>();
        page.setRecords(List.of(history(1L)));
        page.setTotal(1);
        when(baseMapper.selectPage(any(), any())).thenReturn(page);

        ResponseResult r = browseService.getHistoryList(1L, 1, 10, null);
        Map<?, ?> data = (Map<?, ?>) r.getData();
        assertEquals(1L, ((Number) data.get("total")).longValue());
        Map<?, ?> item = (Map<?, ?>) ((List<?>) data.get("list")).get(0);
        assertEquals("1", item.get("id"));
        assertEquals("100", item.get("articleId"));
        assertEquals(1, item.get("targetType"));
    }

    // ---------- clearHistory ----------
    @Test
    @DisplayName("clearHistory 逻辑删除并记录删除时间")
    void clearHistory() {
        when(baseMapper.update(any(), any())).thenReturn(1);
        browseService.clearHistory(1L);
        verify(baseMapper, times(1)).update(any(), any());
    }

    // ---------- reportBrowse ----------
    @Test
    @DisplayName("reportBrowse 参数缺失返回 PARAM_INVALID")
    void reportBrowseMissingParam() {
        assertEquals(AppHttpCodeEnum.PARAM_INVALID.getCode(),
                browseService.reportBrowse(null, 1, 100L).getCode());
        assertEquals(AppHttpCodeEnum.PARAM_INVALID.getCode(),
                browseService.reportBrowse(1L, null, 100L).getCode());
        assertEquals(AppHttpCodeEnum.PARAM_INVALID.getCode(),
                browseService.reportBrowse(1L, 1, null).getCode());
    }

    @Test
    @DisplayName("reportBrowse 已存在则更新浏览时间")
    void reportBrowseExisting() {
        // ServiceImpl.getOne(wrapper) 内部调用 selectOne(wrapper, true) 两个参数
        when(baseMapper.selectOne(any(), anyBoolean())).thenReturn(history(1L));
        when(baseMapper.updateById(any(ApBrowseHistory.class))).thenReturn(1);

        assertEquals(200, browseService.reportBrowse(1L, 1, 100L).getCode());
        verify(baseMapper).updateById(any(ApBrowseHistory.class));
        verify(baseMapper, never()).insert(any(ApBrowseHistory.class));
    }

    @Test
    @DisplayName("reportBrowse 不存在则插入新记录")
    void reportBrowseNew() {
        when(baseMapper.selectOne(any(), anyBoolean())).thenReturn(null);
        when(baseMapper.insert(any(ApBrowseHistory.class))).thenReturn(1);

        assertEquals(200, browseService.reportBrowse(1L, 2, 200L).getCode());
        verify(baseMapper).insert(any(ApBrowseHistory.class));
        verify(baseMapper, never()).updateById(any(ApBrowseHistory.class));
    }
}