<template>
  <div class="creator-dashboard">
    <!-- 左侧主内容区 -->
    <div class="dashboard-main">
      <user-info class="dashboard-header" />

      <!-- 数据概览卡片 -->
      <Article class="dashboard-card dashboard-article" />

      <!-- 创作成长 + 热门话题 两栏 -->
      <div class="dashboard-bottom">
        <div class="dashboard-card dashboard-growth">
          <GrowthTasks />
        </div>
        <div class="dashboard-card dashboard-hot">
          <HotTopics />
        </div>
      </div>
    </div>

    <!-- 右侧边栏 -->
    <RightAside class="dashboard-aside" />
  </div>
</template>

<script>
import UserInfo from './components/UserInfo.vue'
import Article from './components/Article.vue'
import GrowthTasks from './components/GrowthTasks.vue'
import HotTopics from './components/HotTopics.vue'
import RightAside from './components/RightAside.vue'

export default {
  name: 'CreatorDashboard',
  components: {
    UserInfo,
    Article,
    GrowthTasks,
    HotTopics,
    RightAside
  }
}
</script>

<style lang="less">
  @import '../layout/styles/variables.less';

  .creator-dashboard {
    display: grid;
    grid-template-columns: minmax(0, 1fr) 280px;
    gap: 20px;
    padding: 24px;
    background: linear-gradient(180deg, #F1F5FE 0%, @bgGray 220px);
    min-height: calc(100vh - 60px);

    .dashboard-main {
      display: flex;
      flex-direction: column;
      gap: 20px;
      min-width: 0;
    }

    .dashboard-header {
      background: @colorCreatorHeaderBg;
      border: 1px solid @colorCreatorHeaderBorder;
      border-radius: @creatorRadius;
      overflow: hidden;
      box-shadow: @creatorShadow;
      transition: box-shadow 0.25s ease;

      &:hover {
        box-shadow: @creatorShadowHover;
      }
    }

    .dashboard-card {
      background: #fff;
      border-radius: @creatorRadius;
      border: 1px solid @borderLight;
      box-shadow: @creatorShadow;
      overflow: hidden;
      transition: box-shadow 0.25s ease, transform 0.25s ease;

      &:hover {
        box-shadow: @creatorShadowHover;
        transform: translateY(-2px);
      }
    }

    .dashboard-article {
      flex: none;
    }

    .dashboard-bottom {
      display: grid;
      grid-template-columns: 1fr 1fr;
      gap: 20px;
      min-height: 280px;
    }

    .dashboard-growth {
      min-height: 280px;
    }

    .dashboard-hot {
      min-height: 280px;
    }

    .dashboard-aside {
      min-width: 0;
    }
  }

  @media screen and (max-width: 768px) {
    .creator-dashboard {
      grid-template-columns: 1fr;
      padding: 12px;

      .dashboard-main {
        gap: 12px;
      }

      .dashboard-bottom {
        grid-template-columns: 1fr;
        gap: 12px;
      }

      .dashboard-aside {
        display: none;
      }

      .dashboard-card:hover {
        transform: none;
      }
    }
  }
</style>
