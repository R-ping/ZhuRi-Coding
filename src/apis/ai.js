import request from '@/common/request'

/** 社区 AI 问答：基于社区文章（RAG）回答，返回 answer + sources */
export function askAi(question, topK, fast, history) {
    return request.post('/content/api/v1/ai/ask', { question: question, topK: topK || 5, fast: !!fast, history: history || [] })
}

/** AI 发布预检：违规/质量分/建议/标签/摘要/相似预警 */
export function precheckArticle(data) {
    return request.post('/content/api/v1/ai/precheck', {
        title: data.title,
        content: data.content,
        articleId: data.articleId || null,
        coverImageUrl: data.coverImageUrl || null
    })
}
