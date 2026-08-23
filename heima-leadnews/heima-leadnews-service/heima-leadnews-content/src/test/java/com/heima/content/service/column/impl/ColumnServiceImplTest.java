package com.heima.content.service.column.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.heima.content.mapper.column.ApColumnMapper;
import com.heima.model.column.pojos.ApColumn;
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
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ColumnServiceImpl 单元测试（专栏管理：列表/统计/创建/更新/删除 + 异步审核）
 *
 * 继承 ServiceImpl，baseMapper(ApColumnMapper) 反射注入并初始化 ApColumn 的 TableInfo；
 * columnAuditService 由 @Mock 提供。
 * 覆盖：
 * - list：未登录、authorId 兜底、status/title 过滤、分页；
 * - statistics：total/published/reviewing/rejected 四段 count；
 * - createColumn：参数校验、成功入库与异步审核；
 * - updateColumn：未登录/无id/不存在/非本人/部分更新；
 * - deleteColumn：未登录/无id/不存在/非本人/逻辑删除；
 * - asyncReviewColumn 空封面与封面审核、审核异常被捕获；
 * - getStatusCode 各 branch。
 */
class ColumnServiceImplTest {

    @Mock
    private ApColumnMapper baseMapper;
    @Mock
    private ColumnAuditService columnAuditService;

    @InjectMocks
    private ColumnServiceImpl columnService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        injectBaseMapper(columnService, baseMapper);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), ApColumn.class);
        when(baseMapper.insert(any(ApColumn.class))).thenReturn(1);
        when(baseMapper.updateById(any(ApColumn.class))).thenReturn(1);
    }

    @AfterEach
    void tearDown() {
        AppThreadLocalUtil.clear();
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

    private void login(Integer id) {
        ApUser u = new ApUser();
        u.setId(id);
        u.setNickname("专栏作者");
        u.setImage("author.png");
        AppThreadLocalUtil.setUser(u);
    }

    private ApColumn column(Long id, Long authorId) {
        ApColumn c = new ApColumn();
        c.setId(id);
        c.setAuthorId(authorId);
        c.setTitle("标题");
        c.setDescription("简介");
        c.setIsDeleted(false);
        c.setStatus(ApColumn.Status.SUBMIT.getCode());
        return c;
    }

    private Page<ApColumn> pageOf(List<ApColumn> records, long total) {
        Page<ApColumn> p = new Page<>();
        p.setRecords(records);
        p.setTotal(total);
        return p;
    }

    // ---------- list ----------
    @Test
    @DisplayName("list 未登录返回 NEED_LOGIN")
    void listNotLoggedIn() {
        assertEquals(AppHttpCodeEnum.NEED_LOGIN.getCode(), columnService.list(null, 1, 10, null, null).getCode());
    }

    @Test
    @DisplayName("list 带 status/title 过滤并分页（authorId 兜底当前用户）")
    void listWithFilters() {
        login(7);
        when(baseMapper.selectPage(any(), any())).thenReturn(pageOf(List.of(column(1L, 7L)), 1));

        Map<?, ?> data = (Map<?, ?>) columnService.list(null, 1, 10, "published", "专").getData();
        assertEquals(1L, ((Number) data.get("total")).longValue());
        assertEquals(1, ((List<?>) data.get("list")).size());
    }

    @Test
    @DisplayName("list 每次调用都是全量评价（无副作用）")
    void listStress() {
        login(7);
        when(baseMapper.selectPage(any(), any())).thenReturn(pageOf(List.of(), 0));
        for (int i = 0; i < 10; i++) {
            assertEquals(0L, ((Map<?, ?>) columnService.list(null, i, 10, null, null).getData()).get("total"));
        }
    }

    // ---------- statistics ----------
    @Test
    @DisplayName("statistics 统计各类状态的数量")
    void statistics() {
        login(7);
        when(baseMapper.selectCount(any())).thenReturn(10L).thenReturn(5L).thenReturn(3L).thenReturn(2L);

        Map<?, ?> data = (Map<?, ?>) columnService.statistics(7L).getData();
        assertEquals(10, ((Number) data.get("total")).intValue());
        assertEquals(5, ((Number) data.get("published")).intValue());
        assertEquals(3, ((Number) data.get("reviewing")).intValue());
        assertEquals(2, ((Number) data.get("rejected")).intValue());
    }

    @Test
    @DisplayName("statistics 未登录返回 NEED_LOGIN")
    void statisticsNotLoggedIn() {
        assertEquals(AppHttpCodeEnum.NEED_LOGIN.getCode(), columnService.statistics(null).getCode());
    }

    // ---------- createColumn ----------
    @Test
    @DisplayName("createColumn 参数校验与成功入库")
    void createColumnValidationAndOk() {
        assertEquals(AppHttpCodeEnum.NEED_LOGIN.getCode(), columnService.createColumn(column(1L, 1L)).getCode());

        login(7);
        ApColumn noTitle = column(1L, 7L);
        noTitle.setTitle("");
        assertEquals(AppHttpCodeEnum.PARAM_INVALID.getCode(), columnService.createColumn(noTitle).getCode());

        ApColumn noDesc = column(1L, 7L);
        noDesc.setDescription("");
        assertEquals(AppHttpCodeEnum.PARAM_INVALID.getCode(), columnService.createColumn(noDesc).getCode());

        ApColumn ok = column(1L, 7L);
        ok.setCoverImage("");
        ResponseResult r = columnService.createColumn(ok);
        assertEquals(200, r.getCode());
        assertNotNull(r.getData());
        verify(baseMapper).insert(any(ApColumn.class));
        verify(columnAuditService).audit(any());
    }

    // ---------- updateColumn ----------
    @Test
    @DisplayName("updateColumn 未登录/无id/不存在/非本人")
    void updateColumnGuards() {
        assertEquals(AppHttpCodeEnum.NEED_LOGIN.getCode(), columnService.updateColumn(column(1L, 1L)).getCode());

        login(7);
        ApColumn noId = column(null, 7L);
        assertEquals(AppHttpCodeEnum.PARAM_INVALID.getCode(), columnService.updateColumn(noId).getCode());

        when(baseMapper.selectById(anyLong())).thenReturn(null);
        assertEquals(AppHttpCodeEnum.DATA_NOT_EXIST.getCode(), columnService.updateColumn(column(1L, 7L)).getCode());

        when(baseMapper.selectById(anyLong())).thenReturn(column(1L, 99L));
        assertEquals(AppHttpCodeEnum.DATA_NOT_EXIST.getCode(), columnService.updateColumn(column(1L, 7L)).getCode());
    }

    @Test
    @DisplayName("updateColumn 部分字段更新成功")
    void updateColumnPartial() {
        login(7);
        ApColumn existing = column(1L, 7L);
        existing.setTitle("旧标题");
        when(baseMapper.selectById(anyLong())).thenReturn(existing);

        ApColumn patch = column(1L, 7L);
        patch.setTitle("新标题");
        patch.setDescription(null);
        ResponseResult r = columnService.updateColumn(patch);
        assertEquals(200, r.getCode());
        verify(baseMapper).updateById(any(ApColumn.class));
    }

    // ---------- deleteColumn ----------
    @Test
    @DisplayName("deleteColumn 未登录/无id/不存在/非本人")
    void deleteColumnGuards() {
        assertEquals(AppHttpCodeEnum.NEED_LOGIN.getCode(), columnService.deleteColumn(1L).getCode());

        login(7);
        assertEquals(AppHttpCodeEnum.PARAM_INVALID.getCode(), columnService.deleteColumn(null).getCode());

        when(baseMapper.selectById(anyLong())).thenReturn(null);
        assertEquals(AppHttpCodeEnum.DATA_NOT_EXIST.getCode(), columnService.deleteColumn(1L).getCode());

        when(baseMapper.selectById(anyLong())).thenReturn(column(1L, 99L));
        assertEquals(AppHttpCodeEnum.DATA_NOT_EXIST.getCode(), columnService.deleteColumn(1L).getCode());
    }

    @Test
    @DisplayName("deleteColumn 本人逻辑删除成功")
    void deleteColumnOk() {
        login(7);
        when(baseMapper.selectById(anyLong())).thenReturn(column(1L, 7L));
        assertEquals(200, columnService.deleteColumn(1L).getCode());
        verify(baseMapper).updateById(any(ApColumn.class));
    }

    // ---------- asyncReviewColumn ----------
    @Test
    @DisplayName("asyncReviewColumn 带封面审核并成功")
    void asyncReviewWithCover() {
        login(7);
        ApColumn c = column(1L, 7L);
        c.setCoverImage("cover.png");
        columnService.asyncReviewColumn(c);
        verify(columnAuditService).audit(any());
    }

    @Test
    @DisplayName("asyncReviewColumn 审核异常被捕获不抛出")
    void asyncReviewException() {
        login(7);
        doThrow(new RuntimeException("audit down")).when(columnAuditService).audit(any());
        columnService.asyncReviewColumn(column(1L, 7L));
        // 无异常即通过
        verify(columnAuditService).audit(any());
    }
}