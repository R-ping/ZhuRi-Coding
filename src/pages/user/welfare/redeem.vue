<template>
    <div class="welfare-page">
        <div class="welfare-content">
            <!-- Left Panel -->
            <div class="left-panel">
                <div class="user-card">
                    <div class="user-avatar">
                        <img v-if="userInfo.avatar" :src="userInfo.avatar" alt="avatar"/>
                        <span v-else class="avatar-default">&#xf007;</span>
                    </div>
                    <div class="user-name">{{ userInfo.nickName || '用户' }}</div>
                    <div class="user-level">{{ userInfo.level || 'JY.1' }}</div>
                </div>
                <div class="side-menu">
                    <div class="menu-item" @click="goCheckin">
                        <span class="menu-icon">&#xf00c;</span>每日签到
                    </div>
                    <div class="menu-item" @click="goGrowth">
                        <span class="menu-icon">&#xf201;</span>成长等级
                    </div>
                    <div class="menu-item" @click="goLottery">
                        <span class="menu-icon">&#xf1d3;</span>幸运抽奖
                    </div>
                    <div class="menu-item active">
                        <span class="menu-icon">&#xf233;</span>福利兑换
                    </div>
                    <div class="menu-item" @click="goHarvest">
                        <span class="menu-icon">&#xf07c;</span>我的收获
                    </div>
                </div>
                <div class="side-footer">用户协议 · 法律声明 ©{{ currentYear }} 稀土掘金</div>
            </div>

            <!-- Main Area -->
            <div class="main-area">
                <!-- Breadcrumb -->
                <div class="breadcrumb">
                    <span class="breadcrumb-link" @click="goBack">{{ source === 'lottery' ? '我的收获' : '福利兑换' }}</span>
                    <span class="breadcrumb-separator">/</span>
                    <span class="breadcrumb-current">兑换详情</span>
                </div>

                <!-- Exchange Detail Card -->
                <div class="detail-card">
                    <div class="detail-title">兑换详情</div>

                    <!-- Shipping Address (only for physical goods) -->
                    <div class="address-section" v-if="goods.isPhysical">
                        <div class="section-title">收货地址</div>
                        <div class="address-form">
                            <div class="form-row">
                                <div class="form-item">
                                    <label>收货姓名</label>
                                    <input v-model="form.name" type="text" placeholder="请输入收货人姓名" />
                                </div>
                                <div class="form-item">
                                    <label>联系方式</label>
                                    <input v-model="form.phone" type="text" placeholder="请输入11位手机号" maxlength="11" />
                                </div>
                            </div>
                            <div class="form-item full-width">
                                <label>收货地址</label>
                                <textarea v-model="form.address" placeholder="请输入详细收货地址" rows="3"></textarea>
                            </div>
                        </div>
                    </div>

                    <!-- Goods Info -->
                    <div class="goods-section">
                        <div class="section-title">物品信息</div>
                        <div class="goods-table">
                            <div class="table-header">
                                <div class="col col-goods">兑换物品</div>
                                <div class="col col-qty">数量</div>
                                <div class="col col-price" v-if="source !== 'lottery'">单价</div>
                                <div class="col col-discount" v-if="source !== 'lottery'">优惠方式</div>
                                <div class="col col-total" v-if="source !== 'lottery'">总计</div>
                            </div>
                            <div class="table-row">
                                <div class="col col-goods">
                                    <div class="goods-thumb">
                                        <img v-if="goods.image" :src="goods.image" :alt="goods.name" />
                                        <div class="thumb-placeholder" v-else>&#xf06b;</div>
                                    </div>
                                    <div class="goods-info">
                                        <div class="goods-name">{{ goods.name }}</div>
                                        <div class="goods-tag" v-if="goods.tags && goods.tags.length > 0">
                                            {{ goods.tags[0] }}
                                        </div>
                                    </div>
                                </div>
                                <div class="col col-qty">1</div>
                                <div class="col col-price" v-if="source !== 'lottery'">
                                    <span class="price-text">&#xf06d; {{ formatPrice(goods.price) }}</span>
                                </div>
                                <div class="col col-discount" v-if="source !== 'lottery'">
                                    <span v-if="goods.originalPrice" class="discount-text">限时折扣</span>
                                    <span v-else class="discount-none">无优惠</span>
                                </div>
                                <div class="col col-total" v-if="source !== 'lottery'">
                                    <span class="total-price">&#xf06d; {{ formatPrice(goods.price) }}</span>
                                </div>
                            </div>
                        </div>
                    </div>

                    <!-- Exchange Notice -->
                    <div class="notice-section">
                        <div class="notice-icon">&#xf05a;</div>
                        <div class="notice-content">
                            <div class="notice-title">兑换须知</div>
                            <ol class="notice-list">
                                <li>虚拟商品兑换后不予退回，会在15个工作日内通过站内信方式发送兑换码，请注意查看【系统消息】</li>
                                <li>实物奖品将在15个工作日内寄出，具体物流信息可以关注微信小程序顺丰速递输入手机号查看，请耐心等待并注意查收</li>
                                <li>兑换成功后可在"成长福利-我的收获"查看</li>
                            </ol>
                        </div>
                    </div>

                    <!-- Remark (矿石兑换专用) -->
                    <div class="remark-section" v-if="source !== 'lottery'">
                        <label class="remark-label">备注</label>
                        <textarea v-model="form.remark" class="remark-input" placeholder="请输入备注，例如兑换产品的期望尺码" rows="2"></textarea>
                    </div>

                    <!-- Confirm Button -->
                    <div class="confirm-section">
                        <button
                            class="confirm-btn"
                            :disabled="!canSubmit || submitting"
                            @click="handleConfirm"
                        >
                            {{ submitting ? '提交中...' : (source === 'lottery' ? '确认领取' : '确认兑换') }}
                        </button>
                    </div>
                </div>
            </div>
        </div>

        <!-- Success Dialog -->
        <div class="dialog-overlay" v-if="showSuccess" @click.self="showSuccess = false">
            <div class="success-dialog">
                <div class="success-icon">&#xf058;</div>
                <div class="success-title">{{ source === 'lottery' ? '领取成功！' : '兑换成功！' }}</div>
                <div class="success-desc">
                    <template v-if="source === 'lottery'">
                        已收到收货地址，奖品进入备货状态，请耐心等待
                    </template>
                    <template v-else-if="goods.isPhysical">
                        实物奖品将在15个工作日内寄出，请耐心等待
                    </template>
                    <template v-else>
                        虚拟商品将在15个工作日内发送兑换码，请注意查收
                    </template>
                </div>
                <div class="success-actions">
                    <button class="btn btn-primary" @click="goBack">返回</button>
                </div>
            </div>
        </div>
    </div>
</template>

<script>
import store from '@/stores/store'
import { getGoodsDetail, doExchange } from '@/apis/welfare'
import { getPhysicalOrderDetail, claimPhysical } from '@/apis/lottery'
import { toast } from '@/utils/toast'

export default {
    name: 'WelfareRedeem',
    props: {
        // welfare=矿石兑换（默认）；lottery=抽奖奖品领取（不消耗矿石）
        source: { type: String, default: 'welfare' },
        orderId: { type: [String, Number], default: null }
    },
    data() {
        return {
            currentYear: new Date().getFullYear(),
            goodsId: null,
            goods: {
                id: null,
                name: '',
                image: '',
                tags: [],
                price: 0,
                originalPrice: 0,
                isPhysical: true,
                description: ''
            },
            form: {
                name: '',
                phone: '',
                address: '',
                remark: ''
            },
            userInfo: { nickName: '', level: '', avatar: '' },
            submitting: false,
            showSuccess: false
        }
    },
    computed: {
        canSubmit() {
            // 抽奖奖品：无需矿石，只校验收货信息
            if (this.source === 'lottery') {
                return this.form.name && this.form.phone && this.form.address
            }
            if (!this.goods.isPhysical) {
                return this.oreBalance >= this.goods.price
            }
            return this.form.name && this.form.phone && this.form.address && 
                   this.oreBalance >= this.goods.price
        },
        oreBalance() {
            const info = store.state.userInfo || {}
            return info.ore || 0
        }
    },
    mounted() {
        if (this.source === 'lottery') {
            this.loadLotteryOrder()
        } else {
            this.goodsId = this.$route.params.id
            this.loadGoodsDetail()
        }
        this.loadUserInfo()
    },
    methods: {
        loadUserInfo() {
            const info = store.state.userInfo || {}
            this.userInfo = {
                nickName: info.nickName || '用户',
                level: info.level || 'JY.1',
                avatar: info.avatar || ''
            }
        },
        // 抽奖奖品领取：加载实体订单详情（展示奖品、不显示矿石价）
        async loadLotteryOrder() {
            const oid = this.orderId || this.$route.params.id
            try {
                const res = await getPhysicalOrderDetail(oid)
                if (res && res.code === 200 && res.data) {
                    const d = res.data
                    this.goodsId = d.orderId
                    this.goods = {
                        id: d.orderId,
                        name: d.prizeName,
                        image: d.iconUrl,
                        tags: [],
                        price: 0,
                        originalPrice: 0,
                        isPhysical: true,
                        description: ''
                    }
                } else {
                    toast(res && res.message ? res.message : '奖品不存在', 2)
                    this.$router.replace('/user/center/harvest')
                }
            } catch (e) {
                toast('加载奖品失败', 2)
                this.$router.replace('/user/center/harvest')
            }
        },
        async loadGoodsDetail() {
            try {
                const res = await getGoodsDetail(this.goodsId)
                if (res && res.code === 200 && res.data) {
                    const data = res.data
                    this.goods = {
                        id: data.id,
                        name: data.name,
                        image: data.image,
                        tags: data.tags || [],
                        price: data.price,
                        originalPrice: data.originalPrice,
                        isPhysical: data.isPhysical,
                        description: data.description
                    }
                } else {
                    toast('商品不存在', 2)
                    this.$router.replace('/user/center/welfare')
                }
            } catch (e) {
                this.goods = this.getDefaultGoods()
            }
        },
        getDefaultGoods() {
            return {
                id: this.goodsId,
                name: '课程5折兑换券',
                image: '',
                tags: ['14天发货', '有效期2周'],
                price: 20000,
                originalPrice: 50000,
                isPhysical: false,
                description: '虚拟商品'
            }
        },
        formatPrice(price) {
            if (!price) return 0
            if (price >= 10000) {
                return (price / 10000).toFixed(1) + '万'
            }
            return price
        },
        async handleConfirm() {
            if (!this.canSubmit) {
                if (this.source === 'lottery') {
                    if (!this.form.name) toast('请填写收货人姓名', 2)
                    else if (!this.form.phone) toast('请填写手机号', 2)
                    else if (!this.form.address) toast('请填写收货地址', 2)
                    return
                }
                if (!this.goods.isPhysical) {
                    toast('矿石不足，无法兑换', 2)
                } else {
                    if (!this.form.name) toast('请填写收货人姓名', 2)
                    else if (!this.form.phone) toast('请填写手机号', 2)
                    else if (!this.form.address) toast('请填写收货地址', 2)
                    else toast('矿石不足，无法兑换', 2)
                }
                return
            }
            this.submitting = true
            try {
                // 抽奖奖品：提交收货地址，不扣矿石，进入备货状态
                if (this.source === 'lottery') {
                    const res = await claimPhysical({
                        orderId: this.goodsId,
                        receiverName: this.form.name,
                        phone: this.form.phone,
                        address: this.form.address
                    })
                    if (res && res.code === 200) {
                        this.showSuccess = true
                    } else {
                        toast(res && res.message ? res.message : '领取失败', 2)
                    }
                    return
                }

                const payload = {
                    goodsId: this.goodsId,
                    remark: this.form.remark
                }
                if (this.goods.isPhysical) {
                    payload.name = this.form.name
                    payload.phone = this.form.phone
                    payload.address = this.form.address
                }
                const res = await doExchange(payload)
                if (res && res.code === 200) {
                    this.showSuccess = true
                } else {
                    toast(res && res.message ? res.message : '兑换失败', 2)
                }
            } catch (e) {
                toast(this.source === 'lottery' ? '领取失败，请稍后重试' : '兑换失败，请稍后重试', 2)
            } finally {
                this.submitting = false
            }
        },
        goBack() {
            if (this.source === 'lottery') {
                this.$router.push('/user/center/harvest')
            } else {
                this.$router.push('/user/center/welfare')
            }
        },
        goCheckin() {
            this.$router.push('/user/center/checkin')
        },
        goGrowth() {
            this.$router.push('/user/center/growth')
        },
        goLottery() {
            this.$router.push('/user/center/lottery')
        },
        goHarvest() {
            this.$router.push('/user/center/harvest')
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

// Left Panel
.left-panel {
    width: 180px;
    flex-shrink: 0;
    display: flex;
    flex-direction: column;
}

.user-card {
    background: #fff;
    border-radius: 12px;
    padding: 20px 16px;
    text-align: center;
    margin-bottom: 16px;
    box-shadow: 0 2px 8px rgba(0, 0, 0, 0.04);
}

.user-avatar {
    width: 64px;
    height: 64px;
    border-radius: 50%;
    overflow: hidden;
    margin: 0 auto 10px;
    background: linear-gradient(135deg, #1e80ff, #69b1ff);
    display: flex;
    align-items: center;
    justify-content: center;
}

.user-avatar img {
    width: 100%;
    height: 100%;
    object-fit: cover;
}

.avatar-default {
    font-family: fontawesome;
    font-size: 28px;
    color: #fff;
}

.user-name {
    font-size: 14px;
    font-weight: 600;
    color: #1a1a1a;
    margin-bottom: 4px;
}

.user-level {
    font-size: 12px;
    color: #fa8c16;
    background: #fff7e6;
    padding: 2px 10px;
    border-radius: 10px;
    display: inline-block;
}

.side-menu {
    background: #fff;
    border-radius: 12px;
    padding: 8px;
    box-shadow: 0 2px 8px rgba(0, 0, 0, 0.04);
    flex: 1;
}

.menu-item {
    display: flex;
    align-items: center;
    gap: 10px;
    padding: 12px 14px;
    font-size: 14px;
    color: #666;
    border-radius: 8px;
    cursor: pointer;
    transition: all 0.2s;

    &:hover {
        background: #f5f7fa;
        color: #1e80ff;
    }

    &.active {
        background: linear-gradient(135deg, #1e80ff, #69b1ff);
        color: #fff;

        .menu-icon {
            color: #fff;
        }
    }
}

.menu-icon {
    font-family: fontawesome;
    font-size: 16px;
    color: #999;
    width: 20px;
    text-align: center;
}

.side-footer {
    text-align: center;
    font-size: 11px;
    color: #ccc;
    margin-top: 16px;
}

// Main Area
.main-area {
    flex: 1;
    min-width: 0;
}

// Breadcrumb
.breadcrumb {
    display: flex;
    align-items: center;
    gap: 8px;
    margin-bottom: 16px;
    font-size: 14px;
}

.breadcrumb-link {
    color: #1e80ff;
    cursor: pointer;

    &:hover {
        text-decoration: underline;
    }
}

.breadcrumb-separator {
    color: #ccc;
}

.breadcrumb-current {
    color: #666;
}

// Detail Card
.detail-card {
    background: #fff;
    border-radius: 12px;
    padding: 32px;
    box-shadow: 0 2px 8px rgba(0, 0, 0, 0.04);
}

.detail-title {
    font-size: 20px;
    font-weight: 600;
    color: #1a1a1a;
    margin-bottom: 24px;
    padding-bottom: 16px;
    border-bottom: 1px solid #f0f0f0;
}

// Section Title
.section-title {
    font-size: 16px;
    font-weight: 600;
    color: #1a1a1a;
    margin-bottom: 16px;
}

// Address Section
.address-section {
    margin-bottom: 32px;
}

.address-form {
    background: #f5f7fa;
    border-radius: 12px;
    padding: 24px;
}

.form-row {
    display: flex;
    gap: 16px;
    margin-bottom: 16px;
}

.form-item {
    flex: 1;

    label {
        display: block;
        font-size: 13px;
        color: #666;
        margin-bottom: 8px;
    }

    input, textarea {
        width: 100%;
        padding: 10px 14px;
        border: 1px solid #e8e8e8;
        border-radius: 8px;
        font-size: 14px;
        color: #1a1a1a;
        background: #fff;
        transition: border-color 0.2s;
        box-sizing: border-box;

        &:focus {
            outline: none;
            border-color: #1e80ff;
        }

        &::placeholder {
            color: #ccc;
        }
    }

    textarea {
        resize: vertical;
    }

    &.full-width {
        flex: 1;
    }
}

// Goods Section
.goods-section {
    margin-bottom: 32px;
}

.goods-table {
    border: 1px solid #f0f0f0;
    border-radius: 12px;
    overflow: hidden;
}

.table-header {
    display: flex;
    background: #f5f7fa;
    font-size: 13px;
    color: #666;
    font-weight: 500;
}

.table-row {
    display: flex;
    background: #fff;
}

.table-header, .table-row {
    .col {
        padding: 16px 20px;
        display: flex;
        align-items: center;
    }
}

.col-goods { flex: 3; }
.col-qty { flex: 1; justify-content: center; }
.col-price { flex: 1.5; justify-content: center; }
.col-discount { flex: 1.5; justify-content: center; }
.col-total { flex: 1.5; justify-content: center; }

.goods-thumb {
    width: 64px;
    height: 64px;
    border-radius: 8px;
    overflow: hidden;
    margin-right: 12px;
    background: #f5f5f5;
    flex-shrink: 0;

    img {
        width: 100%;
        height: 100%;
        object-fit: cover;
    }
}

.thumb-placeholder {
    width: 100%;
    height: 100%;
    display: flex;
    align-items: center;
    justify-content: center;
    font-family: fontawesome;
    font-size: 24px;
    color: #ddd;
}

.goods-info {
    flex: 1;
}

.goods-name {
    font-size: 14px;
    color: #1a1a1a;
    font-weight: 500;
    margin-bottom: 4px;
}

.goods-tag {
    display: inline-block;
    font-size: 11px;
    color: #fa8c16;
    background: #fff7e6;
    padding: 2px 6px;
    border-radius: 4px;
}

.price-text {
    font-family: fontawesome;
    color: #fa8c16;
    font-weight: 500;
}

.total-price {
    font-family: fontawesome;
    color: #fa8c16;
    font-weight: 600;
    font-size: 15px;
}

.discount-text {
    color: #fa8c16;
    font-size: 12px;
}

.discount-none {
    color: #999;
    font-size: 12px;
}

// Notice Section
.notice-section {
    display: flex;
    gap: 12px;
    background: #e6f7ff;
    border-radius: 12px;
    padding: 16px 20px;
    margin-bottom: 32px;
}

.notice-icon {
    font-family: fontawesome;
    font-size: 20px;
    color: #1e80ff;
    flex-shrink: 0;
    margin-top: 2px;
}

.notice-content {
    flex: 1;
}

.notice-title {
    font-size: 14px;
    font-weight: 600;
    color: #1a1a1a;
    margin-bottom: 8px;
}

.notice-list {
    font-size: 13px;
    color: #666;
    line-height: 1.8;
    padding-left: 20px;
    margin: 0;

    li {
        margin-bottom: 4px;
    }
}

// Remark Section
.remark-section {
    margin-bottom: 32px;

    .remark-label {
        display: block;
        font-size: 14px;
        color: #1a1a1a;
        font-weight: 500;
        margin-bottom: 8px;
    }

    .remark-input {
        width: 100%;
        padding: 10px 14px;
        border: 1px solid #e8e8e8;
        border-radius: 8px;
        font-size: 14px;
        color: #1a1a1a;
        background: #fff;
        resize: vertical;
        box-sizing: border-box;

        &:focus {
            outline: none;
            border-color: #1e80ff;
        }

        &::placeholder {
            color: #ccc;
        }
    }
}

// Confirm Section
.confirm-section {
    text-align: right;
}

.confirm-btn {
    padding: 12px 48px;
    background: linear-gradient(135deg, #1e80ff, #69b1ff);
    color: #fff;
    border: none;
    border-radius: 8px;
    font-size: 15px;
    font-weight: 500;
    cursor: pointer;
    transition: all 0.2s;

    &:hover:not(:disabled) {
        background: linear-gradient(135deg, #4096ff, #91caff);
        transform: translateY(-2px);
    }

    &:disabled {
        background: #e8e8e8;
        color: #999;
        cursor: not-allowed;
    }
}

// Success Dialog
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

.success-dialog {
    background: #fff;
    border-radius: 16px;
    width: 360px;
    padding: 40px 32px;
    text-align: center;
}

.success-icon {
    width: 64px;
    height: 64px;
    background: #52c41a;
    border-radius: 50%;
    display: flex;
    align-items: center;
    justify-content: center;
    margin: 0 auto 20px;
    font-family: fontawesome;
    font-size: 32px;
    color: #fff;
}

.success-title {
    font-size: 20px;
    font-weight: 600;
    color: #1a1a1a;
    margin-bottom: 12px;
}

.success-desc {
    font-size: 14px;
    color: #666;
    line-height: 1.6;
    margin-bottom: 24px;
}

.success-actions {
    .btn {
        padding: 10px 40px;
        border-radius: 8px;
        font-size: 14px;
        font-weight: 500;
        border: none;
        cursor: pointer;
    }

    .btn-primary {
        background: linear-gradient(135deg, #1e80ff, #69b1ff);
        color: #fff;

        &:hover {
            background: linear-gradient(135deg, #4096ff, #91caff);
        }
    }
}
</style>
