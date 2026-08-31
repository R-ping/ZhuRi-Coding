<template>
    <div class="lottery-page">
        <div class="lottery-content">
            <!-- Left Panel -->
            <UserCenterSidebar
                active-menu="lottery"
                @menu-click="handleSidebarMenuClick"
            />

            <!-- Main Area -->
            <div class="main-area">
                <!-- Header Banner -->
                <div class="header-banner">
                    <div class="banner-decoration">
                        <div class="deco-star">&#xf005;</div>
                        <div class="deco-box">&#xf0a3;</div>
                    </div>
                    <div class="banner-title">掘金福利限量抽</div>
                    <div class="banner-subtitle">惊喜大奖等你来拿</div>
                    <div class="banner-coin">
                        <span class="coin-icon">&#xf06d;</span>
                        <span class="coin-count">{{ dashboard.oreBalance || 0 }}</span>
                    </div>
                    <div class="rules-link" @click="showRulesModal = true">抽奖规则 <span class="arrow">&#xf043;</span></div>
                </div>

                <!-- Content Layout -->
                <div class="content-layout">
                    <!-- Lottery Wheel Area -->
                    <div class="wheel-area">
                        <div class="wheel-section-title">
                            <span class="title-icon">&#xf06d;</span>
                            幸运大转盘
                        </div>

                        <div class="wheel-container">
                            <div class="wheel-board">
                                <div
                                    v-for="(item, index) in wheelPrizes"
                                    :key="item.id || index"
                                    class="wheel-cell"
                                    :class="{
                                        'is-locked': item.isLocked,
                                        'is-highlight': highlightIndex === index,
                                        'is-physical': item.type === 'physical'
                                    }"
                                >
                                    <div class="cell-lock" v-if="item.isLocked">&#xf023;</div>
                                    <div class="cell-icon">{{ item.icon || getDefaultIcon(item.type) }}</div>
                                    <div class="cell-name">{{ item.name }}</div>
                                    <div class="cell-amount" v-if="item.oreAmount">{{ item.oreAmount }}</div>
                                    <div class="cell-lock-hint" v-if="item.isLocked">{{ item.lockHint }}</div>
                                </div>
                                <div class="wheel-center" :class="{ 'is-drawing': isDrawing }" @click="handleCenterDraw">
                                    <div class="center-icon" v-if="!isDrawing">&#xf058;</div>
                                    <div class="center-icon spinning" v-else>&#xf021;</div>
                                    <div class="center-text">{{ isDrawing ? '抽奖中...' : drawButtonText }}</div>
                                </div>
                            </div>

                            <!-- Draw Buttons -->
                            <div class="draw-buttons">
                                <button
                                    class="draw-btn single"
                                    :disabled="isDrawing || !canDrawSingle"
                                    @click="handleDraw('single')"
                                >
                                    <span class="draw-btn-title">单抽</span>
                                    <span class="draw-btn-cost">
                                        <span class="ore-icon">&#xf06d;</span>{{ 200 }}
                                    </span>
                                </button>
                                <button
                                    class="draw-btn ten"
                                    :disabled="isDrawing || !canDrawTen"
                                    @click="handleDraw('ten')"
                                >
                                    <span class="draw-btn-title">十连抽</span>
                                    <span class="draw-btn-cost">
                                        <span class="ore-icon">&#xf06d;</span>{{ 2000 }}
                                    </span>
                                </button>
                            </div>

                            <!-- Lucky Value Bar -->
                            <div class="lucky-bar">
                                <div class="lucky-label">
                                    <span class="lucky-icon">&#xf06d;</span>
                                    幸运值
                                </div>
                                <div class="lucky-progress">
                                    <div
                                        class="lucky-progress-fill"
                                        :style="{ width: luckyPercent + '%' }"
                                    ></div>
                                </div>
                                <div class="lucky-value">
                                    {{ currentLuckyValue }} / 6000
                                </div>
                            </div>
                        </div>
                    </div>

                    <!-- Broadcast Area -->
                    <div class="broadcast-area">
                        <div class="broadcast-section-title">
                            <span class="title-icon">&#xf0a1;</span>
                            围观大奖
                        </div>
                        <div class="broadcast-list">
                            <div
                                v-for="(item, index) in displays"
                                :key="index"
                                class="broadcast-card"
                            >
                                <div class="broadcast-user-info">
                                    <img v-if="item.avatar" class="broadcast-avatar" :src="item.avatar" />
                                    <span v-else class="broadcast-avatar-default">&#xf007;</span>
                                    <span class="broadcast-username">{{ item.userName }}</span>
                                </div>
                                <div class="broadcast-prize-info">
                                    <span class="broadcast-action">抽中了</span>
                                    <span class="broadcast-prize">{{ item.prizeName }}</span>
                                </div>
                            </div>
                        </div>
                        <div class="broadcast-empty" v-if="displays.length === 0">
                            <span class="empty-icon">&#xf06d;</span>
                            <span class="empty-text">暂无中奖播报</span>
                        </div>

                        <!-- Pagination -->
                        <div class="broadcast-pagination" v-if="totalPages > 1">
                            <span class="page-arrow" :class="{ disabled: currentPage <= 1 }" @click="prevPage">&#xf104;</span>
                            <span class="page-num" v-for="p in totalPages" :key="p" :class="{ active: p === currentPage }" @click="goPage(p)">{{ p }}</span>
                            <span class="page-arrow" :class="{ disabled: currentPage >= totalPages }" @click="nextPage">&#xf105;</span>
                        </div>
                    </div>
                </div>
            </div>
            </div>

        <!-- Draw Result Modal -->
        <LotteryResultModal
            :visible="showResultModal"
            :results="drawResults"
            :isFree="drawUsedFree"
            :canDraw="canDrawSingle"
            :drawCost="200"
            @close="showResultModal = false"
            @draw-again="handleDraw('single')"
            @go-harvest="handleGoHarvest"
        />

        <!-- Rules Modal -->
        <LotteryRulesModal
            :visible="showRulesModal"
            @close="showRulesModal = false"
        />
    </div>
</template>

<script>
import LotteryResultModal from '@/components/lottery/LotteryResultModal.vue'
import LotteryRulesModal from '@/components/lottery/LotteryRulesModal.vue'
import UserCenterSidebar from '@/components/user/UserCenterSidebar.vue'
import store from '@/stores/store'
import { getDashboard, doDraw, getBroadcast } from '@/apis/lottery'
import { toast } from '@/utils/toast'

const PRIZE_COLORS = ['#fa8c16', '#1e80ff', '#52c41a', '#eb2f96', '#722ed1', '#13c2c2', '#f5222d', '#faad14', '#2f54eb']

export default {
    name: 'UserLottery',
    components: { LotteryResultModal, LotteryRulesModal, UserCenterSidebar },
    data() {
        return {
            currentYear: new Date().getFullYear(),
            isDrawing: false,
            showResultModal: false,
            showRulesModal: false,
            drawResults: [],
            drawUsedFree: false,
            highlightIndex: -1,
            highlightTimer: null,
            broadcastTimer: null,
            currentPage: 1,
            pageSize: 5,
            dashboard: {
                oreBalance: 0,
                freeDrawAvailable: false,
                freeDrawUsed: false,
                todayDrawCount: 0,
                luckyValue: 0,
                luckyThreshold: 6000,
                prizePool: [],
                broadcastMessages: [],
                userInfo: { nickname: '', level: '' }
            }
        }
    },
    computed: {
        userInfo() {
            const info = store.state.userInfo || {}
            return {
                nickName: info.nickName || '用户',
                level: info.level || 'JY.1',
                avatar: info.avatar || ''
            }
        },
        wheelPrizes() {
            const pool = this.dashboard.prizePool || []
            if (pool.length === 0) {
                return this.getDefaultPrizes()
            }
            // Map to 9 positions (3x3 grid), skip center (index 4)
            const positions = [0, 1, 2, 5, 8, 7, 6, 3]
            const items = []
            for (let i = 0; i < 8; i++) {
                const prize = pool[i % pool.length] || {}
                const pos = positions[i]
                const col = pos % 3
                const row = Math.floor(pos / 3)
                items.push({
                    ...prize,
                    _position: pos,
                    _col: col,
                    _row: row,
                    id: prize.id || ('default-' + i)
                })
            }
            return items
        },
        displayPrizes() {
            const pool = this.dashboard.prizePool || []
            if (pool.length === 0) return []
            const positions = [0, 1, 2, 5, 8, 7, 6, 3]
            return this.wheelPrizes
        },
        displays() {
            const broadcasts = this.dashboard.broadcastMessages || []
            const start = (this.currentPage - 1) * this.pageSize
            return broadcasts.slice(start, start + this.pageSize).map(b => ({
                userName: b.user || '用户',
                prizeName: b.prize || '神秘奖品',
                avatar: ''
            }))
        },
        totalPages() {
            const count = (this.dashboard.broadcastMessages || []).length
            return Math.ceil(count / this.pageSize)
        },
        currentLuckyValue() {
            return this.dashboard.luckyValue || 0
        },
        luckyPercent() {
            return Math.min(100, Math.round((this.currentLuckyValue / 6000) * 100))
        },
        freeCount() {
            return this.dashboard.freeDrawAvailable && !this.dashboard.freeDrawUsed ? 1 : 0
        },
        remainCount() {
            return this.freeCount + Math.floor(this.dashboard.oreBalance / 200)
        },
        canDrawSingle() {
            return this.freeCount > 0 || this.dashboard.oreBalance >= 200
        },
        canDrawTen() {
            return this.dashboard.oreBalance >= 2000
        },
        drawButtonText() {
            if (this.freeCount > 0) return '免费抽奖'
            return '立即抽奖'
        }
    },
    mounted() {
        this.loadDashboard()
        this.loadBroadcasts()
        this.startBroadcastLoop()
    },
    beforeDestroy() {
        if (this.broadcastTimer) {
            clearInterval(this.broadcastTimer)
            this.broadcastTimer = null
        }
        if (this.highlightTimer) {
            clearInterval(this.highlightTimer)
            this.highlightTimer = null
        }
    },
    methods: {
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
        handleGoHarvest() {
            this.showResultModal = false
            this.$router.push('/user/center/harvest')
        },
        prevPage() {
            if (this.currentPage > 1) { this.currentPage-- }
        },
        nextPage() {
            if (this.currentPage < this.totalPages) { this.currentPage++ }
        },
        goPage(p) { this.currentPage = p },
        getDefaultIcon(type) {
            if (type === 'physical') return '&#xf1c0;'
            if (type === 'virtual') return '&#xf023;'
            return '&#xf06d;'
        },
        getDefaultPrizes() {
            return [
                { name: '随机矿石', icon: '&#xf06d;', type: 'ore', oreAmount: '随机', isLocked: false, id: 'p1', _position: 0, _col: 0, _row: 0 },
                { name: '课程5折券', icon: '&#xf023;', type: 'virtual', isLocked: false, id: 'p2', _position: 1, _col: 1, _row: 0 },
                { name: '马克杯', icon: '&#xf1c0;', type: 'physical', isLocked: true, lockHint: '再抽1次解锁', id: 'p3', _position: 2, _col: 2, _row: 0 },
                { name: '随机盲盒', icon: '&#xf06d;', type: 'ore', oreAmount: '随机', isLocked: false, id: 'p4', _position: 3, _col: 0, _row: 1 },
                { name: '抽奖', icon: '&#xf058;', type: 'ore', isCenter: true, id: 'center', _position: -1, _col: 1, _row: 1 },
                { name: '小夜灯', icon: '&#xf0c0;', type: 'physical', isLocked: true, lockHint: '再抽2次解锁', id: 'p5', _position: 5, _col: 2, _row: 1 },
                { name: '金币眼罩', icon: '&#xf06d;', type: 'ore', oreAmount: '随机', isLocked: false, id: 'p6', _position: 6, _col: 0, _row: 2 },
                { name: '周边徽章', icon: '&#xf1c0;', type: 'physical', isLocked: true, lockHint: '再抽3次解锁', id: 'p7', _position: 7, _col: 1, _row: 2 },
                { name: 'Switch', icon: '&#xf1c0;', type: 'physical', isLocked: true, lockHint: '再抽3次解锁', id: 'p8', _position: 8, _col: 2, _row: 2 }
            ]
        },
        async loadDashboard() {
            try {
                const res = await getDashboard()
                if (res && res.code === 200 && res.data) {
                    const data = res.data
                    this.dashboard.oreBalance = data.oreBalance || 0
                    this.dashboard.freeDrawAvailable = data.freeDrawAvailable || false
                    this.dashboard.freeDrawUsed = data.freeDrawUsed || false
                    this.dashboard.todayDrawCount = data.todayDrawCount || 0
                    this.dashboard.luckyValue = data.luckyValue || 0
                    this.dashboard.luckyThreshold = data.luckyThreshold || 6000
                    this.dashboard.prizePool = data.prizePool || []
                    this.dashboard.broadcastMessages = data.broadcastMessages || []
                }
            } catch (e) {
                console.error('加载抽奖数据失败', e)
            }
        },
        async loadBroadcasts() {
            try {
                const res = await getBroadcast()
                if (res && res.code === 200 && res.data) {
                    const list = res.data.list || res.data || []
                    this.dashboard.broadcastMessages = list
                }
            } catch (e) {
                // ignore
            }
        },
        startBroadcastLoop() {
            this.broadcastTimer = setInterval(() => {
                this.loadBroadcasts()
            }, 15000)
        },
        handleCenterDraw() {
            if (this.isDrawing) return
            this.handleDraw('single')
        },
        async handleDraw(type) {
            if (this.isDrawing) return
            if (!this.canDrawSingle && type === 'single') {
                toast(this.freeCount > 0 ? '免费次数已用完' : '矿石不足200，无法抽奖', 2)
                return
            }
            if (!this.canDrawTen && type === 'ten') {
                toast('矿石不足2000，无法十连抽', 2)
                return
            }

            const useFree = type === 'single' && this.freeCount > 0
            this.isDrawing = true

            try {
                const res = await doDraw(type, useFree)
                if (res && res.code === 200 && res.data) {
                    const results = res.data.results || []
                    this.drawUsedFree = useFree

                    // Animate highlight
                    await this.animateHighlight(results)

                    // Show result modal
                    this.drawResults = results.map((r, idx) => {
                        const prize = this.dashboard.prizePool.find(p => p.id === r.prizeId) || {}
                        return {
                            id: r.prizeId,
                            name: r.prizeName || prize.name || '神秘奖品',
                            type: r.prizeType || (prize.type === 1 ? 'ore' : prize.type === 2 ? 'virtual' : 'physical'),
                            icon: prize.icon || this.getDefaultIcon(r.prizeType || prize.type),
                            oreAmount: r.oreAmount,
                            color: PRIZE_COLORS[idx % PRIZE_COLORS.length]
                        }
                    })
                    this.showResultModal = true

                    // Update dashboard
                    await this.loadDashboard()
                    await this.loadBroadcasts()
                } else {
                    toast(res && res.message ? res.message : '抽奖失败', 2)
                }
            } catch (e) {
                toast('抽奖失败，请稍后重试', 2)
            } finally {
                this.isDrawing = false
                this.highlightIndex = -1
            }
        },
        animateHighlight(results) {
            return new Promise(resolve => {
                const steps = results.length > 1 ? 25 : 12
                let step = 0
                let delay = 80

                const run = () => {
                    const idx = step % 8
                    this.highlightIndex = idx
                    step++

                    if (step < steps) {
                        delay += step > steps * 0.7 ? 40 : 15
                        this.highlightTimer = setTimeout(run, delay)
                    } else {
                        this.highlightIndex = -1
                        resolve()
                    }
                }
                run()
            })
        }
    }
}
</script>

<style lang="less" scoped>
@import '../../../styles/common';

.lottery-page {
    min-height: 100vh;
    background: linear-gradient(180deg, #f5f7fa 0%, #e6f0ff 100%);
}

.lottery-content {
    max-width: 1280px;
    margin: 0 auto;
    padding: 0 24px 24px;
    display: flex;
    gap: 16px;
}

// Main Area
.main-area {
    flex: 1;
    min-width: 0;
}

.header-banner {
    background: linear-gradient(135deg, #1e3a8a 0%, #2563eb 50%, #3b82f6 100%);
    border-radius: 16px;
    padding: 32px 40px;
    position: relative;
    overflow: hidden;
    margin-bottom: 16px;
}

.banner-decoration {
    position: absolute;
    top: 0;
    left: 0;
    right: 0;
    bottom: 0;
    pointer-events: none;
}

.deco-star {
    position: absolute;
    top: 10px;
    right: 100px;
    font-family: fontawesome;
    font-size: 24px;
    color: rgba(255, 255, 255, 0.3);
}

.deco-box {
    position: absolute;
    bottom: 10px;
    left: 50%;
    font-family: fontawesome;
    font-size: 40px;
    color: rgba(255, 255, 255, 0.15);
    transform: translateX(-50%);
}

.banner-title {
    font-size: 42px;
    font-weight: 700;
    color: #ffd700;
    text-shadow: 0 2px 8px rgba(255, 215, 0, 0.4);
    margin-bottom: 8px;
    letter-spacing: 4px;
}

.banner-subtitle {
    font-size: 18px;
    color: rgba(255, 255, 255, 0.9);
    margin-bottom: 16px;
}

.banner-coin {
    display: inline-flex;
    align-items: center;
    gap: 8px;
    background: rgba(255, 255, 255, 0.2);
    padding: 8px 16px;
    border-radius: 20px;
    backdrop-filter: blur(10px);
}

.coin-icon {
    font-family: fontawesome;
    font-size: 18px;
    color: #ffd700;
}

.coin-count {
    font-size: 18px;
    font-weight: 700;
    color: #fff;
}

.rules-link {
    position: absolute;
    top: 20px;
    right: 24px;
    font-size: 13px;
    color: rgba(255, 255, 255, 0.9);
    cursor: pointer;
    display: flex;
    align-items: center;
    gap: 4px;
    padding: 6px 12px;
    background: rgba(255, 255, 255, 0.15);
    border-radius: 16px;
    transition: background 0.2s;

    &:hover {
        background: rgba(255, 255, 255, 0.25);
    }

    .arrow {
        font-family: fontawesome;
        font-size: 10px;
    }
}

// Content Layout
.content-layout {
    display: flex;
    gap: 16px;
}

// Wheel Area
.wheel-area {
    flex: 1;
    background: #fff;
    border-radius: 16px;
    padding: 24px;
    box-shadow: 0 2px 12px rgba(0, 0, 0, 0.06);
}

.wheel-section-title {
    font-size: 18px;
    font-weight: 600;
    color: #1a1a1a;
    margin-bottom: 20px;
    display: flex;
    align-items: center;
    gap: 8px;
}

.title-icon {
    font-family: fontawesome;
    font-size: 20px;
    color: #fa8c16;
}

.wheel-container {
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: 20px;
}

.wheel-board {
    display: grid;
    grid-template-columns: repeat(3, 1fr);
    grid-template-rows: repeat(3, 1fr);
    gap: 12px;
    width: 420px;
    height: 420px;
    padding: 20px;
    background: linear-gradient(135deg, #fff7e6, #ffe7ba);
    border-radius: 16px;
    position: relative;
}

.wheel-cell {
    background: #fff;
    border-radius: 12px;
    padding: 12px 8px;
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    gap: 4px;
    position: relative;
    transition: all 0.15s ease;
    border: 2px solid transparent;

    &.is-highlight {
        background: linear-gradient(135deg, #fa8c16, #ffc069);
        border-color: #fa8c16;
        transform: scale(1.05);

        .cell-icon, .cell-name, .cell-amount {
            color: #fff !important;
        }
    }

    &.is-locked {
        opacity: 0.6;
    }

    &.is-physical {
        background: linear-gradient(135deg, #fff7e6, #fff0f0);
        border: 1px solid #ffccc7;
    }
}

.cell-lock {
    position: absolute;
    top: 4px;
    right: 4px;
    font-family: fontawesome;
    font-size: 12px;
    color: #fa8c16;
}

.cell-icon {
    font-family: fontawesome;
    font-size: 28px;
    color: #fa8c16;
    transition: color 0.15s;
}

.cell-name {
    font-size: 12px;
    color: #333;
    text-align: center;
    font-weight: 500;
    line-height: 1.3;
    transition: color 0.15s;
}

.cell-amount {
    font-size: 11px;
    color: #fa8c16;
    font-weight: 600;
    transition: color 0.15s;
}

.cell-lock-hint {
    position: absolute;
    bottom: 4px;
    font-size: 9px;
    color: #fa8c16;
    white-space: nowrap;
}

.wheel-center {
    grid-column: 2;
    grid-row: 2;
    background: linear-gradient(135deg, #fa8c16, #ffc069);
    border-radius: 50%;
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    cursor: pointer;
    box-shadow: 0 8px 24px rgba(250, 140, 22, 0.4);
    transition: transform 0.2s;
    position: relative;
    z-index: 10;

    &:hover:not(.is-drawing) {
        transform: scale(1.05);
    }

    &.is-drawing {
        cursor: not-allowed;
    }
}

.center-icon {
    font-family: fontawesome;
    font-size: 36px;
    color: #fff;
    margin-bottom: 4px;

    &.spinning {
        animation: spin 0.8s linear infinite;
    }
}

@keyframes spin {
    from { transform: rotate(0deg); }
    to { transform: rotate(360deg); }
}

.center-text {
    font-size: 14px;
    color: #fff;
    font-weight: 600;
}

// Draw Buttons
.draw-buttons {
    display: flex;
    gap: 16px;
    width: 100%;
    max-width: 420px;
}

.draw-btn {
    flex: 1;
    display: flex;
    flex-direction: column;
    align-items: center;
    padding: 14px 24px;
    border-radius: 12px;
    cursor: pointer;
    transition: all 0.2s;
    border: none;

    &:disabled {
        opacity: 0.5;
        cursor: not-allowed;
    }

    &:hover:not(:disabled) {
        transform: translateY(-2px);
    }

    &.single {
        background: linear-gradient(135deg, #fa8c16, #ffc069);
        color: #fff;
    }

    &.ten {
        background: linear-gradient(135deg, #722ed1, #9254de);
        color: #fff;
    }
}

.draw-btn-title {
    font-size: 16px;
    font-weight: 600;
    margin-bottom: 4px;
}

.draw-btn-cost {
    display: flex;
    align-items: center;
    gap: 4px;
    font-size: 13px;
    opacity: 0.9;
}

.ore-icon {
    font-family: fontawesome;
    font-size: 12px;
}

// Lucky Bar
.lucky-bar {
    display: flex;
    align-items: center;
    gap: 12px;
    width: 100%;
    max-width: 420px;
    padding: 12px 16px;
    background: #f5f7fa;
    border-radius: 12px;
}

.lucky-label {
    display: flex;
    align-items: center;
    gap: 6px;
    font-size: 13px;
    color: #666;
    flex-shrink: 0;
}

.lucky-icon {
    font-family: fontawesome;
    font-size: 14px;
    color: #fa8c16;
}

.lucky-progress {
    flex: 1;
    height: 8px;
    background: #e8e8e8;
    border-radius: 4px;
    overflow: hidden;
}

.lucky-progress-fill {
    height: 100%;
    background: linear-gradient(90deg, #fa8c16, #ffc069);
    border-radius: 4px;
    transition: width 0.3s ease;
}

.lucky-value {
    font-size: 13px;
    color: #fa8c16;
    font-weight: 600;
    flex-shrink: 0;
}

// Broadcast Area
.broadcast-area {
    width: 280px;
    flex-shrink: 0;
    background: #fff;
    border-radius: 16px;
    padding: 20px;
    box-shadow: 0 2px 12px rgba(0, 0, 0, 0.06);
}

.broadcast-section-title {
    font-size: 16px;
    font-weight: 600;
    color: #1a1a1a;
    margin-bottom: 16px;
    display: flex;
    align-items: center;
    gap: 8px;
}

.broadcast-list {
    display: flex;
    flex-direction: column;
    gap: 12px;
}

.broadcast-card {
    padding: 12px;
    background: #f5f7fa;
    border-radius: 10px;
}

.broadcast-user-info {
    display: flex;
    align-items: center;
    gap: 8px;
    margin-bottom: 6px;
}

.broadcast-avatar {
    width: 28px;
    height: 28px;
    border-radius: 50%;
    object-fit: cover;
}

.broadcast-avatar-default {
    width: 28px;
    height: 28px;
    border-radius: 50%;
    background: linear-gradient(135deg, #1e80ff, #69b1ff);
    display: flex;
    align-items: center;
    justify-content: center;
    font-family: fontawesome;
    font-size: 12px;
    color: #fff;
}

.broadcast-username {
    font-size: 13px;
    color: #1e80ff;
    font-weight: 500;
    max-width: 120px;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
}

.broadcast-prize-info {
    display: flex;
    align-items: center;
    gap: 4px;
    font-size: 12px;
}

.broadcast-action {
    color: #999;
}

.broadcast-prize {
    color: #fa8c16;
    font-weight: 500;
}

.broadcast-empty {
    display: flex;
    flex-direction: column;
    align-items: center;
    padding: 40px 0;
    gap: 12px;
}

.empty-icon {
    font-family: fontawesome;
    font-size: 36px;
    color: #ddd;
}

.empty-text {
    font-size: 13px;
    color: #999;
}

.broadcast-pagination {
    display: flex;
    align-items: center;
    justify-content: center;
    gap: 6px;
    margin-top: 16px;
}

.page-arrow {
    font-family: fontawesome;
    font-size: 12px;
    color: #666;
    cursor: pointer;
    padding: 4px 8px;
    border-radius: 4px;

    &:hover:not(.disabled) {
        background: #f0f2f5;
        color: #1e80ff;
    }

    &.disabled {
        color: #ddd;
        cursor: not-allowed;
    }
}

.page-num {
    font-size: 13px;
    color: #666;
    cursor: pointer;
    padding: 4px 10px;
    border-radius: 4px;
    min-width: 28px;
    text-align: center;

    &.active {
        background: #1e80ff;
        color: #fff;
    }

    &:hover:not(.active) {
        background: #f0f2f5;
    }
}

</style>
