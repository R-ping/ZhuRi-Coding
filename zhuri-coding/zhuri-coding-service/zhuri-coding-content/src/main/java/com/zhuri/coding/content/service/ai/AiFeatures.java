package com.zhuri.coding.content.service.ai;

/**
 * AI 功能标识（token 计量与成本归因的维度键）。
 *
 * <p>命名原则：与 {@code AiFeedback.FEATURE_*} 保持一致的语义粒度，
 * 但这里需要区分「同一次用户请求内部的多次模型调用」——例如一次完整问答会依次产生
 * {@link #REWRITE}、{@link #RERANK}、{@link #ASK} 三条用量，成本归因必须能拆开看，
 * 否则无法回答"到底是生成贵还是精排贵"。
 */
public final class AiFeatures {

    private AiFeatures() {
    }

    /** 完整 RAG 问答（改写 + 召回 + 精排 + 生成） */
    public static final String ASK = "ask";
    /** 快速问答（跳过改写/精排，单次召回 + 生成） */
    public static final String ASK_FAST = "ask_fast";
    /** 流式问答 */
    public static final String ASK_STREAM = "ask_stream";
    /** 单篇问答 */
    public static final String ASK_ARTICLE = "ask_article";
    /** 单篇文章 AI 摘要（详情页摘要卡；低成本高频场景，路由到 flash 快模型） */
    public static final String ARTICLE_SUMMARY = "article_summary";
    /** 查询改写（Query Rewrite） */
    public static final String REWRITE = "rewrite";
    /** 候选精排（LLM Rerank） */
    public static final String RERANK = "rerank";
    /** 答案忠实度复核（LLM 复核层） */
    public static final String FAITHFULNESS = "faithfulness";
    /** 发布预检/写作伴侣 */
    public static final String PRECHECK = "precheck";
    /** 创作复盘报告 */
    public static final String CREATOR_REPORT = "creator_report";
    /** 内容申诉初审 */
    public static final String APPEAL_AUDIT = "appeal_audit";
    /** AIGC 内容检测 */
    public static final String AIGC_DETECT = "aigc_detect";
    /** 多智能体专家 Worker（Agent 编排内部调用） */
    public static final String AGENT_EXPERT = "agent_expert";
    /** 文章评论温和治理（折叠判定） */
    public static final String COMMENT_AUDIT = "comment_audit";
    /** 沸点评论温和治理（折叠判定） */
    public static final String PINS_COMMENT_AUDIT = "pins_comment_audit";
    /** 会话记忆摘要压缩（长会话 token 膨胀治理，异步低频） */
    public static final String MEMORY_COMPRESS = "memory_compress";
    /**
     * 每日一题·题库供给：从文章反向生成题目。
     *
     * <p><b>已下线</b>：不再有调用方产生这条用量。常量保留是为了让历史 token 计量数据
     * 还能解释（成本归因按 feature 维度落库，删掉键值会让旧数据变成孤儿）。</p>
     */
    public static final String QUESTION_GENERATE = "question_generate";
    /** 每日一题·题库质检：作者投稿的一次性 AI 质检（题干清晰/答案唯一/选项互斥） */
    public static final String QUESTION_AUDIT = "question_audit";
    /** 模拟面试·提纲生成（开面时一次：主题 + 主问题 + 关键考点清单） */
    public static final String INTERVIEW_PLAN = "interview_plan";
    /** 模拟面试·逐轮追问判定（流式：控制行 FOLLOWUP/NEXT + 追问文本；实时性优先走 flash） */
    public static final String INTERVIEW_TURN = "interview_turn";
    /** 模拟面试·报告生成（逐题三维等级 + 覆盖清单 + 总评建议；质量优先走默认强模型） */
    public static final String INTERVIEW_REPORT = "interview_report";
    /** 每日一题·单题评估（简答：对照关键考点判 covered/missing + 结构与准确性等级） */
    public static final String DAILY_EVAL = "daily_eval";
    /** 其他/未归类（计量兜底，出现即说明有新调用点未归类） */
    public static final String OTHER = "other";
}
