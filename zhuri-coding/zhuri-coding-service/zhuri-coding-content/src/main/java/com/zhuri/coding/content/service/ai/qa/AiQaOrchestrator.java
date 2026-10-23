package com.zhuri.coding.content.service.ai.qa;

import com.zhuri.coding.content.service.ai.AiAskService;
import com.zhuri.coding.content.service.ai.ArticleQaService;
import com.zhuri.coding.model.article.dtos.AiAnswerVo;
import org.springframework.stereotype.Service;

/**
 * 统一问答编排：只做 scope 分派，检索与生成仍由两条既有管线负责。
 */
@Service
public class AiQaOrchestrator {

    private final AiAskService aiAskService;
    private final ArticleQaService articleQaService;

    public AiQaOrchestrator(AiAskService aiAskService, ArticleQaService articleQaService) {
        this.aiAskService = aiAskService;
        this.articleQaService = articleQaService;
    }

    public AiAnswerVo stream(AiAskCommand command, AiStreamSink sink) {
        if (command.scope() == AiScope.ARTICLE) {
            return articleQaService.streamAskArticle(command, sink);
        }
        return aiAskService.streamAsk(command, sink);
    }
}
