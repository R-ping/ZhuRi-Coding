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

    /** 触发摘要压缩的消息数阈值（P2-3b：达到即把最早 COMPRESS_BATCH_MSGS 条压成 1 条摘要） */
    long COMPRESS_THRESHOLD_MSGS = 44;

    /** 单次压缩的消息条数（最早的 N 条 → 1 条摘要，压缩后总长回落约 N-1） */
    long COMPRESS_BATCH_MSGS = 20;

    /**
     * 读取用户持久化会话（oldest → newest）。
     *
     * @param userId 用户 id；null 或异常统一返回空列表（fail-open，不影响问答主流程）
     */
    List<Map<String, String>> load(Integer userId);

    /**
     * 记录一轮问答（user + assistant），并滚动裁剪至 {@link #MAX_PERSISTED_MSGS} 条、刷新 7 天 TTL；
     * 达到 {@link #COMPRESS_THRESHOLD_MSGS} 条时异步触发 LLM 摘要压缩（fail-open，不阻塞本轮响应）。
     */
    void appendTurn(Integer userId, String question, String answer);

    /**
     * 若会话长度达到压缩阈值则执行一次摘要压缩（异步）。
     *
     * <p>同步段只做「长度检查 + Redis 抢锁」，LLM 调用在后台线程执行；
     * 返回值表示是否提交了压缩任务（未达阈值 / 抢锁失败 / 开关关闭均返回 false）。
     *
     * @return true=已提交异步压缩任务
     */
    boolean compressIfNeeded(Integer userId);

    /** 清空用户会话记忆 */
    void clear(Integer userId);
}