package com.heima.content.service.ai;

/**
 * AI Prompt 版本注册表（P2-1）。
 *
 * <p>解决：prompt 硬编码在代码里 → 调整要改代码重新部署，无法回滚/灰度/归因。
 *
 * <p>模型（表 ap_ai_prompt，库 leadnews_article）：
 * <ul>
 *   <li><b>正式版</b>：enabled=1 且 rollout_percent=0 的行中 version 最大者（发布新版全量 = 插入更大 version 且 rollout=0 的行）；</li>
 *   <li><b>灰度版</b>：enabled=1 且 rollout_percent ∈ [1,99]，按 version 降序逐个尝试，
 *       {@code floorMod(userId,100) < rollout_percent} 命中即用（同一用户对同一 key 分流稳定）；</li>
 *   <li><b>代码兜底</b>：调用方传入的 static 常量作为 fallback（version 记 0）——DB 无行/查询失败/注册表关闭时生效，
 *       prompt 链路 fail-open，绝不阻断主流程。</li>
 * </ul>
 *
 * <p>实现要求：本地快照 + 周期懒刷新；DB 异常沿用旧快照。归因：version 写日志与 AiAnswerVo.promptVersions。
 */
public interface AiPromptRegistry {

    /**
     * 解析 prompt：灰度（userId 非空时）→ 正式版 → 代码兜底。
     *
     * @param key      注册表 key（如 ai_ask_system）
     * @param fallback 代码内置默认（调用类保留的 static 常量，兜底语义）
     * @param userId   灰度分流用；null（系统内部调用）直接走正式版
     */
    ResolvedPrompt resolve(String key, String fallback, Integer userId);

    /** 解析结果：content 为最终使用的 prompt；version=0 表示走了代码兜底 */
    final class ResolvedPrompt {
        public final String key;
        public final String content;
        public final int version;

        public ResolvedPrompt(String key, String content, int version) {
            this.key = key;
            this.content = content;
            this.version = version;
        }
    }
}
