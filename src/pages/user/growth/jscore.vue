<template>
    <div class="jscore-page">
        <div class="art-top" v-if="!isDesktop"><HomeBar/></div>
        <div class="jscore-content-wrapper">
            <!-- 左侧侧边栏 -->
            <UserCenterSidebar
                active-menu="growth"
                @menu-click="handleSidebarMenuClick"
            />

            <!-- 右侧主内容区 -->
            <div class="main-area">
                <!-- 顶部导航 -->
                <div class="header-nav">
                    <div class="nav-back" @click="goBack">
                        <span class="back-arrow">&lt;</span>
                        <span class="back-text">返回</span>
                    </div>
                    <div class="nav-title">我的掘友分</div>
                    <div class="nav-right" @click="showRulesModal = true">
                        <span class="rules-link">等级规则</span>
                    </div>
                </div>

                <!-- 现有内容 -->
                <div class="jscore-content">
            <!-- 统计周期 -->
            <div class="stat-period">
                <span class="period-label">统计周期：</span>
                <span class="period-date">{{ statDate }}</span>
            </div>

            <!-- 维度概览卡片 -->
            <div class="dimension-cards-wrapper">
                <div class="dimension-cards" ref="dimensionCards">
                    <div
                        v-for="(dim, index) in dimensionList"
                        :key="dim.key"
                        class="dimension-card"
                        :class="{ active: dim.key === activeDimensionKey }"
                        @click="onDimensionClick(dim)"
                    >
                        <div class="dim-name">{{ dim.name }}</div>
                        <div class="dim-today" :class="{ positive: dim.today > 0, negative: dim.today < 0 }">
                            {{ dim.today > 0 ? '+' : '' }}{{ dim.today }}
                        </div>
                        <div class="dim-total">
                            总计 <span class="dim-total-val">{{ dim.total }}</span>
                        </div>
                    </div>
                </div>
            </div>

            <!-- 分类筛选 Tabs -->
            <div class="category-tabs">
                <div
                    v-for="tab in categoryTabs"
                    :key="tab.key"
                    class="tab-item"
                    :class="{ active: currentCategory === tab.key }"
                    @click="switchCategory(tab.key)"
                >
                    {{ tab.name }}
                </div>
            </div>

            <!-- 雷达图（仅总览 Tab） -->
            <div class="radar-chart-section" v-if="currentCategory === 'overview' && chartData">
                <div class="section-card">
                    <canvas ref="radarCanvas" class="radar-canvas"></canvas>
                </div>
            </div>

            <!-- 时间线列表 -->
            <div class="timeline-section">
                <div class="timeline-list" v-if="timelineList.length > 0">
                    <div
                        v-for="item in timelineList"
                        :key="item.id"
                        class="timeline-item"
                    >
                        <div class="timeline-left">
                            <div class="timeline-time">{{ formatTime(item.created_at) }}</div>
                            <div class="timeline-dot"></div>
                        </div>
                        <div class="timeline-line"></div>
                        <div class="timeline-content">
                            <div class="timeline-desc">{{ item.action_desc }}</div>
                            <div class="timeline-score" :class="{ positive: item.score > 0, negative: item.score < 0 }">
                                {{ item.score > 0 ? '+' : '' }}{{ item.score }}
                            </div>
                        </div>
                    </div>
                    <div class="load-more" v-if="hasMore" @click="loadMore">
                        <span class="load-more-text">加载更多</span>
                    </div>
                    <div class="load-more" v-if="!hasMore && timelineList.length > 0">
                        <span class="load-more-text no-more">没有更多了</span>
                    </div>
                </div>
                <div class="empty-state" v-else-if="!loading">
                    <div class="empty-icon">
                        <svg width="80" height="80" viewBox="0 0 80 80" fill="none">
                            <rect x="10" y="20" width="60" height="45" rx="6" fill="#E8E8E8"/>
                            <rect x="16" y="28" width="25" height="3" rx="1.5" fill="#D0D0D0"/>
                            <rect x="16" y="35" width="40" height="3" rx="1.5" fill="#D0D0D0"/>
                            <rect x="16" y="42" width="30" height="3" rx="1.5" fill="#D0D0D0"/>
                            <rect x="16" y="49" width="35" height="3" rx="1.5" fill="#D0D0D0"/>
                            <circle cx="40" cy="14" r="8" fill="#E8E8E8"/>
                            <circle cx="40" cy="14" r="4" fill="#D0D0D0"/>
                        </svg>
                    </div>
                    <div class="empty-text">暂无任何数据噢~</div>
                </div>
                <div class="loading-spinner" v-if="loading">
                    <span>加载中...</span>
                </div>
                </div>
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
                        <div class="rules-title">☀️ 逐日等级获取方式</div>
                        <ul class="rules-list">
                            <li>每日登录 +10分</li>
                            <li>阅读文章 +2分</li>
                            <li>发表评论 +5分</li>
                            <li>点赞 +1分</li>
                            <li>分享 +3分</li>
                            <li>关注 +2分</li>
                        </ul>
                    </div>
                    <div class="rules-section">
                        <div class="rules-title">💪 逐力值获取方式</div>
                        <ul class="rules-list">
                            <li>发布文章 +10分</li>
                            <li>文章被点赞 +2分</li>
                            <li>文章被评论 +3分</li>
                            <li>文章被收藏 +5分</li>
                            <li>文章被阅读 +1分</li>
                        </ul>
                    </div>
                </div>
            </div>
        </div>
    </div>
</template>

<script>
import { getJScoreOverview, getJScoreDetail } from '@/apis/jscore'
import HomeBar from '@/components/bars/home_bar'
import UserCenterSidebar from '@/components/user/UserCenterSidebar'
import Utils from '@/utils/env'

const CATEGORY_MAP = {
    overview: { name: '总览', apiKey: '' },
    effect: { name: '影响力', apiKey: 'effect' },
    active: { name: '活跃', apiKey: 'active' },
    learn: { name: '学习', apiKey: 'learn' },
    basic: { name: '基础', apiKey: 'basic' },
    spec: { name: '规范', apiKey: 'spec' }
}

export default {
    name: 'JScore',
    components: { HomeBar, UserCenterSidebar },
    data() {
        return {
            statDate: '',
            overviewData: null,
            chartData: null,
            dimensionList: [],
            categoryTabs: [],
            currentCategory: 'overview',
            activeDimensionKey: '',
            timelineList: [],
            nextCursor: '',
            hasMore: false,
            loading: false,
            showRulesModal: false,
            pageSize: 20
        }
    },
    computed: {
        isDesktop() {
            return Utils.isDesktop()
        }
    },
    created() {
        this.categoryTabs = Object.entries(CATEGORY_MAP).map(([key, val]) => ({
            key,
            name: val.name
        }))
        // 从 URL 参数恢复 category
        const category = this.$route.query.category
        if (category && CATEGORY_MAP[category]) {
            this.currentCategory = category
        }
        this.syncDimensionKey()
    },
    mounted() {
        this.loadOverview()
        this.loadDetail()
    },
    methods: {
        goBack() {
            this.$router.push('/user/center/growth')
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
        formatTime(timeStr) {
            if (!timeStr) return ''
            const parts = timeStr.split(' ')
            return parts.length === 2 ? parts[1] : timeStr
        },
        syncDimensionKey() {
            const mapping = {
                overview: '',
                effect: 'effect',
                active: 'active',
                learn: 'learn',
                basic: 'basic',
                spec: 'spec'
            }
            this.activeDimensionKey = mapping[this.currentCategory] || ''
        },
        onDimensionClick(dim) {
            if (dim.key === 'total') return
            const tabMap = {
                basic: 'basic',
                active: 'active',
                learn: 'learn',
                effect: 'effect',
                spec: 'spec'
            }
            const tabKey = tabMap[dim.key]
            if (tabKey) {
                this.switchCategory(tabKey)
            }
        },
        switchCategory(category) {
            if (this.currentCategory === category) return
            this.currentCategory = category
            this.syncDimensionKey()
            this.timelineList = []
            this.nextCursor = ''
            this.hasMore = false
            // 更新 URL Query 参数
            this.$router.replace({
                query: { category: category }
            })
            this.loadDetail()
            // 切换后重新绘制雷达图
            if (category === 'overview') {
                this.$nextTick(() => {
                    this.drawRadarChart()
                })
            }
        },
        async loadOverview() {
            try {
                const res = await getJScoreOverview()
                if (res.code === 200 && res.data) {
                    this.overviewData = res.data
                    this.statDate = this.formatDate(res.data.stat_date)
                    this.chartData = res.data.chart
                    this.buildDimensionList(res.data.summary)
                    this.$nextTick(() => {
                        this.drawRadarChart()
                    })
                }
            } catch (e) {
                // Keep defaults
            }
        },
        formatDate(dateStr) {
            if (!dateStr) {
                const d = new Date()
                const y = d.getFullYear()
                const m = String(d.getMonth() + 1).padStart(2, '0')
                const day = String(d.getDate()).padStart(2, '0')
                return y + '.' + m + '.' + day
            }
            return dateStr.replace(/-/g, '.')
        },
        buildDimensionList(summary) {
            if (!summary) return
            const dimConfig = [
                { key: 'total', name: '总计变化' },
                { key: 'basic', name: '社区基础' },
                { key: 'active', name: '社区活跃' },
                { key: 'learn', name: '社区学习' },
                { key: 'effect', name: '社区影响力' },
                { key: 'spec', name: '社区规范' }
            ]
            this.dimensionList = dimConfig.map(cfg => {
                const item = summary[cfg.key]
                return {
                    key: cfg.key,
                    name: cfg.name,
                    today: item ? (item.today || 0) : 0,
                    total: item ? (item.total || 0) : 0
                }
            })
        },
        async loadDetail() {
            if (this.loading) return
            this.loading = true
            try {
                const params = {
                    page_size: this.pageSize
                }
                const category = CATEGORY_MAP[this.currentCategory]
                if (category.apiKey) {
                    params.category = category.apiKey
                }
                if (this.nextCursor) {
                    params.cursor = this.nextCursor
                }
                const res = await getJScoreDetail(params)
                if (res.code === 200 && res.data) {
                    const data = res.data
                    const list = data.list || []
                    if (this.nextCursor) {
                        this.timelineList = this.timelineList.concat(list)
                    } else {
                        this.timelineList = list
                    }
                    this.nextCursor = data.next_cursor || ''
                    this.hasMore = !!data.has_more
                }
            } catch (e) {
                // Keep current list
            } finally {
                this.loading = false
            }
        },
        loadMore() {
            if (!this.loading && this.hasMore) {
                this.loadDetail()
            }
        },
        drawRadarChart() {
            if (this.currentCategory !== 'overview') return
            const canvas = this.$refs.radarCanvas
            if (!canvas || !this.chartData) return
            const ctx = canvas.getContext('2d')
            const dpr = window.devicePixelRatio || 1
            const rect = canvas.parentElement.getBoundingClientRect()
            const width = rect.width || 300
            const height = 280
            canvas.width = width * dpr
            canvas.height = height * dpr
            canvas.style.width = width + 'px'
            canvas.style.height = height + 'px'
            ctx.scale(dpr, dpr)

            const centerX = width / 2
            const centerY = height / 2 + 10
            const radius = Math.min(width, height) / 2 - 40

            const dimensions = this.chartData.dimensions || ['影响力', '活跃', '学习', '基础', '规范']
            const values = this.chartData.values || [0, 0, 0, 0, 0]
            const sides = dimensions.length
            const maxVal = Math.max(...values, 1)

            const colors = [
                '#FF6B6B', '#4ECDC4', '#45B7D1', '#FFA94D', '#A29BFE'
            ]

            // 绘制背景网格
            const levels = 5
            for (let l = 1; l <= levels; l++) {
                const r = (radius / levels) * l
                ctx.beginPath()
                for (let i = 0; i < sides; i++) {
                    const angle = (Math.PI * 2 * i) / sides - Math.PI / 2
                    const x = centerX + r * Math.cos(angle)
                    const y = centerY + r * Math.sin(angle)
                    if (i === 0) {
                        ctx.moveTo(x, y)
                    } else {
                        ctx.lineTo(x, y)
                    }
                }
                ctx.closePath()
                ctx.strokeStyle = '#E8E8E8'
                ctx.lineWidth = 1
                ctx.stroke()
            }

            // 绘制轴线
            for (let i = 0; i < sides; i++) {
                const angle = (Math.PI * 2 * i) / sides - Math.PI / 2
                const x = centerX + radius * Math.cos(angle)
                const y = centerY + radius * Math.sin(angle)
                ctx.beginPath()
                ctx.moveTo(centerX, centerY)
                ctx.lineTo(x, y)
                ctx.strokeStyle = '#E8E8E8'
                ctx.lineWidth = 1
                ctx.stroke()
            }

            // 绘制数据区域
            ctx.beginPath()
            for (let i = 0; i < sides; i++) {
                const val = values[i] || 0
                const r = (val / maxVal) * radius
                const angle = (Math.PI * 2 * i) / sides - Math.PI / 2
                const x = centerX + r * Math.cos(angle)
                const y = centerY + r * Math.sin(angle)
                if (i === 0) {
                    ctx.moveTo(x, y)
                } else {
                    ctx.lineTo(x, y)
                }
            }
            ctx.closePath()

            // 填充渐变
            const gradient = ctx.createRadialGradient(centerX, centerY, 0, centerX, centerY, radius)
            gradient.addColorStop(0, 'rgba(30, 128, 255, 0.3)')
            gradient.addColorStop(1, 'rgba(30, 128, 255, 0.05)')
            ctx.fillStyle = gradient
            ctx.fill()

            ctx.strokeStyle = '#1E80FF'
            ctx.lineWidth = 2
            ctx.stroke()

            // 绘制数据点
            for (let i = 0; i < sides; i++) {
                const val = values[i] || 0
                const r = (val / maxVal) * radius
                const angle = (Math.PI * 2 * i) / sides - Math.PI / 2
                const x = centerX + r * Math.cos(angle)
                const y = centerY + r * Math.sin(angle)
                ctx.beginPath()
                ctx.arc(x, y, 4, 0, Math.PI * 2)
                ctx.fillStyle = colors[i % colors.length]
                ctx.fill()
                ctx.strokeStyle = '#FFFFFF'
                ctx.lineWidth = 2
                ctx.stroke()
            }

            // 绘制维度标签
            ctx.font = '12px -apple-system, BlinkMacSystemFont, "PingFang SC", "Microsoft YaHei", sans-serif'
            ctx.textAlign = 'center'
            ctx.textBaseline = 'middle'
            for (let i = 0; i < sides; i++) {
                const angle = (Math.PI * 2 * i) / sides - Math.PI / 2
                const labelRadius = radius + 22
                const x = centerX + labelRadius * Math.cos(angle)
                const y = centerY + labelRadius * Math.sin(angle)
                ctx.fillStyle = colors[i % colors.length]
                ctx.fillText(dimensions[i], x, y)
            }

            // 绘制每个维度的数值
            ctx.font = '11px -apple-system, BlinkMacSystemFont, "PingFang SC", "Microsoft YaHei", sans-serif'
            ctx.fillStyle = '#666666'
            for (let i = 0; i < sides; i++) {
                const val = values[i] || 0
                const r = (val / maxVal) * radius
                const angle = (Math.PI * 2 * i) / sides - Math.PI / 2
                const x = centerX + r * Math.cos(angle)
                const y = centerY + r * Math.sin(angle)
                const offsetR = 14
                const nx = centerX + offsetR * Math.cos(angle)
                const ny = centerY + offsetR * Math.sin(angle)
                ctx.fillText(val, nx, ny)
            }
        }
    },
    watch: {
        '$route.query.category': function(newVal) {
            if (newVal && CATEGORY_MAP[newVal] && newVal !== this.currentCategory) {
                this.switchCategory(newVal)
            }
        }
    }
}
</script>

<style lang="less" scoped>
@import '../../../styles/common';

.jscore-page {
    min-height: 100vh;
    background: #F5F7FA;
}

.jscore-content-wrapper {
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
    background: #fff;
    border-radius: 16px;
    padding: 20px;
    box-shadow: 0 2px 12px rgba(0,0,0,0.06);
}

// 顶部导航
.header-nav {
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding-bottom: 16px;
    border-bottom: 1px solid #F0F2F5;
    margin-bottom: 20px;
}

.nav-back {
    display: flex;
    align-items: center;
    gap: 4px;
    cursor: pointer;
    padding: 4px 0;
    user-select: none;
}

.back-arrow {
    font-size: 16px;
    color: #1A1A1A;
    font-weight: 600;
}

.back-text {
    font-size: 14px;
    color: #1A1A1A;
}

.nav-title {
    font-size: 18px;
    font-weight: 600;
    color: #1A1A1A;
}

.nav-right {
    cursor: pointer;
    user-select: none;
}

.rules-link {
    font-size: 14px;
    color: #1A73E8;
    &:hover { color: #1557B0; }
}

// 内容区
.jscore-content {
    // 由 main-area 控制布局
}

// 统计周期
.stat-period {
    padding: 0 4px;
    margin-bottom: 16px;
    font-size: 13px;
    color: #8C8C8C;
}

// 维度卡片
.dimension-cards-wrapper {
    overflow-x: auto;
    -webkit-overflow-scrolling: touch;
    margin-bottom: 20px;
    padding-bottom: 4px;
    &::-webkit-scrollbar { height: 0; }
}

.dimension-cards {
    display: flex;
    gap: 12px;
    padding: 2px 0;
    min-width: min-content;
}

.dimension-card {
    flex-shrink: 0;
    width: 120px;
    padding: 16px;
    background: #F8F9FA;
    border-radius: 12px;
    cursor: pointer;
    transition: all 0.2s ease;
    border: 2px solid transparent;
    &:hover {
        box-shadow: 0 4px 12px rgba(0,0,0,0.08);
        transform: translateY(-2px);
    }
    &.active {
        border-color: #7C3AED;
        background: #F3E8FF;
    }
}

.dim-name {
    font-size: 13px;
    color: #666;
    margin-bottom: 8px;
    white-space: nowrap;
}

.dim-today {
    font-size: 24px;
    font-weight: 700;
    color: #1A1A1A;
    margin-bottom: 4px;
    &.positive { color: #52C41A; }
    &.negative { color: #F53F3F; }
}

.dim-total {
    font-size: 12px;
    color: #999;
}

.dim-total-val {
    font-weight: 500;
    color: #666;
}

// 分类 Tabs（pill 形状）
.category-tabs {
    display: flex;
    gap: 8px;
    margin-bottom: 20px;
    flex-wrap: wrap;
}

.tab-item {
    padding: 6px 16px;
    font-size: 13px;
    color: #666;
    cursor: pointer;
    border-radius: 16px;
    transition: all 0.2s ease;
    white-space: nowrap;
    user-select: none;
    background: #F5F5F5;
    &:hover {
        color: #7C3AED;
        background: #F3E8FF;
    }
    &.active {
        color: #fff;
        font-weight: 500;
        background: #7C3AED;
    }
}

// 雷达图
.radar-chart-section {
    margin-bottom: 20px;
}

.section-card {
    background: #1A1A1A;
    border-radius: 12px;
    padding: 20px;
}

.radar-canvas {
    display: block;
    width: 100%;
    height: 300px;
}

// 时间线（表格样式）
.timeline-section {
    background: #fff;
    border-radius: 12px;
    min-height: 200px;
}

.timeline-list {
    position: relative;
}

.timeline-item {
    display: flex;
    align-items: center;
    padding: 12px 0;
    border-bottom: 1px solid #F0F2F5;
    &:last-child { border-bottom: none; }
}

.timeline-left {
    display: flex;
    align-items: center;
    width: 140px;
    flex-shrink: 0;
}

.timeline-time {
    font-size: 13px;
    color: #999;
    white-space: nowrap;
}

.timeline-dot {
    display: none;
}

.timeline-line {
    display: none;
}

.timeline-content {
    flex: 1;
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding-left: 16px;
}

.timeline-desc {
    font-size: 14px;
    color: #1A1A1A;
    flex: 1;
    margin-right: 12px;
}

.timeline-score {
    font-size: 15px;
    font-weight: 600;
    flex-shrink: 0;
    white-space: nowrap;
    &.positive { color: #52C41A; }
    &.negative { color: #F53F3F; }
}

// 加载更多
.load-more {
    text-align: center;
    padding: 16px 0 8px;
}

.load-more-text {
    font-size: 13px;
    color: #7C3AED;
    cursor: pointer;
    &:hover { color: #6D28D9; }
    &.no-more {
        color: #C0C0C0;
        cursor: default;
    }
}

// 空状态
.empty-state {
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    padding: 60px 0;
}

.empty-icon {
    margin-bottom: 16px;
    opacity: 0.6;
}

.empty-text {
    font-size: 14px;
    color: #C0C0C0;
}

// 加载中
.loading-spinner {
    text-align: center;
    padding: 24px 0;
    font-size: 13px;
    color: #999;
}

// Modal
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
    max-width: 400px;
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

.rules-list {
    list-style: none;
    padding: 0;
    margin: 0;
    display: flex;
    flex-wrap: wrap;
    gap: 10px;
    li {
        font-size: 13px;
        color: #666;
        padding: 6px 12px;
        background: #F5F7FA;
        border-radius: 8px;
    }
}

// 响应式
@media screen and (max-width: 768px) {
    .jscore-content-wrapper {
        padding: 12px;
        flex-direction: column;
    }
    .main-area {
        padding: 16px;
    }
    .dimension-card {
        width: 100px;
        padding: 12px;
    }
    .dim-today {
        font-size: 20px;
    }
    .tab-item {
        padding: 4px 12px;
        font-size: 12px;
    }
    .timeline-left {
        width: 100px;
    }
    .timeline-time {
        font-size: 11px;
    }
}
</style>