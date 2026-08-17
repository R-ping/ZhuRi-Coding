<template>
  <div class="tag-detail" v-if="!notFound">
    <!-- 标签头部信息区（白底卡片） -->
    <div class="tag-header-card">
      <div class="tag-header-top">
        <h1 class="tag-name">#{{ tagInfo.tagName }}</h1>
        <div class="follow-btn-wrap">
          <button
            class="follow-btn"
            :class="{ followed: isFollowed, loading: followLoading }"
            :disabled="followLoading"
            @click="toggleFollow"
          >
            <i class="btn-icon">{{ isFollowed ? '\uf070' : '\uf067' }}</i>
            <span>{{ followText }}</span>
          </button>
        </div>
      </div>
      <div class="tag-stats">
        <span>{{ formatCount(followerCount) }} 关注</span>
        <span class="stat-sep">·</span>
        <span>{{ formatCount(articleCount) }} 文章</span>
      </div>
      <p v-if="tagInfo.categoryName" class="tag-desc">分类：{{ tagInfo.categoryName }}</p>
    </div>

    <!-- 排序栏 -->
    <div class="sort-bar">
      <span
        class="sort-item"
        :class="{ active: sortType === item.value }"
        v-for="item in sortOptions"
        :key="item.value"
        @click="switchSort(item.value)"
      >{{ item.label }}</span>
    </div>

    <!-- 文章列表 -->
    <div class="article-list">
      <!-- 首次加载骨架屏 -->
      <div class="cell-wrap skeleton" v-for="n in 3" :key="'sk' + n" v-if="initialLoading">
        <div class="sk-title"></div>
        <div class="sk-line"></div>
        <div class="sk-line short"></div>
      </div>

      <!-- 文章卡片（复用首页卡片组件） -->
      <div
        class="cell-wrap"
        v-for="item in articleList"
        :key="item.id"
        @click="openArticle(item)"
      >
        <component
          :is="pickCell(item)"
          :data="item"
          :showTime="sortType === 'latest'"
          @author-click="goAuthor"
        ></component>
      </div>

      <!-- 空状态 -->
      <div class="empty-tip" v-if="!initialLoading && articleList.length === 0">
        <p>该标签下暂无文章</p>
        <p class="empty-sub">去看看其他热门标签吧</p>
      </div>
    </div>

    <!-- 加载更多状态 -->
    <div class="load-more" v-if="articleList.length > 0">
      <span v-if="loadingMore" class="loading-tip">加载中...</span>
      <span v-else-if="!hasMore" class="no-more">没有更多了</span>
    </div>
  </div>

  <!-- 404：标签不存在 -->
  <div class="tag-not-found" v-else>
    <h1 class="nf-code">404</h1>
    <p class="nf-text">该标签不存在</p>
    <button class="nf-btn" @click="goHome">回到首页</button>
  </div>
</template>

<script>
import { getTagDetail, getTagArticles, followTag, unfollowTag } from '@/apis/tag'
import { toast } from '@/utils/toast'
import Article0 from '@/components/cells/article_0.vue'
import Article1 from '@/components/cells/article_1.vue'
import Article3 from '@/components/cells/article_3.vue'

const SORT_OPTIONS = [
  { value: 'hot', label: '热门' },
  { value: 'latest', label: '最新' },
  { value: 'hottest', label: '最热' }
]

export default {
  name: 'TagDetail',
  components: { Article0, Article1, Article3 },
  data() {
    return {
      sortOptions: SORT_OPTIONS,
      tagInfo: { tagName: '', categoryName: '' },
      followerCount: 0,
      articleCount: 0,
      isFollowed: false,
      followLoading: false,
      // 文章列表
      articleList: [],
      sortType: 'hot',
      page: 1,
      size: 20,
      hasMore: true,
      initialLoading: true,
      loadingMore: false,
      notFound: false
    }
  },
  computed: {
    tagName() {
      return this.$route.params.tagName
    },
    isLoggedIn() {
      return this.$store.getters.isLoggedIn
    },
    followText() {
      if (this.followLoading) return '处理中...'
      return this.isFollowed ? '已关注' : '关注'
    }
  },
  mounted() {
    this.init()
    window.addEventListener('scroll', this.handleScroll)
  },
  beforeDestroy() {
    window.removeEventListener('scroll', this.handleScroll)
  },
  watch: {
    '$route.params.tagName'(newVal, oldVal) {
      if (newVal && newVal !== oldVal) {
        this.init()
      }
    }
  },
  methods: {
    // 标签页初始化：并行拉取标签详情 + 首屏文章列表
    init() {
      this.notFound = false
      this.articleList = []
      this.articleCount = 0
      this.page = 1
      this.hasMore = true
      this.initialLoading = true
      this.loadDetail()
      this.loadArticles(true)
    },
    async loadDetail() {
      try {
        const res = await getTagDetail(this.tagName)
        if (res && res.code === 200 && res.data) {
          this.tagInfo = res.data || { tagName: this.tagName, categoryName: '' }
          this.followerCount = Number(res.data.followerCount || 0)
          this.isFollowed = !!res.data.isFollowed
        } else {
          // 标签不存在（后端返回非 200）
          this.notFound = true
        }
      } catch (e) {
        console.error('加载标签详情失败:', e)
        this.notFound = true
      }
    },
    async loadArticles(reset = false) {
      if (this.loadingMore || (!this.hasMore && !reset)) return
      let page = this.page
      if (reset) {
        this.articleList = []
        this.page = 1
        this.hasMore = true
        page = 1
        this.initialLoading = true
      }
      this.loadingMore = true
      try {
        const res = await getTagArticles(this.tagName, { page, size: this.size, sort: this.sortType })
        if (res && res.code === 200 && res.data) {
          const data = res.data
          const list = (data.list || []).map((raw) => this.buildCard(raw))
          this.articleList = reset ? list : this.articleList.concat(list)
          // 文章总数：由文章列表接口 total 提供
          this.articleCount = Number(data.total || 0)
          this.hasMore = list.length > 0 && this.articleList.length < this.articleCount
          this.page = page + 1
        } else {
          this.hasMore = false
        }
      } catch (e) {
        console.error('加载标签文章失败:', e)
        this.hasMore = false
      } finally {
        this.initialLoading = false
        this.loadingMore = false
      }
    },
    // 将后台文章原始数据转换为卡片组件所需的 data 结构（与首页一致）
    buildCard(raw) {
      if (!raw) return null
      const coverImage = raw.coverImage || ''
      let images = []
      // 后台 tags 为字符串数组，无多图冗余，这里以封面作为单图卡片
      if (coverImage) {
        images = [coverImage]
      }
      // 封面图决定卡片类型：有图为 1，无图为 0
      const articleType = images.length >= 1 ? 1 : 0
      let pubTime = raw.publishTime
      if (pubTime) {
        if (typeof pubTime === 'string') pubTime = new Date(pubTime).getTime()
        if (isNaN(pubTime)) pubTime = Date.now()
      } else {
        pubTime = Date.now()
      }
      return {
        id: raw.id,
        title: raw.title || '',
        summary: raw.summary || '',
        comment: raw.comment || 0,
        views: raw.views || 0,
        likes: raw.likes || 0,
        authorId: raw.authorId,
        source: raw.authorName || '',
        authorImage: raw.authorImage || '',
        date: pubTime,
        type: articleType,
        image: images,
        coverImage: coverImage,
        tags: Array.isArray(raw.tags) ? raw.tags : [],
        icon: '\uf06d'
      }
    },
    pickCell(item) {
      if (item.type === 3) return 'Article3'
      if (item.type === 1) return 'Article1'
      return 'Article0'
    },
    switchSort(type) {
      if (this.sortType === type) return
      this.sortType = type
      this.loadArticles(true)
    },
    handleScroll() {
      const scrollTop = window.pageYOffset || document.documentElement.scrollTop
      const windowHeight = window.innerHeight
      const documentHeight = document.documentElement.scrollHeight
      if (scrollTop + windowHeight >= documentHeight - 300) {
        this.loadArticles()
      }
    },
    // 关注/取关（乐观更新 + 失败回滚）
    async toggleFollow() {
      if (this.followLoading) return
      if (!this.isLoggedIn) {
        this.$store.dispatch('showLogin')
        return
      }
      this.followLoading = true
      const previousFollowed = this.isFollowed
      const previousCount = this.followerCount
      // 1. 乐观更新
      this.isFollowed = !previousFollowed
      this.followerCount = previousFollowed ? Math.max(previousCount - 1, 0) : previousCount + 1
      try {
        // 2. 异步请求
        const res = previousFollowed
          ? await unfollowTag(this.tagInfo.id)
          : await followTag(this.tagInfo.id)
        if (!res || res.code !== 200) {
          throw new Error('操作失败')
        }
        toast(previousFollowed ? '已取消关注' : '关注成功')
      } catch (e) {
        // 3. 失败回滚
        this.isFollowed = previousFollowed
        this.followerCount = previousCount
        this.followLoading = false
        toast('操作失败，请稍后重试')
        return
      }
      this.followLoading = false
    },
    // 点击文章 -> 打开文章详情（FTL SSR）
    openArticle(item) {
      if (!item || !item.id) return
      window.open('/content/article/' + item.id, '_blank')
    },
    // 点击作者 -> 跳转用户主页
    goAuthor(userId) {
      if (!userId) return
      this.$router.push('/user/' + userId)
    },
    formatCount(num) {
      const n = Number(num || 0)
      if (n >= 100000000) return (n / 100000000).toFixed(1) + '亿'
      if (n >= 10000) return (n / 10000).toFixed(1) + '万'
      return String(n)
    },
    goHome() {
      this.$router.push('/home')
    }
  }
}
</script>

<style lang="less" scoped>
@import '../../styles/common';

.tag-detail {
  max-width: 1000px;
  margin: 0 auto;
  padding: 24px 20px;
}

/* ====== 标签头部 ====== */
.tag-header-card {
  background: #fff;
  border-radius: 8px;
  border: 1px solid #f0f0f0;
  padding: 24px;
  margin-bottom: 12px;
}
.tag-header-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.tag-name {
  font-size: 26px;
  font-weight: 700;
  color: #1e80ff;
}
.follow-btn-wrap {
  flex-shrink: 0;
}
.follow-btn {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 7px 20px;
  font-size: 14px;
  border: none;
  border-radius: 4px;
  background: linear-gradient(135deg, #1e80ff, #4096ff);
  color: #fff;
  cursor: pointer;
  transition: opacity 0.2s, background 0.2s;
}
.follow-btn:hover {
  opacity: 0.88;
}
.follow-btn.followed {
  background: #f4f5f5;
  color: #515767;
  border: 1px solid #e5e6eb;
}
.follow-btn.followed:hover {
  background: #e8e8e8;
}
.follow-btn[disabled] {
  opacity: 0.7;
  cursor: not-allowed;
}
.btn-icon {
  font-family: fontawesome;
  font-style: normal;
}
.tag-stats {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 14px;
  color: #515767;
  margin-top: 12px;
}
.stat-sep {
  color: #c0c4cc;
}
.tag-desc {
  font-size: 14px;
  color: #86909c;
  margin-top: 8px;
}

/* ====== 排序栏 ====== */
.sort-bar {
  display: flex;
  gap: 4px;
  background: #fff;
  border: 1px solid #f0f0f0;
  border-radius: 8px;
  padding: 6px 12px;
  margin-bottom: 12px;
}
.sort-item {
  padding: 8px 16px;
  font-size: 15px;
  color: #515767;
  cursor: pointer;
  border-radius: 4px;
  border-bottom: 2px solid transparent;
  transition: color 0.2s;
}
.sort-item:hover {
  color: #1e80ff;
}
.sort-item.active {
  color: #1e80ff;
  font-weight: 600;
  background: rgba(30, 128, 255, 0.08);
}

/* ====== 文章列表 ====== */
.article-list {
  background: #fff;
  border: 1px solid #f0f0f0;
  border-radius: 8px;
  padding: 0 16px;
}
.cell-wrap {
  padding: 16px 0;
  cursor: pointer;
  border-bottom: 1px solid #f0f0f0;
}
.cell-wrap:last-child {
  border-bottom: none;
}
.cell-wrap:hover {
  .title {
    color: #1e80ff;
  }
}

/* 骨架屏 */
.sk-title {
  width: 60%;
  height: 20px;
  border-radius: 4px;
  background: linear-gradient(90deg, #f2f3f5 25%, #e8e9eb 37%, #f2f3f5 63%);
  background-size: 400% 100%;
  animation: sk 1.4s ease infinite;
  margin-bottom: 12px;
}
.sk-line {
  width: 90%;
  height: 14px;
  border-radius: 4px;
  background: #f2f3f5;
  margin-bottom: 10px;
}
.sk-line.short {
  width: 40%;
}
@keyframes sk {
  0% { background-position: 100% 50%; }
  100% { background-position: 0 50%; }
}

.empty-tip {
  text-align: center;
  padding: 60px 20px;
  color: #86909c;
  font-size: 15px;
}
.empty-sub {
  margin-top: 8px;
  font-size: 13px;
  color: #c0c4cc;
}

/* 加载更多 */
.load-more {
  text-align: center;
  padding: 20px;
  color: #c0c4cc;
  font-size: 13px;
}

/* ====== 404 ====== */
.tag-not-found {
  max-width: 500px;
  margin: 80px auto;
  background: #fff;
  border: 1px solid #f0f0f0;
  border-radius: 8px;
  text-align: center;
  padding: 60px 20px;
}
.nf-code {
  font-size: 48px;
  color: #c0c4cc;
  font-weight: 700;
}
.nf-text {
  font-size: 16px;
  color: #515767;
  margin: 12px 0 24px;
}
.nf-btn {
  padding: 8px 24px;
  font-size: 14px;
  border: none;
  border-radius: 4px;
  background: #1e80ff;
  color: #fff;
  cursor: pointer;
}
.nf-btn:hover {
  opacity: 0.88;
}
</style>