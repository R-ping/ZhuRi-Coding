package com.zhuri.coding.content.schedule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.zhuri.coding.content.mapper.activity.ApActivityMapper;
import com.zhuri.coding.model.activity.ActivityStatus;
import com.zhuri.coding.model.activity.pojos.ApActivity;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 活动生命周期推进任务单测。
 *
 * <p>三件事值得钉住：
 * <ol>
 *   <li><b>两条 UPDATE 的顺序</b>：先"即将开始 → 进行中"，再"进行中 → 已结束"。
 *       反过来写的话，一条早已过期但仍标着 {@code upcoming} 的活动（停机期间错过的窗口）
 *       会被第一条 UPDATE 变成 {@code ongoing}，而第二条此刻已经跑过了 —— 它要等到下一轮
 *       才会被修正，中间这段时间它就是错的；</li>
 *   <li><b>参照值是"今天 00:00"而不是"此刻"</b>：{@code end_date} 是 DATE 列，
 *       拿此刻去比会让最后一天的活动提前一整天被结束；</li>
 *   <li><b>草稿与已下线绝不能被碰到</b>：两种 UPDATE 都带 {@code status = 旧值}，
 *       草稿/已下线不在任何一条的取值里。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("活动生命周期推进任务（ActivityLifecycleTask）")
class ActivityLifecycleTaskTest {

    @Mock
    private ApActivityMapper apActivityMapper;

    @InjectMocks
    private ActivityLifecycleTask task;

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(
            new MapperBuilderAssistant(new MybatisConfiguration(), ""), ApActivity.class);
    }

    @Test
    @DisplayName("两条 UPDATE 先「开始」后「结束」，且各自带旧状态条件（并发/重复执行只生效一次）")
    void advancesInTheRightOrder() {
        when(apActivityMapper.update(any(), any())).thenReturn(1);

        task.advanceStatus();

        ArgumentCaptor<Wrapper<ApActivity>> captor = updateCaptor();
        verify(apActivityMapper, times(2)).update(any(), captor.capture());
        List<Wrapper<ApActivity>> updates = captor.getAllValues();

        LambdaUpdateWrapper<?> first = (LambdaUpdateWrapper<?>) updates.get(0);
        Set<Object> firstValues = paramsOf(first);
        assertTrue(firstValues.contains(ActivityStatus.UPCOMING),
            "第一条的条件是「还是即将开始」：" + firstValues);
        assertTrue(firstValues.contains(ActivityStatus.ONGOING),
            "第一条的结果是「变成进行中」：" + firstValues);
        assertTrue(first.getSqlSegment().contains("start_date"),
            "第一条按开始日期判断：" + first.getSqlSegment());

        LambdaUpdateWrapper<?> second = (LambdaUpdateWrapper<?>) updates.get(1);
        Set<Object> secondValues = paramsOf(second);
        assertTrue(secondValues.contains(ActivityStatus.ONGOING),
            "第二条的条件是「还在进行中」：" + secondValues);
        assertTrue(secondValues.contains(ActivityStatus.ENDED),
            "第二条的结果是「变成已结束」：" + secondValues);
        assertTrue(second.getSqlSegment().contains("end_date"),
            "第二条按结束日期判断：" + second.getSqlSegment());
    }

    @Test
    @DisplayName("参照值是今天 00:00（按时刻比会把最后一天的活动提前结束一整天）")
    void usesStartOfToday() {
        when(apActivityMapper.update(any(), any())).thenReturn(1);

        task.advanceStatus();

        ArgumentCaptor<Wrapper<ApActivity>> captor = updateCaptor();
        verify(apActivityMapper, times(2)).update(any(), captor.capture());

        for (Wrapper<ApActivity> wrapper : captor.getAllValues()) {
            LambdaUpdateWrapper<?> update = (LambdaUpdateWrapper<?>) wrapper;
            update.getSqlSegment();
            Date today = update.getParamNameValuePairs().values().stream()
                .filter(Date.class::isInstance)
                .map(Date.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("条件里应带上参照日期"));
            Calendar c = Calendar.getInstance();
            c.setTime(today);
            assertEquals(0, c.get(Calendar.HOUR_OF_DAY));
            assertEquals(0, c.get(Calendar.MINUTE));
            assertEquals(0, c.get(Calendar.SECOND));
            assertEquals(0, c.get(Calendar.MILLISECOND));
            assertTrue(today.after(day(2000, 1, 1)), "参照日期应该是当前时间附近，不是写死的常量");
        }
    }

    @Test
    @DisplayName("草稿与已下线不在任何一条 UPDATE 的取值里（它们与日期无关，任务不该碰）")
    void neverTouchesDraftOrOffline() {
        when(apActivityMapper.update(any(), any())).thenReturn(0);

        task.advanceStatus();

        ArgumentCaptor<Wrapper<ApActivity>> captor = updateCaptor();
        verify(apActivityMapper, times(2)).update(any(), captor.capture());
        for (Wrapper<ApActivity> wrapper : captor.getAllValues()) {
            Set<Object> values = paramsOf((LambdaUpdateWrapper<?>) wrapper);
            assertTrue(!values.contains(ActivityStatus.DRAFT), values.toString());
            assertTrue(!values.contains(ActivityStatus.OFFLINE), values.toString());
        }
    }

    @Test
    @DisplayName("落库抛异常时不外抛（定时任务抛异常只会被调度器记一笔，下一轮自愈更好）")
    void swallowsException() {
        when(apActivityMapper.update(any(), any())).thenThrow(new RuntimeException("connection reset"));

        task.advanceStatus();

        // 没有异常逃出去即通过；两侧各调一次，说明第一条失败没有阻止第二条尝试
        verify(apActivityMapper, times(2)).update(any(), any());
    }

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

    /**
     * 取包装器里的参数值。
     *
     * <p>⚠️ <b>必须先渲染一次 {@code getSqlSegment()}</b>：MyBatis-Plus 的 WHERE 条件是**惰性求值**的
     * （存成 lambda，等到拼 SQL 片段时才调 {@code formatParam} 把值放进参数表），
     * 而 SET 子句是构造时就写好的。直接读 {@code getParamNameValuePairs()} 只能看到 SET 的那部分，
     * 表现为"条件参数莫名其妙少了"、断言里打印出 {@code [ongoing]} 这种缺一半的集合。
     */
    private static Set<Object> paramsOf(LambdaUpdateWrapper<?> wrapper) {
        wrapper.getSqlSegment();
        return flatten(wrapper.getParamNameValuePairs());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ArgumentCaptor<Wrapper<ApActivity>> updateCaptor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(Wrapper.class);
    }

    private static Date day(int year, int month, int day) {
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(year, month - 1, day);
        return c.getTime();
    }
}
