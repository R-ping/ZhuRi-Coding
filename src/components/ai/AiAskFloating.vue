<template>
    <div class="ai-ask-root">
        <!-- 悬浮球 -->
        <div class="ai-fab" :class="{ 'open': panelVisible }" @click="togglePanel">
            <span class="fab-icon" v-if="!panelVisible">AI</span>
            <span class="fab-icon" v-else>&#10005;</span>
        </div>

        <!-- 问答面板 -->
        <div class="ai-panel-mask" v-if="panelVisible" @click.self="closePanel">
            <div class="ai-panel">
                <div class="ai-header">
                    <div class="ai-title">
                        <span class="ai-logo">✦</span>
                        问社区 AI
                        <span class="ai-sub">基于社区文章回答 · 可溯源 · 记忆持久化</span>
                    </div>
                    <div class="ai-actions">
                        <span class="ai-clear" v-if="messages.length && !loading" @click="clearMemory">清空记忆</span>
                        <div class="ai-close" @click="closePanel">&#10005;</div>
                    </div>
                </div>

                <!-- 额度条：今日免费剩余 tokens + 已购 tokens + 充值入口（按 token 计费） -->
                <div class="ai-quota-bar" v-if="quotaLoaded">
                    <span class="qb-item">今日免费 <b>{{ formatTokens(freeTokens.remainToday) }}</b> tokens</span>
                    <span class="qb-item">已购 <b>{{ formatTokens(walletTokens) }}</b> tokens</span>
                    <span class="qb-link" @click.stop="goRecharge">去充值 ›</span>
                </div>

                <div class="ai-body" ref="body">
                    <div class="ai-tip" v-if="messages.length === 0 && !loading">
                        试试问我社区里讲过的内容，例如：<br>
                        “社区里有哪些讲 MySQL 优化的文章？”
                    </div>

                    <div v-for="(m, mi) in messages" :key="mi" class="msg-block">
                        <div class="msg q"><span class="tag-q">问</span>{{ m.q }}</div>
                        <div class="msg a">
                            <span class="tag-a">AI</span>
                            <div class="answer-body">
                                <template v-if="m.loading && !m.streaming && !m.answer">
                                    <span class="typing">正在检索社区文章并生成回答…</span>
                                </template>
                                <template v-else-if="m.streaming && !m.answer">
                                    <span class="typing">AI 正在思考…</span>
                                </template>
                                <template v-else-if="m.error">
                                    <span class="a-error">{{ m.error }}</span>
                                </template>
                                <template v-else>
                                    <div class="a-text" v-html="renderAnswer(m.answer, m.sources)" @click="onAnswerClick($event, mi)"></div>
                                    <div class="a-feedback" v-if="m.answer && !m.loading && !m.streaming && !m.error">
                                        <template v-if="!m.feedbackGiven">
                                            <span class="fb-label">这个回答有帮助吗？</span>
                                            <button type="button" class="fb-btn" @click="giveFeedback(mi, 1)">有帮助</button>
                                            <button type="button" class="fb-btn" @click="giveFeedback(mi, -1)">没帮助</button>
                                        </template>
                                        <span v-else class="fb-done">已反馈，感谢</span>
                                    </div>
                                    <div class="a-sources" v-if="m.sources && m.sources.length">
                                        <div class="src-title">参考来源（点击阅读原文）</div>
                                        <div class="src-item"
                                            v-for="(s, si) in m.sources" :key="s.articleId"
                                            :class="{ 'src-active': mi === activeMsg && si === activeSource }"
                                            @click="openArticle(s.articleId)">
                                            <span class="src-idx">[{{ si + 1 }}]</span>
                                            <div class="src-info">
                                                <div class="src-name">{{ s.title }}</div>
                                                <div class="src-meta">@{{ s.author }} · {{ s.likes || 0 }} 赞 · 相似度 {{ Math.round((s.similarity || 0) * 100) }}%</div>
                                            </div>
                                        </div>
                                    </div>
                                </template>
                            </div>
                        </div>
                    </div>
                </div>

                <div class="ai-mode-bar">
            <span class="mode-label">回答模式</span>
            <span class="mode-chip" :class="{ 'on': fastMode }" @click="fastMode = true">快速</span>
            <span class="mode-chip" :class="{ 'on': !fastMode }" @click="fastMode = false">深度</span>
            <span class="mode-hint">{{ fastMode ? '约 5s' : '更精准 · 约 15s' }}</span>
        </div>
        <!-- 额度用尽引导 -->
        <div class="ai-quota-banner" v-if="quotaExhausted">
            <span>今日免费额度与钱包额度已用尽，购买额度包后继续提问</span>
            <button type="button" class="qb-buy" @click="goRecharge">立即充值</button>
        </div>
        <div class="ai-footer">
                    <input
                        class="ai-input"
                        v-model="question"
                        placeholder="输入问题（限 200 字）"
                        maxlength="200"
                        :disabled="loading"
                        @keyup.enter="send" />
                    <button class="ai-send" :disabled="loading || !question.trim()" @click="send">
                        {{ loading ? '检索中…' : '发送' }}
                    </button>
                </div>
                <div class="ai-legal">回答由 AI 生成，请以原文为准 · 单用户 5 次/分钟</div>
            </div>
        </div>
    </div>
</template>

<script>
    import { askAi, getAiQuotaStatus, getAiConversation, clearAiConversation } from '@/apis/ai'
    import { toast } from '@/utils/toast'

    export default {
        name: 'AiAskFloating',
        data() {
            return {
                panelVisible: false,
                question: '',
                loading: false,
                messages: [],
                activeMsg: -1,
                activeSource: -1,
                fastMode: true,
                // 商业化额度状态（阶段3）：面板内展示 + 用尽引导（按 token 计费口径）
                quotaLoaded: false,
                freeTokens: { dailyLimit: 0, usedToday: 0, remainToday: 0 },
                walletTokens: 0,
                quotaExhausted: false
            }
        },
        methods: {
            /** token 数格式化：0.5万 / 12万 / 300万（大数用万，避免面板里出现一长串数字） */
            formatTokens(n) {
                const v = Number(n) || 0
                if (v >= 10000) {
                    const w = v / 10000
                    return (w >= 100 ? Math.round(w) : w.toFixed(1).replace(/\.0$/, '')) + '万'
                }
                return String(v)
            },
            /** 拉取额度总览：今日免费 tokens + 已购 tokens；未登录静默忽略 */
            loadQuota() {
                getAiQuotaStatus().then(res => {
                    if (res && res.code === 200 && res.data) {
                        this.quotaLoaded = true
                        const ft = res.data.freeTokens
                        // token 单一口径（原「次数」维度已下线，后端不再返回 freeQuota / walletBalance）
                        if (ft) {
                            this.freeTokens = Object.assign({ dailyLimit: 0, usedToday: 0, remainToday: 0 }, ft)
                            this.walletTokens = Number(res.data.walletTokenBalance) || 0
                        }
                        // 免费与已购双双用尽才在面板提示充值（免费额度未满时不做干扰）
                        this.quotaExhausted = (this.freeTokens.remainToday <= 0) && this.walletTokens <= 0
                    }
                }).catch(() => {
                    // 未登录 / 服务异常：保持无额度条，不影响问答主流程
                })
            },
            /** 跳转 AI 额度中心（未登录先弹登录） */
            goRecharge() {
                if (!this.$store.state.accessToken) {
                    this.$store.commit('SHOW_LOGIN_MODAL')
                    return
                }
                this.$router.push('/user/ai/quota')
            },
            togglePanel() {
                if (this.panelVisible) {
                    this.closePanel()
                    return
                }
                if (!this.$store.state.accessToken) {
                    this.$store.commit('SHOW_LOGIN_MODAL')
                    return
                }
                this.panelVisible = true
                this.loadQuota()
                this.restoreConversation()
            },
            closePanel() {
                this.panelVisible = false
                this.activeMsg = -1
                this.activeSource = -1
            },
            /** 打开面板时恢复持久化会话（Memory 持久化：刷新/换设备后上下文不丢） */
            restoreConversation() {
                if (this.messages.length || !this.$store.state.accessToken) return
                this.loading = true
                getAiConversation().then(res => {
                    this.loading = false
                    if (res && res.code === 200 && Array.isArray(res.data) && res.data.length) {
                        const msgs = []
                        let pendingQ = null
                        res.data.forEach(t => {
                            if (!t || !t.content) return
                            if (t.role === 'user') {
                                pendingQ = String(t.content).slice(0, 200)
                            } else if (t.role === 'assistant' && pendingQ != null) {
                                msgs.push({ q: pendingQ, answer: String(t.content), sources: [], loading: false, streaming: false })
                                pendingQ = null
                            }
                        })
                        this.messages = msgs
                        this.$nextTick(this.scrollBottom)
                    }
                }).catch(() => {
                    this.loading = false
                })
            },
            /** 清空持久化会话记忆（服务端 Redis + 本地消息） */
            clearMemory() {
                clearAiConversation().then(() => {
                    this.messages = []
                    this.loading = false
                    this.quotaExhausted = false
                    toast('会话记忆已清空', 2)
                }).catch(() => {
                    toast('清空失败，请稍后重试', 2)
                })
            },
            send() {
                const q = (this.question || '').trim()
                if (!q || this.loading) return
                this.messages.push({ q: q, loading: true, answer: '', sources: [], streaming: false })
                this.question = ''
                this.loading = true
                this.scrollBottom()
                if (this.fastMode) {
                    this.streamAsk(q)
                    return
                }
                askAi(q, 5, false, this.buildHistory()).then(res => {
                    const last = this.messages[this.messages.length - 1]
                    last.loading = false
                    if (res && res.code === 200 && res.data) {
                        last.answer = res.data.answer || ''
                        last.sources = res.data.sources || []
                        if (!last.sources.length) {
                            last.answer = last.answer || '知识库暂未检索到相关内容。'
                        }
                        // 回答成功：刷新额度条（本次已消耗 1 次）
                        this.loadQuota()
                    } else {
                        last.error = (res && res.message) || 'AI 服务暂不可用，请稍后再试'
                    }
                    this.loading = false
                    this.$nextTick(this.scrollBottom)
                }).catch(err => {
                    const last = this.messages[this.messages.length - 1]
                    last.loading = false
                    // 3301 额度用尽：显示引导横幅（错误文案来自后端，不带编码展示）
                    if (err && err.code === 3301) {
                        last.error = (err.message && String(err.message).replace(/^\[\d+\]\s*/, '')) || '今日额度已用尽，请充值后继续'
                        this.loadQuota()
                    } else {
                        last.error = 'AI 服务暂不可用，请稍后再试'
                        toast('提问失败，请稍后重试', 2)
                    }
                    this.loading = false
                    this.$nextTick(this.scrollBottom)
                })
            },
            /** 组装最近对话上下文（<=6 轮，每条截断） */
            buildHistory() {
                const hist = []
                const msgs = this.messages.filter(m => !m.loading && !m.streaming && m.answer !== undefined)
                for (let i = Math.max(0, msgs.length - 6); i < msgs.length; i++) {
                    const m = msgs[i]
                    if (m.q) hist.push({ role: 'user', content: String(m.q).slice(0, 200) })
                    if (m.answer) hist.push({ role: 'assistant', content: String(m.answer).slice(0, 300) })
                }
                return hist
            },
            /** fast 模式：SSE 流式问答，逐字渲染 */
            streamAsk(q) {
                const last = this.messages[this.messages.length - 1]
                last.streaming = true
                const token = this.$store.state.accessToken
                fetch('/content/api/v1/ai/ask/stream', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json', 'accToken': token || '' },
                    body: JSON.stringify({ question: q, topK: 5, history: this.buildHistory() })
                }).then(resp => {
                    if (!resp.ok || !resp.body) {
                        throw new Error('HTTP ' + resp.status)
                    }
                    const reader = resp.body.getReader()
                    const decoder = new TextDecoder('utf-8')
                    let buffer = ''
                    let eventName = ''
                    const pump = () => reader.read().then(({ done, value }) => {
                        if (done) { this.finishStream(last); return }
                        buffer += decoder.decode(value, { stream: true })
                        // 按行解析 SSE
                        const lines = buffer.split('\n')
                        buffer = lines.pop()
                        for (const line of lines) {
                            const t = line.trim()
                            if (t.startsWith('event:')) { eventName = t.slice(6).trim(); continue }
                            if (t.startsWith('data:')) {
                                const data = t.slice(5).trim()
                                if (eventName === 'delta') {
                                    last.answer = (last.answer || '') + data
                                    this.scrollBottom()
                                } else if (eventName === 'done') {
                                    try {
                                        const vo = JSON.parse(data)
                                        last.sources = vo.sources || []
                                        last.latency = vo.latencyMs
                                    } catch (e) { /* ignore */ }
                                } else if (eventName === 'error') {
                                    // 流式额度用尽：后端发 "[3301] 文案"，识别后仅展示文案并触发引导
                                    if (typeof data === 'string' && data.indexOf('[3301]') === 0) {
                                        last.error = data.replace(/^\[\d+\]\s*/, '')
                                        this.loadQuota()
                                    } else {
                                        last.error = data || 'AI 服务暂不可用'
                                    }
                                }
                                eventName = ''
                            }
                        }
                        return pump()
                    }).catch(err => { last.error = '流式响应中断，请重试'; this.loading = false })
                    return pump()
                }).catch(err => {
                    last.error = 'AI 服务暂不可用（' + (err.message || '网络错误') + '）'
                    this.loading = false
                })
            },
            finishStream(last) {
                last.streaming = false
                last.loading = false
                this.loading = false
                if (!last.answer) last.error = last.error || '未获得回答，请重试'
                // 流式回答结束：刷新额度条
                this.loadQuota()
                this.scrollBottom()
            },
            /**
             * 回答渲染：先整体转义防 XSS，再把「有对应来源」的 [N] 包成可点击角标；
             * 无对应来源（sources 为空或序号越界）的 [N] 保留为普通文本、不渲染角标，
             * 纯文本照常展示不报错。因转义在前、标签在后注入，用户/模型文本不会成为 HTML。
             */
            renderAnswer(text, sources) {
                if (!text) return ''
                // 第一步：把文本视为纯文本转义（杜绝任何用户/模型内容被当作 HTML 解析）
                const safe = text
                    .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
                // 第二步：仅在序号有对应来源时才替换成可点角标，否则原样输出 [N]
                return safe.replace(/\[(\d{1,2})\]/g, (_, n) => {
                    const idx = parseInt(n, 10)
                    if (Array.isArray(sources) && sources[idx - 1]) {
                        return '<span class="a-ref" data-idx="' + n + '">[' + n + ']</span>'
                    }
                    return '[' + n + ']'
                })
            },
            /** 点击回答中的 [n] 引用：高亮并滚动到对应来源卡片 */
            onAnswerClick(event, msgIndex) {
                const refEl = event.target && event.target.closest ? event.target.closest('.a-ref') : null
                if (!refEl) return
                const idx = parseInt(refEl.getAttribute('data-idx'), 10)
                const m = this.messages[msgIndex]
                if (!m || !m.sources || !m.sources[idx - 1]) return
                this.activeMsg = msgIndex
                this.activeSource = idx - 1
                this.$nextTick(() => {
                    const body = this.$refs.body
                    const target = body && body.querySelector('.src-active')
                    if (target) {
                        body.scrollTop = target.offsetTop - body.offsetTop - 80
                    }
                })
            },
            openArticle(articleId) {
                window.open('/content/article/' + articleId, '_blank')
            },
            /** 回答反馈（👍/👎 反馈闭环，幂等由后端保证） */
            giveFeedback(mi, fb) {
                const m = this.messages[mi]
                if (!m || m.feedbackGiven) return
                const token = this.$store.state.accessToken
                if (!token) {
                    this.$store.commit('SHOW_LOGIN_MODAL')
                    return
                }
                m.feedbackGiven = true
                fetch('/content/api/v1/ai/feedback', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json', accToken: token },
                    body: JSON.stringify({
                        feature: 'aiask_global',
                        sceneId: '',
                        question: String(m.q || '').slice(0, 200),
                        answer: String(m.answer || '').slice(0, 500),
                        feedback: fb
                    })
                }).then(r => r.json()).then(res => {
                    if (!res || res.code !== 200) m.feedbackGiven = false
                }).catch(() => { m.feedbackGiven = false })
            },
            scrollBottom() {
                const body = this.$refs.body
                if (body) body.scrollTop = body.scrollHeight
            }
        }
    }
</script>

<style scoped>
    .ai-fab {
        position: fixed;
        right: 28px;
        bottom: 60px;
        width: 52px;
        height: 52px;
        border-radius: 50%;
        background: linear-gradient(135deg, #4f7cff, #7c5cff);
        color: #fff;
        display: flex;
        align-items: center;
        justify-content: center;
        font-size: 16px;
        font-weight: 700;
        cursor: pointer;
        box-shadow: 0 6px 18px rgba(79, 124, 255, 0.4);
        z-index: 1200;
        transition: transform 0.2s;
    }
    .ai-fab:hover { transform: scale(1.06); }
    .ai-fab.open { background: #666; box-shadow: 0 4px 12px rgba(0, 0, 0, 0.25); }

    .ai-panel-mask {
        position: fixed;
        inset: 0;
        background: rgba(0, 0, 0, 0.32);
        z-index: 1199;
        display: flex;
        justify-content: flex-end;
        align-items: flex-end;
    }
    .ai-panel {
        width: 420px;
        height: min(640px, 84vh);
        background: #fff;
        border-radius: 14px 14px 0 0;
        box-shadow: 0 -6px 30px rgba(0, 0, 0, 0.18);
        display: flex;
        flex-direction: column;
        overflow: hidden;
    }
    .ai-header {
        display: flex;
        align-items: center;
        justify-content: space-between;
        padding: 14px 16px;
        border-bottom: 1px solid #f0f0f0;
    }
    .ai-title { font-size: 15px; font-weight: 600; color: #1f2329; }
    .ai-logo { color: #4f7cff; margin-right: 4px; }
    .ai-sub { font-size: 11px; color: #999; font-weight: 400; margin-left: 8px; }
    .ai-close { cursor: pointer; color: #999; font-size: 14px; padding: 2px 6px; }
    .ai-close:hover { color: #333; }
    .ai-actions { display: flex; align-items: center; gap: 6px; }
    .ai-clear { font-size: 12px; color: #86909c; cursor: pointer; padding: 2px 4px; }
    .ai-clear:hover { color: #d93026; }

    .ai-quota-bar {
        display: flex;
        align-items: center;
        gap: 10px;
        padding: 6px 16px;
        background: #f7f9ff;
        border-bottom: 1px solid #f0f0f0;
        font-size: 12px;
        color: #86909c;
    }
    .ai-quota-bar b { color: #4f7cff; font-weight: 600; }
    .qb-link {
        margin-left: auto;
        color: #4f7cff;
        cursor: pointer;
        font-weight: 600;
    }
    .qb-link:hover { text-decoration: underline; }

    .ai-quota-banner {
        display: flex;
        align-items: center;
        gap: 8px;
        padding: 6px 12px;
        background: #fff7e6;
        border-top: 1px solid #ffe6b3;
        font-size: 12px;
        color: #8a6d3b;
    }
    .qb-buy {
        flex: none;
        font-size: 12px;
        color: #fff;
        background: #ff9900;
        border: none;
        border-radius: 4px;
        padding: 2px 10px;
        cursor: pointer;
    }
    .qb-buy:hover { background: #e68a00; }

    .ai-body {
        flex: 1;
        overflow-y: auto;
        padding: 14px 16px;
        background: #fafbfc;
    }
    .ai-tip { color: #999; font-size: 13px; line-height: 1.8; text-align: center; padding: 48px 10px; }
    .msg-block { margin-bottom: 14px; }
    .msg { display: flex; gap: 8px; font-size: 13px; line-height: 1.7; }
    .msg.q { justify-content: flex-end; }
    .tag-q, .tag-a { flex: none; font-size: 11px; border-radius: 4px; padding: 2px 6px; height: fit-content; }
    .tag-q { background: #eef2ff; color: #4f7cff; }
    .tag-a { background: #f3e8ff; color: #7c5cff; }
    .msg.q { color: #1f2329; font-weight: 500; }
    .msg.q { max-width: 80%; }
    .msg.a { align-items: flex-start; }
    .answer-body { max-width: 88%; }
    .a-text { color: #1f2329; white-space: pre-wrap; word-break: break-word; }
    .a-ref { color: #4f7cff; font-weight: 600; cursor: pointer; margin: 0 1px; }
    .a-error { color: #d93026; }
    .typing { color: #999; }
    .a-sources { margin-top: 10px; border-top: 1px dashed #e5e6eb; padding-top: 8px; }
    .a-feedback { margin-top: 10px; display: flex; align-items: center; gap: 8px; }
    .a-feedback .fb-label { font-size: 12px; color: #86909c; }
    .a-feedback .fb-btn {
        font-size: 12px;
        color: #86909c;
        background: none;
        border: 1px solid #e5e6eb;
        border-radius: 10px;
        padding: 1px 10px;
        cursor: pointer;
    }
    .a-feedback .fb-btn:hover { color: #4f7cff; border-color: #4f7cff; }
    .a-feedback .fb-done { font-size: 12px; color: #86909c; }
    .src-title { font-size: 12px; color: #86909c; margin-bottom: 6px; }
    .src-item {
        display: flex; gap: 8px; padding: 7px 8px; border-radius: 8px; cursor: pointer;
        background: #fff; border: 1px solid #f0f0f0; margin-bottom: 6px; transition: all 0.15s;
    }
    .src-item:hover { border-color: #4f7cff; background: #f7f9ff; }
    .src-active { border-color: #4f7cff; background: #f0f4ff; }
    .src-idx { color: #4f7cff; font-weight: 600; flex: none; }
    .src-name { font-size: 13px; color: #1f2329; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    .src-meta { font-size: 11px; color: #86909c; margin-top: 2px; }

    .ai-mode-bar {
        display: flex;
        align-items: center;
        gap: 8px;
        padding: 6px 12px 0;
        background: #fff;
    }
    .mode-label { font-size: 11px; color: #999; margin-right: 2px; }
    .mode-chip {
        font-size: 12px; color: #666; padding: 1px 10px; border-radius: 10px;
        border: 1px solid #e0e0e0; cursor: pointer; line-height: 18px;
    }
    .mode-chip.on { color: #fff; background: #4f7cff; border-color: #4f7cff; }
    .mode-hint { font-size: 11px; color: #c0c4cc; }
    .ai-footer { display: flex; gap: 8px; padding: 10px 12px; border-top: 1px solid #f0f0f0; background: #fff; }
    .ai-input {
        flex: 1; height: 36px; border: 1px solid #e5e6eb; border-radius: 8px; padding: 0 12px;
        font-size: 13px; outline: none;
    }
    .ai-input:focus { border-color: #4f7cff; }
    .ai-send {
        height: 36px; padding: 0 18px; border: none; border-radius: 8px; background: #4f7cff;
        color: #fff; font-size: 13px; cursor: pointer;
    }
    .ai-send:disabled { background: #c9cdd4; cursor: not-allowed; }
    .ai-legal { text-align: center; font-size: 11px; color: #c0c4cc; padding: 6px; background: #fff; }

    @media (max-width: 768px) {
        .ai-panel { width: 100vw; height: 78vh; }
        .ai-fab { right: 16px; bottom: 100px; }
    }
</style>
