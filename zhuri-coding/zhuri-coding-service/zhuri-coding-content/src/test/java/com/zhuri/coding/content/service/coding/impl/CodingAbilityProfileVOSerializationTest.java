package com.zhuri.coding.content.service.coding.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhuri.coding.model.coding.vos.CodingAbilityProfileVO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 能力档案 VO 序列化契约测试（前后端字段名约定）
 *
 * <p>{@code publicVisible} 字段必须序列化为 JSON 的 {@code public}（分项开关），
 * 且不能同时输出内部字段名 {@code publicVisible} —— 前端按 {@code public} 判定"未公开"占位。</p>
 */
class CodingAbilityProfileVOSerializationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("序列化 - 分项开关字段名为 public，不出现 publicVisible")
    void testPublicFieldName() throws Exception {
        CodingAbilityProfileVO vo = new CodingAbilityProfileVO();
        vo.setBlocks(new CodingAbilityProfileVO.Blocks());
        vo.getBlocks().getDomain().setPublicVisible(true);
        vo.getBlocks().getStreak().setPublicVisible(false);

        String json = objectMapper.writeValueAsString(vo);

        assertTrue(json.contains("\"public\":true"));
        assertTrue(json.contains("\"public\":false"));
        assertFalse(json.contains("publicVisible"));
    }
}