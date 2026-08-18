<template>
  <aside class="right-aside">
    <!-- 创作活动 -->
    <div class="aside-card activity-card">
      <h4 class="aside-title">创作活动</h4>
      <div class="activity-list">
        <div class="activity-item">
          <span class="activity-tag">创作活动</span>
        </div>
        <div class="activity-item">
          <span class="activity-tag activity-ai">TRAE AI 创造力大赛</span>
        </div>
      </div>
      <a href="javascript:;" class="more-link">查看更多 &gt;</a>
    </div>

    <!-- 创作话题（热门 + 推荐话题统一入口） -->
    <div class="aside-card topics-card">
      <h4 class="aside-title topics-title">
        <span>创作话题</span>
        <span class="refresh-btn" @click="refreshTopics">换一换</span>
      </h4>
      <div class="topic-list">
        <div
          class="topic-item"
          v-for="topic in topics"
          :key="topic.id"
          @click="goTopic(topic)"
        >
          <span class="topic-title">
            <span class="topic-badge" v-if="topic.badge">{{ topic.badge }}</span>
            <span class="hash">#</span>{{ topic.name }}
          </span>
          <span class="topic-meta">{{ formatCount(topic.participantCount) }}位掘友已发布 · {{ formatCount(topic.viewCount) }}阅读</span>
        </div>
        <div v-if="topics.length === 0 && !loading" class="empty-tip">暂无创作话题</div>
      </div>
      <div class="topic-more" @click="goSquare">查看更多话题 &gt;</div>
    </div>
  </aside>
</template>

<script>
import { getRecommendTopics } from '@/apis/topic'

export default {
  name: 'RightAside',
  data() {
    return {
      topics: [],
      page: 0,
      loading: false
    }
  },
  mounted() {
    this.loadTopics()
  },
  methods: {
    async loadTopics() {
      this.loading = true
      try {
        const res = await getRecommendTopics(this.page, 5)
        if (res && res.code === 200 && res.data) {
          this.topics = res.data.list || []
        }
      } catch (e) {
        console.error('加载创作话题失败:', e)
      } finally {
        this.loading = false
      }
    },
    refreshTopics() {
      this.page++
      this.loadTopics()
    },
    formatCount(num) {
      if (!num) return '0'
      if (num >= 10000) {
        return (num / 1000).toFixed(1) + 'k'
      }
      return num.toString()
    },
    goTopic(topic) {
      this.$router.push(`/pin/topic/${topic.id}`)
    },
    goSquare() {
      this.$router.push('/pin/topics')
    }
  }
}
</script>

<style lang="less" scoped>
  @import '../../layout/styles/variables.less';

  .right-aside {
    display: flex;
    flex-direction: column;
    gap: 16px;
  }

  .aside-card {
    background: #fff;
    border-radius: @creatorRadius;
    border: 1px solid @borderLight;
    padding: 18px;
    box-shadow: @creatorShadow;
    transition: box-shadow 0.25s ease;

    &:hover {
      box-shadow: @creatorShadowHover;
    }
  }

  .aside-title {
    margin: 0 0 12px;
    font-size: 16px;
    font-weight: 700;
    color: @textPrimary;
    position: relative;
    padding-left: 10px;

    &::before {
      content: '';
      position: absolute;
      left: 0;
      top: 3px;
      width: 3px;
      height: 14px;
      border-radius: 2px;
      background: @brandGradient;
    }
  }

  .topics-title {
    display: flex;
    align-items: center;
    justify-content: space-between;

    .refresh-btn {
      font-size: 12px;
      font-weight: 400;
      color: @brandBlue;
      cursor: pointer;
      &:hover {
        opacity: 0.8;
      }
    }
  }

  .activity-list {
    display: flex;
    flex-direction: column;
    gap: 10px;
    margin-bottom: 12px;
  }

  .activity-tag {
    display: inline-block;
    padding: 6px 12px;
    font-size: 13px;
    font-weight: 500;
    color: @brandBlue;
    background-color: #E8F3FF;
    border-radius: 4px;
    cursor: pointer;
    transition: background-color 0.2s;

    &:hover {
      background-color: #D6E9FF;
    }

    &.activity-ai {
      color: #7B4DFE;
      background-color: #F0EBFF;

      &:hover {
        background-color: #E4DCFF;
      }
    }
  }

  .more-link {
    display: inline-block;
    font-size: 13px;
    color: @textMuted;
    text-decoration: none;

    &:hover {
      color: @brandBlue;
    }
  }

  .topic-list {
    display: flex;
    flex-direction: column;
    gap: 6px;
  }

  .topic-item {
    display: flex;
    flex-direction: column;
    padding: 8px 0;
    cursor: pointer;
    border-bottom: 1px solid @borderLight;
    transition: background-color 0.2s;

    &:last-child {
      border-bottom: none;
    }

    &:hover {
      .topic-title {
        color: #1171ee;
      }
    }
  }

  .topic-title {
    font-size: 14px;
    font-weight: 500;
    color: @brandBlue;
    margin-bottom: 2px;
    transition: color 0.2s;

    .hash {
      margin-right: 2px;
    }

    .topic-badge {
      display: inline-block;
      background: #ff6b35;
      color: #fff;
      font-size: 11px;
      padding: 1px 5px;
      border-radius: 3px;
      margin-right: 4px;
      vertical-align: middle;
    }
  }

  .topic-meta {
    font-size: 12px;
    color: @colorStatLabel;
  }

  .empty-tip {
    padding: 16px 0;
    text-align: center;
    color: #999;
    font-size: 13px;
  }

  .topic-more {
    margin-top: 12px;
    padding-top: 12px;
    border-top: 1px solid @borderLight;
    text-align: center;
    font-size: 13px;
    color: @brandBlue;
    cursor: pointer;

    &:hover {
      opacity: 0.8;
    }
  }
</style>