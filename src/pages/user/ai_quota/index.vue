<template>
    <div class="ai-quota-page">
        <!-- 页头 -->
        <div class="page-header">
            <div class="header-title">AI 额度中心</div>
            <div class="header-subtitle">充值 AI 额度，按 tokens 用量计费 · 与每日免费额度累加使用</div>
        </div>

        <div class="page-body">
            <!-- 额度概览 -->
            <div class="card overview-card">
                <div class="card-title">我的额度</div>
                <div class="overview-grid">
                    <div class="ov-item">
                        <div class="ov-label">今日免费额度</div>
                        <div class="ov-value free">
                            <span class="num">{{ formatTokens(freeTokens.remainToday) }}</span>
                            <span class="slash">/ {{ formatTokens(freeTokens.dailyLimit) }} tokens</span>
                        </div>
                        <div class="ov-bar">
                            <div class="ov-bar-inner" :style="{ width: freePercent }"></div>
                        </div>
                        <div class="ov-tip">每日 {{ formatTokens(freeTokens.dailyLimit) }} tokens（≈ {{ free.dailyLimit }} 次问答），次日 0 点重置</div>
                    </div>
                    <div class="ov-item">
                        <div class="ov-label">已购额度</div>
                        <div class="ov-value wallet">
                            <span class="num">{{ formatTokens(walletTokens) }}</span>
                            <span class="slash">tokens</span>
                        </div>
                        <div class="ov-tip gap">购买额度包后按 token 实际用量抵扣，永不过期</div>
                    </div>
                </div>
            </div>

            <!-- 套餐选择 -->
            <div class="card package-card">
                <div class="card-title">选择额度包</div>
                <div class="pkg-grid">
                    <div class="pkg-item" v-for="pkg in packages" :key="pkg.code">
                        <div class="pkg-quota">
                            <span class="pkg-num">{{ formatTokens(pkg.tokenQuota) }}</span>
                            <span class="pkg-unit">tokens</span>
                        </div>
                        <div class="pkg-price">
                            <span class="pkg-yen">¥</span>
                            <span class="pkg-num">{{ formatYuan(pkg.priceFen) }}</span>
                        </div>
                        <div class="pkg-unit-price">折合 ¥{{ formatPerMillion(pkg.priceFen, pkg.tokenQuota) }}/百万 tokens</div>
                        <button class="pkg-buy" :disabled="paying" @click="buy(pkg)">
                            {{ paying && currentPkg === pkg.code ? '下单中…' : '立即购买' }}
                        </button>
                    </div>
                </div>
                <div class="pkg-note">支付走支付宝（沙箱环境）；支付成功后额度实时到账</div>
            </div>

            <!-- 当前订单 -->
            <div class="card order-card" v-if="order.orderNo">
                <div class="card-title">本笔订单</div>
                <div class="order-row">
                    <span class="row-label">订单编号</span>
                    <span class="row-value">{{ order.orderNo }}</span>
                </div>
                <div class="order-row">
                    <span class="row-label">订单状态</span>
                    <span class="row-value status" :class="orderStatusClass">{{ orderStatusText }}</span>
                </div>
                <div class="order-actions" v-if="order.status === 0">
                    <button class="pay-btn" :disabled="paying" @click="repay">重新打开支付</button>
                    <button class="ghost-btn" @click="closeOrder">我已支付</button>
                </div>
            </div>
        </div>
    </div>
</template>

<script>
import { getAiQuotaStatus, aiTopupCreate, aiTopupStatus, fetchTopupPayHtml } from '@/apis/ai'
import { toast } from '@/utils/toast'

const STATUS_MAP = {
    0: { text: '待支付（请在新窗口完成支付宝支付）', cls: 'pending' },
    1: { text: '支付成功，额度已到账', cls: 'paid' },
    2: { text: '订单已关闭', cls: 'closed' }
}

export default {
    name: 'AiQuotaCenter',
    data() {
        return {
            // 次数维度（保留：每日免费次数闸门仍在生效，用于兜底提示）
            free: { dailyLimit: 0, usedToday: 0, remainToday: 0 },
            // tokens 维度（主展示口径）：用量与余额均按 token 计费
            freeTokens: { dailyLimit: 0, usedToday: 0, remainToday: 0 },
            walletTokens: 0,
            packages: [],
            order: {},
            currentPkg: '',
            paying: false,
            pollTimer: null
        }
    },
    computed: {
        freePercent() {
            const limit = this.freeTokens.dailyLimit || this.free.dailyLimit
            const remain = this.freeTokens.dailyLimit ? this.freeTokens.remainToday : this.free.remainToday
            if (!limit) return '0%'
            const pct = Math.round((remain / limit) * 100)
            return Math.max(0, Math.min(100, pct)) + '%'
        },
        orderStatusText() {
            const s = STATUS_MAP[this.order.status]
            return s ? s.text : '未知'
        },
        orderStatusClass() {
            const s = STATUS_MAP[this.order.status]
            return s ? s.cls : ''
        }
    },
    mounted() {
        this.loadQuota()
    },
    beforeDestroy() {
        this.stopPolling()
    },
    methods: {
        loadQuota() {
            getAiQuotaStatus().then(res => {
                if (res && res.code === 200 && res.data) {
                    this.free = res.data.freeQuota || this.free
                    // tokens 口径优先；老后端未返回时降级用次数展示（不报错）
                    if (res.data.freeTokens) {
                        this.freeTokens = res.data.freeTokens
                        this.walletTokens = Number(res.data.walletTokenBalance) || 0
                    } else {
                        this.freeTokens = this.free
                        this.walletTokens = Number(res.data.walletBalance) || 0
                    }
                    const pkgs = res.data.packages || {}
                    this.packages = Object.keys(pkgs).map(code => ({
                        code,
                        quota: (pkgs[code] && pkgs[code].quota) || 0,
                        priceFen: (pkgs[code] && pkgs[code].priceFen) || 0,
                        tokenQuota: (pkgs[code] && pkgs[code].tokenQuota) || 0
                    }))
                }
            }).catch(err => {
                // 未登录：code=1（NEED_LOGIN），弹出登录
                if (err && err.code === 1) {
                    this.$store.commit('SHOW_LOGIN_MODAL')
                }
            })
        },
        formatYuan(fen) {
            const v = parseInt(fen, 10)
            if (isNaN(v)) return '0.00'
            return (v / 100).toFixed(2)
        },
        /** token 数格式化：1.5万 / 50万 / 300万（避免页面出现 3000000 这种长数字） */
        formatTokens(n) {
            const v = Number(n) || 0
            if (v >= 10000) {
                const w = v / 10000
                return (w >= 100 ? Math.round(w) : w.toFixed(1).replace(/\.0$/, '')) + '万'
            }
            return String(v)
        },
        /** 折合每百万 tokens 单价（元，两位小数） */
        formatPerMillion(fen, tokens) {
            const v = parseInt(fen, 10)
            const t = Number(tokens) || 0
            if (isNaN(v) || !t) return '0.00'
            return (v / 100 / t * 1000000).toFixed(2)
        },
        /** 下单 → 打开支付宝收银台 → 轮询支付状态 */
        async buy(pkg) {
            if (this.paying) return
            if (!this.$store.state.accessToken) {
                this.$store.commit('SHOW_LOGIN_MODAL')
                return
            }
            this.paying = true
            this.currentPkg = pkg.code
            try {
                const res = await aiTopupCreate(pkg.code)
                if (res && res.code === 200 && res.data && res.data.orderNo) {
                    this.order = Object.assign({ status: 0 }, res.data)
                    this.loadQuota()
                    await this.openPayWindow(res.data.orderNo)
                    this.startPolling(res.data.orderNo)
                } else {
                    toast((res && res.message) || '下单失败，请稍后重试', 2)
                    this.paying = false
                }
            } catch (err) {
                toast((err && err.message) || '下单失败，请稍后重试', 2)
                this.paying = false
            }
        },
        /**
         * 打开支付收银台：
         * 后端 /topup/page 依赖登录态（accToken 头），无法 window.open(url) 直开。
         * 故先同步开空窗（保持在用户点击手势内，防弹窗拦截），fetch 到 HTML 后
         * 显式 document.open()/write()/close() 写入并交由页面内脚本自动提交。
         */
        async openPayWindow(orderNo) {
            const win = window.open('about:blank', '_blank')
            if (!win) {
                toast('请允许浏览器弹窗后重试', 2)
                this.paying = false
                return
            }
            try {
                const html = await fetchTopupPayHtml(orderNo)
                if (win.closed) {
                    this.paying = false
                    return
                }
                win.document.open()
                win.document.write(html)
                win.document.close()
            } catch (err) {
                if (!win.closed) win.close()
                this.paying = false
                toast('打开支付页失败，可稍后点击"重新打开支付"', 2)
            }
        },
        /** 未支付成功时再次拉起支付，无需重新下单 */
        async repay() {
            if (!this.order.orderNo || this.paying) return
            this.paying = true
            await this.openPayWindow(this.order.orderNo)
            this.paying = false
            this.startPolling(this.order.orderNo)
        },
        closeOrder() {
            this.stopPolling()
            this.order = {}
        },
        /** 每 3s 轮询订单状态，支付成功/关闭即停止并刷新额度 */
        startPolling(orderNo) {
            this.stopPolling()
            let pollCount = 0
            const maxPolls = 60
            this.pollTimer = setInterval(async () => {
                pollCount++
                try {
                    const res = await aiTopupStatus(orderNo)
                    if (res && res.code === 200 && res.data) {
                        const status = res.data.status
                        if (status === 1) {
                            this.stopPolling()
                            this.order = Object.assign({}, this.order, res.data)
                            this.paying = false
                            this.currentPkg = ''
                            toast('支付成功，额度已到账！', 2)
                            this.loadQuota()
                        } else if (status === 2) {
                            this.stopPolling()
                            this.order = Object.assign({}, this.order, res.data)
                            this.paying = false
                            toast('订单已关闭，请重新下单', 2)
                        }
                    }
                } catch (e) {
                    // 轮询失败忽略，继续下一轮
                }
                if (pollCount >= maxPolls) {
                    this.stopPolling()
                    this.paying = false
                }
            }, 3000)
        },
        stopPolling() {
            if (this.pollTimer) {
                clearInterval(this.pollTimer)
                this.pollTimer = null
            }
        }
    }
}
</script>

<style lang="less" scoped>
.ai-quota-page {
    min-height: 100vh;
    background: #f4f5f7;
    padding-bottom: 80px;
}

.page-header {
    background: linear-gradient(135deg, #1E80FF 0%, #4A90FF 100%);
    padding: 40px 24px 32px;
    color: #fff;
}

.header-title {
    font-size: 32px;
    font-weight: 700;
    margin-bottom: 8px;
}

.header-subtitle {
    font-size: 14px;
    opacity: 0.85;
}

.page-body {
    max-width: 1024px;
    margin: 0 auto;
    padding: 24px;
    display: flex;
    flex-direction: column;
    gap: 16px;
}

.card {
    background: #fff;
    border-radius: 8px;
    padding: 20px;
}

.card-title {
    font-size: 16px;
    font-weight: 600;
    color: #252933;
    margin-bottom: 16px;
    padding-bottom: 12px;
    border-bottom: 1px solid #f0f1f5;
}

.overview-grid {
    display: flex;
    gap: 24px;
    flex-wrap: wrap;
}

.ov-item {
    flex: 1;
    min-width: 240px;
}

.ov-label {
    font-size: 13px;
    color: #8a919f;
    margin-bottom: 8px;
}

.ov-value .num {
    font-size: 36px;
    font-weight: 700;
    color: #1E80FF;
}

.ov-value.wallet .num {
    color: #F53F3F;
}

.ov-value .slash {
    font-size: 14px;
    color: #8a919f;
    margin-left: 4px;
}

.ov-bar {
    height: 6px;
    background: #eef1f5;
    border-radius: 3px;
    overflow: hidden;
    margin: 10px 0 6px;
}

.ov-bar-inner {
    height: 100%;
    background: linear-gradient(90deg, #1E80FF, #4A90FF);
    border-radius: 3px;
    transition: width .3s;
}

.ov-tip {
    font-size: 12px;
    color: #c0c4cc;
}

.ov-tip.gap {
    padding-top: 10px;
}

.pkg-grid {
    display: grid;
    grid-template-columns: repeat(3, 1fr);
    gap: 16px;
}

.pkg-item {
    border: 1px solid #f0f1f5;
    border-radius: 8px;
    padding: 20px;
    text-align: center;
    transition: border-color .2s, box-shadow .2s;
}

.pkg-item:hover {
    border-color: #1E80FF;
    box-shadow: 0 4px 16px rgba(30, 128, 255, .12);
}

.pkg-quota .pkg-num {
    font-size: 32px;
    font-weight: 700;
    color: #252933;
}

.pkg-unit {
    font-size: 14px;
    color: #8a919f;
}

.pkg-price {
    margin: 8px 0 4px;
}

.pkg-yen {
    font-size: 14px;
    color: #F53F3F;
}

.pkg-price .pkg-num {
    font-size: 24px;
    font-weight: 700;
    color: #F53F3F;
}

.pkg-unit-price {
    font-size: 12px;
    color: #8a919f;
    margin-bottom: 16px;
}

.pkg-buy {
    width: 100%;
    padding: 10px;
    border: none;
    border-radius: 6px;
    background: #1E80FF;
    color: #fff;
    font-size: 15px;
    font-weight: 600;
    cursor: pointer;
    transition: background-color .2s;
}

.pkg-buy:hover {
    background: #1a7de8;
}

.pkg-buy:disabled {
    background: #c9cdd4;
    cursor: not-allowed;
}

.pkg-note {
    margin-top: 14px;
    font-size: 12px;
    color: #c0c4cc;
    text-align: center;
}

.order-row {
    display: flex;
    justify-content: space-between;
    align-items: center;
    padding: 10px 0;
    font-size: 14px;
}

.row-label {
    color: #8a919f;
}

.row-value {
    color: #252933;
    word-break: break-all;
}

.row-value.status.pending {
    color: #ff9900;
}

.row-value.status.paid {
    color: #67c23a;
}

.row-value.status.closed {
    color: #909399;
}

.order-actions {
    display: flex;
    gap: 12px;
    margin-top: 12px;
    padding-top: 12px;
    border-top: 1px solid #f0f1f5;
}

.pay-btn {
    flex: 1;
    padding: 10px;
    background: #F53F3F;
    color: #fff;
    font-size: 15px;
    font-weight: 600;
    border: none;
    border-radius: 6px;
    cursor: pointer;
}

.pay-btn:disabled {
    opacity: .6;
    cursor: not-allowed;
}

.ghost-btn {
    flex: 1;
    padding: 10px;
    background: #f4f5f7;
    color: #515767;
    font-size: 14px;
    border: none;
    border-radius: 6px;
    cursor: pointer;
}

@media screen and (max-width: 768px) {
    .page-body {
        padding: 16px;
    }

    .pkg-grid {
        grid-template-columns: 1fr;
    }
}
</style>