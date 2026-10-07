package com.zhuri.coding.user.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.zhuri.coding.common.exception.BusinessException;
import com.zhuri.coding.model.common.enums.AppHttpCodeEnum;
import com.zhuri.coding.model.user.pojos.ApUser;
import com.zhuri.coding.user.mapper.ApUserMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 封禁闸口单测。
 *
 * <p>这一层的特点是"两种情况长得几乎一样"：<b>没有封禁记录</b>与<b>已到期</b>都必须放行，
 * 而<b>解析失败</b>也必须放行（fail-open，理由见类注释）。所以这里的断言重点在
 * "什么时候不拦" —— 拦错一次的代价是全站用户登不进来。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("封禁闸口（UserBanChecker）")
class UserBanCheckerTest {

    private static final Integer USER_ID = 1001;

    @Mock
    private ApUserMapper userMapper;

    @InjectMocks
    private UserBanChecker checker;

    @BeforeEach
    void setUp() {
        // ⚠️ 必须显式初始化：LambdaQueryWrapper 的 eq() 会立刻解析列名 → 读实体元信息，
        //    没有 MyBatis 会话时抛异常。这里的异常会被 assertNotBanned 的 fail-open 兜住，
        //    表现为"永远不拦人"——测试里看到的是"该抛的异常没抛"，很容易误判成逻辑写反了。
        //    另外：漏了这行时，只要同一个 JVM 里先跑过任何初始化过 ApUser 的测试类，本类就会"意外通过"，
        //    形成按类名/顺序才成立的假绿灯（曾经如此）。
        TableInfoHelper.initTableInfo(
            new MapperBuilderAssistant(new MybatisConfiguration(), ""), ApUser.class);
    }

    private void givenBan(Date until, String reason) {
        ApUser user = new ApUser();
        user.setId(USER_ID);
        user.setBanUntil(until);
        user.setBanReason(reason);
        when(userMapper.selectList(any())).thenReturn(List.of(user));
    }

    private static Date daysLater(int days) {
        return new Date(System.currentTimeMillis() + days * 86_400_000L);
    }

    private static Date permanent() {
        return Date.from(LocalDateTime.of(9999, 12, 31, 23, 59, 59)
            .atZone(ZoneId.systemDefault()).toInstant());
    }

    @Nested
    @DisplayName("放行的情况")
    class Allowed {

        @Test
        @DisplayName("从未被封禁 → 放行")
        void neverBanned() {
            givenBan(null, null);

            assertDoesNotThrow(() -> checker.assertNotBanned(USER_ID));
        }

        @Test
        @DisplayName("封禁已到期 → 放行（不需要定时任务提前解锁，比较一下就够）")
        void banExpired() {
            givenBan(daysLater(-1), "上次的违规");

            assertDoesNotThrow(() -> checker.assertNotBanned(USER_ID));
        }

        @Test
        @DisplayName("账号不存在 → 放行（后续流程自会因找不到用户而失败）")
        void userMissing() {
            when(userMapper.selectList(any())).thenReturn(List.of());

            assertDoesNotThrow(() -> checker.assertNotBanned(USER_ID));
        }

        @Test
        @DisplayName("userId 为空 → 直接放行，不查库")
        void nullUserId() {
            assertDoesNotThrow(() -> checker.assertNotBanned(null));
            verify(userMapper, never()).selectList(any());
        }

        @Test
        @DisplayName("查库抛异常 → 按未封禁放行（fail-open）：封禁校验不该让全站登不进来")
        void queryFailureIsFailOpen() {
            when(userMapper.selectList(any())).thenThrow(new RuntimeException("DB 连接断了"));

            assertDoesNotThrow(() -> checker.assertNotBanned(USER_ID));
        }
    }

    @Nested
    @DisplayName("拦截的情况")
    class Blocked {

        @Test
        @DisplayName("临时封禁 → 抛业务异常，且文案带原因、解封时间与剩余天数")
        void temporaryBan() {
            givenBan(daysLater(7), "连续发布违规内容");

            BusinessException e = assertThrows(BusinessException.class, () -> checker.assertNotBanned(USER_ID));

            assertEquals(AppHttpCodeEnum.USER_BANNED.getCode(), e.getCode());
            String msg = e.getMessage();
            // 被封的人登不进来、读不到站内信，这条报错是他唯一能拿到的告知，三样都必须有
            assertTrue(msg.contains("连续发布违规内容"), "必须说明原因：" + msg);
            assertTrue(msg.contains("解封时间"), "必须说明什么时候恢复：" + msg);
            assertTrue(msg.contains("约剩 7 天"), "剩余时间让当事人知道该等还是该申诉：" + msg);
            assertFalse(msg.contains("永久"), msg);
        }

        @Test
        @DisplayName("剩余不足一天按 1 天报 —— 说「剩 0 天」会被读成「已经解封了」")
        void lessThanOneDayReportsOneDay() {
            givenBan(new Date(System.currentTimeMillis() + 3_600_000L), "轻度违规");

            BusinessException e = assertThrows(BusinessException.class, () -> checker.assertNotBanned(USER_ID));

            assertTrue(e.getMessage().contains("约剩 1 天"), e.getMessage());
        }

        @Test
        @DisplayName("永久封禁 → 文案标「永久」且不出现解封时间（否则当事人会一直等）")
        void permanentBan() {
            givenBan(permanent(), "严重违规");

            BusinessException e = assertThrows(BusinessException.class, () -> checker.assertNotBanned(USER_ID));

            assertTrue(e.getMessage().contains("永久"), e.getMessage());
            assertFalse(e.getMessage().contains("解封时间"), e.getMessage());
        }

        @Test
        @DisplayName("没有理由时不留「原因：」这个空标签")
        void banWithoutReason() {
            givenBan(daysLater(3), null);

            BusinessException e = assertThrows(BusinessException.class, () -> checker.assertNotBanned(USER_ID));

            assertFalse(e.getMessage().contains("原因："), e.getMessage());
        }

        @Test
        @DisplayName("理由两端有空白也要清理后再拼进文案")
        void reasonIsTrimmed() {
            givenBan(daysLater(3), "  刷屏  ");

            BusinessException e = assertThrows(BusinessException.class, () -> checker.assertNotBanned(USER_ID));

            assertTrue(e.getMessage().contains("原因：刷屏。"), e.getMessage());
        }
    }

    @Nested
    @DisplayName("isBanned 查询口")
    class IsBanned {

        @Test
        @DisplayName("封禁中 → true；已到期 → false")
        void reflectsCurrentState() {
            givenBan(daysLater(2), "违规");
            assertTrue(checker.isBanned(USER_ID));

            givenBan(daysLater(-2), "违规");
            assertFalse(checker.isBanned(USER_ID));
        }

        @Test
        @DisplayName("查询失败按未封禁返回 false（与其他路径同一取舍）")
        void failOpen() {
            when(userMapper.selectList(any())).thenThrow(new RuntimeException("DB 挂了"));

            assertFalse(checker.isBanned(USER_ID));
        }

        @Test
        @DisplayName("userId 为空 → false，不查库")
        void nullUserId() {
            assertFalse(checker.isBanned(null));
            verify(userMapper, never()).selectList(any());
        }
    }

    @Nested
    @DisplayName("永久判定")
    class PermanentDetection {

        @Test
        @DisplayName("null / 近期时间 → 非永久；远期时间 → 永久")
        void byYear() {
            assertFalse(UserBanChecker.isPermanent(null));
            assertFalse(UserBanChecker.isPermanent(daysLater(3650)));
            assertTrue(UserBanChecker.isPermanent(permanent()));
        }

        @Test
        @DisplayName("阈值与写入侧一致：避免一边写远期时间、另一边不认，把永久封禁显示成还剩 290 万天")
        void thresholdMatchesWriterValue() {
            Date writerValue = permanent();
            int year = LocalDateTime.ofInstant(writerValue.toInstant(), ZoneId.systemDefault()).getYear();
            assertTrue(year >= UserBanChecker.PERMANENT_YEAR_THRESHOLD,
                "写入侧用的远期时间年份必须落在永久阈值内：" + year);
        }
    }
}
