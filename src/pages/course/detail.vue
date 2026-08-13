<template>
  <div class="course-detail-page" :class="{ 'is-desktop': isDesktop }">
    <div class="detail-header">
      <div class="header-bg"></div>
      <div class="header-content">
        <div class="course-cover-wrapper">
          <img :src="course.coverImage || '/static/images/avatar_head_1.png'" class="course-cover" />
          <div class="course-badge" v-if="course.price === 0">免费</div>
          <div class="course-badge discount" v-else-if="course.originalPrice > course.price">
            {{ Math.round((1 - course.price / course.originalPrice) * 100) }}%OFF
          </div>
        </div>
        <div class="course-meta">
          <div class="course-title">{{ course.title }}</div>
          <div class="course-subtitle">{{ course.subtitle }}</div>
          <div class="course-author">
            <img :src="course.authorAvatar || '/static/images/avatar_head_1.png'" class="author-avatar" />
            <div class="author-info">
              <div class="author-name">{{ course.authorName }}</div>
              <div class="author-label">作者</div>
            </div>
          </div>
          <div class="course-stats-row">
            <span class="stat-item">
              <span class="stat-value">{{ course.chapterCount }}</span>
              <span class="stat-label">小节</span>
            </span>
            <span class="stat-divider"></span>
            <span class="stat-item">
              <span class="stat-value">{{ course.studyCount }}</span>
              <span class="stat-label">人已购</span>
            </span>
            <span class="stat-divider"></span>
            <span class="stat-item">
              <span class="stat-value">{{ course.estimatedHours }}h</span>
              <span class="stat-label">预计阅读</span>
            </span>
          </div>
        </div>
      </div>
    </div>

    <div class="detail-body">
      <div class="detail-left">
        <div class="section">
          <div class="section-title">课程简介</div>
          <div class="section-content">
            {{ course.description }}
          </div>
        </div>

        <div class="section">
          <div class="section-title">课程目录</div>
          <div class="chapter-list">
            <div 
              v-for="chapter in chapters" 
              :key="chapter.id"
              class="chapter-item"
              :class="{ locked: !chapter.isFree && !isPurchased }"
              @click="handleChapterClick(chapter)"
            >
              <div class="chapter-left">
                <span class="chapter-number">{{ chapter.sortOrder }}</span>
                <span class="chapter-title">{{ chapter.title }}</span>
              </div>
              <div class="chapter-right">
                <span class="free-tag" v-if="chapter.isFree">免费</span>
                <span class="lock-icon" v-else-if="!isPurchased">&#xf023;</span>
                <span class="word-count">{{ chapter.wordCount }}字</span>
              </div>
            </div>
          </div>
        </div>
      </div>

      <div class="detail-right">
        <div class="purchase-card">
          <div class="price-section">
            <div class="price-row">
              <span class="current-price">¥{{ course.price }}</span>
              <span class="original-price" v-if="course.originalPrice > course.price">¥{{ course.originalPrice }}</span>
            </div>
            <div class="save-text" v-if="course.originalPrice > course.price">
              立省 ¥{{ (course.originalPrice - course.price).toFixed(0) }}
            </div>
          </div>

          <div class="action-buttons">
            <button 
              class="buy-btn"
              v-if="!isPurchased"
              @click="handleBuy"
            >
              立即购买
            </button>
            <button 
              class="read-btn"
              v-else
              @click="handleRead"
            >
              继续阅读
            </button>
          </div>

          <div class="purchase-info">
            <div class="info-item">
              <span class="info-icon">&#xf075;</span>
              <span class="info-text">支持7天无理由退款</span>
            </div>
            <div class="info-item">
              <span class="info-icon">&#xf02e;</span>
              <span class="info-text">永久有效，随时回看</span>
            </div>
            <div class="info-item">
              <span class="info-icon">&#xf121;</span>
              <span class="info-text">支持多端阅读</span>
            </div>
          </div>

          <div class="author-card">
            <img :src="course.authorAvatar || '/static/images/avatar_head_1.png'" class="author-avatar-lg" />
            <div class="author-info-lg">
              <div class="author-name-lg">{{ course.authorName }}</div>
              <div class="author-course-count">共{{ getAuthorCourseCount() }}门课程</div>
            </div>
          </div>
        </div>
      </div>
    </div>

    <div class="purchase-modal" v-if="showPurchaseModal" @click="closePurchaseModal">
      <div class="modal-content" @click.stop>
        <div class="modal-header">
          <div class="modal-title">确认购买</div>
          <span class="modal-close" @click="closePurchaseModal">&#10005;</span>
        </div>
        <div class="modal-body">
          <div class="order-info">
            <div class="order-item">
              <span class="order-label">课程名称</span>
              <span class="order-value">{{ course.title }}</span>
            </div>
            <div class="order-item">
              <span class="order-label">课程作者</span>
              <span class="order-value">{{ course.authorName }}</span>
            </div>
            <div class="order-item">
              <span class="order-label">课程价格</span>
              <span class="order-value price">¥{{ course.price }}</span>
            </div>
            <div class="discount-row">
              <el-input
                v-model="discountCode"
                placeholder="输入折扣码（选填）"
                size="small"
                class="discount-input"
                clearable
              />
            </div>
            <div class="order-total">
              <span class="total-label">应付金额</span>
              <span class="total-value">¥{{ finalPrice.toFixed(2) }}</span>
            </div>
            <div class="discount-info" v-if="discountInfo">
              <span class="discount-text">
                折扣码 {{ discountInfo.code }}：
                <template v-if="discountInfo.discountType === 1">-¥{{ discountInfo.discountValue }}</template>
                <template v-else>-{{ discountInfo.discountValue }}%</template>
              </span>
            </div>
          </div>
        </div>
        <div class="modal-footer">
          <button class="cancel-btn" @click="closePurchaseModal">取消</button>
          <button class="confirm-btn" @click="confirmPurchase">确认支付</button>
        </div>
      </div>
    </div>
  </div>
</template>

<script>
import { toast } from "@/utils/toast"
import Utils from '@/utils/env'
import courseApi from '@/apis/course'

export default {
  name: 'CourseDetailPage',
  data() {
    return {
      course: {},
      chapters: [],
      isPurchased: false,
      showPurchaseModal: false,
      discountCode: '',
      discountInfo: null,
      discountValidating: false,
      loading: true,
      paying: false
    }
  },
  computed: {
    isDesktop() {
      return Utils.isDesktop()
    },
    finalPrice() {
      if (!this.discountInfo) return this.course.price || 0
      const price = parseFloat(this.course.price) || 0
      if (this.discountInfo.discountType === 1) {
        // 固定金额
        return Math.max(0, price - parseFloat(this.discountInfo.discountValue))
      } else {
        // 百分比
        return Math.max(0, price * (1 - parseFloat(this.discountInfo.discountValue) / 100))
      }
    }
  },
  mounted() {
    this.loadCourseDetail()
    this.checkPurchaseStatus()
  },
  watch: {
    discountCode: {
      handler(val) {
        if (!val || val.trim() === '') {
          this.discountInfo = null
          return
        }
        this.validateDiscountCode(val.trim())
      },
      immediate: false
    }
  },
  methods: {
    async loadCourseDetail() {
      const courseId = parseInt(this.$route.params.id)
      this.loading = true
      try {
        const res = await courseApi.getCourseDetail({ courseId })
        if (res && res.code === 200 && res.data) {
          this.course = res.data.course || {}
          this.chapters = res.data.chapters || []
        }
      } catch (e) {
        console.error('加载课程详情失败', e)
        toast('加载课程失败', 2)
      } finally {
        this.loading = false
      }
    },
    async checkPurchaseStatus() {
      try {
        const res = await courseApi.getMyCourses({})
        if (res && res.code === 200 && res.data) {
          const list = res.data.list || []
          const courseId = parseInt(this.$route.params.id)
          this.isPurchased = list.some(c => c.id === courseId)
        }
      } catch (e) {
        // 未登录或请求失败，默认为未购买
        this.isPurchased = false
      }
    },
    getAuthorCourseCount() {
      return 0
    },
    handleChapterClick(chapter) {
      if (!chapter.isFree && !this.isPurchased) {
        toast('该章节需要购买后才能阅读', 2)
        return
      }
      this.$router.push(`/course/read/${chapter.id}`)
    },
    handleBuy() {
      if (!this.$store.getters.isLoggedIn) {
        this.$store.dispatch('showLogin')
        return
      }
      if (this.course.price === 0) {
        // 免费课程直接加入
        this.confirmFreeJoin()
        return
      }
      this.showPurchaseModal = true
    },
    closePurchaseModal() {
      this.showPurchaseModal = false
      this.discountCode = ''
      this.discountInfo = null
    },
    async confirmFreeJoin() {
      try {
        const res = await courseApi.createOrder({ courseId: parseInt(this.$route.params.id) })
        if (res && res.code === 200) {
          this.isPurchased = true
          toast('已加入课程', 2)
          const firstChapter = this.chapters[0]
          if (firstChapter) {
            setTimeout(() => this.$router.push(`/course/read/${firstChapter.id}`), 800)
          }
        }
      } catch (e) {
        toast('加入课程失败', 2)
      }
    },
    async validateDiscountCode(code) {
      if (this.discountValidating) return
      this.discountValidating = true
      try {
        const res = await courseApi.validateDiscount({
          code,
          courseId: parseInt(this.$route.params.id)
        })
        if (res && res.code === 200 && res.data) {
          this.discountInfo = res.data
        } else {
          this.discountInfo = null
        }
      } catch (e) {
        this.discountInfo = null
      } finally {
        this.discountValidating = false
      }
    },
    async confirmPurchase() {
      if (this.paying) return
      this.paying = true
      try {
        const res = await courseApi.createOrder({
          courseId: parseInt(this.$route.params.id),
          discountCode: this.discountCode || undefined
        })
        if (res && res.code === 200 && res.data) {
          this.showPurchaseModal = false
          // 跳转到订单详情页，由订单页发起支付并轮询订单状态
          this.$router.push(`/course/order/${res.data.orderNo}`)
        } else {
          toast(res.message || '创建订单失败', 2)
        }
      } catch (e) {
        toast('创建订单失败，请稍后重试', 2)
      } finally {
        this.paying = false
      }
    },
    handleRead() {
      const firstChapter = this.chapters[0]
      if (firstChapter) {
        this.$router.push(`/course/read/${firstChapter.id}`)
      }
    }
  }
}
</script>

<style lang="less" scoped>
@import '../../styles/common';

.course-detail-page {
  min-height: 100vh;
  background-color: #f4f5f7;

  &.is-desktop {
    background: transparent;
    min-height: auto;
  }
}

.detail-header {
  position: relative;
  padding-bottom: 32px;
}

.header-bg {
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  height: 200px;
  background: linear-gradient(135deg, #1E80FF 0%, #4A90FF 100%);
}

.header-content {
  position: relative;
  display: flex;
  gap: 20px;
  padding: 24px;
  max-width: 1280px;
  margin: 0 auto;
}

.course-cover-wrapper {
  position: relative;
  flex-shrink: 0;
}

.course-cover {
  width: 280px;
  height: 168px;
  border-radius: 8px;
  object-fit: cover;
  box-shadow: 0 4px 16px rgba(0,0,0,0.15);
}

.course-badge {
  position: absolute;
  top: -8px;
  right: -8px;
  padding: 4px 12px;
  background-color: #F53F3F;
  color: #fff;
  font-size: 12px;
  font-weight: 600;
  border-radius: 12px;
}

.course-badge.discount {
  background-color: #FF7D00;
}

.course-meta {
  flex: 1;
  color: #fff;
}

.course-title {
  font-size: 28px;
  font-weight: 700;
  margin-bottom: 8px;
}

.course-subtitle {
  font-size: 16px;
  opacity: 0.85;
  margin-bottom: 16px;
}

.course-author {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 16px;
}

.author-avatar {
  width: 40px;
  height: 40px;
  border-radius: 50%;
  object-fit: cover;
  border: 2px solid rgba(255,255,255,0.5);
}

.author-info {
  display: flex;
  flex-direction: column;
}

.author-name {
  font-size: 14px;
  font-weight: 500;
}

.author-label {
  font-size: 12px;
  opacity: 0.7;
}

.course-stats-row {
  display: flex;
  align-items: center;
  gap: 20px;
}

.stat-item {
  display: flex;
  flex-direction: column;
  align-items: center;
}

.stat-value {
  font-size: 20px;
  font-weight: 700;
}

.stat-label {
  font-size: 12px;
  opacity: 0.7;
}

.stat-divider {
  width: 1px;
  height: 30px;
  background-color: rgba(255,255,255,0.3);
}

.detail-body {
  display: flex;
  gap: 24px;
  padding: 24px;
  max-width: 1280px;
  margin: 0 auto;
}

.detail-left {
  flex: 1;
  background-color: #fff;
  border-radius: 8px;
  padding: 24px;
}

.detail-right {
  width: 280px;
  flex-shrink: 0;
  position: sticky;
  top: 80px;
}

.section {
  margin-bottom: 24px;
}

.section-title {
  font-size: 18px;
  font-weight: 600;
  color: #252933;
  margin-bottom: 16px;
  padding-bottom: 8px;
  border-bottom: 1px solid #f0f1f5;
}

.section-content {
  font-size: 15px;
  color: #515767;
  line-height: 1.8;
}

.chapter-list {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.chapter-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 16px;
  border-radius: 8px;
  cursor: pointer;
  transition: background-color 0.2s;
}

.chapter-item:hover {
  background-color: #f5f7fa;
}

.chapter-item.locked {
  opacity: 0.6;
}

.chapter-left {
  display: flex;
  align-items: center;
  gap: 12px;
  flex: 1;
}

.chapter-number {
  width: 28px;
  height: 28px;
  display: flex;
  align-items: center;
  justify-content: center;
  background-color: #f5f7fa;
  color: #8a919f;
  font-size: 13px;
  font-weight: 500;
  border-radius: 6px;
  flex-shrink: 0;
}

.chapter-title {
  font-size: 14px;
  color: #252933;
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.chapter-right {
  display: flex;
  align-items: center;
  gap: 8px;
}

.free-tag {
  padding: 2px 8px;
  background-color: #EAF2FF;
  color: #1E80FF;
  font-size: 12px;
  border-radius: 4px;
}

.lock-icon {
  font-family: fontawesome;
  font-size: 14px;
  color: #c0c4cc;
}

.word-count {
  font-size: 13px;
  color: #8a919f;
}

.purchase-card {
  background-color: #fff;
  border-radius: 8px;
  padding: 20px;
}

.price-section {
  margin-bottom: 16px;
}

.price-row {
  display: flex;
  align-items: baseline;
  gap: 8px;
}

.current-price {
  font-size: 28px;
  font-weight: 700;
  color: #F53F3F;
}

.original-price {
  font-size: 16px;
  color: #c0c4cc;
  text-decoration: line-through;
}

.save-text {
  font-size: 13px;
  color: #F53F3F;
  margin-top: 4px;
}

.action-buttons {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-bottom: 16px;
}

.buy-btn {
  padding: 12px;
  background-color: #F53F3F;
  color: #fff;
  font-size: 16px;
  font-weight: 600;
  border: none;
  border-radius: 8px;
  cursor: pointer;
  transition: background-color 0.2s;
}

.buy-btn:hover {
  background-color: #E53935;
}

.read-btn {
  padding: 12px;
  background-color: #1E80FF;
  color: #fff;
  font-size: 16px;
  font-weight: 600;
  border: none;
  border-radius: 8px;
  cursor: pointer;
  transition: background-color 0.2s;
}

.read-btn:hover {
  background-color: #1a7de8;
}

.purchase-info {
  display: flex;
  flex-direction: column;
  gap: 10px;
  margin-bottom: 16px;
  padding-bottom: 16px;
  border-bottom: 1px solid #f0f1f5;
}

.info-item {
  display: flex;
  align-items: center;
  gap: 8px;
}

.info-icon {
  font-family: fontawesome;
  font-size: 14px;
  color: #c0c4cc;
}

.info-text {
  font-size: 13px;
  color: #8a919f;
}

.author-card {
  display: flex;
  align-items: center;
  gap: 12px;
}

.author-avatar-lg {
  width: 50px;
  height: 50px;
  border-radius: 50%;
  object-fit: cover;
}

.author-info-lg {
  display: flex;
  flex-direction: column;
}

.author-name-lg {
  font-size: 14px;
  font-weight: 500;
  color: #252933;
}

.author-course-count {
  font-size: 12px;
  color: #8a919f;
}

.purchase-modal {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background-color: rgba(0,0,0,0.5);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 500;
}

.modal-content {
  width: 90%;
  max-width: 420px;
  background-color: #fff;
  border-radius: 12px;
  overflow: hidden;
}

.modal-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16px 20px;
  border-bottom: 1px solid #f0f1f5;
}

.modal-title {
  font-size: 18px;
  font-weight: 600;
  color: #252933;
}

.modal-close {
  font-size: 20px;
  color: #c0c4cc;
  cursor: pointer;
}

.modal-body {
  padding: 20px;
}

.order-info {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.order-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.order-label {
  font-size: 14px;
  color: #8a919f;
}

.order-value {
  font-size: 14px;
  color: #252933;
}

.order-value.price {
  color: #F53F3F;
  font-weight: 600;
}

.discount-row {
  padding: 8px 0;
}

.discount-input {
  width: 100%;
}

.order-total {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding-top: 12px;
  margin-top: 12px;
  border-top: 1px solid #f0f1f5;
}

.total-label {
  font-size: 16px;
  color: #252933;
  font-weight: 500;
}

.total-value {
  font-size: 24px;
  color: #F53F3F;
  font-weight: 700;
}

.modal-footer {
  display: flex;
  gap: 12px;
  padding: 16px 20px;
  border-top: 1px solid #f0f1f5;
}

.cancel-btn {
  flex: 1;
  padding: 12px;
  background-color: #f4f5f7;
  color: #515767;
  font-size: 16px;
  font-weight: 500;
  border: none;
  border-radius: 8px;
  cursor: pointer;
}

.confirm-btn {
  flex: 1;
  padding: 12px;
  background-color: #F53F3F;
  color: #fff;
  font-size: 16px;
  font-weight: 600;
  border: none;
  border-radius: 8px;
  cursor: pointer;
}

@media screen and (max-width: 768px) {
  .header-content {
    flex-direction: column;
  }

  .course-cover {
    width: 100%;
    height: auto;
    max-height: 200px;
  }

  .detail-body {
    flex-direction: column;
  }

  .detail-right {
    width: 100%;
    position: static;
  }
}
</style>