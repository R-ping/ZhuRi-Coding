<template>
  <div class="course-detail-page" :class="{ 'is-desktop': isDesktop }">
    <!-- ===== 上部：头部（付费/免费区分） ===== -->
    <div class="detail-header" :class="{ 'is-free': isFree }">
      <div class="header-inner">
        <div class="course-cover-wrapper">
          <img :src="course.coverImage || '/static/images/avatar_head_1.png'" class="course-cover" />
          <div class="course-badge" v-if="isFree">免费</div>
          <div class="course-badge discount" v-else-if="hasDiscount">
            {{ Math.round((1 - course.price / course.originalPrice) * 100) }}%OFF
          </div>
        </div>

        <div class="course-meta">
          <h1 class="course-title">{{ course.title }}</h1>
          <div class="course-subtitle" v-if="course.subtitle">{{ course.subtitle }}</div>

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
              <span class="stat-label">人已读</span>
            </span>
            <span class="stat-divider"></span>
            <span class="stat-item">
              <span class="stat-value">{{ course.estimatedHours }}h</span>
              <span class="stat-label">预计阅读</span>
            </span>
          </div>
        </div>

        <!-- 价格/操作区：付费与免费在此区分 -->
        <div class="header-action">
          <div class="price-section">
            <div class="price-label">{{ isFree ? '免费' : '价格' }}</div>
            <div class="price-row" v-if="!isFree">
              <span class="current-price">¥{{ course.price }}</span>
              <span class="original-price" v-if="course.originalPrice > course.price">¥{{ course.originalPrice }}</span>
            </div>
            <div class="save-text" v-else-if="hasDiscount">
              立省 ¥{{ (course.originalPrice - course.price).toFixed(0) }}
            </div>
            <div class="free-tip" v-if="isFree">免费小册，登录即可阅读</div>
          </div>

          <div class="action-buttons">
            <!-- 已购买：继续阅读 -->
            <button class="read-btn" v-if="isPurchased" @click="handleRead">
              继续阅读
            </button>
            <!-- 未购买：免费小册走免费加入；付费小册提供"立即购买 + 免费试读" -->
            <template v-else>
              <div class="buy-row" v-if="!isFree">
                <button class="buy-btn" @click="handleBuy">
                  立即购买
                </button>
                <button class="trial-btn" @click="handleFreeTrial">
                  免费试读
                </button>
              </div>
              <button v-else class="buy-btn free-btn" @click="handleBuy">
                免费阅读
              </button>
            </template>
          </div>

          <div class="purchase-info">
            <div class="info-item" v-if="!isFree">
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
        </div>
      </div>
    </div>

    <!-- ===== 下部：左侧介绍/目录 + 右侧推荐小册 ===== -->
    <div class="detail-body">
      <div class="detail-left">
        <!-- 简介 / 目录 分栏 -->
        <div class="tabs-bar">
          <div class="tab-item" :class="{ active: activeTab === 'intro' }" @click="activeTab = 'intro'">小册简介</div>
          <div class="tab-item" :class="{ active: activeTab === 'catalog' }" @click="activeTab = 'catalog'">
            目录
            <span class="tab-count">{{ chapters.length }}</span>
          </div>
        </div>

        <!-- 简介 -->
        <div v-show="activeTab === 'intro'" class="section-panel intro-panel">
          <div class="section-content">{{ course.description }}</div>
        </div>

        <!-- 目录 -->
        <div v-show="activeTab === 'catalog'" class="section-panel">
          <div class="chapter-list">
            <div
              v-for="chapter in chapters"
              :key="chapter.id"
              class="chapter-item"
              :class="{ locked: !isFree && !chapter.isFree && !isPurchased }"
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

      <!-- 右侧：推荐小册 -->
      <div class="detail-right">
        <div class="recommend-title">推荐小册</div>
        <div class="recommend-list" v-loading="recommendLoading">
          <div
            v-for="rec in recommendList"
            :key="rec.id"
            class="recommend-item"
            :class="{ free: Number(rec.price) <= 0 }"
            @click="goDetail(rec.id)"
          >
            <img :src="rec.coverImage || '/static/images/avatar_head_1.png'" class="recommend-cover" />
            <div class="recommend-info">
              <div class="recommend-name">{{ rec.title }}</div>
              <div class="recommend-meta">
                <span v-if="Number(rec.price) > 0" class="recommend-price">¥{{ rec.price }}</span>
                <span v-else class="recommend-free">免费</span>
                <span class="recommend-count">{{ rec.studyCount }}人学</span>
              </div>
            </div>
          </div>
          <div v-if="!recommendLoading && recommendList.length === 0" class="recommend-empty">暂无推荐</div>
        </div>
      </div>
    </div>

    <!-- ===== 购买弹窗 ===== -->
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
      paying: false,
      // 简介/目录分栏 + 推荐小册
      activeTab: 'intro',
      recommendList: [],
      recommendLoading: false
    }
  },
  computed: {
    isDesktop() {
      return Utils.isDesktop()
    },
    // 是否为免费小册（价格为 0 即免费）
    isFree() {
      return Number(this.course.price) <= 0
    },
    hasDiscount() {
      return !this.isFree && Number(this.course.originalPrice) > Number(this.course.price)
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
      // 雪花ID超过 JS Number 安全范围，必须保留字符串形式，避免 parseInt 精度丢失
      const courseId = this.$route.params.id
      this.loading = true
      try {
        const res = await courseApi.getCourseDetail({ courseId })
        if (res && res.code === 200 && res.data) {
          this.course = res.data.course || {}
          this.chapters = res.data.chapters || []
          this.loadRecommendList()
        }
      } catch (e) {
        console.error('加载课程详情失败', e)
        toast('加载课程失败', 2)
      } finally {
        this.loading = false
      }
    },
    async loadRecommendList() {
      this.recommendLoading = true
      try {
        const res = await courseApi.getCourseList({ page: 1, size: 8 })
        if (res && res.code === 200 && res.data) {
          const list = res.data.list || []
          const courseId = this.$route.params.id
          // 过滤掉当前小册，优先取相同分类
          const others = list.filter(c => String(c.id) !== String(courseId))
          const sameCate = others.filter(c => c.categoryId === this.course.categoryId)
          this.recommendList = (sameCate.length > 0 ? sameCate : others).slice(0, 4)
        }
      } catch (e) {
        this.recommendList = []
      } finally {
        this.recommendLoading = false
      }
    },
    async checkPurchaseStatus() {
      try {
        const res = await courseApi.getMyCourses({})
        if (res && res.code === 200 && res.data) {
          const list = res.data.list || []
          const courseId = this.$route.params.id
          this.isPurchased = list.some(c => c.id === courseId)
        }
      } catch (e) {
        // 未登录或请求失败，默认为未购买
        this.isPurchased = false
      }
    },
    goDetail(id) {
      this.$router.push(`/course/${id}`)
    },
    handleChapterClick(chapter) {
      // 免费小册整本可读；付费小册需免费章节或已购买
      if (!this.isFree && !chapter.isFree && !this.isPurchased) {
        toast('该章节需要购买后才能阅读', 2)
        return
      }
      this.$router.push(`/course/read/${chapter.id}`)
    },
    handleBuy() {
      if (Number(this.course.price) === 0) {
        // 免费课程无需加入/下单，未登录用户也可直接阅读
        this.handleRead()
        return
      }
      if (!this.$store.getters.isLoggedIn) {
        this.$store.dispatch('showLogin')
        return
      }
      this.showPurchaseModal = true
    },
    closePurchaseModal() {
      this.showPurchaseModal = false
      this.discountCode = ''
      this.discountInfo = null
    },
    // 付费课程免费试读：跳转目录中第一个可免费试读的章节（isFree === 1）
    handleFreeTrial() {
      const trialChapter = this.chapters.find(c => c.isFree === 1)
      if (!trialChapter) {
        toast('该小册暂无可试读章节', 2)
        return
      }
      this.$router.push(`/course/read/${trialChapter.id}`)
    },
    async validateDiscountCode(code) {
      if (this.discountValidating) return
      this.discountValidating = true
      try {
        const res = await courseApi.validateDiscount({
          code,
          courseId: this.$route.params.id
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
          courseId: this.$route.params.id,
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

/* ===== 整体上下布局 ===== */
.course-detail-page {
  min-height: 100vh;
  background-color: #f4f5f7;

  &.is-desktop {
    background: transparent;
    min-height: auto;
  }
}

/* ===== 上部头部 ===== */
.detail-header {
  background: #fff;
  border-bottom: 1px solid #f0f1f5;
  margin-bottom: 24px;

  // 免费小册头部用绿色基调与付费区分
  &.is-free {
    background: linear-gradient(135deg, #00b96b 0%, #00c581 100%);
    .course-meta,
    .author-label,
    .stat-label { color: #fff; }
    .course-meta { color: #fff; }
    .course-title,
    .course-subtitle { color: #fff; }
  }
}

.header-inner {
  display: flex;
  gap: 24px;
  max-width: 1280px;
  margin: 0 auto;
  padding: 32px 24px;
}

.course-cover-wrapper {
  position: relative;
  flex-shrink: 0;
}

.course-cover {
  width: 260px;
  height: 156px;
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
  color: #252933;
}

.course-title {
  font-size: 26px;
  font-weight: 700;
  color: #252933;
  margin-bottom: 8px;
  line-height: 1.4;
}

.course-subtitle {
  font-size: 15px;
  color: #515767;
  margin-bottom: 16px;
  line-height: 1.6;
}

.course-author {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 16px;
}

.author-avatar {
  width: 38px;
  height: 38px;
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
  color: #252933;
}

.author-label {
  font-size: 12px;
  color: #8a919f;
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
  color: #252933;
}

.stat-label {
  font-size: 12px;
  color: #8a919f;
}

.stat-divider {
  width: 1px;
  height: 30px;
  background-color: #e0e3e8;
}

/* 头部价格/操作区 */
.header-action {
  width: 260px;
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  justify-content: center;
  gap: 14px;
  padding-left: 24px;
  border-left: 1px solid #f0f1f5;
}

.price-label {
  font-size: 13px;
  color: #8a919f;
}

.price-row {
  display: flex;
  align-items: baseline;
  gap: 8px;
}

.current-price {
  font-size: 30px;
  font-weight: 700;
  color: #F53F3F;
}

.original-price {
  font-size: 15px;
  color: #c0c4cc;
  text-decoration: line-through;
}

.free-tip {
  font-size: 13px;
  color: #00b96b;
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
}

/* 付费：立即购买 + 免费试读 并排一行 */
.buy-row {
  display: flex;
  gap: 8px;
}

.buy-row .buy-btn {
  flex: 1.4;
}

.trial-btn {
  flex: 1;
  padding: 12px;
  background-color: #EAF2FF;
  color: #1E80FF;
  font-size: 16px;
  font-weight: 600;
  border: none;
  border-radius: 8px;
  cursor: pointer;
  transition: background-color 0.2s;
}

.trial-btn:hover { background-color: #d6e8ff; }

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

.buy-btn:hover { background-color: #E53935; }

.buy-btn.free-btn {
  background-color: #00b96b;
}
.buy-btn.free-btn:hover { background-color: #00a85f; }

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

.read-btn:hover { background-color: #1a7de8; }

.purchase-info {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.info-item {
  display: flex;
  align-items: center;
  gap: 8px;
}

.info-icon {
  font-family: fontawesome;
  font-size: 13px;
  color: #8a919f;
}

.info-text {
  font-size: 13px;
  color: #8a919f;
}

/* ===== 下部左右布局 ===== */
.detail-body {
  display: flex;
  gap: 24px;
  max-width: 1280px;
  margin: 0 auto;
  padding: 0 24px 32px;
}

.detail-left {
  flex: 1;
  min-width: 0;
  background-color: #fff;
  border-radius: 8px;
  overflow: hidden;
}

/* 简介/目录分栏 */
.tabs-bar {
  display: flex;
  gap: 24px;
  padding: 0 24px;
  border-bottom: 1px solid #f0f1f5;
}

.tab-item {
  position: relative;
  padding: 16px 0;
  font-size: 16px;
  font-weight: 500;
  color: #515767;
  cursor: pointer;
  transition: color 0.2s;
}

.tab-item.active {
  color: #1E80FF;
  font-weight: 600;
}

.tab-item.active::after {
  content: '';
  position: absolute;
  left: 0;
  right: 0;
  bottom: -1px;
  height: 2px;
  background-color: #1E80FF;
}

.tab-count {
  display: inline-block;
  margin-left: 4px;
  padding: 0 6px;
  background-color: #f0f1f5;
  color: #8a919f;
  font-size: 12px;
  border-radius: 8px;
}

.section-panel {
  padding: 24px;
}

.section-content {
  font-size: 15px;
  color: #515767;
  line-height: 1.8;
}

/* 目录 */
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

.chapter-item:hover { background-color: #f5f7fa; }
.chapter-item.locked { opacity: 0.6; }

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

/* ===== 右侧推荐小册 ===== */
.detail-right {
  width: 280px;
  flex-shrink: 0;
  position: sticky;
  top: 80px;
  height: fit-content;
}

.recommend-title {
  font-size: 16px;
  font-weight: 600;
  color: #252933;
  margin-bottom: 16px;
}

.recommend-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.recommend-item {
  display: flex;
  gap: 12px;
  padding: 12px;
  background-color: #fff;
  border-radius: 8px;
  cursor: pointer;
  transition: box-shadow 0.2s, transform 0.2s;
}

.recommend-item:hover {
  box-shadow: 0 4px 12px rgba(0,0,0,0.08);
  transform: translateY(-1px);
}

.recommend-cover {
  width: 120px;
  height: 72px;
  border-radius: 6px;
  object-fit: cover;
  flex-shrink: 0;
}

.recommend-info {
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  min-width: 0;
}

.recommend-name {
  font-size: 14px;
  font-weight: 500;
  color: #252933;
  line-height: 1.4;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.recommend-meta {
  display: flex;
  align-items: center;
  gap: 8px;
}

.recommend-price { color: #F53F3F; font-size: 14px; font-weight: 600; }
.recommend-free { color: #00b96b; font-size: 13px; font-weight: 500; }
.recommend-count { font-size: 12px; color: #8a919f; }

.recommend-empty {
  padding: 24px;
  text-align: center;
  background-color: #fff;
  border-radius: 8px;
  color: #8a919f;
  font-size: 14px;
}

/* ===== 购买弹窗 ===== */
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

.modal-title { font-size: 18px; font-weight: 600; color: #252933; }
.modal-close { font-size: 20px; color: #c0c4cc; cursor: pointer; }

.modal-body { padding: 20px; }

.order-info { display: flex; flex-direction: column; gap: 12px; }
.order-item { display: flex; justify-content: space-between; align-items: center; }
.order-label { font-size: 14px; color: #8a919f; }
.order-value { font-size: 14px; color: #252933; }
.order-value.price { color: #F53F3F; font-weight: 600; }
.discount-row { padding: 8px 0; }
.discount-input { width: 100%; }

.order-total {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding-top: 12px;
  margin-top: 12px;
  border-top: 1px solid #f0f1f5;
}
.total-label { font-size: 16px; color: #252933; font-weight: 500; }
.total-value { font-size: 24px; color: #F53F3F; font-weight: 700; }

.discount-info { font-size: 13px; color: #00b96b; }
.discount-text { font-size: 13px; }

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

/* ===== 响应式 ===== */
@media screen and (max-width: 768px) {
  .header-inner {
    flex-direction: column;
    padding: 20px 16px;
  }

  .course-cover {
    width: 100%;
    height: auto;
    max-height: 200px;
  }

  .header-action {
    width: 100%;
    padding-left: 0;
    border-left: none;
  }

  .detail-body {
    flex-direction: column;
    padding: 0 16px 24px;
  }

  .detail-right {
    width: 100%;
    position: static;
  }
}
</style>