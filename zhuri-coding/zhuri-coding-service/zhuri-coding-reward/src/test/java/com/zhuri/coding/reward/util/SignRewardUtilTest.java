package com.heima.reward.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SignRewardUtil 单元测试
 *
 * 覆盖 30 天周期奖励算法：校验各连续天数的奖励值、特殊奖励日判定、周期取模、
 * 以及非法入参（<=0）的兜底逻辑，保证签到奖励计算准确。
 */
class SignRewardUtilTest {

    // ==================== getRewardByContinuousDays ====================

    @Test
    @DisplayName("非法入参(0/负数)返回0")
    void testInvalidDaysReturnsZero() {
        assertEquals(0, SignRewardUtil.getRewardByContinuousDays(0));
        assertEquals(0, SignRewardUtil.getRewardByContinuousDays(-1));
        assertEquals(0, SignRewardUtil.getRewardByContinuousDays(-100));
    }

    @Test
    @DisplayName("周期首日奖励100")
    void testFirstDayReward() {
        assertEquals(100, SignRewardUtil.getRewardByContinuousDays(1));
    }

    @Test
    @DisplayName("特殊奖励日(第3天512)")
    void testSpecialDays() {
        assertEquals(512, SignRewardUtil.getRewardByContinuousDays(3));
        assertEquals(1024, SignRewardUtil.getRewardByContinuousDays(7));
        assertEquals(2048, SignRewardUtil.getRewardByContinuousDays(14));
        assertEquals(4096, SignRewardUtil.getRewardByContinuousDays(21));
        assertEquals(5120, SignRewardUtil.getRewardByContinuousDays(30));
    }

    @Test
    @DisplayName("普通日奖励700")
    void testNormalDays() {
        assertEquals(700, SignRewardUtil.getRewardByContinuousDays(16));
        assertEquals(700, SignRewardUtil.getRewardByContinuousDays(29));
    }

    @Test
    @DisplayName("30天周期循环，第31天回到第1天奖励")
    void testCycleRepeat() {
        assertEquals(SignRewardUtil.getRewardByContinuousDays(1),
                SignRewardUtil.getRewardByContinuousDays(31));
        assertEquals(SignRewardUtil.getRewardByContinuousDays(14),
                SignRewardUtil.getRewardByContinuousDays(44));
        assertEquals(100, SignRewardUtil.getRewardByContinuousDays(31));
        assertEquals(2048, SignRewardUtil.getRewardByContinuousDays(44));
    }

    @Test
    @DisplayName("完整30天奖励表逐一校验")
    void testFullTable() {
        int[] expected = {
                100, 150, 512, 250, 300, 350, 1024, 450, 500, 550,
                600, 650, 700, 2048, 700, 700, 700, 700, 700, 700,
                4096, 700, 700, 700, 700, 700, 700, 700, 700, 5120
        };
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], SignRewardUtil.getRewardByContinuousDays(i + 1),
                    "第" + (i + 1) + "天奖励不符");
        }
    }

    // ==================== isSpecialDay ====================

    @Test
    @DisplayName("非法入参(0/负数)非特殊日")
    void testInvalidDaysNotSpecial() {
        assertFalse(SignRewardUtil.isSpecialDay(0));
        assertFalse(SignRewardUtil.isSpecialDay(-5));
    }

    @Test
    @DisplayName("高额日(>700)判定为特殊奖励日")
    void testIsSpecialDayTrue() {
        assertTrue(SignRewardUtil.isSpecialDay(7));   // 1024
        assertTrue(SignRewardUtil.isSpecialDay(14));  // 2048
        assertTrue(SignRewardUtil.isSpecialDay(21));  // 4096
        assertTrue(SignRewardUtil.isSpecialDay(30));  // 5120
    }

    @Test
    @DisplayName("普通奖励日(700)非特殊而低于700也非特殊")
    void testIsSpecialDayFalse() {
        assertFalse(SignRewardUtil.isSpecialDay(1));   // 100
        assertFalse(SignRewardUtil.isSpecialDay(16));  // 700
        assertFalse(SignRewardUtil.isSpecialDay(29));  // 700
    }

    @Test
    @DisplayName("周期循环后特殊日判定保持一致")
    void testCycleSpecialRepeat() {
        assertTrue(SignRewardUtil.isSpecialDay(37));   // 37 -> index 6 -> 1024
        assertFalse(SignRewardUtil.isSpecialDay(31));  // 31 -> index 0 -> 100
    }
}