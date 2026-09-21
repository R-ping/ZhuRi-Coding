package com.zhuri.coding.content.service.audit;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 内容治理「人工终审」审核员守卫。
 *
 * <p><b>为什么需要</b>：申诉终审（{@code allow} 解除折叠 / {@code allow} 撤销 AIGC 标注）会直接改变
 * 内容治理结论。原先该接口只校验"是否登录"，任何注册用户都能终审他人申诉 —— 等于绕过整个治理闭环。
 * 本类补上缺失的<b>授权</b>判定（原实现只有认证，没有授权）。
 *
 * <p><b>信任语义（fail-closed）</b>：与 {@code InternalAuthSigner} 保持一致的取舍 —— 白名单未配置时
 * 视为"无任何审核员"，所有终审请求一律拒绝。这样"漏配"只会让终审暂时不可用（可由运营发现并补配），
 * 而不会退化为"任意用户可终审"。
 *
 * <p><b>配置</b>：{@code audit.reviewer-user-ids}，逗号分隔的用户 id 列表，例如
 * {@code AUDIT_REVIEWER_USER_IDS=1001,1002}。后续若引入运营角色表，只需替换本类实现，
 * 调用方（Controller）无需改动。
 */
@Slf4j
@Component
public class AuditReviewerGuard {

    /** 审核员用户 id 白名单（不可变集合；空集表示未配置，此时一律拒绝） */
    private final Set<Integer> reviewerIds;

    public AuditReviewerGuard(@Value("${audit.reviewer-user-ids:}") String rawReviewerIds) {
        Set<Integer> ids = new LinkedHashSet<>();
        if (rawReviewerIds != null && !rawReviewerIds.isBlank()) {
            for (String part : rawReviewerIds.split(",")) {
                String text = part.trim();
                if (text.isEmpty()) {
                    continue;
                }
                try {
                    ids.add(Integer.valueOf(text));
                } catch (NumberFormatException e) {
                    // 忽略非法项但保留告警，避免一个手误导致整段配置静默失效
                    log.warn("[AuditReviewer] 忽略非法的审核员用户 id: {}", text);
                }
            }
        }
        this.reviewerIds = Collections.unmodifiableSet(ids);
        if (ids.isEmpty()) {
            log.warn("audit.reviewer-user-ids 未配置：申诉人工终审将全部拒绝（fail-closed）。"
                + "如需开放终审，请配置运营账号 id，例如 audit.reviewer-user-ids=1001,1002");
        } else {
            log.info("[AuditReviewer] 已配置 {} 个申诉终审审核员", ids.size());
        }
    }

    /**
     * 是否为授权审核员。
     *
     * @param userId 当前登录用户 id（来自网关透传的可信身份头）
     * @return true 表示可执行终审；白名单为空或 userId 为空一律 false
     */
    public boolean isReviewer(Integer userId) {
        return userId != null && reviewerIds.contains(userId);
    }

    /** 已配置的审核员数量（供健康检查/诊断使用） */
    public int reviewerCount() {
        return reviewerIds.size();
    }
}
