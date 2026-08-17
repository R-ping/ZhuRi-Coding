<template>
  <div class="comment-page">
    <div class="comment-card">
      <header class="card-header">
        <a class="back-link" @click="$router.back()">&lt; 返回评论管理</a>
      </header>
      <div class="card-body">
        <div class="detail-wrap">
          <h1>{{ articleTitle || '文章评论' }}</h1>
        </div>
        <div v-loading="loading" class="comments-list">
          <div v-if="!loading && commentList.length === 0" class="empty-tip">暂无评论</div>
          <div class="comment-item" v-for="item in commentList" :key="item.id">
            <img class="head-img" :src="item.userAvatar || defaultAvatar" alt="头像" />
            <div class="comment-info">
              <div class="row-top">
                <span class="nickname">{{ item.userName || '用户' }}</span>
                <span class="time">{{ formatTime(item.createdTime) }}</span>
              </div>
              <div class="content">{{ item.content }}</div>
              <div class="row-bottom">
                <span class="meta"><i class="el-icon-chat-dot-round"></i> {{ item.replyCount || 0 }}</span>
                <span class="meta"><i class="el-icon-thumb"></i> {{ item.likeCount || 0 }}</span>
              </div>
              <div class="reply-list" v-if="item.children && item.children.length">
                <div class="reply-item" v-for="reply in item.children" :key="reply.id">
                  <span class="reply-name">{{ reply.userName || '用户' }}</span>
                  ：{{ reply.content }}
                </div>
              </div>
            </div>
          </div>
          <div
            class="load-more"
            v-if="commentList.length > 0"
            :class="{ nomore: !hasMore }"
            @click="loadMore"
          >{{ hasMore ? '加载更多评论' : '评论加载完毕' }}</div>
        </div>
      </div>
    </div>
  </div>
</template>

<script>
import { getArticleComments } from '@/apis/creator/comment'

const defaultAvatar = 'data:image/svg+xml,%3Csvg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100"%3E%3Ccircle cx="50" cy="50" r="50" fill="%23ddd"/%3E%3C/svg%3E'

export default {
  name: 'CreatorCommentDetail',
  data() {
    return {
      articleId: null,
      articleTitle: '',
      commentList: [],
      cursor: 0,
      hasMore: false,
      loading: false,
      defaultAvatar
    }
  },
  created() {
    this.articleId = this.$route.query.articleId || ''
    this.articleTitle = this.$route.query.title || ''
    if (!this.articleId) {
      this.$replace && this.$replace('/creator/comment')
      this.$router.replace('/creator/comment')
      return
    }
    this.loadComments(false)
  },
  methods: {
    async loadComments(append) {
      if (this.loading) return
      this.loading = true
      try {
        const res = await getArticleComments(this.articleId, this.cursor || undefined, 10)
        if (res && res.code === 200 && res.data) {
          const data = res.data
          const list = data.list || []
          this.commentList = append ? this.commentList.concat(list) : list
          this.cursor = data.cursor || this.cursor
          this.hasMore = !!data.has_more
          if (!append && list.length === 0) {
            this.hasMore = false
          }
        } else {
          this.$message.error((res && res.message) || '获取评论失败')
        }
      } catch (e) {
        this.$message.error('获取评论失败，请稍后重试')
      } finally {
        this.loading = false
      }
    },
    loadMore() {
      if (this.hasMore) this.loadComments(true)
    },
    formatTime(val) {
      if (!val) return '--'
      const d = new Date(val)
      if (isNaN(d.getTime())) return val
      const pad = n => String(n).padStart(2, '0')
      return d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate()) + ' ' +
        pad(d.getHours()) + ':' + pad(d.getMinutes())
    }
  }
}
</script>

<style rel="stylesheet/less" lang="less" scoped>
@import '../layout/styles/variables.less';
.comment-page {
  min-height: calc(100vh - 70px);
  background-color: @bgGray;
  padding: 20px;
  .comment-card {
    background-color: #ffffff;
    border-radius: @cardRadius;
    box-shadow: @cardShadow;
    overflow: hidden;
    .card-header {
      padding: 0 24px;
      height: 56px;
      line-height: 56px;
      border-bottom: 1px solid #e8e8e8;
      .back-link {
        color: @brandBlue;
        cursor: pointer;
        font-size: 14px;
      }
    }
    .card-body {
      padding: 24px;
    }
  }
  .detail-wrap {
    padding-bottom: 24px;
    border-bottom: 1px solid #e8e8e8;
    h1 {
      font-weight: 500;
      margin: 0;
      font-size: 20px;
      color: @textPrimary;
    }
  }
  .comments-list {
    min-height: 200px;
    margin-top: 24px;
    .empty-tip {
      padding: 60px 0;
      text-align: center;
      color: @textMuted;
    }
    .comment-item {
      display: flex;
      padding: 20px 0;
      border-bottom: 1px solid #f2f3f5;
      .head-img {
        width: 44px;
        height: 44px;
        border-radius: 50%;
        flex-shrink: 0;
      }
      .comment-info {
        flex: 1;
        margin-left: 16px;
        min-width: 0;
        .row-top {
          display: flex;
          align-items: center;
          justify-content: space-between;
          .nickname {
            font-weight: 500;
            color: @textPrimary;
          }
          .time {
            font-size: 12px;
            color: @textMuted;
          }
        }
        .content {
          margin-top: 10px;
          font-size: 14px;
          line-height: 1.6;
          color: @textSecondary;
          word-break: break-word;
        }
        .row-bottom {
          margin-top: 10px;
          .meta {
            margin-right: 20px;
            font-size: 13px;
            color: @textMuted;
          }
        }
        .reply-list {
          margin-top: 12px;
          background: #f7f8fa;
          border-radius: 6px;
          padding: 10px 14px;
          .reply-item {
            padding: 4px 0;
            font-size: 13px;
            color: @textSecondary;
            line-height: 1.5;
            .reply-name {
              color: @brandBlue;
            }
          }
        }
      }
    }
    .load-more {
      height: 48px;
      line-height: 48px;
      text-align: center;
      cursor: pointer;
      color: @textSecondary;
      font-size: 14px;
      &.nomore {
        color: @textMuted;
        cursor: default;
      }
    }
  }
}
</style>