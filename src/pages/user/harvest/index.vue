<template>
    <div class="harvest-page">
        <div class="harvest-content">
            <!-- Left Panel -->
            <UserCenterSidebar
                active-menu="harvest"
                @menu-click="handleSidebarMenuClick"
            />

            <!-- Main Area -->
            <div class="main-area">
                <!-- Header Banner -->
                <div class="header-banner">
                    <div class="banner-bg-decor">
                        <div class="deco-gift">&#xf06b;</div>
                        <div class="deco-star">&#xf005;</div>
                    </div>
                    <div class="banner-title">我的收获</div>
                    <div class="banner-subtitle">抽奖与兑换的惊喜都在这里</div>
                </div>

                <!-- Category Tabs -->
                <div class="content-tabs">
                    <div
                        class="tab-item"
                        :class="{ active: activeTab === 'goods' }"
                        @click="switchTab('goods')"
                    >
                        惊喜好物
                    </div>
                    <div
                        class="tab-item"
                        :class="{ active: activeTab === 'props' }"
                        @click="switchTab('props')"
                    >
                        我的道具
                    </div>
                </div>

                <!-- 惊喜好物：抽奖获得的实体奖品 -->
                <div class="harvest-list" v-if="activeTab === 'goods'">
                    <div class="harvest-grid" v-if="goodsList.length > 0">
                        <div
                            v-for="item in goodsList"
                            :key="item.drawId"
                            class="harvest-card"
                        >
                            <div class="harvest-image">
                                <img v-if="item.iconUrl" :src="item.iconUrl" :alt="item.prizeName" />
                                <div class="harvest-img-placeholder" v-else>&#xf1c0;</div>
                            </div>
                            <div class="harvest-info">
                                <div class="harvest-name">{{ item.prizeName }}</div>
                                <div class="harvest-time">中奖于 {{ item.createdAt }}</div>
                                <div class="harvest-status-row">
                                    <span
                                        class="status-pill"
                                        :class="statusClass(item.orderStatusNum)"
                                    >
                                        {{ item.orderStatus || '待填地址' }}
                                    </span>
                                </div>
                                <button
                                    v-if="isPendingAddress(item)"
                                    class="harvest-action-btn"
                                    @click="handleRedeem(item)"
                                >
                                    去兑换
                                </button>
                                <div v-else class="harvest-finished">
                                    <span class="finished-text">物品状态跟随物流同步</span>
                                </div>
                            </div>
                        </div>
                    </div>
                    <div class="empty-state" v-else-if="!loading">
                        <div class="empty-icon">&#xf11a;</div>
                        <div class="empty-text">暂未抽到实体好物，快去抽奖吧</div>
                    </div>
                </div>

                <!-- 我的道具：抽奖获得的虚拟奖品（补签卡、课程兑换券等，聚合持有数量） -->
                <div class="harvest-list" v-else>
                    <div class="harvest-grid" v-if="propsList.length > 0">
                        <div
                            v-for="(item, idx) in propsList"
                            :key="item.itemCode || idx"
                            class="harvest-card"
                        >
                            <div class="harvest-image">
                                <img v-if="item.iconUrl" :src="item.iconUrl" :alt="item.itemName" />
                                <div class="harvest-img-placeholder" v-else>&#xf023;</div>
                                <div class="prop-count" v-if="item.quantity > 1">x{{ item.quantity }}</div>
                            </div>
                            <div class="harvest-info">
                                <div class="harvest-name">{{ item.itemName }}</div>
                                <div class="prop-tag">虚拟道具</div>
                                <div class="harvest-status-row">
                                    <span class="status-pill status-delivered">持有 {{ item.quantity }} 个</span>
                                </div>
                                <button
                                    v-if="isUsableProp(item)"
                                    class="harvest-action-btn"
                                    @click="handleUseProp(item)"
                                >
                                    去使用
                                </button>
                                <div v-else class="harvest-finished">
                                    <span class="finished-text">已入账</span>
                                </div>
                            </div>
                        </div>
                    </div>
                    <div class="empty-state" v-else-if="!loading">
                        <div class="empty-icon">&#xf11a;</div>
                        <div class="empty-text">暂未抽到虚拟道具</div>
                    </div>
                </div>

                <!-- Loading -->
                <div class="loading-state" v-if="loading">
                    <div class="loading-spinner"></div>
                    <div class="loading-text">加载中...</div>
                </div>

                <!-- Pagination -->
                <div class="pagination" v-if="totalPages > 1">
                    <span class="page-arrow" :class="{ disabled: currentPage <= 1 }" @click="prevPage">&#xf104;</span>
                    <span
                        v-for="p in totalPages"
                        :key="p"
                        class="page-num"
                        :class="{ active: p === currentPage }"
                        @click="goPage(p)"
                    >{{ p }}</span>
                    <span class="page-arrow" :class="{ disabled: currentPage >= totalPages }" @click="nextPage">&#xf105;</span>
                </div>
            </div>
        </div>
    </div>
</template>

<script>
import UserCenterSidebar from '@/components/user/UserCenterSidebar.vue'
import { getMyPrizes, getMyVirtualAssets } from '@/apis/lottery'
import { toast } from '@/utils/toast'

export default {
    name: 'UserHarvest',
    components: { UserCenterSidebar },
    data() {
        return {
            currentYear: new Date().getFullYear(),
            activeTab: 'goods',
            goodsList: [],
            propsList: [],
            currentPage: 1,
            totalPages: 1,
            pageSize: 12,
            loading: false
        }
    },
    mounted() {
        this.loadList()
    },
    methods: {
        switchTab(tab) {
            if (this.activeTab === tab) return
            this.activeTab = tab
            this.currentPage = 1
            this.loadList()
        },
        // 惊喜好物按实体、我的道具按虚拟分别请求
        async loadList() {
            this.loading = true
            try {
                if (this.activeTab === 'goods') {
                    const res = await getMyPrizes({
                        type: 'physical',
                        page: this.currentPage,
                        size: this.pageSize
                    })
                    if (res && res.code === 200 && res.data) {
                        const data = res.data
                        this.goodsList = data.list || []
                        this.totalPages = Math.ceil((data.total || 0) / this.pageSize) || 1
                    }
                } else {
                    // 我的道具走聚合持有接口（按 itemCode 聚合数量，不分页）
                    const res = await getMyVirtualAssets()
                    if (res && res.code === 200 && res.data) {
                        this.propsList = res.data.list || []
                    }
                    this.totalPages = 1
                }
            } catch (e) {
                if (this.activeTab === 'goods') {
                    this.goodsList = []
                } else {
                    this.propsList = []
                }
                this.totalPages = 1
            } finally {
                this.loading = false
            }
        },
        // 仅「待填地址」的实体奖品需要去兑换
        isPendingAddress(item) {
            return !item.orderStatusNum || item.orderStatusNum === 1
        },
        statusClass(statusNum) {
            switch (statusNum) {
                case 2: return 'status-prep'
                case 3: return 'status-shipping'
                case 4: return 'status-done'
                case 5: return 'status-expired'
                default: return 'status-pending'
            }
        },
        handleRedeem(item) {
            if (!item.orderId) {
                toast('订单信息异常', 2)
                return
            }
            this.$router.push('/user/center/harvest/redeem/' + item.orderId)
        },
        // 判断道具是否有使用入口：折扣率 < 1 的课程折扣券可跳课程列表去使用
        isUsableProp(item) {
            const rate = Number(item.discountRate)
            return !!item.itemCode && rate > 0 && rate < 1
        },
        handleUseProp(item) {
            if (item.itemCode === 'course50') {
                toast('请在课程详情购买时使用该折扣券', 2)
                this.$router.push('/course')
            }
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
        prevPage() {
            if (this.currentPage > 1) {
                this.currentPage--
                this.loadList()
            }
        },
        nextPage() {
            if (this.currentPage < this.totalPages) {
                this.currentPage++
                this.loadList()
            }
        },
        goPage(p) {
            this.currentPage = p
            this.loadList()
        }
    }
}
</script>

<style lang="less" scoped>
@import '../../../styles/common';

.harvest-page {
    min-height: 100vh;
    background: linear-gradient(180deg, #f5f7fa 0%, #e6f0ff 100%);
}

.harvest-content {
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
    background: linear-gradient(135deg, #2f54eb 0%, #597ef7 50%, #85a5ff 100%);
    border-radius: 16px;
    padding: 40px 50px;
    position: relative;
    overflow: hidden;
    margin-bottom: 16px;
}

.banner-bg-decor {
    position: absolute;
    top: 0;
    left: 0;
    right: 0;
    bottom: 0;
    pointer-events: none;
}

.deco-gift {
    position: absolute;
    top: 20px;
    right: 80px;
    font-family: fontawesome;
    font-size: 60px;
    color: rgba(255, 255, 255, 0.2);
    transform: rotate(-15deg);
}

.deco-star {
    position: absolute;
    bottom: 20px;
    right: 150px;
    font-family: fontawesome;
    font-size: 24px;
    color: rgba(255, 255, 255, 0.3);
}

.banner-title {
    font-size: 42px;
    font-weight: 700;
    color: #fff;
    text-shadow: 0 2px 8px rgba(255, 255, 255, 0.3);
    margin-bottom: 8px;
    letter-spacing: 6px;
}

.banner-subtitle {
    font-size: 18px;
    color: rgba(255, 255, 255, 0.9);
}

// Tabs
.content-tabs {
    display: flex;
    align-items: center;
    background: #fff;
    border-radius: 12px;
    padding: 0 24px;
    margin-bottom: 16px;
    box-shadow: 0 2px 8px rgba(0, 0, 0, 0.04);
}

.tab-item {
    padding: 16px 24px;
    font-size: 15px;
    color: #666;
    cursor: pointer;
    position: relative;
    transition: color 0.2s;

    &:hover {
        color: #1e80ff;
    }

    &.active {
        color: #1e80ff;
        font-weight: 600;

        &::after {
            content: '';
            position: absolute;
            bottom: 0;
            left: 50%;
            transform: translateX(-50%);
            width: 28px;
            height: 3px;
            background: #1e80ff;
            border-radius: 2px;
        }
    }
}

// List
.harvest-list {
    background: #fff;
    border-radius: 12px;
    padding: 24px;
    box-shadow: 0 2px 8px rgba(0, 0, 0, 0.04);
    min-height: 400px;
}

.harvest-grid {
    display: grid;
    grid-template-columns: repeat(4, 1fr);
    gap: 16px;
}

.harvest-card {
    background: #fafafa;
    border-radius: 12px;
    overflow: hidden;
    border: 1px solid #f0f0f0;
    transition: all 0.2s;

    &:hover {
        transform: translateY(-4px);
        box-shadow: 0 8px 24px rgba(0, 0, 0, 0.1);
    }
}

.harvest-image {
    width: 100%;
    aspect-ratio: 1;
    overflow: hidden;
    background: #f5f5f5;
    position: relative;

    img {
        width: 100%;
        height: 100%;
        object-fit: cover;
    }
}

.harvest-img-placeholder {
    width: 100%;
    height: 100%;
    display: flex;
    align-items: center;
    justify-content: center;
    font-family: fontawesome;
    font-size: 48px;
    color: #ddd;
}

.prop-count {
    position: absolute;
    top: 8px;
    right: 8px;
    background: rgba(0, 0, 0, 0.6);
    color: #fff;
    font-size: 12px;
    font-weight: 500;
    padding: 2px 8px;
    border-radius: 10px;
}

.harvest-info {
    padding: 12px;
}

.harvest-name {
    font-size: 14px;
    color: #1a1a1a;
    font-weight: 500;
    margin-bottom: 6px;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
}

.prop-tag {
    display: inline-block;
    font-size: 11px;
    color: #722ed1;
    background: #f9f0ff;
    padding: 2px 6px;
    border-radius: 4px;
    margin-bottom: 6px;
}

.harvest-time {
    font-size: 11px;
    color: #999;
    margin-bottom: 8px;
}

.harvest-status-row {
    margin-bottom: 10px;
}

.status-pill {
    display: inline-block;
    font-size: 12px;
    padding: 3px 10px;
    border-radius: 10px;
    font-weight: 500;

    &.status-pending {
        color: #fa8c16;
        background: #fff7e6;
    }

    &.status-prep {
        color: #1e80ff;
        background: #e6f4ff;
    }

    &.status-shipping {
        color: #722ed1;
        background: #f9f0ff;
    }

    &.status-done {
        color: #52c41a;
        background: #f6ffed;
    }

    &.status-expired {
        color: #999;
        background: #f5f5f5;
    }

    &.status-delivered {
        color: #52c41a;
        background: #f6ffed;
    }
}

.harvest-action-btn {
    width: 100%;
    padding: 8px 0;
    background: linear-gradient(135deg, #fa8c16, #ffc069);
    color: #fff;
    border: none;
    border-radius: 8px;
    font-size: 13px;
    font-weight: 500;
    cursor: pointer;
    transition: all 0.2s;

    &:hover {
        opacity: 0.9;
    }
}

.harvest-finished {
    .finished-text {
        display: block;
        text-align: center;
        font-size: 12px;
        color: #bbb;
        padding: 6px 0;
    }
}

// Empty & Loading
.empty-state {
    display: flex;
    flex-direction: column;
    align-items: center;
    padding: 60px 0;
}

.empty-icon {
    font-family: fontawesome;
    font-size: 64px;
    color: #e8e8e8;
    margin-bottom: 16px;
}

.empty-text {
    font-size: 15px;
    color: #ccc;
}

.loading-state {
    display: flex;
    flex-direction: column;
    align-items: center;
    padding: 40px 0;
}

.loading-spinner {
    width: 32px;
    height: 32px;
    border: 3px solid #f0f0f0;
    border-top-color: #1e80ff;
    border-radius: 50%;
    animation: spin 0.8s linear infinite;
    margin-bottom: 12px;
}

@keyframes spin {
    to {
        transform: rotate(360deg);
    }
}

.loading-text {
    font-size: 14px;
    color: #999;
}

// Pagination
.pagination {
    display: flex;
    align-items: center;
    justify-content: center;
    gap: 8px;
    margin-top: 24px;
    padding-top: 20px;
    border-top: 1px solid #f0f2f5;
}

.page-arrow {
    font-family: fontawesome;
    font-size: 14px;
    color: #666;
    cursor: pointer;
    padding: 4px 10px;
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