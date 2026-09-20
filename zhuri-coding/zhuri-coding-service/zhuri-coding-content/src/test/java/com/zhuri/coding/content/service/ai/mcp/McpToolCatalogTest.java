package com.zhuri.coding.content.service.ai.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import com.zhuri.coding.content.service.ai.mcp.McpToolCatalog.McpToolInfo;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * McpToolCatalog 单元测试（P2-8：MCP 工具目录 fail-open 封装）。
 *
 * <p>覆盖：provider 未装配（null）→ 停用/空目录/null provider；正常装配 → 目录含 name+description 且
 * 可透传 provider；provider 读取抛异常 → fail-open（停用/空目录）；空 ToolCallback 定义被跳过。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("McpToolCatalog（MCP 工具目录 fail-open 封装）")
class McpToolCatalogTest {

    @Mock private SyncMcpToolCallbackProvider provider;

    @InjectMocks private McpToolCatalog catalog;

    @BeforeEach
    void setUp() {
        // 默认 allow any 即 null：enabled/catalog/providerOrNull 在无打桩时应安全返回
    }

    /** 构造一个仅实现接口的 ToolCallback（返回值固定，不触发 MCP 网络） */
    private ToolCallback callback(String name, String description) {
        final ToolDefinition def = ToolDefinition.builder()
            .name(name).description(description).inputSchema("{}").build();
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return def;
            }

            @Override
            public String call(String toolInput) {
                return "{}";
            }
        };
    }

    @Test
    @DisplayName("provider 未装配（null）：停用、目录为空、providerOrNull 返回 null")
    void providerMissingFailsOpen() {
        McpToolCatalog noProvider = new McpToolCatalog();

        assertFalse(noProvider.enabled());
        assertTrue(noProvider.catalog().isEmpty());
        assertNull(noProvider.providerOrNull());
    }

    @Test
    @DisplayName("正常装配：目录含 name+description，可用且透传同一 provider")
    void catalogListsTools() {
        when(provider.getToolCallbacks()).thenReturn(new ToolCallback[]{
            callback("search_files", "在 docs 目录中按关键字搜索文件"),
            callback("get_current_time", "返回指定时区的当前时间（毫秒精度）")
        });

        assertTrue(catalog.enabled());
        List<McpToolInfo> tools = catalog.catalog();
        assertEquals(2, tools.size());
        assertEquals("search_files", tools.get(0).name());
        assertEquals("在 docs 目录中按关键字搜索文件", tools.get(0).description());
        assertEquals("get_current_time", tools.get(1).name());
        assertSame(provider, catalog.providerOrNull(), "可用时应透传 provider 供探针使用");
    }

    @Test
    @DisplayName("provider 读取抛异常：fail-open 停用 / 空目录 / null，不向上抛")
    void providerThrowsFailsOpen() {
        when(provider.getToolCallbacks()).thenThrow(new RuntimeException("mcp down"));

        assertFalse(catalog.enabled());
        assertTrue(catalog.catalog().isEmpty());
        assertNull(catalog.providerOrNull());
    }

    @Test
    @DisplayName("工具定义为 null 的条目被跳过，不影响其余工具")
    void nullDefinitionSkipped() {
        when(provider.getToolCallbacks()).thenReturn(new ToolCallback[]{
            callback("a", "工具A"),
            null,
            new ToolCallback() {
                @Override
                public ToolDefinition getToolDefinition() {
                    return null;
                }

                @Override
                public String call(String toolInput) {
                    return "{}";
                }
            }
        });

        List<McpToolInfo> tools = catalog.catalog();
        assertEquals(1, tools.size(), "null 定义与非空但有 null 定义的条目都应跳过");
        assertEquals("a", tools.get(0).name());
    }
}