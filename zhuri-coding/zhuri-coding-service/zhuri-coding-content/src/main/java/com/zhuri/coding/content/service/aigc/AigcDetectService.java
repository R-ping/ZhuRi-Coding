package com.zhuri.coding.content.service.aigc;

import com.zhuri.coding.model.article.pojos.ApArticle;

/**
 * AIGC 水文检测服务（内容诚信治理，Step4）
 *
 * <p>定位"低智 AI 水文过滤器"：不反 AI、不追求学术级检测，
 * 打击的是"AI 水文冒充人写去赚打赏/卖课"与"低质 AI 文本污染 RAG 向量库"。
 *
 * <p>执行模型（两段式）：
 * 1. 同步 L1 统计快检（毫秒级，文本特征）→ 高分立即打标（打赏/向量库闸门即时生效）；
 * 2. 异步复核（L2 作者历史文风画像 + L3 LLM 复核）→ 确认/纠偏，防误伤。
 * 处置语义：只标不删（flagged 关闭打赏、不入 RAG、课程禁售），记录可审计、可申诉。
 *
 * <p><b>触发路径（2026-09-18 起）</b>：文章侧检测已并入<b>发布审核责任链</b>
 * （{@code AigcDetectProcessor}，Order 在相似度/向量入库之前），保证"先判诚信、再决定入库"在同一条链内串行；
 * 沸点与课程小节仍由其发布入口直接调用。本接口保留 {@link #detectAndFlagArticle(Long)} 作为按 id 检测的通用入口。
 */
public interface AigcDetectService {

    /**
     * L1 快检结果：分数 + 是否已打标（flagged）。
     *
     * <p>返回值用于审核链内<b>回填实体</b>——链上后续处理器（向量入库）读的是同一个
     * {@link ApArticle} 对象，回填后才能读到本次的最终判定，避免"快照过期导致水文入库"。
     */
    record AigcL1Outcome(int l1Score, boolean flagged) {
    }

    /** 文章发布后检测（按 id，内部查正文；通用/运维入口） */
    void detectAndFlagArticle(Long articleId);

    /**
     * 审核链内调用：直接用链上已有的实体与正文做 L1 快检（不重复查库），
     * 并把判定结果返回给调用方回填实体，供同链后续处理器读取最新值。
     *
     * @param article 链上文章实体（读 title/authorId/publishTime 等）
     * @param content 链上正文（由编排方一次性查出，避免重复 IO）
     * @return L1 结果；正文为空等无法检测时返回 null（调用方按"未判定"处理）
     */
    AigcL1Outcome detectAndFlagArticle(ApArticle article, String content);

    /** 沸点发布后检测 */
    void detectAndFlagPins(Long pinsId);

    /** 课程小节创建/更新后检测 */
    void detectAndFlagChapter(Long chapterId);
}
