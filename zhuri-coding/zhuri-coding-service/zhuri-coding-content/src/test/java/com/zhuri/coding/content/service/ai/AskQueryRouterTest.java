package com.zhuri.coding.content.service.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * AskQueryRouter 单元测试（P2 Routing：闲聊 / 技术问答 二分类）。
 *
 * <p>覆盖：技术词优先判 TECH；闲聊词 + 短句判 CHAT；闲聊词 + 长句仍 TECH（防误伤知识问答）；
 * 无词命中默认 TECH；null/空白 TECH；路由开关关闭一律 TECH；自定义词表替换默认词表。
 * 说明：@Value 字段在单测环境不注入，测试用 ReflectionTestUtils 显式装配。
 */
@DisplayName("AskQueryRouter（问答意图路由：二分类）")
class AskQueryRouterTest {

    private static AskQueryRouter router() {
        AskQueryRouter r = new AskQueryRouter();
        ReflectionTestUtils.setField(r, "enabled", true);
        ReflectionTestUtils.setField(r, "chatWordsCfg", "");
        ReflectionTestUtils.setField(r, "techWordsCfg", "");
        return r;
    }

    @Test
    @DisplayName("命中技术关键词：判 TECH（即使包含闲聊词）")
    void techWordWins() {
        AskQueryRouter r = router();
        assertEquals(AskQueryRouter.Intent.TECH, r.intent("你好，Redis 是怎么实现的"));
        assertEquals(AskQueryRouter.Intent.TECH, r.intent("怎么解决空指针异常"));
        assertEquals(AskQueryRouter.Intent.TECH, r.intent("Spring 面试常问什么"));
    }

    @Test
    @DisplayName("闲聊词 + 短句（≤20 字）：判 CHAT（不烧配额）")
    void shortChatHit() {
        AskQueryRouter r = router();
        assertEquals(AskQueryRouter.Intent.CHAT, r.intent("你好"));
        assertEquals(AskQueryRouter.Intent.CHAT, r.intent("谢谢你，辛苦了"));
        assertEquals(AskQueryRouter.Intent.CHAT, r.intent("在吗"));
    }

    @Test
    @DisplayName("闲聊词 + 长句：判 TECH（避免把技术讨论误判为寒暄）")
    void longSentenceWithChatWordStillTech() {
        AskQueryRouter r = router();
        assertEquals(AskQueryRouter.Intent.TECH, r.intent("你好，最近工作压力好大感觉每天加班好累啊不想上班"));
    }

    @Test
    @DisplayName("未命中任何词：默认 TECH（知识问答不误伤）")
    void defaultTech() {
        AskQueryRouter r = router();
        assertEquals(AskQueryRouter.Intent.TECH, r.intent("云原生到底是个啥"));
        assertEquals(AskQueryRouter.Intent.TECH, r.intent("扩展阅读指南"));
    }

    @Test
    @DisplayName("null / 空白输入：一律 TECH")
    void blankIsTech() {
        AskQueryRouter r = router();
        assertEquals(AskQueryRouter.Intent.TECH, r.intent(null));
        assertEquals(AskQueryRouter.Intent.TECH, r.intent("   "));
    }

    @Test
    @DisplayName("开关关闭（enabled=false）：聊天也判 TECH（完全走知识问答）")
    void disabledRoutesAllTech() {
        AskQueryRouter r = router();
        ReflectionTestUtils.setField(r, "enabled", false);
        assertEquals(AskQueryRouter.Intent.TECH, r.intent("你好"));
    }

    @Test
    @DisplayName("自定义词表：覆盖默认词表生效（默认词不再命中）")
    void customWordsOverrideDefaults() {
        AskQueryRouter r = router();
        ReflectionTestUtils.setField(r, "chatWordsCfg", "嗨,在么");
        ReflectionTestUtils.setField(r, "techWordsCfg", "压测");
        // 模拟 Spring 生命周期：字段注入完成后由 @PostConstruct 解析词表（词表改为启动时构建一次）
        r.init();
        // 自定义闲聊词命中（短句）
        assertEquals(AskQueryRouter.Intent.CHAT, r.intent("在么？"));
        // 自定义技术词优先
        assertEquals(AskQueryRouter.Intent.TECH, r.intent("压测怎么做"));
        // 默认词表被替换：默认"你好"不再判定闲聊
        assertEquals(AskQueryRouter.Intent.TECH, r.intent("你好"));
    }
}