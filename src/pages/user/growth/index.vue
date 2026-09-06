<template>
    <div class="growth-page">
        <div class="art-top" v-if="!isDesktop"><HomeBar/></div>
        <div class="growth-content">
            <!-- 左侧侧边栏 -->
            <UserCenterSidebar active-menu="growth" @menu-click="handleSidebarMenuClick" />
            
            <!-- 右侧主内容区 -->
            <div class="main-area">
                <!-- 页面标题栏 -->
                <div class="page-header">
                    <div class="page-title">成长等级</div>
                    <div class="page-links">
                        <span class="link-item" @click="goToJScore">
                            <span class="link-icon">📊</span> 掘友分明细
                        </span>
                        <span class="link-item" @click="showRulesModal = true">
                            <span class="link-icon">📋</span> 等级规则
                        </span>
                    </div>
                </div>

                <!-- 等级信息 + 分值柱状图 -->
                <div class="level-overview">
                    <!-- 左侧：紫色渐变等级卡片 -->
                    <div class="level-card-purple">
                        <div class="level-card-content">
                            <div class="level-info">
                                <div class="level-badge">{{ levelInfo.dailyScore > 0 ? '当前等级' : '暂未获得' }}</div>
                                <div class="level-name">{{ currentLevelConfig.title || '新星掘友' }}</div>
                                <div class="level-score-section" v-if="levelInfo.dailyScore > 0">
                                    <div class="score-label">掘友分</div>
                                    <div class="score-value">{{ levelInfo.dailyScore || 0 }}</div>
                                </div>
                                <div class="level-score-section" v-else>
                                    <div class="score-label">掘友分需达到</div>
                                    <div class="score-value">{{ currentLevelConfig.minScore }}</div>
                                </div>
                            </div>
                            <div class="level-crystal">
                                <div class="crystal-shape"></div>
                            </div>
                        </div>
                        <div class="level-progress" v-if="levelInfo.dailyScore > 0">
                            <div class="level-progress-bar">
                                <div class="progress-fill" :style="{ width: dailyProgressPercent + '%' }"></div>
                            </div>
                            <div class="level-progress-text">{{ dailyProgressText }}</div>
                        </div>
                        <div class="level-progress" v-else>
                            <div class="level-progress-text">还需{{ currentLevelConfig.minScore }}分可升至{{ currentLevelConfig.title }}</div>
                        </div>
                    </div>

                    <!-- 右侧：深色等级分值阶梯图 -->
                    <div class="level-chart-dark">
                        <div class="chart-title">等级分值</div>
                        <!-- 阶梯行 -->
                        <div class="chart-stairs-row">
                            <div v-for="(config, index) in levelChartConfigs" :key="'cell-'+config.level" 
                                 class="chart-stair-cell"
                                 :class="{ 
                                    locked: config.level > levelInfo.dailyLevel,
                                    unlocked: config.level <= levelInfo.dailyLevel,
                                    active: config.level === levelInfo.dailyLevel,
                                    selected: config.level === selectedLevel && config.level !== levelInfo.dailyLevel,
                                    'has-indicator': config.level === levelInfo.dailyLevel
                                 }"
                                 @click="selectLevel(config.level)">
                                <!-- ▼ 指示器（背景外顶部） -->
                                <div class="stair-indicator" v-if="config.level === levelInfo.dailyLevel">
                                    <span class="indicator-icon">▼</span>
                                </div>
                                <!-- 圆角矩形背景：包裹数值+柱体+标签 -->
                                <div class="stair-wrapper"
                                     :class="{ 
                                        active: config.level === levelInfo.dailyLevel,
                                        selected: config.level === selectedLevel && config.level !== levelInfo.dailyLevel
                                     }">
                                    <!-- 经验值（在柱体正上方） -->
                                    <div class="stair-value" 
                                         :class="{ active: config.level === levelInfo.dailyLevel }">
                                        <span class="value-num">{{ config.minScore }}</span>
                                    </div>
                                    <!-- 柱体 -->
                                    <div class="bar-stick" 
                                         :class="{ 
                                            active: config.level === levelInfo.dailyLevel,
                                            unlocked: config.level <= levelInfo.dailyLevel
                                         }"
                                         :style="{ height: getBarHeight(index) + 'px' }">
                                    </div>
                                    <!-- 等级标签 -->
                                    <div class="stair-label" :class="{ active: config.level === levelInfo.dailyLevel }">
                                        JY{{ config.level }}
                                    </div>
                                </div>
                            </div>
                        </div>
                    </div>
                </div>

                <!-- 等级权益区 -->
                <div class="benefits-section">
                    <div class="benefits-header">
                        <div class="benefits-title">
                            等级权益
                            <span class="benefits-level-tag">查看 JY{{ selectedLevel }} 权益</span>
                        </div>
                        <div class="benefits-nav" v-if="benefitsTotalPages > 1">
                            <button class="nav-btn" @click="prevBenefitsPage">◀</button>
                            <span class="page-indicator">{{ benefitsPage }} / {{ benefitsTotalPages }}</span>
                            <button class="nav-btn" @click="nextBenefitsPage">▶</button>
                        </div>
                    </div>
                    <div class="benefits-list">
                        <div v-for="perm in currentBenefitsPage" :key="perm.code" class="benefit-item" :class="{ locked: perm.locked }" @click="openBenefitModal(perm)">
                            <div class="benefit-icon-wrap">
                                <img v-if="perm.icon" :src="perm.icon" class="benefit-img" />
                                <span v-else class="benefit-icon">{{ perm.icon }}</span>
                                <span class="benefit-lock" v-if="perm.locked">🔒</span>
                            </div>
                            <div class="benefit-name">{{ perm.name }}</div>
                        </div>
                    </div>
                </div>

                <!-- 升级行为任务列表 -->
                <div class="tasks-section">
                    <div class="tasks-header">
                        <div class="tasks-title">升级行为</div>
                        <div class="tasks-today-score">今日掘友分 +{{ todayJscore || levelInfo.dailyScoreToday || 0 }}</div>
                    </div>
                    <div v-for="group in taskGroups" :key="group.name" class="task-group">
                        <div class="group-title">{{ group.icon }} {{ group.name }}</div>
                        <div class="group-tasks">
                            <div v-for="task in group.tasks" :key="task.task_id || task.actionType" class="task-item">
                                <div class="task-icon-wrap">
                                    <img v-if="task.icon && (task.icon.startsWith('http') || task.icon.startsWith('/static/'))" :src="task.icon" class="task-img" />
                                    <span v-else class="task-icon">{{ task.icon }}</span>
                                </div>
                                <div class="task-info">
                                    <div class="task-name">{{ task.name }}</div>
                                    <div class="task-progress-text">
                                        掘友分 +{{ task.score }}
                                        <template v-if="task.limit > 0">，已完成 {{ task.done || 0 }}/{{ task.limit }}</template>
                                        <!-- limit === -1：无限制任务（社区影响力等）不展示已完成次数 -->
                                    </div>
                                </div>
                                <button class="task-btn" 
                                        :class="{ completed: task.completed }"
                                        :disabled="task.completed"
                                        @click="handleTaskAction(task)">
                                    {{ task.completed ? '已完成' : task.buttonText }}
                                </button>
                            </div>
                        </div>
                    </div>
                </div>

                <!-- 底部信息 -->
                <div class="growth-footer">
                    <span>用户协议</span> · <span>法律声明</span> · <span>&copy;2026 稀土掘金</span>
                </div>
            </div>
        </div>

        <!-- 等级规则 Modal -->
        <div class="modal-overlay" v-if="showRulesModal" @click.self="showRulesModal = false">
            <div class="modal-content">
                <div class="modal-header">
                    <div class="modal-title">等级规则</div>
                    <div class="modal-close" @click="showRulesModal = false">&times;</div>
                </div>
                <div class="modal-body">
                    <div class="rules-section">
                        <div class="rules-title">📈 等级与权益</div>
                        <div class="rules-desc">掘友分到达指定区间，即可升级至对应掘友等级，自动解锁对应权益。</div>
                    </div>
                    <div class="rules-section">
                        <div class="rules-title">☀️ 社区活跃（每日有上限）</div>
                        <ul class="rules-list">
                            <li>发布文章 +8分</li>
                            <li>发布沸点 +2分</li>
                            <li>评论文章 +2分</li>
                            <li>评论沸点 +2分</li>
                            <li>点赞文章 +1分</li>
                            <li>点赞沸点 +1分</li>
                            <li>收藏文章 +1分</li>
                            <li>关注掘友 +4分</li>
                        </ul>
                    </div>
                    <div class="rules-section">
                        <div class="rules-title">📚 社区学习（每日有上限）</div>
                        <ul class="rules-list">
                            <li>浏览文章/课程 +0.5分</li>
                        </ul>
                    </div>
                    <div class="rules-section">
                        <div class="rules-title">⭐ 社区影响力（无上限）</div>
                        <ul class="rules-list">
                            <li>沸点被点赞 +0.1分</li>
                            <li>文章被点赞 +0.1分</li>
                            <li>被掘友关注 +0.1分</li>
                        </ul>
                    </div>
                    <div class="rules-section">
                        <div class="rules-title">🏠 社区基础（一次性）</div>
                        <ul class="rules-list">
                            <li>上传头像 +1分</li>
                        </ul>
                    </div>
                    <div class="rules-section">
                        <div class="rules-title">⚠️ 减分规则</div>
                        <ul class="rules-list">
                            <li>发布违规内容 -10分</li>
                            <li>评论违规 -5分</li>
                            <li>被举报核实 -20分</li>
                        </ul>
                    </div>
                </div>
            </div>
        </div>

        <!-- 权益说明 Modal -->
        <div class="modal-overlay" v-if="showBenefitModal" @click.self="closeBenefitModal">
            <div class="benefit-modal">
                <div class="benefit-modal-header">
                    <span class="benefit-modal-title">权益说明</span>
                    <span class="modal-close" @click="closeBenefitModal">&times;</span>
                </div>
                <div class="benefit-modal-body">
                    <div class="benefit-sidebar">
                        <div v-for="group in privilegeGroups" :key="group.name" class="benefit-group">
                            <div class="benefit-group-title" @click="toggleGroup(group.name)">
                                <span class="group-arrow" :class="{expanded: group.expanded}">▶</span>
                                {{ group.name }}
                            </div>
                            <div class="benefit-group-list" v-show="group.expanded">
                                <div v-for="priv in group.items" :key="priv.priv_id"
                                     class="benefit-nav-item"
                                     :class="{active: priv.priv_id === selectedBenefitId, locked: priv.priv_status !== 1}"
                                     @click="selectBenefit(priv)">
                                    {{ priv.title }}
                                    <span v-if="priv.priv_status !== 1" class="lock-tag">暂未解锁</span>
                                </div>
                            </div>
                        </div>
                    </div>
                    <div class="benefit-detail" v-if="currentBenefit">
                        <div class="benefit-detail-icon">
                            <img :src="currentBenefit.icon" :alt="currentBenefit.title" v-if="currentBenefit.icon" />
                            <span v-else class="icon-fallback">✨</span>
                        </div>
                        <div class="benefit-detail-title">{{ currentBenefit.title }}</div>
                        <div class="benefit-detail-desc" v-for="(d, i) in currentBenefit.desc" :key="i">
                            <div class="desc-title">{{ d.desc_title }}</div>
                            <div class="desc-content" :class="{unlocked: d.desc_title === '解锁等级'}">{{ d.desc_content }}</div>
                        </div>
                        <div class="benefit-detail-status" v-if="currentBenefit.priv_status !== 1">暂未解锁</div>
                        <a class="benefit-jump" v-if="currentBenefit.web_jump_url" :href="currentBenefit.web_jump_url">立即前往 →</a>
                    </div>
                </div>
            </div>
        </div>
    </div>
</template>

<script>
import HomeBar from '@/components/bars/home_bar'
import UserCenterSidebar from '@/components/user/UserCenterSidebar'
import Utils from '@/utils/env'
import { toast } from '@/utils/toast'
import request from '@/common/article_request'

// 等级完整配置（与掘友等级参考文档一致）
const LEVEL_FULL_CONFIG = [
    { level: 1, minScore: 0, title: '新星掘友', permissions: ['can_send_private_message'] },
    { level: 2, minScore: 15, title: '进阶掘友', permissions: ['can_create_poll'] },
    { level: 3, minScore: 30, title: '先锋掘友', permissions: ['can_set_comment_permission'] },
    { level: 4, minScore: 150, title: '杰出掘友', permissions: ['can_mark'] },
    { level: 5, minScore: 500, title: '资深掘友', permissions: ['can_use_ore'] },
    { level: 6, minScore: 2000, title: '专家掘友', permissions: ['can_customize'] },
    { level: 7, minScore: 7000, title: '大师掘友', permissions: ['can_upgrade_ore'] },
    { level: 8, minScore: 25000, title: '传奇掘友', permissions: ['can_judge', 'can_create_course'] }
]

// 权益图标映射
const PERMISSION_ICON_MAP = {
    'can_send_private_message': { name: '主动发起私信', icon: '💬' },
    'can_create_poll': { name: '发起投票', icon: '📊' },
    'can_set_comment_permission': { name: '评论区权限设置', icon: '🔒' },
    'can_mark': { name: '评论区Mark设置', icon: '✏️' },
    'can_use_ore': { name: '使用掘金特色表情', icon: '😊' },
    'can_customize': { name: '个性装扮', icon: '🎨' },
    'can_upgrade_ore': { name: '升级矿石奖励', icon: '💎' },
    'can_judge': { name: '参选掘金神评审', icon: '🏆' },
    'can_create_course': { name: '参选小册评审团', icon: '📚' }
}

// 任务分组配置（与掘友等级参考文档一致）
const TASK_GROUP_CONFIG = {
    '社区基础': {
        icon: '🏠',
        actionTypes: ['upload_avatar']
    },
    '社区活跃': {
        icon: '🔥',
        actionTypes: [
            'publish_article', 'publish_pin',
            'comment_article', 'comment_pin',
            'like_article', 'like_pin',
            'collect_article', 'follow_user'
        ]
    },
    '社区学习': {
        icon: '📚',
        actionTypes: ['browse_article']
    },
    '社区影响力': {
        icon: '⭐',
        actionTypes: ['be_followed', 'pin_liked', 'article_liked']
    }
}

// 任务图标映射
const TASK_ICON_MAP = {
    'upload_avatar': '📷',
    'publish_article': '✍️',
    'publish_pin': '💬',
    'comment_article': '💬',
    'comment_pin': '💬',
    'like_article': '👍',
    'like_pin': '👍',
    'collect_article': '⭐',
    'follow_user': '👤',
    'browse_article': '📖',
    'be_followed': '👥',
    'pin_liked': '❤️',
    'article_liked': '❤️'
}

// 任务按钮文案映射
const TASK_BUTTON_MAP = {
    'upload_avatar': '去上传',
    'publish_article': '去发布',
    'publish_pin': '去发布',
    'comment_article': '去评论',
    'comment_pin': '去评论',
    'like_article': '去点赞',
    'like_pin': '去点赞',
    'collect_article': '去收藏',
    'follow_user': '去关注',
    'browse_article': '去学习',
    'be_followed': '去分享',
    'pin_liked': '去发布沸点',
    'article_liked': '去创作'
}

// 默认任务数据（与掘友等级参考文档分值一致）
const DEFAULT_TASKS = [
    // 社区基础
    { actionType: 'upload_avatar', task_type: '社区基础', name: '上传头像', score: 1, current: 0, max: 1 },
    // 社区活跃
    { actionType: 'publish_article', task_type: '社区活跃', name: '发布一篇文章', score: 8, current: 0, max: 2 },
    { actionType: 'publish_pin', task_type: '社区活跃', name: '发布一条沸点', score: 2, current: 0, max: 2 },
    { actionType: 'comment_article', task_type: '社区活跃', name: '评论一篇文章', score: 2, current: 0, max: 5 },
    { actionType: 'comment_pin', task_type: '社区活跃', name: '评论一条沸点', score: 2, current: 0, max: 5 },
    { actionType: 'like_article', task_type: '社区活跃', name: '点赞一篇文章', score: 1, current: 0, max: 5 },
    { actionType: 'like_pin', task_type: '社区活跃', name: '点赞一条沸点', score: 1, current: 0, max: 5 },
    { actionType: 'collect_article', task_type: '社区活跃', name: '收藏一篇文章', score: 1, current: 0, max: 3 },
    { actionType: 'follow_user', task_type: '社区活跃', name: '关注一位掘友', score: 4, current: 0, max: 5 },
    // 社区学习
    { actionType: 'browse_article', task_type: '社区学习', name: '浏览1篇文章/课程', score: 0.5, current: 0, max: 10 },
    // 社区影响力
    { actionType: 'be_followed', task_type: '社区影响力', name: '被一位掘友关注', score: 0.1, current: 0, max: 0 },
    { actionType: 'pin_liked', task_type: '社区影响力', name: '沸点获得一个点赞', score: 0.1, current: 0, max: 0 },
    { actionType: 'article_liked', task_type: '社区影响力', name: '文章获得一个点赞', score: 0.1, current: 0, max: 0 }
]

export default {
    name: 'GrowthLevel',
    components: { HomeBar, UserCenterSidebar },
    data() {
        return {
            userId: null,
            levelInfo: {
                dailyScore: 0,
                dailyLevel: 1,
                dailyTitle: '',
                dailyScoreToday: 0
            },
            dailyLevelConfigs: [],
            permissions: [],
            tasks: [],
            hasCheckedIn: false,
            dailyNextLevelScore: 0,
            showRulesModal: false,
            showBenefitModal: false,
            selectedBenefitId: null,
            privilegeGroups: [],
            privileges: [],
            todayJscore: 0,
            benefitsPage: 1,
            benefitsPageSize: 6,
            selectedLevel: 1
        }
    },
    computed: {
        isDesktop() {
            return Utils.isDesktop()
        },
        userName() {
            const userInfo = this.$store.state.userInfo
            return userInfo ? (userInfo.nickName || '用户') : '用户'
        },
        // 当前等级配置
        currentLevelConfig() {
            const level = this.levelInfo.dailyLevel || 1
            return LEVEL_FULL_CONFIG.find(c => c.level === level) || LEVEL_FULL_CONFIG[0]
        },
        // 选中等级的配置（用于权益预览）
        selectedLevelConfig() {
            return LEVEL_FULL_CONFIG.find(c => c.level === this.selectedLevel) || LEVEL_FULL_CONFIG[0]
        },
        dailyProgressPercent() {
            const configs = this.levelChartConfigs
            if (!configs.length) return 0
            const currentLevel = this.levelInfo.dailyLevel || 1
            const currentScore = this.levelInfo.dailyScore || 0
            const currentConfig = configs.find(c => c.level === currentLevel)
            const nextConfig = configs.find(c => c.level === currentLevel + 1)
            if (!currentConfig) return 0
            if (!nextConfig) {
                this.dailyNextLevelScore = 0
                return 100
            }
            const min = currentConfig.minScore
            const max = nextConfig.minScore
            this.dailyNextLevelScore = Math.max(Math.ceil(max - currentScore), 0)
            return Math.min(Math.max(((currentScore - min) / (max - min)) * 100, 0), 100)
        },
        dailyProgressText() {
            if (this.dailyNextLevelScore <= 0) return '已达最高等级'
            const nextConfig = LEVEL_FULL_CONFIG.find(c => c.level === (this.levelInfo.dailyLevel || 1) + 1)
            const nextTitle = nextConfig ? nextConfig.title : ''
            return '还需 ' + this.dailyNextLevelScore + ' 分可升至 ' + (nextTitle || ('JY' + ((this.levelInfo.dailyLevel || 1) + 1)))
        },
        taskGroups() {
            // 分组展示顺序与掘金一致：社区基础 → 社区活跃 → 社区学习 → 社区影响力（影响力在最底）
            // 仅展示 TASK_GROUP_CONFIG 声明的分组；DB 已清理多余动作配置，此处兜底过滤未知分组
            const GROUP_ORDER = ['社区基础', '社区活跃', '社区学习', '社区影响力']
            const groups = {}
            const groupIconMap = {}
            for (const [groupName, groupConfig] of Object.entries(TASK_GROUP_CONFIG)) {
                groupIconMap[groupName] = groupConfig.icon
                groups[groupName] = { name: groupName, icon: groupConfig.icon, tasks: [] }
            }

            for (const task of this.tasks) {
                const type = task.task_type || '其他'
                const group = groups[type]
                if (!group) {
                    continue // 非掘金分组（如已清理的"内容创作"）不展示
                }
                const completed = task.limit > 0 && (task.done || 0) >= task.limit
                group.tasks.push({
                    ...task,
                    buttonText: task.btn_name || task.buttonText || '去完成',
                    completed: completed
                })
            }
            return GROUP_ORDER.filter(name => groups[name].tasks.length > 0).map(name => groups[name])
        },
        levelChartConfigs() {
            if (this.dailyLevelConfigs && this.dailyLevelConfigs.length > 0) {
                return this.dailyLevelConfigs.map(c => ({
                    level: c.levelValue || c.level,
                    minScore: c.minScore,
                    title: c.title || ''
                }))
            }
            return LEVEL_FULL_CONFIG.map(c => ({ level: c.level, minScore: c.minScore, title: c.title }))
        },
        // 根据选中等级计算权益（累积到选中等级）
        computedPermissions() {
            const selectedLevel = this.selectedLevel
            const currentLevel = this.levelInfo.dailyLevel || 1

            if (this.privileges && this.privileges.length > 0) {
                const result = []
                const seen = new Set()
                for (const priv of this.privileges) {
                    if (priv.need_jscore_level <= selectedLevel) {
                        if (!seen.has(priv.priv_id)) {
                            seen.add(priv.priv_id)
                            result.push({
                                code: priv.priv_id,
                                name: priv.title,
                                icon: priv.icon,
                                locked: priv.priv_status !== 1
                            })
                        }
                    }
                }
                return result
            }

            const result = []
            const seen = new Set()
            for (const config of LEVEL_FULL_CONFIG) {
                if (config.level <= selectedLevel) {
                    for (const permCode of config.permissions) {
                        if (!seen.has(permCode)) {
                            seen.add(permCode)
                            const info = PERMISSION_ICON_MAP[permCode] || { name: permCode, icon: '✨' }
                            result.push({
                                code: permCode,
                                name: info.name,
                                icon: info.icon,
                                locked: config.level > currentLevel
                            })
                        }
                    }
                }
            }
            return result
        },
        currentBenefit() {
            if (this.selectedBenefitId === null) return null
            for (const group of this.privilegeGroups) {
                for (const priv of group.items) {
                    if (priv.priv_id === this.selectedBenefitId) {
                        return priv
                    }
                }
            }
            return null
        },
        currentBenefitsPage() {
            const start = (this.benefitsPage - 1) * this.benefitsPageSize
            return this.computedPermissions.slice(start, start + this.benefitsPageSize)
        },
        benefitsTotalPages() {
            return Math.ceil(this.computedPermissions.length / this.benefitsPageSize) || 1
        }
    },
    watch: {
        levelInfo: {
            immediate: true,
            handler(val) {
                if (val && val.dailyLevel) {
                    this.selectedLevel = val.dailyLevel
                }
            }
        },
        // 用户行为（点赞/收藏/关注/评论/发布）成功后 store 自增 taskVersion，即时刷新等级与任务进度
        '$store.state.taskVersion': function () {
            this.loadData()
        }
    },
    created() {
        // 用户信息存于单 store 顶层 userInfo（Vuex 无 user 子模块）
        this.userId = this.$store.state.userInfo ? this.$store.state.userInfo.userId : null
    },
    mounted() {
        this.loadData()
    },
    methods: {
        selectLevel(level) {
            this.selectedLevel = level
            this.benefitsPage = 1
        },
        getBarHeight(index) {
            const heights = [14, 26, 40, 56, 72, 88, 102, 118]
            return heights[index] || 14
        },
        handleSidebarMenuClick(key) {
            const routeMap = {
                checkin: '/user/center/checkin',
                growth: '/user/center/growth',
                lottery: '/user/center/lottery',
                welfare: '/user/center/welfare',
                harvest: '/user/center/harvest'
            }
            const path = routeMap[key]
            if (path && this.$route.path !== path) {
                this.$router.push(path)
            }
        },
        goToJScore() {
            this.$router.push('/user/center/growth/jscore')
        },
        prevBenefitsPage() {
            if (this.benefitsPage > 1) {
                this.benefitsPage--
            }
        },
        nextBenefitsPage() {
            if (this.benefitsPage < this.benefitsTotalPages) {
                this.benefitsPage++
            }
        },
        async loadData() {
            await Promise.all([
                this.loadLevelInfo(),
                this.loadLevelConfigs(),
                this.loadTasks(),
                this.loadPrivileges()
            ])
        },
        async loadLevelInfo() {
            // 主数据源：info-pack（未登录也能返回基础信息）
            try {
                const response = await request.get('/api/v1/user/info-pack')
                const data = response.data
                if (data && typeof data === 'object') {
                    const growth = data.user_growth_info || {}
                    if (growth && typeof growth === 'object' && Object.keys(growth).length > 0) {
                        this.levelInfo = {
                            ...this.levelInfo,
                            dailyScore: growth.jscore !== undefined ? growth.jscore : this.levelInfo.dailyScore,
                            dailyLevel: growth.jscore_level !== undefined ? growth.jscore_level : this.levelInfo.dailyLevel,
                            dailyTitle: growth.jscore_title || this.levelInfo.dailyTitle
                        }
                        if (growth.jscore_next_level_score !== undefined) {
                            this.dailyNextLevelScore = growth.jscore_next_level_score
                        }
                        if (growth.jscore_level) {
                            this.selectedLevel = growth.jscore_level
                        }
                    }
                }
            } catch (error) {
                console.warn('loadLevelInfo(info-pack) API failed:', error)
            }
            // 兜底：旧接口 level/user/{userId}/info（仅登录用户）
            if (!this.userId) return
            try {
                const response = await request.get('/api/v1/level/user/' + this.userId + '/info')
                const data = response.data
                if (data && typeof data === 'object') {
                    this.levelInfo = {
                        ...this.levelInfo,
                        ...data
                    }
                    if (data.dailyLevel) {
                        this.selectedLevel = data.dailyLevel
                    }
                }
            } catch (error) {
                console.warn('loadLevelInfo fallback API failed:', error)
            }
        },
        async loadLevelConfigs() {
            // 等级阶梯主数据源为 privileges 接口的 level_spec（见 loadPrivileges）
            // 本方法仅作兜底：仅在 dailyLevelConfigs 仍为空时用旧配置接口填充
            try {
                const response = await request.get('/api/v1/level/configs?levelType=1')
                const data = response.data
                let list = null
                if (Array.isArray(data)) {
                    list = data
                } else if (data && Array.isArray(data.list)) {
                    list = data.list
                } else if (data && Array.isArray(data.records)) {
                    list = data.records
                } else if (data && Array.isArray(data.data)) {
                    list = data.data
                }
                if (list && list.length > 0 && (!this.dailyLevelConfigs || this.dailyLevelConfigs.length === 0)) {
                    this.dailyLevelConfigs = list
                }
            } catch (error) {
                console.warn('loadLevelConfigs API failed:', error)
            }
        },
        async loadTasks() {
            if (!this.userId) {
                this.tasks = this.getDefaultTasks()
                return
            }
            try {
                const response = await request.get('/api/v1/level/user/' + this.userId + '/tasks')
                const data = response.data
                let taskList = []

                if (data && data.growth_tasks && typeof data.growth_tasks === 'object') {
                    this.todayJscore = data.today_jscore || 0
                    const growthTasks = data.growth_tasks
                    for (const key of Object.keys(growthTasks)) {
                        const groupTasks = growthTasks[key]
                        if (Array.isArray(groupTasks)) {
                            taskList = taskList.concat(groupTasks.map(t => ({
                                task_id: t.task_id,
                                task_type: t.task_type,
                                icon: t.icon,
                                btn_name: t.btn_name,
                                name: t.title,
                                title: t.title,
                                score: t.score,
                                limit: t.limit,
                                done: t.done || 0,
                                web_jump_url: t.web_jump_url,
                                actionType: t.action_code || t.task_id
                            })))
                        }
                    }
                } else if (Array.isArray(data)) {
                    taskList = data
                } else if (data && Array.isArray(data.tasks)) {
                    taskList = data.tasks
                } else if (data && Array.isArray(data.list)) {
                    taskList = data.list
                } else if (data && Array.isArray(data.records)) {
                    taskList = data.records
                } else if (data && Array.isArray(data.data)) {
                    taskList = data.data
                }

                if (taskList.length > 0) {
                    // 统一归一化：actionType 优先 action_code；icon 名称转本地路径、空值回退 emoji
                    this.tasks = taskList.map(t => {
                        const actionType = t.actionType || t.action_code || t.task_id
                        return {
                            ...t,
                            actionType: actionType,
                            icon: this.resolveTaskIcon(t.icon, actionType)
                        }
                    })
                } else {
                    this.tasks = this.getDefaultTasks()
                }
            } catch (error) {
                console.warn('loadTasks API failed:', error)
                this.tasks = this.getDefaultTasks()
            }
        },
        getDefaultTasks() {
            return DEFAULT_TASKS.map(t => ({
                task_id: t.actionType,
                task_type: t.task_type || '其他',
                icon: TASK_ICON_MAP[t.actionType] || '✨',
                btn_name: TASK_BUTTON_MAP[t.actionType] || '去完成',
                name: t.name,
                title: t.name,
                score: t.score,
                limit: t.max || -1,
                done: 0,
                web_jump_url: '',
                actionType: t.actionType
            }))
        },
        // 任务图标：后端返回图标名称（如 upload_avatar）转本地图片路径；空值回退 emoji
        resolveTaskIcon(icon, actionType) {
            if (icon) {
                if (icon.indexOf('/') === 0 || icon.indexOf('http') === 0) {
                    return icon
                }
                return '/static/images/level/' + icon + '.png'
            }
            return TASK_ICON_MAP[actionType] || '✨'
        },
        // 权益图标：后端返回图标名称（如 priv_send_private_message）转本地图片路径
        resolvePrivIcon(icon) {
            if (!icon) return ''
            if (icon.indexOf('/') === 0 || icon.indexOf('http') === 0) {
                return icon
            }
            return '/static/images/level/' + icon + '.png'
        },
        handleTaskAction(task) {
            if (task.completed) {
                toast('该任务已完成', 2)
                return
            }
            // 优先按 action_code 路由
            let routed = true
            switch (task.actionType) {
                case 'publish_article':
                    this.$router.push('/creator/publish')
                    break
                case 'publish_pin':
                    this.$router.push('/pins')
                    break
                case 'browse_article':
                case 'comment_article':
                case 'comment_pin':
                case 'like_article':
                case 'like_pin':
                case 'follow_user':
                case 'collect_article':
                    this.$router.push('/')
                    break
                case 'upload_avatar':
                    this.$router.push('/user/settings')
                    break
                case 'be_followed':
                case 'pin_liked':
                case 'article_liked':
                    toast('快去创作优质内容吸引关注吧', 2)
                    break
                default:
                    routed = false
            }
            // switch 未命中时回退 web_jump_url（站外 http 链接新开/整页跳转，站内路径走路由）
            if (!routed && task.web_jump_url) {
                if (/^https?:\/\//.test(task.web_jump_url)) {
                    window.location.href = task.web_jump_url
                } else {
                    this.$router.push(task.web_jump_url)
                }
                return
            }
            if (!routed) {
                toast('任务功能开发中', 2)
            }
        },
        async loadPrivileges() {
            try {
                const response = await request.get('/api/v1/level/privileges')
                const data = response.data
                if (data && data.level_privilege && Array.isArray(data.level_privilege)) {
                    const allPrivileges = []
                    for (const levelArray of data.level_privilege) {
                        if (Array.isArray(levelArray)) {
                            for (const priv of levelArray) {
                                allPrivileges.push({
                                    ...priv,
                                    priv_id: priv.priv_id,
                                    title: priv.title,
                                    icon: priv.icon ? this.resolvePrivIcon(priv.icon) : '',
                                    priv_status: priv.priv_status,
                                    desc: priv.desc || [],
                                    need_jscore_level: priv.need_jscore_level,
                                    web_jump_url: priv.web_jump_url || ''
                                })
                            }
                        }
                    }
                    this.privileges = allPrivileges
                    this.buildPrivilegeGroups(allPrivileges)
                } else {
                    this.privileges = this.getDefaultPrivileges()
                    this.buildPrivilegeGroups(this.privileges)
                }

                // 等级阶梯数据：level_spec
                if (data && data.level_spec && Array.isArray(data.level_spec) && data.level_spec.length > 0) {
                    this.dailyLevelConfigs = data.level_spec.map(s => ({
                        level: s.level,
                        minScore: s.min_score,
                        maxScore: s.max_score,
                        title: s.level_title
                    }))
                }

                // current_level/current_score 兜底更新等级信息（仅当 info-pack 未提供有效值时）
                if (data && data.current_level && (!this.levelInfo.dailyLevel || this.levelInfo.dailyLevel === 1)) {
                    this.levelInfo.dailyLevel = data.current_level
                    if (!this.selectedLevel || this.selectedLevel === 1) {
                        this.selectedLevel = data.current_level
                    }
                }
                if (data && data.current_score && !this.levelInfo.dailyScore) {
                    this.levelInfo.dailyScore = data.current_score
                }
            } catch (error) {
                console.warn('loadPrivileges API failed:', error)
                this.privileges = this.getDefaultPrivileges()
                this.buildPrivilegeGroups(this.privileges)
            }
        },
        buildPrivilegeGroups(privileges) {
            const groupDefs = [
                { name: '功能权益', minLevel: 1, maxLevel: 4 },
                { name: '社区福利', minLevel: 5, maxLevel: 5 },
                { name: '身份权益', minLevel: 6, maxLevel: 8 }
            ]
            const groups = groupDefs.map(def => ({
                name: def.name,
                expanded: true,
                items: privileges.filter(p =>
                    p.need_jscore_level >= def.minLevel && p.need_jscore_level <= def.maxLevel
                )
            }))
            this.privilegeGroups = groups
            if (groups.length > 0 && groups[0].items.length > 0) {
                this.selectedBenefitId = groups[0].items[0].priv_id
            }
        },
        getDefaultPrivileges() {
            return [
                { priv_id: 0, title: '主动发起私信', icon: '', priv_status: 1, desc: [{desc_title:'解锁等级',desc_content:'JY1级'},{desc_title:'权益说明',desc_content:'可以主动向其他掘友发起私信沟通'}], need_jscore_level: 1, web_jump_url: '' },
                { priv_id: 1, title: '发起投票', icon: '', priv_status: 1, desc: [{desc_title:'解锁等级',desc_content:'JY2级'},{desc_title:'权益说明',desc_content:'可以在发布内容时发起投票'}], need_jscore_level: 2, web_jump_url: '' },
                { priv_id: 2, title: '评论区权限设置', icon: '', priv_status: 1, desc: [{desc_title:'解锁等级',desc_content:'JY3级'},{desc_title:'权益说明',desc_content:'可以设置评论区的查看和评论权限'}], need_jscore_level: 3, web_jump_url: '' },
                { priv_id: 3, title: '评论区Mark设置', icon: '', priv_status: 1, desc: [{desc_title:'解锁等级',desc_content:'JY4级'},{desc_title:'权益说明',desc_content:'可以在评论区使用Markdown语法'}], need_jscore_level: 4, web_jump_url: '' },
                { priv_id: 4, title: '使用掘金特色表情', icon: '', priv_status: 1, desc: [{desc_title:'解锁等级',desc_content:'JY5级'},{desc_title:'权益说明',desc_content:'可以使用掘金专属特色表情'}], need_jscore_level: 5, web_jump_url: '' },
                { priv_id: 5, title: '个性装扮', icon: '', priv_status: 1, desc: [{desc_title:'解锁等级',desc_content:'JY6级'},{desc_title:'权益说明',desc_content:'可以自定义个人主页装扮'}], need_jscore_level: 6, web_jump_url: '' },
                { priv_id: 6, title: '升级矿石奖励', icon: '', priv_status: 1, desc: [{desc_title:'解锁等级',desc_content:'JY7级'},{desc_title:'权益说明',desc_content:'矿石奖励比例提升'}], need_jscore_level: 7, web_jump_url: '' },
                { priv_id: 7, title: '参选掘金神评审', icon: '', priv_status: 1, desc: [{desc_title:'解锁等级',desc_content:'JY8级'},{desc_title:'权益说明',desc_content:'可以参选掘金神评审团'}], need_jscore_level: 8, web_jump_url: '' },
                { priv_id: 8, title: '参选小册评审团', icon: '', priv_status: 1, desc: [{desc_title:'解锁等级',desc_content:'JY8级'},{desc_title:'权益说明',desc_content:'可以参选小册评审团'}], need_jscore_level: 8, web_jump_url: '' }
            ]
        },
        openBenefitModal(perm) {
            if (perm && perm.code !== undefined) {
                const privId = typeof perm.code === 'number' ? perm.code : null
                if (privId !== null) {
                    this.selectedBenefitId = privId
                }
            }
            if (!this.privilegeGroups || this.privilegeGroups.length === 0) {
                this.loadPrivileges()
            }
            this.showBenefitModal = true
        },
        closeBenefitModal() {
            this.showBenefitModal = false
        },
        selectBenefit(priv) {
            if (priv.priv_status !== 1) {
                toast('该权益暂未解锁', 2)
                return
            }
            this.selectedBenefitId = priv.priv_id
        },
        toggleGroup(name) {
            const group = this.privilegeGroups.find(g => g.name === name)
            if (group) {
                group.expanded = !group.expanded
            }
        }
    }
}
</script>

<style lang="less" scoped>
@import '../../../styles/common';

.growth-page {
    min-height: 100vh;
    background: #F5F7FA;
}

.growth-content {
    max-width: 1200px;
    margin: 0 auto;
    padding: 24px;
    display: flex;
    gap: 16px;
    align-items: flex-start;
}

.main-area {
    flex: 1;
    min-width: 0;
}

// 页面标题栏
.page-header {
    display: flex;
    align-items: center;
    justify-content: space-between;
    margin-bottom: 20px;
}

.page-title {
    font-size: 20px;
    font-weight: 600;
    color: #1A1A1A;
}

.page-links {
    display: flex;
    gap: 24px;
}

.link-item {
    font-size: 14px;
    color: #666;
    cursor: pointer;
    display: flex;
    align-items: center;
    gap: 6px;
    transition: color 0.2s;
    &:hover { color: #7C3AED; }
}

.link-icon {
    font-size: 16px;
}

// 等级信息 + 阶梯图两栏
.level-overview {
    display: flex;
    gap: 16px;
    margin-bottom: 16px;
}

// 紫色渐变等级卡片
.level-card-purple {
    flex: 1;
    background: linear-gradient(135deg, #8B5CF6 0%, #A78BFA 60%, #C4B5FD 100%);
    border-radius: 16px;
    padding: 20px;
    color: #fff;
    position: relative;
    overflow: hidden;
    min-height: 180px;
    display: flex;
    flex-direction: column;
}

.level-card-content {
    display: flex;
    flex: 1;
    align-items: center;
    gap: 16px;
}

.level-info {
    flex: 1;
}

.level-badge {
    display: inline-block;
    background: rgba(255,255,255,0.25);
    padding: 2px 12px;
    border-radius: 10px;
    font-size: 12px;
    margin-bottom: 10px;
    font-weight: 500;
}

.level-name {
    font-size: 28px;
    font-weight: 700;
    margin-bottom: 14px;
    letter-spacing: 1px;
}

.level-score-section {
    margin-bottom: 4px;
}

.score-label {
    font-size: 13px;
    opacity: 0.85;
    margin-bottom: 4px;
}

.score-value {
    font-size: 36px;
    font-weight: 700;
    line-height: 1;
}

.level-crystal {
    width: 80px;
    height: 80px;
    flex-shrink: 0;
    position: relative;
}

.crystal-shape {
    width: 100%;
    height: 100%;
    background: linear-gradient(135deg, rgba(255,255,255,0.6) 0%, rgba(255,255,255,0.3) 50%, rgba(255,255,255,0.1) 100%);
    clip-path: polygon(50% 0%, 100% 38%, 82% 100%, 18% 100%, 0% 38%);
    filter: drop-shadow(0 4px 12px rgba(255,255,255,0.4));
}

.level-progress {
    margin-top: 12px;
}

.level-progress-bar {
    height: 4px;
    background: rgba(255,255,255,0.3);
    border-radius: 2px;
    overflow: hidden;
    margin-bottom: 6px;
}

.level-card-purple .progress-fill {
    height: 100%;
    background: #fff;
    border-radius: 2px;
    transition: width 0.8s ease;
}

.level-progress-text {
    font-size: 12px;
    opacity: 0.9;
}

// 深色等级分值阶梯图
.level-chart-dark {
    width: 380px;
    flex-shrink: 0;
    background: #1A1A1A;
    border-radius: 16px;
    padding: 20px 16px 16px;
    color: #fff;
}

.chart-title {
    font-size: 16px;
    font-weight: 600;
    margin-bottom: 16px;
    color: #fff;
}

// 阶梯行
.chart-stairs-row {
    display: flex;
    align-items: flex-end;
    gap: 4px;
    height: 180px;
    overflow: visible;
    padding-bottom: 4px;
}

// 每列
.chart-stair-cell {
    flex: 1;
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: flex-end;
    cursor: pointer;
    position: relative;
}

// ▼ 指示器（背景外顶部）
.stair-indicator {
    width: 20px;
    height: 12px;
    display: flex;
    align-items: center;
    justify-content: center;
    margin-bottom: 2px;

    .indicator-icon {
        color: #A78BFA;
        font-size: 10px;
        line-height: 10px;
    }
}

// 圆角矩形背景（包裹数值+柱体+标签）
.stair-wrapper {
    display: flex;
    flex-direction: column;
    align-items: center;
    padding: 8px 4px 6px;
    border-radius: 8px;
    transition: background 0.2s;

    &.active {
        background: rgba(124, 58, 237, 0.25);
        box-shadow: 0 0 12px rgba(124, 58, 237, 0.3);
    }

    &.selected {
        background: rgba(124, 58, 237, 0.12);
    }
}

// 经验值
.stair-value {
    margin-bottom: 6px;

    .value-num {
        font-size: 11px;
        color: #888;
        white-space: nowrap;
        display: block;
    }

    &.active .value-num {
        color: #A78BFA;
        font-weight: 700;
        font-size: 14px;
    }
}

// 柱体
.bar-stick {
    width: 16px;
    background: #333;
    border-radius: 3px 3px 0 0;
    transition: all 0.3s;

    &.unlocked {
        background: linear-gradient(180deg, #7C3AED 0%, #5B21B6 100%);
    }

    &.active {
        background: linear-gradient(180deg, #A78BFA 0%, #7C3AED 100%);
        box-shadow: 0 0 10px rgba(167, 139, 250, 0.6);
    }

    &.locked {
        background: #333;
    }
}

// 等级标签
.stair-label {
    font-size: 11px;
    color: #666;
    margin-top: 6px;
    white-space: nowrap;
    line-height: 1;

    &.active {
        color: #A78BFA;
        font-weight: 700;
    }
}

// 等级权益区
.benefits-section {
    background: #fff;
    border-radius: 16px;
    padding: 20px;
    margin-bottom: 16px;
    box-shadow: 0 2px 12px rgba(0,0,0,0.06);
}

.benefits-header {
    display: flex;
    align-items: center;
    justify-content: space-between;
    margin-bottom: 16px;
}

.benefits-title {
    font-size: 16px;
    font-weight: 600;
    color: #1A1A1A;
    display: flex;
    align-items: center;
    gap: 12px;
}

.benefits-level-tag {
    font-size: 12px;
    font-weight: 400;
    color: #7C3AED;
    background: #F3E8FF;
    padding: 2px 10px;
    border-radius: 10px;
}

.benefits-nav {
    display: flex;
    align-items: center;
    gap: 12px;
}

.nav-btn {
    width: 28px;
    height: 28px;
    border-radius: 50%;
    border: 1px solid #E8E8E8;
    background: #fff;
    cursor: pointer;
    display: flex;
    align-items: center;
    justify-content: center;
    font-size: 12px;
    &:hover { background: #F5F5F5; }
}

.page-indicator {
    font-size: 13px;
    color: #999;
}

.benefits-list {
    display: flex;
    gap: 24px;
    overflow-x: auto;
}

.benefit-item {
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: 8px;
    min-width: 80px;
    cursor: pointer;
    transition: transform 0.2s;

    &:hover {
        transform: translateY(-2px);
    }
}

.benefit-item.locked .benefit-icon-wrap {
    filter: grayscale(0.8);
    opacity: 0.5;
}

.benefit-item.locked .benefit-name {
    color: #BBB;
}

.benefit-icon-wrap {
    width: 48px;
    height: 48px;
    border-radius: 50%;
    background: #FFF7E6;
    display: flex;
    align-items: center;
    justify-content: center;
    position: relative;
}

.benefit-item:not(.locked) .benefit-icon-wrap {
    background: linear-gradient(135deg, #F3E8FF 0%, #E9D5FF 100%);
}

.benefit-icon {
    font-size: 24px;
}

.benefit-img {
    width: 32px;
    height: 32px;
    object-fit: contain;
    border-radius: 6px;
}

.benefit-lock {
    position: absolute;
    top: -4px;
    right: -4px;
    font-size: 14px;
    filter: none;
}

.benefit-name {
    font-size: 12px;
    color: #666;
    text-align: center;
    max-width: 80px;
    line-height: 1.3;
}

// 升级行为任务区
.tasks-section {
    background: #fff;
    border-radius: 16px;
    padding: 16px;
    margin-bottom: 16px;
    box-shadow: 0 2px 12px rgba(0,0,0,0.06);
}

.tasks-header {
    display: flex;
    align-items: center;
    justify-content: space-between;
    margin-bottom: 14px;
    padding-bottom: 10px;
    border-bottom: 1px solid #F0F2F5;
}

.tasks-title {
    font-size: 16px;
    font-weight: 600;
    color: #1A1A1A;
}

.tasks-today-score {
    font-size: 14px;
    color: #7C3AED;
}

.task-group {
    margin-bottom: 18px;
    &:last-child { margin-bottom: 0; }
}

.group-title {
    font-size: 14px;
    font-weight: 500;
    color: #1A1A1A;
    margin-bottom: 10px;
    padding-bottom: 6px;
    border-bottom: 2px solid #7C3AED;
    display: inline-block;
}

.group-tasks {
    display: grid;
    grid-template-columns: repeat(3, 1fr);
    gap: 12px;
}

.task-item {
    display: flex;
    align-items: center;
    padding: 14px;
    background: #FAFAFA;
    border-radius: 10px;
    border: 1px solid #F0F2F5;
    transition: all 0.2s;

    &:hover {
        background: #F3E8FF;
        border-color: #E9D5FF;
    }
}

.task-icon-wrap {
    width: 36px;
    height: 36px;
    border-radius: 50%;
    background: #F0F5FF;
    display: flex;
    align-items: center;
    justify-content: center;
    flex-shrink: 0;
    margin-right: 10px;
}

.task-icon {
    font-size: 18px;
}

.task-img {
    width: 24px;
    height: 24px;
    object-fit: contain;
}

.task-info {
    flex: 1;
    min-width: 0;
    display: flex;
    flex-direction: column;
    gap: 2px;
}

.task-name {
    font-size: 13px;
    color: #1A1A1A;
    line-height: 1.3;
}

.task-progress-text {
    font-size: 12px;
    color: #999;
    line-height: 1.3;
}

.task-btn {
    padding: 5px 16px;
    border: 1px solid #7C3AED;
    border-radius: 14px;
    background: transparent;
    color: #7C3AED;
    font-size: 12px;
    cursor: pointer;
    white-space: nowrap;
    flex-shrink: 0;
    transition: all 0.2s;
    &:hover:not(:disabled) { background: #F3E8FF; }
    &.completed, &:disabled {
        border-color: #D9D9D9;
        color: #999;
        cursor: default;
        background: transparent;
    }
}

// Modal 样式
.modal-overlay {
    position: fixed;
    top: 0; left: 0; right: 0; bottom: 0;
    background: rgba(0,0,0,0.5);
    display: flex;
    align-items: center;
    justify-content: center;
    z-index: 1000;
    padding: 16px;
}

.modal-content {
    background: #fff;
    border-radius: 16px;
    width: 100%;
    max-width: 480px;
    max-height: 80vh;
    overflow-y: auto;
}

.modal-header {
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding: 20px 20px 12px;
    border-bottom: 1px solid #F0F2F5;
}

.modal-title {
    font-size: 16px;
    font-weight: 600;
    color: #1A1A1A;
}

.modal-close {
    font-size: 24px;
    color: #999;
    cursor: pointer;
    &:hover { color: #1A1A1A; }
}

.modal-body {
    padding: 16px 20px 20px;
}

.rules-section {
    margin-bottom: 16px;
    &:last-child { margin-bottom: 0; }
}

.rules-title {
    font-size: 14px;
    font-weight: 600;
    color: #333;
    margin-bottom: 10px;
}

.rules-desc {
    font-size: 13px;
    color: #666;
    line-height: 1.6;
}

.rules-list {
    list-style: none;
    padding: 0;
    margin: 0;
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
    li {
        font-size: 13px;
        color: #666;
        padding: 6px 12px;
        background: #F5F7FA;
        border-radius: 8px;
    }
}

// 底部
.growth-footer {
    text-align: center;
    padding: 24px 0;
    font-size: 12px;
    color: #B0B0B0;
}

// 权益说明 Modal
.benefit-modal {
    background: #fff;
    border-radius: 16px;
    width: 100%;
    max-width: 720px;
    max-height: 85vh;
    overflow: hidden;
    display: flex;
    flex-direction: column;
}

.benefit-modal-header {
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding: 20px 24px 16px;
    border-bottom: 1px solid #F0F2F5;
}

.benefit-modal-title {
    font-size: 18px;
    font-weight: 600;
    color: #1A1A1A;
}

.benefit-modal-body {
    display: flex;
    flex: 1;
    overflow: hidden;
    min-height: 400px;
}

.benefit-sidebar {
    width: 220px;
    border-right: 1px solid #F0F2F5;
    overflow-y: auto;
    padding: 12px 0;
    flex-shrink: 0;
}

.benefit-group {
    margin-bottom: 8px;
}

.benefit-group-title {
    padding: 10px 16px;
    font-size: 14px;
    font-weight: 600;
    color: #333;
    cursor: pointer;
    display: flex;
    align-items: center;
    gap: 8px;
    transition: background 0.2s;

    &:hover {
        background: #F9FAFB;
    }
}

.group-arrow {
    font-size: 10px;
    color: #999;
    transition: transform 0.2s;

    &.expanded {
        transform: rotate(90deg);
    }
}

.benefit-group-list {
    padding: 0 8px;
}

.benefit-nav-item {
    padding: 8px 12px;
    font-size: 13px;
    color: #666;
    border-radius: 6px;
    cursor: pointer;
    display: flex;
    justify-content: space-between;
    align-items: center;
    transition: all 0.2s;

    &:hover {
        background: #F3E8FF;
        color: #7C3AED;
    }

    &.active {
        background: #7C3AED;
        color: #fff;

        .lock-tag {
            background: rgba(255,255,255,0.3);
            color: #fff;
        }
    }

    &.locked {
        color: #BBB;
    }
}

.lock-tag {
    font-size: 11px;
    color: #999;
    background: #F0F2F5;
    padding: 2px 6px;
    border-radius: 4px;
}

.benefit-detail {
    flex: 1;
    padding: 28px 32px;
    overflow-y: auto;
    display: flex;
    flex-direction: column;
}

.benefit-detail-icon {
    width: 80px;
    height: 80px;
    border-radius: 16px;
    background: linear-gradient(135deg, #F3E8FF 0%, #E9D5FF 100%);
    display: flex;
    align-items: center;
    justify-content: center;
    margin-bottom: 20px;
    overflow: hidden;

    img {
        width: 48px;
        height: 48px;
        object-fit: contain;
    }
}

.icon-fallback {
    font-size: 36px;
}

.benefit-detail-title {
    font-size: 22px;
    font-weight: 700;
    color: #1A1A1A;
    margin-bottom: 20px;
}

.benefit-detail-desc {
    margin-bottom: 16px;
}

.desc-title {
    font-size: 13px;
    color: #999;
    margin-bottom: 4px;
}

.desc-content {
    font-size: 14px;
    color: #333;
    line-height: 1.6;

    &.unlocked {
        color: #7C3AED;
        font-weight: 600;
    }
}

.benefit-detail-status {
    margin-top: 16px;
    padding: 10px 16px;
    background: #FFF7E6;
    border-radius: 8px;
    color: #FA8C16;
    font-size: 13px;
    align-self: flex-start;
}

.benefit-jump {
    margin-top: 20px;
    display: inline-block;
    padding: 10px 20px;
    background: linear-gradient(135deg, #7C3AED 0%, #5B21B6 100%);
    color: #fff;
    border-radius: 20px;
    font-size: 14px;
    text-decoration: none;
    align-self: flex-start;
    transition: opacity 0.2s;

    &:hover {
        opacity: 0.9;
    }
}

// 响应式
@media screen and (max-width: 768px) {
    .growth-content {
        padding: 12px;
        flex-direction: column;
    }
    .level-overview {
        flex-direction: column;
    }
    .level-chart-dark {
        width: 100%;
    }
    .benefit-list {
        gap: 16px;
    }
    .chart-bars {
        height: 160px;
    }
    .benefit-modal {
        max-width: 100%;
        max-height: 90vh;
    }
    .benefit-modal-body {
        flex-direction: column;
    }
    .benefit-sidebar {
        width: 100%;
        border-right: none;
        border-bottom: 1px solid #F0F2F5;
        max-height: 200px;
    }
    .benefit-detail {
        padding: 20px 16px;
    }
}
</style>
