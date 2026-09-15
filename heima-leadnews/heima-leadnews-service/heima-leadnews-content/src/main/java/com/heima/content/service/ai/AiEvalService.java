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
     * <p>评测条目支持 {@code expectNoAnswer=true}（无 golden，考察无答案拒绝能力）：
     * 真实跑一次问答，若答案<strong>有内容但引用为空</strong>则记一次拒绝成功（refusal hit）；
     * 此类条目不参与精确率/召回率打分，单独统计 refusalRate。答案为空视为降级跳过（不算命中，偏严格）。
     *
     * @param limit 参评问题数上限（服务端再收敛一次）
     */
    Map<String, Object> runAnswerEval(int limit);

    /**
     * 评测门禁：组合检索评测 + 答案级评测，按可配置阈值（ai.eval.gate.*，百分数口径与报告一致）判定
     * pass/fail，输出 {@code gate.pass + gate.checks[]} 逐项明细。
     *
     * <p>门禁语义（fail-closed）：任一检查不过即整体 fail；评测报告带 error 或召回有效题数
     * 低于 ai.eval.gate.min-cases 时按 0 分判定（评测跑不出来 ≠ 通过）。配置了对应评测集没有的
     * 样本类型（如无 expectNoAnswer 条目）时该项标记 skipped 并视为通过。
     *
     * <p>成本：全量向量化（每题 1 次 embedding）+ 最多 {@code MAX_ANSWER_CASES} 次生成，
     * 故调用方必须限流；建议仅在改 prompt / 调召回阈值 / 换模型等 RAG 变更后跑。
     */
    Map<String, Object> runGate();
}
