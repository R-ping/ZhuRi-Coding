package com.heima.content.service.ai.skill;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * AI Skill 注册表：按 id 检索可复用技能（P2）。
 *
 * <p>装配：Spring 自动收集所有 {@link AiSkill} Bean（构造注入 List）；按 {@link AiSkill#id()} 唯一索引，
 * 重复 id 以 Map::merge 后者覆盖并告警。查询按 {@code Optional} 返回：未知 id 返回 empty（调用方决定降级）。
 */
@Component
public class AiSkillRegistry {

    private final Map<String, AiSkill> byId;

    public AiSkillRegistry(List<AiSkill> skills) {
        this.byId = (skills == null ? List.<AiSkill>of() : skills).stream()
            .collect(Collectors.toMap(AiSkill::id, Function.identity(),
                (a, b) -> {
                    org.slf4j.LoggerFactory.getLogger(AiSkillRegistry.class)
                        .warn("[AiSkillRegistry] 重复 skill id，后注册覆盖: id={}, old={}, new={}", a.id(), a.name(), b.name());
                    return b;
                }));
    }

    /** 按 id 检索；未知返回 empty */
    public Optional<AiSkill> get(String id) {
        return id == null ? Optional.empty() : Optional.ofNullable(byId.get(id));
    }

    /** 当前全部 skill id */
    public Set<String> ids() {
        return byId.keySet();
    }

    /** 已注册数量 */
    public int size() {
        return byId.size();
    }
}