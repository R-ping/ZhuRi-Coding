<template>
  <div class="inspiration-page">
    <div class="page-header">
      <h2 class="page-title">创作灵感</h2>
    </div>

    <!-- Tab切换 -->
    <div class="tab-bar">
      <div
        class="tab-item"
        :class="{ active: activeTab === 'topics' }"
        @click="switchTab('topics')"
      >创作话题</div>
      <div
        class="tab-item"
        :class="{ active: activeTab === 'activities' }"
        @click="switchTab('activities')"
      >创作活动</div>
    </div>

    <!-- 创作话题列表 -->
    <div v-if="activeTab === 'topics'" class="topics-section">
      <div class="sort-bar">
        <span
          class="sort-item"
          :class="{ active: topicSort === 'hot' }"
          @click="topicSort = 'hot'; fetchTopics(1)"
        >热度排序</span>
        <span
          class="sort-item"
          :class="{ active: topicSort === 'participants' }"
          @click="topicSort = 'participants'; fetchTopics(1)"
        >参与数排序</span>
      </div>

      <div class="topic-list">
        <div v-for="topic in topicList" :key="topic.id" class="topic-card" @click="goToTopicDetail(topic)">
          <div class="topic-info">
            <h3 class="topic-name">#{{ topic.name }}#</h3>
            <p class="topic-desc">{{ topic.description }}</p>
            <div class="topic-stats">
              <span class="stat-item">{{ topic.participantCount || 0 }} 人参与</span>
              <span class="stat-divider">·</span>
              <span class="stat-item">{{ topic.viewCount || 0 }} 阅读</span>
            </div>
          </div>
          <div class="topic-actions">
            <el-button size="small" type="primary" @click.stop="createArticle(topic)">发文章</el-button>
            <el-button size="small" @click.stop="createPin(topic)">发沸点</el-button>
          </div>
        </div>
        <div v-if="topicList.length === 0" class="empty-state">暂无话题</div>
      </div>

      <div class="pagination-wrapper" v-if="topicTotal > 0">
        <el-pagination
          layout="prev, pager, next"
          :total="topicTotal"
          :page-size="topicSize"
          :current-page="topicPage"
          @current-change="fetchTopics"
        />
      </div>
    </div>

    <!-- 创作活动列表 -->
    <div v-if="activeTab === 'activities'" class="activities-section">
      <div class="filter-bar">
        <div class="filter-group">
          <label>类型：</label>
          <el-radio-group v-model="activityFilter.type" size="small" @change="fetchActivities(1)">
            <el-radio-button label="">全部</el-radio-button>
            <el-radio-button label="article">文章</el-radio-button>
            <el-radio-button label="pin">沸点</el-radio-button>
          </el-radio-group>
        </div>
        <div class="filter-group">
          <label>状态：</label>
          <el-radio-group v-model="activityFilter.status" size="small" @change="fetchActivities(1)">
            <el-radio-button label="">全部</el-radio-button>
            <el-radio-button label="ongoing">进行中</el-radio-button>
            <el-radio-button label="ended">已结束</el-radio-button>
          </el-radio-group>
        </div>
        <div class="filter-group">
          <label>分类：</label>
          <el-radio-group v-model="activityFilter.category" size="small" @change="fetchActivities(1)">
            <el-radio-button label="hot">热门</el-radio-button>
            <el-radio-button label="backend">后端</el-radio-button>
            <el-radio-button label="frontend">前端</el-radio-button>
            <el-radio-button label="android">Android</el-radio-button>
            <el-radio-button label="ios">iOS</el-radio-button>
            <el-radio-button label="ai">人工智能</el-radio-button>
            <el-radio-button label="devtools">开发工具</el-radio-button>
            <el-radio-button label="codelife">代码人生</el-radio-button>
          </el-radio-group>
        </div>
      </div>

      <div class="activity-list">
        <div v-for="activity in activityList" :key="activity.id" class="activity-card">
          <img v-if="activity.coverImage" :src="activity.coverImage" class="activity-cover" />
          <div class="activity-info">
            <h3 class="activity-title">{{ activity.title }}</h3>
            <p class="activity-desc">{{ activity.description }}</p>
            <div class="activity-meta">
              <span class="meta-item">{{ activity.startDate }} ~ {{ activity.endDate }}</span>
              <span class="meta-divider">|</span>
              <span class="meta-item">{{ activity.totalParticipants || 0 }} 人参与</span>
            </div>
            <div class="activity-tags">
              <el-tag size="mini" :type="activity.status === 'ongoing' ? 'success' : 'info'">
                {{ activity.status === 'ongoing' ? '进行中' : activity.status === 'upcoming' ? '即将开始' : '已结束' }}
              </el-tag>
              <el-tag size="mini" type="warning">{{ activity.category }}</el-tag>
            </div>
          </div>
        </div>
        <div v-if="activityList.length === 0" class="empty-state">暂无活动</div>
      </div>

      <div class="pagination-wrapper" v-if="activityTotal > 0">
        <el-pagination
          layout="prev, pager, next"
          :total="activityTotal"
          :page-size="activitySize"
          :current-page="activityPage"
          @current-change="fetchActivities"
        />
      </div>
    </div>
  </div>
</template>

<script>
import { getInspirationTopics, getInspirationActivities } from '@/apis/creator/inspiration'

export default {
  name: 'CreatorInspiration',
  data() {
    return {
      activeTab: 'topics',
      // 话题
      topicList: [],
      topicPage: 1,
      topicSize: 20,
      topicTotal: 0,
      topicSort: 'hot',
      // 活动
      activityList: [],
      activityPage: 1,
      activitySize: 20,
      activityTotal: 0,
      activityFilter: {
        type: '',
        status: '',
        category: 'hot'
      }
    }
  },
  mounted() {
    this.fetchTopics(1)
  },
  methods: {
    switchTab(tab) {
      this.activeTab = tab
      if (tab === 'topics' && this.topicList.length === 0) {
        this.fetchTopics(1)
      } else if (tab === 'activities' && this.activityList.length === 0) {
        this.fetchActivities(1)
      }
    },
    async fetchTopics(page) {
      this.topicPage = page
      try {
        const res = await getInspirationTopics({
          page,
          size: this.topicSize,
          sort: this.topicSort,
          themeType: 1
        })
        if (res && res.code === 200 && res.data) {
          this.topicList = res.data.list || []
          this.topicTotal = res.data.total || 0
        }
      } catch (e) {
        this.topicList = []
      }
    },
    async fetchActivities(page) {
      this.activityPage = page
      try {
        const res = await getInspirationActivities({
          page,
          size: this.activitySize,
          type: this.activityFilter.type || undefined,
          status: this.activityFilter.status || undefined,
          category: this.activityFilter.category === 'hot' ? undefined : this.activityFilter.category
        })
        if (res && res.code === 200 && res.data) {
          this.activityList = res.data.list || []
          this.activityTotal = res.data.total || 0
        }
      } catch (e) {
        this.activityList = []
      }
    },
    goToTopicDetail(topic) {
      this.$router.push(`/creator/growth/topic/${topic.id}`)
    },
    createArticle(topic) {
      this.$router.push(`/creator/publish?topicId=${topic.id}&topicName=${encodeURIComponent(topic.name)}`)
    },
    createPin(topic) {
      this.$router.push(`/pins?topicId=${topic.id}&topicName=${encodeURIComponent(topic.name)}`)
    }
  }
}
</script>

<style lang="less" scoped>
@import '../layout/styles/variables.less';

.inspiration-page {
  min-height: 100vh;
  background: @bgGray;
  padding: 24px 32px;
}

.page-header {
  margin-bottom: 20px;
  .page-title {
    font-size: 20px;
    font-weight: 600;
    color: @textPrimary;
    margin: 0;
  }
}

.tab-bar {
  display: flex;
  gap: 0;
  border-bottom: 2px solid @borderLight;
  margin-bottom: 20px;
  .tab-item {
    padding: 12px 24px;
    font-size: 15px;
    color: @textSecondary;
    cursor: pointer;
    border-bottom: 2px solid transparent;
    margin-bottom: -2px;
    transition: all 0.2s;
    &:hover { color: @brandBlue; }
    &.active {
      color: @brandBlue;
      border-bottom-color: @brandBlue;
      font-weight: 600;
    }
  }
}

.sort-bar {
  display: flex;
  gap: 16px;
  margin-bottom: 16px;
  .sort-item {
    font-size: 14px;
    color: @textMuted;
    cursor: pointer;
    padding: 4px 8px;
    border-radius: 4px;
    transition: all 0.2s;
    &:hover { color: @brandBlue; }
    &.active {
      color: @brandBlue;
      font-weight: 600;
    }
  }
}

.topic-list {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
}

.topic-card {
  background: #fff;
  border: 1px solid @borderLight;
  border-radius: 8px;
  padding: 16px 20px;
  display: flex;
  justify-content: space-between;
  align-items: center;
  cursor: pointer;
  transition: all 0.2s;
  &:hover {
    border-color: @brandBlue;
    box-shadow: 0 2px 8px rgba(30, 128, 255, 0.1);
  }
  .topic-info {
    flex: 1;
    .topic-name {
      font-size: 16px;
      font-weight: 600;
      color: @textPrimary;
      margin: 0 0 4px;
    }
    .topic-desc {
      font-size: 13px;
      color: @textMuted;
      margin: 0 0 8px;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
      max-width: 500px;
    }
    .topic-stats {
      font-size: 12px;
      color: #c2c8d1;
      .stat-divider { margin: 0 6px; }
    }
  }
  .topic-actions {
    display: none;
    gap: 8px;
    flex-shrink: 0;
  }
  &:hover .topic-actions {
    display: flex;
  }
}

.filter-bar {
  margin-bottom: 20px;
  .filter-group {
    display: flex;
    align-items: center;
    gap: 8px;
    margin-bottom: 12px;
    label {
      font-size: 13px;
      color: @textMuted;
      white-space: nowrap;
      min-width: 40px;
    }
  }
}

.activity-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.activity-card {
  background: #fff;
  border: 1px solid @borderLight;
  border-radius: 8px;
  padding: 16px;
  display: flex;
  gap: 16px;
  .activity-cover {
    width: 120px;
    height: 80px;
    border-radius: 4px;
    object-fit: cover;
    flex-shrink: 0;
  }
  .activity-info {
    flex: 1;
    .activity-title {
      font-size: 15px;
      font-weight: 600;
      color: @textPrimary;
      margin: 0 0 4px;
    }
    .activity-desc {
      font-size: 13px;
      color: @textMuted;
      margin: 0 0 8px;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }
    .activity-meta {
      font-size: 12px;
      color: #c2c8d1;
      margin-bottom: 8px;
      .meta-divider { margin: 0 8px; }
    }
    .activity-tags {
      display: flex;
      gap: 6px;
    }
  }
}

.empty-state {
  text-align: center;
  padding: 60px 0;
  color: #c2c8d1;
  font-size: 14px;
}

.pagination-wrapper {
  display: flex;
  justify-content: center;
  margin-top: 24px;
}
</style>