package com.zhuri.coding.content.service.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 申诉终审审核员守卫（AuditReviewerGuard）单测。
 *
 * <p>该守卫是内容治理的**授权**边界：此前终审接口只校验"是否登录"，任何注册用户都能终审他人申诉。
 * 本测试重点守住 fail-closed 语义 —— 配置缺失时必须一律拒绝，而不是退化为"人人可审"。
 */
@DisplayName("申诉终审审核员守卫（AuditReviewerGuard）")
class AuditReviewerGuardTest {

    @Test
    @DisplayName("未配置白名单 → 一律拒绝（fail-closed）")
    void emptyConfigDeniesAll() {
        AuditReviewerGuard guard = new AuditReviewerGuard("");
        assertFalse(guard.isReviewer(1));
        assertFalse(guard.isReviewer(1001));
        assertEquals(0, guard.reviewerCount());

        // null 与空白同理
        assertFalse(new AuditReviewerGuard(null).isReviewer(1));
        assertFalse(new AuditReviewerGuard("   ").isReviewer(1));
    }

    @Test
    @DisplayName("userId 为 null → 拒绝")
    void nullUserDenied() {
        assertFalse(new AuditReviewerGuard("1001").isReviewer(null));
    }

    @Test
    @DisplayName("命中白名单 → 放行；未命中 → 拒绝")
    void whitelistMatch() {
        AuditReviewerGuard guard = new AuditReviewerGuard("1001,1002");

        assertTrue(guard.isReviewer(1001));
        assertTrue(guard.isReviewer(1002));
        assertFalse(guard.isReviewer(1003));
        assertEquals(2, guard.reviewerCount());
    }

    @Test
    @DisplayName("容忍空格、空项与重复项")
    void toleratesSpacesAndDuplicates() {
        AuditReviewerGuard guard = new AuditReviewerGuard(" 7 , ,8,7 ");

        assertTrue(guard.isReviewer(7));
        assertTrue(guard.isReviewer(8));
        assertEquals(2, guard.reviewerCount());
    }

    @Test
    @DisplayName("非法项被忽略，但合法项仍生效（不因一个手误整段失效）")
    void invalidItemIgnoredOthersKept() {
        AuditReviewerGuard guard = new AuditReviewerGuard("abc,1001,,2x");

        assertTrue(guard.isReviewer(1001));
        assertEquals(1, guard.reviewerCount());
    }
}
