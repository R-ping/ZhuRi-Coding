<template>
  <transition name="hover-card-fade">
    <div
      v-if="visible"
      class="author-hover-card"
      :style="cardStyle"
      @click.stop="onCardClick"
      @mouseenter="onCardEnter"
      @mouseleave="onCardLeave"
    >
      <div class="card-arrow" :class="arrowDirection"></div>

      <div class="card-body" @click.stop>
        <div class="author-section">
          <img
            class="author-avatar"
            :src="displayAvatar"
            alt="author avatar"
            title="查看个人主页"
            @click.stop="onGoProfile"
          />
          <div class="author-info">
            <div class="author-name-row">
              <span class="author-name" title="查看个人主页" @click.stop="onGoProfile">{{ displayName }}</span>
            </div>
            <div class="author-level-row">
              <span class="level-badge daily" title="逐日等级">
                <span class="level-badge-label">逐日</span>
                <span class="level-badge-value">Lv.{{ displayDailyLevel }}</span>
              </span>
              <span class="level-badge power" title="逐力值等级">
                <span class="level-badge-label">逐力</span>
                <span class="level-badge-value">Lv.{{ displayPowerLevel }}</span>
              </span>
            </div>
            <div class="author-bio">{{ displayPosition }}</div>
          </div>
        </div>

        <div class="author-stats">
          <div class="stat-item">
            <span class="stat-value">{{ formatCount(displayFollowCount) }}</span>
            <span class="stat-label">关注</span>
          </div>
          <div class="stat-divider"></div>
          <div class="stat-item">
            <span class="stat-value">{{ formatCount(displayFollowerCount) }}</span>
            <span class="stat-label">粉丝</span>
          </div>
        </div>

        <div class="author-actions">
          <button
            class="btn-follow"
            :class="{ 'is-followed': displayFollowed }"
            @click="onFollow"
          >
            {{ displayFollowed ? '已关注' : '+ 关注' }}
          </button>
          <button
            class="btn-message"
            @click="onMessage"
          >
            私信
          </button>
        </div>
      </div>
    </div>
  </transition>
</template>

<script>
import { getAuthorInfo } from '@/apis/author'

export default {
  name: 'AuthorHoverCard',
  props: {
    visible: {
      type: Boolean,
      default: false
    },
    // 作者用户ID：传入时组件自动拉取聚合接口数据
    userId: {
      type: [Number, String],
      default: null
    },
    // 兼容旧用法：未传 userId 时使用静态 author 数据
    author: {
      type: Object,
      default: function () { return {} }
    },
    position: {
      type: Object,
      default: function () { return { top: 0, left: 0 } }
    }
  },
  data() {
    return {
      defaultAvatar: 'data:image/svg+xml;base64,PHN2ZyB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciIHZpZXdCb3g9IjAgMCA2NCA2NCI+PHJlY3Qgd2lkdGg9IjY0IiBoZWlnaHQ9IjY0IiBmaWxsPSIjZTBlMGUwIiByeD0iMzIiLz48dGV4dCB4PSIzMiIgeT0iNDIiIGZvbnQtc2l6ZT0iMzIiIHRleHQtYW5jaG9yPSJtaWRkbGUiIGZpbGw9IiM5OTkiPuWvueaPjC90ZXh0Pjwvc3ZnPg==',
      info: {},
      loading: false
    }
  },
  computed: {
    cardStyle() {
      return {
        top: this.position.top + 'px',
        left: this.position.left + 'px'
      }
    },
    arrowDirection() {
      return this.position.arrow || 'top'
    },
    displayName() {
      return this.info.nickname || this.author.name || '匿名用户'
    },
    displayAvatar() {
      return this.info.avatar || this.author.avatar || this.defaultAvatar
    },
    displayPosition() {
      return this.info.position || this.author.position || this.author.bio || '暂无简介'
    },
    displayDailyLevel() {
      return this.info.dailyLevel || this.author.dailyLevel || 1
    },
    displayPowerLevel() {
      return this.info.powerLevel || this.author.powerLevel || 1
    },
    displayFollowCount() {
      return this.info.followCount != null ? this.info.followCount : (this.author.followCount || 0)
    },
    displayFollowerCount() {
      return this.info.followerCount != null ? this.info.followerCount : (this.author.followerCount || 0)
    },
    displayFollowed() {
      return this.info.isFollowed != null ? this.info.isFollowed : (this.author.isFollowed || false)
    },
    currentUserId() {
      return this.userId || this.author.id || null
    }
  },
  watch: {
    visible(val) {
      if (val) {
        document.addEventListener('click', this.handleOutsideClick)
        this.fetchInfo()
      } else {
        document.removeEventListener('click', this.handleOutsideClick)
      }
    }
  },
  beforeDestroy() {
    document.removeEventListener('click', this.handleOutsideClick)
  },
  methods: {
    formatCount(count) {
      if (count == null) return '0'
      count = Number(count)
      if (count > 9999) {
        return (count / 10000).toFixed(1) + 'w'
      }
      if (count > 999) {
        return (count / 1000).toFixed(1) + 'k'
      }
      return String(count)
    },
    // 拉取作者聚合信息；未传 userId 时保留静态数据
    async fetchInfo() {
      if (!this.currentUserId || this.loading) return
      this.loading = true
      try {
        const res = await getAuthorInfo(this.currentUserId)
        if (res && res.code === 200 && res.data) {
          this.info = res.data
        }
      } catch (e) {
        // 拉取失败时保留静态数据兜底
      } finally {
        this.loading = false
      }
    },
    onFollow() {
      const followed = this.displayFollowed
      this.$emit('follow', this.currentUserId, !followed)
    },
    onMessage() {
      // 携带昵称/头像，供跳转站内信私信分栏后直接选中该用户打开聊天区
      this.$emit('message', {
        userId: this.currentUserId,
        name: this.displayName,
        avatar: this.displayAvatar
      })
    },
    onCardClick() {},
    // 点击卡片内头像/昵称 -> 派发跳转事件，由父级跳转目标用户个人主页
    onGoProfile() {
      if (!this.currentUserId) return
      this.$emit('go-profile', this.currentUserId)
    },
    // 鼠标进入卡片：通知父级取消隐藏定时器，保证可点击卡片内的关注/私信按钮
    onCardEnter() {
      this.$emit('card-enter')
    },
    // 鼠标离开卡片：通知父级延迟隐藏
    onCardLeave() {
      this.$emit('card-leave')
    },
    handleOutsideClick(e) {
      if (this.$el && !this.$el.contains(e.target)) {
        this.$emit('close')
      }
    }
  }
}
</script>

<style lang="less" scoped>
.author-hover-card {
  position: fixed;
  z-index: 9999;
  width: 232px;
  background-color: #ffffff;
  border-radius: 10px;
  box-shadow: 0 6px 20px rgba(0, 0, 0, 0.14), 0 0 0 1px rgba(0, 0, 0, 0.04);
  overflow: visible;
  transform-origin: top center;
}

.card-arrow {
  position: absolute;
  width: 12px;
  height: 12px;
  background-color: #ffffff;
  transform: rotate(45deg);
  z-index: -1;
}

.card-arrow.top {
  top: -6px;
  left: 50%;
  margin-left: -6px;
  box-shadow: -2px -2px 4px rgba(0, 0, 0, 0.04);
}

.card-arrow.bottom {
  bottom: -6px;
  left: 50%;
  margin-left: -6px;
  box-shadow: 2px 2px 4px rgba(0, 0, 0, 0.04);
}

.card-arrow.left {
  left: -6px;
  top: 50%;
  margin-top: -6px;
  box-shadow: -2px 2px 4px rgba(0, 0, 0, 0.04);
}

.card-arrow.right {
  right: -6px;
  top: 50%;
  margin-top: -6px;
  box-shadow: 2px -2px 4px rgba(0, 0, 0, 0.04);
}

.card-body {
  padding: 16px 16px 14px;
  position: relative;
}

.author-section {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 14px;
}

.author-avatar {
  width: 56px;
  height: 56px;
  flex-shrink: 0;
  border-radius: 50%;
  object-fit: cover;
  background-color: #f0f0f0;
  border: 2px solid #ffffff;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.08);
  cursor: pointer;
  transition: transform 0.15s ease, box-shadow 0.15s ease;
}

.author-avatar:hover {
  transform: scale(1.05);
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.14);
}

.author-info {
  flex: 1;
  min-width: 0;
}

.author-name-row {
  display: flex;
  align-items: center;
  margin-bottom: 5px;
}

.author-name {
  font-size: 16px;
  font-weight: 600;
  color: #252933;
  line-height: 1.3;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 100%;
  cursor: pointer;
  transition: color 0.15s ease;
}

.author-name:hover {
  color: #1E80FF;
}

.author-level-row {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 5px;
}

.level-badge {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  padding: 1px 7px;
  border-radius: 4px;
  font-size: 11px;
  line-height: 1.4;
}

.level-badge.daily {
  color: #1E80FF;
  background-color: #E8F3FF;
}

.level-badge.power {
  color: #FA8C16;
  background-color: #FFF7E6;
}

.level-badge-label {
  opacity: 0.85;
}

.level-badge-value {
  font-weight: 600;
}

.author-bio {
  font-size: 13px;
  color: #8A93A6;
  line-height: 1.5;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
  margin-top: 2px;
  word-break: break-word;
}

.author-stats {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 10px 0;
  border-top: 1px solid #F2F3F5;
  border-bottom: 1px solid #F2F3F5;
  margin-bottom: 10px;
}

.stat-item {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 2px;
  flex: 1;
}

.stat-value {
  font-size: 16px;
  font-weight: 600;
  color: #252933;
  line-height: 1.2;
}

.stat-label {
  font-size: 12px;
  color: #8A93A6;
  line-height: 1;
}

.stat-divider {
  width: 1px;
  height: 24px;
  background-color: #E5E6EB;
}

.author-actions {
  display: flex;
  gap: 8px;
}

.btn-follow {
  flex: 1;
  height: 32px;
  border: none;
  border-radius: 4px;
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.15s ease;
  background-color: #1E80FF;
  color: #ffffff;
}

.btn-follow:hover {
  background-color: #1A6FD9;
}

.btn-follow.is-followed {
  background-color: #F2F3F5;
  color: #8A93A6;
}

.btn-follow.is-followed:hover {
  background-color: #E5E6EB;
  color: #515767;
}

.btn-message {
  flex: 1;
  height: 32px;
  border: 1px solid #E5E6EB;
  border-radius: 4px;
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.15s ease;
  background-color: #ffffff;
  color: #515767;
}

.btn-message:hover {
  border-color: #1E80FF;
  color: #1E80FF;
}

.hover-card-fade-enter-active,
.hover-card-fade-leave-active {
  transition: opacity 0.2s ease, transform 0.2s ease;
}

.hover-card-fade-enter,
.hover-card-fade-leave-to {
  opacity: 0;
  transform: translateY(-8px) scale(0.96);
}

@media screen and (max-width: 767px) {
  .author-hover-card {
    width: 200px;
  }

  .author-avatar {
    width: 48px;
    height: 48px;
  }

  .author-name {
    font-size: 14px;
  }

  .author-bio {
    font-size: 12px;
  }

  .stat-value {
    font-size: 14px;
  }

  .btn-follow,
  .btn-message {
    height: 28px;
    font-size: 12px;
  }
}
</style>