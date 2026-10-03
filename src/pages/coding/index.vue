<template>
    <div class="coding-page">
        <!-- 头部：标题 + 我的统计速览 + 出题入口 -->
        <div class="coding-header">
            <div class="header-left">
                <h1 class="page-title">每日一题</h1>
                <span class="page-sub">每天一道题，把编码能力练在身上</span>
            </div>
            <div class="header-right" v-if="isLoggedIn">
                <div class="stat-chip">
                    <span class="chip-value">{{ stat.continuousDays || 0 }}</span>
                    <span class="chip-label">连续天数</span>
                </div>
                <div class="stat-chip">
                    <span class="chip-value">{{ stat.accuracy || 0 }}%</span>
                    <span class="chip-label">正确率</span>
                </div>
                <div class="stat-chip">
                    <span class="chip-value">{{ stat.totalCount || 0 }}</span>
                    <span class="chip-label">已答题数</span>
                </div>
                <button class="supply-btn" @click="goAbilityProfile">能力档案</button>
                <button class="supply-btn" @click="openSupplyDialog">出题投稿</button>
            </div>
        </div>

        <div class="coding-body">
            <div class="coding-main">
                <!-- 今日题目 -->
                <div class="card daily-card">
                    <div class="card-head">
                        <div class="card-title">
                            今日题目
                            <span v-if="todayQuestion" class="difficulty-badge" :class="'d' + todayQuestion.difficulty">
                                {{ difficultyLabel(todayQuestion.difficulty) }}
                            </span>
                        </div>
                        <div class="difficulty-switch">
                            <span v-for="opt in difficultyOptions" :key="opt.value"
                                  class="switch-item" :class="{ active: difficulty === opt.value }"
                                  @click="switchDifficulty(opt.value)">{{ opt.label }}</span>
                        </div>
                    </div>

                    <div v-if="!isLoggedIn" class="login-hint">
                        <p>登录后开始答题：答对可得逐日分，连续天数与签到共用一份记录</p>
                        <button class="primary-btn" @click="showLogin">登录 / 注册</button>
                    </div>
                    <template v-else>
                        <div v-if="todayLoading" class="loading">题目加载中...</div>
                        <template v-else-if="todayQuestion">
                            <div class="stem-row">
                                <span class="type-tag">{{ todayQuestion.questionType === 2 ? '多选题' : '单选题' }}</span>
                                <span class="stem-text">{{ todayQuestion.stem }}</span>
                            </div>
                            <div class="options">
                                <div v-for="(opt, idx) in todayQuestion.options" :key="idx"
                                     class="option-item"
                                     :class="todayOptionClass(idx)"
                                     @click="!todayAnswered && toggleSelect(idx, true)">
                                    <span class="option-letter">{{ letter(idx) }}</span>
                                    <span class="option-text">{{ opt }}</span>
                                </div>
                            </div>
                            <div class="action-row" v-if="!todayAnswered">
                                <button class="primary-btn"
                                        :disabled="submitting || selected.length === 0"
                                        @click="submitAnswer(true)">
                                    {{ submitting ? '判分中...' : '提交答案' }}
                                </button>
                                <span class="hint">每天只有一次作答机会，答错也会锁定，请想好再交</span>
                            </div>
                            <div v-else class="result-block">
                                <div class="result-head">
                                    <span class="result-tag" :class="todayResult && todayResult.isCorrect ? 'ok' : 'fail'">
                                        {{ todayResult && todayResult.isCorrect ? '回答正确' : '回答错误' }}
                                    </span>
                                    <span v-if="todayResult && todayResult.scoreAwarded > 0" class="gain">
                                        +{{ todayResult.scoreAwarded }} 逐日分
                                    </span>
                                    <span v-if="todayResult && todayResult.continuousDays" class="gain">
                                        连续 {{ todayResult.continuousDays }} 天
                                    </span>
                                    <span class="used" v-if="todayResult && todayResult.elapsedSeconds">
                                        用时 {{ todayResult.elapsedSeconds }}s
                                    </span>
                                </div>
                                <div class="explanation" v-if="todayResult && todayResult.explanation">
                                    <span class="explain-label">解析</span>{{ todayResult.explanation }}
                                </div>
                                <div class="source-link" v-if="todayResult && todayResult.sourceArticleTitle"
                                     @click="openArticle(todayResult.sourceArticleId)">
                                    延伸阅读：{{ todayResult.sourceArticleTitle }}
                                </div>
                                <div class="tip-line">明天再来，连续答题不断档</div>
                            </div>
                        </template>
                        <div v-else class="loading">题库准备中，暂无可用题目，请稍后再来</div>
                    </template>
                </div>

                <!-- 题库练习 -->
                <div class="card practice-card">
                    <div class="card-head">
                        <div class="card-title">
                            题库练习
                            <span class="card-tip">自由练习不计分，只沉淀统计</span>
                        </div>
                        <div class="difficulty-switch">
                            <span class="switch-item" :class="{ active: practiceDifficulty === null }"
                                  @click="switchPracticeDifficulty(null)">全部</span>
                            <span v-for="opt in difficultyOptions" :key="opt.value"
                                  class="switch-item" :class="{ active: practiceDifficulty === opt.value }"
                                  @click="switchPracticeDifficulty(opt.value)">{{ opt.label }}</span>
                        </div>
                    </div>

                    <!-- 来源文章过滤（文章详情页"相关练习"跳转而来） -->
                    <div class="filter-banner" v-if="articleFilter">
                        <span class="filter-text">仅显示本文相关题目</span>
                        <span class="filter-clear" @click="clearArticleFilter">查看全部题目</span>
                    </div>

                    <div v-if="questionList.length === 0" class="loading">暂无题目</div>
                    <template v-else>
                        <div v-for="q in questionList" :key="q.id" class="practice-item">
                            <div class="practice-row" @click="togglePractice(q)">
                                <div class="practice-stem">{{ q.stem }}</div>
                                <div class="practice-meta">
                                    <span class="mini-tag">{{ difficultyLabel(q.difficulty) }}</span>
                                    <span class="mini-tag" v-for="tag in (q.tags || [])" :key="tag">{{ tag }}</span>
                                    <span class="mini-tag done" v-if="q.answered">已答</span>
                                </div>
                            </div>
                            <div class="practice-detail" v-if="practiceActive && practiceActive.id === q.id">
                                <div class="options">
                                    <div v-for="(opt, idx) in q.options" :key="idx"
                                         class="option-item"
                                         :class="practiceOptionClass(idx)"
                                         @click="!practiceResult && toggleSelect(idx, false)">
                                        <span class="option-letter">{{ letter(idx) }}</span>
                                        <span class="option-text">{{ opt }}</span>
                                    </div>
                                </div>
                                <div class="action-row" v-if="!practiceResult">
                                    <button class="primary-btn small"
                                            :disabled="submittingPractice || practiceSelected.length === 0"
                                            @click="submitAnswer(false)">
                                        {{ submittingPractice ? '判分中...' : '提交练习' }}
                                    </button>
                                </div>
                                <div v-else class="result-block">
                                    <div class="result-head">
                                        <span class="result-tag" :class="practiceResult.isCorrect ? 'ok' : 'fail'">
                                            {{ practiceResult.isCorrect ? '回答正确' : '回答错误' }}
                                        </span>
                                    </div>
                                    <div class="explanation" v-if="practiceResult.explanation">
                                        <span class="explain-label">解析</span>{{ practiceResult.explanation }}
                                    </div>
                                    <div class="source-link" v-if="practiceResult.sourceArticleTitle"
                                         @click="openArticle(practiceResult.sourceArticleId)">
                                        延伸阅读：{{ practiceResult.sourceArticleTitle }}
                                    </div>
                                </div>
                            </div>
                        </div>
                        <div class="pager">
                            <span class="pager-btn" :class="{ disabled: page <= 1 }" @click="changePage(-1)">上一页</span>
                            <span class="pager-info">{{ page }} / {{ totalPages }}</span>
                            <span class="pager-btn" :class="{ disabled: page >= totalPages }" @click="changePage(1)">下一页</span>
                        </div>
                    </template>
                </div>
            </div>

            <!-- 侧栏：榜单 + 我的统计 -->
            <div class="coding-aside">
                <div class="card ranking-card">
                    <div class="card-title">答题榜单</div>
                    <div class="rank-tabs">
                        <span v-for="t in rankingTabs" :key="t.value" class="rank-tab"
                              :class="{ active: rankingPeriod === t.value }"
                              @click="switchRanking(t.value)">{{ t.label }}</span>
                    </div>
                    <div v-if="ranking.length === 0" class="loading">暂无上榜记录，快来抢占榜首</div>
                    <div v-else class="rank-list">
                        <div v-for="item in ranking" :key="item.userId" class="rank-item"
                             :class="{ self: item.isSelf }">
                            <span class="rank-no" :class="'top' + item.rank">{{ item.rank }}</span>
                            <img v-if="item.avatar" class="rank-avatar" :src="item.avatar" alt="">
                            <span v-else class="rank-avatar default">&#xf007;</span>
                            <span class="rank-name">{{ item.nickname || ('用户' + item.userId) }}</span>
                            <span class="rank-score">{{ item.correctCount }}题 · {{ item.accuracy }}%</span>
                        </div>
                    </div>
                </div>

                <div class="card stat-card" v-if="isLoggedIn">
                    <div class="card-title">我的统计</div>
                    <div class="stat-grid">
                        <div class="stat-cell">
                            <span class="cell-value">{{ stat.continuousDays || 0 }}</span>
                            <span class="cell-label">连续天数（与签到同步）</span>
                        </div>
                        <div class="stat-cell">
                            <span class="cell-value">{{ stat.accuracy || 0 }}%</span>
                            <span class="cell-label">每日一题正确率</span>
                        </div>
                        <div class="stat-cell">
                            <span class="cell-value">{{ stat.practiceCount || 0 }}</span>
                            <span class="cell-label">练习次数</span>
                        </div>
                        <div class="stat-cell">
                            <span class="cell-value small-value">
                                {{ stat.todayAnswered ? (stat.todayCorrect ? '已答对' : '已答错') : '未作答' }}
                            </span>
                            <span class="cell-label">今日状态</span>
                        </div>
                    </div>
                    <div class="tag-stats" v-if="tagStatsList.length">
                        <div class="tag-stat-row" v-for="t in tagStatsList" :key="t.name">
                            <span class="tag-name">{{ t.name }}</span>
                            <div class="tag-bar"><div class="tag-bar-inner" :style="{ width: t.rate + '%' }"></div></div>
                            <span class="tag-rate">{{ t.rate }}%</span>
                        </div>
                    </div>
                </div>
            </div>
        </div>

        <!-- 出题投稿弹窗：AI 从文章生成 / 手动投稿 -->
        <div class="supply-overlay" v-if="supplyVisible" @click.self="supplyVisible = false">
            <div class="supply-dialog">
                <div class="dialog-head">
                    <span class="dialog-title">出题投稿</span>
                    <span class="dialog-close" @click="supplyVisible = false">&#10005;</span>
                </div>
                <div class="dialog-tabs">
                    <span class="dialog-tab" :class="{ active: supplyTab === 'ai' }"
                          @click="supplyTab = 'ai'">从我的文章生成</span>
                    <span class="dialog-tab" :class="{ active: supplyTab === 'manual' }"
                          @click="supplyTab = 'manual'">手动投稿</span>
                </div>

                <div v-if="supplyTab === 'ai'" class="dialog-body">
                    <p class="dialog-desc">输入自己的已发布文章 ID，AI 会从正文提炼最多 3 道选择题入池（每日限 5 次）。</p>
                    <div class="form-row">
                        <input v-model="genArticleId" class="form-input" placeholder="文章 ID，如 123456" />
                        <button class="primary-btn small" :disabled="generating" @click="handleGenerate">
                            {{ generating ? '生成中...' : '生成题目' }}
                        </button>
                    </div>
                    <div class="gen-result" v-if="genResult">
                        <div class="gen-summary">
                            生成 {{ genResult.generated }} 道，入池 {{ genResult.inserted }} 道，重复跳过 {{ genResult.skipped }} 道
                        </div>
                        <div class="gen-item" v-for="item in (genResult.list || [])" :key="item.id">
                            · {{ item.stem }}
                        </div>
                    </div>
                </div>

                <div v-else class="dialog-body">
                    <div class="form-row">
                        <textarea v-model="manualForm.stem" class="form-textarea"
                                  placeholder="题干（8~500 字，独立成立，不要出现“本文中”这类指代）"></textarea>
                    </div>
                    <div class="form-row inline-row">
                        <span class="form-label">题型</span>
                        <span class="switch-item" :class="{ active: manualForm.questionType === 1 }"
                              @click="manualForm.questionType = 1">单选</span>
                        <span class="switch-item" :class="{ active: manualForm.questionType === 2 }"
                              @click="manualForm.questionType = 2">多选</span>
                        <span class="form-label">难度</span>
                        <span v-for="opt in difficultyOptions" :key="opt.value" class="switch-item"
                              :class="{ active: manualForm.difficulty === opt.value }"
                              @click="manualForm.difficulty = opt.value">{{ opt.label }}</span>
                    </div>
                    <div class="form-row">
                        <span class="form-label">选项（点击左侧勾选正确项）</span>
                        <div v-for="(opt, idx) in manualForm.options" :key="idx" class="option-edit-row">
                            <span class="opt-check" :class="{ checked: manualForm.answer.indexOf(idx) !== -1 }"
                                  @click="toggleManualAnswer(idx)">{{ letter(idx) }}</span>
                            <input v-model="manualForm.options[idx]" class="form-input" :placeholder="'选项 ' + letter(idx)" />
                            <span class="opt-remove" v-if="manualForm.options.length > 2"
                                  @click="removeManualOption(idx)">&#10005;</span>
                        </div>
                        <span class="add-option" v-if="manualForm.options.length < 6" @click="addManualOption">
                            + 添加选项
                        </span>
                    </div>
                    <div class="form-row">
                        <input v-model="manualForm.tags" class="form-input" placeholder="知识点标签（逗号分隔，最多 3 个，可选）" />
                    </div>
                    <div class="form-row">
                        <input v-model="manualForm.articleId" class="form-input" placeholder="来源文章 ID（可选，须为自己的已发布文章）" />
                    </div>
                    <div class="form-row">
                        <textarea v-model="manualForm.explanation" class="form-textarea small"
                                  placeholder="答案解析（可选，1000 字内）"></textarea>
                    </div>
                    <div class="dialog-actions">
                        <button class="primary-btn small" :disabled="submittingQuestion" @click="handleManualSubmit">
                            {{ submittingQuestion ? '提交中...' : '提交投稿' }}
                        </button>
                        <span class="dialog-tip">提交后经一次 AI 质检，通过即上架</span>
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
        getCodingRanking,
        getCodingQuestions,
        getCodingStat,
        generateQuestionsFromArticle,
        submitCodingQuestion
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
                rankingTabs: [
                    { value: 'day', label: '当日' },
                    { value: 'week', label: '本周' },
                    { value: 'month', label: '本月' }
                ],
                difficulty: null,
                todayLoading: false,
                todayQuestion: null,
                todayAnswered: false,
                todayResult: null,
                selected: [],
                submitting: false,
                dailyStartAt: null,

                practiceDifficulty: null,
                questionList: [],
                page: 1,
                pageSize: 10,
                totalQuestions: 0,
                practiceActive: null,
                practiceSelected: [],
                practiceResult: null,
                practiceStartAt: null,
                submittingPractice: false,
                // 来源文章过滤（文章详情页 -> /coding?articleId=xx&questionId=yy 反向入口）
                articleFilter: null,
                pendingQuestionId: null,

                rankingPeriod: 'day',
                ranking: [],

                stat: {
                    continuousDays: 0,
                    todayAnswered: false,
                    todayCorrect: null,
                    totalCount: 0,
                    correctCount: 0,
                    accuracy: 0,
                    practiceCount: 0,
                    practiceCorrectCount: 0,
                    practiceAccuracy: 0,
                    tagStats: {}
                },

                supplyVisible: false,
                supplyTab: 'ai',
                genArticleId: '',
                generating: false,
                genResult: null,
                manualForm: this.buildEmptyManualForm(),
                submittingQuestion: false
            }
        },
        computed: {
            isLoggedIn() {
                return this.$store.getters.isLoggedIn
            },
            totalPages() {
                return Math.max(1, Math.ceil(this.totalQuestions / this.pageSize))
            },
            tagStatsList() {
                const stats = this.stat.tagStats || {}
                const list = []
                Object.keys(stats).forEach((name) => {
                    const item = stats[name] || {}
                    const total = item.total || 0
                    const correct = item.correct || 0
                    if (total > 0) {
                        list.push({ name, rate: Math.round((correct * 100) / total) })
                    }
                })
                return list.sort((a, b) => b.rate - a.rate).slice(0, 6)
            }
        },
        mounted() {
            // 文章详情页反向入口：/coding?articleId=xx&questionId=yy（ID 均为字符串，避免雪花ID精度丢失）
            const query = this.$route.query || {}
            this.articleFilter = query.articleId ? String(query.articleId) : null
            this.pendingQuestionId = query.questionId ? String(query.questionId) : null
            this.loadToday()
            this.loadRanking()
            this.loadQuestions()
            if (this.isLoggedIn) {
                this.loadStat()
            }
        },
        methods: {
            showLogin() {
                this.$store.dispatch('showLogin')
            },
            // 能力档案入口（第二层）：本人档案 + 隐私设置 + 分享
            goAbilityProfile() {
                this.$router.push('/coding/ability').catch(() => {})
            },
            difficultyLabel(value) {
                const found = this.difficultyOptions.find((item) => item.value === value)
                return found ? found.label : '入门'
            },
            letter(index) {
                return String.fromCharCode(65 + index)
            },
            buildEmptyManualForm() {
                return {
                    stem: '',
                    questionType: 1,
                    options: ['', '', '', ''],
                    answer: [],
                    explanation: '',
                    difficulty: 1,
                    tags: '',
                    articleId: ''
                }
            },

            // ============ 今日题 ============
            async loadToday() {
                if (!this.isLoggedIn) {
                    this.todayQuestion = null
                    return
                }
                this.todayLoading = true
                try {
                    const res = await getTodayQuestion(this.difficulty || undefined)
                    if (res && res.code === 200 && res.data) {
                        const question = res.data
                        this.todayQuestion = question
                        this.difficulty = question.difficulty || this.difficulty
                        if (question.answered) {
                            this.todayAnswered = true
                            this.todayResult = {
                                isCorrect: question.isCorrect === true,
                                userAnswer: question.userAnswer || [],
                                correctAnswer: question.correctAnswer || [],
                                explanation: question.explanation || '',
                                scoreAwarded: question.scoreAwarded || 0,
                                elapsedSeconds: question.elapsedSeconds,
                                sourceArticleId: question.sourceArticleId,
                                sourceArticleTitle: question.sourceArticleTitle
                            }
                        } else {
                            this.todayAnswered = false
                            this.todayResult = null
                            this.selected = []
                            this.dailyStartAt = Date.now()
                        }
                    } else {
                        this.todayQuestion = null
                        if (res && res.code !== 200 && res.message) {
                            toast(res.message, 2)
                        }
                    }
                } catch (e) {
                    this.todayQuestion = null
                } finally {
                    this.todayLoading = false
                }
            },
            switchDifficulty(value) {
                if (this.todayAnswered) {
                    toast('今日已作答，明天再来挑战新难度', 2)
                    return
                }
                if (this.difficulty === value) {
                    return
                }
                this.difficulty = value
                this.loadToday()
            },
            toggleSelect(index, isDaily) {
                const list = isDaily ? this.selected : this.practiceSelected
                const at = list.indexOf(index)
                if (at === -1) {
                    // 单选表现：点新选项替换旧选择；多选题可叠加
                    const single = isDaily
                        ? (this.todayQuestion && this.todayQuestion.questionType !== 2)
                        : (this.practiceActive && this.practiceActive.questionType !== 2)
                    if (single) {
                        list.splice(0, list.length, index)
                    } else {
                        list.push(index)
                    }
                } else {
                    list.splice(at, 1)
                }
            },
            todayOptionClass(index) {
                const classes = {}
                const chosen = this.todayAnswered && this.todayResult
                    ? (this.todayResult.userAnswer || []) : this.selected
                if (chosen.indexOf(index) !== -1) {
                    classes.selected = true
                }
                if (this.todayAnswered && this.todayResult) {
                    const correct = this.todayResult.correctAnswer || []
                    if (correct.indexOf(index) !== -1) {
                        classes['correct-flag'] = true
                    } else if (chosen.indexOf(index) !== -1) {
                        classes['wrong-flag'] = true
                    }
                }
                return classes
            },

            // ============ 题库练习 ============
            async loadQuestions() {
                try {
                    const params = { page: this.page, size: this.pageSize }
                    if (this.practiceDifficulty) {
                        params.difficulty = this.practiceDifficulty
                    }
                    if (this.articleFilter) {
                        params.articleId = this.articleFilter
                    }
                    const res = await getCodingQuestions(params)
                    if (res && res.code === 200 && res.data) {
                        this.questionList = res.data.list || []
                        this.totalQuestions = res.data.total || 0
                        this.autoOpenPendingQuestion()
                    } else {
                        this.questionList = []
                        this.totalQuestions = 0
                    }
                } catch (e) {
                    this.questionList = []
                    this.totalQuestions = 0
                }
            },
            /** 从文章页带 questionId 进入时，自动展开该题并滚动到练习区 */
            autoOpenPendingQuestion() {
                if (!this.pendingQuestionId) {
                    return
                }
                const questionId = this.pendingQuestionId
                this.pendingQuestionId = null
                const target = this.questionList.find((item) => String(item.id) === questionId)
                if (!target) {
                    return
                }
                if (this.practiceActive && this.practiceActive.id === target.id) {
                    return
                }
                this.togglePractice(target)
                this.$nextTick(() => {
                    const el = document.querySelector('.practice-card')
                    if (el && el.scrollIntoView) {
                        el.scrollIntoView({ behavior: 'smooth', block: 'start' })
                    }
                })
            },
            /** 清除来源文章过滤，回到完整题库 */
            clearArticleFilter() {
                this.articleFilter = null
                this.page = 1
                const query = this.$route.query || {}
                if (query.articleId || query.questionId) {
                    this.$router.replace({ path: '/coding' }).catch(() => {})
                }
                this.loadQuestions()
            },
            switchPracticeDifficulty(value) {
                if (this.practiceDifficulty === value) {
                    return
                }
                this.practiceDifficulty = value
                this.page = 1
                this.loadQuestions()
            },
            changePage(step) {
                const next = this.page + step
                if (next < 1 || next > this.totalPages) {
                    return
                }
                this.page = next
                this.loadQuestions()
            },
            togglePractice(question) {
                if (this.practiceActive && this.practiceActive.id === question.id) {
                    this.practiceActive = null
                    this.practiceResult = null
                    this.practiceSelected = []
                    return
                }
                this.practiceActive = question
                this.practiceSelected = []
                this.practiceResult = null
                this.practiceStartAt = Date.now()
            },
            practiceOptionClass(index) {
                const classes = {}
                const chosen = this.practiceResult
                    ? (this.practiceResult.userAnswer || this.practiceSelected) : this.practiceSelected
                if (chosen.indexOf(index) !== -1) {
                    classes.selected = true
                }
                if (this.practiceResult) {
                    const correct = this.practiceResult.correctAnswer || []
                    if (correct.indexOf(index) !== -1) {
                        classes['correct-flag'] = true
                    } else if (chosen.indexOf(index) !== -1) {
                        classes['wrong-flag'] = true
                    }
                }
                return classes
            },

            // ============ 提交作答（当日题 / 练习共用） ============
            async submitAnswer(isDaily) {
                if (!this.isLoggedIn) {
                    toast('登录后才能提交答案', 2)
                    this.showLogin()
                    return
                }
                const question = isDaily ? this.todayQuestion : this.practiceActive
                const answers = isDaily ? this.selected.slice() : this.practiceSelected.slice()
                if (!question || !question.id || answers.length === 0) {
                    return
                }
                const startAt = isDaily ? this.dailyStartAt : this.practiceStartAt
                const elapsedSeconds = startAt
                    ? Math.max(1, Math.round((Date.now() - startAt) / 1000)) : null
                if (isDaily) {
                    this.submitting = true
                } else {
                    this.submittingPractice = true
                }
                try {
                    const res = await submitCodingAnswer({
                        questionId: question.id,
                        answers,
                        elapsedSeconds,
                        isDaily
                    })
                    if (!res || res.code !== 200 || !res.data) {
                        toast((res && res.message) || '提交失败，请稍后重试', 2)
                        // 当日题已被锁定（如另一标签页已作答）：刷新为回放态
                        if (isDaily && res && (res.code === 400)) {
                            this.loadToday()
                        }
                        return
                    }
                    const data = res.data
                    if (isDaily) {
                        this.todayAnswered = true
                        this.todayResult = {
                            isCorrect: data.isCorrect === true,
                            userAnswer: answers,
                            correctAnswer: data.correctAnswer || [],
                            explanation: data.explanation || '',
                            scoreAwarded: data.scoreAwarded || 0,
                            continuousDays: data.continuousDays,
                            elapsedSeconds,
                            sourceArticleId: data.sourceArticleId,
                            sourceArticleTitle: data.sourceArticleTitle
                        }
                        if (data.isCorrect) {
                            toast('回答正确，连续天数 +1', 2)
                        } else {
                            toast('回答错误，看看解析再战', 2)
                        }
                        this.loadStat()
                        this.loadRanking()
                    } else {
                        data.userAnswer = answers
                        this.practiceResult = data
                        const target = this.questionList.find((item) => item.id === question.id)
                        if (target) {
                            target.answered = true
                        }
                        this.loadStat()
                    }
                } catch (e) {
                    toast('网络异常，请稍后重试', 2)
                } finally {
                    this.submitting = false
                    this.submittingPractice = false
                }
            },

            // ============ 榜单与统计 ============
            async loadRanking() {
                try {
                    const res = await getCodingRanking(this.rankingPeriod)
                    if (res && res.code === 200 && res.data) {
                        this.ranking = res.data.list || []
                    } else {
                        this.ranking = []
                    }
                } catch (e) {
                    this.ranking = []
                }
            },
            switchRanking(period) {
                if (this.rankingPeriod === period) {
                    return
                }
                this.rankingPeriod = period
                this.loadRanking()
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
            },
            openArticle(articleId) {
                if (articleId) {
                    window.open('/content/article/' + articleId, '_blank')
                }
            },

            // ============ 出题投稿 ============
            openSupplyDialog() {
                if (!this.isLoggedIn) {
                    toast('登录后才能出题投稿', 2)
                    this.showLogin()
                    return
                }
                this.supplyVisible = true
            },
            async handleGenerate() {
                // 文章ID 为雪花长整型：按字符串传递，避免 Number 转换丢精度
                const articleId = this.genArticleId ? String(this.genArticleId).trim() : ''
                if (!articleId || !/^\d+$/.test(articleId)) {
                    toast('请输入正确的文章 ID', 2)
                    return
                }
                this.generating = true
                try {
                    const res = await generateQuestionsFromArticle(articleId)
                    if (res && res.code === 200 && res.data) {
                        this.genResult = res.data
                        toast('生成完成，入池 ' + (res.data.inserted || 0) + ' 道题', 2)
                        this.page = 1
                        this.loadQuestions()
                    } else {
                        toast((res && res.message) || '生成失败，请稍后重试', 2)
                    }
                } catch (e) {
                    toast('网络异常，请稍后重试', 2)
                } finally {
                    this.generating = false
                }
            },
            addManualOption() {
                if (this.manualForm.options.length < 6) {
                    this.manualForm.options.push('')
                }
            },
            removeManualOption(index) {
                if (this.manualForm.options.length <= 2) {
                    return
                }
                this.manualForm.options.splice(index, 1)
                this.manualForm.answer = this.manualForm.answer
                    .filter((item) => item !== index)
                    .map((item) => (item > index ? item - 1 : item))
            },
            toggleManualAnswer(index) {
                const at = this.manualForm.answer.indexOf(index)
                if (at === -1) {
                    this.manualForm.answer.push(index)
                } else {
                    this.manualForm.answer.splice(at, 1)
                }
            },
            async handleManualSubmit() {
                const form = this.manualForm
                if (!form.stem || form.stem.trim().length < 8) {
                    toast('题干至少 8 个字', 2)
                    return
                }
                const options = form.options.map((item) => (item || '').trim())
                if (options.some((item) => !item)) {
                    toast('选项不能为空', 2)
                    return
                }
                if (form.answer.length === 0) {
                    toast('请勾选正确答案', 2)
                    return
                }
                if (form.questionType === 1 && form.answer.length !== 1) {
                    toast('单选题只能有 1 个正确答案', 2)
                    return
                }
                this.submittingQuestion = true
                try {
                    const payload = {
                        stem: form.stem.trim(),
                        questionType: form.questionType,
                        options,
                        answer: form.answer.slice().sort((a, b) => a - b),
                        explanation: form.explanation ? form.explanation.trim() : null,
                        difficulty: form.difficulty,
                        tags: form.tags ? form.tags.trim() : null,
                        // 文章ID 为雪花长整型：按字符串传递，避免 Number 转换丢精度
                        articleId: form.articleId ? String(form.articleId).trim() : null
                    }
                    const res = await submitCodingQuestion(payload)
                    if (res && res.code === 200) {
                        toast('投稿成功，题目已上架', 2)
                        this.manualForm = this.buildEmptyManualForm()
                        this.page = 1
                        this.loadQuestions()
                    } else {
                        toast((res && res.message) || '投稿失败，请检查后重试', 2)
                    }
                } catch (e) {
                    toast('网络异常，请稍后重试', 2)
                } finally {
                    this.submittingQuestion = false
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