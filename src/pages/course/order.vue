<template>
  <div class="order-page" :class="{ 'is-desktop': isDesktop }">
    <div class="order-header">
      <div class="header-title">订单详情</div>
      <div class="header-subtitle">确认订单信息并完成支付</div>
    </div>

    <div class="order-body">
      <div class="order-main">
        <!-- 支付成功提示 -->
        <div class="success-banner" v-if="order.status === 1">
          <span class="success-icon">&#xf058;</span>
          <div class="success-text">
            <div class="success-title">支付成功</div>
            <div class="success-sub">课程已开通，祝你学习愉快</div>
          </div>
        </div>

        <!-- 课程信息卡片 -->
        <div class="card course-card" v-if="course.id">
          <div class="card-title">课程信息</div>
          <div class="course-row">
            <img :src="course.coverImage || defaultCover" class="course-cover" />
            <div class="course-info">
              <div class="course-title">{{ course.title }}</div>
              <div class="course-subtitle" v-if="course.subtitle">{{ course.subtitle }}</div>
              <div class="course-author">
                <img :src="course.authorAvatar || defaultCover" class="author-avatar" />
                <span class="author-name">{{ course.authorName }}</span>
              </div>
              <div class="course-chapter" v-if="course.chapterCount">
                <span class="icon">&#xf02d;</span>
                共 {{ course.chapterCount }} 节
              </div>
            </div>
          </div>
        </div>

        <!-- 订单信息卡片 -->
        <div class="card order-card" v-if="order.orderNo">
          <div class="card-title">订单信息</div>
          <div class="order-row">
            <span class="row-label">订单编号</span>
            <span class="row-value">{{ order.orderNo }}</span>
          </div>
          <div class="order-row">
            <span class="row-label">订单状态</span>
            <span class="row-value status" :class="statusClass">{{ statusText }}</span>
          </div>
          <div class="order-row">
            <span class="row-label">支付方式</span>
            <span class="row-value">支付宝</span>
          </div>
          <div class="order-row">
            <span class="row-label">下单时间</span>
            <span class="row-value">{{ formatTime(order.createdTime) }}</span>
          </div>
          <div class="order-row" v-if="order.payTime">
            <span class="row-label">支付时间</span>
            <span class="row-value">{{ formatTime(order.payTime) }}</span>
          </div>
        </div>

        <!-- 金额明细卡片 -->
        <div class="card amount-card" v-if="order.orderNo">
          <div class="card-title">金额明细</div>
          <div class="amount-row">
            <span class="row-label">课程价格</span>
            <span class="row-value">¥{{ formatAmount(order.originalAmount) }}</span>
          </div>
          <div class="amount-row" v-if="hasDiscount">
            <span class="row-label">
              优惠
              <span class="discount-code" v-if="order.discountCode">({{ order.discountCode }})</span>
            </span>
            <span class="row-value discount">-¥{{ formatAmount(order.discountAmount) }}</span>
          </div>
          <div class="amount-total">
            <span class="total-label">实付金额</span>
            <span class="total-value">¥{{ formatAmount(order.paidAmount) }}</span>
          </div>
        </div>
      </div>

      <!-- 右侧操作区 -->
      <div class="order-side" v-if="order.orderNo">
        <div class="pay-card">
          <div class="pay-amount">
            <span class="pay-label">应付金额</span>
            <span class="pay-value">¥{{ formatAmount(order.paidAmount) }}</span>
          </div>
          <div class="pay-tip" v-if="order.status === 0 || order.status === 4">
            {{ order.status === 4 ? '订单正在处理支付，请在新窗口完成支付宝支付；若已关闭可点击重新发起' : '请使用支付宝扫码或账号完成支付，支付完成后将自动开通课程' }}
          </div>
          <button class="pay-btn" v-if="order.status === 0 || order.status === 4" :disabled="paying" @click="handlePay">
            {{ paying ? '支付中...' : '立即支付' }}
          </button>
          <button class="read-btn" v-else-if="order.status === 1" @click="goRead">
            继续阅读
          </button>
          <button class="cancel-btn" @click="goCourse">
            返回课程详情
          </button>
          <div class="pay-note">
            <span class="note-item">&#xf058; 支持7天无理由退款</span>
            <span class="note-item">&#xf017; 永久有效，随时回看</span>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script>
import { toast } from "@/utils/toast"
import Utils from '@/utils/env'
import courseApi from '@/apis/course'

const STATUS_MAP = {
  0: { text: '待支付', cls: 'pending' },
  1: { text: '已支付', cls: 'paid' },
  2: { text: '已取消', cls: 'cancelled' },
  3: { text: '已退款', cls: 'refunded' },
  4: { text: '支付处理中', cls: 'processing' }
}

export default {
  name: 'CourseOrderPage',
  data() {
    return {
      order: {},
      course: {},
      paying: false,
      defaultCover: '/static/images/avatar_head_1.png'
    }
  },
  computed: {
    isDesktop() {
      return Utils.isDesktop()
    },
    statusText() {
      const s = STATUS_MAP[this.order.status]
      return s ? s.text : '未知'
    },
    statusClass() {
      const s = STATUS_MAP[this.order.status]
      return s ? s.cls : ''
    },
    hasDiscount() {
      const discount = parseFloat(this.order.discountAmount) || 0
      return discount > 0
    }
  },
  mounted() {
    this.loadOrder()
  },
  beforeDestroy() {
    this.stopPolling()
  },
  methods: {
    async loadOrder() {
      const orderNo = this.$route.params.orderNo
      try {
        const res = await courseApi.getOrderStatus(orderNo)
        if (res && res.code === 200 && res.data) {
          this.order = res.data
          // 待支付/支付处理中的订单进入页面后自动开始轮询
          if (this.order.status === 0 || this.order.status === 4) {
            this.startPolling()
          }
          this.loadCourse()
        } else {
          toast('订单不存在', 2)
        }
      } catch (e) {
        toast('加载订单失败', 2)
      }
    },
    async loadCourse() {
      if (!this.order.courseId) return
      try {
        const res = await courseApi.getCourseDetail({ courseId: this.order.courseId })
        if (res && res.code === 200 && res.data) {
          this.course = res.data.course || {}
        }
      } catch (e) {
        // 课程信息加载失败不影响订单展示
      }
    },
    async handlePay() {
      if (this.paying) return
      this.paying = true
      try {
        // 先去支付准备：后端原子置为「支付处理中」并核验折扣码/5折券有效性
        const prep = await courseApi.preparePay(this.order.orderNo)
        if (prep && prep.code === 200 && prep.data) {
          this.order = prep.data
          // 新开标签页跳转到支付页（支付宝收银台）
          const payUrl = courseApi.getPayPageUrl(this.order.orderNo)
          window.open(payUrl, '_blank')
          // 打开支付页后开始轮询订单状态
          this.startPolling()
        } else {
          // 订单已关闭/券码失效：提示并刷新订单状态，不跳转支付页
          const msg = (prep && prep.message) || '订单已处理，请重新下单'
          toast(msg, 2)
          this.loadOrder()
        }
      } catch (e) {
        toast('发起支付失败，请重试', 2)
      } finally {
        this.paying = false
      }
    },
    startPolling() {
      this.stopPolling()
      let pollCount = 0
      const maxPolls = 60
      this.pollTimer = setInterval(async () => {
        pollCount++
        try {
          const res = await courseApi.getOrderStatus(this.order.orderNo)
          if (res && res.code === 200 && res.data) {
            const status = res.data.status
            if (status === 1) {
              // 支付成功
              this.stopPolling()
              this.order = res.data
              toast('支付成功，课程已开通！', 2)
            } else if (status === 2 || status === 3) {
              // 已取消或已退款
              this.stopPolling()
              this.order = res.data
              toast(status === 2 ? '订单已取消' : '订单已退款', 2)
            }
          }
        } catch (e) {
          // 忽略轮询错误
        }
        if (pollCount >= maxPolls) {
          this.stopPolling()
        }
      }, 3000)
    },
    stopPolling() {
      if (this.pollTimer) {
        clearInterval(this.pollTimer)
        this.pollTimer = null
      }
    },
    goRead() {
      const firstChapter = this.course.chapters && this.course.chapters[0]
      if (firstChapter) {
        this.$router.push(`/course/read/${firstChapter.id}`)
      } else {
        this.goCourse()
      }
    },
    goCourse() {
      this.$router.push(`/course/${this.order.courseId}`)
    },
    formatTime(time) {
      if (!time) return ''
      const d = new Date(time)
      if (isNaN(d.getTime())) return ''
      const pad = (n) => (n < 10 ? '0' + n : '' + n)
      return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
    },
    formatAmount(amount) {
      const val = parseFloat(amount)
      if (isNaN(val)) return '0.00'
      return val.toFixed(2)
    }
  }
}
</script>

<style lang="less" scoped>
@import '../../styles/common';

.order-page {
  min-height: 100vh;
  background-color: #f4f5f7;
  padding-bottom: 120px;
}

.order-header {
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

.order-body {
  display: flex;
  gap: 24px;
  padding: 24px;
  max-width: 1024px;
  margin: 0 auto;
  align-items: flex-start;
}

.order-main {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.order-side {
  width: 300px;
  flex-shrink: 0;
  position: sticky;
  top: 80px;
}

.card {
  background-color: #fff;
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

.success-banner {
  display: flex;
  align-items: center;
  gap: 12px;
  background-color: #f0f9eb;
  border: 1px solid #e1f3d8;
  border-radius: 8px;
  padding: 16px 20px;
}

.success-icon {
  font-family: fontawesome;
  font-size: 28px;
  color: #67c23a;
}

.success-title {
  font-size: 16px;
  font-weight: 600;
  color: #67c23a;
}

.success-sub {
  font-size: 13px;
  color: #8a919f;
  margin-top: 2px;
}

.course-row {
  display: flex;
  gap: 16px;
}

.course-cover {
  width: 160px;
  height: 96px;
  border-radius: 6px;
  object-fit: cover;
  flex-shrink: 0;
}

.course-info {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.course-title {
  font-size: 17px;
  font-weight: 600;
  color: #252933;
}

.course-subtitle {
  font-size: 13px;
  color: #8a919f;
}

.course-author {
  display: flex;
  align-items: center;
  gap: 6px;
}

.author-avatar {
  width: 20px;
  height: 20px;
  border-radius: 50%;
}

.author-name {
  font-size: 13px;
  color: #515767;
}

.course-chapter {
  font-size: 13px;
  color: #8a919f;
}

.course-chapter .icon {
  font-family: fontawesome;
  margin-right: 4px;
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
}

.row-value.status.pending {
  color: #ff9900;
}

.row-value.status.paid {
  color: #67c23a;
}

.row-value.status.cancelled,
.row-value.status.refunded {
  color: #909399;
}

.row-value.status.processing {
  color: #1e80ff;
}

.amount-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 10px 0;
  font-size: 14px;
}

.discount-code {
  color: #c0c4cc;
  font-size: 12px;
}

.row-value.discount {
  color: #ff9900;
}

.amount-total {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding-top: 14px;
  margin-top: 6px;
  border-top: 1px solid #f0f1f5;
}

.total-label {
  font-size: 15px;
  font-weight: 500;
  color: #252933;
}

.total-value {
  font-size: 24px;
  font-weight: 700;
  color: #F53F3F;
}

.pay-card {
  background-color: #fff;
  border-radius: 8px;
  padding: 20px;
}

.pay-amount {
  display: flex;
  flex-direction: column;
  gap: 4px;
  margin-bottom: 12px;
}

.pay-label {
  font-size: 13px;
  color: #8a919f;
}

.pay-value {
  font-size: 32px;
  font-weight: 700;
  color: #F53F3F;
}

.pay-tip {
  font-size: 13px;
  color: #8a919f;
  line-height: 1.6;
  margin-bottom: 16px;
}

.pay-btn {
  width: 100%;
  padding: 12px;
  background-color: #F53F3F;
  color: #fff;
  font-size: 16px;
  font-weight: 600;
  border: none;
  border-radius: 8px;
  cursor: pointer;
  margin-bottom: 12px;
  transition: background-color 0.2s;
}

.pay-btn:hover {
  background-color: #E53935;
}

.pay-btn:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.read-btn {
  width: 100%;
  padding: 12px;
  background-color: #1E80FF;
  color: #fff;
  font-size: 16px;
  font-weight: 600;
  border: none;
  border-radius: 8px;
  cursor: pointer;
  margin-bottom: 12px;
  transition: background-color 0.2s;
}

.read-btn:hover {
  background-color: #1a7de8;
}

.cancel-btn {
  width: 100%;
  padding: 12px;
  background-color: #f4f5f7;
  color: #515767;
  font-size: 15px;
  font-weight: 500;
  border: none;
  border-radius: 8px;
  cursor: pointer;
  margin-bottom: 16px;
}

.pay-note {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding-top: 12px;
  border-top: 1px solid #f0f1f5;
}

.note-item {
  font-family: fontawesome, sans-serif;
  font-size: 12px;
  color: #c0c4cc;
}

@media screen and (max-width: 768px) {
  .order-body {
    flex-direction: column;
    padding: 16px;
  }

  .order-side {
    width: 100%;
    position: static;
  }

  .course-cover {
    width: 120px;
    height: 72px;
  }
}
</style>
