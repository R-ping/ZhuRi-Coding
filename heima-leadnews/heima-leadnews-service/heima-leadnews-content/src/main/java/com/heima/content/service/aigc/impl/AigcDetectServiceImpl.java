package com.heima.content.service.aigc.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.heima.content.mapper.aigc.AigcRecordMapper;
import com.heima.content.mapper.article.ApArticleContentMapper;
import com.heima.content.mapper.article.ApArticleMapper;
import com.heima.content.mapper.course.ApCourseChapterMapper;
import com.heima.content.mapper.pins.ApPinsMapper;
import com.heima.content.service.aigc.AigcDetectService;
import com.heima.content.service.article.impl.ArticleEmbeddingServiceImpl;
import com.heima.model.aigc.pojos.AigcRecord;
import com.heima.model.article.pojos.ApArticle;
import com.heima.model.article.pojos.ApArticle.Status;
import com.heima.model.article.pojos.ApArticleContent;
import com.heima.model.article.pojos.ApArticleEmbedding;
import com.heima.model.course.pojos.ApCourseChapter;
import com.heima.model.pins.pojos.ApPins;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AIGC 水文检测实现（Step4 P0）。
 *
 * 信号体系：
 * L1 文本统计（突发度/重复度/模板化/实例锚点）——同步快检，毫秒级零成本；
 * L2 作者历史文风画像（本文字向量 vs 作者历史文章向量均值余弦，复用 pgvector 基建）——异步；
 * L3 LLM 复核（仅组合分达阈值才调用控成本；同族模型自检不可靠，仅作高置信确认/纠偏）。
 * 所有 AI/向量/DB 环节失败一律 fail-open（跳过该信号/维持现状），检测不阻塞发布主链路。
 */
@Slf4j
@Service
public class AigcDetectServiceImpl implements AigcDetectService {

    /** 中等疑似起点：>= 进入异步复核（L2/L3 成本闸门） */
    private static final int THRESHOLD_REVIEW = 45;
    /** 高疑似起点：>= 即 flagged（打标处置：关打赏/不入向量库/禁售） */
    private static final int THRESHOLD_FLAG = 70;
    /** L2 作者画像最少历史篇数（不足跳过该信号） */
    private static final int AUTHOR_HISTORY_MIN = 3;
    /** L2 作者画像最多历史篇数 */
    private static final int AUTHOR_HISTORY_MAX = 10;
    /** L3 复核输入截断（控 token） */
    private static final int REVIEW_CHARS = 4000;
    /** 检测方法版本 */
    private static final String METHOD_V1 = "stat_v1";

    /** L1 权重 */
    private static final double W_REPEAT = 0.30;
    private static final double W_TEMPLATE = 0.25;
    private static final double W_BURST = 0.25;
    private static final double W_ANCHOR = 0.20;
    /** L2 画像权重（弱信号占比低，防误伤） */
    private static final double W_AUTHOR = 0.30;

    private static final Pattern CODE_BLOCK = Pattern.compile("```[\\s\\S]*?```");
    private static final Pattern SENT_SPLIT = Pattern.compile("[。！？!?；;\\n]");
    private static final Pattern DIGIT = Pattern.compile("[0-9]+(\\.[0-9]+)?%?");
    private static final String[] TEMPLATE_WORDS = {
        "首先", "其次", "然后", "最后", "总之", "总而言之", "总的来说", "综上所述",
        "需要注意的是", "一方面", "另一方面", "此外", "同时", "通过以上", "可以看出",
        "本文", "本篇文章", "其实", "所以说", "举个例子"
    };

    @Autowired
    private AigcRecordMapper aigcRecordMapper;
    @Autowired
    private ApArticleMapper apArticleMapper;
    @Autowired
    private ApArticleContentMapper contentMapper;
    @Autowired
    private ApPinsMapper apPinsMapper;
    @Autowired
    private ApCourseChapterMapper courseChapterMapper;
    @Autowired
    private ArticleEmbeddingServiceImpl embeddingService;
    @Autowired
    private ChatModel chatModel;
    @Autowired
    @Qualifier("aiSseExecutor")
    private Executor aiSseExecutor;

    private final ObjectMapper objectMapper = new ObjectMapper();

    // ==================== 入口：同步 L1 快检 + 投递异步复核 ====================

    @Override
    public void detectAndFlagArticle(Long articleId) {
        if (articleId == null) {
            return;
        }
        try {
            ApArticle article = apArticleMapper.selectById(articleId);
            if (article == null) {
                return;
            }
            ApArticleContent ac = contentMapper.selectOne(new LambdaQueryWrapper<ApArticleContent>()
                .eq(ApArticleContent::getArticleId, articleId).last("LIMIT 1"));
            if (ac == null || ac.getContent() == null || ac.getContent().isBlank()) {
                return;
            }
            String title = article.getTitle() == null ? "" : article.getTitle();
            String text = title + "\n\n" + ac.getContent();
            Integer authorId = article.getAuthorId() != null ? article.getAuthorId().intValue() : null;
            int l1 = computeL1(text);
            persistRecord(AigcRecord.TYPE_ARTICLE, articleId, authorId, l1, "{}", l1 >= THRESHOLD_FLAG);
            if (l1 >= THRESHOLD_REVIEW) {
                submitReview(AigcRecord.TYPE_ARTICLE, articleId, authorId, title, text, l1);
            }
            if (l1 >= THRESHOLD_FLAG) {
                applyFlag(AigcRecord.TYPE_ARTICLE, articleId, true, l1);
                log.warn("[Aigc] 文章 L1 快检 flagged, articleId={}, score={}", articleId, l1);
            }
        } catch (Exception e) {
            log.error("[Aigc] 文章检测异常, articleId={}", articleId, e);
        }
    }

    @Override
    public void detectAndFlagPins(Long pinsId) {
        if (pinsId == null) {
            return;
        }
        try {
            ApPins pins = apPinsMapper.selectById(pinsId);
            if (pins == null || pins.getContent() == null || pins.getContent().isBlank()) {
                return;
            }
            String text = pins.getContent();
            Integer authorId = pins.getAuthorId() != null ? pins.getAuthorId().intValue() : null;
            int l1 = computeL1(text);
            persistRecord(AigcRecord.TYPE_PINS, pinsId, authorId, l1, "{}", l1 >= THRESHOLD_FLAG);
            if (l1 >= THRESHOLD_REVIEW) {
                submitReview(AigcRecord.TYPE_PINS, pinsId, authorId,
                    text.length() > 40 ? text.substring(0, 40) : text, text, l1);
            }
            if (l1 >= THRESHOLD_FLAG) {
                applyFlag(AigcRecord.TYPE_PINS, pinsId, true, l1);
                log.warn("[Aigc] 沸点 L1 快检 flagged, pinsId={}, score={}", pinsId, l1);
            }
        } catch (Exception e) {
            log.error("[Aigc] 沸点检测异常, pinsId={}", pinsId, e);
        }
    }

    @Override
    public void detectAndFlagChapter(Long chapterId) {
        if (chapterId == null) {
            return;
        }
        try {
            ApCourseChapter ch = courseChapterMapper.selectById(chapterId);
            if (ch == null || ch.getContent() == null || ch.getContent().isBlank()) {
                return;
            }
            String text = (ch.getTitle() == null ? "" : ch.getTitle()) + "\n\n" + ch.getContent();
            // 章节无向量库、无作者列 → 仅 L1（快检标注，禁止售卖由售卖接口按 is_aigc 拦截）
            int l1 = computeL1(text);
            persistRecord(AigcRecord.TYPE_CHAPTER, chapterId, null, l1, "{}", l1 >= THRESHOLD_FLAG);
            if (l1 >= THRESHOLD_FLAG) {
                applyFlag(AigcRecord.TYPE_CHAPTER, chapterId, true, l1);
                log.warn("[Aigc] 课程小节 L1 快检 flagged, chapterId={}, score={}", chapterId, l1);
            }
        } catch (Exception e) {
            log.error("[Aigc] 课程小节检测异常, chapterId={}", chapterId, e);
        }
    }

    // ==================== 异步复核（L2 画像 + L3 LLM） ====================

    private void submitReview(int type, Long id, Integer authorId, String title, String text, int l1) {
        try {
            CompletableFuture.runAsync(() -> deepReview(type, id, authorId, title, text, l1), aiSseExecutor);
        } catch (Exception e) {
            log.warn("[Aigc] 投递异步复核失败, type={}, id={}", type, id, e);
        }
    }

    private void deepReview(int type, Long id, Integer authorId, String title, String text, int l1) {
        try {
            Map<String, Object> signals = new LinkedHashMap<>();
            Integer authorScore = null;
            if (type == AigcRecord.TYPE_ARTICLE && authorId != null) {
                authorScore = computeAuthorComponent(authorId, id, text);
                if (authorScore != null) {
                    signals.put("author_cos", authorScore);
                }
            }
            int finalScore = combineScore(l1, authorScore);
            boolean flagged = finalScore >= THRESHOLD_FLAG;
            if (finalScore >= THRESHOLD_FLAG) {
                Integer llmScore = llmReview(title, text);
                signals.put("llm_verdict", llmScore == null ? "unavailable" : String.valueOf(llmScore));
                if (llmScore != null && llmScore < THRESHOLD_FLAG) {
                    // 模型判 normal → 纠偏清标（防误伤）
                    flagged = false;
                    finalScore = Math.min(finalScore, THRESHOLD_FLAG - 1);
                }
            }
            applyFlag(type, id, flagged, finalScore);
            upsertRecordScore(type, id, finalScore, signals, flagged);
            log.info("[Aigc] 复核完成, type={}, id={}, l1={}, final={}, flagged={}", type, id, l1, finalScore, flagged);
        } catch (Exception e) {
            log.warn("[Aigc] 异步复核异常, type={}, id={}", type, id, e);
        }
    }

    /** L2：新文与作者历史文章向量均值的偏离度 0-100（cos 高=延续个人风格 → 低分） */
    private Integer computeAuthorComponent(Integer authorId, Long excludeArticleId, String text) {
        try {
            List<ApArticle> history = apArticleMapper.selectList(new LambdaQueryWrapper<ApArticle>()
                .eq(ApArticle::getAuthorId, authorId.longValue())
                .eq(ApArticle::getStatus, Status.PUBLISHED.getCode())
                .eq(ApArticle::getIsAigc, 0)
                .ne(ApArticle::getId, excludeArticleId)
                .orderByDesc(ApArticle::getPublishTime)
                .last("LIMIT " + AUTHOR_HISTORY_MAX));
            if (history == null || history.size() < AUTHOR_HISTORY_MIN) {
                return null;
            }
            String sample = text.length() > 6000 ? text.substring(0, 6000) : text;
            double[] curEmb = embeddingService.generateEmbedding(sample);
            if (curEmb == null || curEmb.length == 0) {
                return null;
            }
            double[] avg = new double[curEmb.length];
            int cnt = 0;
            for (ApArticle a : history) {
                ApArticleEmbedding emb = embeddingService.getEmbedding(a.getId());
                if (emb == null || emb.getEmbedding() == null) {
                    continue;
                }
                double[] e = emb.getEmbedding();
                for (int i = 0; i < Math.min(avg.length, e.length); i++) {
                    avg[i] += e[i];
                }
                cnt++;
            }
            if (cnt == 0) {
                return null;
            }
            for (int i = 0; i < avg.length; i++) {
                avg[i] /= cnt;
            }
            double cos = cosine(curEmb, avg);
            return (int) Math.round((1.0 - Math.max(0.0, cos)) * 100.0);
        } catch (Exception e) {
            log.warn("[Aigc] L2 作者画像计算失败, authorId={}", authorId, e);
            return null; // fail-open
        }
    }

    /** L3：LLM 复核 → 模型疑似分（>=FLAG 视为可疑；null=不可用） */
    private Integer llmReview(String title, String text) {
        try {
            String sys = "你是内容诚信审核员。判断下面内容是否疑似\"AI 批量生成的水文\""
                + "（无个人经验、模板化空转、车轱辘话、信息密度低）。真人撰写但文风流畅不算；"
                + "含代码、数据、具体案例的技术教程不算。只输出 JSON："
                + "{\"verdict\":\"normal\"|\"suspicious\",\"score\":0-100,\"reason\":\"不超过40字理由\"}";
            String user = (title == null || title.isBlank() ? "" : "标题：" + title + "\n")
                + (text.length() > REVIEW_CHARS ? text.substring(0, REVIEW_CHARS) : text);
            String raw = org.springframework.ai.chat.client.ChatClient.builder(chatModel).build()
                .prompt().system(sys).user(user).call().content();
            if (raw == null || raw.isBlank()) {
                return null;
            }
            int start = raw.indexOf('{');
            int end = raw.lastIndexOf('}');
            if (start < 0 || end <= start) {
                return null;
            }
            JsonNode node = objectMapper.readTree(raw.substring(start, end + 1));
            String verdict = node.path("verdict").asText("");
            int score = node.path("score").asInt(50);
            log.info("[Aigc] LLM 复核 verdict={}, score={}", verdict, score);
            return "suspicious".equalsIgnoreCase(verdict)
                ? Math.max(score, THRESHOLD_FLAG)
                : Math.min(score, THRESHOLD_FLAG - 1);
        } catch (Exception e) {
            log.warn("[Aigc] LLM 复核失败", e);
            return null;
        }
    }

    private static double cosine(double[] a, double[] b) {
        double dot = 0, na = 0, nb = 0;
        int n = Math.min(a.length, b.length);
        for (int i = 0; i < n; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        if (na <= 0 || nb <= 0) {
            return 0;
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    private static int combineScore(int l1, Integer authorScore) {
        if (authorScore == null) {
            return l1;
        }
        return (int) Math.round(l1 * (1.0 - W_AUTHOR) + authorScore * W_AUTHOR);
    }

    // ==================== L1 文本统计 0-100 ====================

    private int computeL1(String raw) {
        try {
            String text = CODE_BLOCK.matcher(raw == null ? "" : raw).replaceAll(" ");
            String[] parts = SENT_SPLIT.split(text);
            List<String> sents = new ArrayList<>();
            for (String p : parts) {
                String t = p.replaceAll("[\\s\\r\\n#*`>\\[\\]]", "").trim();
                if (t.length() >= 6) {
                    sents.add(t);
                }
            }
            if (sents.size() < 3) {
                return 0; // 内容过短不做判定
            }
            int total = 0;
            for (String s : sents) {
                total += s.length();
            }
            double avg = total * 1.0 / sents.size();
            double var = 0;
            for (String s : sents) {
                double d = s.length() - avg;
                var += d * d;
            }
            var /= sents.size();
            double std = Math.sqrt(var);
            // 句长整齐（std 小）→ AI 腔 → 高分；句长参差 → 人工 → 低分
            double burst = 100.0 * (1.0 - Math.min(1.0, std / Math.max(avg, 1.0) * 0.8));
            double repeat = repeatRatio(text);
            int tmpl = 0;
            for (String w : TEMPLATE_WORDS) {
                int idx = 0;
                while ((idx = text.indexOf(w, idx)) >= 0) {
                    tmpl++;
                    idx += w.length();
                }
            }
            double perThousand = tmpl * 1000.0 / Math.max(1, text.length());
            double template = Math.min(100.0, perThousand * 12.0);
            int anchors = countAnchors(text);
            double anchorScore = 100.0 * (1.0 - Math.min(1.0, anchors / 25.0));
            double score = W_REPEAT * repeat + W_TEMPLATE * template + W_BURST * burst + W_ANCHOR * anchorScore;
            return (int) Math.max(0, Math.min(100, Math.round(score)));
        } catch (Exception e) {
            log.warn("[Aigc] L1 统计异常", e);
            return 0;
        }
    }

    /** 4-gram 去重后比例 → 重复越多分越高 */
    private static double repeatRatio(String text) {
        try {
            String compact = text.replaceAll("[\\s，。！？、；：,.!?;:“”\"'()（）\\[\\]#*>`-]", "");
            if (compact.length() < 24) {
                return 30.0;
            }
            java.util.HashSet<String> set = new java.util.HashSet<>();
            int n = compact.length();
            for (int i = 0; i + 4 <= n; i += 4) {
                set.add(compact.substring(i, i + 4));
            }
            int grams = n / 4;
            if (grams <= 0) {
                return 0;
            }
            return 100.0 * (1.0 - set.size() * 1.0 / grams);
        } catch (Exception e) {
            return 30.0;
        }
    }

    /** 干货锚点：数字/代码块/URL 越多 → 实例密度高 */
    private static int countAnchors(String text) {
        int c = 0;
        Matcher m = DIGIT.matcher(text);
        while (m.find()) {
            c++;
        }
        int code = 0;
        int idx = text.indexOf("```");
        while (idx >= 0) {
            code++;
            idx = text.indexOf("```", idx + 3);
        }
        int url = 0;
        idx = text.indexOf("http");
        while (idx >= 0) {
            url++;
            idx = text.indexOf("http", idx + 4);
        }
        return c + code * 3 + url;
    }

    // ==================== 打标与记录落库 ====================

    /** 内容主表写/清标记（只标不删） */
    private void applyFlag(int type, Long id, boolean flagged, int score) {
        try {
            if (type == AigcRecord.TYPE_ARTICLE) {
                ApArticle u = new ApArticle();
                u.setId(id);
                u.setIsAigc(flagged ? 1 : 0);
                u.setAigcScore(score);
                u.setAigcCheckedAt(new Date());
                apArticleMapper.updateById(u);
            } else if (type == AigcRecord.TYPE_PINS) {
                ApPins u = new ApPins();
                u.setId(id);
                u.setIsAigc(flagged ? 1 : 0);
                u.setAigcScore(score);
                apPinsMapper.updateById(u);
            } else if (type == AigcRecord.TYPE_CHAPTER) {
                ApCourseChapter u = new ApCourseChapter();
                u.setId(id);
                u.setIsAigc(flagged ? 1 : 0);
                u.setAigcScore(score);
                courseChapterMapper.updateById(u);
            }
        } catch (Exception e) {
            log.warn("[Aigc] 打标更新失败, type={}, id={}", type, id, e);
        }
    }

    private void persistRecord(int type, Long id, Integer authorId, int score, String signals, boolean flagged) {
        try {
            AigcRecord old = findRecord(type, id);
            if (old != null) {
                old.setScore(score);
                old.setStatus(flagged ? AigcRecord.STATUS_FLAGGED : AigcRecord.STATUS_RECORD);
                old.setSignalsJson(signals);
                old.setUpdateTime(new Date());
                aigcRecordMapper.updateById(old);
                return;
            }
            AigcRecord rec = new AigcRecord();
            rec.setContentType(type);
            rec.setContentId(id);
            rec.setAuthorId(authorId == null ? 0 : authorId);
            rec.setScore(score);
            rec.setSignalsJson(signals);
            rec.setMethod(METHOD_V1);
            rec.setStatus(flagged ? AigcRecord.STATUS_FLAGGED : AigcRecord.STATUS_RECORD);
            rec.setCreateTime(new Date());
            rec.setUpdateTime(new Date());
            try {
                aigcRecordMapper.insert(rec);
            } catch (DuplicateKeyException e) {
                log.info("[Aigc] 记录并发重复, type={}, id={}", type, id);
            }
        } catch (Exception e) {
            log.warn("[Aigc] 记录落库失败, type={}, id={}", type, id, e);
        }
    }

    private void upsertRecordScore(int type, Long id, int score, Map<String, Object> signals, boolean flagged) {
        try {
            AigcRecord old = findRecord(type, id);
            if (old == null) {
                return;
            }
            old.setScore(score);
            old.setStatus(flagged ? AigcRecord.STATUS_FLAGGED : AigcRecord.STATUS_RECORD);
            old.setSignalsJson(objectMapper.writeValueAsString(signals));
            old.setUpdateTime(new Date());
            aigcRecordMapper.updateById(old);
        } catch (Exception e) {
            log.warn("[Aigc] 复核记录更新失败, type={}, id={}", type, id, e);
        }
    }

    private AigcRecord findRecord(int type, Long id) {
        return aigcRecordMapper.selectOne(new LambdaQueryWrapper<AigcRecord>()
            .eq(AigcRecord::getContentType, type)
            .eq(AigcRecord::getContentId, id)
            .last("LIMIT 1"));
    }
}
