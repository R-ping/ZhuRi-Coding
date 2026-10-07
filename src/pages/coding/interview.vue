<template>
    <div class="interview-page">
        <!-- 额度耗尽引导横幅（开面被拒 / 轮中 [3301] 中断） -->
        <div class="quota-banner" v-if="quotaBlocked">
            <span class="quota-banner-text">{{ quotaMessage }}</span>
            <span class="quota-banner-btn" @click="goQuota">去额度中心</span>
            <span class="quota-banner-close" @click="quotaBlocked = false">&#10005;</span>
        </div>

        <!-- 头部 -->
        <div class="interview-header">
            <div class="header-left">
                <h1 class="page-title">模拟面试</h1>
                <span class="page-sub">AI 面试官逐题追问 · 限时作答 · 结束后给你一份三维报告</span>
            </div>
            <div class="header-right">
                <button class="ghost-btn" @click="goCoding">每日一题</button>
                <button class="ghost-btn" @click="goAbility">能力档案</button>
            </div>
        </div>

        <!-- 未登录 -->
        <div v-if="!isLoggedIn" class="card state-card">
            <div class="state-title">登录后开始模拟面试</div>
            <div class="state-desc">面试消耗 AI 额度；对话与报告仅自己可见</div>
            <button class="primary-btn" @click="showLogin">登录 / 注册</button>
        </div>

        <template v-else>
            <div v-if="loading" class="card state-card">加载中...</div>

            <!-- ============ 准备态 ============ -->
            <template v-else-if="phase === 'prep'">
                <!-- 上次未完成场次：继续面试 -->
                <div class="card ongoing-card" v-if="ongoing">
                    <div class="ongoing-info">
                        <div class="ongoing-title">有一场进行中的面试</div>
                        <div class="ongoing-desc">
                            {{ ongoing.direction }} · 进度 {{ (ongoing.currentIndex || 0) + 1 }}/{{ ongoing.totalTopics }}
                            · 剩余 {{ formatRemain(ongoing.remainingSeconds) }}
                        </div>
                    </div>
                    <button class="primary-btn" @click="enterChat(ongoing)">继续面试</button>
                </div>

                <div class="card prep-card">
                    <div class="card-title">开一场面试</div>
                    <div class="form-block">
                        <div class="form-label">面试方向</div>
                        <div class="preset-row">
                            <span v-for="p in directionPresets" :key="p" class="preset-item"
                                  :class="{ active: direction === p }" @click="direction = p">{{ p }}</span>
                        </div>
                        <input v-model="direction" class="form-input" maxlength="64"
                               placeholder="或输入自定义方向，如：Java 后端（偏中间件）" />
                    </div>
                    <div class="form-block">
                        <div class="form-label">难度</div>
                        <div class="switch-row">
                            <span v-for="opt in difficultyOptions" :key="opt.value" class="switch-item"
                                  :class="{ active: difficulty === opt.value }"
                                  @click="difficulty = opt.value">{{ opt.label }}</span>
                        </div>
                    </div>
                    <div class="form-block">
                        <div class="form-label">题数</div>
                        <div class="switch-row">
                            <span v-for="n in questionCountOptions" :key="n" class="switch-item"
                                  :class="{ active: questionCount === n }"
                                  @click="questionCount = n">{{ n }} 题</span>
                        </div>
                    </div>
                    <div class="form-block">
                        <div class="form-label">
                            简历（可选）
                            <span class="form-label-hint">给了简历会针对你的项目经历深挖，约 6 成题目来自简历</span>
                        </div>
                        <div class="resume-row">
                            <button type="button" class="ghost-btn small-btn"
                                    :disabled="resumeParsing || starting" @click="pickResumeFile">
                                {{ resumeParsing ? '解析中...' : '上传简历文件' }}
                            </button>
                            <span class="resume-file" v-if="resumeFileName">{{ resumeFileName }}</span>
                            <span class="resume-clear" v-if="resumeText" @click="clearResume">清空</span>
                            <input ref="resumeInput" type="file" class="hidden-file"
                                   accept=".pdf,.doc,.docx,.txt,.md" @change="onResumeFileChange" />
                        </div>
                        <textarea v-model="resumeText" class="form-input resume-input" rows="4"
                                  maxlength="8000"
                                  placeholder="也可以直接粘贴简历文本。内容只用于本次出题，服务端不留存；草稿仅存在本机浏览器。"></textarea>
                        <div class="resume-meta">
                            <span>{{ (resumeText || '').length }}/8000</span>
                            <span class="resume-notice" v-if="resumeNotice">{{ resumeNotice }}</span>
                            <span class="resume-error" v-if="resumeError">{{ resumeError }}</span>
                        </div>
                    </div>
                    <div class="prep-actions">
                        <button class="primary-btn" :disabled="starting" @click="startInterview">
                            {{ starting ? '面试官出题中...' : '开始面试' }}
                        </button>
                        <span class="prep-hint">限时 {{ durationMinutes }} 分钟 · {{ questionCount }} 个主题 · 每个主题最多 1 次追问</span>
                    </div>
                    <div class="prep-error" v-if="startError">{{ startError }}</div>
                    <div class="quota-line" v-if="quota">
                        <span>AI 额度：今日免费剩余 {{ quotaRemainText }} · 钱包 {{ quotaWalletText }}</span>
                        <span class="quota-link" @click="goQuota">额度中心</span>
                    </div>
                </div>

                <div class="card history-card">
                    <div class="card-title">历史面试</div>
                    <div v-if="history.length === 0" class="empty-line">还没有面试记录，开一场试试</div>
                    <template v-else>
                        <div v-for="item in history" :key="item.interviewId" class="history-item"
                             @click="openHistory(item)">
                            <div class="history-main">
                                <span class="history-direction">{{ item.direction }}</span>
                                <span class="mini-tag">{{ difficultyLabel(item.difficulty) }}</span>
                                <span class="mini-tag" :class="'status-' + item.status">{{ statusLabel(item.status) }}</span>
                            </div>
                            <div class="history-side">
                                <span class="history-score">{{ item.overallScore ? ('等级 ' + item.overallScore + '/5') : '—' }}</span>
                                <span class="history-time">{{ item.status === 2 ? item.finishedTime : item.startedTime }}</span>
                            </div>
                        </div>
                    </template>
                </div>
            </template>

            <!-- ============ 对话态 ============ -->
            <template v-else-if="phase === 'chat' && session">
                <div class="card chat-top">
                    <div class="chat-meta">
                        <span class="chat-direction">{{ session.direction }}</span>
                        <span class="mini-tag">{{ difficultyLabel(session.difficulty) }}</span>
                        <span class="mini-tag source-resume"
                              v-if="session.currentTopic && session.currentTopic.source === 'resume'">简历深挖</span>
                        <span class="chat-progress">主题 {{ chatProgress }}</span>
                    </div>
                    <div class="chat-right">
                        <span class="countdown" :class="{ urgent: remainingSeconds <= 300 }">{{ countdownText }}</span>
                        <template v-if="!confirmingFinish">
                            <button class="plain-btn" :disabled="finishing || streaming" @click="confirmingFinish = true">结束面试</button>
                        </template>
                        <template v-else>
                            <button class="primary-btn small" :disabled="finishing" @click="doFinish(false)">
                                {{ finishing ? '报告生成中...' : '确认结束并生成报告' }}
                            </button>
                            <button class="plain-btn" :disabled="finishing" @click="confirmingFinish = false">再想想</button>
                        </template>
                    </div>
                </div>

                <div class="card chat-card">
                    <div class="chat-list" ref="chatList">
                        <div v-for="(t, ti) in chatTurns" :key="ti" class="chat-row"
                             :class="t.role === 'user' ? 'me' : 'interviewer'">
                            <div class="avatar" :class="t.role === 'user' ? 'me-avatar' : 'bot-avatar'">
                                {{ t.role === 'user' ? '我' : '面' }}
                            </div>
                            <div class="bubble" :class="t.role === 'user' ? 'me-bubble' : 'bot-bubble'">
                                <span class="bubble-tag" v-if="t.role === 'interviewer' && t.type === 'followup'">追问</span>
                                <div class="bubble-text">{{ t.content }}</div>
                            </div>
                        </div>
                        <!-- 流式中的面试官气泡（打字机） -->
                        <div class="chat-row interviewer" v-if="streaming">
                            <div class="avatar bot-avatar">面</div>
                            <div class="bubble bot-bubble">
                                <div class="bubble-text">{{ streamingText || '正在思考...' }}<span class="caret" v-if="streamingText"></span></div>
                            </div>
                        </div>
                    </div>

                    <div class="chat-input-block">
                        <textarea v-model="answer" class="chat-input" maxlength="2000"
                                  :disabled="streaming || finishing"
                                  placeholder="输入你的回答（2000 字内）；Enter 发送，Shift+Enter 换行"
                                  @keydown.enter.exact.prevent="submitAnswer"></textarea>
                        <div class="chat-actions">
                            <button class="primary-btn" :disabled="streaming || finishing || !answer.trim()" @click="submitAnswer">
                                {{ streaming ? '面试官思考中...' : '发送回答' }}
                            </button>
                            <span class="chat-hint">{{ remainingSeconds > 0 ? '倒计时以服务端 deadline 为准，到点即废' : '面试已超时，请重新开面' }}</span>
                        </div>
                    </div>
                </div>
            </template>

            <!-- ============ 报告态 ============ -->
            <template v-else-if="phase === 'report'">
                <div v-if="!report" class="card state-card">报告加载中...</div>
                <template v-else>
                    <div class="card report-head">
                        <div class="report-title-row">
                            <span class="report-direction">{{ report.direction }}</span>
                            <span class="mini-tag">{{ difficultyLabel(report.difficulty) }}</span>
                            <span class="mini-tag" :class="'status-' + report.status">{{ statusLabel(report.status) }}</span>
                        </div>
                        <div class="report-meta">
                            <span>完成 {{ report.completedTopics }}/{{ report.totalTopics }} 个主题</span>
                            <span v-if="report.durationSeconds">用时 {{ Math.max(1, Math.round(report.durationSeconds / 60)) }} 分钟</span>
                            <span>{{ report.finishedTime || report.startedTime }}</span>
                        </div>
                        <div class="report-actions">
                            <button class="primary-btn small" @click="restart">再面一场</button>
                            <button class="plain-btn" @click="goCoding">返回每日一题</button>
                        </div>
                    </div>

                    <!-- 结构化失败 / 未生成：原文兜底 + 可重试 -->
                    <div class="card degrade-card" v-if="!report.reportReady">
                        <div class="degrade-title">{{ reportDegradeTitle }}</div>
                        <div class="degrade-text" v-if="report.rawText">{{ report.rawText }}</div>
                        <button v-if="report.status === 2" class="primary-btn small" :disabled="finishing" @click="retryReport">
                            {{ finishing ? '重新生成中...' : '重新生成报告' }}
                        </button>
                    </div>

                    <template v-else>
                        <div class="card overall-card" v-if="report.overallScore">
                            <span class="overall-label">综合等级</span>
                            <span class="overall-value">{{ report.overallScore }}</span>
                            <span class="overall-unit">/ 5</span>
                            <span class="overall-note">三维均值（回答结构 / 考点覆盖 / 技术准确性），不给百分制</span>
                        </div>

                        <div class="card item-card" v-for="(it, ii) in (report.items || [])" :key="ii"
                             :class="{ 'item-pending': it.pending }">
                            <div class="item-head">
                                <span class="item-index">{{ ii + 1 }}</span>
                                <span class="item-topic">{{ it.topic }}</span>
                                <span class="coverage-count pending-tag" v-if="it.pending">未评估</span>
                                <span class="coverage-count" v-else-if="it.coverage">
                                    你覆盖了 {{ coveredCount(it) }}/{{ coveredCount(it) + (it.coverage.missing || []).length }} 个考点
                                </span>
                            </div>
                            <div class="level-rows" v-if="!it.pending">
                                <div class="level-row" v-for="lv in itemLevels(it)" :key="lv.name">
                                    <span class="level-name">{{ lv.name }}</span>
                                    <span class="level-bar"><span class="level-bar-inner" :style="{ width: (lv.value * 20) + '%' }"></span></span>
                                    <span class="level-value">{{ lv.value }}/5</span>
                                </div>
                            </div>
                            <div class="coverage-block" v-if="!it.pending && it.coverage">
                                <div class="coverage-row" v-if="(it.coverage.covered || []).length">
                                    <span class="coverage-tag ok">已覆盖</span>
                                    <span class="coverage-chip ok" v-for="c in it.coverage.covered" :key="'c' + c">{{ c }}</span>
                                </div>
                                <div class="coverage-row" v-if="(it.coverage.missing || []).length">
                                    <span class="coverage-tag miss">待补强</span>
                                    <span class="coverage-chip miss" v-for="m in it.coverage.missing" :key="'m' + m">{{ m }}</span>
                                </div>
                            </div>
                            <div class="item-comment" v-if="it.comment">{{ it.comment }}</div>
                        </div>

                        <div class="card summary-card" v-if="report.overall || (report.suggestions || []).length">
                            <div class="card-title">总评与建议</div>
                            <div class="overall-text" v-if="report.overall">{{ report.overall }}</div>
                            <ul class="suggestion-list" v-if="(report.suggestions || []).length">
                                <li v-for="(s, si) in report.suggestions" :key="si">{{ s }}</li>
                            </ul>
                        </div>
                    </template>

                    <div class="card replay-card" v-if="(report.turns || []).length">
                        <div class="card-title">
                            对话回放
                            <span class="collapse-toggle" @click="replayOpen = !replayOpen">{{ replayOpen ? '收起' : '展开' }}</span>
                        </div>
                        <div class="replay-list" v-if="replayOpen">
                            <div v-for="(t, ti) in report.turns" :key="'r' + ti" class="replay-item"
                                 :class="{ me: t.role === 'user' }">
                                <span class="replay-role">{{ t.role === 'user' ? '我' : '面试官' }}</span>
                                <span class="replay-content">{{ t.content }}</span>
                            </div>
                        </div>
                    </div>
                </template>
            </template>
        </template>
    </div>
</template>

<script>
    import {
        startInterview,
        parseInterviewResume,
        getCurrentInterview,
        interviewTurnStream,
        finishInterview,
        getInterviewReport,
        getInterviewHistory
    } from '@/apis/coding'
    import { getAiQuotaStatus } from '@/apis/ai'
    import { toast } from '@/utils/toast'

    /** 简历草稿只存本机浏览器（服务端不落库），键名带版本便于后续调整结构 */
    const RESUME_DRAFT_KEY = 'coding_interview_resume_draft_v1'

    export default {
        name: 'CodingInterview',
        data() {
            return {
                loading: false,
                phase: 'prep',
                // ---- 准备态 ----
                directionPresets: ['Java 后端', '前端开发', 'Python 后端', '算法与数据结构', '数据库与中间件', '系统设计'],
                direction: 'Java 后端',
                difficulty: 2,
                difficultyOptions: [
                    { value: 1, label: '入门' },
                    { value: 2, label: '进阶' },
                    { value: 3, label: '挑战' }
                ],
                questionCount: 5,
                questionCountOptions: [3, 5, 8, 10],
                durationMinutes: 45,
                // ---- 简历（可选，只用于本次出题） ----
                resumeText: '',
                resumeFileName: '',
                resumeParsing: false,
                resumeNotice: '',
                resumeError: '',
                resumeDraftTimer: null,
                starting: false,
                startError: '',
                quota: null,
                ongoing: null,
                history: [],
                // ---- 对话态 ----
                session: null,
                chatTurns: [],
                turnSeq: 0,
                currentIndex: 0,
                totalTopics: 0,
                remainingSeconds: 0,
                answer: '',
                streaming: false,
                streamingText: '',
                abortTurn: null,
                confirmingFinish: false,
                finishing: false,
                timer: null,
                // ---- 报告态 ----
                report: null,
                replayOpen: false,
                // ---- 额度引导 ----
                quotaBlocked: false,
                quotaMessage: ''
            }
        },
        computed: {
            isLoggedIn() {
                return this.$store.getters.isLoggedIn
            },
            countdownText() {
                const s = Math.max(0, this.remainingSeconds)
                const m = Math.floor(s / 60)
                const sec = s % 60
                return (m < 10 ? '0' : '') + m + ':' + (sec < 10 ? '0' : '') + sec
            },
            chatProgress() {
                return Math.min(this.currentIndex + 1, this.totalTopics) + '/' + this.totalTopics
            },
            quotaRemainText() {
                const ft = (this.quota && this.quota.freeTokens) || {}
                return this.formatTokens(ft.remainToday || 0) + ' tokens'
            },
            quotaWalletText() {
                return this.formatTokens((this.quota && this.quota.walletTokenBalance) || 0) + ' tokens'
            },
            reportDegradeTitle() {
                if (!this.report) {
                    return ''
                }
                if (this.report.status === 1) {
                    return '该场次仍在进行中，结束后才会生成报告'
                }
                if (this.report.status === 3) {
                    return '该场次已过期（超时未完成），不生成报告'
                }
                // 后端两种降级：解析失败（存模型原文，rawText 有值）与生成失败/超时（无原文）
                return this.report.rawText ? '报告结构化解析失败，以下为模型原文（可重试）' : '报告生成失败，请点击下方重试'
            }
        },
        watch: {
            // 简历草稿防抖落本机（400ms）：打字时不逐字符写 localStorage
            resumeText() {
                if (this.resumeDraftTimer) {
                    clearTimeout(this.resumeDraftTimer)
                }
                this.resumeDraftTimer = setTimeout(() => {
                    this.resumeDraftTimer = null
                    this.saveResumeDraft()
                }, 400)
            }
        },
        mounted() {
            this.restoreResumeDraft()
            // 历史回看入口：/coding/interview?id=xx 直接进报告态
            const query = this.$route.query || {}
            if (query.id && this.isLoggedIn) {
                this.openReport(String(query.id))
                return
            }
            if (this.isLoggedIn) {
                this.loadCurrent()
                this.loadHistory()
                this.loadQuota()
            }
        },
        beforeDestroy() {
            this.stopTimer()
            this.abortTurnStream()
            if (this.resumeDraftTimer) {
                clearTimeout(this.resumeDraftTimer)
                this.resumeDraftTimer = null
            }
        },
        methods: {
            showLogin() {
                this.$store.dispatch('showLogin')
            },
            goCoding() {
                this.$router.push('/coding').catch(() => {})
            },
            goAbility() {
                this.$router.push('/coding/ability').catch(() => {})
            },
            goQuota() {
                this.$router.push('/user/ai/quota').catch(() => {})
            },
            difficultyLabel(value) {
                if (value === 3) return '挑战'
                if (value === 2) return '进阶'
                return '入门'
            },
            statusLabel(value) {
                if (value === 1) return '进行中'
                if (value === 2) return '已完成'
                return '已过期'
            },
            /** token 数格式化：1.2万 / 12万（与 AI 面板同口径） */
            formatTokens(n) {
                const v = Number(n) || 0
                if (v >= 10000) {
                    const w = v / 10000
                    return (w >= 100 ? Math.round(w) : w.toFixed(1).replace(/\.0$/, '')) + '万'
                }
                return String(v)
            },
            formatRemain(seconds) {
                const s = Math.max(0, Number(seconds) || 0)
                const m = Math.floor(s / 60)
                const sec = s % 60
                return (m < 10 ? '0' : '') + m + ':' + (sec < 10 ? '0' : '') + sec
            },
            showQuotaBlocked(msg) {
                this.quotaMessage = String(msg || '').replace(/^\[\d+\]\s*/, '') || 'AI 额度已用尽，请充值后继续'
                this.quotaBlocked = true
                this.loadQuota()
            },

            // ============ 准备态 ============
            async loadCurrent() {
                this.loading = true
                try {
                    const res = await getCurrentInterview()
                    const data = res && res.code === 200 ? res.data : null
                    this.ongoing = data && data.status === 1 ? data : null
                } catch (e) {
                    this.ongoing = null
                } finally {
                    this.loading = false
                }
            },
            async loadHistory() {
                try {
                    const res = await getInterviewHistory({ page: 1, size: 5 })
                    if (res && res.code === 200 && res.data) {
                        this.history = res.data.list || []
                    }
                } catch (e) {
                    // 历史加载失败不影响开面
                }
            },
            async loadQuota() {
                try {
                    const res = await getAiQuotaStatus()
                    if (res && res.code === 200 && res.data) {
                        this.quota = res.data
                    }
                } catch (e) {
                    // 额度提示失败静默
                }
            },
            async startInterview() {
                if (this.starting) {
                    return
                }
                const direction = (this.direction || '').trim()
                if (!direction) {
                    toast('请先选择或输入面试方向', 2)
                    return
                }
                if (direction.length > 64) {
                    toast('方向最多 64 字', 2)
                    return
                }
                this.starting = true
                this.startError = ''
                try {
                    const payload = {
                        direction,
                        difficulty: this.difficulty,
                        questionCount: this.questionCount
                    }
                    const resume = (this.resumeText || '').trim()
                    if (resume) {
                        payload.resumeText = resume
                    }
                    const res = await startInterview(payload)
                    if (res && res.code === 200 && res.data) {
                        this.ongoing = null
                        this.enterChat(res.data)
                    } else {
                        this.startError = (res && res.message) || '开面失败，请稍后重试'
                    }
                } catch (e) {
                    const msg = (e && e.message) || '开面失败，请稍后重试'
                    // 额度不足（3301）：横幅引导充值；其余（每日场次上限等）内联展示
                    if (e && e.code === 3301) {
                        this.showQuotaBlocked(msg)
                    } else {
                        this.startError = msg
                    }
                } finally {
                    this.starting = false
                }
            },

            // ============ 简历（可选，服务端不落库） ============

            pickResumeFile() {
                if (this.resumeParsing || this.starting) {
                    return
                }
                const input = this.$refs.resumeInput
                if (!input) {
                    return
                }
                // 清空 value，否则连续选同一个文件不触发 change
                input.value = ''
                input.click()
            },

            async onResumeFileChange(e) {
                const file = e && e.target && e.target.files && e.target.files[0]
                if (!file) {
                    return
                }
                // 前端先拦一道：超过容器 multipart 上限会被直接拒绝，报错信息对用户没意义
                if (file.size > 10 * 1024 * 1024) {
                    this.resumeError = '文件大小不能超过 10MB'
                    this.resumeNotice = ''
                    const rejectInput = this.$refs.resumeInput
                    if (rejectInput) {
                        rejectInput.value = ''
                    }
                    return
                }
                this.resumeParsing = true
                this.resumeNotice = ''
                this.resumeError = ''
                try {
                    const res = await parseInterviewResume(file)
                    if (res && res.code === 200 && res.data) {
                        this.resumeText = res.data.text || ''
                        this.resumeFileName = file.name
                        if (res.data.truncated) {
                            this.resumeNotice = '原文 ' + res.data.chars + ' 字，已截断后填入'
                        }
                        this.saveResumeDraft()
                    } else {
                        this.resumeError = (res && res.message) || '简历解析失败，请重试或直接粘贴文本'
                    }
                } catch (err) {
                    this.resumeError = (err && err.message) || '简历解析失败，请重试或直接粘贴文本'
                } finally {
                    this.resumeParsing = false
                }
            },

            clearResume() {
                this.resumeText = ''
                this.resumeFileName = ''
                this.resumeNotice = ''
                this.resumeError = ''
                const input = this.$refs.resumeInput
                if (input) {
                    input.value = ''
                }
                this.saveResumeDraft()
            },

            /** 草稿存本机浏览器：服务端不留存简历，刷新后靠这里回填 */
            saveResumeDraft() {
                try {
                    const text = (this.resumeText || '').trim()
                    if (text) {
                        window.localStorage.setItem(RESUME_DRAFT_KEY, text)
                    } else {
                        window.localStorage.removeItem(RESUME_DRAFT_KEY)
                    }
                } catch (e) {
                    // 隐私模式等场景 localStorage 不可用：静默放弃，不影响开面
                }
            },

            restoreResumeDraft() {
                try {
                    const saved = window.localStorage.getItem(RESUME_DRAFT_KEY)
                    if (saved) {
                        this.resumeText = saved
                    }
                } catch (e) {
                    // 同上，读取失败按无草稿处理
                }
            },

            // ============ 对话态 ============
            enterChat(session) {
                if (!session) {
                    return
                }
                this.session = session
                this.chatTurns = (session.turns || []).map((t) => ({
                    role: t.role,
                    type: t.type,
                    content: t.content,
                    topicIndex: t.topicIndex
                }))
                // turnSeq = 服务端 turn_count（已作答轮数），后续以 done.turnCount 推进
                this.turnSeq = (session.turns || []).filter((t) => t.role === 'user').length
                this.totalTopics = session.totalTopics || 0
                this.currentIndex = session.currentIndex || 0
                this.remainingSeconds = session.remainingSeconds || 0
                this.answer = ''
                this.streaming = false
                this.streamingText = ''
                this.confirmingFinish = false
                this.phase = 'chat'
                this.startTimer()
                this.$nextTick(this.scrollChatBottom)
                window.scrollTo({ top: 0 })
            },
            startTimer() {
                this.stopTimer()
                this.timer = setInterval(() => {
                    if (this.remainingSeconds > 0) {
                        this.remainingSeconds--
                    }
                    if (this.remainingSeconds <= 0) {
                        this.stopTimer()
                        this.handleExpired()
                    }
                }, 1000)
            },
            stopTimer() {
                if (this.timer) {
                    clearInterval(this.timer)
                    this.timer = null
                }
            },
            handleExpired() {
                toast('面试已超时（限时 ' + this.durationMinutes + ' 分钟），请重新开面', 2)
                this.session = null
                this.chatTurns = []
                this.phase = 'prep'
                this.loadCurrent()
                this.loadHistory()
            },
            scrollChatBottom() {
                const el = this.$refs.chatList
                if (el) {
                    el.scrollTop = el.scrollHeight
                }
            },
            abortTurnStream() {
                if (this.abortTurn) {
                    this.abortTurn()
                    this.abortTurn = null
                }
            },
            submitAnswer() {
                if (this.streaming || this.finishing || !this.session) {
                    return
                }
                const text = (this.answer || '').trim()
                if (!text) {
                    return
                }
                if (this.remainingSeconds <= 0) {
                    this.handleExpired()
                    return
                }
                this.answer = ''
                this.chatTurns.push({ role: 'user', type: 'answer', content: text })
                const pendingIndex = this.chatTurns.length - 1
                this.streaming = true
                this.streamingText = ''
                this.$nextTick(this.scrollChatBottom)

                this.abortTurn = interviewTurnStream({
                    interviewId: this.session.interviewId,
                    answer: text,
                    turnSeq: this.turnSeq
                }, {
                    onDelta: (delta) => {
                        this.streamingText += delta
                        this.scrollChatBottom()
                    },
                    onDone: (vo) => {
                        this.streaming = false
                        this.abortTurn = null
                        const finalText = (vo && vo.text) ? vo.text : this.streamingText
                        this.streamingText = ''
                        this.chatTurns.push({
                            role: 'interviewer',
                            type: vo && vo.kind === 'followup' ? 'followup' : 'question',
                            content: finalText,
                            topicIndex: vo ? vo.topicIndex : undefined
                        })
                        if (vo && vo.turnCount != null) {
                            this.turnSeq = vo.turnCount
                        }
                        if (vo && vo.topicIndex != null) {
                            this.currentIndex = vo.topicIndex
                        }
                        this.$nextTick(this.scrollChatBottom)
                        if (vo && vo.completed) {
                            toast('全部主题已答完，正在为你生成报告', 2)
                            this.doFinish(true)
                        }
                    },
                    onError: (msg) => {
                        this.streaming = false
                        this.abortTurn = null
                        this.streamingText = ''
                        // 本轮未落库：撤回乐观消息，回答回填输入框可重试
                        this.chatTurns.splice(pendingIndex, 1)
                        this.answer = text
                        const errText = String(msg || '')
                        if (errText.indexOf('[3301]') === 0) {
                            this.showQuotaBlocked(errText)
                            return
                        }
                        toast(errText || '面试官暂时离线，请重试', 2)
                        // 状态类错误（超时/过期/已结束/已变化）以服务端为准刷新，避免本地状态错乱
                        if (errText.indexOf('超时') >= 0 || errText.indexOf('过期') >= 0
                            || errText.indexOf('已结束') >= 0 || errText.indexOf('刷新') >= 0) {
                            this.refreshChat()
                        }
                    }
                })
            },
            /** 状态错乱时以服务端为准恢复：进行中续答 / 已完成进报告 / 否则回准备态 */
            async refreshChat() {
                this.stopTimer()
                try {
                    const res = await getCurrentInterview()
                    const data = res && res.code === 200 ? res.data : null
                    if (data && data.status === 1) {
                        this.enterChat(data)
                    } else if (data && data.status === 2) {
                        this.session = null
                        this.openReport(data.interviewId)
                    } else {
                        this.session = null
                        this.chatTurns = []
                        this.phase = 'prep'
                        this.loadCurrent()
                        this.loadHistory()
                    }
                } catch (e) {
                    this.session = null
                    this.phase = 'prep'
                }
            },
            async doFinish(auto) {
                if (this.finishing || !this.session) {
                    return
                }
                this.finishing = true
                this.confirmingFinish = false
                this.stopTimer()
                toast(auto ? '全部主题已答完，正在生成面试报告...' : '已结束面试，正在生成报告...', 2)
                try {
                    const res = await finishInterview({ interviewId: this.session.interviewId })
                    if (res && res.code === 200 && res.data) {
                        const interviewId = res.data.interviewId || this.session.interviewId
                        this.session = null
                        this.chatTurns = []
                        await this.openReport(interviewId)
                        if (res.data.reportReady === false) {
                            toast('报告生成失败，可在报告页重新生成', 2)
                        }
                    } else {
                        toast((res && res.message) || '结束失败，请稍后重试', 2)
                        if (this.phase === 'chat') {
                            this.startTimer()
                        }
                    }
                } catch (e) {
                    const msg = (e && e.message) || '结束失败，请稍后重试'
                    toast(msg, 2)
                    if (this.phase === 'chat') {
                        this.startTimer()
                        if (msg.indexOf('超时') >= 0 || msg.indexOf('过期') >= 0) {
                            this.handleExpired()
                        }
                    }
                } finally {
                    this.finishing = false
                }
            },

            // ============ 报告态 ============
            async openHistory(item) {
                if (item.status === 1) {
                    try {
                        const res = await getCurrentInterview()
                        const data = res && res.code === 200 ? res.data : null
                        if (data && data.status === 1) {
                            this.enterChat(data)
                            return
                        }
                    } catch (e) {
                        // fallthrough：按不可恢复处理
                    }
                    toast('该场次已过期或已结束，刷新历史看看', 2)
                    this.loadCurrent()
                    this.loadHistory()
                    return
                }
                this.openReport(item.interviewId)
            },
            async openReport(id) {
                if (!id) {
                    return
                }
                this.phase = 'report'
                this.report = null
                this.replayOpen = false
                try {
                    const res = await getInterviewReport(id)
                    if (res && res.code === 200 && res.data) {
                        this.report = res.data
                    } else {
                        toast((res && res.message) || '报告加载失败', 2)
                        this.phase = 'prep'
                    }
                } catch (e) {
                    toast((e && e.message) || '报告加载失败', 2)
                    this.phase = 'prep'
                }
                window.scrollTo({ top: 0 })
            },
            async retryReport() {
                if (!this.report || this.finishing) {
                    return
                }
                this.finishing = true
                try {
                    const res = await finishInterview({ interviewId: this.report.interviewId })
                    if (res && res.code === 200 && res.data) {
                        const id = this.report.interviewId
                        await this.openReport(id)
                        toast(res.data.reportReady ? '报告已生成' : '报告仍未生成成功，可稍后再试', 2)
                    } else {
                        toast((res && res.message) || '重新生成失败，请稍后重试', 2)
                    }
                } catch (e) {
                    toast((e && e.message) || '重新生成失败，请稍后重试', 2)
                } finally {
                    this.finishing = false
                }
            },
            restart() {
                this.report = null
                this.phase = 'prep'
                // 清掉回看参数，避免刷新又进报告态
                const query = this.$route.query || {}
                if (query.id) {
                    this.$router.replace({ path: '/coding/interview' }).catch(() => {})
                }
                this.loadCurrent()
                this.loadHistory()
                this.loadQuota()
                window.scrollTo({ top: 0 })
            },
            itemLevels(item) {
                return [
                    { name: '回答结构', value: item.structure || 0 },
                    { name: '考点覆盖', value: item.coverageScore || 0 },
                    { name: '技术准确性', value: item.accuracy || 0 }
                ]
            },
            coveredCount(item) {
                return ((item.coverage && item.coverage.covered) || []).length
            }
        }
    }
</script>

<style scoped>
    .interview-page {
        padding-bottom: 40PX;
        min-height: 60vh;
    }

    .interview-page * {
        box-sizing: border-box;
    }

    /* 额度横幅 */
    .quota-banner {
        display: flex;
        align-items: center;
        gap: 12PX;
        background: #FFF7E6;
        border: 1PX solid #FFD591;
        border-radius: 8PX;
        padding: 10PX 16PX;
        margin-bottom: 12PX;
        font-size: 13PX;
        color: #AD6800;
    }

    .quota-banner-text {
        flex: 1;
        line-height: 1.6;
    }

    .quota-banner-btn {
        color: #1E80FF;
        cursor: pointer;
        flex-shrink: 0;
    }

    .quota-banner-btn:hover {
        text-decoration: underline;
    }

    .quota-banner-close {
        color: #C9A76A;
        cursor: pointer;
        flex-shrink: 0;
    }

    /* 头部 */
    .interview-header {
        display: flex;
        align-items: center;
        justify-content: space-between;
        background: linear-gradient(120deg, #1E80FF 0%, #4A9BFF 100%);
        border-radius: 10PX;
        padding: 22PX 28PX;
        color: #fff;
        margin-bottom: 16PX;
        flex-wrap: wrap;
        gap: 12PX;
    }

    .page-title {
        margin: 0 0 6PX;
        font-size: 24PX;
        font-weight: 600;
    }

    .page-sub {
        font-size: 13PX;
        opacity: 0.9;
    }

    .header-right {
        display: flex;
        align-items: center;
        gap: 12PX;
    }

    .ghost-btn {
        border: 1PX solid rgba(255, 255, 255, 0.8);
        background: transparent;
        color: #fff;
        border-radius: 16PX;
        padding: 7PX 16PX;
        font-size: 13PX;
        cursor: pointer;
    }

    .ghost-btn:hover {
        background: rgba(255, 255, 255, 0.15);
    }

    /* 通用卡片 */
    .card {
        background: #fff;
        border-radius: 8PX;
        padding: 18PX 20PX;
    }

    .card + .card {
        margin-top: 16PX;
    }

    .card-title {
        font-size: 16PX;
        font-weight: 600;
        color: #222;
        display: flex;
        align-items: center;
        gap: 8PX;
        margin-bottom: 14PX;
    }

    .state-card {
        text-align: center;
        padding: 40PX 20PX;
        color: #86909C;
        font-size: 14PX;
    }

    .state-title {
        font-size: 16PX;
        color: #333;
        margin-bottom: 8PX;
    }

    .state-desc {
        font-size: 13PX;
        color: #86909C;
        margin-bottom: 16PX;
    }

    .primary-btn {
        background: #1E80FF;
        color: #fff;
        border: none;
        border-radius: 6PX;
        padding: 9PX 26PX;
        font-size: 14PX;
        cursor: pointer;
    }

    .primary-btn:hover {
        background: #1A73E8;
    }

    .primary-btn:disabled {
        background: #A8CBFF;
        cursor: not-allowed;
    }

    .primary-btn.small {
        padding: 7PX 18PX;
        font-size: 13PX;
    }

    .plain-btn {
        background: transparent;
        border: 1PX solid #e5e6eb;
        color: #4E5969;
        border-radius: 6PX;
        padding: 8PX 18PX;
        font-size: 13PX;
        cursor: pointer;
    }

    .plain-btn:hover {
        border-color: #1E80FF;
        color: #1E80FF;
    }

    .mini-tag {
        font-size: 12PX;
        color: #86909C;
        background: #F2F3F5;
        border-radius: 4PX;
        padding: 1PX 8PX;
    }

    /* 当前主题来自简历深挖（只有开面时传了简历才会出现） */
    .mini-tag.source-resume {
        color: #1E80FF;
        background: #E8F3FF;
    }

    .mini-tag.status-1 {
        color: #1E80FF;
        background: #E8F3FF;
    }

    .mini-tag.status-2 {
        color: #52C41A;
        background: #F0FFF0;
    }

    .mini-tag.status-3 {
        color: #86909C;
        background: #F2F3F5;
    }

    /* 准备态 */
    .ongoing-card {
        display: flex;
        align-items: center;
        justify-content: space-between;
        gap: 14PX;
        border: 1PX solid #B8D8FF;
        background: #F0F7FF;
        flex-wrap: wrap;
    }

    .ongoing-title {
        font-size: 15PX;
        font-weight: 600;
        color: #1E80FF;
        margin-bottom: 4PX;
    }

    .ongoing-desc {
        font-size: 13PX;
        color: #4E5969;
    }

    .form-block {
        margin-bottom: 16PX;
    }

    .form-label {
        font-size: 13PX;
        color: #555;
        margin-bottom: 8PX;
    }

    .preset-row {
        display: flex;
        gap: 8PX;
        flex-wrap: wrap;
        margin-bottom: 10PX;
    }

    .preset-item {
        font-size: 13PX;
        color: #4E5969;
        border: 1PX solid #e5e6eb;
        border-radius: 14PX;
        padding: 4PX 14PX;
        cursor: pointer;
    }

    .preset-item:hover {
        border-color: #B8D8FF;
    }

    .preset-item.active {
        color: #1E80FF;
        border-color: #1E80FF;
        background: #E8F3FF;
    }

    .form-input {
        width: 100%;
        border: 1PX solid #e5e6eb;
        border-radius: 6PX;
        padding: 8PX 12PX;
        font-size: 13PX;
        color: #333;
        outline: none;
    }

    .form-input:focus {
        border-color: #1E80FF;
    }

    .form-label-hint {
        margin-left: 6PX;
        font-size: 12PX;
        color: #999;
    }

    .resume-row {
        display: flex;
        align-items: center;
        gap: 10PX;
        flex-wrap: wrap;
        margin-bottom: 8PX;
    }

    .small-btn {
        font-size: 12PX;
        padding: 4PX 12PX;
    }

    .resume-file {
        font-size: 12PX;
        color: #4E5969;
        max-width: 220PX;
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
    }

    .resume-clear {
        font-size: 12PX;
        color: #1E80FF;
        cursor: pointer;
    }

    .hidden-file {
        display: none;
    }

    .resume-input {
        resize: vertical;
        min-height: 78PX;
        line-height: 1.6;
        font-family: inherit;
    }

    .resume-meta {
        display: flex;
        align-items: center;
        gap: 10PX;
        margin-top: 6PX;
        font-size: 12PX;
        color: #999;
    }

    .resume-notice {
        color: #FF7D00;
    }

    .resume-error {
        color: #F53F3F;
    }

    .switch-row {
        display: flex;
        gap: 6PX;
    }

    .switch-item {
        font-size: 12PX;
        color: #666;
        border: 1PX solid #e5e6eb;
        border-radius: 12PX;
        padding: 3PX 12PX;
        cursor: pointer;
    }

    .switch-item.active {
        color: #1E80FF;
        border-color: #1E80FF;
        background: #E8F3FF;
    }

    .prep-actions {
        display: flex;
        align-items: center;
        gap: 12PX;
        flex-wrap: wrap;
        margin-top: 4PX;
    }

    .prep-hint {
        font-size: 12PX;
        color: #999;
    }

    .prep-error {
        margin-top: 12PX;
        font-size: 13PX;
        color: #F5222D;
        background: #FFF1F0;
        border-radius: 6PX;
        padding: 8PX 12PX;
    }

    .quota-line {
        margin-top: 14PX;
        padding-top: 12PX;
        border-top: 1PX dashed #eee;
        display: flex;
        align-items: center;
        justify-content: space-between;
        gap: 12PX;
        font-size: 12PX;
        color: #86909C;
        flex-wrap: wrap;
    }

    .quota-link {
        color: #1E80FF;
        cursor: pointer;
    }

    .quota-link:hover {
        text-decoration: underline;
    }

    .empty-line {
        padding: 18PX 0;
        text-align: center;
        font-size: 13PX;
        color: #999;
    }

    .history-item {
        display: flex;
        align-items: center;
        justify-content: space-between;
        gap: 12PX;
        padding: 12PX 4PX;
        border-bottom: 1PX solid #f2f3f5;
        cursor: pointer;
        flex-wrap: wrap;
    }

    .history-item:last-child {
        border-bottom: none;
    }

    .history-item:hover .history-direction {
        color: #1E80FF;
    }

    .history-main {
        display: flex;
        align-items: center;
        gap: 8PX;
        min-width: 0;
    }

    .history-direction {
        font-size: 14PX;
        color: #333;
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
    }

    .history-side {
        display: flex;
        align-items: center;
        gap: 14PX;
        flex-shrink: 0;
    }

    .history-score {
        font-size: 13PX;
        color: #FA8C16;
    }

    .history-time {
        font-size: 12PX;
        color: #86909C;
    }

    /* 对话态 */
    .chat-top {
        display: flex;
        align-items: center;
        justify-content: space-between;
        gap: 12PX;
        flex-wrap: wrap;
    }

    .chat-meta {
        display: flex;
        align-items: center;
        gap: 8PX;
    }

    .chat-direction {
        font-size: 15PX;
        font-weight: 600;
        color: #222;
    }

    .chat-progress {
        font-size: 13PX;
        color: #4E5969;
    }

    .chat-right {
        display: flex;
        align-items: center;
        gap: 10PX;
        flex-wrap: wrap;
    }

    .countdown {
        font-size: 16PX;
        font-weight: 600;
        color: #1E80FF;
        font-variant-numeric: tabular-nums;
    }

    .countdown.urgent {
        color: #F5222D;
    }

    .chat-list {
        max-height: 460PX;
        overflow-y: auto;
        padding: 6PX 2PX;
        display: flex;
        flex-direction: column;
        gap: 14PX;
    }

    .chat-row {
        display: flex;
        gap: 10PX;
        align-items: flex-start;
    }

    .chat-row.me {
        flex-direction: row-reverse;
    }

    .avatar {
        width: 32PX;
        height: 32PX;
        border-radius: 50%;
        display: inline-flex;
        align-items: center;
        justify-content: center;
        font-size: 13PX;
        flex-shrink: 0;
    }

    .bot-avatar {
        background: #E8F3FF;
        color: #1E80FF;
    }

    .me-avatar {
        background: #F2F3F5;
        color: #4E5969;
    }

    .bubble {
        max-width: 76%;
        border-radius: 10PX;
        padding: 10PX 14PX;
        font-size: 14PX;
        line-height: 1.7;
        word-break: break-word;
        white-space: pre-wrap;
    }

    .bot-bubble {
        background: #F7F8FA;
        color: #333;
    }

    .me-bubble {
        background: #E8F3FF;
        color: #1D2129;
    }

    .bubble-tag {
        display: inline-block;
        font-size: 12PX;
        color: #FA8C16;
        background: #FFF3E6;
        border-radius: 4PX;
        padding: 0 6PX;
        margin-bottom: 6PX;
    }

    .caret {
        display: inline-block;
        width: 2PX;
        height: 14PX;
        background: #1E80FF;
        margin-left: 2PX;
        vertical-align: -2PX;
        animation: blink 0.9s steps(1) infinite;
    }

    @keyframes blink {
        50% {
            opacity: 0;
        }
    }

    .chat-input-block {
        margin-top: 14PX;
        border-top: 1PX dashed #eee;
        padding-top: 14PX;
    }

    .chat-input {
        width: 100%;
        min-height: 84PX;
        border: 1PX solid #e5e6eb;
        border-radius: 6PX;
        padding: 10PX 12PX;
        font-size: 13PX;
        color: #333;
        outline: none;
        resize: vertical;
        font-family: inherit;
    }

    .chat-input:focus {
        border-color: #1E80FF;
    }

    .chat-actions {
        margin-top: 10PX;
        display: flex;
        align-items: center;
        gap: 12PX;
        flex-wrap: wrap;
    }

    .chat-hint {
        font-size: 12PX;
        color: #999;
    }

    /* 报告态 */
    .report-head {
        display: flex;
        flex-direction: column;
        gap: 10PX;
    }

    .report-title-row {
        display: flex;
        align-items: center;
        gap: 8PX;
        flex-wrap: wrap;
    }

    .report-direction {
        font-size: 18PX;
        font-weight: 600;
        color: #222;
    }

    .report-meta {
        display: flex;
        align-items: center;
        gap: 16PX;
        font-size: 13PX;
        color: #86909C;
        flex-wrap: wrap;
    }

    .report-actions {
        display: flex;
        align-items: center;
        gap: 10PX;
    }

    .degrade-card {
        display: flex;
        flex-direction: column;
        gap: 12PX;
        align-items: flex-start;
    }

    .degrade-title {
        font-size: 14PX;
        color: #AD6800;
        background: #FFF7E6;
        border-radius: 6PX;
        padding: 8PX 12PX;
        width: 100%;
    }

    .degrade-text {
        font-size: 13PX;
        color: #555;
        line-height: 1.8;
        background: #F7F8FA;
        border-radius: 6PX;
        padding: 12PX 14PX;
        width: 100%;
        white-space: pre-wrap;
        word-break: break-word;
        max-height: 420PX;
        overflow-y: auto;
    }

    .overall-card {
        display: flex;
        align-items: baseline;
        gap: 8PX;
        flex-wrap: wrap;
    }

    .overall-label {
        font-size: 14PX;
        color: #4E5969;
    }

    .overall-value {
        font-size: 30PX;
        font-weight: 600;
        color: #1E80FF;
    }

    .overall-unit {
        font-size: 15PX;
        color: #1E80FF;
    }

    .overall-note {
        font-size: 12PX;
        color: #86909C;
        margin-left: 8PX;
    }

    .item-head {
        display: flex;
        align-items: center;
        gap: 10PX;
        flex-wrap: wrap;
        margin-bottom: 12PX;
    }

    .item-index {
        width: 22PX;
        height: 22PX;
        border-radius: 50%;
        background: #E8F3FF;
        color: #1E80FF;
        font-size: 12PX;
        display: inline-flex;
        align-items: center;
        justify-content: center;
        flex-shrink: 0;
    }

    .item-topic {
        font-size: 15PX;
        font-weight: 600;
        color: #222;
    }

    .coverage-count {
        font-size: 12PX;
        color: #FA8C16;
        background: #FFF3E6;
        border-radius: 10PX;
        padding: 2PX 10PX;
    }

    /* 未评估占位（该主题所属批次调用失败）：弱化显示，避免被误读成"答得差" */
    .item-pending .item-topic {
        color: #86909C;
    }

    .pending-tag {
        color: #86909C;
        background: #F2F3F5;
    }

    .level-rows {
        display: flex;
        flex-direction: column;
        gap: 8PX;
    }

    .level-row {
        display: flex;
        align-items: center;
        gap: 10PX;
    }

    .level-name {
        width: 76PX;
        font-size: 12PX;
        color: #4E5969;
        flex-shrink: 0;
    }

    .level-bar {
        flex: 1;
        height: 8PX;
        background: #F2F3F5;
        border-radius: 4PX;
        overflow: hidden;
    }

    .level-bar-inner {
        display: block;
        height: 100%;
        background: linear-gradient(90deg, #1E80FF, #4A9BFF);
        border-radius: 4PX;
    }

    .level-value {
        width: 36PX;
        text-align: right;
        font-size: 12PX;
        color: #86909C;
        flex-shrink: 0;
    }

    .coverage-block {
        margin-top: 12PX;
        display: flex;
        flex-direction: column;
        gap: 8PX;
    }

    .coverage-row {
        display: flex;
        align-items: center;
        gap: 6PX;
        flex-wrap: wrap;
    }

    .coverage-tag {
        font-size: 12PX;
        flex-shrink: 0;
    }

    .coverage-tag.ok {
        color: #52C41A;
    }

    .coverage-tag.miss {
        color: #F5222D;
    }

    .coverage-chip {
        font-size: 12PX;
        border-radius: 4PX;
        padding: 2PX 8PX;
    }

    .coverage-chip.ok {
        color: #389E0D;
        background: #F0FFF0;
    }

    .coverage-chip.miss {
        color: #CF1322;
        background: #FFF1F0;
    }

    .item-comment {
        margin-top: 12PX;
        font-size: 13PX;
        color: #555;
        line-height: 1.8;
        background: #F7F8FA;
        border-radius: 6PX;
        padding: 10PX 12PX;
        white-space: pre-wrap;
        word-break: break-word;
    }

    .overall-text {
        font-size: 14PX;
        color: #333;
        line-height: 1.8;
        white-space: pre-wrap;
    }

    .suggestion-list {
        margin: 10PX 0 0;
        padding-left: 18PX;
        font-size: 13PX;
        color: #555;
        line-height: 1.9;
    }

    .collapse-toggle {
        font-size: 12PX;
        font-weight: 400;
        color: #1E80FF;
        cursor: pointer;
    }

    .replay-list {
        display: flex;
        flex-direction: column;
        gap: 10PX;
        max-height: 420PX;
        overflow-y: auto;
    }

    .replay-item {
        display: flex;
        gap: 10PX;
        font-size: 13PX;
        line-height: 1.7;
    }

    .replay-item.me .replay-role {
        color: #4E5969;
    }

    .replay-role {
        flex-shrink: 0;
        width: 48PX;
        color: #1E80FF;
    }

    .replay-content {
        flex: 1;
        color: #333;
        white-space: pre-wrap;
        word-break: break-word;
    }

    /* 移动端适配 */
    @media (max-width: 768PX) {
        .interview-header {
            padding: 16PX 18PX;
        }

        .bubble {
            max-width: 86%;
        }
    }
</style>