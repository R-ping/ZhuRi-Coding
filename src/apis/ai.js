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

/**
 * AI 发布预检（SSE 流式版）：后端按阶段推送进度，最终返回报告。
 * 使用原生 fetch + reader 手动解析 text/event-stream（依赖登录态，头部 accToken）。
 * data 行可能被 chunk 拆开，故自建字符串缓冲按换行拼接出完整行再解析。
 *
 * @param {Object} payload { title, content, coverImageUrl }
 * @param {Object} handlers { onStage(name,status), onDone(voJson), onError(text) }
 * @returns {Function} abort() 调用可中途取消本次流式请求
 */
export function precheckArticleStream(payload, handlers) {
    // 与 request 封装保持一致的鉴权头：accToken + store.state.accessToken
    const token = (store && store.state && store.state.accessToken) || ''
    const controller = typeof AbortController !== 'undefined' ? new AbortController() : null

    fetch('/content/api/v1/ai/precheck/stream', {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json; charset=UTF-8',
            'accToken': token
        },
        body: JSON.stringify({
            title: payload.title,
            content: payload.content,
            coverImageUrl: payload.coverImageUrl || null
        }),
        signal: controller ? controller.signal : undefined
    }).then((resp) => {
        if (!resp.ok) {
            throw new Error('HTTP ' + resp.status)
        }
        if (!resp.body || !resp.body.getReader) {
            throw new Error('当前浏览器不支持流式响应')
        }
        const reader = resp.body.getReader()
        const decoder = new TextDecoder()
        let buf = ''                  // 累积行缓冲，处理 data 被 chunk 拆分
        let currentEvent = null       // { name, data:[] } 待分发的事件

        // 处理单行：event:/data: 行写入 currentEvent；空行触发事件分发
        function handleLine(line) {
            if (line === '') {
                dispatchEvent()
                return
            }
            if (line.startsWith('event:')) {
                if (!currentEvent) currentEvent = { name: null, data: [] }
                currentEvent.name = line.slice(6).trim()
            } else if (line.startsWith('data:')) {
                if (!currentEvent) currentEvent = { name: null, data: [] }
                currentEvent.data.push(line.slice(5).trim())
            }
        }

        // 分发一条完整 SSE 事件
        function dispatchEvent() {
            if (!currentEvent) return
            const evtName = currentEvent.name || ''
            const payloadText = currentEvent.data.join('\n')
            currentEvent = null
            if (payloadText === '') return
            if (evtName === 'stage') {
                try {
                    const stage = JSON.parse(payloadText)
                    if (handlers && handlers.onStage) {
                        handlers.onStage(stage.stage, stage.status)
                    }
                } catch (e) {
                    /* 忽略无法解析的阶段事件 */
                }
            } else if (evtName === 'done') {
                let vo = {}
                try { vo = JSON.parse(payloadText) } catch (e) { vo = {} }
                if (handlers && handlers.onDone) handlers.onDone(vo)
            } else if (evtName === 'error') {
                if (handlers && handlers.onError) handlers.onError(payloadText)
            }
        }

        // 逐块读取并切分出行，交给 handleLine
        function pump() {
            return reader.read().then((res) => {
                if (res.done) {
                    // 流结束：处理残留未换行的数据，并触发最后一次事件分发
                    if (buf) {
                        handleLine(buf)
                        buf = ''
                    }
                    handleLine('')
                    return
                }
                buf += decoder.decode(res.value, { stream: true })
                let idx
                while ((idx = buf.indexOf('\n')) !== -1) {
                    const line = buf.slice(0, idx)
                    buf = buf.slice(idx + 1)
                    handleLine(line)
                }
                return pump()
            })
        }
        return pump()
    }).catch((err) => {
        // fetch 异常/取消（AbortError）等一律交给 onError 兜底展示
        if (handlers && handlers.onError) {
            handlers.onError((err && err.message) || '网络错误，AI 预检失败')
        }
    })

    // 返回 abort() 用于中途取消
    return function abort() {
        if (controller) controller.abort()
    }
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
