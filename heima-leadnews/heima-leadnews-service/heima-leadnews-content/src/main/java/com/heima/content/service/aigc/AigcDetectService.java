package com.heima.content.service.aigc;

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
 */
public interface AigcDetectService {

    /** 文章发布后检测（正文来自 ap_article_content） */
    void detectAndFlagArticle(Long articleId);

    /** 沸点发布后检测 */
    void detectAndFlagPins(Long pinsId);

    /** 课程小节创建/更新后检测 */
    void detectAndFlagChapter(Long chapterId);
}
