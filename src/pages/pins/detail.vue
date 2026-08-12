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
                        <div class="pin-tags" v-if="pin.circleId || (pin.topicTags && pin.topicTags.length > 0)">
                            <span class="pin-circle" v-if="pin.circleId" @click="goToCircle">{{ escapeHtml(pin.circleName) }}</span>
                            <span class="pin-topic" v-for="(tag, idx) in pin.topicTags" :key="idx" @click="goToTopic">{{ escapeHtml(tag) }}</span>
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
                            <!-- 回复目标提示 -->
                            <div class="reply-target-bar" v-if="commentExpanded && replyTarget">
                                <span class="reply-label">回复</span>
                                <span class="reply-target-name">@{{ escapeHtml(replyTargetName) }}</span>
                                <span class="reply-cancel" @click.stop="cancelReply">取消</span>
                            </div>
                            <textarea
                                v-if="commentExpanded"
                                ref="commentTextarea"
                                class="comment-textarea"
                                :placeholder="replyTarget ? '回复 @' + replyTargetName : '平等表达，友善交流'"
                                v-model="commentText"
                                maxlength="1000"
                                @keydown.ctrl.enter="submitComment"
                            ></textarea>
                            <!-- 已选图片预览 -->
                            <div class="comment-images-preview" v-if="commentExpanded && uploadedImages.length">
                                <div class="comment-preview-item" v-for="(img, idx) in uploadedImages" :key="idx">
                                    <img :src="img" alt="图片">
                                    <i class="preview-remove" @click.stop="removeImage(idx)">&times;</i>
                                </div>
                            </div>
                            <div class="comment-toolbar" v-if="commentExpanded">
                                <div class="toolbar-left">
                                    <span class="tool-item" title="表情" @click.stop="showEmoji = !showEmoji">
                                        <span class="action-icon">&#xf118;</span>
                                    </span>
                                    <span class="tool-item" title="图片" @click.stop="triggerImageUpload">
                                        <span class="action-icon">&#xf03e;</span>
                                    </span>
                                    <input ref="commentImageInput" type="file" accept="image/*" style="display:none" @change="handleImageUpload">
                                </div>
                                <div class="toolbar-right">
                                    <span class="word-count" :class="{ 'limit': commentText.length >= 1000 }">{{ commentText.length }}/1000</span>
                                    <button class="comment-submit-btn" :disabled="!canSubmit" @click="submitComment">发送</button>
                                </div>
                            </div>
                            <!-- 表情面板 -->
                            <div class="emoji-panel" v-if="commentExpanded && showEmoji" @click.stop>
                                <span v-for="e in emojiList" :key="e" class="emoji-item" @click="insertEmoji(e)">{{ e }}</span>
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
                                    <div class="comment-images" v-if="comment.imageUrls && comment.imageUrls.length">
                                        <img v-for="(img, idx) in comment.imageUrls" :key="idx" :src="img" class="comment-image" alt="图片">
                                    </div>
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
                                            <div class="reply-main">
                                                <span class="reply-user">{{ escapeHtml(reply.userName) }}</span>
                                                <template v-if="reply.replyToUserName">
                                                    <span class="reply-sep">回复</span>
                                                    <span class="reply-target-user">@{{ escapeHtml(reply.replyToUserName) }}</span>
                                                </template>
                                                <span class="reply-colon">:</span>
                                                <span class="reply-text">{{ escapeHtml(reply.content) }}</span>
                                            </div>
                                            <div class="reply-images" v-if="reply.imageUrls && reply.imageUrls.length">
                                                <img v-for="(img, idx) in reply.imageUrls" :key="idx" :src="img" class="reply-image" alt="图片">
                                            </div>
                                            <div class="reply-meta">
                                                <span class="reply-time">{{ formatTime(reply.createdTime) }}</span>
                                                <button class="reply-action-btn" @click="replyToSubReply(comment, reply)">回复</button>
                                            </div>
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
import { uploadFile } from '@/common/oss_upload'

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
            replyTarget: null,
            showEmoji: false,
            uploadedImages: [],
            emojiList: [
                '😀', '😃', '😄', '😁', '😅', '🤣', '😂', '🙂', '😊', '😇',
                '😍', '😩', '😘', '😗', '😚', '😋', '😛', '😜', '😪', '😝',
                '🤑', '🤗', '🤭', '🤫', '🤔', '😐', '🤨', '😐', '😑', '😶',
                '😏', '😒', '🙄', '🤬', '🤮', '🤯', '😲', '🤐', '😤', '😪',
                '👍', '👎', '👏', '🙌', '🤝', '💪', '👋', '🤙', '❤️', '🔥',
                '⭐', '🎉', '🙏', '💯', '✨', '💡', '📌', '💬', '🗨️', '📝'
            ],
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
        },
        // 回复目标昵称（兼顾未知字段）
        replyTargetName() {
            return (this.replyTarget && (this.replyTarget.userName || this.replyTarget.name || '用户')) || ''
        },
        // 可提交：文本或图片至少其一，且非提交中
        canSubmit() {
            return (this.commentText.trim() || this.uploadedImages.length > 0) && !this.submitting
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
            if (!this.canSubmit) return
            this.submitting = true
            try {
                const data = {
                    pinsId: this.pinsId,
                    content: this.commentText.trim(),
                    imageUrls: this.uploadedImages.slice()
                }
                // 回复场景：parentId 指向其所属的顶级评论，保证在同一回复主题下展示
                if (this.replyingComment) {
                    data.parentId = this.replyingComment.id
                }
                // 若回复的是二级回复，携带被回复者信息用于"回复 @xxx"展示
                if (this.replyTarget) {
                    data.replyToUserId = this.replyTarget.userId
                    data.replyToUserName = this.replyTarget.userName || ''
                }
                const res = await createComment(data)
                if (res && res.code === 200) {
                    this.commentText = ''
                    this.uploadedImages = []
                    this.replyingComment = null
                    this.replyTarget = null
                    this.showEmoji = false
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
            // 回复一级评论：展开评论框并聚焦
            this.replyingComment = comment
            this.replyTarget = { userId: comment.userId, userName: comment.userName }
            this.showEmoji = false
            this.commentExpanded = true
            this.focusCommentTextarea()
        },
        replyToSubReply(comment, reply) {
            // 回复二级回复：父评论仍为顶级评论，回复目标为被回复的二级回复作者
            this.replyingComment = comment
            this.replyTarget = { userId: reply.userId, userName: reply.userName }
            this.showEmoji = false
            this.commentExpanded = true
            this.focusCommentTextarea()
        },
        cancelReply() {
            this.replyingComment = null
            this.replyTarget = null
        },
        insertEmoji(emoji) {
            this.commentText += emoji
            this.showEmoji = false
            this.focusCommentTextarea()
        },
        triggerImageUpload() {
            this.$refs.commentImageInput.click()
        },
        async handleImageUpload(e) {
            const file = e.target.files[0]
            if (!file) return
            if (!file.type.startsWith('image/')) {
                toast('请选择图片文件', 2)
                return
            }
            try {
                const url = await uploadFile(file)
                if (url) {
                    this.uploadedImages.push(url)
                }
            } catch (err) {
                toast('图片上传失败', 2)
            }
            this.$refs.commentImageInput.value = ''
        },
        removeImage(idx) {
            this.uploadedImages.splice(idx, 1)
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
            this.replyTarget = null
            this.showEmoji = false
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
        // 点击圈子标签 -> 跳转圈子详情页
        goToCircle() {
            if (this.pin && this.pin.circleId) {
                this.$router.push('/pins/circle/' + this.pin.circleId)
            }
        },
        // 点击话题标签 -> 跳转话题详情页
        goToTopic() {
            if (this.pin && this.pin.topicId) {
                this.$router.push('/pin/topic/' + this.pin.topicId)
            }
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
            if (diff < 0) return '刚刚'
            const minutes = Math.floor(diff / 60000)
            const hours = Math.floor(diff / 3600000)
            const days = Math.floor(diff / 86400000)
            const months = Math.floor(diff / 2592000000)
            if (minutes < 1) return '刚刚'
            if (minutes < 60) return minutes + '分钟前'
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
    cursor: pointer;
    &:hover {
        background: #ffe7ba;
    }
}

.pin-circle {
    display: inline-flex;
    align-items: center;
    padding: 2px 8px;
    background: #eaf2ff;
    color: #1e80ff;
    font-size: 12px;
    border-radius: 4px;
    cursor: pointer;
    &:hover {
        background: #d6e4ff;
    }
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
    justify-content: space-between;
    align-items: center;
    margin-top: 8px;
}

.toolbar-left {
    display: flex;
    align-items: center;
    gap: 4px;
}

.toolbar-right {
    display: flex;
    align-items: center;
    gap: 12px;
}

.tool-item {
    width: 30px;
    height: 30px;
    display: flex;
    align-items: center;
    justify-content: center;
    border-radius: 6px;
    cursor: pointer;
    color: #515767;
    font-size: 16px;
    transition: background-color 0.2s;
    &:hover {
        background-color: #e4e6eb;
    }
}

.word-count {
    font-size: 12px;
    color: #8a93a6;
    &.limit {
        color: #ff4d4f;
    }
}

/* 回复目标提示 */
.reply-target-bar {
    display: flex;
    align-items: center;
    gap: 6px;
    padding: 6px 2px 8px;
    font-size: 13px;
    color: #515767;
    .reply-label {
        color: #8a93a6;
    }
    .reply-target-name {
        font-weight: 500;
        color: #1e80ff;
        flex: 1;
    }
    .reply-cancel {
        cursor: pointer;
        color: #8a93a6;
        &:hover {
            color: #515767;
        }
    }
}

/* 表情面板 */
.emoji-panel {
    display: flex;
    flex-wrap: wrap;
    gap: 4px;
    padding: 8px 2px;
    max-height: 160px;
    overflow-y: auto;
}

.emoji-item {
    width: 30px;
    height: 30px;
    display: flex;
    align-items: center;
    justify-content: center;
    font-size: 19px;
    cursor: pointer;
    border-radius: 4px;
    &:hover {
        background-color: #f2f3f5;
    }
}

/* 已选图片预览 */
.comment-images-preview {
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
    margin-top: 8px;
}

.comment-preview-item {
    position: relative;
    width: 60px;
    height: 60px;
    border-radius: 6px;
    overflow: hidden;
    img {
        width: 100%;
        height: 100%;
        object-fit: cover;
    }
    .preview-remove {
        position: absolute;
        top: -4px;
        right: -4px;
        width: 18px;
        height: 18px;
        line-height: 16px;
        text-align: center;
        background: #fff;
        color: #999;
        font-size: 14px;
        font-style: normal;
        border-radius: 50%;
        cursor: pointer;
        box-shadow: 0 1px 2px rgba(0,0,0,0.2);
        &:hover {
            color: #ff4d4f;
        }
    }
}

/* 评论图片展示 */
.comment-images {
    display: flex;
    gap: 6px;
    flex-wrap: wrap;
    margin-bottom: 8px;
}

.comment-image {
    width: 80px;
    height: 80px;
    border-radius: 6px;
    object-fit: cover;
    cursor: pointer;
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
    padding: 6px 0;
    font-size: 13px;
    line-height: 1.5;
}

.reply-main {
    display: inline;
}

.reply-user {
    color: #1e80ff;
    font-weight: 500;
    margin-right: 4px;
}

.reply-sep {
    color: #8a93a6;
    margin-right: 4px;
}

.reply-target-user {
    color: #1e80ff;
    font-weight: 500;
    margin-right: 4px;
}

.reply-colon {
    color: #515767;
    margin-right: 4px;
}

.reply-text {
    color: #515767;
    word-break: break-word;
}

.reply-images {
    display: flex;
    gap: 6px;
    flex-wrap: wrap;
    margin-top: 6px;
}

.reply-image {
    width: 60px;
    height: 60px;
    border-radius: 6px;
    object-fit: cover;
    cursor: pointer;
}

.reply-meta {
    display: flex;
    align-items: center;
    gap: 12px;
    margin-top: 4px;
}

.reply-time {
    font-size: 12px;
    color: #8a93a6;
}

.reply-action-btn {
    padding: 0;
    border: none;
    background: transparent;
    font-size: 12px;
    color: #8a93a6;
    cursor: pointer;
    &:hover {
        color: #1e80ff;
    }
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