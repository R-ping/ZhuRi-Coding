<template>
  <section class="growth-tasks">
    <header class="section-header">
      <div class="header-left">
        <span class="section-title">创作任务</span>
        <span class="section-sub">完成今日任务，提升逐力值等级</span>
      </div>
      <span class="done-count">{{ doneCount }}/{{ tasks.length }}</span>
    </header>
    <div class="task-list">
      <div class="task-item" v-for="task in tasks" :key="task.actionType">
        <span class="task-icon">{{ task.icon }}</span>
        <div class="task-info">
          <div class="task-title">{{ task.title }}</div>
          <div class="task-desc">{{ task.reward }}</div>
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

export default {
  name: 'GrowthTasks',
  data() {
    return {
      // 创作任务：取自逐日等级「社区活跃」分组（每日有上限）
      tasks: [
        { actionType: 'publish_article', icon: '✍️', title: '发布一篇文章', reward: '+8 逐力值', btnText: '去发布', completed: false },
        { actionType: 'publish_pin', icon: '💬', title: '发布一条沸点', reward: '+8 逐力值', btnText: '去发布', completed: false },
        { actionType: 'comment_article', icon: '💬', title: '评论一篇文章', reward: '+2 逐力值', btnText: '去评论', completed: false },
        { actionType: 'comment_pin', icon: '💬', title: '评论一条沸点', reward: '+2 逐力值', btnText: '去评论', completed: false },
        { actionType: 'like_article', icon: '👍', title: '点赞一篇文章', reward: '+1 逐力值', btnText: '去点赞', completed: false },
        { actionType: 'like_pin', icon: '👍', title: '点赞一条沸点', reward: '+1 逐力值', btnText: '去点赞', completed: false },
        { actionType: 'collect_article', icon: '⭐', title: '收藏一篇文章', reward: '+1 逐力值', btnText: '去收藏', completed: false },
        { actionType: 'follow_user', icon: '👤', title: '关注一位掘友', reward: '+4 逐力值', btnText: '去关注', completed: false }
      ]
    }
  },
  computed: {
    doneCount() {
      return this.tasks.filter(t => t.completed).length
    }
  },
  methods: {
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