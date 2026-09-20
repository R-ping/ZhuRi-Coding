package com.zhuri.coding.content.service.ai.mcp;

import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * MCP 工具目录（P2-8）：对 Spring AI 自动装配的 {@link SyncMcpToolCallbackProvider} 做 fail-open 聚合，
 * 供探测端点（/mcp/tools、/mcp/ping）展示与调用社区 stdio server 暴露的外部工具。
 *
 * <p>MCP 连接由 starter 懒建立（首次 listTools 才拉起 npx 进程，应用启动不受影响）；本组件在
 * provider 未装配（如 spring.ai.mcp.client.enabled=false）或调用异常时一律返回空表 / false / null，
 * 保证 MCP 故障绝不阻塞主链路（与「AI 旁路不得阻断主链路」的全局口径一致）。
 */
@Slf4j
@Component
public class McpToolCatalog {

    /** MCP 工具轻量描述（对外目录项） */
    public record McpToolInfo(String name, String description) {
    }

    /** 由 spring-ai-autoconfigure-mcp-client 自动装配；未启用 MCP 时为 null（required=false） */
    @Autowired(required = false)
    private SyncMcpToolCallbackProvider mcpToolCallbacks;

    /** 是否已启用且存在可用 MCP 工具（异常 fail-open：返回 false） */
    public boolean enabled() {
        try {
            return mcpToolCallbacks != null && mcpToolCallbacks.getToolCallbacks().length > 0;
        } catch (Exception e) {
            log.warn("[McpToolCatalog] 可用性探测异常，fail-open", e);
            return false;
        }
    }

    /** 工具目录：name + description 轻量清单（未装配 / 异常返回空表，不向上抛） */
    public List<McpToolInfo> catalog() {
        if (mcpToolCallbacks == null) {
            return new ArrayList<>();
        }
        List<McpToolInfo> list = new ArrayList<>();
        try {
            for (ToolCallback cb : mcpToolCallbacks.getToolCallbacks()) {
                if (cb == null || cb.getToolDefinition() == null) {
                    continue;
                }
                list.add(new McpToolInfo(
                    cb.getToolDefinition().name(),
                    cb.getToolDefinition().description()));
            }
            return list;
        } catch (Exception e) {
            log.warn("[McpToolCatalog] 工具目录读取异常，fail-open 返回空", e);
            return new ArrayList<>();
        }
    }

    /** 供探测端点透传给 {@code AiLlmGateway.probeWithToolsOrNull}；未装配 / 异常返回 null */
    public ToolCallbackProvider providerOrNull() {
        try {
            return enabled() ? mcpToolCallbacks : null;
        } catch (Exception e) {
            return null;
        }
    }
}