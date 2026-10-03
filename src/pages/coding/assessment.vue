<template>
    <div class="assessment-page">
        <!-- 头部 -->
        <div class="assessment-header">
            <div class="header-left">
                <h1 class="page-title">能力测评</h1>
                <span class="page-sub">限时作答 · 快照判分 · 成绩沉淀进能力档案</span>
            </div>
            <div class="header-right">
                <button class="ghost-btn" @click="goAbility">能力档案</button>
            </div>
        </div>

        <!-- 未登录 -->
        <div v-if="!isLoggedIn" class="card state-card">
            <div class="state-icon">🔑</div>
            <div class="state-title">登录后开始能力测评</div>
            <div class="state-desc">测评成绩会沉淀进能力档案，默认仅自己可见</div>
            <button class="primary-btn" @click="showLogin">登录 / 注册</button>
        </div>

        <template v-else>
            <div v-if="loading" class="card state-card">加载中...</div>

            <!-- 开卷介绍 -->
            <template v-else-if="phase === 'intro'">
                <div class="card intro-card">
                    <div class="intro-title">测一次，拿到你的能力快照</div>
                    <ul class="intro-list">
                        <li>选择题组卷（单选/多选），满分 100 分，按技术领域产出掌握分布</li>
                        <li>限时 15 分钟，到点自动交卷；中途刷新可继续，计时不停</li>
                        <li>成绩可展示在能力档案中（默认私有，可逐项公开）</li>
                        <li>每 90 天可考一次，避免成绩被时间稀释后仍被当作当前水平</li>
                    </ul>
                    <div class="intro-actions">
                        <button class="primary-btn" :disabled="starting" @click="startAssessment">
                            {{ starting ? '开卷中...' : '开始测评' }}
                        </button>
                        <span class="intro-hint" v-if="introMessage">{{ introMessage }}</span>
                    </div>
                    <div class="last-score" v-if="latest">
                        上次成绩：<b>{{ latest.score }}</b> 分（答对 {{ latest.correctCount }}/{{ latest.totalCount }}）
                        <span class="last-time">{{ latest.submittedTime }}</span>
                    </div>
                </div>
            </template>

            <!-- 作答 -->
            <template v-else-if="phase === 'exam' && paper">
                <div class="card exam-top">
                    <span class="progress-text">已答 <b>{{ answeredCount }}</b>/{{ paper.questions.length }} 题</span>
                    <span class="countdown" :class="{ urgent: remainingSeconds <= 60 }">{{ countdownText }}</span>
                </div>

                <div class="card question-card" v-for="(q, qi) in paper.questions" :key="q.questionId">
                    <div class="q-head">
                        <span class="q-index">{{ qi + 1 }}</span>
                        <span class="type-tag">{{ q.questionType === 2 ? '多选题' : '单选题' }}</span>
                        <span class="difficulty-badge" :class="'d' + q.difficulty">{{ difficultyLabel(q.difficulty) }}</span>
                        <span class="q-tags" v-if="q.tags && q.tags.length">{{ q.tags.join(' · ') }}</span>
                    </div>
                    <div class="q-stem">{{ q.stem }}</div>
                    <div class="options">
                        <div v-for="(opt, oi) in q.options" :key="oi"
                             class="option-item"
                             :class="{ selected: isSelected(q.questionId, oi) }"
                             @click="toggleOption(q, oi)">
                            <span class="option-letter">{{ letter(oi) }}</span>
                            <span class="option-text">{{ opt }}</span>
                        </div>
                    </div>
                </div>

                <div class="card submit-bar">
                    <template v-if="!confirmingSubmit">
                        <button class="primary-btn" :disabled="submitting" @click="confirmingSubmit = true">交卷</button>
                        <span class="submit-hint">交卷后不可修改；未作答按错题计</span>
                    </template>
                    <template v-else>
                        <button class="primary-btn" :disabled="submitting" @click="doSubmit(false)">
                            {{ submitting ? '判分中...' : '确认交卷（已答 ' + answeredCount + '/' + paper.questions.length + ' 题）' }}
                        </button>
                        <button class="plain-btn" @click="confirmingSubmit = false">再检查一下</button>
                    </template>
                </div>
            </template>

            <!-- 成绩单 -->
            <template v-else-if="phase === 'result' && result">
                <div class="card result-card">
                    <div class="result-score">
                        <span class="score-value">{{ result.score }}</span>
                        <span class="score-unit">分</span>
                    </div>
                    <div class="result-meta">
                        答对 {{ result.correctCount }}/{{ result.totalCount }} 题
                        <template v-if="result.percentile > 0"> · 超过 {{ result.percentile }}% 的参与者</template>
                    </div>
                    <div class="result-time">{{ result.submittedTime }}</div>
                </div>

                <div class="card domain-card" v-if="domainList.length">
                    <div class="domain-title">领域分布</div>
                    <div class="domain-item" v-for="d in domainList" :key="d.tag">
                        <span class="domain-name">{{ d.tag }}</span>
                        <div class="domain-bar">
                            <div class="domain-bar-inner" :style="{ width: d.rate + '%' }"></div>
                        </div>
                        <span class="domain-num">{{ d.correct }}/{{ d.total }}</span>
                    </div>
                </div>

                <div class="card question-card" v-for="(it, ii) in result.items" :key="it.questionId">
                    <div class="q-head">
                        <span class="q-index">{{ ii + 1 }}</span>
                        <span class="result-tag" :class="it.correct ? 'ok' : 'fail'">{{ it.correct ? '答对' : '答错' }}</span>
                        <span class="q-tags" v-if="it.tags && it.tags.length">{{ it.tags.join(' · ') }}</span>
                    </div>
                    <div class="q-stem">{{ it.stem }}</div>
                    <div class="answer-line">
                        你的答案：<span :class="it.correct ? 'ok-text' : 'fail-text'">{{ letters(it.userAnswer) }}</span>
                    </div>
                    <div class="answer-line">
                        正确答案：<span class="ok-text">{{ letters(it.correctAnswer) }}</span>
                    </div>
                    <div class="explanation" v-if="it.explanation">
                        <span class="explain-label">解析</span>{{ it.explanation }}
                    </div>
                </div>

                <div class="card submit-bar">
                    <button class="primary-btn" @click="goAbility">查看能力档案</button>
                    <button class="plain-btn" @click="resetToIntro">返回</button>
                </div>
            </template>
        </template>
    </div>
</template>

<script>
    import {
        startCodingAssessment,
        getCurrentAssessment,
        submitAssessment,
        getLatestAssessment
    } from '@/apis/coding'
    import { toast } from '@/utils/toast'

    export default {
        name: 'CodingAssessment',
        data() {
            return {
                loading: false,
                starting: false,
                submitting: false,
                confirmingSubmit: false,
                phase: 'intro',
                paper: null,
                // questionId -> 已选下标数组
                answers: {},
                remainingSeconds: 0,
                result: null,
                latest: null,
                introMessage: '',
                timer: null,
                draftKey: ''
            }
        },
        computed: {
            isLoggedIn() {
                return this.$store.getters.isLoggedIn
            },
            answeredCount() {
                return Object.keys(this.answers).filter((k) => (this.answers[k] || []).length > 0).length
            },
            countdownText() {
                const s = Math.max(0, this.remainingSeconds)
                const m = Math.floor(s / 60)
                const sec = s % 60
                return (m < 10 ? '0' : '') + m + ':' + (sec < 10 ? '0' : '') + sec
            },
            domainList() {
                const stats = (this.result && this.result.domainStats) || {}
                return Object.keys(stats).map((tag) => {
                    const item = stats[tag] || {}
                    const total = item.total || 0
                    const correct = item.correct || 0
                    return {
                        tag,
                        total,
                        correct,
                        rate: total ? Math.round((correct * 100) / total) : 0
                    }
                }).sort((a, b) => b.total - a.total)
            }
        },
        mounted() {
            if (this.isLoggedIn) {
                this.loadCurrent()
            }
        },
        beforeDestroy() {
            this.stopTimer()
        },
        methods: {
            letter(index) {
                return String.fromCharCode(65 + index)
            },
            letters(list) {
                if (!list || !list.length) {
                    return '未作答'
                }
                return list.map((i) => this.letter(i)).join('、')
            },
            difficultyLabel(value) {
                if (value === 3) return '挑战'
                if (value === 2) return '进阶'
                return '入门'
            },
            goAbility() {
                this.$router.push('/coding/ability').catch(() => {})
            },
            showLogin() {
                this.$store.dispatch('showLogin')
            },
            // 进入页面先看有没有进行中的卷（刷新恢复），没有再展示介绍页
            async loadCurrent() {
                this.loading = true
                try {
                    const res = await getCurrentAssessment()
                    if (res && res.code === 200 && res.data && res.data.questions && res.data.questions.length) {
                        this.enterExam(res.data)
                    } else {
                        this.phase = 'intro'
                        this.loadLatest()
                    }
                } catch (e) {
                    this.phase = 'intro'
                } finally {
                    this.loading = false
                }
            },
            async loadLatest() {
                try {
                    const res = await getLatestAssessment()
                    if (res && res.code === 200) {
                        this.latest = res.data || null
                    }
                } catch (e) {
                    // 最近成绩加载失败不影响开卷
                }
            },
            async startAssessment() {
                if (this.starting) {
                    return
                }
                this.starting = true
                this.introMessage = ''
                try {
                    const res = await startCodingAssessment()
                    if (res && res.code === 200 && res.data) {
                        this.enterExam(res.data)
                        if (res.data.resumed) {
                            toast('继续上次的测评，计时不停', 2)
                        }
                    } else {
                        this.introMessage = (res && res.message) || '开卷失败，请稍后重试'
                    }
                } catch (e) {
                    // 冷却中/题库不足等业务拒绝（冷却 message 含下次可考时间）
                    this.introMessage = (e && e.message) || '开卷失败，请稍后重试'
                } finally {
                    this.starting = false
                }
            },
            enterExam(paper) {
                this.paper = paper
                this.phase = 'exam'
                this.confirmingSubmit = false
                this.remainingSeconds = paper.remainingSeconds || 0
                this.draftKey = 'coding_assessment_draft_' + paper.assessmentId
                this.answers = this.restoreDraft()
                this.startTimer()
                window.scrollTo({ top: 0 })
            },
            // 作答暂存 sessionStorage（刷新恢复；换设备/清缓存会丢，deadline 继续计时）
            restoreDraft() {
                try {
                    const raw = sessionStorage.getItem(this.draftKey)
                    if (!raw) {
                        return {}
                    }
                    const parsed = JSON.parse(raw)
                    if (parsed && parsed.assessmentId === this.paper.assessmentId && parsed.answers) {
                        return parsed.answers
                    }
                } catch (e) {
                    // ignore
                }
                return {}
            },
            saveDraft() {
                try {
                    sessionStorage.setItem(this.draftKey, JSON.stringify({
                        assessmentId: this.paper.assessmentId,
                        answers: this.answers
                    }))
                } catch (e) {
                    // ignore
                }
            },
            clearDraft() {
                try {
                    sessionStorage.removeItem(this.draftKey)
                } catch (e) {
                    // ignore
                }
            },
            // 倒计时以服务端剩余秒数起算；到点自动交卷（后端懒过期兜底）
            startTimer() {
                this.stopTimer()
                this.timer = setInterval(() => {
                    if (this.remainingSeconds > 0) {
                        this.remainingSeconds--
                    }
                    if (this.remainingSeconds <= 0) {
                        this.stopTimer()
                        this.doSubmit(true)
                    }
                }, 1000)
            },
            stopTimer() {
                if (this.timer) {
                    clearInterval(this.timer)
                    this.timer = null
                }
            },
            isSelected(questionId, idx) {
                return (this.answers[questionId] || []).indexOf(idx) >= 0
            },
            toggleOption(q, idx) {
                const key = q.questionId
                const current = (this.answers[key] || []).slice()
                if (q.questionType === 2) {
                    const pos = current.indexOf(idx)
                    if (pos >= 0) {
                        current.splice(pos, 1)
                    } else {
                        current.push(idx)
                    }
                } else {
                    // 单选：点击已选项取消，否则替换
                    if (current.length === 1 && current[0] === idx) {
                        current.length = 0
                    } else {
                        current.length = 0
                        current.push(idx)
                    }
                }
                this.$set(this.answers, key, current)
                this.saveDraft()
            },
            async doSubmit(auto) {
                if (this.submitting || !this.paper) {
                    return
                }
                this.submitting = true
                if (auto) {
                    toast('时间到，已自动交卷', 2)
                }
                try {
                    const payload = {
                        assessmentId: this.paper.assessmentId,
                        answers: this.paper.questions.map((q) => ({
                            questionId: q.questionId,
                            userAnswer: this.answers[q.questionId] || []
                        }))
                    }
                    const res = await submitAssessment(payload)
                    if (res && res.code === 200 && res.data) {
                        this.stopTimer()
                        this.result = res.data
                        this.phase = 'result'
                        this.clearDraft()
                        window.scrollTo({ top: 0 })
                    } else {
                        toast((res && res.message) || '交卷失败，请稍后重试', 2)
                    }
                } catch (e) {
                    const msg = (e && e.message) || '交卷失败，请稍后重试'
                    toast(msg, 2)
                    // 已过期：回到介绍页（成绩按过期处理）；其他错误留在作答页可重试
                    if (msg.indexOf('已过期') >= 0) {
                        this.stopTimer()
                        this.paper = null
                        this.phase = 'intro'
                        this.loadLatest()
                    }
                } finally {
                    this.submitting = false
                }
            },
            resetToIntro() {
                this.stopTimer()
                this.paper = null
                this.result = null
                this.answers = {}
                this.confirmingSubmit = false
                this.phase = 'intro'
                this.loadLatest()
            }
        }
    }
</script>

<style scoped>
    .assessment-page {
        padding-bottom: 40PX;
        min-height: 60vh;
    }

    .assessment-page * {
        box-sizing: border-box;
    }

    .assessment-header {
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

    .card {
        background: #fff;
        border-radius: 8PX;
        padding: 18PX 20PX;
        margin-bottom: 16PX;
    }

    .state-card {
        display: flex;
        flex-direction: column;
        align-items: center;
        gap: 10PX;
        padding: 48PX 20PX;
        color: #86909c;
    }

    .state-icon {
        font-size: 34PX;
    }

    .state-title {
        font-size: 16PX;
        color: #1d2129;
        font-weight: 500;
    }

    .state-desc {
        font-size: 13PX;
        color: #86909c;
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

    .primary-btn:disabled {
        opacity: 0.6;
        cursor: not-allowed;
    }

    .plain-btn {
        background: transparent;
        color: #4e5969;
        border: 1PX solid #e5e6eb;
        border-radius: 6PX;
        padding: 9PX 18PX;
        font-size: 14PX;
        cursor: pointer;
    }

    /* 介绍页 */
    .intro-card {
        padding: 28PX;
    }

    .intro-title {
        font-size: 18PX;
        font-weight: 600;
        color: #1d2129;
        margin-bottom: 14PX;
    }

    .intro-list {
        margin: 0 0 20PX;
        padding-left: 18PX;
        color: #4e5969;
        font-size: 14PX;
        line-height: 2;
    }

    .intro-actions {
        display: flex;
        align-items: center;
        gap: 14PX;
        flex-wrap: wrap;
    }

    .intro-hint {
        font-size: 13PX;
        color: #f53f3f;
    }

    .last-score {
        margin-top: 16PX;
        padding-top: 14PX;
        border-top: 1PX solid #f2f3f5;
        font-size: 13PX;
        color: #86909c;
    }

    .last-score b {
        color: #1E80FF;
        font-size: 16PX;
    }

    .last-time {
        margin-left: 8PX;
    }

    /* 作答页 */
    .exam-top {
        display: flex;
        align-items: center;
        justify-content: space-between;
        position: sticky;
        top: 0;
        z-index: 10;
    }

    .progress-text {
        font-size: 14PX;
        color: #4e5969;
    }

    .progress-text b {
        color: #1E80FF;
    }

    .countdown {
        font-size: 22PX;
        font-weight: 600;
        color: #1E80FF;
        font-variant-numeric: tabular-nums;
    }

    .countdown.urgent {
        color: #f53f3f;
    }

    .question-card .q-head {
        display: flex;
        align-items: center;
        gap: 10PX;
        margin-bottom: 10PX;
        flex-wrap: wrap;
    }

    .q-index {
        width: 22PX;
        height: 22PX;
        border-radius: 50%;
        background: #e8f3ff;
        color: #1E80FF;
        font-size: 12PX;
        display: inline-flex;
        align-items: center;
        justify-content: center;
        flex-shrink: 0;
    }

    .type-tag {
        font-size: 12PX;
        color: #1E80FF;
        background: #e8f3ff;
        border-radius: 4PX;
        padding: 1PX 8PX;
    }

    .difficulty-badge {
        font-size: 12PX;
        border-radius: 4PX;
        padding: 1PX 8PX;
    }

    .difficulty-badge.d1 {
        color: #00b42a;
        background: #e8ffea;
    }

    .difficulty-badge.d2 {
        color: #ff7d00;
        background: #fff7e8;
    }

    .difficulty-badge.d3 {
        color: #f53f3f;
        background: #ffece8;
    }

    .q-tags {
        font-size: 12PX;
        color: #86909c;
    }

    .q-stem {
        font-size: 15PX;
        color: #1d2129;
        line-height: 1.7;
        margin-bottom: 14PX;
    }

    .options {
        display: flex;
        flex-direction: column;
        gap: 10PX;
    }

    .option-item {
        display: flex;
        align-items: flex-start;
        gap: 10PX;
        border: 1PX solid #e5e6eb;
        border-radius: 6PX;
        padding: 10PX 14PX;
        cursor: pointer;
        transition: border-color 0.15s ease, background 0.15s ease;
    }

    .option-item:hover {
        border-color: #94bfff;
    }

    .option-item.selected {
        border-color: #1E80FF;
        background: #f2f8ff;
    }

    .option-letter {
        width: 20PX;
        height: 20PX;
        border-radius: 50%;
        border: 1PX solid #c9cdd4;
        color: #86909c;
        font-size: 12PX;
        display: inline-flex;
        align-items: center;
        justify-content: center;
        flex-shrink: 0;
        margin-top: 1PX;
    }

    .option-item.selected .option-letter {
        background: #1E80FF;
        border-color: #1E80FF;
        color: #fff;
    }

    .option-text {
        font-size: 14PX;
        color: #4e5969;
        line-height: 1.6;
    }

    .submit-bar {
        display: flex;
        align-items: center;
        gap: 14PX;
        flex-wrap: wrap;
    }

    .submit-hint {
        font-size: 13PX;
        color: #86909c;
    }

    /* 成绩单 */
    .result-card {
        text-align: center;
        padding: 30PX 20PX;
    }

    .result-score {
        display: flex;
        align-items: baseline;
        justify-content: center;
        gap: 4PX;
    }

    .score-value {
        font-size: 52PX;
        font-weight: 600;
        color: #1E80FF;
        line-height: 1;
    }

    .score-unit {
        font-size: 15PX;
        color: #86909c;
    }

    .result-meta {
        margin-top: 12PX;
        font-size: 14PX;
        color: #4e5969;
    }

    .result-time {
        margin-top: 6PX;
        font-size: 12PX;
        color: #c9cdd4;
    }

    .domain-title {
        font-size: 15PX;
        font-weight: 600;
        color: #1d2129;
        margin-bottom: 12PX;
    }

    .domain-item {
        display: flex;
        align-items: center;
        gap: 10PX;
        margin-bottom: 10PX;
    }

    .domain-item:last-child {
        margin-bottom: 0;
    }

    .domain-name {
        width: 90PX;
        font-size: 13PX;
        color: #4e5969;
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
        flex-shrink: 0;
    }

    .domain-bar {
        flex: 1;
        height: 8PX;
        background: #f2f3f5;
        border-radius: 4PX;
        overflow: hidden;
    }

    .domain-bar-inner {
        height: 100%;
        background: linear-gradient(90deg, #1E80FF, #4A9BFF);
        border-radius: 4PX;
        transition: width 0.3s ease;
    }

    .domain-num {
        width: 52PX;
        text-align: right;
        font-size: 12PX;
        color: #86909c;
        flex-shrink: 0;
    }

    .result-tag {
        font-size: 12PX;
        border-radius: 4PX;
        padding: 1PX 8PX;
    }

    .result-tag.ok {
        color: #00b42a;
        background: #e8ffea;
    }

    .result-tag.fail {
        color: #f53f3f;
        background: #ffece8;
    }

    .answer-line {
        font-size: 13PX;
        color: #86909c;
        margin-bottom: 6PX;
    }

    .ok-text {
        color: #00b42a;
        font-weight: 500;
    }

    .fail-text {
        color: #f53f3f;
        font-weight: 500;
    }

    .explanation {
        margin-top: 10PX;
        background: #f7f8fa;
        border-radius: 6PX;
        padding: 10PX 14PX;
        font-size: 13PX;
        color: #4e5969;
        line-height: 1.7;
    }

    .explain-label {
        color: #1E80FF;
        margin-right: 6PX;
        font-weight: 500;
    }
</style>