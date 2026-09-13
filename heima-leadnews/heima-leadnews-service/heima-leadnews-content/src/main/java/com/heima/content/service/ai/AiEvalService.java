package com.heima.content.service.ai;

import java.util.Map;

/**
 * AI 检索评测（RAG eval）
 *
 * <p>黄金问答对来自 classpath ai-eval/eval-questions.json（问题 + 期望召回文章 golden id）。
 * 跑 Recall@k：query 向量化 → pgvector 召回前 k → 计算命中 golden 占比；
 * 聚合各问题平均 recall，作为检索质量改动的回归基线（改 rewrite/rerank/阈值前后对比）。
 */
public interface AiEvalService {

    /** 运行评测（k=5 与 10），返回报告 Map（含每题命中与均值 recall） */
    Map<String, Object> runEval();

    /**
     * 答案级评测（生成侧）：对黄金问题真实跑一次问答，统计
     * ① 引用精确率（引用的文章里命中 golden 的比例）② 引用召回率（golden 被引用的比例）
     * ③ 未溯源率（疑似无资料支撑的句子占比，来自忠实度校验）。
     *
     * <p>成本提示：每题 = 1 次生成（+ 校验），故必须限量，且建议在 `ai.faithfulness.mode=sync` 下跑，
     * 避免 ask() 内部再异步校验一次造成重复计费。
     *
     * @param limit 参评问题数上限（服务端再收敛一次）
     */
    Map<String, Object> runAnswerEval(int limit);
}
