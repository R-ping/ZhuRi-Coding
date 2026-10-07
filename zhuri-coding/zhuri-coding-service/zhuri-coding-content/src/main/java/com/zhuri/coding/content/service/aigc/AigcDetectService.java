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

    /**
     * 人工复核放行：清除业务主表上的 AI 标记（{@code is_aigc=0, aigc_score=0}）。
     *
     * <p>口径与申诉终审 {@code ContentAppealServiceImpl#revertDisposal} 完全一致 ——
     * 同一个"撤销 AIGC 标注"语义不该有两套写法。所有下游处置（打赏关闭、RAG 排除、
     * 课程禁售、语义搜索过滤）都是读时判断 {@code is_aigc}，清掉标记即自动恢复，
     * 不需要逐个通知下游。
     *
     * <p>只清业务表、不动 {@code ap_aigc_record}：判定历史（分数、信号明细）要留着
     * 供阈值调优与追责，记录状态由调用方（复核服务）按自己的幂等闸口推进。
     *
     * <p><b>与检测链路相反，本方法不吞异常</b>：检测失败可以 fail-open（漏检一次的代价
     * 远小于阻断发布主链路），而复核是运营的显式动作 —— 返回成功就必须真的写成功，
     * 静默失败会让运营以为已经放行，作者那边却依旧被打赏关闭/禁售。
     *
     * @param type 内容类型：{@link com.zhuri.coding.model.aigc.pojos.AigcRecord} 的 TYPE_* 常量
     * @param id   内容主键
     * @throws IllegalArgumentException 类型未知或 id 为空
     * @throws IllegalStateException    内容主表对应行不存在（可能已被删除）
     */
    void clearAigcFlag(int type, Long id);
}
