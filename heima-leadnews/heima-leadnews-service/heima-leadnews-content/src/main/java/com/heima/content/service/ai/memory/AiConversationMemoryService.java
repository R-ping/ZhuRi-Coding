package com.heima.content.service.ai.memory;

import java.util.List;
import java.util.Map;

/**
 * 会话记忆持久化服务（Memory & State 之「短期会话记忆」）。
 *
 * <p>将 AI 问答会话按用户持久化到 Redis，取代原先「仅靠前端携带 history」的内存态，
 * 实现跨刷新、跨设备的会话记忆：页面刷新后可直接 GET /ai/conversation 恢复上下文。
 *
 * <p>存储结构：Redis List，每个元素为 JSON 字符串 {@code {"role":"user|assistant","content":"..."}}。
 * 设计上使用 List 保证按序追加与滚动裁剪（LTRIM），免去 read-modify-write 的并发覆盖风险。
 */
public interface AiConversationMemoryService {

    /** Redis 保留的最大消息条数（约 30 轮问答），超出滚动淘汰最旧 */
    long MAX_PERSISTED_MSGS = 60;

    /**
     * 读取用户持久化会话（oldest → newest）。
     *
     * @param userId 用户 id；null 或异常统一返回空列表（fail-open，不影响问答主流程）
     */
    List<Map<String, String>> load(Integer userId);

    /**
     * 记录一轮问答（user + assistant），并滚动裁剪至 {@link #MAX_PERSISTED_MSGS} 条、刷新 7 天 TTL。
     */
    void appendTurn(Integer userId, String question, String answer);

    /** 清空用户会话记忆 */
    void clear(Integer userId);
}