<template>
  <div class="topic-detail-page">
    <!-- 话题信息 -->
    <div class="topic-header">
      <div class="topic-info">
        <h1 class="topic-name">#{{ topic.name }}#</h1>
        <p class="topic-desc">{{ topic.description }}</p>
        <div class="topic-stats">
          <span class="stat-item">{{ topic.participantCount || 0 }} 人参与</span>
          <span class="stat-divider">·</span>
          <span class="stat-item">{{ topic.viewCount || 0 }} 阅读</span>
          <span class="stat-divider">·</span>
          <span class="stat-item">{{ topic.postCount || 0 }} 内容</span>
        </div>
      </div>
      <div class="topic-badge" v-if="topic.badge">
        <el-tag size="small" type="warning">{{ topic.badge }}</el-tag>
      </div>
    </div>

    <div class="topic-body">
      <div class="topic-main">
        <!-- Tab切换 -->
        <div class="feed-tabs">
          <div
            v-for="tab in availableTabs"
            :key="tab"
            class="feed-tab"
            :class="{ active: activeFeedTab === tab }"
            @click="switchFeedTab(tab)"
          >
            {{ tabLabels[tab] || tab }}
          </div>
        </div>

        <!-- 内容列表 -->
        <div class="feed-list">
          <div v-for="item in feedList" :key="item.id" class="feed-item">
            <div class="feed-header">
              <img :src="item.userAvatar || defaultAvatar" class="feed-avatar" />
              <span class="feed-user">{{ item.userName || '未知用户' }}</span>
              <span class="feed-time">{{ formatTime(item.createdTime) }}</span>
            </div>
            <div class="feed-content">{{ item.content }}</div>
            <div class="feed-meta">
              <span class="meta-action">❤ {{ item.likeCount || 0 }}</span>
              <span class="meta-action">💬 {{ item.commentCount || 0 }}</span>
            </div>
          </div>
          <div v-if="feedList.length === 0 && !feedLoading" class="feed-empty">暂无内容</div>
          <div v-if="feedLoading" class="feed-loading">加载中...</div>
          <div v-if="hasMore" class="feed-more" @click="loadMore">加载更多</div>
        </div>
      </div>

      <!-- 右侧边栏 -->
      <div class="topic-sidebar">
        <!-- 相关圈子 -->
        <div class="sidebar-section" v-if="topic.circleInfo && topic.circleInfo.length > 0">
          <h3 class="sidebar-title">相关圈子</h3>
          <div
            class="circle-item"
            v-for="circle in topic.circleInfo"
            :key="circle.circleId"
            @click="goToCircle(circle.circleId)"
          >
            <span class="circle-name">{{ circle.circleName }}</span>
            <span class="circle-count">{{ circle.memberCount || 0 }} 人</span>
          </div>
        </div>

        <!-- 推荐话题 -->
        <div class="sidebar-section">
          <h3 class="sidebar-title">推荐话题</h3>
          <div
            class="recommend-topic"
            v-for="rec in recommendedTopics"
            :key="rec.id"
            @click="goToTopic(rec.id)"
          >
            <span class="rec-name">#{{ rec.name }}#</span>
            <span class="rec-count">{{ rec.participantCount || 0 }} 参与</span>
          </div>
          <div class="sidebar-more" @click="goToTopicSquare">查看更多</div>
        </div>
      </div>
    </div>
  </div>
</template>

<script>
import { getTopicDetail, getTopicFeed, getRecommendedTopics, incrTopicView } from '@/apis/creator/topicDetail'
import defaultAvatar from '@/static/images/creator/avatar.jpg'

export default {
  name: 'TopicDetail',
  data() {
    return {
      topic: {
        name: '',
        description: '',
        circleInfo: []
      },
      activeFeedTab: 'hot',
      feedList: [],
      feedCursor: 0,
      feedSize: 20,
      hasMore: true,
      feedLoading: false,
      recommendedTopics: [],
      tabLabels: {
        hot: '热门',
        new: '最新',
        article: '文章',
        pin: '沸点'
      }
    }
  },
  computed: {
    availableTabs() {
      return this.topic.availableTabs || ['hot', 'new']
    },
    defaultAvatar() {
      return defaultAvatar
    }
  },
  async mounted() {
    const topicId = this.$route.params.id
    if (topicId) {
      await this.fetchTopicDetail(topicId)
      await this.fetchRecommendedTopics(topicId)
      this.fetchFeed()
      incrTopicView(topicId)
    }
  },
  methods: {
    async fetchTopicDetail(id) {
      try {
        const res = await getTopicDetail(id)
        if (res && res.code === 200 && res.data) {
          this.topic = res.data
        }
      } catch (e) {}
    },
    async fetchFeed(reset = true) {
      if (this.feedLoading) return
      if (reset) {
        this.feedCursor = 0
        this.feedList = []
        this.hasMore = true
      }
      this.feedLoading = true
      try {
        const res = await getTopicFeed(this.$route.params.id, {
          tab: this.activeFeedTab,
          cursor: this.feedCursor,
          size: this.feedSize
        })
        if (res && res.code === 200 && res.data) {
          const list = res.data.list || []
          this.feedList = reset ? list : [...this.feedList, ...list]
          this.feedCursor = res.data.cursor || (this.feedCursor + this.feedSize)
          this.hasMore = res.data.has_more !== false
        }
      } catch (e) {} finally {
        this.feedLoading = false
      }
    },
    async fetchRecommendedTopics(excludeId) {
      try {
        const res = await getRecommendedTopics(excludeId, 6)
        if (res && res.code === 200 && res.data) {
          this.recommendedTopics = res.data
        }
      } catch (e) {}
    },
    switchFeedTab(tab) {
      this.activeFeedTab = tab
      this.fetchFeed(true)
    },
    loadMore() {
      this.fetchFeed(false)
    },
    formatTime(timestamp) {
      if (!timestamp) return ''
      const t = typeof timestamp === 'string' ? new Date(timestamp).getTime() : timestamp
      const diff = Date.now() - t
      const hours = Math.floor(diff / 3600000)
      const days = Math.floor(diff / 86400000)
      if (hours < 1) return '刚刚'
      if (hours < 24) return hours + '小时前'
      if (days < 30) return days + '天前'
      return new Date(timestamp).toLocaleDateString()
    },
    goToCircle(circleId) {
      this.$router.push(`/pins/circle/${circleId}`)
    },
    goToTopic(topicId) {
      this.$router.push(`/creator/growth/topic/${topicId}`)
    },
    goToTopicSquare() {
      this.$router.push('/topics')
    }
  }
}
</script>

<style lang="less" scoped>
.topic-detail-page {
  max-width: 1000px;
  margin: 0 auto;
  padding: 24px;
}

.topic-header {
  background: #fff;
  border: 1px solid #e4e6eb;
  border-radius: 8px;
  padding: 24px;
  margin-bottom: 20px;
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  .topic-info {
    flex: 1;
    .topic-name {
      font-size: 22px;
      font-weight: 700;
      color: #252933;
      margin-bottom: 8px;
    }
    .topic-desc {
      font-size: 14px;
      color: #8a919f;
      margin-bottom: 12px;
      line-height: 1.6;
    }
    .topic-stats {
      font-size: 13px;
      color: #c2c8d1;
      .stat-divider { margin: 0 8px; }
    }
  }
}

.topic-body {
  display: flex;
  gap: 20px;
}

.topic-main {
  flex: 1;
  min-width: 0;
}

.feed-tabs {
  display: flex;
  gap: 0;
  border-bottom: 2px solid #e4e6eb;
  margin-bottom: 16px;
  .feed-tab {
    padding: 10px 20px;
    font-size: 14px;
    color: #515767;
    cursor: pointer;
    border-bottom: 2px solid transparent;
    margin-bottom: -2px;
    transition: all 0.2s;
    &:hover { color: #1e80ff; }
    &.active {
      color: #1e80ff;
      border-bottom-color: #1e80ff;
      font-weight: 600;
    }
  }
}

.feed-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.feed-item {
  background: #fff;
  border: 1px solid #e4e6eb;
  border-radius: 8px;
  padding: 16px;
  .feed-header {
    display: flex;
    align-items: center;
    gap: 8px;
    margin-bottom: 8px;
    .feed-avatar {
      width: 32px;
      height: 32px;
      border-radius: 50%;
    }
    .feed-user {
      font-size: 14px;
      color: #252933;
      font-weight: 500;
    }
    .feed-time {
      font-size: 12px;
      color: #c2c8d1;
      margin-left: auto;
    }
  }
  .feed-content {
    font-size: 14px;
    color: #515767;
    line-height: 1.6;
    margin-bottom: 8px;
  }
  .feed-meta {
    display: flex;
    gap: 16px;
    .meta-action {
      font-size: 13px;
      color: #8a919f;
    }
  }
}

.feed-empty, .feed-loading {
  text-align: center;
  padding: 40px;
  color: #c2c8d1;
  font-size: 14px;
}

.feed-more {
  text-align: center;
  padding: 16px;
  color: #1e80ff;
  cursor: pointer;
  font-size: 14px;
  &:hover { color: #4096ff; }
}

.topic-sidebar {
  width: 260px;
  flex-shrink: 0;
}

.sidebar-section {
  background: #fff;
  border: 1px solid #e4e6eb;
  border-radius: 8px;
  padding: 16px;
  margin-bottom: 16px;
  .sidebar-title {
    font-size: 15px;
    font-weight: 600;
    color: #252933;
    margin-bottom: 12px;
    padding-bottom: 8px;
    border-bottom: 1px solid #f2f3f5;
  }
}

.circle-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 8px 0;
  cursor: pointer;
  &:hover .circle-name { color: #1e80ff; }
  .circle-name {
    font-size: 14px;
    color: #515767;
    transition: color 0.2s;
  }
  .circle-count {
    font-size: 12px;
    color: #c2c8d1;
  }
}

.recommend-topic {
  padding: 8px 0;
  cursor: pointer;
  display: flex;
  justify-content: space-between;
  align-items: center;
  &:hover .rec-name { color: #1e80ff; }
  .rec-name {
    font-size: 14px;
    color: #515767;
    transition: color 0.2s;
  }
  .rec-count {
    font-size: 12px;
    color: #c2c8d1;
  }
}

.sidebar-more {
  text-align: center;
  padding-top: 8px;
  font-size: 13px;
  color: #1e80ff;
  cursor: pointer;
  &:hover { color: #4096ff; }
}

@media screen and (max-width: 768px) {
  .topic-body {
    flex-direction: column;
  }
  .topic-sidebar {
    width: 100%;
  }
}
</style>