<template>
    <div class="coding-page">
        <!-- 头部：标题 + 统计速览 + 入口 -->
        <div class="coding-header">
            <div class="header-left">
                <h1 class="page-title">每日一题</h1>
                <span class="page-sub">每天一道简答，把编码能力讲在嘴上</span>
            </div>
            <div class="header-right" v-if="isLoggedIn">
                <div class="stat-chip">
                    <span class="chip-value">{{ stat.continuousDays || 0 }}</span>
                    <span class="chip-label">连续签到</span>
                </div>
                <div class="stat-chip">
                    <span class="chip-value">{{ stat.totalCount || 0 }}</span>
                    <span class="chip-label">已答题数</span>
                </div>
                <div class="stat-chip" v-if="stat.avgLevel">
                    <span class="chip-value">{{ stat.avgLevel }}</span>
                    <span class="chip-label">平均等级</span>
                </div>
                <button class="supply-btn" @click="goAbilityProfile">能力档案</button>
                <button class="supply-btn" @click="goInterview">模拟面试</button>
            </div>
        </div>

        <div class="coding-body">
            <div class="coding-main">
                <!-- 今日题目（简答） -->
                <div class="card daily-card">
                    <div class="card-head">
                        <div class="card-title">
                            今日题目
                            <span v-if="todayQuestion" class="difficulty-badge" :class="'d' + todayQuestion.difficulty">
                                {{ difficultyLabel(todayQuestion.difficulty) }}
                            </span>
                        </div>
                        <div class="difficulty-switch">
                            <input v-model="directionInput" class="form-input direction-input"
                                   placeholder="方向，如 Java 后端" @keyup.enter="changeDirection" />
                            <span class="switch-item" @click="changeDirection">换方向</span>
                        </div>
                    </div>

                    <div v-if="!isLoggedIn" class="login-hint">
                        <p>登录后开始答题：每天一道简答，写完即评（签到请到签到页）</p>
                        <button class="primary-btn" @click="showLogin">登录 / 注册</button>
                    </div>
                    <template v-else>
                        <div v-if="todayLoading" class="loading">题目加载中...</div>
                        <template v-else-if="todayQuestion">
                            <div class="stem-row">
                                <span class="type-tag">简答题</span>
                                <span class="stem-text">{{ todayQuestion.stem }}</span>
                            </div>

                            <div class="form-row" v-if="!todayAnswered">
                                <textarea v-model="answerText" class="form-textarea" rows="8"
                                          placeholder="用你自己的话讲清楚。考点会逐条对照，讲到了才算覆盖。"></textarea>
                            </div>
                            <div class="action-row" v-if="!todayAnswered">
                                <button class="primary-btn"
                                        :disabled="submitting || !answerText || !answerText.trim()"
                                        @click="submitAnswer">
                                    {{ submitting ? '评估中...' : '提交作答' }}
                                </button>
                                <span class="hint">每天只有一次作答机会，提交后锁定</span>
                            </div>

                            <div v-else class="result-block">
                                <div class="result-head">
                                    <span class="result-tag ok" v-if="todayResult">
                                        {{ todayResult.level ? '综合等级 ' + todayResult.level + '/5' : '未评估' }}
                                    </span>
                                    <span v-if="todayResult && todayResult.scoreAwarded > 0" class="gain">
                                        +{{ todayResult.scoreAwarded }} 逐日分
                                    </span>
                                    <span class="used" v-if="todayResult && todayResult.elapsedSeconds">
                                        用时 {{ todayResult.elapsedSeconds }}s
                                    </span>
                                </div>
                                <template v-if="todayResult">
                                    <div class="explanation" v-if="todayResult.feedback">
                                        <span class="explain-label">点评</span>{{ todayResult.feedback }}
                                    </div>
                                    <div class="coverage-line" v-if="todayResult.covered && todayResult.covered.length">
                                        <span class="explain-label ok">已覆盖</span>{{ todayResult.covered.join('、') }}
                                    </div>
                                    <div class="coverage-line" v-if="todayResult.missing && todayResult.missing.length">
                                        <span class="explain-label miss">待补强</span>{{ todayResult.missing.join('、') }}
                                    </div>
                                </template>
                                <div class="tip-line">明天还有新题，再来挑战</div>
                            </div>
                        </template>
                        <div v-else class="loading">题库准备中，暂无可用题目，请稍后再来</div>
                    </template>
                </div>
            </div>

            <!-- 侧栏：我的统计 -->
            <div class="coding-aside">
                <div class="card stat-card" v-if="isLoggedIn">
                    <div class="card-title">我的统计</div>
                    <div class="stat-grid">
                        <div class="stat-cell">
                            <span class="cell-value">{{ stat.continuousDays || 0 }}</span>
                            <span class="cell-label">连续签到天数</span>
                        </div>
                        <div class="stat-cell">
                            <span class="cell-value">{{ stat.totalCount || 0 }}</span>
                            <span class="cell-label">已答简答数</span>
                        </div>
                        <div class="stat-cell">
                            <span class="cell-value small-value">
                                {{ stat.todayAnswered ? (stat.todayLevel ? '等级 ' + stat.todayLevel + '/5' : '未评估') : '未作答' }}
                            </span>
                            <span class="cell-label">今日状态</span>
                        </div>
                        <div class="stat-cell">
                            <span class="cell-value small-value">{{ stat.direction || '默认方向' }}</span>
                            <span class="cell-label">当前方向</span>
                        </div>
                    </div>
                    <div class="tag-stats" v-if="tagStatsList.length">
                        <div class="tag-stat-row" v-for="t in tagStatsList" :key="t.name">
                            <span class="tag-name">{{ t.name }}</span>
                            <div class="tag-bar"><div class="tag-bar-inner" :style="{ width: t.rate + '%' }"></div></div>
                            <span class="tag-rate">{{ t.rate }}</span>
                        </div>
                    </div>
                </div>
            </div>
        </div>
    </div>
</template>

<script>
    import { toast } from '@/utils/toast'
    import {
        getTodayQuestion,
        submitCodingAnswer,
        getCodingStat
    } from '@/apis/coding'

    export default {
        name: 'CodingDaily',
        data() {
            return {
                difficultyOptions: [
                    { value: 1, label: '入门' },
                    { value: 2, label: '进阶' },
                    { value: 3, label: '挑战' }
                ],
                directionInput: '',
                todayLoading: false,
                todayQuestion: null,
                todayAnswered: false,
                todayResult: null,
                submitting: false,
                answerText: '',
                stat: {
                    continuousDays: 0,
                    todayAnswered: false,
                    todayLevel: null,
                    totalCount: 0,
                    avgLevel: null,
                    direction: '',
                    tagStats: {}
                }
            }
        },
        computed: {
            isLoggedIn() {
                return this.$store.getters.isLoggedIn
            },
            tagStatsList() {
                const stats = this.stat.tagStats || {}
                const list = []
                Object.keys(stats).forEach((name) => {
                    const item = stats[name] || {}
                    const total = item.total || 0
                    const levelSum = item.levelSum || 0
                    if (total > 0) {
                        // 领域分布的口径是平均等级（1-5），不是正确率
                        list.push({ name, rate: (levelSum / total).toFixed(1) })
                    }
                })
                return list.sort((a, b) => b.rate - a.rate).slice(0, 6)
            }
        },
        mounted() {
            this.loadToday()
            if (this.isLoggedIn) {
                this.loadStat()
            }
        },
        methods: {
            showLogin() {
                this.$store.dispatch('showLogin')
            },
            goAbilityProfile() {
                this.$router.push('/coding/ability').catch(() => {})
            },
            goInterview() {
                this.$router.push('/coding/interview').catch(() => {})
            },
            difficultyLabel(value) {
                const found = this.difficultyOptions.find((item) => item.value === value)
                return found ? found.label : '进阶'
            },
            changeDirection() {
                const next = (this.directionInput || '').trim()
                if (!next) {
                    toast('请先输入方向', 2)
                    return
                }
                if (this.todayQuestion && this.todayQuestion.direction === next) {
                    return
                }
                // 换方向即重抽今日题（当天还没答过才有意义；答过会被服务端拒绝并回放结果）
                this.directionInput = next
                this.loadToday(next)
            },
            async loadToday(direction) {
                if (!this.isLoggedIn) {
                    this.todayQuestion = null
                    return
                }
                this.todayLoading = true
                try {
                    const res = await getTodayQuestion(direction || undefined)
                    if (res && res.code === 200 && res.data) {
                        const question = res.data
                        this.todayQuestion = question
                        this.directionInput = question.direction || this.directionInput
                        if (question.answered) {
                            this.todayAnswered = true
                            this.todayResult = {
                                level: question.level,
                                feedback: question.feedback || '',
                                covered: question.covered || [],
                                missing: question.missing || [],
                                scoreAwarded: question.scoreAwarded || 0,
                                elapsedSeconds: question.elapsedSeconds
                            }
                            this.answerText = question.userAnswer || ''
                        } else {
                            this.todayAnswered = false
                            this.todayResult = null
                            this.answerText = ''
                        }
                    } else {
                        this.todayQuestion = null
                        if (res && res.code !== 200 && res.message) {
                            toast(res.message, 2)
                        }
                    }
                } catch (e) {
                    window.__todayErr = { msg: e && e.message, code: e && e.code, body: JSON.stringify(e).slice(0, 300) }
                    this.todayQuestion = null
                } finally {
                    this.todayLoading = false
                }
            },
            async submitAnswer() {
                if (!this.isLoggedIn) {
                    toast('登录后才能提交作答', 2)
                    this.showLogin()
                    return
                }
                const question = this.todayQuestion
                const text = (this.answerText || '').trim()
                if (!question || !question.id || !text) {
                    return
                }
                this.submitting = true
                try {
                    const res = await submitCodingAnswer({
                        poolId: question.id,
                        answerText: text,
                        elapsedSeconds: null
                    })
                    if (!res || res.code !== 200 || !res.data) {
                        toast((res && res.message) || '提交失败，请稍后重试', 2)
                        // 今日题可能已被刷新，重新拉取保持界面与服务端一致
                        if (res && res.code === 400) {
                            this.loadToday()
                        }
                        return
                    }
                    const data = res.data
                    this.todayAnswered = true
                    this.todayResult = {
                        level: data.level,
                        feedback: data.feedback || '',
                        covered: data.covered || [],
                        missing: data.missing || [],
                        scoreAwarded: data.scoreAwarded || 0,
                        elapsedSeconds: null
                    }
                    if (data.pending) {
                        toast('本次未生成评估结果，可稍后重试', 2)
                    } else {
                        toast('已提交，综合等级 ' + data.level + '/5', 2)
                    }
                    this.loadStat()
                } catch (e) {
                    toast('网络异常，请稍后重试', 2)
                } finally {
                    this.submitting = false
                }
            },
            async loadStat() {
                if (!this.isLoggedIn) {
                    return
                }
                try {
                    const res = await getCodingStat()
                    if (res && res.code === 200 && res.data) {
                        this.stat = Object.assign({}, this.stat, res.data)
                    }
                } catch (e) {
                    // 统计加载失败不影响主流程
                }
            }
        }
    }
</script>

<style scoped>
    .coding-page {
        padding-bottom: 40PX;
        min-height: 60vh;
    }

    .coding-page * {
        box-sizing: border-box;
    }

    /* 头部 */
    .coding-header {
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
        gap: 14PX;
    }

    .stat-chip {
        display: flex;
        flex-direction: column;
        align-items: center;
        min-width: 64PX;
    }

    .chip-value {
        font-size: 20PX;
        font-weight: 600;
    }

    .chip-label {
        font-size: 12PX;
        opacity: 0.9;
    }

    .supply-btn {
        border: 1PX solid rgba(255, 255, 255, 0.8);
        background: transparent;
        color: #fff;
        border-radius: 16PX;
        padding: 7PX 16PX;
        font-size: 13PX;
        cursor: pointer;
    }

    .supply-btn:hover {
        background: rgba(255, 255, 255, 0.15);
    }

    /* 布局 */
    .coding-body {
        display: flex;
        gap: 16PX;
        align-items: flex-start;
    }

    .coding-main {
        flex: 1;
        min-width: 0;
        display: flex;
        flex-direction: column;
        gap: 16PX;
    }

    .coding-aside {
        width: 300PX;
        flex-shrink: 0;
        display: flex;
        flex-direction: column;
        gap: 16PX;
    }

    .card {
        background: #fff;
        border-radius: 8PX;
        padding: 18PX 20PX;
    }

    .card-head {
        display: flex;
        align-items: center;
        justify-content: space-between;
        flex-wrap: wrap;
        gap: 8PX;
        margin-bottom: 14PX;
    }

    .card-title {
        font-size: 16PX;
        font-weight: 600;
        color: #222;
        display: flex;
        align-items: center;
        gap: 8PX;
    }

    .card-tip {
        font-size: 12PX;
        font-weight: 400;
        color: #999;
    }

    .difficulty-badge {
        font-size: 12PX;
        font-weight: 400;
        color: #1E80FF;
        background: #E8F3FF;
        border-radius: 10PX;
        padding: 2PX 10PX;
    }

    .difficulty-badge.d2 {
        color: #FA8C16;
        background: #FFF3E6;
    }

    .difficulty-badge.d3 {
        color: #F5222D;
        background: #FFEDED;
    }

    .difficulty-switch {
        display: flex;
        align-items: center;
        gap: 8PX;
        white-space: nowrap;
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

    /* 今日题 */
    .login-hint {
        padding: 30PX 0;
        text-align: center;
        color: #666;
        font-size: 14PX;
    }

    .login-hint p {
        margin-bottom: 14PX;
    }

    .stem-row {
        display: flex;
        gap: 8PX;
        margin-bottom: 14PX;
    }

    .type-tag {
        flex-shrink: 0;
        font-size: 12PX;
        color: #1E80FF;
        background: #E8F3FF;
        border-radius: 4PX;
        padding: 2PX 8PX;
        height: fit-content;
        margin-top: 2PX;
    }

    .stem-text {
        font-size: 15PX;
        color: #222;
        line-height: 1.7;
        white-space: pre-wrap;
        word-break: break-word;
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
        transition: all 0.15s;
        line-height: 1.6;
    }

    .option-item:hover {
        border-color: #B8D8FF;
    }

    .option-item.selected {
        border-color: #1E80FF;
        background: #F0F7FF;
    }

    .option-item.correct-flag {
        border-color: #52C41A;
        background: #F0FFF0;
    }

    .option-item.wrong-flag {
        border-color: #F5222D;
        background: #FFF1F0;
    }

    .option-letter {
        font-weight: 600;
        color: #1E80FF;
        flex-shrink: 0;
    }

    .option-text {
        font-size: 14PX;
        color: #333;
        word-break: break-word;
    }

    .action-row {
        margin-top: 16PX;
        display: flex;
        align-items: center;
        gap: 12PX;
        flex-wrap: wrap;
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

    .hint {
        font-size: 12PX;
        color: #999;
    }

    .result-block {
        margin-top: 16PX;
        border-top: 1PX dashed #eee;
        padding-top: 14PX;
    }

    .result-head {
        display: flex;
        align-items: center;
        gap: 12PX;
        flex-wrap: wrap;
    }

    .result-tag {
        font-size: 13PX;
        border-radius: 4PX;
        padding: 3PX 10PX;
    }

    .result-tag.ok {
        color: #52C41A;
        background: #F0FFF0;
    }

    .result-tag.fail {
        color: #F5222D;
        background: #FFF1F0;
    }

    .gain {
        font-size: 13PX;
        color: #FA8C16;
    }

    .used {
        font-size: 12PX;
        color: #999;
    }

    .explanation {
        margin-top: 10PX;
        font-size: 13PX;
        color: #555;
        line-height: 1.7;
        background: #F7F8FA;
        border-radius: 6PX;
        padding: 10PX 12PX;
        word-break: break-word;
    }

    .explain-label {
        color: #1E80FF;
        font-weight: 600;
        margin-right: 6PX;
    }

    .source-link {
        margin-top: 10PX;
        font-size: 13PX;
        color: #1E80FF;
        cursor: pointer;
    }

    .source-link:hover {
        text-decoration: underline;
    }

    .tip-line {
        margin-top: 10PX;
        font-size: 12PX;
        color: #999;
    }

    .loading {
        padding: 24PX 0;
        text-align: center;
        color: #999;
        font-size: 13PX;
    }

    /* 题库练习 */
    .filter-banner {
        display: flex;
        align-items: center;
        justify-content: space-between;
        background: #F0F7FF;
        border-radius: 6PX;
        padding: 8PX 12PX;
        margin-bottom: 10PX;
    }

    .filter-text {
        font-size: 13PX;
        color: #1E80FF;
    }

    .filter-clear {
        font-size: 13PX;
        color: #86909C;
        cursor: pointer;
    }

    .filter-clear:hover {
        color: #1E80FF;
    }

    .practice-item {
        border-bottom: 1PX solid #f2f3f5;
    }

    .practice-item:last-of-type {
        border-bottom: none;
    }

    .practice-row {
        padding: 12PX 4PX;
        cursor: pointer;
    }

    .practice-row:hover .practice-stem {
        color: #1E80FF;
    }

    .practice-stem {
        font-size: 14PX;
        color: #333;
        line-height: 1.6;
        word-break: break-word;
    }

    .practice-meta {
        margin-top: 6PX;
        display: flex;
        gap: 6PX;
        flex-wrap: wrap;
    }

    .mini-tag {
        font-size: 12PX;
        color: #86909C;
        background: #F2F3F5;
        border-radius: 4PX;
        padding: 1PX 8PX;
    }

    .mini-tag.done {
        color: #52C41A;
        background: #F0FFF0;
    }

    .practice-detail {
        padding: 6PX 4PX 16PX;
    }

    .pager {
        display: flex;
        align-items: center;
        justify-content: center;
        gap: 16PX;
        padding-top: 14PX;
    }

    .pager-btn {
        font-size: 13PX;
        color: #1E80FF;
        cursor: pointer;
    }

    .pager-btn.disabled {
        color: #c9cdd4;
        cursor: not-allowed;
    }

    .pager-info {
        font-size: 13PX;
        color: #86909C;
    }

    /* 榜单 */
    .rank-tabs {
        display: flex;
        gap: 6PX;
        margin-bottom: 10PX;
    }

    .rank-tab {
        font-size: 12PX;
        color: #666;
        padding: 3PX 12PX;
        border-radius: 12PX;
        cursor: pointer;
    }

    .rank-tab.active {
        color: #1E80FF;
        background: #E8F3FF;
    }

    .rank-list {
        display: flex;
        flex-direction: column;
    }

    .rank-item {
        display: flex;
        align-items: center;
        gap: 8PX;
        padding: 8PX 0;
        border-bottom: 1PX solid #f2f3f5;
    }

    .rank-item:last-child {
        border-bottom: none;
    }

    .rank-item.self {
        background: #F0F7FF;
        border-radius: 4PX;
    }

    .rank-no {
        width: 20PX;
        text-align: center;
        font-size: 13PX;
        color: #86909C;
        font-weight: 600;
        flex-shrink: 0;
    }

    .rank-no.top1 {
        color: #FFB800;
    }

    .rank-no.top2 {
        color: #9BA7B5;
    }

    .rank-no.top3 {
        color: #D4854A;
    }

    .rank-avatar {
        width: 24PX;
        height: 24PX;
        border-radius: 50%;
        object-fit: cover;
        flex-shrink: 0;
    }

    .rank-avatar.default {
        display: inline-flex;
        align-items: center;
        justify-content: center;
        background: #E8F3FF;
        color: #1E80FF;
        font-family: fontawesome;
        font-size: 12PX;
    }

    .rank-name {
        flex: 1;
        font-size: 13PX;
        color: #333;
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
    }

    .rank-score {
        font-size: 12PX;
        color: #86909C;
        flex-shrink: 0;
    }

    /* 我的统计 */
    .stat-grid {
        display: grid;
        grid-template-columns: 1fr 1fr;
        gap: 10PX;
    }

    .stat-cell {
        background: #F7F8FA;
        border-radius: 6PX;
        padding: 10PX 12PX;
        display: flex;
        flex-direction: column;
        gap: 4PX;
    }

    .cell-value {
        font-size: 18PX;
        font-weight: 600;
        color: #222;
    }

    .cell-value.small-value {
        font-size: 15PX;
    }

    .cell-label {
        font-size: 12PX;
        color: #86909C;
    }

    .tag-stats {
        margin-top: 12PX;
        display: flex;
        flex-direction: column;
        gap: 8PX;
    }

    .tag-stat-row {
        display: flex;
        align-items: center;
        gap: 8PX;
    }

    .tag-name {
        width: 64PX;
        font-size: 12PX;
        color: #555;
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
        flex-shrink: 0;
    }

    .tag-bar {
        flex: 1;
        height: 6PX;
        background: #F2F3F5;
        border-radius: 3PX;
        overflow: hidden;
    }

    .tag-bar-inner {
        height: 100%;
        background: #1E80FF;
        border-radius: 3PX;
    }

    .tag-rate {
        width: 36PX;
        text-align: right;
        font-size: 12PX;
        color: #86909C;
        flex-shrink: 0;
    }

    /* 出题弹窗 */
    .supply-overlay {
        position: fixed;
        left: 0;
        top: 0;
        right: 0;
        bottom: 0;
        background: rgba(0, 0, 0, 0.45);
        display: flex;
        align-items: center;
        justify-content: center;
        z-index: 1000;
    }

    .supply-dialog {
        width: 560PX;
        max-width: calc(100vw - 32PX);
        max-height: 86vh;
        overflow-y: auto;
        background: #fff;
        border-radius: 10PX;
        padding: 18PX 22PX 22PX;
    }

    .dialog-head {
        display: flex;
        align-items: center;
        justify-content: space-between;
        margin-bottom: 12PX;
    }

    .dialog-title {
        font-size: 16PX;
        font-weight: 600;
        color: #222;
    }

    .dialog-close {
        cursor: pointer;
        color: #86909C;
        font-size: 14PX;
    }

    .dialog-tabs {
        display: flex;
        gap: 18PX;
        border-bottom: 1PX solid #f2f3f5;
        margin-bottom: 14PX;
    }

    .dialog-tab {
        font-size: 14PX;
        color: #666;
        padding-bottom: 8PX;
        cursor: pointer;
        border-bottom: 2PX solid transparent;
    }

    .dialog-tab.active {
        color: #1E80FF;
        border-bottom-color: #1E80FF;
    }

    .dialog-desc {
        font-size: 13PX;
        color: #86909C;
        margin: 0 0 12PX;
        line-height: 1.6;
    }

    .form-row {
        margin-bottom: 12PX;
    }

    .inline-row {
        display: flex;
        align-items: center;
        gap: 8PX;
        flex-wrap: wrap;
    }

    .form-label {
        font-size: 13PX;
        color: #555;
    }

    .form-input,
    .form-textarea {
        width: 100%;
        border: 1PX solid #e5e6eb;
        border-radius: 6PX;
        padding: 8PX 12PX;
        font-size: 13PX;
        color: #333;
        outline: none;
    }

    .form-input:focus,
    .form-textarea:focus {
        border-color: #1E80FF;
    }

    .form-textarea {
        min-height: 64PX;
        resize: vertical;
        font-family: inherit;
    }

    .form-textarea.small {
        min-height: 48PX;
    }

    .option-edit-row {
        display: flex;
        align-items: center;
        gap: 8PX;
        margin-top: 8PX;
    }

    .opt-check {
        width: 24PX;
        height: 24PX;
        border-radius: 50%;
        border: 1PX solid #e5e6eb;
        display: inline-flex;
        align-items: center;
        justify-content: center;
        font-size: 12PX;
        color: #86909C;
        cursor: pointer;
        flex-shrink: 0;
    }

    .opt-check.checked {
        border-color: #1E80FF;
        background: #E8F3FF;
        color: #1E80FF;
    }

    .opt-remove {
        color: #c9cdd4;
        cursor: pointer;
        font-size: 12PX;
        flex-shrink: 0;
    }

    .add-option {
        display: inline-block;
        margin-top: 8PX;
        font-size: 13PX;
        color: #1E80FF;
        cursor: pointer;
    }

    .dialog-actions {
        display: flex;
        align-items: center;
        gap: 12PX;
        margin-top: 6PX;
    }

    .dialog-tip {
        font-size: 12PX;
        color: #999;
    }

    .gen-result {
        margin-top: 14PX;
        background: #F7F8FA;
        border-radius: 6PX;
        padding: 12PX 14PX;
    }

    .gen-summary {
        font-size: 13PX;
        color: #333;
        margin-bottom: 8PX;
    }

    .gen-item {
        font-size: 13PX;
        color: #555;
        line-height: 1.7;
        word-break: break-word;
    }

    /* 移动端适配 */
    @media (max-width: 768PX) {
        .coding-body {
            flex-direction: column;
        }

        .coding-aside {
            width: 100%;
        }

        .coding-header {
            padding: 16PX 18PX;
        }
    }
</style>