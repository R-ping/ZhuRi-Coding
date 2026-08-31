<template>
  <div class="search-result-user">
    <!-- 左：圆形头像 -->
    <div class="user-avatar" @click="onOpen">
      <img v-if="data.authorAvatar" :src="data.authorAvatar" alt="avatar" />
      <span v-else class="avatar-placeholder">{{ avatarFallback }}</span>
    </div>

    <!-- 中：昵称 + 元信息 -->
    <div class="user-info" @click="onOpen">
      <span class="user-name" v-html="displayName"></span>
      <span v-if="hasMeta" class="user-meta">
        <template v-if="data.followCount != null">关注 {{ data.followCount }}</template>
        <template v-if="data.followerCount != null">
          <template v-if="data.followCount != null"> · </template>粉丝 {{ data.followerCount }}
        </template>
      </span>
    </div>

    <!-- 右：关注按钮 -->
    <button
      class="btn-follow"
      :class="{ 'is-followed': data.isFollowed }"
      @click="onFollow"
    >{{ data.isFollowed ? '已关注' : '+ 关注' }}</button>
  </div>
</template>

<script>
import { highlight } from '../../utils/sanitize.js'

export default {
  name: 'SearchResultUser',
  props: {
    data: {
      type: Object,
      required: true
    },
    keyword: {
      type: String,
      default: ''
    }
  },
  computed: {
    displayName() {
      return highlight(this.data.authorName || this.data.name || this.data.title, this.keyword)
    },
    avatarFallback() {
      var name = this.data.authorName || this.data.name || this.data.title || '用户'
      return name.charAt(0)
    },
    hasMeta() {
      return this.data.followCount != null || this.data.followerCount != null
    }
  },
  methods: {
    onOpen() {
      this.$emit('open', this.data.authorId || this.data.id)
    },
    onFollow() {
      this.$emit('follow', this.data.authorId || this.data.id)
    }
  }
}
</script>

<style lang="less" scoped>
.search-result-user {
  display: flex;
  align-items: center;
  gap: 14px;
  background-color: #fff;
  border: 1px solid #f0f1f5;
  border-radius: 8px;
  padding: 14px 16px;
  margin-bottom: 12px;
  transition: box-shadow 0.25s ease;
}

.search-result-user:hover {
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.06);
}

.user-avatar {
  width: 56px;
  height: 56px;
  flex-shrink: 0;
  border-radius: 50%;
  overflow: hidden;
  background-color: #f2f3f5;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
}

.user-avatar img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}

.avatar-placeholder {
  font-size: 22px;
  color: #c2c8d1;
  font-weight: 600;
}

.user-info {
  flex: 1;
  min-width: 0;
  cursor: pointer;
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.user-name {
  font-size: 16px;
  font-weight: 600;
  color: #1e80ff;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.user-name /deep/ em {
  color: #f53f3f;
  font-style: normal;
  background-color: #fff1f0;
  padding: 0 2px;
  border-radius: 2px;
}

.user-meta {
  font-size: 12px;
  color: #8a93a6;
}

.btn-follow {
  flex-shrink: 0;
  font-size: 13px;
  color: #1e80ff;
  background-color: #e8f3ff;
  border: none;
  border-radius: 4px;
  padding: 6px 14px;
  cursor: pointer;
  transition: all 0.2s;
  white-space: nowrap;
}

.btn-follow:hover {
  background-color: #1e80ff;
  color: #fff;
}

.btn-follow.is-followed {
  color: #515767;
  background-color: #f2f3f5;
  cursor: default;
}

@media screen and (max-width: 767px) {
  .search-result-user {
    padding: 12px;
    border-radius: 0;
    margin-bottom: 0;
    border: none;
    border-bottom: 1px solid #f0f1f5;
  }

  .user-avatar {
    width: 48px;
    height: 48px;
  }
}
</style>