<template>
    <div class="pin-detail-page">
        <div class="pin-detail-content">
            <!-- 左侧主内容区 -->
            <div class="pin-main">
                <!-- 加载中 -->
                <div class="main-loading" v-if="loading">
                    <span>加载中...</span>
                </div>

                <!-- 沸点不存在 -->
                <div class="main-error" v-else-if="!pin">
                    <span>该沸点已被删除或不存在</span>
                </div>

                <template v-else>
                    <!-- 沸点原文卡片 -->
                    <div class="pin-card">
                        <div class="pin-card-header">
                            <img :src="pin.userAvatar || defaultAvatar" class="pin-avatar" alt="avatar">
                            <div class="pin-author-info">
                                <span class="pin-author-name">{{ escapeHtml(pin.userName) }}</span>
                                <span class="pin-publish-time">{{ formatTime(pin.createdTime) }}</span>
                            </div>
                        </div>
                        <div class="pin-content-text">{{ escapeHtml(pin.content) }}</div>

                        <!-- 图片 -->
                        <div class="pin-images" v-if="pin.imageUrls && pin.imageUrls.length > 0">
                            <img
                                :src="img"
                                class="pin-content-image"
                                :class="'img-count-' + Math.min(pin.imageUrls.length, 3)"
                                v-for="(img, idx) in pin.imageUrls"
                                :key="idx"
                                alt="image"
                            >
                        </div>

                        <!-- 链接卡片 -->
                        <div class="pin-link-card" v-if="pin.linkUrl" @click="openLink(pin.linkUrl)">
                            <span class="link-domain">{{ escapeHtml(pin.linkTitle || pin.linkUrl) }}</span>
                        </div>

                        <!-- 标签 -->
                        <div class="pin-tags" v-if="pin.topicTags && pin.topicTags.length > 0">
                            <span class="pin-topic" v-for="(tag, idx) in pin.topicTags" :key="idx">{{ escapeHtml(tag) }}</span>
                        </div>

                        <!-- 操作栏 -->
                        <div class="pin-actions">
                            <button class="pin-action-btn" @click="sharePins">
                                <span class="action-icon">&#xf1e0;</span>
                                <span>{{ pin.shareCount || 0 }}</span>
                            </button>
                            <button class="pin-action-btn" @click="scrollToComments">
                                <span class="action-icon">&#xf075;</span>
                                <span>{{ pin.commentCount || 0 }}</span>
                            </button>
                            <button class="pin-action-btn" :class="{ 'active': pin.liked }" @click="toggleLike">
                                <span class="action-icon">&#xf087;</span>
                                <span>{{ pin.likeCount || 0 }}</span>
                            </button>
                        </div>
                    </div>

                    <!-- 评论区 -->
                    <div class="comment-section" ref="commentSection">
                        <!-- 评论标题栏 + 分栏切换 -->
                        <div class="comment-title-bar">
                            <span class="comment-title">评论 {{ pin.commentCount || 0 }}</span>
                            <div class="comment-sort-tabs">
                                <span
                                    class="comment-sort-tab"
                                    :class="{ 'active': commentSort === 'latest' }"
                                    @click="switchCommentSort('latest')"
                                >最新</span>
                                <span
                                    class="comment-sort-tab"
                                    :class="{ 'active': commentSort === 'hot' }"
                                    @click="switchCommentSort('hot')"
                                >热门</span>
                            </div>
                        </div>

                        <!-- 评论输入框（折叠/展开） -->
                        <div class="comment-input-wrap" ref="commentInputWrap" :class="{ 'expanded': commentExpanded }" @click="expandCommentBox">
                            <textarea
                                v-if="commentExpanded"
                                ref="commentTextarea"
                                class="comment-textarea"
                                placeholder="平等表达，友善交流"
                                v-model="commentText"
                                maxlength="500"
                                @keydown.ctrl.enter="submitComment"
                            ></textarea>
                            <div class="comment-toolbar" v-if="commentExpanded">
                                <button class="comment-submit-btn" :disabled="!commentText.trim() || submitting" @click="submitComment">发送</button>
                            </div>
                        </div>

                        <!-- 评论列表 -->
                        <div class="comment-list">
                            <div class="comment-loading" v-if="commentsLoading">
                                <span>加载中...</span>
                            </div>
                            <div class="comment-empty" v-else-if="comments.length === 0">
                                <span>快来抢沙发</span>
                            </div>
                            <div class="comment-item" v-for="comment in comments" :key="comment.id">
                                <img :src="comment.userAvatar || defaultAvatar" class="comment-avatar" alt="avatar">
                                <div class="comment-content">
                                    <div class="comment-header">
                                        <span class="comment-user">{{ escapeHtml(comment.userName) }}</span>
                                        <span class="comment-time">{{ formatTime(comment.createdTime) }}</span>
                                    </div>
                                    <div class="comment-text">{{ escapeHtml(comment.content) }}</div>
                                    <div class="comment-actions">
                                        <button class="comment-action-btn" :class="{ 'active': comment.liked }" @click="toggleCommentLike(comment)">
                                            <span class="action-icon">&#xf087;</span>
                                            <span>{{ comment.likeCount || 0 }}</span>
                                        </button>
                                        <button class="comment-action-btn" @click="replyComment(comment)">
                                            <span class="action-icon">&#xf112;</span>
                                            <span>回复</span>
                                        </button>
                                    </div>

                                    <!-- 二级回复 -->
                                    <div class="reply-list" v-if="comment.replies && comment.replies.length">
                                        <div class="reply-item" v-for="reply in comment.replies" :key="reply.id">
                                            <span class="reply-user">{{ escapeHtml(reply.userName) }}</span>
                                            <span class="reply-text">: {{ escapeHtml(reply.content) }}</span>
                                        </div>
                                    </div>
                                </div>
                            </div>
                            <div class="comment-load-more" v-if="hasMoreComments" @click="loadMoreComments">
                                <span>加载更多</span>
                            </div>
                        </div>
                    </div>
                </template>
            </div>

            <!-- 右侧边栏：推荐沸点 -->
            <div class="pin-sidebar">
                <div class="recommend-section">
                    <div class="recommend-title">热门沸点</div>
                    <div class="recommend-empty" v-if="!recommendLoading && recommendList.length === 0">
                        <span>暂无更多推荐</span>
                    </div>
                    <div
                        class="recommend-item"
                        v-for="item in recommendList"
                        :key="item.id"
                        @mouseenter="startHoverTimer(item)"
                        @mouseleave="clearHoverTimer(item)"
                        @click="goToDetail(item)"
                    >
                        <div class="recommend-content">{{ item.content }}</div>
                        <div class="recommend-meta">
                            <img :src="item.userAvatar || defaultAvatar" class="recommend-avatar" alt="avatar">
                            <span class="recommend-name">{{ escapeHtml(item.userName) }}</span>
                            <span class="recommend-like">
                                <span class="action-icon">&#xf087;</span>
                                {{ item.likeCount || 0 }}
                            </span>
                        </div>

                        <!-- 悬浮弹框：展示完整内容 -->
                        <div class="recommend-popover" v-show="hoverItem && hoverItem.id === item.id">
                            <div class="popover-author">
                                <img :src="item.userAvatar || defaultAvatar" class="popover-avatar" alt="avatar">
                                <div class="popover-author-info">
                                    <span class="popover-name">{{ escapeHtml(item.userName) }}</span>
                                    <span class="popover-time">{{ formatTime(item.createdTime) }}</span>
                                </div>
                            </div>
                            <div class="popover-content">{{ escapeHtml(item.content) }}</div>
                            <div class="popover-images" v-if="item.imageUrls && item.imageUrls.length > 0">
                                <img :src="img" class="popover-image" v-for="(img, idx) in item.imageUrls" :key="idx" alt="image">
                            </div>
                            <div class="popover-actions">
                                <span class="popover-like">
                                    <span class="action-icon">&#xf087;</span>
                                    {{ item.likeCount || 0 }}
                                </span>
                                <span class="popover-comment">
                                    <span class="action-icon">&#xf075;</span>
                                    {{ item.commentCount || 0 }}
                                </span>
                            </div>
                        </div>
                    </div>
                </div>
            </div>
        </div>
    </div>
</template>

<script>
import { toast } from '@/utils/toast'
import { getPinsDetail, getPinsList, getComments, createComment, likePins } from '@/apis/pins'

const defaultAvatar = 'data:image/svg+xml,%3Csvg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100"%3E%3Ccircle cx="50" cy="50" r="50" fill="%23ddd"/%3E%3C/svg%3E'

export default {
    name: 'PinDetail',
    data() {
        return {
            pinsId: null,
            pin: null,
            loading: true,
            // 评论
            comments: [],
            commentSort: 'latest',
            commentPage: 1,
            commentSize: 10,
            commentsLoading: false,
            hasMoreComments: true,
            commentText: '',
            commentExpanded: false,
            replyingComment: null,
            submitting: false,
            // 推荐
            recommendList: [],
            recommendLoading: false,
            hoverItem: null,
            hoverTimers: {}
        }
    },
    computed: {
        defaultAvatar() {
            return defaultAvatar
        }
    },
    created() {
        this.pinsId = this.$route.params.id ? String(this.$route.params.id) : ''
    },
    mounted() {
        if (this.pinsId) {
            this.init()
        } else {
            this.loading = false
        }
        // 监听点击外部折叠评论框
        document.addEventListener('mousedown', this.onDocumentMouseDown)
    },
    beforeDestroy() {
        document.removeEventListener('mousedown', this.onDocumentMouseDown)
        // 清理所有悬浮定时器
        Object.keys(this.hoverTimers).forEach((key) => {
            clearTimeout(this.hoverTimers[key])
        })
    },
    methods: {
        async init() {
            this.loading = true
            var detailLoaded = false
            try {
                const res = await getPinsDetail(this.pinsId)
                if (res && res.code === 200 && res.data) {
                    this.pin = res.data
                    detailLoaded = true
                } else {
                    this.pin = null
                }
            } catch (e) {
                this.pin = null
            } finally {
                this.loading = false
            }
            // 并行加载评论和推荐，互不影响
            this.fetchComments(true)
            this.fetchRecommend()
        },

        // ============== 评论 ==============
        async fetchComments(reset) {
            if (this.commentsLoading) return
            if (reset) {
                this.commentPage = 1
                this.comments = []
                this.hasMoreComments = true
            }
            if (!this.hasMoreComments) return
            this.commentsLoading = true
            try {
                const res = await getComments({
                    pinsId: this.pinsId,
                    sort: this.commentSort,
                    page: this.commentPage,
                    size: this.commentSize
                })
                if (res && res.code === 200 && res.data) {
                    const list = (res.data.list || []).map(c => ({
                        ...c,
                        liked: c.liked || false,
                        replies: c.replies || []
                    }))
                    if (reset) {
                        this.comments = list
                    } else {
                        this.comments = this.comments.concat(list)
                    }
                    this.commentPage++
                    const total = res.data.total || 0
                    if (this.comments.length >= total || list.length < this.commentSize) {
                        this.hasMoreComments = false
                    }
                } else {
                    this.hasMoreComments = false
                }
            } catch (e) {
                this.hasMoreComments = false
            } finally {
                this.commentsLoading = false
            }
        },
        loadMoreComments() {
            this.fetchComments(false)
        },
        switchCommentSort(sort) {
            if (this.commentSort === sort) return
            this.commentSort = sort
            this.fetchComments(true)
        },
        async submitComment() {
            const content = this.commentText.trim()
            if (!content || this.submitting) return
            this.submitting = true
            try {
                const data = { pinsId: this.pinsId, content: content }
                if (this.replyingComment) {
                    data.parentId = this.replyingComment.id
                }
                const res = await createComment(data)
                if (res && res.code === 200) {
                    this.commentText = ''
                    this.replyingComment = null
                    this.commentExpanded = false
                    if (this.pin) {
                        this.$set(this.pin, 'commentCount', (this.pin.commentCount || 0) + 1)
                    }
                    toast('评论成功', 2)
                    this.fetchComments(true)
                } else {
                    toast((res && res.message) || '评论失败', 2)
                }
            } catch (e) {
                toast('评论失败，请重试', 2)
            } finally {
                this.submitting = false
            }
        },
        replyComment(comment) {
            // 展开评论框并聚焦
            this.replyingComment = comment
            this.commentExpanded = true
            this.focusCommentTextarea()
        },
        expandCommentBox() {
            // 点击折叠态评论框 -> 展开并聚焦输入区
            if (!this.commentExpanded) {
                this.commentExpanded = true
                this.focusCommentTextarea()
            }
        },
        focusCommentTextarea() {
            this.$nextTick(() => {
                if (this.$refs.commentTextarea) {
                    this.$refs.commentTextarea.focus()
                }
            })
        },
        async toggleCommentLike(comment) {
            const newLiked = !comment.liked
            try {
                const res = await likePins({ pinsId: comment.id, liked: newLiked })
                if (res && res.code === 200) {
                    comment.liked = newLiked
                    comment.likeCount = (comment.likeCount || 0) + (newLiked ? 1 : -1)
                    if (comment.likeCount < 0) comment.likeCount = 0
                }
            } catch (e) {
                toast('点赞失败', 2)
            }
        },
        onDocumentMouseDown(e) {
            // 点击外部折叠评论框（排除评论框容器内部）
            if (!this.commentExpanded) return
            const wrap = this.$refs.commentInputWrap
            if (wrap && wrap.contains(e.target)) return
            this.commentExpanded = false
            this.replyingComment = null
        },
        scrollToComments() {
            if (this.$refs.commentSection) {
                this.$refs.commentSection.scrollIntoView({ behavior: 'smooth' })
            }
        },

        // ============== 沸点操作 ==============
        async toggleLike() {
            if (!this.pin) return
            const newLiked = !this.pin.liked
            try {
                const res = await likePins({ pinsId: this.pin.id, liked: newLiked })
                if (res && res.code === 200) {
                    this.$set(this.pin, 'liked', newLiked)
                    this.$set(this.pin, 'likeCount', (this.pin.likeCount || 0) + (newLiked ? 1 : -1))
                    if (this.pin.likeCount < 0) this.$set(this.pin, 'likeCount', 0)
                }
            } catch (e) {
                toast('点赞失败', 2)
            }
        },
        async sharePins() {
            toast('分享功能正在努力开发中~', 2)
        },
        openLink(url) {
            if (url) window.open(url, '_blank')
        },

        // ============== 推荐沸点 ==============
        async fetchRecommend() {
            this.recommendLoading = true
            try {
                // 从热门分栏取前3条，排除当前沸点
                const res = await getPinsList({ tab: 'hot', page: 1, size: 3 })
                if (res && res.code === 200 && res.data) {
                    const list = res.data.list || []
                    this.recommendList = list.filter(item => String(item.id) !== String(this.pinsId))
                } else {
                    this.recommendList = []
                }
            } catch (e) {
                this.recommendList = []
            } finally {
                this.recommendLoading = false
            }
        },
        startHoverTimer(item) {
            // 300ms 延迟防误触
            this.clearHoverTimer(item)
            this.hoverTimers[item.id] = setTimeout(() => {
                this.hoverItem = item
            }, 300)
        },
        clearHoverTimer(item) {
            if (this.hoverTimers[item.id]) {
                clearTimeout(this.hoverTimers[item.id])
                this.hoverTimers[item.id] = null
            }
            // 离开时立即关闭弹框
            if (this.hoverItem && this.hoverItem.id === item.id) {
                this.hoverItem = null
            }
        },
        goToDetail(item) {
            if (item && item.id) {
                this.$router.push('/pins/detail/' + item.id)
            }
        },

        // ============== 工具 ==============
        escapeHtml(str) {
            if (!str) return ''
            return str.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;').replace(/'/g, '&#039;')
        },
        formatTime(timestamp) {
            if (!timestamp) return ''
            const t = typeof timestamp === 'string' ? new Date(timestamp).getTime() : timestamp
            if (isNaN(t)) return ''
            const diff = Date.now() - t
            const hours = Math.floor(diff / 3600000)
            const days = Math.floor(diff / 86400000)
            const months = Math.floor(diff / 2592000000)
            if (hours < 1) return '刚刚'
            if (hours < 24) return hours + '小时前'
            if (days < 30) return days + '天前'
            if (months < 12) return months + '个月前'
            return Math.floor(months / 12) + '年前'
        }
    }
}
</script>

<style lang="less" scoped>
@import '../../styles/common';

.pin-detail-page {
    min-height: 100vh;
    background: #f7f8fa;
}

.pin-detail-content {
    max-width: 1200px;
    margin: 0 auto;
    padding: 24px;
    display: flex;
    gap: 24px;
    align-items: flex-start;
}

/* 左侧主内容区 */
.pin-main {
    flex: 1;
    min-width: 0;
}

.main-loading, .main-error {
    background: #fff;
    border-radius: 8px;
    padding: 60px 24px;
    text-align: center;
    color: #8a919f;
    font-size: 14px;
    box-shadow: 0 1px 2px rgba(0,0,0,0.05);
}

/* 沸点原文卡片 */
.pin-card {
    background: #fff;
    border-radius: 8px;
    padding: 20px;
    box-shadow: 0 1px 2px rgba(0,0,0,0.05);
    margin-bottom: 16px;
}

.pin-card-header {
    display: flex;
    align-items: center;
    gap: 12px;
    margin-bottom: 12px;
}

.pin-avatar {
    width: 48px;
    height: 48px;
    border-radius: 50%;
    object-fit: cover;
}

.pin-author-info {
    display: flex;
    flex-direction: column;
    gap: 2px;
}

.pin-author-name {
    font-size: 15px;
    font-weight: 600;
    color: #252933;
}

.pin-publish-time {
    font-size: 12px;
    color: #8a919f;
}

.pin-content-text {
    font-size: 15px;
    color: #252933;
    line-height: 1.7;
    margin-bottom: 12px;
    word-break: break-word;
    white-space: pre-wrap;
}

/* 图片 */
.pin-images {
    display: flex;
    gap: 8px;
    flex-wrap: wrap;
    margin-bottom: 12px;
}

.pin-content-image {
    border-radius: 6px;
    object-fit: cover;
    &.img-count-1 {
        max-width: 320px;
        max-height: 220px;
        width: auto;
        height: auto;
    }
    &.img-count-2 {
        width: 160px;
        height: 160px;
    }
    &.img-count-3 {
        width: 110px;
        height: 110px;
    }
}

/* 链接卡片 */
.pin-link-card {
    margin-bottom: 12px;
    padding: 10px 14px;
    background: #f7f8fa;
    border-radius: 6px;
    cursor: pointer;
    &:hover {
        background: #eaf2ff;
    }
}

.link-domain {
    font-size: 13px;
    color: #1e80ff;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
}

/* 标签 */
.pin-tags {
    display: flex;
    align-items: center;
    gap: 8px;
    flex-wrap: wrap;
    margin-bottom: 12px;
}

.pin-topic {
    display: inline-flex;
    align-items: center;
    padding: 2px 8px;
    background: #fff7e6;
    color: #fa8c16;
    font-size: 12px;
    border-radius: 4px;
}

.action-icon {
    font-family: fontawesome;
}

/* 操作栏 */
.pin-actions {
    display: flex;
    align-items: center;
    gap: 24px;
    padding-top: 12px;
    border-top: 1px solid #f2f3f5;
}

.pin-action-btn {
    display: flex;
    align-items: center;
    gap: 4px;
    padding: 4px 8px;
    border: none;
    background: transparent;
    font-size: 13px;
    color: #8a919f;
    cursor: pointer;
    transition: color 0.2s;
    &:hover {
        color: #1e80ff;
    }
    &.active {
        color: #ff4d4f;
    }
}

/* 评论区 */
.comment-section {
    background: #fff;
    border-radius: 8px;
    padding: 20px;
    box-shadow: 0 1px 2px rgba(0,0,0,0.05);
}

.comment-title-bar {
    display: flex;
    align-items: center;
    justify-content: space-between;
    margin-bottom: 16px;
}

.comment-title {
    font-size: 16px;
    font-weight: 600;
    color: #252933;
}

.comment-sort-tabs {
    display: flex;
    gap: 8px;
}

.comment-sort-tab {
    padding: 4px 12px;
    font-size: 13px;
    color: #8a919f;
    cursor: pointer;
    border-radius: 4px;
    transition: all 0.2s;
    &:hover {
        color: #1e80ff;
    }
    &.active {
        color: #1e80ff;
        background: #eaf2ff;
        font-weight: 500;
    }
}

/* 评论输入框 */
.comment-input-wrap {
    border: 1px solid #e4e6eb;
    border-radius: 8px;
    padding: 10px 14px;
    margin-bottom: 16px;
    background: #f4f5f5;
    cursor: text;
    transition: background 0.2s, border-color 0.2s;
    /* 悬浮时背景色加深 */
    &:hover {
        background: #ebebeb;
    }
    &.expanded {
        background: #fff;
        border-color: #1e80ff;
        cursor: default;
        &:hover {
            background: #fff;
        }
    }
}

.comment-textarea {
    width: 100%;
    min-height: 60px;
    border: none;
    resize: none;
    font-size: 14px;
    line-height: 1.6;
    color: #252933;
    background: transparent;
    outline: none;
    &::placeholder {
        color: #c4c9d1;
    }
}

.comment-toolbar {
    display: flex;
    justify-content: flex-end;
    margin-top: 8px;
}

.comment-submit-btn {
    padding: 6px 20px;
    border: none;
    border-radius: 16px;
    background: #1e80ff;
    color: #fff;
    font-size: 13px;
    cursor: pointer;
    &:hover {
        background: #4096ff;
    }
    &:disabled {
        background: #c4c9d1;
        cursor: not-allowed;
    }
}

/* 评论列表 */
.comment-loading, .comment-empty {
    text-align: center;
    padding: 24px 0;
    color: #8a919f;
    font-size: 13px;
}

.comment-item {
    display: flex;
    gap: 12px;
    padding: 14px 0;
    border-bottom: 1px solid #f2f3f5;
    &:last-child {
        border: none;
    }
}

.comment-avatar {
    width: 36px;
    height: 36px;
    border-radius: 50%;
    object-fit: cover;
    flex-shrink: 0;
}

.comment-content {
    flex: 1;
    min-width: 0;
}

.comment-header {
    display: flex;
    align-items: center;
    gap: 8px;
    margin-bottom: 4px;
}

.comment-user {
    font-size: 13px;
    font-weight: 500;
    color: #252933;
}

.comment-time {
    font-size: 12px;
    color: #8a919f;
}

.comment-text {
    font-size: 14px;
    color: #252933;
    line-height: 1.5;
    margin-bottom: 8px;
    word-break: break-word;
}

.comment-actions {
    display: flex;
    align-items: center;
    gap: 16px;
}

.comment-action-btn {
    display: flex;
    align-items: center;
    gap: 4px;
    padding: 2px 8px;
    border: none;
    background: transparent;
    font-size: 12px;
    color: #8a919f;
    cursor: pointer;
    transition: color 0.2s;
    &:hover {
        color: #1e80ff;
    }
    &.active {
        color: #ff4d4f;
    }
}

.reply-list {
    margin-top: 8px;
    padding: 8px 12px;
    background: #f7f8fa;
    border-radius: 6px;
}

.reply-item {
    padding: 4px 0;
    font-size: 13px;
    line-height: 1.5;
}

.reply-user {
    color: #1e80ff;
    font-weight: 500;
    margin-right: 4px;
}

.reply-text {
    color: #515767;
}

.comment-load-more {
    text-align: center;
    padding: 12px 0;
    font-size: 13px;
    color: #1e80ff;
    cursor: pointer;
    &:hover {
        text-decoration: underline;
    }
}

/* 右侧边栏 */
.pin-sidebar {
    width: 300px;
    flex-shrink: 0;
}

.recommend-section {
    background: #fff;
    border-radius: 8px;
    padding: 16px;
    box-shadow: 0 1px 2px rgba(0,0,0,0.05);
    position: sticky;
    top: 24px;
}

.recommend-title {
    font-size: 14px;
    font-weight: 600;
    color: #252933;
    margin-bottom: 12px;
}

.recommend-empty {
    text-align: center;
    padding: 24px 0;
    color: #8a919f;
    font-size: 13px;
}

.recommend-item {
    position: relative;
    padding: 10px;
    border-radius: 8px;
    cursor: pointer;
    margin-bottom: 8px;
    transition: background-color 0.2s;
    &:hover {
        background: #f7f8fa;
    }
}

.recommend-content {
    font-size: 13px;
    color: #252933;
    line-height: 1.5;
    margin-bottom: 8px;
    display: -webkit-box;
    -webkit-line-clamp: 2;
    -webkit-box-orient: vertical;
    overflow: hidden;
}

.recommend-meta {
    display: flex;
    align-items: center;
    gap: 6px;
}

.recommend-avatar {
    width: 22px;
    height: 22px;
    border-radius: 50%;
    object-fit: cover;
}

.recommend-name {
    font-size: 12px;
    color: #8a919f;
    flex: 1;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
}

.recommend-like {
    font-size: 12px;
    color: #8a919f;
    display: flex;
    align-items: center;
    gap: 2px;
}

/* 悬浮弹框 */
.recommend-popover {
    position: absolute;
    left: 0;
    right: 0;
    bottom: calc(100% + 8px);
    background: #fff;
    border: 1px solid #e4e6eb;
    border-radius: 8px;
    padding: 12px;
    box-shadow: 0 8px 24px rgba(0,0,0,0.15);
    z-index: 1000;
}

.popover-author {
    display: flex;
    align-items: center;
    gap: 8px;
    margin-bottom: 8px;
}

.popover-avatar {
    width: 32px;
    height: 32px;
    border-radius: 50%;
    object-fit: cover;
}

.popover-author-info {
    display: flex;
    flex-direction: column;
    gap: 2px;
}

.popover-name {
    font-size: 13px;
    font-weight: 500;
    color: #252933;
}

.popover-time {
    font-size: 11px;
    color: #8a919f;
}

.popover-content {
    font-size: 13px;
    color: #252933;
    line-height: 1.6;
    margin-bottom: 8px;
    word-break: break-word;
    max-width: 100%;
}

.popover-images {
    display: flex;
    gap: 6px;
    flex-wrap: wrap;
    margin-bottom: 8px;
}

.popover-image {
    width: 60px;
    height: 60px;
    border-radius: 4px;
    object-fit: cover;
}

.popover-actions {
    display: flex;
    align-items: center;
    gap: 16px;
    font-size: 12px;
    color: #8a919f;
}

.popover-like, .popover-comment {
    display: flex;
    align-items: center;
    gap: 4px;
}

/* 响应式 */
@media screen and (max-width: 992px) {
    .pin-detail-content {
        flex-direction: column;
        padding: 12px;
    }
    .pin-sidebar {
        width: 100%;
    }
}
</style>