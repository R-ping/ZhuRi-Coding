<template>
  <div class="topic-detail">
    <!-- 顶部信息区（白底卡片） -->
    <div class="topic-header-card">
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
        <!-- 纯沸点模式：发布入口卡片（深色、话题名、无图标），点击弹发布框 -->
        <div class="publish-entry" v-if="topic.type === 1" @click="openPublish">
          <span class="entry-text">#{{ topic.name }}</span>
        </div>

        <!-- 一级分栏（仅混合话题）：文章 | 沸点 -->
        <div class="level1-tabs" v-if="topic.type === 2">
          <span
            class="tab-item"
            :class="{ active: contentType === 'article' }"
            @click="switchContent('article')"
          >文章</span>
          <span
            class="tab-item"
            :class="{ active: contentType === 'pin' }"
            @click="switchContent('pin')"
          >沸点</span>
        </div>

        <!-- 二级分栏：热门 | 最新 -->
        <div class="level2-tabs">
          <span
            class="tab-item"
            :class="{ active: sortType === 'hot' }"
            @click="switchSort('hot')"
          >热门</span>
          <span
            class="tab-item"
            :class="{ active: sortType === 'new' }"
            @click="switchSort('new')"
          >最新</span>
        </div>

        <!-- 内容列表（白底卡片） -->
        <div class="feed-list">
          <!-- 文章卡片 -->
          <div
            class="article-card"
            v-for="item in feedList"
            :key="'a' + item.id"
            v-if="contentType === 'article'"
            @click="goArticle(item)"
          >
            <div class="article-info">
              <h3 class="article-title">{{ item.title }}</h3>
              <div class="article-meta">
                <span class="meta-author">{{ item.authorName }}</span>
                <span class="meta-channel">{{ item.channelName }}</span>
                <span class="meta-stat">{{ formatCount(item.viewCount) }}阅读</span>
                <span class="meta-stat">{{ formatCount(item.commentCount) }}评论</span>
              </div>
            </div>
            <img
              v-if="item.coverImage"
              :src="item.coverImage"
              class="article-cover"
              alt="cover"
            />
          </div>

          <!-- 沸点卡片 -->
          <div
            class="feed-item"
            v-for="item in feedList"
            :key="'p' + item.id"
            v-if="contentType === 'pin'"
          >
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
          <recommend-topics />
        </div>
      </div>
    </div>

    <!-- 发布弹窗（复用公共组件） -->
    <PinsPublishModal
      v-if="showPublishModal"
      v-model="publishContent"
      :selectedTopic="selectedTopic"
      :publishing="publishing"
      @close="closePublishModal"
      @publish="handlePublish"
    />

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
import PinsPublishModal from '@/pages/creator/pins/components/PinsPublishModal.vue'
import { publishPins } from '@/apis/pins'
import { toast } from '@/utils/toast'

const defaultAvatar = 'data:image/svg+xml,%3Csvg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100"%3E%3Ccircle cx="50" cy="50" r="50" fill="%23ddd"/%3E%3C/svg%3E'

export default {
  name: 'TopicDetail',
  components: { RecommendTopics, PinsPublishModal },
  data() {
    return {
      topic: { type: 1, circleInfo: [] },
      // 一级分栏：article / pin；二级分栏：hot / new
      contentType: 'pin',
      sortType: 'hot',
      feedList: [],
      feedCursor: 0,
      feedHasMore: true,
      feedLoading: false,
      showPublishModal: false,
      publishing: false,
      publishContent: '',
      selectedTopic: null,
      defaultAvatar
    }
  },
  computed: {
    topicId() {
      return this.$route.params.id
    },
    // 当前 feed 使用的 tab 参数
    activeTab() {
      if (this.contentType === 'article') {
        return 'article_' + this.sortType
      }
      return this.sortType
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
          // 根据话题类型初始化一级分栏
          if (this.topic.type === 2) {
            this.contentType = 'article'
          } else {
            this.contentType = 'pin'
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
    switchContent(type) {
      if (this.contentType === type) return
      this.contentType = type
      this.loadFeed(true)
    },
    switchSort(type) {
      if (this.sortType === type) return
      this.sortType = type
      this.loadFeed(true)
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
      // 预填当前话题并弹出发布框
      this.selectedTopic = { id: this.topicId, name: this.topic.name }
      this.showPublishModal = true
    },
    closePublishModal() {
      this.showPublishModal = false
      this.publishContent = ''
    },
    async handlePublish(data) {
      if (this.publishing) return
      this.publishing = true
      try {
        const res = await publishPins(data)
        if (res && res.code === 200) {
          toast('发布成功！')
          this.closePublishModal()
          this.loadFeed(true)
        } else {
          toast('发布失败，请重试')
        }
      } catch (e) {
        console.error('发布沸点失败:', e)
        toast('发布失败，请重试')
      } finally {
        this.publishing = false
      }
    },
    goArticle(item) {
      window.open(`/content/article/${item.id}`, '_blank')
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

  .topic-header-card {
    background: #fff;
    border-radius: 8px;
    padding: 24px;
    margin-bottom: 16px;
    border: 1px solid #f0f0f0;

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
    background: #fff;
    border: 1px solid #f0f0f0;
    border-radius: 8px;
    padding: 16px;
  }

  .content-sidebar {
    width: 300px;
    flex-shrink: 0;
  }

  /* 纯沸点发布入口：灰色卡片，仅显示话题名，点击弹发布框 */
  .publish-entry {
    background: #86909c;
    border-radius: 8px;
    padding: 16px;
    margin-bottom: 16px;
    cursor: pointer;
    transition: background-color 0.2s;
    &:hover {
      background: #6b7785;
    }
    .entry-text {
      color: #fff;
      font-size: 15px;
      font-weight: 500;
    }
  }

  /* 一级分栏：文章 | 沸点 */
  .level1-tabs {
    display: flex;
    gap: 0;
    border-bottom: 2px solid #e5e6eb;
    margin-bottom: 8px;
    .tab-item {
      padding: 10px 20px;
      font-size: 16px;
      font-weight: 600;
      color: #515767;
      cursor: pointer;
      border-bottom: 2px solid transparent;
      margin-bottom: -2px;
      transition: all 0.2s;
      &.active {
        color: #1e80ff;
        border-bottom-color: #1e80ff;
      }
      &:hover { color: #1e80ff; }
    }
  }

  /* 二级分栏：热门 | 最新 */
  .level2-tabs {
    display: flex;
    gap: 0;
    border-bottom: 1px solid #e5e6eb;
    margin-bottom: 16px;
    .tab-item {
      padding: 8px 20px;
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
    .article-card {
      display: flex;
      align-items: center;
      gap: 16px;
      padding: 16px;
      border-bottom: 1px solid #f2f3f5;
      cursor: pointer;
      transition: background-color 0.2s;
      &:last-child { border-bottom: none; }
      &:hover { background: #f7f8fa; }

      .article-info {
        flex: 1;
        min-width: 0;
      }
      .article-title {
        font-size: 16px;
        font-weight: 600;
        color: #252933;
        line-height: 1.5;
        margin: 0 0 12px;
        display: -webkit-box;
        -webkit-line-clamp: 2;
        -webkit-box-orient: vertical;
        overflow: hidden;
      }
      .article-meta {
        display: flex;
        align-items: center;
        gap: 12px;
        font-size: 13px;
        color: #86909c;
        .meta-author { color: #515767; }
      }
      .article-cover {
        width: 120px;
        height: 80px;
        object-fit: cover;
        border-radius: 6px;
        flex-shrink: 0;
      }
    }

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