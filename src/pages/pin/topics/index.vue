<template>
  <div class="topic-square">
    <div class="square-header">
      <h2>话题广场</h2>
      <div class="search-box">
        <input v-model="keyword" placeholder="搜索话题名称" @keyup.enter="doSearch" />
        <button @click="doSearch">搜索</button>
      </div>
    </div>
    <div class="topic-grid">
      <div class="topic-card" v-for="topic in topics" :key="topic.id" @click="goTopic(topic)">
        <div class="card-body">
          <h3 class="card-title">#{{ topic.name }}#</h3>
          <p class="card-desc">{{ topic.description }}</p>
          <div class="card-stats">
            <span>{{ topic.participantCount }}位掘友已发布精彩内容</span>
            <span>{{ formatViewCount(topic.viewCount) }}阅读</span>
          </div>
        </div>
        <div class="card-action">
          <button class="publish-btn" @click.stop="publishPin(topic)">发沸点</button>
        </div>
      </div>
    </div>
    <div v-if="loading" class="loading-tip">加载中...</div>
    <div v-if="!hasMore && topics.length > 0" class="no-more">没有更多了</div>
    <div v-if="topics.length === 0 && !loading" class="empty-tip">暂无话题</div>
  </div>
</template>

<script>
import { getTopicSquare } from '@/apis/topic'

export default {
  name: 'TopicSquare',
  data() {
    return {
      topics: [],
      keyword: '',
      sort: 'hot',
      cursor: 0,
      hasMore: true,
      loading: false
    }
  },
  mounted() {
    this.loadTopics()
    window.addEventListener('scroll', this.handleScroll)
  },
  beforeDestroy() {
    window.removeEventListener('scroll', this.handleScroll)
  },
  methods: {
    async loadTopics(reset = false) {
      if (this.loading || (!this.hasMore && !reset)) return
      if (reset) {
        this.cursor = 0
        this.topics = []
        this.hasMore = true
      }
      this.loading = true
      try {
        const res = await getTopicSquare({
          keyword: this.keyword,
          sort: this.sort,
          cursor: this.cursor,
          size: 20
        })
        if (res && res.code === 200) {
          const data = res.data || {}
          this.topics = reset ? (data.list || []) : [...this.topics, ...(data.list || [])]
          this.cursor = data.cursor || this.cursor
          this.hasMore = data.has_more !== false
        }
      } catch (e) {
        console.error('加载话题广场失败:', e)
      } finally {
        this.loading = false
      }
    },
    doSearch() {
      this.loadTopics(true)
    },
    handleScroll() {
      const scrollTop = window.pageYOffset || document.documentElement.scrollTop
      const windowHeight = window.innerHeight
      const documentHeight = document.documentElement.scrollHeight
      if (scrollTop + windowHeight >= documentHeight - 200) {
        this.loadTopics()
      }
    },
    formatViewCount(num) {
      if (!num) return '0'
      if (num >= 10000) {
        return (num / 1000).toFixed(1) + 'k'
      }
      return num.toString()
    },
    goTopic(topic) {
      this.$router.push(`/pin/topic/${topic.id}`)
    },
    publishPin(topic) {
      this.$router.push(`/pin/topic/${topic.id}?publish=1`)
    }
  }
}
</script>

<style lang="less" scoped>
.topic-square {
  max-width: 960px;
  margin: 0 auto;
  padding: 24px 20px;

  .square-header {
    margin-bottom: 24px;
    h2 {
      font-size: 24px;
      font-weight: 600;
      margin-bottom: 16px;
    }
    .search-box {
      display: flex;
      gap: 8px;
      input {
        flex: 1;
        max-width: 400px;
        padding: 8px 12px;
        border: 1px solid #ddd;
        border-radius: 6px;
        font-size: 14px;
        outline: none;
        &:focus { border-color: #1e80ff; }
      }
      button {
        padding: 8px 20px;
        background: #1e80ff;
        color: #fff;
        border: none;
        border-radius: 6px;
        cursor: pointer;
        font-size: 14px;
        &:hover { background: #1171ee; }
      }
    }
  }

  .topic-grid {
    display: grid;
    grid-template-columns: repeat(2, 1fr);
    gap: 16px;
  }

  .topic-card {
    background: #fff;
    border-radius: 12px;
    padding: 20px;
    cursor: pointer;
    transition: box-shadow 0.2s;
    border: 1px solid #f0f0f0;
    display: flex;
    flex-direction: column;
    justify-content: space-between;

    &:hover { box-shadow: 0 4px 12px rgba(0,0,0,0.08); }

    .card-body {
      .card-title {
        font-size: 18px;
        font-weight: 600;
        color: #1e80ff;
        margin-bottom: 8px;
      }
      .card-desc {
        font-size: 13px;
        color: #86909c;
        margin-bottom: 12px;
        line-height: 1.5;
        min-height: 36px;
      }
      .card-stats {
        font-size: 13px;
        color: #515767;
        display: flex;
        gap: 16px;
      }
    }

    .card-action {
      margin-top: 16px;
      .publish-btn {
        width: 100%;
        padding: 8px 0;
        background: #1e80ff;
        color: #fff;
        border: none;
        border-radius: 6px;
        cursor: pointer;
        font-size: 14px;
        &:hover { background: #1171ee; }
      }
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
  .topic-square .topic-grid {
    grid-template-columns: 1fr;
  }
}
</style>