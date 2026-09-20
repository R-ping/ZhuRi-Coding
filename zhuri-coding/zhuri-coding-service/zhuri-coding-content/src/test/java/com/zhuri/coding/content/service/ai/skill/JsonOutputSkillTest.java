package com.zhuri.coding.content.service.ai.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.zhuri.coding.model.article.dtos.AiPrecheckVo;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * JsonOutputSkill 单元测试（P2 结构化输出：容错 JSON 提取 + 预检 FINAL 的 Spring AI Bean 化）。
 *
 * <p>覆盖：干净 JSON / 带 markdown 与前后缀解释文本的容错提取；非法与 null 回落 null；
 * 完整 FINAL JSON Bean 化映射（含相似预警）；缺字段容错（默认值补齐）；非法 raw Bean 化回落 null。
 */
@DisplayName("JsonOutputSkill（结构化输出：容错提取 + Bean 化）")
class JsonOutputSkillTest {

    private final JsonOutputSkill skill = new JsonOutputSkill();

    /** 完整预检 FINAL JSON（snake_case，对齐模型输出） */
    private static final String FINAL_JSON = "{"
        + "\"is_violation\":false,\"violation_type\":\"\",\"violation_reason\":\"\","
        + "\"quality_score\":88,\"is_tech\":true,"
        + "\"suggestions\":[\"补充锁超时与续期策略\",\"给出可运行示例代码\"],"
        + "\"tags\":[\"Redis\",\"分布式锁\"],"
        + "\"summary\":\"讲解 Redis 分布式锁原理与落地要点\","
        + "\"similar_article_id\":100,\"similar_title\":\"旧文：Redis 分布式锁\",\"similarity\":0.9123}";

    // ==================== 容错 JSON 提取 ====================

    @Test
    @DisplayName("干净 JSON：原样解析为 JsonNode")
    void parseCleanJson() {
        JsonNode node = skill.parseOrNull("{\"a\":1,\"b\":2}");
        assertNotNull(node);
        assertEquals(1, node.path("a").asInt());
    }

    @Test
    @DisplayName("模型附加 markdown/解释文本：截取首个 { 到末个 } 仍可解析")
    void parseWrappedJson() {
        JsonNode node = skill.parseOrNull("```json\nFINAL: {\"is_violation\":false,\"tags\":[\"Redis\"]}\n```");
        assertNotNull(node);
        assertFalse(node.path("is_violation").asBoolean());
        assertEquals("Redis", node.path("tags").get(0).asText());
    }

    @Test
    @DisplayName("非法 JSON：返回 null（不抛错）")
    void parseInvalidReturnsNull() {
        assertNull(skill.parseOrNull("这不是 JSON"));
        assertNull(skill.parseOrNull("{a:b}"));
        assertNull(skill.parseOrNull(""));
        assertNull(skill.parseOrNull(null));
    }

    // ==================== 预检 FINAL JSON Bean 化 ====================

    @Test
    @DisplayName("完整 FINAL JSON Bean 化：LLM 字段全部映射（含相似预警）")
    void parsePrecheckBeanFullJson() {
        AiPrecheckVo vo = skill.parsePrecheckBeanOrNull(FINAL_JSON);
        assertNotNull(vo);
        assertFalse(vo.getViolation());
        assertEquals(88, vo.getQualityScore());
        assertTrue(vo.getTech());
        assertEquals(List.of("补充锁超时与续期策略", "给出可运行示例代码"), vo.getSuggestions());
        assertEquals(List.of("Redis", "分布式锁"), vo.getTags());
        assertEquals("讲解 Redis 分布式锁原理与落地要点", vo.getSummary());
        assertEquals(100L, vo.getSimilarArticleId());
        assertEquals("旧文：Redis 分布式锁", vo.getSimilarTitle());
        assertEquals(0.9123, vo.getSimilarity());
    }

    @Test
    @DisplayName("缺字段容错：is_violation 显式 false 时其余取默认（不抛错）")
    void parsePrecheckBeanPartialFields() {
        AiPrecheckVo vo = skill.parsePrecheckBeanOrNull("{\"is_violation\":true,\"quality_score\":70}");
        assertNotNull(vo);
        assertTrue(vo.getViolation());
        assertEquals(70, vo.getQualityScore());
        assertTrue(vo.getTech(), "is_tech 缺省时应为 true");
        assertTrue(vo.getSuggestions().isEmpty());
        assertNull(vo.getSimilarArticleId(), "缺 similar_article_id 不应填相似预警");
    }

    @Test
    @DisplayName("非法 raw：Bean 化返回 null（交由调用方回落旧路径）")
    void parsePrecheckBeanInvalid() {
        assertNull(skill.parsePrecheckBeanOrNull("FINAL: 这不是 JSON"));
        assertNull(skill.parsePrecheckBeanOrNull(null));
    }

    // ==================== 通用入口 ====================

    @Test
    @DisplayName("execute 通用入口：走容错提取返回 JsonNode")
    void executeReturnsParsedNode() {
        Object r = skill.execute(new AiSkill.SkillContext(null, null, null, null, null, "{\"ok\":true}"));
        assertTrue(r instanceof JsonNode);
        assertTrue(((JsonNode) r).path("ok").asBoolean());
        assertNull(skill.execute(null), "null context 不抛错，返回 null");
    }
}