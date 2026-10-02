package com.zhuri.coding.content.service.ai;

import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * 语义缓存的 prompt 版本签名（P1-4）。
 *
 * <p>把「生成该答案所用的 prompt 版本」编码成可比对字符串，供 {@link AiSemanticCacheService}
 * 在命中时校验：签名与当前生效版本不一致 → 答案由旧 prompt 生成，不可返回（evict 后回源）。
 *
 * <p><b>为什么只含 {@code ai_ask_system}</b>：问答三条路径（同步 / 流式 / fast）共用同一用户的
 * 缓存且会跨路径命中，而 rewrite/rerank 只在同步完整路径参与。若把它们纳入签名，流式/fast
 * 写入的行会被完整路径判为「版本不匹配」而反复误删，缓存直接失效。system prompt 是唯一直接
 * 决定答案文本的 prompt，作为签名维度既准确又三路可比；rewrite/rerank 变更对答案的影响
 * 已由「语料指纹 + TTL」兜底。
 *
 * <p><b>格式稳定性</b>：按 key 字典序拼接 {@code key@version}（如 {@code ai_ask_system@3}），
 * 与入参 Map 的实现/顺序无关；version=0 表示代码兜底版，同样计入（后续 DB 出现正式版时
 * 版本号变化 → 旧缓存正确失效）。空 Map 返回空串，缓存层按「无版本信息」处理（不做校验）。
 */
public final class PromptStamp {

    private PromptStamp() {
    }

    /**
     * 生成稳定签名。
     *
     * @param versions {promptKey: version}；为 null/空返回空串
     * @return 如 {@code ai_ask_system@3}；多 key 时按字典序以 {@code |} 连接
     */
    public static String of(Map<String, Integer> versions) {
        if (versions == null || versions.isEmpty()) {
            return "";
        }
        return new TreeMap<>(versions).entrySet().stream()
            .map(e -> e.getKey() + "@" + (e.getValue() == null ? 0 : e.getValue()))
            .collect(Collectors.joining("|"));
    }
}