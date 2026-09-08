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
                        <span class="ai-sub">基于社区文章回答 · 可溯源</span>
                    </div>
                    <div class="ai-close" @click="closePanel">&#10005;</div>
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
                                    <div class="a-text" v-html="renderAnswer(m.answer)" @click="onAnswerClick($event, mi)"></div>
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
    import { askAi } from '@/apis/ai'
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
                fastMode: true
            }
        },
        methods: {
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
            },
            closePanel() {
                this.panelVisible = false
                this.activeMsg = -1
                this.activeSource = -1
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
                    } else {
                        last.error = (res && res.message) || 'AI 服务暂不可用，请稍后再试'
                    }
                    this.loading = false
                    this.$nextTick(this.scrollBottom)
                }).catch(err => {
                    const last = this.messages[this.messages.length - 1]
                    last.loading = false
                    last.error = 'AI 服务暂不可用，请稍后再试'
                    this.loading = false
                    toast('提问失败，请稍后重试', 2)
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
                                    last.error = data || 'AI 服务暂不可用'
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
                this.scrollBottom()
            },
            /** 回答中的 [n] 引用 → 高亮序号（点击经事件委托定位来源） */
            renderAnswer(text) {
                if (!text) return ''
                return text
                    .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
                    .replace(/\[(\d{1,2})\]/g, '<span class="a-ref" data-idx="$1">[$1]</span>')
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
