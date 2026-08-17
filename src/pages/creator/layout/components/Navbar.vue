<template>
  <div class="navbar">
    <div class="left-menu">
      <hamburger :toggle-click="toggleSideBar" class="hamburger-container"/>
      <router-link to="/home" class="brand-container">
        <img src="/static/images/logo-icon.svg" width="24" height="24" class="brand-icon" alt="逐日Coding">
        <span class="brand-name">逐日Coding</span>
        <span class="brand-divider">·</span>
        <span class="brand-subtitle">创作者中心</span>
      </router-link>
    </div>
    <div class="right-menu">
      <NotificationBell class="right-menu-item" v-if="isLoggedIn" :unreadCount="unreadCount" @go-to-notification="goToNotification" />
      <div class="avatar-container right-menu-item" v-if="isLoggedIn" @click="toggleUserDropdown">
        <div class="avatar-wrapper">
          <img class="user-avatar" :src="avatarUrl" alt="头像">
          <span class="user-name">{{ nickName }}</span>
          <i class="el-icon-caret-bottom"/>
        </div>
        <UserDropdown
          v-if="showUserDropdown"
          :userAvatar="avatarUrl"
          :userName="nickName"
          :levelBadge="levelBadge"
          :formattedDiamond="formattedDiamond"
          :levelPercent="levelPercent"
          :formattedLevelText="formattedLevelText"
          :stats="stats"
          @go-profile="goToProfile"
          @go-growth="goToGrowth"
          @go-follow="goToFollow"
          @go-likes="goToLikes"
          @go-collects="goToCollects"
          @go-checkin="goToCheckin"
          @go-courses="goToCourses"
          @go-history="goToHistory"
          @my-discount="handleMyDiscount"
          @go-settings="goToSettings"
          @logout="handleLogout"
        />
      </div>
    </div>
  </div>
</template>

<script>
import { mapGetters } from 'vuex'
import Hamburger from '../../components/Hamburger/index.vue'
import { clearUser } from '../../utils/store'
import emitter from '../../utils/event'
import request from '@/common/request'
import conf from '@/common/conf'
import defaultAvatar from '@/static/images/avatar_head_1.png'
import UserDropdown from '@/components/bars/UserDropdown.vue'
import NotificationBell from '@/components/bars/NotificationBell.vue'
import { getUserStatistics } from '@/apis/user'
import { toast } from '@/utils/toast'

export default {
  components: {
    Hamburger,
    UserDropdown,
    NotificationBell
  },
  data() {
    return {
      unreadCount: 0,
      unreadTimer: null,
      showUserDropdown: false,
      stats: {
        followCount: 0,
        likeCount: 0,
        collectCount: 0
      },
      diamondCount: '0',
      levelBadge: 'ZR.1',
      levelScore: 0,
      levelMax: 150,
      levelPercent: 0
    }
  },
  computed: {
    ...mapGetters(['userInfo']),
    isLoggedIn() {
      return this.$store.getters.isLoggedIn
    },
    avatarUrl () {
      if (this.userInfo && this.userInfo.avatar) {
        return this.userInfo.avatar
      }
      return defaultAvatar
    },
    nickName () {
      return this.userInfo ? (this.userInfo.nickName || '用户') : '用户'
    },
    formattedDiamond() {
      const count = parseFloat(this.diamondCount)
      if (isNaN(count)) return '0'
      if (count >= 1000) {
        return (count / 1000).toFixed(1) + 'k'
      }
      return String(Math.floor(count))
    },
    formattedLevelText() {
      return this.levelScore + ' / ' + this.levelMax
    }
  },
  methods: {
    toggleSideBar() {
      emitter.$emit('changeCollapse')
    },
    logout() {
      clearUser()
      this.$store.dispatch('logout')
      this.$router.replace({ path: '/login' })
    },
    fetchUnreadCount() {
      if (!this.isLoggedIn) return
      request.get(conf.urls.get('notifications_unread'), {}).then(d => {
        if (d && d.code === 200 && d.data) {
          this.unreadCount = d.data.total || 0
        }
      }).catch(() => {})
    },
    goToNotification(type) {
      this.$router.push('/notification?tab=' + type)
    },
    toggleUserDropdown(e) {
      if (this.isLoggedIn) {
        e.stopPropagation()
        this.showUserDropdown = !this.showUserDropdown
        if (this.showUserDropdown) {
          this.loadUserStats()
        }
      }
    },
    async loadUserStats() {
      try {
        const res = await getUserStatistics()
        if (res && res.code === 200 && res.data) {
          const data = res.data
          this.stats.followCount = data.followCount || 0
          this.stats.likeCount = data.likeCount || 0
          this.stats.collectCount = data.collectCount || 0
          this.diamondCount = data.diamondCount || '0'
          if (data.levelInfo) {
            const li = data.levelInfo
            this.levelBadge = 'ZR.' + (li.dailyLevel || 1)
            this.levelScore = li.dailyScore || 0
            const levelMaxMap = { 1: 150, 2: 300, 3: 500, 4: 800, 5: 1200 }
            this.levelMax = levelMaxMap[li.dailyLevel] || 150
            const levelBaseMap = { 1: 0, 2: 150, 3: 300, 4: 500, 5: 800 }
            const base = levelBaseMap[li.dailyLevel] || 0
            const currentInLevel = this.levelScore - base
            this.levelPercent = Math.min(Math.round(currentInLevel / this.levelMax * 100), 100)
          }
        }
      } catch (e) {
        // Silently fail, use defaults
      }
    },
    goToProfile() {
      this.showUserDropdown = false
      const userId = this.userInfo && this.userInfo.userId ? this.userInfo.userId : 1
      this.$router.push('/user/' + userId)
    },
    goToSettings() {
      this.showUserDropdown = false
      this.$router.push('/user/settings')
    },
    goToGrowth() {
      this.showUserDropdown = false
      this.$router.push('/user/center/growth')
    },
    goToCheckin() {
      this.showUserDropdown = false
      this.$router.push('/user/center/checkin')
    },
    goToCourses() {
      this.showUserDropdown = false
      this.$router.push('/user/courses')
    },
    goToHistory() {
      this.showUserDropdown = false
      this.$router.push('/user/history')
    },
    goToFollow() {
      this.showUserDropdown = false
      const userId = this.userInfo && this.userInfo.userId ? this.userInfo.userId : 1
      this.$router.push('/user/' + userId + '?tab=follow&subTab=following')
    },
    goToLikes() {
      this.showUserDropdown = false
      const userId = this.userInfo && this.userInfo.userId ? this.userInfo.userId : 1
      this.$router.push('/user/' + userId + '?tab=likes&subTab=article')
    },
    goToCollects() {
      this.showUserDropdown = false
      const userId = this.userInfo && this.userInfo.userId ? this.userInfo.userId : 1
      this.$router.push('/user/' + userId + '?tab=collection')
    },
    handleMyDiscount() {
      this.showUserDropdown = false
      toast('我的优惠功能开发中', 2)
    },
    handleLogout() {
      this.showUserDropdown = false
      clearUser()
      this.$store.dispatch('logout')
      this.$router.replace({ path: '/login' })
    },
    closeDropdown(e) {
      if (this.showUserDropdown) {
        var userEl = this.$el.querySelector('.avatar-container')
        if (userEl && !userEl.contains(e.target)) {
          this.showUserDropdown = false
        }
      }
    }
  },
  mounted() {
    this.fetchUnreadCount()
    this.unreadTimer = setInterval(() => this.fetchUnreadCount(), 30000)
    document.addEventListener('click', this.closeDropdown)
  },
  beforeDestroy() {
    if (this.unreadTimer) {
      clearInterval(this.unreadTimer)
      this.unreadTimer = null
    }
    document.removeEventListener('click', this.closeDropdown)
  }
}
</script>

<style lang="less" scoped>
@import '../styles/variables.less';

.navbar {
  height: 60px;
  line-height: 60px;
  background-color: #ffffff;
  border-bottom: 1px solid #e4e6eb;
  box-shadow: @cardShadow;
  position: sticky;
  top: 0;
  z-index: 10;
  width: 100%;
  flex-shrink: 0;

  .left-menu {
    float: left;
    height: 100%;

    .hamburger-container {
      float: left;
      padding: 0 10px;
      line-height: 56px;
      cursor: pointer;
      transition: color 0.2s;

      &:hover {
        i { color: @brandBlue; }
      }
    }

    .brand-container {
      display: inline-flex;
      align-items: center;
      height: 60px;
      padding: 0 10px;
      font-size: 16px;
      color: @textPrimary;
      text-decoration: none;

      .brand-icon {
        vertical-align: middle;
        margin-right: 6px;
        flex-shrink: 0;
      }

      .brand-name {
        font-weight: 600;
      }

      .brand-divider {
        margin: 0 6px;
        color: @textMuted;
      }

      .brand-subtitle {
        color: @textSecondary;
      }
    }
  }

  .right-menu {
    float: right;
    height: 100%;
    padding-right: 20px;
    display: flex;
    align-items: center;

    &:focus {
      outline: none;
    }

    .right-menu-item {
      display: inline-block;
      margin: 0 8px;
    }

    .avatar-container {
      position: relative;
      height: 60px;
      margin-right: 10px;
      cursor: pointer;

      .avatar-wrapper {
        cursor: pointer;
        position: relative;
        line-height: 60px;

        .user-avatar {
          width: 36px;
          height: 36px;
          border-radius: 50%;
          object-fit: cover;
          vertical-align: middle;
          margin-right: 8px;
        }

        .user-name {
          font-size: 15px;
          color: @textPrimary;
          vertical-align: middle;
        }

        .el-icon-caret-bottom {
          cursor: pointer;
          margin-left: 6px;
          font-size: 12px;
          color: @textMuted;
          vertical-align: middle;
        }
      }
    }
  }
}
</style>