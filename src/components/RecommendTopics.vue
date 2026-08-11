<template>
  <div class="recommend-topics">
    <!-- 标题行 + 换一换 -->
    <div class="rt-header">
      <span class="rt-title">{{ title }}</span>
      <span class="rt-refresh" v-if="showRefresh" @click="refresh">换一换</span>
    </div>

    <!-- 话题列表 -->
    <div class="rt-list" v-if="topics.length > 0">
      <div
        class="rt-item"
        v-for="topic in topics"
        :key="topic.id"
        @click="goTopic(topic)"
      >
        <span class="rt-name">#{{ topic.name }}#</span>
        <span class="rt-count">{{ formatCount(topic.participantCount || topic.postCount || topic.count || 0) }} 讨论</span>
      </div>
    </div>
    <div class="rt-empty" v-else>
      <span>暂无推荐话题</span>
    </div>

    <!-- 查看更多 -->
    <div class="rt-more" v-if="showMore" @click="goMore">查看更多 &gt;</div>
  </div>
</template>

<script>
import { getRecommendTopics } from '@/apis/topic'

export default {
  name: 'RecommendTopics',
  props: {
    // 卡片标题
    title: {
      type: String,
      default: '推荐话题'
    },
    // 每次拉取条数
    size: {
      type: Number,
      default: 5
    },
    // 需从结果中排除的话题 id（如当前所在话题详情页）
    excludeId: {
      type: [Number, String],
      default: null
    },
    // 是否显示“换一换”
    showRefresh: {
      type: Boolean,
      default: true
    },
    // 是否显示“查看更多”
    showMore: {
      type: Boolean,
      default: true
    },
    // “查看更多”跳转地址
    moreTarget: {
      type: String,
      default: '/pin/topics'
    }
  },
  data() {
    return {
      topics: [],
      page: 0
    }
  },
  mounted() {
    this.loadRecommend()
  },
  methods: {
    async loadRecommend() {
      try {
        const res = await getRecommendTopics(this.page, this.size)
        if (res && res.code === 200) {
          const data = res.data
          let list = Array.isArray(data) ? data : (data && data.list ? data.list : [])
          if (this.excludeId != null && this.excludeId !== '') {
            list = list.filter(t => {
              const id = Number(t.id)
              return !(id === Number(this.excludeId))
            })
          }
          this.topics = list
        }
      } catch (e) {
        console.error('加载推荐话题失败', e)
      }
    },
    // “换一换”：递增页码并重新加载
    refresh() {
      this.page++
      this.loadRecommend()
    },
    goTopic(topic) {
      this.$router.push(`/pin/topic/${topic.id}`)
    },
    goMore() {
      this.$router.push(this.moreTarget)
    },
    formatCount(count) {
      if (!count) return '0'
      if (count >= 1000) {
        return (count / 1000).toFixed(1) + 'k'
      }
      return String(count)
    }
  }
}
</script>

<style lang="less" scoped>
.recommend-topics {
  .rt-header {
    display: flex;
    justify-content: space-between;
    align-items: center;
    margin-bottom: 12px;

    .rt-title {
      font-size: 16px;
      font-weight: 600;
      color: #252933;
    }

    .rt-refresh {
      font-size: 13px;
      color: #1e80ff;
      cursor: pointer;
      &:hover {
        opacity: 0.8;
      }
    }
  }

  .rt-list {
    .rt-item {
      display: flex;
      justify-content: space-between;
      align-items: center;
      padding: 8px 0;
      cursor: pointer;
      font-size: 14px;
      border-bottom: 1px solid #f5f5f5;
      &:last-child {
        border-bottom: none;
      }
      &:hover .rt-name {
        color: #1e80ff;
      }

      .rt-name {
        color: #1e80ff;
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
      }

      .rt-count {
        flex-shrink: 0;
        margin-left: 8px;
        color: #86909c;
        font-size: 13px;
      }
    }
  }

  .rt-empty {
    padding: 16px 0;
    text-align: center;
    color: #86909c;
    font-size: 14px;
  }

  .rt-more {
    margin-top: 12px;
    padding-top: 12px;
    border-top: 1px solid #f0f0f0;
    font-size: 14px;
    color: #1e80ff;
    cursor: pointer;
    &:hover {
      opacity: 0.8;
    }
  }
}
</style>