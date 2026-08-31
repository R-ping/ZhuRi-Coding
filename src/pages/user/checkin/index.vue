<template>
    <div class="checkin-page">
        <div class="art-top" v-if="!isDesktop"><HomeBar/></div>

        <div class="checkin-content">
            <!-- 左侧侧边栏 -->
            <UserCenterSidebar
                active-menu="checkin"
                :user-info="dashboard.userInfo"
                @menu-click="handleSidebarMenuClick"
            />

            <!-- 右侧主内容区 -->
            <div class="main-area">
                <!-- 签到进度弹框 -->
                <CheckinProgressModal
                    :visible="showCheckinModal"
                    :mode="checkinModalMode"
                    :earnedOre="checkinResult.earnedOre"
                    :milestoneProgress="checkinResult.milestoneProgress"
                    :nextSpecial="checkinResult.nextSpecial || dashboard.nextSpecialReward"
                    :isSigned="dashboard.checkinStats.todaySigned"
                    @close="closeCheckinModal"
                    @checkin-success="handleCheckinSuccess"
                    @go-lottery="handleGoLotteryFromModal"
                />

                <!-- 用户信息栏 -->
                <div class="user-info-bar">
                    <div class="user-info-section">
                        <span class="user-nickname">{{ dashboard.userInfo.nickname || '用户' }}</span>
                        <span class="user-level-badge">{{ dashboard.userInfo.level || 'ZR.1' }}</span>
                    </div>
                    <span class="info-separator">|</span>
                    <div class="user-info-section">
                        <span class="info-value">{{ dashboard.checkinStats.continuousDays || 0 }}</span>
                        <span class="info-label">连续签到天数</span>
                    </div>
                    <span class="info-separator">|</span>
                    <div class="user-info-section">
                        <span class="info-value">{{ dashboard.checkinStats.totalDays || 0 }}</span>
                        <span class="info-label">累计签到天数</span>
                    </div>
                    <span class="info-separator">|</span>
                    <div class="user-info-section ore-section">
                        <span class="info-value ore-value">{{ dashboard.checkinStats.oreBalance || 0 }}</span>
                        <span class="ore-icon">&#xf06d;</span>
                        <span class="ore-question" title="矿石说明">?</span>
                    </div>
                </div>

                <!-- 签到操作区 -->
                <div class="action-section">
                    <div class="action-left">
                        <div class="action-title">
                            <span class="ore-icon">&#xf06d;</span>
                            <span class="action-text">每日签到</span>
                        </div>
                        <div class="action-desc">连续签到领取矿石奖励</div>
                        <button
                            class="checkin-btn"
                            :class="{ 'checked': dashboard.checkinStats.todaySigned }"
                            @click="handleCheckinBtnClick"
                        >
                            <span class="btn-ore-icon">&#xf06d;</span>
                            {{ dashboard.checkinStats.todaySigned ? '今日已签到' : '立即签到' }}
                        </button>
                    </div>
                    <div class="action-stats">
                        <div class="stat-item">
                            <div class="stat-value">{{ dashboard.checkinStats.continuousDays || 0 }}</div>
                            <div class="stat-label">连续签到</div>
                        </div>
                        <div class="stat-item">
                            <div class="stat-value">{{ dashboard.checkinStats.totalDays || 0 }}</div>
                            <div class="stat-label">累计签到</div>
                        </div>
                        <div class="stat-item">
                            <div class="stat-value ore">{{ dashboard.checkinStats.oreBalance || 0 }}</div>
                            <div class="stat-label">矿石余额</div>
                        </div>
                        <div class="stat-item">
                            <div class="stat-value">{{ dashboard.patchCardCount || 0 }}</div>
                            <div class="stat-label">补签卡</div>
                        </div>
                    </div>
                </div>

                <!-- 日历区域 -->
                <div class="calendar-section">
                    <div class="calendar-header">
                        <span class="calendar-title">签到日历</span>
                        <div class="calendar-nav">
                            <span class="nav-arrow" :class="{ disabled: isPrevDisabled }" @click="prevMonthCalendar">&#xf104;</span>
                            <span class="nav-arrow" :class="{ disabled: isNextDisabled }" @click="nextMonthCalendar">&#xf105;</span>
                        </div>
                        <span class="today-btn" @click="goToday">今天</span>
                    </div>
                    <!-- 当前展示月份 -->
                    <div class="calendar-month-block">
                        <div class="month-label">{{ displayYear }}年{{ displayMonth }}月</div>
                        <div class="calendar-grid">
                            <div class="calendar-header-row">
                                <div
                                    v-for="day in weekDays"
                                    :key="'cal-h-' + day"
                                    class="calendar-header-cell"
                                >{{ day }}</div>
                            </div>
                            <div class="calendar-body">
                                <div
                                    v-for="(cell, index) in displayCells"
                                    :key="'cal-' + index"
                                    class="calendar-cell"
                                    :class="cellClass(cell)"
                                    @click="handleCellClick(cell)"
                                >
                                    <template v-if="!cell.isEmpty">
                                        <span class="cell-date">{{ cell.day }}</span>
                                        <span v-if="cell.reward" class="cell-reward">
                                            +{{ cell.reward }}
                                        </span>
                                        <span v-if="cell.status === 'repaired'" class="cell-patched-label">补</span>
                                        <span v-if="cell.isSpecialDay" class="cell-special-label">奖</span>
                                        <span v-if="cell.status === 'unsigned' && cell.canExtra" class="cell-unsigned-label">待补签</span>
                                    </template>
                                </div>
                            </div>
                        </div>
                    </div>
                </div>

                <!-- Bottom Sections -->
                <div class="bottom-sections">
                    <div class="bottom-section section-notice">
                        <div class="section-title">掘金公告</div>
                        <div class="notice-list">
                            <div class="notice-item">
                                <span class="notice-name">公益计划</span>
                                <span class="notice-link">了解详情 &#xf105;</span>
                            </div>
                            <div class="notice-item">
                                <span class="notice-name">游戏入口调整</span>
                                <span class="notice-link">了解详情 &#xf105;</span>
                            </div>
                            <div class="notice-item">
                                <span class="notice-link">捐赠详情 &#xf105;</span>
                            </div>
                        </div>
                    </div>
                    <div class="bottom-section section-exchange">
                        <div class="section-title">点石成金</div>
                        <div class="exchange-content">
                            <div class="exchange-desc">用矿石兑换公益基金</div>
                            <div class="exchange-btn">去兑换</div>
                        </div>
                    </div>
                </div>

                <!-- My Tasks Section -->
                <div class="tasks-section">
                    <div class="tasks-header">
                        <span class="tasks-title">我的任务</span>
                        <span class="tasks-question-icon" title="任务说明">?</span>
                    </div>
                    <div class="tasks-list">
                        <div
                            v-for="task in dashboard.tasks"
                            :key="task.id"
                            class="task-item"
                        >
                            <div class="task-info">
                                <div class="task-name">{{ task.name }}</div>
                                <div class="task-desc">{{ task.description }}</div>
                            </div>
                            <div class="task-reward">
                                +{{ task.reward }} 矿石
                            </div>
                            <div
                                class="task-status"
                                :class="{ completed: task.status === 'completed' }"
                            >
                                {{ task.status === 'completed' ? '已完成' : '去完成' }}
                            </div>
                        </div>
                    </div>
                </div>

                <!-- Footer -->
                <div class="checkin-footer">
                    用户协议 · 法律声明 &copy;{{ new Date().getFullYear() }} 稀土掘金
                </div>
            </div>
        </div>
    </div>
</template>

<script>
import HomeBar from '@/components/bars/home_bar'
import CheckinProgressModal from '@/components/checkin/CheckinProgressModal.vue'
import UserCenterSidebar from '@/components/user/UserCenterSidebar.vue'
import Utils from '@/utils/env'
import { getSignStatus, doSignCheckin, doSignExtra } from '@/apis/checkin'
import { toast } from '@/utils/toast'

export default {
    name: 'UserCheckin',
    components: { HomeBar, CheckinProgressModal, UserCenterSidebar },
    data() {
        return {
            weekDays: ['日', '一', '二', '三', '四', '五', '六'],
            displayYear: new Date().getFullYear(),
            displayMonth: new Date().getMonth() + 1,
            todayYear: new Date().getFullYear(),
            todayMonth: new Date().getMonth() + 1,
            today: new Date().getDate(),
            showCheckinModal: false,
            checkinModalMode: 'entry',
            checkinResult: {
                earnedOre: 0,
                milestoneProgress: { current: 0, total: 30, percent: 0, specialDays: [] },
                nextSpecial: null
            },
            dashboard: {
                userInfo: { nickname: '', level: '', userId: 0 },
                checkinStats: {
                    continuousDays: 0,
                    totalDays: 0,
                    oreBalance: 0,
                    todaySigned: false,
                    pendingReward: 0
                },
                patchCardCount: 0,
                currentPeriodDay: 0,
                nextSpecialReward: null,
                milestoneProgress: { current: 0, total: 30, percent: 0, specialDays: [] },
                calendarMonths: [],
                tasks: []
            }
        }
    },
    computed: {
        isDesktop() {
            return Utils.isDesktop()
        },
        displayCells() {
            return this.buildMonthCells(this.displayYear, this.displayMonth)
        },
        isPrevDisabled() {
            // 只能显示当前月和前一个月，不能更早
            const prevMonth = this.displayMonth === 1 ? 12 : this.displayMonth - 1
            const prevYear = this.displayMonth === 1 ? this.displayYear - 1 : this.displayYear
            const todayPrevMonth = this.todayMonth === 1 ? 12 : this.todayMonth - 1
            const todayPrevYear = this.todayMonth === 1 ? this.todayYear - 1 : this.todayYear
            return prevYear < todayPrevYear || (prevYear === todayPrevYear && prevMonth < todayPrevMonth)
                || (prevYear === todayPrevYear && prevMonth === todayPrevMonth)
        },
        isNextDisabled() {
            // 不能超过当前月
            return this.displayYear >= this.todayYear && this.displayMonth >= this.todayMonth
        }
    },
    mounted() {
        this.loadSignStatus()
    },
    methods: {
        goBack() {
            this.$router.back()
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
        buildMonthCells(year, month) {
            const cells = []
            const firstDay = new Date(year, month - 1, 1).getDay()
            const totalDays = new Date(year, month, 0).getDate()

            for (let i = 0; i < firstDay; i++) {
                cells.push({ isEmpty: true })
            }

            // 从 calendarMonths 中查找对应月份的数据
            const monthData = (this.dashboard.calendarMonths || []).find(
                m => m.year === year && m.month === month
            )
            const daysData = monthData ? monthData.days : []

            for (let d = 1; d <= totalDays; d++) {
                const dateStr = this.formatDateStrWithYearMonth(year, month, d)
                const dayInfo = daysData.find(item => item.date === dateStr)

                let status = 'miss'
                let reward = null
                let isSpecialDay = false
                let canExtra = false

                if (dayInfo) {
                    // 状态映射：后端返回的6种状态
                    const statusMap = {
                        'signed': 'signed',
                        'extra_signed': 'repaired',
                        'unsigned': 'unsigned',
                        'expired': 'expired',
                        'future': 'future'
                    }
                    status = statusMap[dayInfo.status] || 'miss'
                    reward = dayInfo.oreAmount || null
                    isSpecialDay = dayInfo.isSpecialDay || false
                    canExtra = dayInfo.canExtra || false
                }

                // 今日特殊处理
                if (dateStr === this.formatDateStrWithYearMonth(this.todayYear, this.todayMonth, this.today)) {
                    if (status === 'unsigned') {
                        status = 'today'
                    }
                }

                cells.push({
                    isEmpty: false,
                    day: d,
                    date: dateStr,
                    status: status,
                    reward: reward,
                    isSpecialDay: isSpecialDay,
                    canExtra: canExtra
                })
            }

            return cells
        },
        cellClass(cell) {
            if (cell.isEmpty) return 'is-empty'
            const map = {
                signed: 'is-signed',
                repaired: 'is-repaired',
                unsigned: 'is-unsigned',
                expired: 'is-expired',
                today: 'is-today',
                future: 'is-future'
            }
            return map[cell.status] || ''
        },
        async loadSignStatus() {
            try {
                const res = await getSignStatus()
                if (res && res.code === 200 && res.data) {
                    const data = res.data
                    this.dashboard.userInfo = data.userInfo || { nickname: '用户', level: 'ZR.1' }
                    this.dashboard.checkinStats = {
                        continuousDays: data.continuousDays || 0,
                        totalDays: data.totalSignDays || 0,
                        oreBalance: data.totalOre || 0,
                        todaySigned: data.todaySigned || false
                    }
                    this.dashboard.patchCardCount = data.extraCards || 0
                    this.dashboard.currentPeriodDay = data.continuousDays ? (data.continuousDays - 1) % 30 + 1 : 0
                    this.dashboard.milestoneProgress = data.milestoneProgress || { current: 0, total: 30, percent: 0, specialDays: [] }
                    this.dashboard.nextSpecialReward = data.nextSpecial || null

                    // 处理日历数据（新格式：calendarMonths数组）
                    if (data.calendarMonths) {
                        this.dashboard.calendarMonths = data.calendarMonths
                    } else {
                        this.dashboard.calendarMonths = []
                    }
                    this.dashboard.tasks = data.tasks || []
                }
            } catch (error) {
                toast('加载数据失败，请稍后重试', 2)
            }
        },
        async handleCellClick(cell) {
            if (cell.isEmpty) return
            if (cell.status === 'future' || cell.status === 'expired') return

            if (cell.status === 'signed' || cell.status === 'repaired') {
                toast('该日期已签到', 2)
                return
            }

            if (cell.status === 'unsigned') {
                // 今日不可补签，弹进度框
                if (cell.date === this.formatDateStrWithYearMonth(this.todayYear, this.todayMonth, this.today)) {
                    this.openCheckinModal()
                    return
                }
                if (!cell.canExtra) {
                    toast('该日期不可补签', 2)
                    return
                }
                if (this.dashboard.patchCardCount <= 0) {
                    toast('补签卡不足', 2)
                    return
                }
                try {
                    const res = await doSignExtra(cell.date)
                    if (res && res.code === 200) {
                        const data = res.data || {}
                        const extraOre = data.extraOre || 0
                        let msg = '补签成功'
                        if (extraOre > 0) {
                            msg += '，经计算补发放共 ' + extraOre + ' 矿石'
                        } else {
                            msg += '！'
                        }
                        toast(msg, 3)
                        await this.loadSignStatus()
                    } else {
                        toast(res && res.message ? res.message : '补签失败', 2)
                    }
                } catch (error) {
                    toast('补签失败，请稍后重试', 2)
                }
                return
            }

            if (cell.status === 'today') {
                this.openCheckinModal()
            }
        },
        openCheckinModal() {
            this.checkinModalMode = 'entry'
            this.showCheckinModal = true
        },
        closeCheckinModal() {
            this.showCheckinModal = false
        },
        async handleCheckinSuccess(data) {
            this.checkinResult = {
                earnedOre: data.awardOre || 0,
                milestoneProgress: data.milestoneProgress || { current: 0, total: 30, percent: 0, specialDays: [] },
                nextSpecial: data.nextSpecial || null
            }
            this.checkinModalMode = 'success'
            this.showCheckinModal = true
            await this.loadSignStatus()
        },
        handleGoLotteryFromModal() {
            this.showCheckinModal = false
            const routeMap = {
                checkin: '/user/center/checkin',
                growth: '/user/center/growth',
                lottery: '/user/center/lottery',
                welfare: '/user/center/welfare'
            }
            const path = routeMap.lottery
            if (path && this.$route.path !== path) {
                this.$router.push(path)
            }
        },
        async handleCheckinBtnClick() {
            if (this.dashboard.checkinStats.todaySigned) {
                this.openCheckinModal()
                return
            }
            try {
                const res = await doSignCheckin()
                if (res && res.code === 200 && res.data) {
                    await this.handleCheckinSuccess(res.data)
                } else {
                    toast(res && res.message ? res.message : '签到失败', 2)
                }
            } catch (error) {
                toast('签到失败，请稍后重试', 2)
            }
        },
        goToday() {
            this.displayYear = this.todayYear
            this.displayMonth = this.todayMonth
        },
        prevMonthCalendar() {
            if (this.isPrevDisabled) return
            if (this.displayMonth === 1) {
                this.displayYear--
                this.displayMonth = 12
            } else {
                this.displayMonth--
            }
        },
        nextMonthCalendar() {
            if (this.isNextDisabled) return
            if (this.displayMonth === 12) {
                this.displayYear++
                this.displayMonth = 1
            } else {
                this.displayMonth++
            }
        },
        formatDateStrWithYearMonth(year, month, day) {
            const y = year
            const m = String(month).padStart(2, '0')
            const d = String(day).padStart(2, '0')
            return y + '-' + m + '-' + d
        }
    }
}
</script>

<style lang="less" scoped>
@import '../../../styles/common';

.checkin-page {
    min-height: 100vh;
    background: #f5f7fa;
}

.checkin-content {
    max-width: 1280px;
    margin: 0 auto;
    padding: 0 24px 24px;
    display: flex;
    gap: 16px;
}

.main-area {
    flex: 1;
    min-width: 0;
}

// User Info Bar
.user-info-bar {
    display: flex;
    align-items: center;
    justify-content: center;
    background: #fff;
    border-radius: 0 0 12px 12px;
    padding: 16px 24px;
    margin-bottom: 16px;
    box-shadow: 0 2px 8px rgba(0, 0, 0, 0.06);
    flex-wrap: wrap;
    gap: 12px;
}

.user-info-section {
    display: flex;
    align-items: center;
    gap: 6px;
}

.user-nickname {
    font-size: 15px;
    color: #1a1a1a;
    font-weight: 500;
}

.user-level-badge {
    display: inline-block;
    padding: 2px 8px;
    border-radius: 10px;
    background: linear-gradient(135deg, #fa8c16, #ffc069);
    color: #fff;
    font-size: 12px;
    font-weight: 600;
    line-height: 1.5;
}

.info-separator {
    color: #e8e8e8;
    font-size: 14px;
}

.info-value {
    font-size: 18px;
    font-weight: 700;
    color: #fa8c16;
}

.info-label {
    font-size: 13px;
    color: #999;
}

.ore-section {
    .ore-value {
        font-size: 18px;
    }

    .ore-icon {
        font-family: fontawesome;
        font-size: 16px;
        color: #fa8c16;
    }

    .ore-question {
        font-size: 14px;
        color: #ccc;
        cursor: pointer;
        margin-left: 2px;
    }
}

// Action Section
.action-section {
    background: #fff;
    border-radius: 16px;
    padding: 32px;
    box-shadow: 0 2px 12px rgba(0, 0, 0, 0.06);
    margin-bottom: 16px;
    display: flex;
    gap: 48px;
    align-items: center;
}

.action-left {
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: 12px;
}

.action-title {
    display: flex;
    align-items: center;
    gap: 8px;
    font-size: 18px;
    font-weight: 600;
    color: #1a1a1a;

    .ore-icon {
        font-family: fontawesome;
        font-size: 20px;
        color: #fa8c16;
    }
}

.action-text {
    color: #1a1a1a;
}

.action-desc {
    font-size: 13px;
    color: #999;
    margin-bottom: 4px;
}

.checkin-btn {
    width: 180px;
    height: 52px;
    background: linear-gradient(135deg, #fa8c16, #ffc069);
    color: #fff;
    border: none;
    border-radius: 26px;
    font-size: 16px;
    font-weight: 600;
    cursor: pointer;
    display: flex;
    align-items: center;
    justify-content: center;
    gap: 8px;
    transition: all 0.2s;

    &:hover {
        opacity: 0.9;
        transform: translateY(-1px);
    }

    &.checked {
        background: linear-gradient(135deg, #52c41a, #73d13d);
        cursor: default;

        &:hover {
            transform: none;
        }
    }

    .btn-ore-icon {
        font-family: fontawesome;
        font-size: 18px;
    }
}

.action-stats {
    display: flex;
    gap: 32px;
    flex: 1;
    justify-content: center;
}

.stat-item {
    text-align: center;
}

.stat-value {
    font-size: 24px;
    font-weight: 700;
    color: #fa8c16;
}

.stat-value.ore {
    color: #fa8c16;
}

.stat-label {
    font-size: 13px;
    color: #999;
    margin-top: 4px;
}

// Calendar Section
.calendar-section {
    background: #fff;
    border-radius: 16px;
    padding: 24px;
    box-shadow: 0 2px 12px rgba(0, 0, 0, 0.06);
    margin-bottom: 16px;
}

.calendar-header {
    display: flex;
    justify-content: space-between;
    align-items: center;
    margin-bottom: 16px;
    padding-bottom: 12px;
    border-bottom: 1px solid #f0f2f5;
}

.calendar-title {
    font-size: 16px;
    font-weight: 600;
    color: #1a1a1a;
}

.calendar-nav {
    display: flex;
    gap: 12px;
    align-items: center;
}

.nav-arrow {
    font-family: fontawesome;
    font-size: 18px;
    color: #666;
    cursor: pointer;
    padding: 4px 8px;
    border-radius: 4px;
    transition: all 0.2s;

    &:hover:not(.disabled) {
        background: #f0f2f5;
        color: #1a1a1a;
    }

    &.disabled {
        color: #d9d9d9;
        cursor: not-allowed;
    }
}

.today-btn {
    padding: 6px 16px;
    border: 1px solid #1e80ff;
    border-radius: 16px;
    color: #1e80ff;
    font-size: 13px;
    cursor: pointer;
    transition: all 0.2s;

    &:hover {
        background: #e6f4ff;
    }
}

.calendar-month-block {
    margin-bottom: 20px;

    &:last-child {
        margin-bottom: 0;
    }
}

.month-label {
    font-size: 14px;
    font-weight: 500;
    color: #666;
    margin-bottom: 12px;
}

.calendar-grid {
    width: 100%;
}

.calendar-header-row {
    display: grid;
    grid-template-columns: repeat(7, 1fr);
    gap: 8px;
    padding-bottom: 8px;
    margin-bottom: 4px;
    border-bottom: 1px solid #f0f2f5;
}

.calendar-header-cell {
    text-align: center;
    font-size: 13px;
    color: #999;
    height: 28px;
    line-height: 28px;
}

.calendar-body {
    display: grid;
    grid-template-columns: repeat(7, 1fr);
    gap: 8px;
}

.calendar-cell {
    aspect-ratio: 1;
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    border-radius: 8px;
    font-size: 14px;
    cursor: pointer;
    transition: all 0.2s;
    position: relative;
    background: #f5f7fa;

    &.is-signed {
        background: linear-gradient(135deg, #fa8c16, #ffc069);
        color: #fff;

        .cell-date {
            color: #fff;
            font-weight: 600;
        }

        .cell-reward {
            color: rgba(255, 255, 255, 0.9);
        }
    }

    &.is-today {
        background: #fff;
        border: 2px solid #f5222d;
        color: #f5222d;

        .cell-date {
            color: #f5222d;
            font-weight: 700;
        }

        &::after {
            content: '今日';
            position: absolute;
            bottom: 2px;
            font-size: 9px;
            color: #f5222d;
            font-weight: 500;
        }
    }

    &.is-missed {
        background: #f5f5f5;
        color: #bbb;

        .cell-date {
            color: #bbb;
        }

        &:hover {
            background: #fff7e6;
            color: #fa8c16;

            .cell-date {
                color: #fa8c16;
            }
        }
    }

    &.is-unsigned {
        background: #fafafa;
        color: #999;
        cursor: pointer;

        .cell-date {
            color: #999;
        }

        &:hover {
            background: #fff7e6;
            color: #fa8c16;

            .cell-date {
                color: #fa8c16;
            }
        }
    }

    &.is-expired {
        background: #f0f0f0;
        color: #ccc;
        cursor: not-allowed;

        .cell-date {
            color: #ccc;
        }
    }

    &.is-future {
        background: #fafafa;
        color: #d9d9d9;
        cursor: not-allowed;

        .cell-date {
            color: #d9d9d9;
        }
    }

    &.is-repaired {
        background: #e6fffb;
        border: 1px dashed #52c41a;
        color: #52c41a;

        .cell-date {
            color: #52c41a;
            font-weight: 600;
        }

        .cell-reward {
            color: #52c41a;
        }

        .cell-patched-label {
            position: absolute;
            top: 2px;
            right: 2px;
            font-size: 10px;
            color: #52c41a;
            background: #fff;
            padding: 0 3px;
            border-radius: 2px;
            font-weight: 600;
        }
    }

    &.is-empty {
        background: transparent;
        cursor: default;
    }

    &:hover:not(.is-empty):not(.is-future):not(.is-signed):not(.is-today) {
        opacity: 0.85;
    }

    .cell-special-label {
        position: absolute;
        top: 2px;
        left: 2px;
        font-size: 9px;
        color: #fa8c16;
        background: #fff7e6;
        padding: 0 3px;
        border-radius: 2px;
        font-weight: 600;
    }

    .cell-unsigned-label {
        font-size: 10px;
        color: #999;
        margin-top: 2px;
    }
}

.cell-date {
    font-size: 15px;
    color: #1a1a1a;
    line-height: 1;
}

.cell-reward {
    font-size: 11px;
    color: #52c41a;
    margin-top: 2px;
    font-weight: 500;
}

// Bottom Sections
.bottom-sections {
    display: grid;
    grid-template-columns: 1fr 1fr;
    gap: 16px;
    margin-bottom: 16px;
}

.bottom-section {
    background: #fff;
    border-radius: 12px;
    padding: 20px 24px;
    box-shadow: 0 2px 8px rgba(0, 0, 0, 0.06);
}

.section-title {
    font-size: 16px;
    font-weight: 600;
    color: #1a1a1a;
    margin-bottom: 16px;
}

.notice-list {
    display: flex;
    flex-direction: column;
    gap: 12px;
}

.notice-item {
    display: flex;
    justify-content: space-between;
    align-items: center;
    padding: 8px 0;
    border-bottom: 1px solid #f2f3f5;

    &:last-child {
        border-bottom: none;
    }
}

.notice-name {
    font-size: 14px;
    color: #1a1a1a;
}

.notice-link {
    font-size: 13px;
    color: #1e80ff;
    cursor: pointer;
    font-family: fontawesome;

    &:hover {
        opacity: 0.8;
    }
}

.exchange-content {
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: 16px;
    padding: 20px 0;
}

.exchange-desc {
    font-size: 14px;
    color: #666;
}

.exchange-btn {
    padding: 10px 32px;
    background: linear-gradient(135deg, #fa8c16, #ffc069);
    color: #fff;
    border-radius: 24px;
    font-size: 14px;
    font-weight: 500;
    cursor: pointer;
    transition: opacity 0.2s;

    &:hover {
        opacity: 0.85;
    }
}

// Tasks Section
.tasks-section {
    background: #fff;
    border-radius: 12px;
    padding: 24px;
    box-shadow: 0 2px 8px rgba(0, 0, 0, 0.06);
    margin-bottom: 16px;
}

.tasks-header {
    display: flex;
    align-items: center;
    margin-bottom: 16px;
}

.tasks-title {
    font-size: 16px;
    font-weight: 600;
    color: #1a1a1a;
}

.tasks-question-icon {
    font-family: fontawesome;
    font-size: 16px;
    color: #ccc;
    margin-left: 8px;
    cursor: pointer;
}

.tasks-list {
    display: flex;
    flex-direction: column;
}

.task-item {
    display: flex;
    align-items: center;
    padding: 14px 0;
    border-bottom: 1px solid #f2f3f5;
    gap: 12px;

    &:last-child {
        border-bottom: none;
    }
}

.task-info {
    flex: 1;
}

.task-name {
    font-size: 15px;
    color: #1a1a1a;
    font-weight: 500;
    margin-bottom: 4px;
}

.task-desc {
    font-size: 13px;
    color: #999;
}

.task-reward {
    font-size: 13px;
    color: #52c41a;
    font-weight: 500;
    white-space: nowrap;
}

.task-status {
    padding: 6px 16px;
    border-radius: 16px;
    font-size: 13px;
    white-space: nowrap;
    border: 1px solid #1e80ff;
    color: #1e80ff;
    cursor: pointer;
    transition: all 0.2s;

    &:hover {
        background: rgba(30, 128, 255, 0.1);
    }

    &.completed {
        border-color: #ccc;
        color: #999;
        cursor: default;
        background: #f5f5f5;

        &:hover {
            background: #f5f5f5;
        }
    }
}

// Footer
.checkin-footer {
    text-align: center;
    font-size: 12px;
    color: #ccc;
    padding: 16px 0;
}

// Responsive
@media screen and (max-width: 768px) {
    .checkin-content {
        padding: 0 12px 12px;
        flex-direction: column;
    }

    .user-info-bar {
        padding: 12px 16px;
        gap: 8px;
    }

    .user-info-section {
        gap: 4px;
    }

    .info-value {
        font-size: 16px;
    }

    .info-label {
        font-size: 12px;
    }

    .action-section {
        flex-direction: column;
        gap: 24px;
        padding: 24px 16px;
    }

    .action-stats {
        gap: 16px;
        flex-wrap: wrap;
    }

    .stat-value {
        font-size: 20px;
    }

    .calendar-cell {
        border-radius: 6px;
    }

    .cell-date {
        font-size: 13px;
    }

    .cell-reward {
        font-size: 10px;
    }

    .bottom-sections {
        grid-template-columns: 1fr;
    }

    .task-item {
        flex-wrap: wrap;
        padding: 12px 0;
    }
}
</style>