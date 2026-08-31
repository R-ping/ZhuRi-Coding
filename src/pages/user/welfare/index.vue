<template>
    <div class="welfare-page">
        <div class="welfare-content">
            <!-- Left Panel -->
            <UserCenterSidebar
                active-menu="welfare"
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
                    <div class="banner-title">福利兑换 超多好礼</div>
                    <div class="banner-subtitle">惊喜好物等你来兑</div>
                </div>

                <!-- Announcement -->
                <div class="announcement-bar">
                    <span class="announcement-icon">&#xf0a1;</span>
                    <span class="announcement-title">公告栏</span>
                    <div class="announcement-content">
                        <p>1. 小铺专属问题反馈和建议，请拍「小铺反馈通道」商品，并在备注处描述问题，工作人员将在工作日24小时内以私信的方式联系您并处理相关问题。（若备注内容为空将无法进行处理反馈）</p>
                        <p>2. 所有中奖/兑换商品30个自然日内未提交收件信息视为自动放弃，逾期不补；</p>
                        <p>3. 抽奖/兑换商品将于15个工作日内发货，商品物流信息查询：关注"顺丰速递+"小程序"查快递"；虚拟物品也是十五个工作日下发到个人账号</p>
                        <p>4. 矿石回收请使用「回收功能」提交回收，备注回收统计不到数据，无法进行回收操作</p>
                        <p>5. 收件地址请填写详细，地址不详导致无法发货后果自负</p>
                        <p>6. 多次发货，拒收的用户，我们将建立黑名单库，后续任何物品不再发货。</p>
                    </div>
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
                        社区道具
                    </div>
                    <div class="ore-display">
                        <span class="ore-icon">&#xf06d;</span>
                        <span class="ore-text">矿石余额：</span>
                        <span class="ore-value">{{ oreBalance }}</span>
                    </div>
                </div>

                <!-- Goods List -->
                <div class="goods-list" v-if="activeTab === 'goods'">
                    <div class="goods-grid" v-if="goodsList.length > 0">
                        <div
                            v-for="goods in goodsList"
                            :key="goods.id"
                            class="goods-card"
                        >
                            <div class="goods-image">
                                <img :src="goods.image" :alt="goods.name" v-if="goods.image" />
                                <div class="goods-img-placeholder" v-else>&#xf06b;</div>
                                <div class="goods-lock-tag" v-if="isLocked(goods)">
                                    <span>&#xf023;</span>
                                    {{ getLockText(goods) }}
                                </div>
                            </div>
                            <div class="goods-info">
                                <div class="goods-name">{{ goods.name }}</div>
                                <div class="goods-tags" v-if="goods.tags && goods.tags.length > 0">
                                    <span v-for="(tag, idx) in goods.tags" :key="idx" class="goods-tag">{{ tag }}</span>
                                </div>
                                <div class="goods-price-row">
                                    <span class="current-price">&#xf06d; {{ formatPrice(goods.price) }}</span>
                                    <span class="original-price" v-if="goods.originalPrice">
                                        &#xf06d; {{ formatPrice(goods.originalPrice) }}
                                    </span>
                                </div>
                                <div class="goods-exchanged-count">
                                    <span>{{ goods.exchangedCount || 0 }}人已兑</span>
                                </div>
                                <button
                                    class="exchange-btn"
                                    :disabled="!canExchange(goods)"
                                    @click="handleExchange(goods)"
                                >
                                    {{ isLocked(goods) ? '暂未开放' : '立即兑换' }}
                                </button>
                            </div>
                        </div>
                    </div>
                    <div class="empty-state" v-else-if="!loading">
                        <div class="empty-icon">&#xf11a;</div>
                        <div class="empty-text">暂无可用商品</div>
                    </div>
                </div>

                <!-- Community Props List -->
                <div class="props-list" v-if="activeTab === 'props'">
                    <div class="props-grid" v-if="propsList.length > 0">
                        <div
                            v-for="prop in propsList"
                            :key="prop.id"
                            class="prop-card"
                        >
                            <div class="prop-image">
                                <img :src="prop.image" :alt="prop.name" v-if="prop.image" />
                                <div class="prop-img-placeholder" v-else>&#xf06b;</div>
                            </div>
                            <div class="prop-info">
                                <div class="prop-name">{{ prop.name }}</div>
                                <div class="prop-desc">{{ prop.description }}</div>
                                <div class="prop-price-row">
                                    <span class="prop-price">&#xf06d; {{ formatPrice(prop.price) }}</span>
                                </div>
                                <button
                                    class="prop-exchange-btn"
                                    :disabled="oreBalance < prop.price"
                                    @click="handlePropExchange(prop)"
                                >
                                    {{ oreBalance < prop.price ? '矿石不足' : '立即兑换' }}
                                </button>
                            </div>
                        </div>
                    </div>
                    <div class="empty-state" v-else-if="!loading">
                        <div class="empty-icon">&#xf11a;</div>
                        <div class="empty-text">暂无可用道具</div>
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

        <!-- Community Prop Exchange Confirm Dialog -->
        <div class="dialog-overlay" v-if="showPropDialog" @click.self="closePropDialog">
            <div class="dialog-box">
                <div class="dialog-header">
                    <span class="dialog-title">确认兑换</span>
                    <span class="dialog-close" @click="closePropDialog">&times;</span>
                </div>
                <div class="dialog-body" v-if="selectedProp">
                    <div class="dialog-goods-image">
                        <img :src="selectedProp.image" :alt="selectedProp.name" v-if="selectedProp.image" />
                        <div class="dialog-goods-placeholder" v-else>&#xf06b;</div>
                    </div>
                    <div class="dialog-goods-name">{{ selectedProp.name }}</div>
                    <div class="dialog-goods-desc" v-if="selectedProp.description">{{ selectedProp.description }}</div>
                    <div class="dialog-ore-cost">
                        需要消耗 <span class="ore-highlight">&#xf06d; {{ formatPrice(selectedProp.price) }}</span> 矿石
                    </div>
                    <div class="dialog-balance">
                        当前矿石：<span class="ore-highlight">&#xf06d; {{ oreBalance }}</span>
                    </div>
                    <div class="dialog-warning" v-if="oreBalance < selectedProp.price">
                        矿石不足，无法兑换
                    </div>
                </div>
                <div class="dialog-footer">
                    <div class="dialog-btn dialog-btn-cancel" @click="closePropDialog">取消</div>
                    <div
                        class="dialog-btn dialog-btn-confirm"
                        :class="{ disabled: oreBalance < (selectedProp ? selectedProp.price : 0) || exchangingProp }"
                        @click="confirmPropExchange"
                    >
                        {{ exchangingProp ? '兑换中...' : '确认兑换' }}
                    </div>
                </div>
            </div>
        </div>
    </div>
</template>

<script>
import store from '@/stores/store'
import UserCenterSidebar from '@/components/user/UserCenterSidebar.vue'
import { getGoodsList, getCommunityProps, exchangeCommunityProp } from '@/apis/welfare'
import { toast } from '@/utils/toast'

export default {
    name: 'UserWelfare',
    components: { UserCenterSidebar },
    data() {
        return {
            currentYear: new Date().getFullYear(),
            activeTab: 'goods',
            goodsList: [],
            propsList: [],
            oreBalance: 0,
            currentPage: 1,
            totalPages: 1,
            pageSize: 8,
            loading: false,
            showPropDialog: false,
            selectedProp: null,
            exchangingProp: false,
            userInfo: { nickName: '', level: '', avatar: '' }
        }
    },
    computed: {
        isDesktop() {
            return window.innerWidth > 1024
        }
    },
    mounted() {
        this.loadUserInfo()
        if (this.activeTab === 'goods') {
            this.loadGoods()
        } else {
            this.loadProps()
        }
    },
    methods: {
        loadUserInfo() {
            const info = store.state.userInfo || {}
            this.userInfo = {
                nickName: info.nickName || '用户',
                level: info.level || 'JY.1',
                avatar: info.avatar || ''
            }
            this.oreBalance = info.ore || 0
        },
        switchTab(tab) {
            this.activeTab = tab
            this.currentPage = 1
            if (tab === 'goods') {
                this.loadGoods()
            } else {
                this.loadProps()
            }
        },
        async loadGoods() {
            this.loading = true
            try {
                const res = await getGoodsList({
                    type: 'PHYSICAL,VIRTUAL',
                    page: this.currentPage,
                    size: this.pageSize
                })
                if (res && res.code === 200 && res.data) {
                    const data = res.data
                    this.goodsList = data.list || data.records || []
                    this.totalPages = data.pages || Math.ceil((data.total || 0) / this.pageSize)
                } else {
                    this.goodsList = []
                }
            } catch (e) {
                this.goodsList = this.getDefaultGoods()
            } finally {
                this.loading = false
            }
        },
        async loadProps() {
            this.loading = true
            try {
                const res = await getCommunityProps({
                    page: this.currentPage,
                    size: this.pageSize
                })
                if (res && res.code === 200 && res.data) {
                    const data = res.data
                    this.propsList = data.list || data.records || []
                    this.totalPages = data.pages || Math.ceil((data.total || 0) / this.pageSize)
                } else {
                    this.propsList = []
                }
            } catch (e) {
                this.propsList = this.getDefaultProps()
            } finally {
                this.loading = false
            }
        },
        getDefaultGoods() {
            return [
                { id: 1, name: '多功能早餐机分区煎烤', image: '', tags: ['限时优惠'], price: 1290000, originalPrice: 1500000, exchangedCount: 70, isPhysical: true, exchangeStart: null, exchangeEnd: null },
                { id: 2, name: '课程5折兑换券', image: '', tags: ['14天发货', '有效期2周'], price: 20000, originalPrice: 50000, exchangedCount: 20000, isPhysical: false, exchangeStart: null, exchangeEnd: null },
                { id: 3, name: '发货问题反馈通道', image: '', tags: ['扫码回复', '签到', '发货'], price: 10000000, exchangedCount: 176, isPhysical: false, exchangeStart: null, exchangeEnd: null },
                { id: 4, name: '解码系列棒球帽', image: '', tags: ['解码潮流'], price: 450000, exchangedCount: 88, isPhysical: true, exchangeStart: null, exchangeEnd: null },
                { id: 5, name: '虎虎生金眼罩', image: '', tags: ['睡个好觉'], price: 450000, exchangedCount: 27, isPhysical: true, exchangeStart: null, exchangeEnd: null },
                { id: 6, name: '好朋友礼盒-卡牌+铃铛', image: '', tags: [], price: 250000, exchangedCount: 15, isPhysical: true, exchangeStart: null, exchangeEnd: null },
                { id: 7, name: '掘金便携电脑支架', image: '', tags: ['稀土开发者限定款'], price: 880000, exchangedCount: 10, isPhysical: true, exchangeStart: null, exchangeEnd: null },
                { id: 8, name: '掘金开发者笔记本', image: '', tags: [], price: 280000, exchangedCount: 9, isPhysical: true, exchangeStart: null, exchangeEnd: null }
            ]
        },
        getDefaultProps() {
            return [
                { id: 'p1', name: '补签卡', image: '', description: '可以补签错过的签到，仅限当月使用', price: 1000 },
                { id: 'p2', name: '发帖置顶卡', image: '', description: '发布帖子后可置顶24小时', price: 5000 },
                { id: 'p3', name: '话题解锁卡', image: '', description: '解锁更多话题参与讨论', price: 3000 },
                { id: 'p4', name: '圈子创建卡', image: '', description: '可以创建自己的圈子', price: 10000 }
            ]
        },
        formatPrice(price) {
            if (!price) return 0
            if (price >= 10000) {
                return (price / 10000).toFixed(1) + '万'
            }
            return price
        },
        isLocked(goods) {
            if (!goods) return false
            const now = new Date()
            if (goods.exchangeStart && new Date(goods.exchangeStart) > now) return true
            if (goods.exchangeEnd && new Date(goods.exchangeEnd) < now) return true
            return false
        },
        getLockText(goods) {
            if (!goods) return ''
            if (goods.exchangeStart) {
                const start = new Date(goods.exchangeStart)
                return start.toLocaleDateString() + '开放兑换'
            }
            return '暂未开放'
        },
        canExchange(goods) {
            if (this.isLocked(goods)) return false
            return this.oreBalance >= goods.price
        },
        handleExchange(goods) {
            if (!this.canExchange(goods)) {
                if (this.isLocked(goods)) {
                    toast('该商品暂未开放兑换', 2)
                } else {
                    toast('矿石不足，无法兑换', 2)
                }
                return
            }
            this.$router.push('/user/center/welfare/redeem/' + goods.id)
        },
        handlePropExchange(prop) {
            if (this.oreBalance < prop.price) {
                toast('矿石不足，无法兑换', 2)
                return
            }
            this.selectedProp = prop
            this.showPropDialog = true
        },
        closePropDialog() {
            this.showPropDialog = false
            this.selectedProp = null
        },
        async confirmPropExchange() {
            if (!this.selectedProp) return
            if (this.oreBalance < this.selectedProp.price) {
                toast('矿石不足', 2)
                return
            }
            this.exchangingProp = true
            try {
                const res = await exchangeCommunityProp(this.selectedProp.id)
                if (res && res.code === 200) {
                    toast('兑换成功！', 2)
                    this.oreBalance -= this.selectedProp.price
                    this.closePropDialog()
                    if (this.activeTab === 'props') {
                        this.loadProps()
                    }
                } else {
                    toast(res && res.message ? res.message : '兑换失败', 2)
                }
            } catch (e) {
                toast('兑换失败，请稍后重试', 2)
            } finally {
                this.exchangingProp = false
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
                this.loadGoods()
            }
        },
        nextPage() {
            if (this.currentPage < this.totalPages) {
                this.currentPage++
                this.loadGoods()
            }
        },
        goPage(p) {
            this.currentPage = p
            this.loadGoods()
        }
    }
}
</script>

<style lang="less" scoped>
@import '../../../styles/common';

.welfare-page {
    min-height: 100vh;
    background: linear-gradient(180deg, #f5f7fa 0%, #e6f0ff 100%);
}

.welfare-content {
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
    background: linear-gradient(135deg, #ff6b35 0%, #f7931e 50%, #ffc107 100%);
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

// Announcement
.announcement-bar {
    background: #fff;
    border-radius: 12px;
    padding: 20px 24px;
    margin-bottom: 16px;
    box-shadow: 0 2px 8px rgba(0, 0, 0, 0.04);
}

.announcement-icon {
    font-family: fontawesome;
    font-size: 20px;
    color: #fa8c16;
    margin-right: 8px;
}

.announcement-title {
    font-size: 16px;
    font-weight: 600;
    color: #1a1a1a;
}

.announcement-content {
    margin-top: 12px;
    font-size: 13px;
    color: #666;
    line-height: 1.8;

    p {
        margin: 4px 0;
    }
}

// Content Tabs
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

.ore-display {
    margin-left: auto;
    display: flex;
    align-items: center;
    gap: 4px;
    font-size: 14px;
    color: #666;
}

.ore-icon {
    font-family: fontawesome;
    color: #fa8c16;
    font-size: 16px;
}

.ore-value {
    font-weight: 600;
    color: #fa8c16;
    font-size: 16px;
}

// Goods List
.goods-list, .props-list {
    background: #fff;
    border-radius: 12px;
    padding: 24px;
    box-shadow: 0 2px 8px rgba(0, 0, 0, 0.04);
    min-height: 400px;
}

.goods-grid, .props-grid {
    display: grid;
    grid-template-columns: repeat(4, 1fr);
    gap: 16px;
}

.goods-card, .prop-card {
    background: #fafafa;
    border-radius: 12px;
    overflow: hidden;
    transition: all 0.2s;
    border: 1px solid #f0f0f0;

    &:hover {
        transform: translateY(-4px);
        box-shadow: 0 8px 24px rgba(0, 0, 0, 0.1);
    }
}

.goods-image, .prop-image {
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

.goods-img-placeholder, .prop-img-placeholder {
    width: 100%;
    height: 100%;
    display: flex;
    align-items: center;
    justify-content: center;
    font-family: fontawesome;
    font-size: 48px;
    color: #ddd;
}

.goods-lock-tag {
    position: absolute;
    top: 8px;
    left: 8px;
    background: rgba(0, 0, 0, 0.6);
    color: #fff;
    font-size: 11px;
    padding: 4px 8px;
    border-radius: 4px;
    display: flex;
    align-items: center;
    gap: 4px;

    span {
        font-family: fontawesome;
    }
}

.goods-info, .prop-info {
    padding: 12px;
}

.goods-name, .prop-name {
    font-size: 14px;
    color: #1a1a1a;
    font-weight: 500;
    margin-bottom: 6px;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
}

.goods-tags {
    margin-bottom: 8px;
}

.goods-tag {
    display: inline-block;
    font-size: 11px;
    color: #fa8c16;
    background: #fff7e6;
    padding: 2px 6px;
    border-radius: 4px;
    margin-right: 4px;
}

.goods-price-row, .prop-price-row {
    display: flex;
    align-items: center;
    gap: 8px;
    margin-bottom: 4px;
}

.current-price, .prop-price {
    font-family: fontawesome;
    font-size: 15px;
    color: #fa8c16;
    font-weight: 600;
}

.original-price {
    font-size: 12px;
    color: #999;
    text-decoration: line-through;
}

.goods-exchanged-count {
    font-size: 12px;
    color: #999;
    margin-bottom: 8px;
}

.prop-desc {
    font-size: 12px;
    color: #666;
    margin-bottom: 8px;
    line-height: 1.4;
    height: 34px;
    overflow: hidden;
}

.exchange-btn, .prop-exchange-btn {
    width: 100%;
    padding: 8px 0;
    background: linear-gradient(135deg, #1e80ff, #69b1ff);
    color: #fff;
    border: none;
    border-radius: 8px;
    font-size: 13px;
    font-weight: 500;
    cursor: pointer;
    transition: all 0.2s;

    &:hover:not(:disabled) {
        background: linear-gradient(135deg, #4096ff, #91caff);
    }

    &:disabled {
        background: #e8e8e8;
        color: #999;
        cursor: not-allowed;
    }
}

// Empty State
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

// Loading State
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

// Dialog
.dialog-overlay {
    position: fixed;
    top: 0;
    left: 0;
    right: 0;
    bottom: 0;
    background: rgba(0, 0, 0, 0.5);
    display: flex;
    align-items: center;
    justify-content: center;
    z-index: 1000;
}

.dialog-box {
    background: #fff;
    border-radius: 16px;
    width: 420px;
    max-width: 90vw;
    box-shadow: 0 16px 48px rgba(0, 0, 0, 0.15);
}

.dialog-header {
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding: 20px 24px 0;
}

.dialog-title {
    font-size: 18px;
    font-weight: 600;
    color: #1a1a1a;
}

.dialog-close {
    font-size: 24px;
    color: #999;
    cursor: pointer;
    line-height: 1;

    &:hover {
        color: #666;
    }
}

.dialog-body {
    padding: 20px 24px;
    display: flex;
    flex-direction: column;
    align-items: center;
    text-align: center;
}

.dialog-goods-image {
    width: 120px;
    height: 120px;
    border-radius: 12px;
    overflow: hidden;
    margin-bottom: 16px;
    background: #f5f5f5;

    img {
        width: 100%;
        height: 100%;
        object-fit: cover;
    }
}

.dialog-goods-placeholder {
    width: 100%;
    height: 100%;
    display: flex;
    align-items: center;
    justify-content: center;
    font-family: fontawesome;
    font-size: 48px;
    color: #ddd;
}

.dialog-goods-name {
    font-size: 16px;
    font-weight: 600;
    color: #1a1a1a;
    margin-bottom: 8px;
}

.dialog-goods-desc {
    font-size: 13px;
    color: #999;
    margin-bottom: 16px;
    line-height: 1.5;
}

.dialog-ore-cost {
    font-size: 15px;
    color: #666;
    margin-bottom: 8px;
}

.dialog-balance {
    font-size: 14px;
    color: #666;
    margin-bottom: 12px;
}

.ore-highlight {
    font-family: fontawesome;
    color: #fa8c16;
    font-weight: 600;
}

.dialog-warning {
    font-size: 13px;
    color: #ff4d4f;
    background: #fff2f0;
    padding: 8px 16px;
    border-radius: 8px;
    width: 100%;
}

.dialog-footer {
    display: flex;
    gap: 12px;
    padding: 0 24px 20px;
}

.dialog-btn {
    flex: 1;
    padding: 12px 0;
    border-radius: 8px;
    font-size: 15px;
    font-weight: 500;
    text-align: center;
    cursor: pointer;
    transition: all 0.2s;
}

.dialog-btn-cancel {
    border: 1px solid #e8e8e8;
    color: #666;

    &:hover {
        border-color: #d0d0d0;
        color: #333;
    }
}

.dialog-btn-confirm {
    background: #1e80ff;
    color: #fff;

    &:hover:not(.disabled) {
        background: #4096ff;
    }

    &.disabled {
        background: #b0d0ff;
        cursor: not-allowed;
    }
}
</style>
