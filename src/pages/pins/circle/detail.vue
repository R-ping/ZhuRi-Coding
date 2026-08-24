<template>
    <div class="circle-detail-page">
        <div class="art-top" v-if="!isDesktop"><HomeBar/></div>
        
        <div class="detail-content">
            <div class="detail-main">
                <!-- 圈子信息（白底卡片） -->
                <div class="circle-info-card">
                    <div class="circle-icon">{{ circleInfo.icon || '📌' }}</div>
                    <div class="circle-info-body">
                        <h1 class="circle-name">{{ circleInfo.name }}</h1>
                        <p class="circle-desc" v-if="circleInfo.description">{{ circleInfo.description }}</p>
                        <div class="circle-stats">{{ circleInfo.memberCount || 0 }} 掘友 · {{ circleInfo.pinsCount || 0 }} 沸点</div>
                    </div>
                    <button
                        class="join-btn"
                        :class="{ joined: circleInfo.isJoined }"
                        @click="toggleJoin"
                    >
                        {{ circleInfo.isJoined ? '已加入' : '+ 加入' }}
                    </button>
                </div>

                <!-- 发布入口（深色卡片，固定文案），点击弹发布框 -->
                <div class="publish-entry" @click="openPublishModal">
                    <span class="entry-text">快和逐友一起分享新鲜事！</span>
                </div>

                <!-- 发布弹窗（复用公共组件） -->
                <PinsPublishModal
                    v-if="showPublishModal"
                    v-model="publishContent"
                    :selectedCircle="selectedCircle"
                    :publishing="publishing"
                    @close="closePublishModal"
                    @publish="handleCirclePublish"
                />

                <!-- Tab 切换 -->
                <div class="feed-tabs">
                    <div 
                        class="feed-tab"
                        :class="{ active: activeTab === 'hot' }"
                        @click="switchTab('hot')"
                    >最热</div>
                    <div 
                        class="feed-tab"
                        :class="{ active: activeTab === 'new' }"
                        @click="switchTab('new')"
                    >最新</div>
                    <div 
                        class="feed-tab"
                        :class="{ active: activeTab === 'featured' }"
                        @click="switchTab('featured')"
                    >精选</div>
                </div>

                <!-- 沸点列表（白底卡片） -->
                <div class="feed-list">
                    <div class="feed-card" v-for="pin in feedList" :key="pin.id">
                        <div class="feed-header">
                            <img class="feed-avatar" :src="pin.userAvatar || defaultAvatar" />
                            <span class="feed-username">{{ pin.userName }}</span>
                            <span class="feed-time">{{ formatTime(pin.createdTime) }}</span>
                        </div>
                        <div class="feed-content">{{ pin.content }}</div>
                        <div class="feed-actions">
                            <span class="feed-action" :class="{ 'active': pin.liked }" @click="toggleLike(pin)">👍 {{ pin.likeCount || 0 }}</span>
                            <span class="feed-action" @click="goToPinDetail(pin)">💬 {{ pin.commentCount || 0 }}</span>
                        </div>
                    </div>
                    <div class="empty-state" v-if="feedList.length === 0 && !loading">
                        <p>暂无内容</p>
                    </div>
                    <div class="loading-state" v-if="loading">
                        <p>加载中...</p>
                    </div>
                </div>
            </div>

            <!-- 右侧边栏 -->
            <div class="detail-sidebar">
                <div class="sidebar-card">
                    <recommend-topics />
                </div>
            </div>
        </div>
    </div>
</template>

<script>
import HomeBar from '@/components/bars/home_bar'
import Utils from '@/utils/env'
const defaultAvatar = 'data:image/svg+xml,%3Csvg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100"%3E%3Ccircle cx="50" cy="50" r="50" fill="%23ddd"/%3E%3C/svg%3E'
import { toast } from '@/utils/toast'
import { requireLogin } from '@/utils/login'
import RecommendTopics from '@/components/RecommendTopics.vue'
import PinsPublishModal from '@/pages/creator/pins/components/PinsPublishModal.vue'
import { publishPins, likePins } from '@/apis/pins'
import { getCircleDetail, joinCircle, leaveCircle, getCircleFeed } from '@/apis/circle'

export default {
    name: 'CircleDetail',
    components: { HomeBar, RecommendTopics, PinsPublishModal },
    data() {
        return {
            circleInfo: {
                id: null,
                name: '',
                description: '',
                icon: '',
                memberCount: 0,
                pinsCount: 0,
                isJoined: false
            },
            activeTab: 'hot',
            feedList: [],
            feedPage: 1,
            feedSize: 20,
            hasMore: true,
            loading: false,
            showPublishModal: false,
            publishContent: '',
            publishing: false,
            selectedCircle: null
        }
    },
    computed: {
        isDesktop() {
            return Utils.isDesktop()
        },
        defaultAvatar() {
            return defaultAvatar
        },
        circleId() {
            return this.$route.params.id
        }
    },
    mounted() {
        this.fetchCircleDetail()
        this.fetchFeed()
    },
    methods: {
        async fetchCircleDetail() {
            try {
                const res = await getCircleDetail(this.circleId)
                if (res && res.code === 200 && res.data) {
                    this.circleInfo = res.data
                }
            } catch (e) {
                toast('获取圈子信息失败', 2)
            }
        },
        async fetchFeed() {
            if (this.loading || !this.hasMore) return
            this.loading = true
            try {
                const res = await getCircleFeed(this.circleId, {
                    tab: this.activeTab,
                    page: this.feedPage,
                    size: this.feedSize
                })
                if (res && res.code === 200 && res.data) {
                    const data = res.data
                    const list = data.list || data.records || []
                    this.feedList = this.feedPage === 1 ? list : [...this.feedList, ...list]
                    this.hasMore = (data.has_more !== undefined) ? data.has_more : (list.length >= this.feedSize)
                }
            } catch (e) {} finally {
                this.loading = false
            }
        },
        switchTab(tab) {
            if (this.activeTab === tab) return
            this.activeTab = tab
            this.feedPage = 1
            this.feedList = []
            this.hasMore = true
            this.fetchFeed()
        },
        // 沸点点赞/取消点赞（需登录）
        async toggleLike(pin) {
            if (!requireLogin()) return
            const newLiked = !pin.liked
            try {
                const res = await likePins({ pinsId: pin.id, liked: newLiked })
                if (res && res.code === 200) {
                    this.$set(pin, 'liked', newLiked)
                    this.$set(pin, 'likeCount', (pin.likeCount || 0) + (newLiked ? 1 : -1))
                    if (pin.likeCount < 0) this.$set(pin, 'likeCount', 0)
                }
            } catch (e) {
                toast('点赞失败', 2)
            }
        },
        // 点击评论/沸点项跳转沸点详情页
        goToPinDetail(pin) {
            if (pin && pin.id) {
                window.open('/pins/detail/' + pin.id, '_blank')
            }
        },
        async toggleJoin() {
            if (!requireLogin()) return
            try {
                if (this.circleInfo.isJoined) {
                    const res = await leaveCircle(this.circleId)
                    if (res && res.code === 200) {
                        this.circleInfo.isJoined = false
                        this.circleInfo.memberCount = Math.max(0, (this.circleInfo.memberCount || 1) - 1)
                        toast('已退出圈子', 2)
                    }
                } else {
                    const res = await joinCircle(this.circleId)
                    if (res && res.code === 200) {
                        this.circleInfo.isJoined = true
                        this.circleInfo.memberCount = (this.circleInfo.memberCount || 0) + 1
                        toast('加入成功', 2)
                    }
                }
            } catch (e) {
                toast('操作失败，请重试', 2)
            }
        },
        openPublishModal() {
            if (!requireLogin()) return
            // 预填当前圈子
            this.selectedCircle = {
                id: this.circleId,
                name: this.circleInfo.name
            }
            this.showPublishModal = true
        },
        closePublishModal() {
            this.showPublishModal = false
            this.publishContent = ''
        },
        async handleCirclePublish(data) {
            if (!requireLogin()) return
            try {
                this.publishing = true
                const res = await publishPins(data)
                if (res && res.code === 200) {
                    toast('发布成功！', 2)
                    this.closePublishModal()
                    // 重置第一页刷新沸点列表
                    this.feedPage = 1
                    this.feedList = []
                    this.hasMore = true
                    this.fetchFeed()
                } else {
                    toast(res && res.message ? res.message : '发布失败，请重试', 2)
                }
            } catch (e) {
                toast('发布失败，请重试', 2)
            } finally {
                this.publishing = false
            }
        },
        formatTime(timestamp) {
            if (!timestamp) return ''
            const now = Date.now()
            const diff = now - new Date(timestamp).getTime()
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
@import '../../../styles/common';

.circle-detail-page {
    min-height: 100vh;
    background: #f7f8fa;
}

.detail-content {
    max-width: 1100px;
    margin: 0 auto;
    padding: 24px;
    display: flex;
    gap: 24px;
}

.detail-main {
    flex: 1;
    min-width: 0;
    background: #fff;
    border-radius: 8px;
    padding: 24px;
    box-shadow: 0 1px 2px rgba(0,0,0,0.05);
}

.circle-info-card {
    display: flex;
    align-items: flex-start;
    gap: 16px;
    padding-bottom: 20px;
    border-bottom: 1px solid #e4e6eb;
    margin-bottom: 20px;
}

.circle-icon {
    font-size: 48px;
    width: 72px;
    height: 72px;
    display: flex;
    align-items: center;
    justify-content: center;
    background: #f7f8fa;
    border-radius: 12px;
    border: 1px solid #e4e6eb;
    flex-shrink: 0;
}

.circle-info-body {
    flex: 1;
    min-width: 0;
}

.circle-name {
    font-size: 22px;
    font-weight: 600;
    color: #252933;
    margin: 0 0 8px;
}

.circle-desc {
    font-size: 14px;
    color: #515767;
    margin: 0 0 8px;
    line-height: 1.6;
}

.circle-stats {
    font-size: 13px;
    color: #8a919f;
}

.join-btn {
    padding: 8px 24px;
    border: 1px solid #1e80ff;
    border-radius: 6px;
    background: #1e80ff;
    color: #fff;
    font-size: 14px;
    cursor: pointer;
    flex-shrink: 0;
    transition: all 0.2s;
    &:hover {
        background: #1171ee;
    }
    &.joined {
        background: #fff;
        color: #8a919f;
        border-color: #8a919f;
    }
}

/* 发布入口：灰色卡片，固定文案，点击弹发布框 */
.publish-entry {
    display: flex;
    align-items: center;
    padding: 16px 20px;
    background: #86909c;
    border-radius: 8px;
    cursor: pointer;
    margin-bottom: 20px;
    transition: background-color 0.2s;
    &:hover {
        background: #6b7785;
    }
    .entry-text {
        flex: 1;
        color: #fff;
        font-size: 15px;
        font-weight: 500;
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
    }
}

.feed-tabs {
    display: flex;
    gap: 24px;
    border-bottom: 1px solid #e4e6eb;
    margin-bottom: 16px;
}

.feed-tab {
    padding: 8px 0;
    font-size: 15px;
    color: #515767;
    cursor: pointer;
    border-bottom: 2px solid transparent;
    transition: all 0.2s;
    &:hover {
        color: #1e80ff;
    }
    &.active {
        color: #1e80ff;
        border-bottom-color: #1e80ff;
        font-weight: 500;
    }
}

.feed-list {
    display: flex;
    flex-direction: column;
    gap: 16px;
}

.feed-card {
    padding: 16px;
    background: #fff;
    border: 1px solid #e4e6eb;
    border-radius: 8px;
}

.feed-header {
    display: flex;
    align-items: center;
    gap: 8px;
    margin-bottom: 10px;
}

.feed-avatar {
    width: 32px;
    height: 32px;
    border-radius: 50%;
    object-fit: cover;
}

.feed-username {
    font-size: 14px;
    font-weight: 500;
    color: #252933;
}

.feed-time {
    font-size: 12px;
    color: #8a919f;
    margin-left: auto;
}

.feed-content {
    font-size: 14px;
    color: #252933;
    line-height: 1.6;
    margin-bottom: 10px;
}

.feed-actions {
    display: flex;
    gap: 20px;
}

.feed-action {
    font-size: 13px;
    color: #8a919f;
    cursor: pointer;
    &.active {
        color: #e5383b;
    }
}

.empty-state, .loading-state {
    text-align: center;
    padding: 40px;
    color: #8a919f;
}

/* 右侧边栏 */
.detail-sidebar {
    width: 300px;
    flex-shrink: 0;
}

.sidebar-card {
    background: #fff;
    border-radius: 8px;
    padding: 16px;
    box-shadow: 0 1px 2px rgba(0,0,0,0.05);
}

/* 响应式 */
@media screen and (max-width: 768px) {
    .detail-content {
        flex-direction: column;
        padding: 12px;
    }
    .detail-sidebar {
        width: 100%;
    }
}
</style>