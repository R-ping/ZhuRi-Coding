package com.zhuri.coding.notification.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("自动私信文案校验")
class AutoReplyContentGuardTest {

    private final AutoReplyContentGuard guard = new AutoReplyContentGuard();

    @Test
    @DisplayName("正常文案通过")
    void testPass() {
        assertTrue(guard.check("谢谢关注").passed());
        assertTrue(guard.check("感谢关注，欢迎常来看看\n有问题随时留言").passed());
    }

    @Test
    @DisplayName("空文案与纯空白按格式错误直接拒绝保存")
    void testBlank() {
        assertTrue(guard.check(null).blocking());
        assertTrue(guard.check("   ").blocking());
    }

    @Test
    @DisplayName("超过 100 字拒绝保存")
    void testTooLong() {
        AutoReplyContentGuard.Result r = guard.check("关".repeat(101));
        assertFalse(r.passed());
        assertTrue(r.blocking());
        assertTrue(r.reason().contains("100"));
    }

    @Test
    @DisplayName("超过 3 行拒绝保存")
    void testTooManyLines() {
        AutoReplyContentGuard.Result r = guard.check("一\n二\n三\n四");
        assertFalse(r.passed());
        assertTrue(r.blocking());
    }

    @Test
    @DisplayName("纯符号没有信息量，拒绝保存")
    void testOnlySymbols() {
        assertTrue(guard.check("！！！？？？").blocking());
    }

    @Test
    @DisplayName("链接与手机号按内容不合规驳回，不阻断保存")
    void testContactInfo() {
        AutoReplyContentGuard.Result url = guard.check("点这里 https://example.com 领取");
        assertFalse(url.passed());
        assertFalse(url.blocking());

        AutoReplyContentGuard.Result phone = guard.check("详情联系 13800138000");
        assertFalse(phone.passed());
        assertFalse(phone.blocking());

        assertFalse(guard.check("加微信 abc123 聊").passed());
    }

    @Test
    @DisplayName("同一字符连续刷屏被驳回")
    void testRepeat() {
        AutoReplyContentGuard.Result r = guard.check("谢谢关注" + "！".repeat(12));
        assertFalse(r.passed());
        assertFalse(r.blocking());
    }

    @Test
    @DisplayName("正常表情不算符号刷屏")
    void testEmoji() {
        assertEquals(true, guard.check("谢谢关注😀").passed());
    }
}
