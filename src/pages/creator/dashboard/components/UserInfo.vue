<template>
  <header class="creator-header">
    <div class="user-main">
      <img class="user-avatar" :src="headImg" alt="">
      <div class="user-meta">
        <h3 class="nickname">{{ nickname }}</h3>
        <div class="stats-row">
          <span class="stat-item"><span class="stat-value">{{ fans }}</span> <span class="stat-label">粉丝</span></span>
          <span class="divider"></span>
          <span class="stat-item"><span class="stat-value">{{ follow }}</span> <span class="stat-label">关注</span></span>
          <span class="divider"></span>
          <span class="stat-item power-link" @click="goToGrade"><span class="stat-value">{{ power }}</span> <span class="stat-label">掘力值</span></span>
        </div>
        <div class="days">在逐日Coding创作的第 {{ days }} 天</div>
      </div>
    </div>
  </header>
</template>

<script>
import { mapGetters } from 'vuex'
import { getUserStatistics } from '@/apis/user.js'
import defaultAvatar from '@/static/images/avatar_head_1.png'

export default {
  data() {
    return {
      stats: null
    }
  },
  computed: {
    ...mapGetters(['userInfo']),
    nickname() {
      const u = this.userInfo || {}
      return u.nickName || '创作者'
    },
    headImg() {
      if (this.userInfo && this.userInfo.avatar) {
        return this.userInfo.avatar
      }
      return defaultAvatar
    },
    fans() {
      return this.stats ? this.stats.followerCount : 0
    },
    follow() {
      return this.stats ? this.stats.followCount : 0
    },
    power() {
      if (this.stats && this.stats.levelInfo) {
        return this.stats.levelInfo.powerValue || 0
      }
      return 0
    },
    days() {
      return this.stats ? this.stats.createDays : 1
    }
  },
  created() {
    this.fetchUserStatistics()
  },
  methods: {
    async fetchUserStatistics() {
      try {
        const res = await getUserStatistics()
        if (res && res.code === 200 && res.data) {
          this.stats = res.data
        }
      } catch (err) {
        console.error('获取用户统计数据失败', err)
      }
    },
    goToGrade() {
      this.$router.push('/creator/growth/grade')
    },
  }
}
</script>

<style lang="less" scoped>
  @import '../../layout/styles/variables.less';

  .creator-header {
    display: flex;
    align-items: center;
    justify-content: flex-start;
    padding: 32px 36px;
    background-color: @colorCreatorHeaderBg;
    border-bottom: 1px solid @colorCreatorHeaderBorder;

    .user-main {
      display: flex;
      align-items: center;
    }

    .user-avatar {
      width: 64px;
      height: 64px;
      border-radius: 50%;
      object-fit: cover;
      margin-right: 20px;
    }

    .nickname {
      margin: 0 0 12px;
      font-size: 18px;
      font-weight: 600;
      color: @textPrimary;
    }

    .stats-row {
      display: flex;
      align-items: center;
      font-size: 14px;
      color: @textMuted;
      margin-bottom: 8px;

      .stat-item {
        .stat-value {
          color: @colorStatValue;
          font-weight: 600;
        }
        .stat-label {
          color: @textMuted;
          font-weight: 400;
          margin-left: 4px;
        }
        &.power-link {
          cursor: pointer;
          transition: color 0.2s;
          &:hover {
            .stat-value {
              color: @brandBlue;
            }
          }
        }
      }

      .divider {
        margin: 0 12px;
        color: @textMuted;
      }
    }

    .days {
      margin-top: 6px;
      font-size: 14px;
      color: @colorStatLabel;
      font-weight: 400;
    }

  }
</style>