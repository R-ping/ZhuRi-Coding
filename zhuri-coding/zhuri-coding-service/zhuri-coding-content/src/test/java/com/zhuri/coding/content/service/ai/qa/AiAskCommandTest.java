package com.zhuri.coding.content.service.ai.qa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.zhuri.coding.model.article.dtos.AiAskDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("统一问答命令归一（AiAskCommand）")
class AiAskCommandTest {

    @Test
    @DisplayName("缺省 scope/mode → global + fast")
    void defaultsToGlobalFast() {
        AiAskDto dto = new AiAskDto();
        dto.setQuestion("Redis 分布式锁怎么实现");

        AiAskCommand cmd = AiAskCommand.from(dto, 7);

        assertEquals(AiScope.GLOBAL, cmd.scope());
        assertEquals(AiMode.FAST, cmd.mode());
        assertEquals("normal", cmd.source());
        assertEquals(7, cmd.userId());
    }

    @Test
    @DisplayName("旧客户端 fast=false → deep")
    void legacyFastFlagMapsToDeep() {
        AiAskDto dto = new AiAskDto();
        dto.setQuestion("跨文章对比一下方案");
        dto.setFast(false);

        assertEquals(AiMode.DEEP, AiAskCommand.from(dto, 7).mode());
    }

    @Test
    @DisplayName("单篇深度请求归一为快速")
    void articleDeepNormalizedToFast() {
        AiAskDto dto = new AiAskDto();
        dto.setQuestion("这篇讲了什么");
        dto.setScope("article");
        dto.setMode("deep");
        dto.setArticleId(100L);

        AiAskCommand cmd = AiAskCommand.from(dto, 7);

        assertEquals(AiScope.ARTICLE, cmd.scope());
        assertEquals(AiMode.FAST, cmd.mode());
    }

    @Test
    @DisplayName("单篇缺少 articleId → 参数错误")
    void articleWithoutIdRejected() {
        AiAskDto dto = new AiAskDto();
        dto.setQuestion("这篇讲了什么");
        dto.setScope("article");

        assertThrows(IllegalArgumentException.class, () -> AiAskCommand.from(dto, 7));
    }

    @Test
    @DisplayName("空问题与超长问题被拒绝")
    void blankOrOverlongRejected() {
        AiAskDto blank = new AiAskDto();
        blank.setQuestion("   ");
        assertThrows(IllegalArgumentException.class, () -> AiAskCommand.from(blank, 7));

        AiAskDto overlong = new AiAskDto();
        overlong.setQuestion("q".repeat(201));
        assertThrows(IllegalArgumentException.class, () -> AiAskCommand.from(overlong, 7));
    }

    @Test
    @DisplayName("topK 收敛到 1~8")
    void topKClamped() {
        AiAskDto dto = new AiAskDto();
        dto.setQuestion("Redis 锁");
        dto.setTopK(99);
        assertEquals(8, AiAskCommand.from(dto, 7).topK());

        dto.setTopK(0);
        assertEquals(1, AiAskCommand.from(dto, 7).topK());
    }
}
