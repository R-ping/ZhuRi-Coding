import request from '@/common/request'
import store from '@/stores/store'

/** 社区 AI 问答：基于社区文章（RAG）回答，返回 answer + sources */
export function askAi(question, topK, fast, history) {
    return request.post('/content/api/v1/ai/ask', { question: question, topK: topK || 5, fast: !!fast, history: history || [] })
}

/** 获取本人持久化会话记忆（[{role, content}]，oldest→newest；刷新/换设备后恢复上下文） */
export function getAiConversation() {
    return request.get('/content/api/v1/ai/conversation')
}

/** 清空本人持久化会话记忆（服务端 Redis 与本地消息一并清除） */
export function clearAiConversation() {
    return request.del('/content/api/v1/ai/conversation')
}

/**
 * AI 发布预检：违规/质量分/建议/标签/摘要/相似预警
 *
 * 返回 data 中的 `tags`（推荐标签，字符串数组）与 `summary`（一句话摘要）会被发布会页
 * [src/pages/creator/publish/index.vue] 在预检成功后**自动回填**到发布表单（仅当字段为空，
 * 标签按 maxTags 与 labels≤20 字符校验收敛），作者仍可手动修改。
 */
export function precheckArticle(data) {
    return request.post('/content/api/v1/ai/precheck', {
        title: data.title,
        content: data.content,
        articleId: data.articleId || null,
        coverImageUrl: data.coverImageUrl || null
    })
}

// ========== AI 商业化闭环：额度查询 / 额度包充值（支付宝沙箱） ==========

/**
 * 查询 AI 额度总览（登录后调用）
 *
 * 计费口径为 **tokens**（不同功能 token 成本差异大，按次计费不公平也不可控）：
 * - freeTokens：今日免费 tokens 的额度/已用/剩余
 * - walletTokenBalance：已购 tokens 余额
 * - packages[code].tokenQuota：该套餐到账 tokens
 * - freeQuota / walletBalance：次数口径（历史兼容，每日次数闸门仍在生效，可作兜底展示）
 *
 * @returns data: { freeTokens:{dailyLimit,usedToday,remainToday}, walletTokenBalance:int,
 *                   freeQuota:{...}, walletBalance:int, packages:{code:{quota,priceFen,tokenQuota}} }
 */
export function getAiQuotaStatus() {
    return request.get('/content/api/v1/ai/quota/status')
}

/**
 * 创建额度包订单（后端 @RequestParam 接收，故走查询参数而非请求体）
 * @param {string} packageCode 套餐编码：q200 / q1000 / q5000
 * @returns data: { orderNo, packageCode, quota, amountFen }
 */
export function aiTopupCreate(packageCode) {
    return request.post('/content/api/v1/ai/topup/create', undefined, { packageCode })
}

/** 查询充值订单状态（本人）；data: { orderNo, status, amountFen, quotaAdded, payTradeNo, createTime } */
export function aiTopupStatus(orderNo) {
    return request.get('/content/api/v1/ai/topup/status', { orderNo })
}

/**
 * 拉取支付宝收银台 HTML（TEXT_HTML，内含表单自动跳转脚本）。
 * 该端点依赖登录态（accToken 请求头），window.open(url) 无法携带该头，
 * 必须 fetch 文本后由调用方写入新窗口 document，交由页面自动提交。
 */
export function fetchTopupPayHtml(orderNo) {
    const token = store.state.accessToken
    return fetch('/content/api/v1/ai/topup/page?orderNo=' + encodeURIComponent(orderNo), {
        method: 'GET',
        headers: { 'Content-Type': 'application/json; charset=UTF-8', 'accToken': token || '' }
    }).then(resp => {
        if (!resp.ok) {
            throw new Error('HTTP ' + resp.status)
        }
        return resp.text()
    })
}
