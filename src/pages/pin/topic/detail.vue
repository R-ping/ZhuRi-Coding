<template>
  <div class="topic-detail">
    <!-- 顶部信息区 -->
    <div class="topic-header">
      <h1>#{{ topic.name }}#</h1>
      <div class="topic-stats">
        <span>{{ formatCount(topic.viewCount) }}阅读</span>
        <span>{{ formatCount(topic.participantCount) }}参与</span>
        <span>{{ formatCount(topic.postCount) }}帖子</span>
      </div>
      <p class="topic-desc" v-if="topic.description">导语：{{ topic.description }}</p>
    </div>

    <div class="topic-content">
      <div class="content-main">
        <!-- 纯沸点模式：发沸点输入卡片 -->
        <div class="publish-card" v-if="topic.type === 1" @click="openPublish">
          <div class="publish-input">说点什么...</div>
          <div class="publish-actions">
            <span class="action-btn">😊</span>
            <span class="action-btn">📷</span>
          </div>
        </div>

        <!-- Tab 栏 -->
        <div class="tab-bar">
          <span v-for="tab in availableTabs" :key="tab"
                :class="['tab-item', { active: activeTab === tab }]"
                @click="switchTab(tab)">
            {{ tabLabel(tab) }}
          </span>
        </div>

        <!-- 内容列表 -->
        <div class="feed-list">
          <div class="feed-item" v-for="item in feedList" :key="item.id">
            <div class="feed-user">
              <img :src="item.userAvatar || defaultAvatar" class="feed-avatar" />
              <span class="feed-name">{{ item.userName }}</span>
            </div>
            <div class="feed-content">{{ item.content }}</div>
            <div class="feed-meta">
              <span>{{ item.likeCount || 0 }}赞</span>
              <span>{{ item.commentCount || 0 }}评论</span>
              <span>{{ formatTime(item.createdTime) }}</span>
            </div>
          </div>
        </div>

        <div v-if="feedLoading" class="loading-tip">加载中...</div>
        <div v-if="!feedHasMore && feedList.length > 0" class="no-more">没有更多了</div>
        <div v-if="feedList.length === 0 && !feedLoading" class="empty-tip">暂无内容</div>
      </div>

      <!-- 右侧边栏 -->
      <div class="content-sidebar">
        <!-- 相关圈子 -->
        <div class="sidebar-section" v-if="topic.circleInfo && topic.circleInfo.length > 0">
          <h3>相关圈子</h3>
          <div class="circle-item" v-for="circle in topic.circleInfo" :key="circle.circleId">
            <span class="circle-name">{{ circle.circleName || '圈子' + circle.circleId }}</span>
            <span class="circle-members">{{ circle.memberCount }}人</span>
          </div>
        </div>
        <!-- 推荐话题 -->
        <div class="sidebar-section">
          <recommend-topics :exclude-id="Number(topicId)" />
        </div>
      </div>
    </div>

    <!-- 底部悬浮栏（type=2 文章+沸点模式） -->
    <div class="bottom-bar" v-if="topic.type === 2">
      <button class="write-btn" @click="writeArticle">写文章</button>
      <button class="pin-btn" @click="openPublish">发沸点</button>
    </div>
  </div>
</template>

<script>
import { getTopicDetail, getTopicFeed, incrTopicView } from '@/apis/topic'
import RecommendTopics from '@/components/RecommendTopics.vue'

export default {
  name: 'TopicDetail',
  components: { RecommendTopics },
  data() {
    return {
      topic: { type: 1, circleInfo: [] },
      activeTab: 'hot',
      feedList: [],
      feedCursor: 0,
      feedHasMore: true,
      feedLoading: false,
      defaultAvatar: 'data:image/svg+xml,%3Csvg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100"%3E%3Ccircle cx="50" cy="50" r="50" fill="%23ddd"/%3E%3C/svg%3E'
    }
  },
  computed: {
    topicId() {
      return this.$route.params.id
    },
    availableTabs() {
      return this.topic.availableTabs || ['hot', 'new', 'pin']
    }
  },
  mounted() {
    this.loadDetail()
    incrTopicView(this.topicId).catch(() => {})
    window.addEventListener('scroll', this.handleScroll)
    // 如果 URL 带 publish=1，自动弹出发布框
    if (this.$route.query.publish === '1') {
      this.openPublish()
    }
  },
  beforeDestroy() {
    window.removeEventListener('scroll', this.handleScroll)
  },
  methods: {
    async loadDetail() {
      try {
        const res = await getTopicDetail(this.topicId)
        if (res && res.code === 200) {
          this.topic = res.data || this.topic
          if (this.activeTab === 'article' && !this.availableTabs.includes('article')) {
            this.activeTab = 'hot'
          }
          this.loadFeed(true)
        }
      } catch (e) {
        console.error('加载话题详情失败:', e)
      }
    },
    async loadFeed(reset = false) {
      if (this.feedLoading || (!this.feedHasMore && !reset)) return
      if (reset) {
        this.feedCursor = 0
        this.feedList = []
        this.feedHasMore = true
      }
      this.feedLoading = true
      try {
        const res = await getTopicFeed(this.topicId, {
          tab: this.activeTab,
          cursor: this.feedCursor,
          size: 20
        })
        if (res && res.code === 200) {
          const data = res.data || {}
          this.feedList = reset ? (data.list || []) : [...this.feedList, ...(data.list || [])]
          this.feedCursor = data.cursor || this.feedCursor
          this.feedHasMore = data.has_more !== false
        }
      } catch (e) {
        console.error('加载话题Feed失败:', e)
      } finally {
        this.feedLoading = false
      }
    },
    switchTab(tab) {
      this.activeTab = tab
      this.loadFeed(true)
    },
    tabLabel(tab) {
      const map = { hot: '热门', new: '最新', article: '文章', pin: '沸点' }
      return map[tab] || tab
    },
    handleScroll() {
      const scrollTop = window.pageYOffset || document.documentElement.scrollTop
      const windowHeight = window.innerHeight
      const documentHeight = document.documentElement.scrollHeight
      if (scrollTop + windowHeight >= documentHeight - 300) {
        this.loadFeed()
      }
    },
    formatCount(num) {
      if (!num) return '0'
      if (num >= 10000) return (num / 1000).toFixed(1) + 'k'
      return num.toString()
    },
    formatTime(time) {
      if (!time) return ''
      const d = new Date(time)
      const now = new Date()
      const diff = now - d
      if (diff < 60000) return '刚刚'
      if (diff < 3600000) return Math.floor(diff / 60000) + '分钟前'
      if (diff < 86400000) return Math.floor(diff / 3600000) + '小时前'
      return d.toLocaleDateString()
    },
    openPublish() {
      // 触发全局发布框事件，带入话题
      this.$root.$emit('open-pins-publish', { topicId: this.topicId, topicName: this.topic.name })
    },
    writeArticle() {
      this.$router.push('/creator/article/edit')
    }
  }
}
</script>

<style lang="less" scoped>
.topic-detail {
  max-width: 1100px;
  margin: 0 auto;
  padding: 24px 20px;

  .topic-header {
    margin-bottom: 24px;
    h1 {
      font-size: 28px;
      font-weight: 700;
      color: #1e80ff;
      margin-bottom: 12px;
    }
    .topic-stats {
      display: flex;
      gap: 24px;
      font-size: 14px;
      color: #515767;
      margin-bottom: 12px;
    }
    .topic-desc {
      font-size: 15px;
      color: #86909c;
      line-height: 1.6;
    }
  }

  .topic-content {
    display: flex;
    gap: 24px;
  }

  .content-main {
    flex: 1;
    min-width: 0;
  }

  .content-sidebar {
    width: 300px;
    flex-shrink: 0;
  }

  .publish-card {
    background: #fff;
    border: 1px solid #e5e6eb;
    border-radius: 8px;
    padding: 16px;
    margin-bottom: 16px;
    cursor: pointer;
    &:hover { border-color: #1e80ff; }

    .publish-input {
      color: #86909c;
      font-size: 14px;
      margin-bottom: 12px;
    }
    .publish-actions {
      display: flex;
      gap: 12px;
      .action-btn {
        font-size: 18px;
        cursor: pointer;
      }
    }
  }

  .tab-bar {
    display: flex;
    gap: 0;
    border-bottom: 1px solid #e5e6eb;
    margin-bottom: 16px;
    .tab-item {
      padding: 10px 20px;
      font-size: 14px;
      color: #515767;
      cursor: pointer;
      border-bottom: 2px solid transparent;
      transition: all 0.2s;
      &.active {
        color: #1e80ff;
        border-bottom-color: #1e80ff;
        font-weight: 500;
      }
      &:hover { color: #1e80ff; }
    }
  }

  .feed-list {
    .feed-item {
      background: #fff;
      border-radius: 8px;
      padding: 16px;
      margin-bottom: 12px;
      border: 1px solid #f0f0f0;

      .feed-user {
        display: flex;
        align-items: center;
        gap: 8px;
        margin-bottom: 10px;
        .feed-avatar {
          width: 32px;
          height: 32px;
          border-radius: 50%;
        }
        .feed-name {
          font-size: 14px;
          font-weight: 500;
          color: #252933;
        }
      }
      .feed-content {
        font-size: 15px;
        color: #252933;
        line-height: 1.6;
        margin-bottom: 10px;
      }
      .feed-meta {
        display: flex;
        gap: 16px;
        font-size: 13px;
        color: #86909c;
      }
    }
  }

  .sidebar-section {
    background: #fff;
    border-radius: 8px;
    padding: 16px;
    margin-bottom: 16px;
    border: 1px solid #f0f0f0;

    h3 {
      font-size: 16px;
      font-weight: 600;
      margin-bottom: 12px;
    }

    .circle-item {
      display: flex;
      justify-content: space-between;
      padding: 8px 0;
      font-size: 14px;
      border-bottom: 1px solid #f5f5f5;
      &:last-child { border-bottom: none; }
      .circle-name { color: #252933; }
      .circle-members { color: #86909c; }
    }
  }

  .bottom-bar {
    position: fixed;
    bottom: 0;
    left: 0;
    right: 0;
    background: #fff;
    border-top: 1px solid #e5e6eb;
    padding: 12px 24px;
    display: flex;
    gap: 12px;
    justify-content: center;
    z-index: 100;

    button {
      padding: 10px 32px;
      border-radius: 20px;
      font-size: 15px;
      cursor: pointer;
      border: none;
    }
    .write-btn {
      background: #fff;
      color: #1e80ff;
      border: 1px solid #1e80ff;
      &:hover { background: #f0f7ff; }
    }
    .pin-btn {
      background: #1e80ff;
      color: #fff;
      &:hover { background: #1171ee; }
    }
  }

  .loading-tip, .no-more, .empty-tip {
    text-align: center;
    padding: 20px;
    color: #999;
    font-size: 14px;
  }
}

@media (max-width: 768px) {
  .topic-detail {
    .topic-content { flex-direction: column; }
    .content-sidebar { width: 100%; }
  }
}
</style>