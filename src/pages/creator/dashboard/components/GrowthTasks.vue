<template>
  <section class="growth-tasks">
    <header class="section-header">
      <div class="header-left">
        <span class="section-title">创作任务</span>
        <span class="section-sub">完成今日任务，提升逐力值等级</span>
      </div>
      <span class="done-count" v-if="!loading && loadError !== 'noUser'">
        {{ doneCount }}/{{ tasks.length }}
      </span>
    </header>

    <!-- 加载中 -->
    <div v-if="loading" class="state-block loading-block">
      <span class="loading-spinner-icon"></span>
      <span class="state-text">任务进度加载中…</span>
    </div>

    <!-- 未登录：提示用户登录后查看真实进度 -->
    <div v-else-if="loadError === 'noUser'" class="state-block hint-block">
      <span class="state-icon">🔒</span>
      <span class="state-text">登录后查看今日任务进度</span>
    </div>

    <!-- 接口失败：明确告知，避免被误以为是"写死的静态数据" -->
    <div v-else-if="loadError === 'network'" class="state-block hint-block">
      <span class="state-icon">⚠️</span>
      <span class="state-text">任务进度加载失败，<a href="javascript:;" @click="reloadTasks">点击重试</a></span>
    </div>

    <!-- 数据为空 -->
    <div v-else-if="tasks.length === 0" class="state-block hint-block">
      <span class="state-icon">📭</span>
      <span class="state-text">暂无任务</span>
    </div>

    <!-- 正常展示：实时进度 -->
    <div v-else class="task-list">
      <div class="task-item" v-for="task in tasks" :key="task.actionType">
        <span class="task-icon">{{ task.icon }}</span>
        <div class="task-info">
          <div class="task-title">{{ task.title }}</div>
          <div class="task-desc">
            <template v-if="task.completed">已完成</template>
            <template v-else>{{ task.done }}/{{ task.limit }}</template>
            · {{ task.reward }}
          </div>
        </div>
        <button
          class="btn-complete"
          :class="{ completed: task.completed }"
          @click="completeTask(task)"
        >
          {{ task.completed ? '已完成' : task.btnText }}
        </button>
      </div>
    </div>
  </section>
</template>

<script>
import { toast } from '@/utils/toast'
import request from '@/common/article_request'

// 创作者中心"创作任务"仅展示逐日等级「社区活跃」分组的任务（每日有上限）
const COMMUNITY_ACTIVE_ACTIONS = [
  'publish_article', 'publish_pin',
  'comment_article', 'comment_pin',
  'like_article', 'like_pin',
  'collect_article', 'follow_user'
]
const TASK_BTN_MAP = {
  publish_article: '去发布', publish_pin: '去发布',
  comment_article: '去评论', comment_pin: '去评论',
  like_article: '去点赞', like_pin: '去点赞',
  collect_article: '去收藏', follow_user: '去关注'
}
const TASK_ICON_MAP = {
  publish_article: '✍️', publish_pin: '💬',
  comment_article: '💬', comment_pin: '💬',
  like_article: '👍', like_pin: '👍',
  collect_article: '⭐', follow_user: '👤'
}
// 兜底任务（未登录/接口失败时展示，进度为 0；分值与次数上限与 ap_behavior_config 保持一致）
const FALLBACK_TASKS = [
  { actionType: 'publish_article', score: 8, limit: 2 },
  { actionType: 'publish_pin', score: 8, limit: 2 },
  { actionType: 'comment_article', score: 2, limit: 5 },
  { actionType: 'comment_pin', score: 2, limit: 5 },
  { actionType: 'like_article', score: 1, limit: 5 },
  { actionType: 'like_pin', score: 1, limit: 5 },
  { actionType: 'collect_article', score: 1, limit: 2 },
  { actionType: 'follow_user', score: 4, limit: 2 }
]

export default {
  name: 'GrowthTasks',
  data() {
    return {
      tasks: [],
      loading: true,      // 初始化时 loading，避免首屏空白假象
      loadError: null     // null | 'noUser' | 'network'
    }
  },
  computed: {
    doneCount() {
      return this.tasks.filter(t => t.completed).length
    }
  },
  created() {
    this.loadTasks()
  },
  methods: {
    /**
     * 拉取逐日等级任务真实进度（与个人中心成长页同源），筛选"社区活跃"分组展示。
     * 不再静默 fallback：失败时给出明确状态，避免被误以为"写死的静态数据"。
     */
    async loadTasks() {
      this.loading = true
      this.loadError = null

      const userInfo = this.$store && this.$store.state && this.$store.state.user
        ? this.$store.state.user.userInfo : null
      const userId = userInfo ? (userInfo.userId || userInfo.id) : null

      if (!userId) {
        this.loadError = 'noUser'
        this.tasks = []
        this.loading = false
        return
      }

      try {
        const res = await request.get('/api/v1/level/user/' + userId + '/tasks')
        const data = res && res.data
        // 仅收集"社区活跃"分组的任务及其进度
        const doneMap = {}
        if (data && data.growth_tasks && typeof data.growth_tasks === 'object') {
          for (const key of Object.keys(data.growth_tasks)) {
            const groupTasks = data.growth_tasks[key] || []
            for (const t of groupTasks) {
              const code = t.action_code || t.task_id
              if (COMMUNITY_ACTIVE_ACTIONS.indexOf(code) >= 0) {
                doneMap[code] = {
                  done: t.done || 0,
                  limit: t.limit,
                  score: t.score || 0
                }
              }
            }
          }
        }
        // 按固定顺序组装"社区活跃"任务列表，合并真实进度（接口缺数据时用 FALLBACK 兜底，done=0）
        this.tasks = FALLBACK_TASKS.map(f => {
          const m = doneMap[f.actionType] || {}
          const done = m.done || 0
          const limit = m.limit != null ? m.limit : f.limit
          return {
            actionType: f.actionType,
            icon: TASK_ICON_MAP[f.actionType] || '✅',
            title: TASK_TITLE_MAP(f.actionType),
            reward: '+ ' + (m.score != null ? m.score : f.score) + ' 掘友分',
            btnText: TASK_BTN_MAP[f.actionType] || '去完成',
            done: done,
            limit: limit,
            completed: limit > 0 ? done >= limit : false
          }
        })
        this.loadError = null
      } catch (e) {
        // 接口失败：明确提示，不再静默 fallback
        console.warn('[GrowthTasks] 加载任务进度失败：', e)
        this.loadError = 'network'
        this.tasks = []
      } finally {
        this.loading = false
      }
    },
    reloadTasks() {
      this.loadTasks()
    },
    completeTask(task) {
      if (task.completed) {
        toast('该任务已完成')
        return
      }
      // 跳转对应创作入口（引导用户完成任务）
      const route = {
        publish_article: '/creator/publish',
        publish_pin: '/pins',
        comment_article: '/',
        comment_pin: '/pins/hot',
        like_article: '/',
        like_pin: '/pins/new',
        collect_article: '/',
        follow_user: '/recommendation/authors/recommended'
      }[task.actionType]
      if (route) {
        this.$router.push(route)
      }
    }
  }
}

// 任务标题（与个人中心成长页一致）
function TASK_TITLE_MAP(actionType) {
  const m = {
    publish_article: '发布一篇文章',
    publish_pin: '发布一条沸点',
    comment_article: '评论一篇文章',
    comment_pin: '评论一条沸点',
    like_article: '点赞一篇文章',
    like_pin: '点赞一条沸点',
    collect_article: '收藏一篇文章',
    follow_user: '关注一位掘友'
  }
  return m[actionType] || '任务'
}
</script>

<style lang="less" scoped>
  @import '../../layout/styles/variables.less';

  .growth-tasks {
    display: flex;
    flex-direction: column;
    height: 100%;

    .section-header {
      display: flex;
      align-items: center;
      justify-content: space-between;
      padding: 14px 20px;
      border-bottom: 1px solid #f2f3f5;
      flex-shrink: 0;

      .header-left {
        display: flex;
        align-items: baseline;
        gap: 10px;

        .section-title {
          font-size: 16px;
          font-weight: 600;
          color: @textPrimary;
        }

        .section-sub {
          font-size: 12px;
          color: @colorStatLabel;
        }
      }

      .done-count {
        font-size: 13px;
        font-weight: 600;
        color: @brandBlue;
      }
    }

    .task-list {
      padding: 16px 20px;
      display: grid;
      grid-template-columns: 1fr 1fr;
      gap: 10px;
      flex: 1;
      align-content: start;
    }

    // 状态块（loading / noUser / network / empty）
    .state-block {
      flex: 1;
      display: flex;
      flex-direction: column;
      align-items: center;
      justify-content: center;
      gap: 8px;
      padding: 24px;
      color: @colorStatLabel;
      font-size: 13px;

      .state-icon {
        font-size: 28px;
        line-height: 1;
      }
      .state-text {
        text-align: center;
        a {
          color: @brandBlue;
          text-decoration: none;
          &:hover { text-decoration: underline; }
        }
      }
    }
    .loading-block .loading-spinner-icon {
      width: 18px;
      height: 18px;
      border: 2px solid #e5e7eb;
      border-top-color: @brandBlue;
      border-radius: 50%;
      animation: spin 0.8s linear infinite;
    }
    @keyframes spin { to { transform: rotate(360deg); } }
    .hint-block {
      background: #FAFBFC;
      border-radius: 8px;
      margin: 12px 16px;
      flex: 0 0 auto;
    }

    .task-item {
      display: flex;
      align-items: center;
      gap: 12px;
      padding: 10px 14px;
      background-color: @colorTaskBg;
      border-radius: 8px;
      transition: background-color 0.2s;

      &:hover {
        background-color: #eef3fb;
      }

      .task-icon {
        width: 34px;
        height: 34px;
        flex-shrink: 0;
        display: inline-flex;
        align-items: center;
        justify-content: center;
        font-size: 17px;
        background: #fff;
        border-radius: 8px;
        box-shadow: @creatorShadow;
      }

      .task-info {
        flex: 1;
        min-width: 0;

        .task-title {
          font-size: 14px;
          font-weight: 500;
          color: @textPrimary;
          margin-bottom: 2px;
        }

        .task-desc {
          font-size: 12px;
          color: @colorStatLabel;
        }
      }

      .btn-complete {
        flex-shrink: 0;
        padding: 4px 16px;
        font-size: 13px;
        font-weight: 400;
        color: @brandBlue;
        border: 1px solid @brandBlue;
        border-radius: 4px;
        background: transparent;
        cursor: pointer;
        transition: all 0.2s;

        &:hover:not(.completed) {
          background-color: #e8f3ff;
        }

        &.completed {
          color: @colorStatLabel;
          border-color: @borderLight;
          cursor: default;
        }
      }
    }
  }
</style>