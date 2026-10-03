package com.zhuri.coding.content.service.coding.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhuri.coding.content.mapper.article.ApArticleContentMapper;
import com.zhuri.coding.content.mapper.article.ApArticleMapper;
import com.zhuri.coding.content.mapper.coding.ApCodingQuestionMapper;
import com.zhuri.coding.content.service.ai.AiFeatures;
import com.zhuri.coding.content.service.ai.AiLlmGateway;
import com.zhuri.coding.content.service.ai.AiPromptRegistry;
import com.zhuri.coding.content.service.coding.CodingSupplyService;
import com.zhuri.coding.model.article.pojos.ApArticle;
import com.zhuri.coding.model.article.pojos.ApArticleContent;
import com.zhuri.coding.model.coding.dtos.CodingQuestionSubmitDTO;
import com.zhuri.coding.model.coding.pojos.ApCodingQuestion;
import com.zhuri.coding.model.common.dtos.ResponseResult;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;

/**
 * 题库供给实现（Coding 延展第一层）
 *
 * <p>两条来源共用同一套"结构校验 + 题干 MD5 查重"：</p>
 * <ul>
 *   <li>AI 生成：文章作者触发，题目来源标注文章，答错/解析可回流文章阅读；</li>
 *   <li>作者投稿：先本地结构校验，再走一次 AI 质检（题干清晰/答案唯一/选项互斥），
 *       质检不通过落库为"已驳回"并带原因，通过即上架。</li>
 * </ul>
 *
 * <p>AI 质检不可用（模型未装配/熔断/超时）时按"格式校验通过即放行"降级：
 * 练习产品不因质检依赖故障阻断作者投稿，结构校验已保证题目可判分。</p>
 */
@Slf4j
@Service
public class CodingSupplyServiceImpl implements CodingSupplyService {

    /** 出题次数限制 key 前缀（每日重置） */
    private static final String GEN_LIMIT_KEY_PREFIX = "coding:gen:daily:";
    /** 单用户每日出题次数上限 */
    private static final int GEN_DAILY_LIMIT = 5;
    /** 提供给模型的正文字符上限（控上下文与成本） */
    private static final int CONTENT_MAX_CHARS = 8000;
    /** 单次生成最多采纳题数 */
    private static final int MAX_QUESTIONS_PER_CALL = 3;

    /** 出题 prompt（注册表 key：coding_question_generate） */
    private static final String GENERATE_SYSTEM =
        "你是程序员社区题库出题人。阅读【文章正文】，从中提炼最多 3 道可考的选择题，要求：\n"
            + "1. 只考文章中真实讲到的知识点，禁止编造文章外的内容；\n"
            + "2. 题干 ≤120 字且独立成立（不出现“本文中提到”这类指代）；每题 4 个选项，互不重叠、干扰项合理；\n"
            + "3. 单选题 answer 为恰好 1 个正确选项下标，多选题 answer 为至少 2 个下标；\n"
            + "4. difficulty 按认知难度取 1（入门·识记）/2（进阶·理解辨析）/3（挑战·应用推理）；\n"
            + "5. tags 为 1~3 个知识点标签；explanation 用 1~2 句给出正确答案的依据。\n"
            + "只输出 JSON 数组，元素形如：\n"
            + "[{\"stem\":\"...\",\"questionType\":1,\"options\":[\"A\",\"B\",\"C\",\"D\"],"
            + "\"answer\":[0],\"explanation\":\"...\",\"difficulty\":1,\"tags\":[\"Redis\"]}]\n"
            + "不要输出任何其它文字。";

    /** 质检 prompt（注册表 key：coding_question_audit） */
    private static final String AUDIT_SYSTEM =
        "你是程序员题库质检员。审核给定的选择题，检查三点：\n"
            + "1. 题干是否清晰、无歧义、可独立作答；\n"
            + "2. 答案是否唯一正确、与选项不矛盾；\n"
            + "3. 选项之间是否互斥、无重复项或明显凑数项。\n"
            + "只输出 JSON：{\"pass\":true,\"reason\":\"\"} 或 {\"pass\":false,\"reason\":\"简短原因（≤50字）\"}，"
            + "不要输出任何其它文字。";

    @Autowired
    private ApCodingQuestionMapper questionMapper;

    @Autowired
    private ApArticleMapper articleMapper;

    @Autowired
    private ApArticleContentMapper contentMapper;

    @Autowired
    private AiLlmGateway llmGateway;

    @Autowired
    private StringRedisTemplate redisTemplate;

    /** Prompt 注册表（版本化 + 兜底；单测未注入时走代码常量） */
    @Autowired(required = false)
    private AiPromptRegistry promptRegistry;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public ResponseResult generateFromArticle(Integer userId, Long articleId) {
        if (articleId == null) {
            return ResponseResult.errorResult(400, "文章ID不能为空");
        }
        ApArticle article = articleMapper.selectById(articleId);
        if (article == null || !article.isPublished()) {
            return ResponseResult.errorResult(400, "文章不存在或未发布");
        }
        if (article.getAuthorId() == null || article.getAuthorId().longValue() != userId.longValue()) {
            return ResponseResult.errorResult(403, "只能为自己的文章出题");
        }
        // 每日次数上限（Redis 计数失败时按不限制放行，不阻断主流程）
        String limitKey = GEN_LIMIT_KEY_PREFIX + userId + ":" + LocalDate.now();
        Long used = null;
        try {
            used = redisTemplate.opsForValue().increment(limitKey);
            if (used != null && used == 1L) {
                redisTemplate.expire(limitKey, 26, TimeUnit.HOURS);
            }
        } catch (Exception e) {
            log.warn("出题次数计数失败（按不限制放行）: userId={}", userId, e);
        }
        if (used != null && used > GEN_DAILY_LIMIT) {
            return ResponseResult.errorResult(429, "今日出题次数已达上限（" + GEN_DAILY_LIMIT + " 次）");
        }

        String body = loadArticleBody(articleId);
        if (body == null || body.isBlank()) {
            return ResponseResult.errorResult(400, "文章正文为空，无法出题");
        }

        String raw = llmGateway.generateOrNull(AiFeatures.QUESTION_GENERATE,
            prompt("coding_question_generate", GENERATE_SYSTEM).content,
            "【文章标题】" + article.getTitle() + "\n【文章正文】\n" + body, null, null);
        if (raw == null || raw.isBlank()) {
            return ResponseResult.errorResult(500, "AI 生成失败，请稍后重试");
        }
        List<Draft> drafts = parseDrafts(raw);
        if (drafts.isEmpty()) {
            return ResponseResult.errorResult(400, "未能生成有效题目，请稍后重试或换一篇文章");
        }

        int inserted = 0;
        int skipped = 0;
        List<Map<String, Object>> list = new ArrayList<>();
        for (Draft draft : drafts) {
            String hash = stemHash(draft.stem);
            if (existsByHash(hash)) {
                skipped++;
                continue;
            }
            ApCodingQuestion entity = toEntity(draft, ApCodingQuestion.SOURCE_AI, articleId,
                userId.longValue(), ApCodingQuestion.STATUS_PUBLISHED, null);
            entity.setStemHash(hash);
            try {
                questionMapper.insert(entity);
                inserted++;
                Map<String, Object> item = new HashMap<>();
                item.put("id", entity.getId());
                item.put("stem", entity.getStem());
                item.put("questionType", entity.getQuestionType());
                item.put("difficulty", entity.getDifficulty());
                list.add(item);
            } catch (DuplicateKeyException e) {
                // 并发下同题干已被另一请求写入：按重复跳过
                skipped++;
            }
        }
        log.info("文章出题完成: userId={}, articleId={}, generated={}, inserted={}, skipped={}",
            userId, articleId, drafts.size(), inserted, skipped);

        Map<String, Object> data = new HashMap<>();
        data.put("generated", drafts.size());
        data.put("inserted", inserted);
        data.put("skipped", skipped);
        data.put("list", list);
        return ResponseResult.okResult(data);
    }

    @Override
    public ResponseResult submitQuestion(Integer userId, CodingQuestionSubmitDTO dto) {
        if (dto == null) {
            return ResponseResult.errorResult(400, "投稿内容不能为空");
        }
        Draft draft = new Draft();
        draft.stem = dto.getStem();
        draft.questionType = dto.getQuestionType();
        draft.options = dto.getOptions();
        draft.answer = dto.getAnswer();
        draft.explanation = dto.getExplanation();
        draft.difficulty = dto.getDifficulty();
        draft.tags = dto.getTags();
        String invalid = normalize(draft);
        if (invalid != null) {
            return ResponseResult.errorResult(400, invalid);
        }

        // 来源文章（可选）：填写时须为投稿人自己的已发布文章（题目标注来源与出题人）
        Long articleId = dto.getArticleId();
        if (articleId != null) {
            ApArticle article = articleMapper.selectById(articleId);
            if (article == null || !article.isPublished()) {
                return ResponseResult.errorResult(400, "来源文章不存在或未发布");
            }
            if (article.getAuthorId() == null || article.getAuthorId().longValue() != userId.longValue()) {
                return ResponseResult.errorResult(403, "来源文章须为自己的已发布文章");
            }
        }

        // 题干查重（全局唯一）
        String hash = stemHash(draft.stem);
        ApCodingQuestion existing = questionMapper.selectOne(new LambdaQueryWrapper<ApCodingQuestion>()
            .eq(ApCodingQuestion::getStemHash, hash).last("LIMIT 1"));
        if (existing != null) {
            if (existing.getStatus() != null && existing.getStatus() == ApCodingQuestion.STATUS_REJECTED) {
                return ResponseResult.errorResult(400, "该题干此前未通过质检，请修改题干后再提交");
            }
            return ResponseResult.errorResult(400, "已有相同题干的题目，无需重复投稿");
        }

        // 一次 AI 质检（不可用时按格式校验放行）
        AuditResult audit = audit(draft);
        if (!audit.pass) {
            ApCodingQuestion rejected = toEntity(draft, ApCodingQuestion.SOURCE_AUTHOR, articleId,
                userId.longValue(), ApCodingQuestion.STATUS_REJECTED, audit.reason);
            rejected.setStemHash(hash);
            try {
                questionMapper.insert(rejected);
            } catch (DuplicateKeyException e) {
                log.info("并发重复投稿, userId={}", userId);
            }
            return ResponseResult.errorResult(400, "题目未通过质检：" + audit.reason);
        }

        ApCodingQuestion entity = toEntity(draft, ApCodingQuestion.SOURCE_AUTHOR, articleId,
            userId.longValue(), ApCodingQuestion.STATUS_PUBLISHED, null);
        entity.setStemHash(hash);
        try {
            questionMapper.insert(entity);
        } catch (DuplicateKeyException e) {
            return ResponseResult.errorResult(400, "已有相同题干的题目，无需重复投稿");
        }

        Map<String, Object> data = new HashMap<>();
        data.put("id", entity.getId());
        data.put("status", ApCodingQuestion.STATUS_PUBLISHED);
        data.put("message", "投稿成功，题目已上架，感谢为题库添砖加瓦");
        return ResponseResult.okResult(data);
    }

    // ==================== 内部方法 ====================

    /** AI 质检：返回是否通过及原因；模型不可用/输出无法解析时 fail-open（按结构校验放行） */
    private AuditResult audit(Draft draft) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("stem", draft.stem);
        payload.put("questionType", draft.questionType);
        payload.put("options", draft.options);
        payload.put("answer", draft.answer);
        payload.put("explanation", draft.explanation);
        String raw = llmGateway.generateOrNull(AiFeatures.QUESTION_AUDIT,
            prompt("coding_question_audit", AUDIT_SYSTEM).content,
            "【题目】\n" + writeJson(payload), null, null);
        if (raw == null || raw.isBlank()) {
            log.warn("题目质检不可用，按格式校验放行: stem={}", truncate(draft.stem, 40));
            return new AuditResult(true, "");
        }
        try {
            String s = raw.trim();
            int start = s.indexOf('{');
            int end = s.lastIndexOf('}');
            if (start >= 0 && end > start) {
                JsonNode node = objectMapper.readTree(s.substring(start, end + 1));
                boolean pass = node.path("pass").asBoolean(false);
                String reason = node.path("reason").asText("");
                if (reason != null && reason.length() > 100) {
                    reason = reason.substring(0, 100);
                }
                return new AuditResult(pass, reason == null ? "" : reason);
            }
        } catch (Exception e) {
            log.warn("解析质检结果失败，按格式校验放行: {}", truncate(raw, 120));
        }
        return new AuditResult(true, "");
    }

    /** 解析出题 JSON 数组（容错：容忍代码围栏与前后杂文本），逐题校验，无效的丢弃 */
    private List<Draft> parseDrafts(String raw) {
        List<Draft> drafts = new ArrayList<>();
        try {
            String s = raw.trim();
            int start = s.indexOf('[');
            int end = s.lastIndexOf(']');
            if (start < 0 || end <= start) {
                return drafts;
            }
            JsonNode array = objectMapper.readTree(s.substring(start, end + 1));
            if (!array.isArray()) {
                return drafts;
            }
            for (JsonNode node : array) {
                if (drafts.size() >= MAX_QUESTIONS_PER_CALL) {
                    break;
                }
                Draft draft = fromNode(node);
                if (draft != null && normalize(draft) == null) {
                    drafts.add(draft);
                }
            }
        } catch (Exception e) {
            log.warn("解析生成题目失败: {}", truncate(raw, 120));
        }
        return drafts;
    }

    private Draft fromNode(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        Draft draft = new Draft();
        draft.stem = text(node, "stem");
        draft.questionType = node.path("questionType").isNumber()
            ? node.path("questionType").asInt() : ApCodingQuestion.TYPE_SINGLE;
        draft.options = stringList(node.get("options"));
        draft.answer = intList(node.get("answer"));
        draft.explanation = text(node, "explanation");
        draft.difficulty = node.path("difficulty").isNumber()
            ? node.path("difficulty").asInt() : ApCodingQuestion.DIFFICULTY_MEDIUM;
        List<String> tags = stringList(node.get("tags"));
        if (tags != null && !tags.isEmpty()) {
            draft.tags = String.join(",", tags);
        }
        return draft;
    }

    /**
     * 结构校验并归一化（生成与投稿共用）；返回错误信息，null 表示通过。
     * 归一化动作：去首尾空白、答案去重排序、标签裁剪为前 3 个。
     */
    private String normalize(Draft draft) {
        if (draft.stem == null || draft.stem.isBlank()) {
            return "题干不能为空";
        }
        draft.stem = draft.stem.trim();
        if (draft.stem.length() < 8 || draft.stem.length() > 500) {
            return "题干长度需在 8~500 字之间";
        }
        if (draft.questionType == null
            || (draft.questionType != ApCodingQuestion.TYPE_SINGLE
                && draft.questionType != ApCodingQuestion.TYPE_MULTIPLE)) {
            return "题型仅支持单选（1）或多选（2）";
        }
        if (draft.options == null || draft.options.size() < 2 || draft.options.size() > 8) {
            return "选项数量需在 2~8 个之间";
        }
        List<String> options = new ArrayList<>();
        for (String option : draft.options) {
            String text = option == null ? "" : option.trim();
            if (text.isEmpty() || text.length() > 200) {
                return "选项不能为空且单个不超过 200 字";
            }
            options.add(text);
        }
        draft.options = options;
        if (draft.answer == null || draft.answer.isEmpty()) {
            return "正确答案不能为空";
        }
        Set<Integer> answers = new LinkedHashSet<>();
        for (Integer index : draft.answer) {
            if (index == null || index < 0 || index >= options.size()) {
                return "正确答案下标超出选项范围";
            }
            answers.add(index);
        }
        if (draft.questionType == ApCodingQuestion.TYPE_SINGLE && answers.size() != 1) {
            return "单选题的正确答案只能有 1 个";
        }
        if (draft.questionType == ApCodingQuestion.TYPE_MULTIPLE && answers.size() < 2) {
            return "多选题的正确答案至少 2 个";
        }
        draft.answer = new ArrayList<>(answers);
        if (draft.explanation != null) {
            draft.explanation = draft.explanation.trim();
            if (draft.explanation.length() > 1000) {
                return "答案解析不超过 1000 字";
            }
            if (draft.explanation.isEmpty()) {
                draft.explanation = null;
            }
        }
        if (draft.difficulty == null
            || draft.difficulty < ApCodingQuestion.DIFFICULTY_EASY
            || draft.difficulty > ApCodingQuestion.DIFFICULTY_HARD) {
            return "难度仅支持 1（入门）/2（进阶）/3（挑战）";
        }
        draft.tags = normalizeTags(draft.tags);
        return null;
    }

    /** 标签归一化：逗号分隔、去空白、单项 ≤30 字、最多 3 个 */
    private String normalizeTags(String tags) {
        if (tags == null || tags.isBlank()) {
            return null;
        }
        List<String> normalized = new ArrayList<>();
        for (String raw : tags.split(",")) {
            String tag = raw == null ? "" : raw.trim();
            if (tag.isEmpty() || tag.length() > 30) {
                continue;
            }
            normalized.add(tag);
            if (normalized.size() >= 3) {
                break;
            }
        }
        return normalized.isEmpty() ? null : String.join(",", normalized);
    }

    private ApCodingQuestion toEntity(Draft draft, int sourceType, Long articleId,
                                      Long sourceUserId, int status, String rejectReason) {
        ApCodingQuestion entity = new ApCodingQuestion();
        entity.setStem(draft.stem);
        entity.setQuestionType(draft.questionType);
        entity.setOptions(writeJson(draft.options));
        entity.setAnswer(writeJson(draft.answer));
        entity.setExplanation(draft.explanation);
        entity.setDifficulty(draft.difficulty);
        entity.setTags(draft.tags);
        entity.setSourceType(sourceType);
        entity.setSourceArticleId(articleId);
        entity.setSourceUserId(sourceUserId);
        entity.setStatus(status);
        entity.setRejectReason(rejectReason);
        entity.setAnswerCount(0);
        entity.setCorrectCount(0);
        entity.setCreatedTime(new Date());
        entity.setUpdatedTime(new Date());
        return entity;
    }

    /** 题干 MD5（归一化：去空白 + 小写，避免空格/大小写差异绕过查重） */
    private static String stemHash(String stem) {
        String normalized = stem.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
        return DigestUtils.md5DigestAsHex(normalized.getBytes(StandardCharsets.UTF_8));
    }

    private boolean existsByHash(String hash) {
        return questionMapper.selectCount(new LambdaQueryWrapper<ApCodingQuestion>()
            .eq(ApCodingQuestion::getStemHash, hash)) > 0;
    }

    private String loadArticleBody(Long articleId) {
        ApArticleContent content = contentMapper.selectOne(new LambdaQueryWrapper<ApArticleContent>()
            .eq(ApArticleContent::getArticleId, articleId).last("LIMIT 1"));
        if (content == null || content.getContent() == null || content.getContent().isBlank()) {
            return null;
        }
        String body = content.getContent();
        return body.length() > CONTENT_MAX_CHARS ? body.substring(0, CONTENT_MAX_CHARS) : body;
    }

    /** Prompt 注册表解析（带 null 兜底，未注入时走代码常量） */
    private AiPromptRegistry.ResolvedPrompt prompt(String key, String fallback) {
        if (promptRegistry == null) {
            return new AiPromptRegistry.ResolvedPrompt(key, fallback, 0);
        }
        try {
            return promptRegistry.resolve(key, fallback, null);
        } catch (Exception e) {
            return new AiPromptRegistry.ResolvedPrompt(key, fallback, 0);
        }
    }

    private List<String> stringList(JsonNode node) {
        List<String> list = new ArrayList<>();
        if (node == null || !node.isArray()) {
            return list;
        }
        for (JsonNode item : node) {
            if (item != null && item.isTextual() && !item.asText().isBlank()) {
                list.add(item.asText().trim());
            }
        }
        return list;
    }

    private List<Integer> intList(JsonNode node) {
        List<Integer> list = new ArrayList<>();
        if (node == null || !node.isArray()) {
            return list;
        }
        for (JsonNode item : node) {
            if (item != null && item.isNumber()) {
                list.add(item.asInt());
            }
        }
        return list;
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || !value.isTextual() ? null : value.asText();
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "[]";
        }
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max);
    }

    /** 结构校验前的归一化草稿（生成与投稿共用） */
    private static final class Draft {
        private String stem;
        private Integer questionType;
        private List<String> options;
        private List<Integer> answer;
        private String explanation;
        private Integer difficulty;
        private String tags;
    }

    /** AI 质检结果 */
    private static final class AuditResult {
        private final boolean pass;
        private final String reason;

        private AuditResult(boolean pass, String reason) {
            this.pass = pass;
            this.reason = reason;
        }
    }
}