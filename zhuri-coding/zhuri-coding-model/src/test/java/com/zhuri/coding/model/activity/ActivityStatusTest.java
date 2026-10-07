package com.zhuri.coding.model.activity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Calendar;
import java.util.Date;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 活动状态机单测。
 *
 * <p>盯住三件在真实数据上很容易错、错了又不容易被发现的事：
 * <ul>
 *   <li><b>按天比较，且结束当天仍算进行中</b>：{@code start_date / end_date} 是 DATE 列，
 *       若拿"今天 00:00"去和"现在 14:00"比，当天的活动会被提前结束一整天；</li>
 *   <li><b>可见性只有一条判据</b>：草稿与已下线必须为 false，其余三种为 true ——
 *       读路径靠的就是它，这里判错等于草稿直接外泄；</li>
 *   <li><b>未知取值 fail-closed</b>：库里一个拼错的状态不该换来任何可见性。</li>
 * </ul>
 */
@DisplayName("活动状态机（ActivityStatus）")
class ActivityStatusTest {

    /** 造一个"某天 00:00"的日期，与 DATE 列读出来的形态一致 */
    private static Date day(int year, int month, int day) {
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(year, month - 1, day);
        return c.getTime();
    }

    /** 造一个"某天某时"的时刻 */
    private static Date at(int year, int month, int day, int hour, int minute) {
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(year, month - 1, day, hour, minute, 0);
        return c.getTime();
    }

    @Nested
    @DisplayName("取值归一与白名单")
    class Normalize {

        @Test
        @DisplayName("大小写与前后空格都容忍（库里的 'Ongoing' 也算 ongoing）")
        void normalizeIsLenient() {
            assertEquals("ongoing", ActivityStatus.normalize("  Ongoing "));
            assertEquals("draft", ActivityStatus.normalize("DRAFT"));
            assertNull(ActivityStatus.normalize(null));
            assertNull(ActivityStatus.normalize("   "));
        }

        @Test
        @DisplayName("五个合法取值全部认得，其它一律不认（fail-closed）")
        void validityIsClosed() {
            for (String code : new String[]{"draft", "upcoming", "ongoing", "ended", "offline"}) {
                assertTrue(ActivityStatus.isValid(code), code);
            }
            assertFalse(ActivityStatus.isValid("published"));
            assertFalse(ActivityStatus.isValid("DELETED"));
            assertFalse(ActivityStatus.isValid(null));
        }
    }

    @Nested
    @DisplayName("C 端可见性")
    class Visibility {

        @Test
        @DisplayName("草稿与已下线不可见；即将开始/进行中/已结束可见")
        void onlyPublishedIsVisible() {
            assertFalse(ActivityStatus.isVisibleToClient(ActivityStatus.DRAFT));
            assertFalse(ActivityStatus.isVisibleToClient(ActivityStatus.OFFLINE));

            assertTrue(ActivityStatus.isVisibleToClient(ActivityStatus.UPCOMING));
            assertTrue(ActivityStatus.isVisibleToClient(ActivityStatus.ONGOING));
            // 已结束的仍然可见：历史活动要在列表里留档
            assertTrue(ActivityStatus.isVisibleToClient(ActivityStatus.ENDED));
        }

        @Test
        @DisplayName("认不出的取值、null、空串一律不可见（宁可少显示，不可多显示）")
        void unknownIsInvisible() {
            for (String code : new String[]{null, "", "   ", "archived", "PUBLISHED"}) {
                assertFalse(ActivityStatus.isVisibleToClient(code), String.valueOf(code));
                assertFalse(ActivityStatus.isPublished(code), String.valueOf(code));
            }
        }

        @Test
        @DisplayName("已发布集合恰好是三种，且顺序稳定（错误提示与测试断言依赖它）")
        void publishedSetIsStable() {
            assertEquals(3, ActivityStatus.publishedStatuses().size());
            assertEquals("upcoming,ongoing,ended",
                String.join(",", ActivityStatus.publishedStatuses()));
            assertEquals(5, ActivityStatus.allStatuses().size());
        }
    }

    @Nested
    @DisplayName("按日期推导阶段")
    class Phase {

        @Test
        @DisplayName("开始前是即将开始、区间内是进行中、结束后是已结束")
        void threePhases() {
            assertEquals(ActivityStatus.UPCOMING,
                ActivityStatus.phaseOf(day(2026, 10, 10), day(2026, 10, 20), at(2026, 10, 1, 9, 0)));
            assertEquals(ActivityStatus.ONGOING,
                ActivityStatus.phaseOf(day(2026, 10, 10), day(2026, 10, 20), at(2026, 10, 15, 9, 0)));
            assertEquals(ActivityStatus.ENDED,
                ActivityStatus.phaseOf(day(2026, 10, 10), day(2026, 10, 20), at(2026, 10, 21, 9, 0)));
        }

        @Test
        @DisplayName("边界按「天」算：开始当天、结束当天的任意时刻都算进行中")
        void boundariesAreWholeDays() {
            Date start = day(2026, 10, 10);
            Date end = day(2026, 10, 20);

            assertEquals(ActivityStatus.ONGOING, ActivityStatus.phaseOf(start, end, start),
                "开始当天 00:00 就该是进行中");
            assertEquals(ActivityStatus.ONGOING, ActivityStatus.phaseOf(start, end, at(2026, 10, 20, 23, 59)),
                "结束当天 23:59 仍是进行中 —— 按时刻比会让当天活动提前结束一整天");
            assertEquals(ActivityStatus.ENDED, ActivityStatus.phaseOf(start, end, day(2026, 10, 21)),
                "次日 00:00 才结束");
        }

        @Test
        @DisplayName("单日活动（start = end）当天全天进行中")
        void singleDayActivity() {
            Date oneDay = day(2026, 10, 10);
            assertEquals(ActivityStatus.ONGOING, ActivityStatus.phaseOf(oneDay, oneDay, at(2026, 10, 10, 0, 0)));
            assertEquals(ActivityStatus.ONGOING, ActivityStatus.phaseOf(oneDay, oneDay, at(2026, 10, 10, 23, 0)));
            assertEquals(ActivityStatus.UPCOMING, ActivityStatus.phaseOf(oneDay, oneDay, at(2026, 10, 9, 23, 0)));
            assertEquals(ActivityStatus.ENDED, ActivityStatus.phaseOf(oneDay, oneDay, day(2026, 10, 11)));
        }

        @Test
        @DisplayName("不传参照时刻时用当前时间，且不抛异常")
        void defaultsToNow() {
            assertEquals(ActivityStatus.ENDED,
                ActivityStatus.phaseOf(day(2000, 1, 1), day(2000, 1, 2)));
            assertEquals(ActivityStatus.UPCOMING,
                ActivityStatus.phaseOf(day(2999, 1, 1), day(2999, 1, 2)));
        }

        @Test
        @DisplayName("日期缺失返回 null（数据不完整，由调用方拒绝，而不是猜一个阶段出来）")
        void missingDatesYieldNull() {
            assertNull(ActivityStatus.phaseOf(null, day(2026, 10, 1), new Date()));
            assertNull(ActivityStatus.phaseOf(day(2026, 10, 1), null, new Date()));
            assertNull(ActivityStatus.phaseOf(day(2026, 10, 1), day(2026, 10, 2), null));
        }
    }

    @Nested
    @DisplayName("流转白名单")
    class Transition {

        @Test
        @DisplayName("只有草稿与已下线可以上线（已发布再点上线是空动作）")
        void onlyDraftAndOfflineCanPublish() {
            assertTrue(ActivityStatus.canPublish(ActivityStatus.DRAFT));
            assertTrue(ActivityStatus.canPublish(ActivityStatus.OFFLINE));
            assertTrue(ActivityStatus.canPublish("  OFFLINE "), "大小写与空格要容忍");

            assertFalse(ActivityStatus.canPublish(ActivityStatus.UPCOMING));
            assertFalse(ActivityStatus.canPublish(ActivityStatus.ONGOING));
            assertFalse(ActivityStatus.canPublish(ActivityStatus.ENDED));
            assertFalse(ActivityStatus.canPublish("garbage"));
        }

        @Test
        @DisplayName("三种已发布状态都能下线（已结束也可以，把历史活动从列表撤掉是真实需求）")
        void publishedCanOffline() {
            assertTrue(ActivityStatus.canOffline(ActivityStatus.UPCOMING));
            assertTrue(ActivityStatus.canOffline(ActivityStatus.ONGOING));
            assertTrue(ActivityStatus.canOffline(ActivityStatus.ENDED));

            assertFalse(ActivityStatus.canOffline(ActivityStatus.DRAFT));
            assertFalse(ActivityStatus.canOffline(ActivityStatus.OFFLINE));
            assertFalse(ActivityStatus.canOffline(null));
        }

        @Test
        @DisplayName("上线与下线互不重叠：可上线的不可下线、可下线的不可上线")
        void publishAndOfflineAreComplementary() {
            for (String code : ActivityStatus.allStatuses()) {
                assertFalse(ActivityStatus.canPublish(code) && ActivityStatus.canOffline(code),
                    code + " 不该同时允许上线与下线");
                assertTrue(ActivityStatus.canPublish(code) || ActivityStatus.canOffline(code),
                    code + " 两种情况都不允许，说明流转表漏了一个状态");
            }
            // 唯一允许"上下线"的覆盖关系：草稿只能上线、已发布只能下线
            assertTrue(ActivityStatus.canPublish(ActivityStatus.DRAFT));
            assertTrue(ActivityStatus.canPublish(ActivityStatus.OFFLINE));
            assertFalse(ActivityStatus.canPublish(ActivityStatus.ONGOING));
        }
    }

    @Test
    @DisplayName("状态中文名：五个取值都有名字，脏数据原样带出便于排查")
    void describeCoversAll() {
        assertEquals("草稿", ActivityStatus.describe(ActivityStatus.DRAFT));
        assertEquals("即将开始", ActivityStatus.describe(ActivityStatus.UPCOMING));
        assertEquals("进行中", ActivityStatus.describe(ActivityStatus.ONGOING));
        assertEquals("已结束", ActivityStatus.describe(ActivityStatus.ENDED));
        assertEquals("已下线", ActivityStatus.describe(ActivityStatus.OFFLINE));
        assertEquals("未设置", ActivityStatus.describe(null));
        // 认不出的取值原样带出便于排查脏数据；带出的是归一化之后的小写形态
        assertEquals("legacy", ActivityStatus.describe("LEGACY"));
    }
}
