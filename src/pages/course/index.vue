<template>
  <div class="course-page" :class="{ 'is-desktop': isDesktop }">
    <div class="course-header">
      <div class="header-title">课程</div>
      <div class="header-subtitle">发现优质课程，提升技术能力</div>
    </div>

    <div class="category-tabs">
      <div 
        v-for="cat in categories" 
        :key="cat.id"
        class="category-tab"
        :class="{ active: currentCategory === cat.id }"
        @click="selectCategory(cat.id)"
      >
        {{ cat.name }}
      </div>
    </div>

    <div class="filter-bar">
      <div 
        v-for="filter in filters" 
        :key="filter.id"
        class="filter-item"
        :class="{ active: currentFilter === filter.id }"
        @click="selectFilter(filter.id)"
      >
        {{ filter.name }}
      </div>
    </div>

    <div class="course-list">
      <CourseCard
        v-for="course in courseList"
        :key="course.id"
        :course="course"
        :category-name="getCategoryName(course.categoryId)"
        @click="goToDetail(course.id)"
      />
    </div>

    <!-- 上拉加载哨兵节点：作为 IntersectionObserver 观察目标，触底时自动加载下一页 -->
    <div ref="loadMoreSentinel" class="load-more-sentinel"></div>

    <div class="loading-more" v-if="loading">
      <span class="loading-spinner"></span>
      <span class="loading-text">加载中...</span>
    </div>

    <!-- 首屏空/错误状态：区分"暂无课程"与"加载失败" -->
    <div class="no-more" v-if="!loading && courseList.length === 0">
      <span v-if="loadError">加载失败，请检查网络后重试</span>
      <span v-else>— 暂无课程 —</span>
      <span class="retry-btn" v-if="loadError" @click="selectCategory(0)">点击重试</span>
    </div>

    <div class="no-more" v-if="!loading && noMore && courseList.length > 0">
      <span>— 没有更多了 —</span>
    </div>
  </div>
</template>

<script>
import CourseCard from './components/CourseCard.vue'
import courseApi from '@/apis/course'
import Utils from '@/utils/env'

export default {
  name: 'CoursePage',
  components: { CourseCard },
  data() {
    return {
      categories: [
        { id: 0, name: '全部' },
        { id: 1, name: '后端' },
        { id: 2, name: '前端' },
        { id: 3, name: 'Android' },
        { id: 4, name: 'iOS' },
        { id: 5, name: '人工智能' },
        { id: 6, name: '开发工具' },
        { id: 7, name: '代码人生' },
        { id: 8, name: '阅读' }
      ],
      filters: [
        { id: 'all', name: '全部' },
        { id: 'latest', name: '最新' },
        { id: 'hot', name: '热销' },
        { id: 'price', name: '价格' }
      ],
      currentCategory: 0,
      currentFilter: 'all',
      courseList: [],
      page: 1,
      loading: false,
      noMore: false,
      // 课程列表加载失败标记（503/超时等）
      loadError: false,
      // 上拉加载观察器实例（beforeDestroy 清理）
      io: null
    }
  },
  computed: {
    isDesktop() {
      return Utils.isDesktop()
    }
  },
  mounted() {
    this.loadCourseList()
    this.setupInfiniteScroll()
  },
  beforeDestroy() {
    // 页面销毁时断开观察器，避免泄漏与重复触发
    if (this.io) {
      this.io.disconnect()
      this.io = null
    }
  },
  methods: {
    /**
     * 设置上拉加载更多：监听哨兵节点进入视口后加载下一页课程。
     */
    setupInfiniteScroll() {
      const el = this.$refs.loadMoreSentinel
      if (!el) return
      if (typeof IntersectionObserver === 'undefined') return
      this.io = new IntersectionObserver((entries) => {
        if (entries[0] && entries[0].isIntersecting) {
          this.loadMoreCourses()
        }
      }, { rootMargin: '200px 0px' })
      this.io.observe(el)
    },
    /**
     * 触底加载下一页（去重：加载中 / 已无更多 时跳过）。
     */
    loadMoreCourses() {
      if (this.loading || this.noMore) return
      this.loadCourseList()
    },
    getCategoryName(categoryId) {
      const cat = this.categories.find(c => c.id === categoryId)
      return cat ? cat.name : ''
    },
    selectCategory(categoryId) {
      this.currentCategory = categoryId
      this.page = 1
      this.courseList = []
      this.noMore = false
      this.loadError = false
      this.loadCourseList()
    },
    selectFilter(filterId) {
      this.currentFilter = filterId
      this.page = 1
      this.courseList = []
      this.noMore = false
      this.loadError = false
      this.loadCourseList()
    },
    async loadCourseList() {
      if (this.loading) return
      this.loading = true

      try {
        const res = await courseApi.getCourseList({
          page: this.page,
          size: 10,
          status: 9 // 只查询已上架课程
        })
        if (res && res.code === 200 && res.data) {
          this.loadError = false
          const list = res.data.list || []
          // 客户端分类筛选
          let filteredData = list
          if (this.currentCategory !== 0) {
            filteredData = list.filter(c => c.categoryId === this.currentCategory)
          }
          if (this.currentFilter === 'latest') {
            filteredData = filteredData.sort((a, b) => new Date(b.publishedAt || b.createdTime) - new Date(a.publishedAt || a.createdTime))
          } else if (this.currentFilter === 'hot') {
            filteredData = filteredData.sort((a, b) => (b.studyCount || 0) - (a.studyCount || 0))
          } else if (this.currentFilter === 'price') {
            filteredData = filteredData.sort((a, b) => (a.price || 0) - (b.price || 0))
          }
          this.courseList = this.courseList.concat(filteredData)
          this.noMore = res.data.total <= this.page * 10
          this.page++
        } else {
          // 业务错误（503/异常返回）
          this.loadError = true
        }
      } catch (e) {
        this.loadError = true
      } finally {
        this.loading = false
        // 若当前已滚动到底（哨兵在视口内）且还有更多数据，主动补载下一页，
        // 避免 IntersectionObserver 仅在 "进入/离开视口" 变化时触发而错过触底续载。
        if (!this.noMore) {
          const el = this.$refs.loadMoreSentinel
          if (el && el.getBoundingClientRect().top < window.innerHeight) {
            this.$nextTick(() => this.loadMoreCourses())
          }
        }
      }
    },
    goToDetail(courseId) {
      // 课程列表进入详情页：新开标签页打开
      window.open(`/course/${courseId}`, '_blank')
    }
  }
}
</script>

<style lang="less" scoped>
@import '../../styles/common';

.course-page {
  min-height: 100vh;
  background-color: #f4f5f7;
  padding-bottom: 120px;
}

.course-header {
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

.category-tabs {
  display: flex;
  overflow-x: auto;
  padding: 16px 20px;
  background-color: #fff;
  gap: 8px;
  white-space: nowrap;
}

.category-tabs::-webkit-scrollbar {
  display: none;
}

.category-tab {
  padding: 8px 16px;
  font-size: 14px;
  color: #515767;
  background-color: #f4f5f7;
  border-radius: 20px;
  cursor: pointer;
  transition: all 0.2s;
}

.category-tab.active {
  background-color: #1E80FF;
  color: #fff;
}

.filter-bar {
  display: flex;
  padding: 12px 20px;
  background-color: #fff;
  border-top: 1px solid #f0f1f5;
  gap: 24px;
}

.filter-item {
  font-size: 14px;
  color: #515767;
  cursor: pointer;
  position: relative;
  padding-bottom: 8px;
}

.filter-item.active {
  color: #1E80FF;
  font-weight: 500;
}

.filter-item.active::after {
  content: '';
  position: absolute;
  bottom: 0;
  left: 0;
  right: 0;
  height: 2px;
  background-color: #1E80FF;
  border-radius: 1px;
}

.course-list {
  padding: 16px;
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 16px;
}

.loading-more, .no-more {
  display: flex;
  justify-content: center;
  align-items: center;
  padding: 20px;
  gap: 8px;
}

/* 上拉加载哨兵：占位触底检测，无需视觉呈现 */
.load-more-sentinel {
  width: 1px;
  height: 1px;
}

.loading-spinner {
  width: 20px;
  height: 20px;
  border: 2px solid #e0e0e0;
  border-top-color: #3194ff;
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

.loading-text {
  font-size: 13px;
  color: #999;
}

.no-more span {
  font-size: 13px;
  color: #ccc;
}

.retry-btn {
  display: inline-block;
  padding: 4px 16px;
  border-radius: 14px;
  border: 1px solid #1E80FF;
  color: #1E80FF;
  font-size: 13px;
  cursor: pointer;
  transition: all 0.2s;
  user-select: none;
}
.retry-btn:hover {
  background: rgba(30, 128, 255, 0.08);
}

@media screen and (min-width: 768px) {
  .course-page {
    padding-bottom: 24px;
  }

  .course-page.is-desktop {
    background: transparent;
    min-height: auto;
    padding-bottom: 0;
  }

  .course-header {
    padding: 48px 24px 40px;
  }

  .header-title {
    font-size: 36px;
  }

  .course-list {
    grid-template-columns: repeat(3, 1fr);
    gap: 20px;
    padding: 20px 24px;
  }
}

@media screen and (min-width: 1024px) {
  .course-list {
    grid-template-columns: repeat(4, 1fr);
  }
}
</style>