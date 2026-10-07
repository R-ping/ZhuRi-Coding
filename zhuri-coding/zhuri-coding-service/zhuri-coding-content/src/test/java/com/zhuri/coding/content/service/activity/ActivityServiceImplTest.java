package com.zhuri.coding.content.service.activity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.zhuri.coding.content.mapper.activity.ApActivityMapper;
import com.zhuri.coding.content.service.activity.impl.ActivityServiceImpl;
import com.zhuri.coding.model.activity.ActivityStatus;
import com.zhuri.coding.model.activity.ActivityTaxonomy;
import com.zhuri.coding.model.activity.pojos.ApActivity;
import com.zhuri.coding.model.activity.vos.ActivityVO;
import java.util.Calendar;
import java.util.Collection;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * C 端活动读路径单测。
 *
 * <p>这个类的存在理由只有一个：<b>草稿与已下线不能漏给用户</b>。
 * 运营后台能写 {@code draft} / {@code offline} 之后，这两类活动就真的躺在同一张表里了 ——
 * 读路径一旦不过滤，"运营刚存了个草稿，全站用户立刻看到半成品"，而且不会有任何报错。
 *
 * <p>两种失效都要钉住：
 * <ul>
 *   <li><b>不筛状态时</b>必须只取已发布的三种（用 IN，而不是"排除 draft/offline"——
 *       遇上一个谁都没预料到的状态取值，IN 把它挡在外面，NOT IN 会把它放进来）；</li>
 *   <li><b>请求了不可见状态时</b>返回空，而不是"忽略这个条件"。忽略等于把结果集放大，
 *       而放大后的结果不会被任何人注意到。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("C 端活动读路径（ActivityServiceImpl）")
class ActivityServiceImplTest {

    @Mock
    private ApActivityMapper apActivityMapper;

    @InjectMocks
    private ActivityServiceImpl service;

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(
            new MapperBuilderAssistant(new MybatisConfiguration(), ""), ApActivity.class);
    }

    // ==================== 列表 ====================

    @Nested
    @DisplayName("列表可见性")
    class ListVisibility {

        @Test
        @DisplayName("不筛状态时只取已发布的三种（草稿与已下线必须排除在 SQL 条件里）")
        void excludesDraftAndOffline() {
            stubEmptyPage();

            service.list(1, 20, null, null, null);

            LambdaQueryWrapper<?> where = capturedQuery();
            assertTrue(where.getSqlSegment().contains("IN"),
                "要用 IN 收口已发布集合，而不是 NOT IN 排除：\n" + where.getSqlSegment());
            Set<Object> values = flatten(where.getParamNameValuePairs());
            assertTrue(values.contains(ActivityStatus.UPCOMING), values.toString());
            assertTrue(values.contains(ActivityStatus.ONGOING), values.toString());
            assertTrue(values.contains(ActivityStatus.ENDED), values.toString());
            assertTrue(!values.contains(ActivityStatus.DRAFT) && !values.contains(ActivityStatus.OFFLINE),
                "草稿/已下线不能出现在条件里：" + values);
        }

        @Test
        @DisplayName("请求草稿/已下线 → 空列表且不查库（不能忽略这个条件，那会把结果集放大）")
        void invisibleRequestedStatusYieldsEmpty() {
            for (String status : new String[]{ActivityStatus.DRAFT, ActivityStatus.OFFLINE, "  DRAFT "}) {
                Map<String, Object> result = service.list(1, 20, null, status, null);

                assertEquals(0L, result.get("total"), status);
                assertTrue(((List<?>) result.get("list")).isEmpty(), status);
                assertEquals(1, ((Number) result.get("page")).intValue());
                assertEquals(20, ((Number) result.get("size")).intValue());
            }
            verifyNoInteractions(apActivityMapper);
        }

        @Test
        @DisplayName("请求认不出的状态 → 同样是空列表（fail-closed，不猜、也不放宽）")
        void unknownRequestedStatusYieldsEmpty() {
            Map<String, Object> result = service.list(1, 20, null, "archived", null);

            assertEquals(0L, result.get("total"));
            verifyNoInteractions(apActivityMapper);
        }

        @Test
        @DisplayName("请求一个可见状态 → 按该状态筛选，并返回对应的记录")
        void visibleRequestedStatusFilters() {
            ApActivity ongoing = activity(1L, ActivityStatus.ONGOING);
            Page<ApActivity> dbPage = new Page<>(1, 20);
            dbPage.setRecords(List.of(ongoing));
            dbPage.setTotal(1);
            when(apActivityMapper.selectPage(any(), any())).thenReturn(dbPage);

            Map<String, Object> result = service.list(1, 20, null, "  ONGOING ", null);

            LambdaQueryWrapper<?> where = capturedQuery();
            Set<Object> values = flatten(where.getParamNameValuePairs());
            assertTrue(values.contains(ActivityStatus.ONGOING), values.toString());
            assertTrue(!values.contains(ActivityStatus.UPCOMING),
                "指定状态时不该再带上整个已发布集合：" + values);

            List<ActivityVO> list = listOf(result);
            assertEquals(1, list.size());
            assertEquals("进行中活动", list.get(0).getTitle());
            assertEquals(1L, ((Number) result.get("total")).longValue());
        }

        @Test
        @DisplayName("类型与分类过滤保持原样（这次改动只该收紧可见性，不该动别的条件）")
        void keepsTypeAndCategoryFilters() {
            stubEmptyPage();

            service.list(2, 10, "pin", null, "backend");

            LambdaQueryWrapper<?> where = capturedQuery();
            Set<Object> values = flatten(where.getParamNameValuePairs());
            assertTrue(values.contains("pin"), values.toString());
            assertTrue(values.contains("backend"), values.toString());
            assertTrue(where.getSqlSegment().contains("activity_type"), where.getSqlSegment());
            assertTrue(where.getSqlSegment().contains("category"), where.getSqlSegment());
        }

        @Test
        @DisplayName("分类 hot 等于不过滤（前端把它当「全部」，改动不能破坏这个约定）")
        void hotCategoryMeansNoFilter() {
            stubEmptyPage();

            service.list(1, 20, null, null, "hot");

            assertTrue(!capturedQuery().getSqlSegment().contains("category"),
                "hot 是默认分区，等同不过滤");
        }
    }

    // ==================== 详情 ====================

    @Nested
    @DisplayName("详情可见性")
    class DetailVisibility {

        @Test
        @DisplayName("草稿与已下线对 C 端等同于不存在（返回 null，不暴露后台内部状态）")
        void hidesUnpublished() {
            for (String status : new String[]{ActivityStatus.DRAFT, ActivityStatus.OFFLINE}) {
                when(apActivityMapper.selectById(21L)).thenReturn(activity(21L, status));
                assertNull(service.getById(21L), status + " 不该对 C 端可见");
            }
        }

        @Test
        @DisplayName("已发布可读；已结束也可见（历史活动要在列表里留档）")
        void visibleForPublished() {
            when(apActivityMapper.selectById(21L)).thenReturn(activity(21L, ActivityStatus.ENDED));
            assertNotNull(service.getById(21L));
        }

        @Test
        @DisplayName("id 不合法或记录不存在 → null，且不打库")
        void missingYieldsNull() {
            assertNull(service.getById(null));
            assertNull(service.getById(0L));
            verifyNoInteractions(apActivityMapper);

            when(apActivityMapper.selectById(99L)).thenReturn(null);
            assertNull(service.getById(99L));
        }

        @Test
        @DisplayName("状态取值认不出（脏数据）→ 不可见，宁可少显示不可多显示")
        void unknownStatusIsHidden() {
            when(apActivityMapper.selectById(21L)).thenReturn(activity(21L, "published"));
            assertNull(service.getById(21L));
        }
    }

    // ==================== 工具 ====================

    private void stubEmptyPage() {
        Page<ApActivity> dbPage = new Page<>(1, 20);
        dbPage.setRecords(List.of());
        dbPage.setTotal(0);
        when(apActivityMapper.selectPage(any(), any())).thenReturn(dbPage);
    }

    private LambdaQueryWrapper<?> capturedQuery() {
        ArgumentCaptor<Wrapper<ApActivity>> captor = queryCaptor();
        verify(apActivityMapper).selectPage(any(), captor.capture());
        LambdaQueryWrapper<?> wrapper = (LambdaQueryWrapper<?>) captor.getValue();
        // ⚠️ 先渲染一次 WHERE：MyBatis-Plus 的条件是**惰性求值**的（存成 lambda，拼 SQL 片段时
        // 才把值放进参数表）。不渲染就读 getParamNameValuePairs()，会读到缺参数的集合，
        // 表现为断言打印出 [] 这种"条件明明是空的"的假象。
        wrapper.getSqlSegment();
        return wrapper;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ArgumentCaptor<Wrapper<ApActivity>> queryCaptor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(Wrapper.class);
    }

    /** MP 把 IN 的集合整体放进参数表，这里摊平一层，两种形态都能断言 */
    private static Set<Object> flatten(Map<String, Object> params) {
        Set<Object> values = new HashSet<>();
        for (Object value : params.values()) {
            if (value instanceof Collection<?> collection) {
                values.addAll(collection);
            } else {
                values.add(value);
            }
        }
        return values;
    }

    @SuppressWarnings("unchecked")
    private static List<ActivityVO> listOf(Map<String, Object> result) {
        return (List<ActivityVO>) result.get("list");
    }

    private static ApActivity activity(Long id, String status) {
        ApActivity activity = new ApActivity();
        activity.setId(id);
        activity.setTitle("进行中活动");
        activity.setStatus(status);
        activity.setActivityType(ActivityTaxonomy.TYPE_ARTICLE);
        activity.setCategory(ActivityTaxonomy.CATEGORY_HOT);
        activity.setStartDate(day(2026, 1, 1));
        activity.setEndDate(day(2026, 1, 31));
        return activity;
    }

    private static Date day(int year, int month, int day) {
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(year, month - 1, day);
        return c.getTime();
    }
}
