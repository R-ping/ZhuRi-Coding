<template>
  <div class="search-result-article" @click="onCardClick">
    <div class="article-header">
      <div class="author-info" @mouseenter="onAuthorEnter" @mouseleave="onAuthorLeave">
        <img
          class="author-avatar"
          :src="data.authorAvatar || defaultAvatar"
          alt="author avatar"
          @click.stop="onAuthorClick"
        />
        <div class="author-meta">
          <span class="author-name" @click.stop="onAuthorClick">{{ data.authorName || '匿名用户' }}</span>
          <span v-if="data.authorLevel" class="author-level">{{ data.authorLevel }}</span>
        </div>
        <span class="publish-time">{{ displayTime }}</span>
      </div>
    </div>

    <div class="article-tags" v-if="displayTags.length > 0">
      <template v-for="(tag, index) in displayTags">
        <span
          :key="tag.id"
          class="tag-item"
          @click.stop="onTagClick(tag)"
        >{{ tag.name }}</span>
        <span
          v-if="index < displayTags.length - 1"
          class="tag-separator"
        >/</span>
      </template>
    </div>

    <h3 class="article-title" v-html="displayTitle" @click.stop="onTitleClick"></h3>

    <div class="article-summary" v-if="displaySummary">
      {{ displaySummary }}
    </div>

    <div class="article-body" :class="{ 'has-cover': hasCover }">
      <div class="article-text">
        <slot name="extra"></slot>
      </div>
      <div class="article-cover" v-if="hasCover">
        <img
          :src="data.coverImage"
          alt="cover"
          class="cover-image"
          @click.stop="onTitleClick"
        />
      </div>
    </div>

    <div class="article-actions">
      <div class="action-item" @click.stop="onLike">
        <i
          class="action-icon"
          :class="liked ? 'icon-like-active' : 'icon-like'"
        ></i>
        <span class="action-count">{{ displayLikeCount }}</span>
      </div>
      <div class="action-item" @click.stop="onComment">
        <i class="action-icon icon-comment"></i>
        <span class="action-count">{{ displayCommentCount }}</span>
      </div>
    </div>
  </div>
</template>

<script>
import { sanitizeHighlight } from '../../utils/sanitize.js'

export default {
  name: 'SearchResultArticle',
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
  data() {
    return {
      liked: false,
      defaultAvatar: 'data:image/svg+xml;base64,PHN2ZyB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciIHZpZXdCb3g9IjAgMCAzMiAzMiI+PHJlY3Qgd2lkdGg9IjMyIiBoZWlnaHQ9IjMyIiBmaWxsPSIjZTBlMGUwIiByeD0iMTYiL348dGV4dCB4PSIxNiIgeT0iMjEiIGZvbnQtc2l6ZT0iMTYiIHRleHQtYW5jaG9yPSJtaWRkbGUiIGZpbGw9IiM5OTkiPuWvueaPjC90ZXh0Pjwvc3ZnPg=='
    }
  },
  computed: {
    displayTitle() {
      if (!this.data.title) return ''
      return sanitizeHighlight(this.data.title)
    },
    displayTime() {
      return this.formatRelativeTime(this.data.publishTime)
    },
    displayTags() {
      return this.data.tags || []
    },
    hasCover() {
      return !!(this.data.coverImage && this.data.coverImage.trim())
    },
    displaySummary() {
      if (!this.data.summary) return ''
      return this.data.summary
    },
    displayLikeCount() {
      var count = this.data.likeCount || 0
      if (this.liked && this.data.liked !== true) {
        count = count + 1
      } else if (!this.liked && this.data.liked === true && count > 0) {
        count = count - 1
      }
      return count > 9999 ? (count / 10000).toFixed(1) + 'w' : count
    },
    displayCommentCount() {
      var count = this.data.commentCount || 0
      return count > 9999 ? (count / 10000).toFixed(1) + 'w' : count
    }
  },
  watch: {
    'data.liked': {
      immediate: true,
      handler(val) {
        this.liked = !!val
      }
    }
  },
  methods: {
    formatRelativeTime(timestamp) {
      if (!timestamp) return ''
      var time = new Date(timestamp).getTime()
      if (isNaN(time)) return ''
      var now = Date.now()
      var diff = now - time
      if (diff < 0) diff = 0

      var seconds = Math.floor(diff / 1000)
      var minutes = Math.floor(seconds / 60)
      var hours = Math.floor(minutes / 60)
      var days = Math.floor(hours / 24)
      var months = Math.floor(days / 30)
      var years = Math.floor(months / 12)

      if (seconds < 60) return '刚刚'
      if (minutes < 60) return minutes + '分钟前'
      if (hours < 24) return hours + '小时前'
      if (days < 30) return days + '天前'
      if (months < 12) return months + '个月前'
      return years + '年前'
    },
    onLike() {
      this.liked = !this.liked
      this.$emit('like', this.data.id, this.liked)
    },
    onComment() {
      this.$emit('comment', this.data.id)
    },
    onAuthorEnter(e) {
      this.$emit('author-hover', this.data, e)
    },
    onAuthorLeave() {
      this.$emit('author-leave')
    },
    onAuthorClick() {
      this.$emit('author-click', this.data.authorId)
    },
    onTagClick(tag) {
      this.$emit('tag-click', tag.id, tag.name)
    },
    onTitleClick() {
      this.$emit('title-click', this.data.id)
    },
    onCardClick() {
      this.$emit('title-click', this.data.id)
    }
  }
}
</script>

<style lang="less" scoped>
@import '../../styles/article';

.search-result-article {
  width: 100%;
  background-color: #ffffff;
  border-radius: 8px;
  padding: 16px;
  box-sizing: border-box;
  margin-bottom: 12px;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.06);
  transition: box-shadow 0.25s ease, transform 0.25s ease;
  cursor: pointer;
}

.article-header {
  margin-bottom: 10px;
}

.author-info {
  display: flex;
  align-items: center;
  gap: 8px;
}

.author-avatar {
  width: 32px;
  height: 32px;
  border-radius: 50%;
  object-fit: cover;
  background-color: #f0f0f0;
  flex-shrink: 0;
  cursor: pointer;
}

.author-meta {
  display: flex;
  align-items: center;
  gap: 6px;
  flex: 1;
  min-width: 0;
}

.author-name {
  font-size: 14px;
  color: #515767;
  font-weight: 400;
  cursor: pointer;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.author-level {
  font-size: 11px;
  color: #1E80FF;
  background-color: #E8F3FF;
  padding: 1px 6px;
  border-radius: 3px;
  line-height: 1.4;
}

.publish-time {
  font-size: 12px;
  color: #8A93A6;
  margin-left: auto;
  flex-shrink: 0;
}

.article-tags {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 2px;
  margin-bottom: 8px;
}

.tag-item {
  font-size: 12px;
  color: #8A93A6;
  cursor: pointer;
  transition: color 0.15s;
  padding: 2px 4px;
  border-radius: 3px;
}

.tag-item:hover {
  color: #1E80FF;
  background-color: #F2F3F5;
}

.tag-separator {
  font-size: 12px;
  color: #C2C8D1;
  user-select: none;
}

.article-title {
  font-size: 17px;
  font-weight: 600;
  color: #252933;
  line-height: 1.5;
  margin: 0 0 8px;
  padding: 0;
  cursor: pointer;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.article-title /deep/ em {
  color: #F53F3F;
  font-style: normal;
  background-color: #FFF1F0;
  padding: 0 2px;
  border-radius: 2px;
}

.article-title:hover {
  color: #1E80FF;
}

.article-summary {
  font-size: 14px;
  color: #515767;
  line-height: 1.6;
  margin-bottom: 12px;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.article-body {
  display: flex;
  flex-direction: column;
  margin-bottom: 12px;
}

.article-body.has-cover {
  flex-direction: row;
  justify-content: space-between;
  gap: 12px;
  align-items: flex-start;
}

.article-text {
  flex: 1;
  min-width: 0;
}

.article-cover {
  width: 120px;
  height: 80px;
  flex-shrink: 0;
  border-radius: 4px;
  overflow: hidden;
  background-color: #F2F3F5;
}

.cover-image {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}

.article-actions {
  display: flex;
  align-items: center;
  gap: 24px;
  padding-top: 10px;
  border-top: 1px solid #F2F3F5;
}

.action-item {
  display: flex;
  align-items: center;
  gap: 4px;
  cursor: pointer;
  transition: color 0.15s;
  user-select: none;
}

.action-icon {
  font-size: 16px;
  font-family: fontawesome;
  font-style: normal;
  display: inline-block;
  width: 16px;
  height: 16px;
  line-height: 16px;
  text-align: center;
  transition: color 0.15s;
}

.icon-like::before {
  content: '\uf164';
}

.icon-like-active {
  color: #F53F3F;
}

.icon-like-active::before {
  content: '\uf165';
}

.icon-comment::before {
  content: '\uf086';
}

.action-count {
  font-size: 13px;
  color: #8A93A6;
  line-height: 1;
}

.action-item:hover .action-icon {
  color: #1E80FF;
}

.action-item:hover .action-count {
  color: #1E80FF;
}

.action-item:hover .icon-like-active {
  color: #F53F3F;
}

@media screen and (max-width: 767px) {
  .search-result-article {
    padding: 12px;
    border-radius: 0;
    margin-bottom: 0;
    box-shadow: none;
    border-bottom: 1px solid #F0F1F5;
  }

  .author-avatar {
    width: 28px;
    height: 28px;
  }

  .author-name {
    font-size: 13px;
  }

  .article-title {
    font-size: 16px;
  }

  .article-summary {
    font-size: 13px;
  }

  .article-cover {
    width: 60px;
    height: 60px;
  }

  .article-actions {
    gap: 20px;
  }

  .action-icon {
    font-size: 14px;
    width: 14px;
    height: 14px;
    line-height: 14px;
  }

  .action-count {
    font-size: 12px;
  }
}
</style>