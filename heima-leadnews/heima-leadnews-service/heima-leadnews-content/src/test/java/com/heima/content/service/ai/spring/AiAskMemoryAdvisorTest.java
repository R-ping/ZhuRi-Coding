package com.heima.content.service.ai.spring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * AiAsk 会话记忆（MessageChatMemoryAdvisor）接入单测。
 *
 * <p>验证：前端携带的 history 经请求级 MessageWindowChatMemory 预载后，
 * 最终提交给模型的 prompt 中历史轮次以真实 user/assistant 消息结构出现，
 * 且位于本轮问题之前（替代原先 buildUser 里手拼的「对话历史」文本块）。
 */
@DisplayName("AiAsk 会话记忆 MessageChatMemoryAdvisor 接入测试")
class AiAskMemoryAdvisorTest {

    private static final String CONVERSATION_ID = "aiask-test-conv";

    @Test
    @DisplayName("history 以真实消息结构注入且位于当前问题之前")
    void historyShouldBeInjectedBeforeCurrentQuestion() {
        AtomicReference<Prompt> captured = new AtomicReference<>();
        ChatModel model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenAnswer(inv -> {
            captured.set(inv.getArgument(0));
            return new ChatResponse(List.of(new Generation(new AssistantMessage("ok"))));
        });

        // 与 AiAskServiceImpl.buildConversationMemory 相同的预载方式
        MessageWindowChatMemory memory = MessageWindowChatMemory.builder().maxMessages(12).build();
        memory.add(CONVERSATION_ID, List.of(
            new UserMessage("我上一轮的问题"),
            new AssistantMessage("我上一轮的回答")));

        ChatClient client = ChatClient.builder(model)
            .defaultAdvisors(MessageChatMemoryAdvisor.builder(memory).build())
            .build();
        client.prompt().system("你是助手").user("当前问题")
            .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, CONVERSATION_ID))
            .call().content();

        List<Message> msgs = captured.get().getInstructions();
        // system + 历史 user + 历史 assistant + 当前 user
        boolean hasHistoryUser = msgs.stream()
            .anyMatch(m -> m instanceof UserMessage && m.getText().contains("我上一轮的问题"));
        boolean hasHistoryAssistant = msgs.stream()
            .anyMatch(m -> m instanceof AssistantMessage && m.getText().contains("我上一轮的回答"));
        assertTrue(hasHistoryUser, "历史 user 轮次应以真实消息注入");
        assertTrue(hasHistoryAssistant, "历史 assistant 轮次应以真实消息注入");

        // 顺序：历史消息在前，当前问题（最后一个 user 消息）在后
        int lastUserIdx = -1;
        for (int i = 0; i < msgs.size(); i++) {
            if (msgs.get(i) instanceof UserMessage) {
                lastUserIdx = i;
            }
        }
        assertTrue(lastUserIdx > 0, "应至少存在当前问题 user 消息");
        assertTrue(msgs.get(lastUserIdx).getText().contains("当前问题"),
            "最后一个 user 消息应是本轮的当前问题");
        List<String> texts = msgs.stream().map(Message::getText).toList();
        assertTrue(texts.indexOf("我上一轮的问题") < texts.lastIndexOf("当前问题"),
            "历史轮次必须位于当前问题之前");
    }

    @Test
    @DisplayName("无历史时 prompt 仅含 system + 当前问题，不注入额外消息")
    void shouldNotInjectWhenHistoryEmpty() {
        AtomicReference<Prompt> captured = new AtomicReference<>();
        ChatModel model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenAnswer(inv -> {
            captured.set(inv.getArgument(0));
            return new ChatResponse(List.of(new Generation(new AssistantMessage("ok"))));
        });

        MessageWindowChatMemory memory = MessageWindowChatMemory.builder().maxMessages(12).build();
        ChatClient client = ChatClient.builder(model)
            .defaultAdvisors(MessageChatMemoryAdvisor.builder(memory).build())
            .build();
        client.prompt().system("你是助手").user("当前问题")
            .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, CONVERSATION_ID))
            .call().content();

        List<Message> msgs = captured.get().getInstructions();
        assertEquals(2, msgs.size(), "空记忆时只应有 system + user 两条消息");
        assertTrue(msgs.stream().anyMatch(m -> m instanceof UserMessage));
    }
}