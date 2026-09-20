package com.zhuri.coding.content.service.article.processor;

import com.zhuri.coding.content.service.aigc.AigcDetectService;
import com.zhuri.coding.model.article.pojos.ApArticle;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * AIGC 内容诚信检测处理器（内容治理 Step4 · 2026-09-18 起并入审核责任链）
 *
 * <p><b>为什么放在链里、且必须在相似度处理器之前</b>：水文的处置之一是"不入 RAG 向量库"，
 * 而向量入库发生在 {@link SimilarityProcessor}。改造前检测与入库由发布事务回调里两个<b>并发</b>动作
 * 分别驱动（异步审核链 vs 同步检测），入库判断读到的是链起点的<b>实体快照</b>，
 * 存在"水文被写入向量库"的时序窗口。本处理器把检测<b>并入审核链</b>并
 * <b>把判定结果回填实体</b>，使"先判诚信、再决定入库"在同一链内串行且读到最新值。
 *
 * <p><b>回填语义（只升不降）</b>：仅在本次 L1 flagged 时回填 {@code isAigc=1}；
 * 未 flagged 时保持实体原值不动——与 {@code AigcDetectServiceImpl} 的既有语义一致
 * （L1 只负责"升标"，降标只由 L3 纠偏或人工申诉触发），避免误清已有标记。
 *
 * <p><b>失败语义：fail-open</b>——检测异常只记日志并继续后续处理器，绝不阻断发布、
 * 也不触发审核链重试（诚信标注属"治理增强"，不同于红线检测的 fail-closed 准入语义）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@org.springframework.core.annotation.Order(3)
public class AigcDetectProcessor implements ArticleAuditProcessor {

    private final AigcDetectService aigcDetectService;

    @Override
    public boolean process(ApArticle article, String content, AuditProcessorContext context) {
        if (content == null || content.isBlank()) {
            log.info("文章内容为空，跳过 AIGC 诚信检测, articleId={}", article.getId());
            return true;
        }
        try {
            AigcDetectService.AigcL1Outcome outcome = aigcDetectService.detectAndFlagArticle(article, content);
            if (outcome != null && outcome.flagged()) {
                // 回填实体：后续 SimilarityProcessor 读到此值才会跳过入库，而不是链起点的旧快照
                article.setIsAigc(1);
                log.info("[Aigc] 审核链内已标记疑似水文（后续不入向量库）, articleId={}, score={}",
                    article.getId(), outcome.l1Score());
            }
        } catch (Exception e) {
            // fail-open：诚信检测失败不影响发布
            log.warn("[Aigc] 审核链内诚信检测异常（fail-open 继续审核）, articleId={}", article.getId(), e);
        }
        return true;
    }
}
