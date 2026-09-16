package com.heima.content.service.ai.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * AiSkillRegistry 单元测试（P2 Skills：按 id 注册/检索）。
 */
@DisplayName("AiSkillRegistry（Skill 注册表）")
class AiSkillRegistryTest {

    private static final class FakeSkill implements AiSkill {
        private final String id;

        FakeSkill(String id) {
            this.id = id;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public String name() {
            return "fake-" + id;
        }

        @Override
        public String description() {
            return "";
        }

        @Override
        public Object execute(SkillContext ctx) {
            return id;
        }
    }

    @Test
    @DisplayName("按 id 检索命中 / 未知返回 empty / ids 覆盖齐全")
    void getByIdAndIds() {
        AiSkillRegistry registry = new AiSkillRegistry(List.of(new FakeSkill("a"), new FakeSkill("b")));

        Optional<AiSkill> a = registry.get("a");
        assertTrue(a.isPresent());
        assertEquals("a", a.get().id());
        assertFalse(registry.get("missing").isPresent());
        assertFalse(registry.get(null).isPresent());
        assertEquals(Set.of("a", "b"), registry.ids());
        assertEquals(2, registry.size());
    }

    @Test
    @DisplayName("空列表 / 重复 id 后者覆盖 不抛错")
    void emptyAndDuplicate() {
        assertEquals(0, new AiSkillRegistry(List.of()).size());
        AiSkillRegistry dup = new AiSkillRegistry(List.of(new FakeSkill("x"), new FakeSkill("x")));
        assertEquals(1, dup.size());
        assertEquals("fake-x", dup.get("x").get().name());
        assertEquals(0, new AiSkillRegistry(null).size());
    }
}