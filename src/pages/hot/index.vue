<template>
  <div class="hot-page">
    <div class="hot-container">
      <div class="hot-banner">
        <div class="banner-left">
          <span class="hot-title">掘金热榜</span>
          <span class="hot-badge">HOT</span>
        </div>
        <div class="banner-right">
          <span class="rule-text">{{ ruleText }}</span>
        </div>
      </div>

      <div class="tabs-secondary">
        <div
          class="tab-item"
          :class="{ active: activeSecondaryTab === 'article' }"
          @click="switchSecondaryTab('article')"
        >
          掘金文章榜
        </div>
        <div
          class="tab-item"
          :class="{ active: activeSecondaryTab === 'author' }"
          @click="switchSecondaryTab('author')"
        >
          优质作者榜
        </div>
        <div
          class="tab-item"
          :class="{ active: activeSecondaryTab === 'collect' }"
          @click="switchSecondaryTab('collect')"
        >
          文章收藏榜
        </div>
      </div>

      <div class="tabs-tertiary" v-if="activeSecondaryTab === 'article'">
        <div
          v-for="cat in articleCategories"
          :key="cat.key"
          class="tertiary-item"
          :class="{ active: activeCategory === cat.key }"
          @click="switchCategory(cat.key)"
        >
          {{ cat.label }}
        </div>
      </div>

      <div class="tabs-tertiary" v-if="activeSecondaryTab === 'author'">
        <div
          class="tertiary-item"
          :class="{ active: activePeriod === 'weekly' }"
          @click="switchPeriod('weekly')"
        >
          周榜
        </div>
        <div
          class="tertiary-item"
          :class="{ active: activePeriod === 'monthly' }"
          @click="switchPeriod('monthly')"
        >
          月榜
        </div>
      </div>

      <div class="hot-list">
        <div class="list-loading" v-if="loading">
          <span class="loading-spinner"></span>
          <span class="loading-text">加载中...</span>
        </div>

        <div class="list-empty" v-else-if="!loading && hotList.length === 0">
          <span class="empty-icon">&#xf15c;</span>
          <span class="empty-text">暂无数据</span>
        </div>

        <template v-else>
          <div
            v-for="(item, index) in hotList"
            :key="item.id || index"
            class="hot-item"
          >
            <div class="rank-number" :class="getRankClass(index)">
              {{ index + 1 }}
            </div>

            <template v-if="activeSecondaryTab === 'author'">
              <div class="author-info">
                <div class="author-avatar" @click="goToUserProfile(item.userId)">
                  <img v-if="item.avatar" :src="item.avatar" :alt="item.nickName" />
                  <span v-else class="avatar-placeholder">{{ (item.nickName || 'U').charAt(0) }}</span>
                </div>
                <div class="author-detail">
                  <div class="author-name" @click="goToUserProfile(item.userId)">
                    {{ item.nickName }}
                  </div>
                  <div class="author-meta">
                    <span class="meta-item">{{ formatCount(item.articleCount) }} 篇文章</span>
                    <span class="meta-item">{{ formatCount(item.followerCount) }} 粉丝</span>
                  </div>
                  <div class="author-desc" v-if="item.description">{{ item.description }}</div>
                </div>
                <div class="author-actions">
                  <button
                    class="btn-follow"
                    :class="{ followed: item.followed }"
                    @click.stop="toggleFollow(item)"
                  >
                    {{ item.followed ? '已关注' : '+ 关注' }}
                  </button>
                </div>
              </div>
            </template>

            <template v-else>
              <div class="article-info">
                <div class="article-main">
                  <div class="article-title" @click="goToArticle(item.id)">
                    {{ item.title }}
                  </div>
                  <div class="article-meta">
                    <span class="meta-author" @click="goToUserProfile(item.userId)">
                      {{ item.nickName }}
                    </span>
                    <span class="meta-dot">·</span>
                    <span class="meta-item">{{ item.categoryName || getCategoryLabel(item.category) }}</span>
                    <span class="meta-dot">·</span>
                    <span class="meta-item">{{ formatCount(item.viewCount) }} 阅读</span>
                    <span class="meta-dot" v-if="item.commentCount !== undefined">·</span>
                    <span class="meta-item" v-if="item.commentCount !== undefined">{{ formatCount(item.commentCount) }} 评论</span>
                  </div>
                </div>
                <div class="article-actions">
                  <span class="hot-score">
                    <span class="score-value">{{ formatCount(item.hotValue || item.score) }}</span>
                    <span class="score-label">热度</span>
                  </span>
                  <button
                    v-if="activeSecondaryTab === 'collect'"
                    class="btn-collect"
                    :class="{ collected: item.collected }"
                    @click.stop="toggleCollect(item)"
                  >
                    <span class="collect-icon">&#xf005;</span>
                    {{ item.collected ? '已收藏' : '收藏' }}
                  </button>
                  <button
                    v-if="activeSecondaryTab === 'article'"
                    class="btn-collect"
                    :class="{ collected: item.collected }"
                    @click.stop="toggleCollect(item)"
                  >
                    <span class="collect-icon">{{ item.collected ? '&#xf005;' : '&#xf006;' }}</span>
                    {{ item.collected ? '已收藏' : '收藏' }}
                  </button>
                </div>
              </div>
            </template>
          </div>
        </template>
      </div>
    </div>
  </div>
</template>

<script>
import { toast } from '@/utils/toast'
import { formatCount } from '@/utils/format'
import {
  getHotArticles,
  getCollectedArticles,
  getHotAuthors,
  getHotMeta
} from '@/apis/hot'

const ARTICLE_CATEGORIES = [
  { key: 'comprehensive', label: '综合' },
  { key: 'backend', label: '后端' },
  { key: 'frontend', label: '前端' },
  { key: 'android', label: 'Android' },
  { key: 'ios', label: 'iOS' },
  { key: 'ai', label: '人工智能' },
  { key: 'devtools', label: '开发工具' },
  { key: 'coderslife', label: '代码人生' },
  { key: 'reading', label: '阅读' }
]

export default {
  name: 'HeiMaHot',
  data() {
    return {
      activeSecondaryTab: 'article',
      activeCategory: 'comprehensive',
      activePeriod: 'weekly',
      articleCategories: ARTICLE_CATEGORIES,
      hotList: [],
      loading: false,
      ruleText: '',
      meta: null
    }
  },
  computed: {
    isLoggedIn() {
      return this.$store.getters.isLoggedIn
    }
  },
  mounted() {
    this.loadMeta()
    this.loadData()
  },
  watch: {
    activeSecondaryTab() {
      this.loadData()
    },
    activeCategory() {
      if (this.activeSecondaryTab === 'article') {
        this.loadData()
      }
    },
    activePeriod() {
      if (this.activeSecondaryTab === 'author') {
        this.loadData()
      }
    }
  },
  methods: {
    formatCount,
    getRankClass(index) {
      if (index === 0) return 'rank-top1'
      if (index === 1) return 'rank-top2'
      if (index === 2) return 'rank-top3'
      return ''
    },
    getCategoryLabel(key) {
      const cat = this.articleCategories.find(c => c.key === key)
      return cat ? cat.label : key
    },
    switchSecondaryTab(tab) {
      if (this.activeSecondaryTab === tab) return
      this.activeSecondaryTab = tab
      this.hotList = []
    },
    switchCategory(cat) {
      if (this.activeCategory === cat) return
      this.activeCategory = cat
      this.hotList = []
    },
    switchPeriod(period) {
      if (this.activePeriod === period) return
      this.activePeriod = period
      this.hotList = []
    },
    async loadMeta() {
      try {
        const res = await getHotMeta(this.activeSecondaryTab, this.activeCategory, this.activePeriod)
        if (res && res.code === 200 && res.data) {
          this.meta = res.data
          this.ruleText = res.data.ruleText || this.getDefaultRuleText()
        } else {
          this.ruleText = this.getDefaultRuleText()
        }
      } catch (e) {
        this.ruleText = this.getDefaultRuleText()
      }
    },
    getDefaultRuleText() {
      const tabLabels = {
        article: '根据文章阅读量、评论数、收藏数等综合热度排名',
        author: '根据作者文章质量、粉丝增长等综合排名',
        collect: '根据文章收藏量排名'
      }
      return tabLabels[this.activeSecondaryTab] || ''
    },
    async loadData() {
      this.loading = true
      try {
        let res
        if (this.activeSecondaryTab === 'article') {
          res = await getHotArticles(this.activeCategory, 30)
        } else if (this.activeSecondaryTab === 'author') {
          res = await getHotAuthors(this.activePeriod, 30)
        } else if (this.activeSecondaryTab === 'collect') {
          res = await getCollectedArticles(30)
        }

        if (res && res.code === 200) {
          this.hotList = res.data || []
        } else {
          this.hotList = []
          toast((res && res.message) || '加载失败')
        }
      } catch (e) {
        console.error('加载热榜数据失败', e)
        this.hotList = []
        toast('加载失败，请重试')
      } finally {
        this.loading = false
      }
    },
    goToArticle(id) {
      if (!id) return
      // 文章详情统一走 FTL SSR 页面
      window.open('/content/article/' + id, '_blank')
    },
    goToUserProfile(userId) {
      if (!userId) return
      this.$router.push('/user/' + userId)
    },
    async toggleCollect(item) {
      if (!this.isLoggedIn) {
        this.$store.dispatch('showLogin')
        return
      }
      const prevCollected = item.collected
      item.collected = !prevCollected
      try {
        const { default: request } = await import('@/common/request')
        const url = '/api/v1/content/collect'
        const res = await request.post(url, {
          articleId: item.id,
          operation: item.collected ? 1 : 0
        })
        if (res && res.code === 200) {
          toast(item.collected ? '收藏成功' : '取消收藏')
          if (item.collected && item.collectCount !== undefined) {
            item.collectCount++
          } else if (!item.collected && item.collectCount !== undefined) {
            item.collectCount = Math.max(0, item.collectCount - 1)
          }
        } else {
          item.collected = prevCollected
          toast((res && res.message) || '操作失败')
        }
      } catch (e) {
        item.collected = prevCollected
        console.error('收藏操作失败', e)
        toast('操作失败，请重试')
      }
    },
    async toggleFollow(item) {
      if (!this.isLoggedIn) {
        this.$store.dispatch('showLogin')
        return
      }
      const prevFollowed = item.followed
      item.followed = !prevFollowed
      try {
        const { default: request } = await import('@/common/request')
        const url = '/api/v1/user/follow'
        const res = await request.post(url, {
          userId: item.userId,
          operation: item.followed ? 1 : 0
        })
        if (res && res.code === 200) {
          toast(item.followed ? '关注成功' : '已取消关注')
          if (item.followed && item.followerCount !== undefined) {
            item.followerCount++
          } else if (!item.followed && item.followerCount !== undefined) {
            item.followerCount = Math.max(0, item.followerCount - 1)
          }
        } else {
          item.followed = prevFollowed
          toast((res && res.message) || '操作失败')
        }
      } catch (e) {
        item.followed = prevFollowed
        console.error('关注操作失败', e)
        toast('操作失败，请重试')
      }
    }
  }
}
</script>

<style lang="less" scoped>
@import '../../styles/common';

.hot-page {
  min-height: 100vh;
  background-color: #f4f5f7;
}

.hot-container {
  max-width: 900PX;
  margin: 0 auto;
  padding: 24PX;
}

.hot-banner {
  display: flex;
  align-items: center;
  justify-content: space-between;
  background: linear-gradient(135deg, #ff6b6b, #ff8e53);
  border-radius: 12PX;
  padding: 24PX 28PX;
  margin-bottom: 20PX;
  color: #fff;
}

.banner-left {
  display: flex;
  align-items: center;
  gap: 12PX;
}

.hot-title {
  font-size: 24PX;
  font-weight: 700;
  letter-spacing: 1PX;
}

.hot-badge {
  display: inline-block;
  padding: 4PX 10PX;
  background-color: rgba(255, 255, 255, 0.25);
  border-radius: 4PX;
  font-size: 12PX;
  font-weight: 600;
  letter-spacing: 1PX;
}

.banner-right {
  flex: 1;
  text-align: right;
  margin-left: 20PX;
}

.rule-text {
  font-size: 13PX;
  opacity: 0.9;
}

.tabs-secondary {
  display: flex;
  background-color: #fff;
  border-radius: 8PX;
  padding: 6PX;
  margin-bottom: 16PX;
  box-shadow: 0 1PX 3PX rgba(0, 0, 0, 0.06);
}

.tab-item {
  flex: 1;
  text-align: center;
  padding: 10PX 16PX;
  font-size: 14PX;
  color: #515767;
  cursor: pointer;
  border-radius: 6PX;
  transition: all 0.2s;
  font-weight: 500;
}

.tab-item:hover {
  color: #1E80FF;
  background-color: #f5f7fa;
}

.tab-item.active {
  color: #fff;
  background-color: #1E80FF;
}

.tabs-tertiary {
  display: flex;
  flex-wrap: wrap;
  gap: 8PX;
  margin-bottom: 16PX;
  padding: 0 4PX;
}

.tertiary-item {
  padding: 6PX 14PX;
  font-size: 13PX;
  color: #515767;
  background-color: #fff;
  border-radius: 16PX;
  cursor: pointer;
  transition: all 0.2s;
  border: 1PX solid transparent;
}

.tertiary-item:hover {
  color: #1E80FF;
  border-color: #1E80FF;
}

.tertiary-item.active {
  color: #1E80FF;
  background-color: #E8F3FF;
  border-color: #1E80FF;
  font-weight: 500;
}

.hot-list {
  background-color: #fff;
  border-radius: 8PX;
  overflow: hidden;
  min-height: 300PX;
}

.list-loading,
.list-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 80PX 20PX;
  gap: 16PX;
}

.loading-spinner {
  width: 32PX;
  height: 32PX;
  border: 3PX solid #e0e0e0;
  border-top-color: #1E80FF;
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

.loading-text {
  font-size: 14PX;
  color: #999;
}

.empty-icon {
  font-family: fontawesome;
  font-size: 48PX;
  color: #d0d0d0;
}

.empty-text {
  font-size: 14PX;
  color: #999;
}

.hot-item {
  display: flex;
  align-items: flex-start;
  padding: 16PX 20PX;
  border-bottom: 1PX solid #f0f1f5;
  transition: background-color 0.15s;
}

.hot-item:last-child {
  border-bottom: none;
}

.hot-item:hover {
  background-color: #fafbfc;
}

.rank-number {
  flex-shrink: 0;
  width: 28PX;
  height: 28PX;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 14PX;
  font-weight: 600;
  color: #8A93A6;
  background-color: #f4f5f7;
  border-radius: 6PX;
  margin-right: 16PX;
}

.rank-top1 {
  background-color: #FF6B6B;
  color: #fff;
}

.rank-top2 {
  background-color: #FFA940;
  color: #fff;
}

.rank-top3 {
  background-color: #FFD666;
  color: #fff;
}

.article-info {
  flex: 1;
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16PX;
}

.article-main {
  flex: 1;
  min-width: 0;
}

.article-title {
  font-size: 16PX;
  font-weight: 600;
  color: #252933;
  line-height: 1.5;
  margin-bottom: 8PX;
  cursor: pointer;
  transition: color 0.2s;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.article-title:hover {
  color: #1E80FF;
}

.article-meta {
  display: flex;
  align-items: center;
  gap: 8PX;
  font-size: 12PX;
  color: #8A93A6;
}

.meta-author {
  color: #515767;
  cursor: pointer;
  transition: color 0.2s;
}

.meta-author:hover {
  color: #1E80FF;
}

.meta-dot {
  color: #c0c4cc;
}

.meta-item {
  color: #8A93A6;
}

.article-actions {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 8PX;
  flex-shrink: 0;
}

.hot-score {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
}

.score-value {
  font-size: 18PX;
  font-weight: 700;
  color: #FF6B6B;
}

.score-label {
  font-size: 11PX;
  color: #8A93A6;
}

.btn-collect {
  display: flex;
  align-items: center;
  gap: 4PX;
  padding: 6PX 12PX;
  font-size: 12PX;
  color: #515767;
  background-color: #f4f5f7;
  border: none;
  border-radius: 4PX;
  cursor: pointer;
  transition: all 0.2s;
}

.btn-collect:hover {
  color: #1E80FF;
  background-color: #E8F3FF;
}

.btn-collect.collected {
  color: #FF6B6B;
  background-color: #FFF0F0;
}

.collect-icon {
  font-family: fontawesome;
  font-size: 12PX;
}

.author-info {
  flex: 1;
  display: flex;
  align-items: center;
  gap: 16PX;
}

.author-avatar {
  flex-shrink: 0;
  width: 56PX;
  height: 56PX;
  border-radius: 50%;
  overflow: hidden;
  cursor: pointer;
  background-color: #f4f5f7;
}

.author-avatar img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.avatar-placeholder {
  width: 100%;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 20PX;
  font-weight: 600;
  color: #fff;
  background-color: #1E80FF;
}

.author-detail {
  flex: 1;
  min-width: 0;
}

.author-name {
  font-size: 16PX;
  font-weight: 600;
  color: #252933;
  margin-bottom: 4PX;
  cursor: pointer;
  transition: color 0.2s;
}

.author-name:hover {
  color: #1E80FF;
}

.author-meta {
  display: flex;
  align-items: center;
  gap: 12PX;
  margin-bottom: 4PX;
}

.author-desc {
  font-size: 13PX;
  color: #8A93A6;
  line-height: 1.4;
  display: -webkit-box;
  -webkit-line-clamp: 1;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.author-actions {
  flex-shrink: 0;
}

.btn-follow {
  padding: 6PX 16PX;
  font-size: 13PX;
  color: #fff;
  background-color: #1E80FF;
  border: none;
  border-radius: 4PX;
  cursor: pointer;
  transition: all 0.2s;
  white-space: nowrap;
}

.btn-follow:hover {
  background-color: #1A6FD9;
}

.btn-follow.followed {
  color: #515767;
  background-color: #f4f5f7;
}

.btn-follow.followed:hover {
  color: #FF6B6B;
  background-color: #FFF0F0;
}

@media screen and (max-width: 768PX) {
  .hot-container {
    padding: 16PX;
  }

  .hot-banner {
    flex-direction: column;
    align-items: flex-start;
    gap: 8PX;
    padding: 20PX;
  }

  .banner-right {
    text-align: left;
    margin-left: 0;
    width: 100%;
  }

  .hot-title {
    font-size: 20PX;
  }

  .article-info {
    flex-direction: column;
    gap: 12PX;
  }

  .article-actions {
    flex-direction: row;
    align-items: center;
    width: 100%;
  }

  .hot-item {
    padding: 14PX 16PX;
  }
}
</style>