<template>
  <header class="creator-header">
    <div class="user-main">
      <img class="user-avatar" :src="headImg" alt="">
      <div class="user-meta">
        <div class="greeting">{{ greeting }}，{{ nickname }}</div>
        <div class="sub-text">欢迎回到逐日Coding创作者中心，今天也要努力创作哦～</div>
        <div class="stats-row">
          <span class="stat-item"><span class="stat-value">{{ fans }}</span> <span class="stat-label">粉丝</span></span>
          <span class="divider"></span>
          <span class="stat-item"><span class="stat-value">{{ follow }}</span> <span class="stat-label">关注</span></span>
          <span class="divider"></span>
          <span class="stat-item power-link" @click="goToGrade"><span class="stat-value">{{ power }}</span> <span class="stat-label">逐力值</span></span>
          <span class="divider"></span>
          <span class="stat-item days-item">在创作的第 {{ days }} 天</span>
        </div>
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
      // 用户统计数据（粉丝、关注、逐力值、创作天数）
      stats: null
    }
  },
computed: {
    ...mapGetters(['userInfo']),
    // 根据当前时间返回问候语
    greeting() {
      const h = new Date().getHours()
      if (h < 6) return '夜深了'
      if (h < 12) return '早上好'
      if (h < 14) return '中午好'
      if (h < 18) return '下午好'
      return '晚上好'
    },
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
    position: relative;
    display: flex;
    align-items: center;
    justify-content: flex-start;
    flex-shrink: 0;
    padding: 18px 36px;
    background:
      radial-gradient(600px 140px at 85% -20%, rgba(30, 128, 255, 0.10), transparent 60%),
      linear-gradient(135deg, #F8FBFF 0%, @colorCreatorHeaderBg 100%);
    border-bottom: 1px solid @colorCreatorHeaderBorder;
    overflow: hidden;

    .user-main {
      display: flex;
      align-items: center;
    }

    .user-avatar {
      width: 60px;
      height: 60px;
      border-radius: 50%;
      object-fit: cover;
      margin-right: 18px;
      border: 3px solid #fff;
      box-shadow: 0 4px 12px rgba(0, 0, 0, 0.12);
    }

    .greeting {
      margin: 0 0 4px;
      font-size: 20px;
      font-weight: 700;
      color: @textPrimary;
      letter-spacing: 0.5px;
    }

    .sub-text {
      font-size: 13px;
      color: @colorStatLabel;
      margin-bottom: 10px;
    }

    .stats-row {
      display: flex;
      align-items: center;
      font-size: 14px;
      color: @textMuted;

      .stat-item {
        display: inline-flex;
        align-items: baseline;
        .stat-value {
          color: @colorStatValue;
          font-weight: 700;
          font-size: 18px;
        }
        .stat-label {
          color: @textMuted;
          font-weight: 400;
          margin-left: 5px;
        }
        &.power-link {
          cursor: pointer;
          transition: color 0.2s;
          &:hover {
            .stat-value {
              color: @brandBlue2;
            }
          }
        }
        &.days-item {
          color: @textSecondary;
          font-weight: 500;
        }
      }

      .divider {
        margin: 0 14px;
        width: 1px;
        height: 18px;
        background: @colorCreatorHeaderBorder;
      }
    }
}
</style>