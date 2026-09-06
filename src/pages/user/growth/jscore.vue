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

                <!-- 概览区：左黑卡雷达图 + 右6维数值网格 -->
                <div class="overview-section">
                    <div class="radar-card">
                        <div class="radar-header">
                            <span class="radar-title">掘友分概览</span>
                            <span class="radar-period">统计周期：{{ statDate }}</span>
                        </div>
                        <div class="radar-body">
                            <svg
                                v-if="chartData && chartData.dimensions && chartData.values"
                                viewBox="0 0 320 280"
                                class="radar-svg"
                            >
                                <!-- 5 层网格 -->
                                <g v-for="lvl in [1,2,3,4,5]" :key="'grid-'+lvl">
                                    <polygon
                                        :points="gridPolygon(lvl, 5, 110)"
                                        fill="none"
                                        stroke="rgba(255,255,255,0.10)"
                                        stroke-width="1"
                                    />
                                </g>
                                <!-- 轴线 -->
                                <line
                                    v-for="(d, i) in chartData.dimensions"
                                    :key="'axis-'+i"
                                    x1="160" y1="140"
                                    :x2="axisEndX(i, 5, 110)"
                                    :y2="axisEndY(i, 5, 110)"
                                    stroke="rgba(255,255,255,0.10)"
                                    stroke-width="1"
                                />
                                <!-- 数据多边形 -->
                                <polygon
                                    :points="dataPolygon(5, 110)"
                                    fill="rgba(255,165,0,0.18)"
                                    stroke="#FFA500"
                                    stroke-width="2"
                                />
                                <!-- 数据点 -->
                                <circle
                                    v-for="(d, i) in chartData.dimensions"
                                    :key="'pt-'+i"
                                    :cx="dataPointX(i, chartData.values, 5, 110)"
                                    :cy="dataPointY(i, chartData.values, 5, 110)"
                                    r="3.5"
                                    fill="#FFA500"
                                    stroke="#1A1A1A"
                                    stroke-width="1.5"
                                />
                                <!-- 维度标签 -->
                                <text
                                    v-for="(d, i) in chartData.dimensions"
                                    :key="'lbl-'+i"
                                    :x="labelX(i, 5, 110)"
                                    :y="labelY(i, 5, 110)"
                                    text-anchor="middle"
                                    dominant-baseline="middle"
                                    fill="#E5E7EB"
                                    font-size="12"
                                >{{ d }}</text>
                            </svg>
                            <div v-else class="radar-empty">暂无数据</div>
                        </div>
                        <div class="radar-legend">
                            <span class="legend-dot"></span>
                            <span class="legend-text">我的得分</span>
                        </div>
                    </div>

                    <!-- 右：6 维数值网格（总计变化 + 5维） -->
                    <div class="dimension-grid">
                        <div
                            v-for="dim in dimensionList"
                            :key="dim.key"
                            class="dim-grid-card"
                            :class="['dim-grid-' + dim.key]"
                            @click="onDimensionClick(dim)"
                        >
                            <div class="dim-grid-name">
                                <span>{{ dim.name }}</span>
                                <span class="dim-grid-arrow">&gt;</span>
                            </div>
                            <div
                                class="dim-grid-today"
                                :class="{ positive: dim.today > 0, negative: dim.today < 0, zero: dim.today === 0 }"
                            >
                                <span v-if="dim.today > 0">+{{ formatScore(dim.today) }}</span>
                                <span v-else>{{ formatScore(dim.today) }}</span>
                            </div>
                        </div>
                    </div>
                </div>

                <!-- 行为数据区 -->
                <div class="data-section">
                    <div class="section-header">
                        <span class="section-title">社区行为数据</span>
                    </div>

                    <!-- Pill 形 Tabs -->
                    <div class="pill-tabs">
                        <div
                            v-for="tab in categoryTabs"
                            :key="tab.key"
                            class="pill-tab"
                            :class="{ active: currentCategory === tab.key }"
                            @click="switchCategory(tab.key)"
                        >{{ tab.name }}</div>
                    </div>

                    <!-- 三列明细表 -->
                    <div class="detail-table" v-if="timelineList.length > 0 || loading">
                        <div class="detail-head">
                            <div class="col col-behavior">升级行为</div>
                            <div class="col col-score">掘友分变化</div>
                            <div class="col col-time">时间</div>
                        </div>
                        <div class="detail-body">
                            <div
                                v-for="item in timelineList"
                                :key="item.id"
                                class="detail-row"
                            >
                                <div class="col col-behavior">
                                    <span class="behavior-name">{{ item.actionName || item.action_code || '行为' }}</span>
                                    <span v-if="item.actionDesc && item.actionName !== item.actionDesc" class="behavior-desc">{{ item.actionDesc }}</span>
                                </div>
                                <div
                                    class="col col-score"
                                    :class="{ positive: item.score > 0, negative: item.score < 0 }"
                                >
                                    <span v-if="item.score > 0">+{{ formatScore(item.score) }}</span>
                                    <span v-else>{{ formatScore(item.score) }}</span>
                                </div>
                                <div class="col col-time">{{ item.createdAt }}</div>
                            </div>
                        </div>

                        <div class="load-more" v-if="hasMore && !loading" @click="loadMore">
                            <span class="load-more-text">加载更多</span>
                        </div>
                        <div class="load-more" v-else-if="!hasMore && timelineList.length > 0 && !loading">
                            <span class="load-more-text no-more">没有更多了</span>
                        </div>
                        <div class="loading-spinner" v-if="loading">
                            <span>加载中...</span>
                        </div>
                    </div>

                    <!-- 空状态 -->
                    <div class="empty-state" v-else-if="!loading">
                        <div class="empty-icon">
                            <svg width="80" height="80" viewBox="0 0 80 80" fill="none">
                                <rect x="10" y="20" width="60" height="45" rx="6" fill="#F0F0F0"/>
                                <rect x="16" y="28" width="25" height="3" rx="1.5" fill="#D0D0D0"/>
                                <rect x="16" y="35" width="40" height="3" rx="1.5" fill="#D0D0D0"/>
                                <rect x="16" y="42" width="30" height="3" rx="1.5" fill="#D0D0D0"/>
                                <rect x="16" y="49" width="35" height="3" rx="1.5" fill="#D0D0D0"/>
                                <circle cx="40" cy="14" r="8" fill="#F0F0F0"/>
                                <circle cx="40" cy="14" r="4" fill="#D0D0D0"/>
                            </svg>
                        </div>
                        <div class="empty-text">暂无任何数据噢~</div>
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

// Tab 配置：key 与后端 CATEGORY_MAP 完全对齐（all/effect/active/learn/basic/spec）
const CATEGORY_TABS = [
    { key: 'all',    name: '掘友分总计' },
    { key: 'effect', name: '影响力' },
    { key: 'active', name: '活跃' },
    { key: 'learn',  name: '学习' },
    { key: 'basic',  name: '基础' },
    { key: 'spec',   name: '规范' }
]

// 6 维数值网格配置（总计变化 + 5 个分类）
const DIMENSION_GRID = [
    { key: 'total',  name: '总计变化' },
    { key: 'effect', name: '社区影响力' },
    { key: 'active', name: '社区活跃' },
    { key: 'learn',  name: '社区学习' },
    { key: 'basic',  name: '社区基础' },
    { key: 'spec',   name: '社区规范' }
]

export default {
    name: 'JScore',
    components: { HomeBar, UserCenterSidebar },
    data() {
        return {
            statDate: '',
            overviewData: null,
            chartData: null,
            dimensionList: [],
            categoryTabs: CATEGORY_TABS,
            currentCategory: 'all',
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
        // 从 URL 参数恢复 category
        const category = this.$route.query.category
        if (category && CATEGORY_TABS.find(t => t.key === category)) {
            this.currentCategory = category
        }
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
        formatScore(val) {
            if (val === null || val === undefined) return '0'
            const n = Number(val)
            if (Number.isNaN(n)) return '0'
            return Number.isInteger(n) ? String(n) : n.toFixed(1)
        },
        formatDate(dateStr) {
            if (!dateStr) {
                const d = new Date()
                const y = d.getFullYear()
                const m = String(d.getMonth() + 1).padStart(2, '0')
                const day = String(d.getDate()).padStart(2, '0')
                return y + '.' + m + '.' + day
            }
            return String(dateStr).replace(/-/g, '.')
        },
        formatDateTime(timeStr) {
            if (!timeStr) return ''
            // 形如 2026-09-04 03:38:31 → 2026-09-04 03:38:31
            return String(timeStr).slice(0, 19).replace('T', ' ')
        },
        onDimensionClick(dim) {
            if (dim.key === 'total') return
            if (CATEGORY_TABS.find(t => t.key === dim.key)) {
                this.switchCategory(dim.key)
            }
        },
        switchCategory(category) {
            if (this.currentCategory === category) return
            this.currentCategory = category
            this.timelineList = []
            this.nextCursor = ''
            this.hasMore = false
            this.$router.replace({ query: { ...this.$route.query, category } })
            this.loadDetail()
        },
        async loadOverview() {
            try {
                const res = await getJScoreOverview()
                if (res && res.code === 200 && res.data) {
                    this.overviewData = res.data
                    this.statDate = this.formatDate(res.data.stat_date || res.data.statDate)
                    this.chartData = res.data.chart || null
                    this.buildDimensionList(res.data.summary)
                    return
                }
                // 业务失败也走骨架
                this.buildDimensionList(null)
            } catch (e) {
                this.buildDimensionList(null)
            }
        },
        buildDimensionList(summary) {
            if (!summary) {
                this.dimensionList = DIMENSION_GRID.map(cfg => ({
                    key: cfg.key, name: cfg.name, today: 0, total: 0
                }))
                return
            }
            // summary key 与 DIMENSION_GRID 的 key 对齐（total/basic/active/learn/effect/spec）
            let totalToday = 0
            let totalTotal = 0
            DIMENSION_GRID.forEach(cfg => {
                if (cfg.key !== 'total' && summary[cfg.key]) {
                    totalToday += Number(summary[cfg.key].today || 0)
                    totalTotal += Number(summary[cfg.key].total || 0)
                }
            })
            this.dimensionList = DIMENSION_GRID.map(cfg => {
                if (cfg.key === 'total') {
                    return { key: cfg.key, name: cfg.name, today: totalToday, total: totalTotal }
                }
                const item = summary[cfg.key]
                return {
                    key: cfg.key, name: cfg.name,
                    today: item ? (item.today || 0) : 0,
                    total: item ? (item.total || 0) : 0
                }
            })
        },
        async loadDetail() {
            if (this.loading) return
            this.loading = true
            try {
                const params = { size: this.pageSize }
                if (this.currentCategory && this.currentCategory !== 'all') {
                    params.category = this.currentCategory
                }
                if (this.nextCursor) {
                    params.cursor = this.nextCursor
                }
                const res = await getJScoreDetail(params)
                if (res && res.code === 200 && res.data) {
                    const data = res.data
                    const list = data.list || []
                    if (this.nextCursor) {
                        this.timelineList = this.timelineList.concat(list)
                    } else {
                        this.timelineList = list
                    }
                    this.nextCursor = data.nextCursor || data.next_cursor || ''
                    this.hasMore = !!data.hasMore || !!data.has_more
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
        // ===== 雷达图几何 =====
        // 第 i 个角的角度（5 维，从正上方顺时针）
        axisAngle(i, total) {
            return -Math.PI / 2 + (Math.PI * 2 * i) / total
        },
        gridPolygon(level, total, radius) {
            const r = (radius / 5) * level
            const pts = []
            for (let i = 0; i < total; i++) {
                const a = this.axisAngle(i, total)
                pts.push((160 + r * Math.cos(a)).toFixed(2) + ',' + (140 + r * Math.sin(a)).toFixed(2))
            }
            return pts.join(' ')
        },
        axisEndX(i, total, radius) {
            const a = this.axisAngle(i, total)
            return (160 + radius * Math.cos(a)).toFixed(2)
        },
        axisEndY(i, total, radius) {
            const a = this.axisAngle(i, total)
            return (140 + radius * Math.sin(a)).toFixed(2)
        },
        dataPolygon(total, radius) {
            const values = (this.chartData && this.chartData.values) || []
            const maxVal = Math.max(1, ...values.map(v => Number(v) || 0))
            const pts = []
            for (let i = 0; i < total; i++) {
                const v = Number(values[i] || 0)
                const r = (v / maxVal) * radius
                const a = this.axisAngle(i, total)
                pts.push((160 + r * Math.cos(a)).toFixed(2) + ',' + (140 + r * Math.sin(a)).toFixed(2))
            }
            return pts.join(' ')
        },
        dataPointX(i, values, total, radius) {
            const maxVal = Math.max(1, ...values.map(v => Number(v) || 0))
            const v = Number(values[i] || 0)
            const r = (v / maxVal) * radius
            const a = this.axisAngle(i, total)
            return (160 + r * Math.cos(a)).toFixed(2)
        },
        dataPointY(i, values, total, radius) {
            const maxVal = Math.max(1, ...values.map(v => Number(v) || 0))
            const v = Number(values[i] || 0)
            const r = (v / maxVal) * radius
            const a = this.axisAngle(i, total)
            return (140 + r * Math.sin(a)).toFixed(2)
        },
        labelX(i, total, radius) {
            const a = this.axisAngle(i, total)
            return (160 + (radius + 24) * Math.cos(a)).toFixed(2)
        },
        labelY(i, total, radius) {
            const a = this.axisAngle(i, total)
            return (140 + (radius + 24) * Math.sin(a)).toFixed(2)
        }
    },
    watch: {
        '$route.query.category': function(newVal) {
            if (newVal && CATEGORY_TABS.find(t => t.key === newVal) && newVal !== this.currentCategory) {
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
    .back-arrow { font-size: 16px; color: #1A1A1A; font-weight: 600; }
    .back-text { font-size: 14px; color: #1A1A1A; }
}
.nav-title { font-size: 18px; font-weight: 600; color: #1A1A1A; }
.nav-right { cursor: pointer; user-select: none; }
.rules-link { font-size: 14px; color: #1A73E8; &:hover { color: #1557B0; } }

// ===== 概览区 =====
.overview-section {
    display: grid;
    grid-template-columns: minmax(360px, 480px) 1fr;
    gap: 16px;
    margin-bottom: 24px;
}

// 左：黑色雷达卡
.radar-card {
    background: #1F2126;
    border-radius: 16px;
    padding: 20px;
    color: #fff;
    display: flex;
    flex-direction: column;
}
.radar-header {
    display: flex;
    justify-content: space-between;
    align-items: center;
    margin-bottom: 12px;
    .radar-title { font-size: 15px; font-weight: 600; color: #FFFFFF; }
    .radar-period { font-size: 12px; color: #9CA3AF; }
}
.radar-body {
    flex: 1;
    display: flex;
    align-items: center;
    justify-content: center;
    min-height: 240px;
}
.radar-svg {
    width: 100%;
    max-width: 320px;
    height: auto;
}
.radar-empty {
    color: #6B7280;
    font-size: 14px;
    padding: 40px 0;
}
.radar-legend {
    display: flex;
    align-items: center;
    justify-content: center;
    gap: 6px;
    margin-top: 8px;
    .legend-dot {
        display: inline-block;
        width: 12px;
        height: 2px;
        background: #FFA500;
    }
    .legend-text { font-size: 12px; color: #D1D5DB; }
}

// 右：6 维数值网格
.dimension-grid {
    display: grid;
    grid-template-columns: 1fr 1fr;
    grid-template-rows: repeat(3, 1fr);
    gap: 12px;
    min-height: 280px;
}
.dim-grid-card {
    background: #F8F9FB;
    border-radius: 12px;
    padding: 14px 16px;
    cursor: pointer;
    transition: all 0.2s ease;
    display: flex;
    flex-direction: column;
    justify-content: space-between;
    &:hover {
        background: #EEF2FF;
        box-shadow: 0 2px 8px rgba(124,58,237,0.08);
    }
    &.dim-grid-total {
        background: linear-gradient(135deg, #EDE9FE 0%, #DDD6FE 100%);
        cursor: default;
        &:hover { background: linear-gradient(135deg, #EDE9FE 0%, #DDD6FE 100%); box-shadow: none; }
    }
}
.dim-grid-name {
    display: flex;
    align-items: center;
    justify-content: space-between;
    font-size: 13px;
    color: #6B7280;
    .dim-grid-arrow { color: #C0C4CC; font-size: 12px; }
}
.dim-grid-today {
    font-size: 28px;
    font-weight: 700;
    line-height: 1.1;
    margin-top: 8px;
    color: #1F2126;
    &.positive { color: #1A73E8; }
    &.negative { color: #F53F3F; }
    &.zero { color: #C0C4CC; }
}

// ===== 数据区 =====
.data-section {
    background: #fff;
    border-radius: 16px;
    padding: 20px;
    border: 1px solid #F0F2F5;
}
.section-header {
    margin-bottom: 14px;
    .section-title { font-size: 16px; font-weight: 600; color: #1A1A1A; }
}

// pill 形 Tabs
.pill-tabs {
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
    margin-bottom: 16px;
}
.pill-tab {
    padding: 6px 16px;
    font-size: 13px;
    color: #4B5563;
    background: #F3F4F6;
    border-radius: 16px;
    cursor: pointer;
    transition: all 0.2s ease;
    user-select: none;
    &:hover { color: #7C3AED; background: #EDE9FE; }
    &.active {
        color: #fff;
        font-weight: 500;
        background: linear-gradient(135deg, #7C3AED 0%, #5B21B6 100%);
    }
}

// 三列明细表
.detail-table {
    border: 1px solid #F0F2F5;
    border-radius: 12px;
    overflow: hidden;
}
.detail-head, .detail-row {
    display: grid;
    grid-template-columns: 1fr 160px 200px;
    align-items: center;
}
.detail-head {
    background: #FAFAFA;
    border-bottom: 1px solid #F0F2F5;
    .col {
        font-size: 13px;
        font-weight: 500;
        color: #6B7280;
        padding: 12px 20px;
    }
}
.detail-body {
    .detail-row {
        border-bottom: 1px solid #F5F5F5;
        transition: background 0.15s ease;
        &:last-child { border-bottom: none; }
        &:hover { background: #FAFAFA; }
    }
    .col {
        font-size: 14px;
        color: #1A1A1A;
        padding: 14px 20px;
    }
    .col-behavior {
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
    }
    .behavior-name {
        display: block;
        font-weight: 500;
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
    }
    .behavior-desc {
        display: block;
        margin-top: 2px;
        font-size: 12px;
        color: #8A8F8A;
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
    }
    .col-score {
        text-align: left;
        font-weight: 600;
        &.positive { color: #1A73E8; }
        &.negative { color: #F53F3F; }
    }
    .col-time { color: #6B7280; font-size: 13px; }
}

.load-more {
    text-align: center;
    padding: 14px 0 4px;
    .load-more-text {
        font-size: 13px;
        color: #7C3AED;
        cursor: pointer;
        &:hover { color: #5B21B6; }
        &.no-more { color: #C0C4CC; cursor: default; }
    }
}
.loading-spinner {
    text-align: center;
    padding: 20px 0;
    font-size: 13px;
    color: #9CA3AF;
}

.empty-state {
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    padding: 60px 0;
    .empty-icon { margin-bottom: 16px; opacity: 0.6; }
    .empty-text { font-size: 14px; color: #C0C4CC; }
}

// ===== Modal =====
.modal-overlay {
    position: fixed;
    inset: 0;
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
    .modal-title { font-size: 16px; font-weight: 600; color: #1A1A1A; }
    .modal-close { font-size: 24px; color: #9CA3AF; cursor: pointer; &:hover { color: #1A1A1A; } }
}
.modal-body { padding: 16px 20px 20px; }
.rules-section { margin-bottom: 16px; &:last-child { margin-bottom: 0; } }
.rules-title { font-size: 14px; font-weight: 600; color: #333; margin-bottom: 10px; }
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

// ===== 响应式 =====
@media screen and (max-width: 900px) {
    .jscore-content-wrapper { padding: 12px; flex-direction: column; }
    .main-area { padding: 16px; }
    .overview-section {
        grid-template-columns: 1fr;
    }
    .dimension-grid { grid-template-columns: 1fr 1fr; min-height: auto; }
    .detail-head, .detail-row { grid-template-columns: 1fr 100px 130px; }
    .detail-head .col, .detail-body .col { padding: 10px 12px; font-size: 12px; }
    .dim-grid-today { font-size: 22px; }
}
</style>