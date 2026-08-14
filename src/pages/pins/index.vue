<template>
    <div class="pins-page" :class="{ 'is-desktop': isDesktop }">
        <div class="art-top" v-if="!isDesktop"><HomeBar/></div>
        
        <div class="pins-content">
            <!-- 左侧边栏 -->
            <div class="pins-sidebar">
                <div class="sidebar-section">
                    <div 
                        class="sidebar-item" 
                        :class="{ 'active': activeTab === 'latest' }"
                        @click="switchTab('latest')"
                    >
                        <span class="sidebar-icon">&#xf01e;</span>
                        <span class="sidebar-text">最新</span>
                    </div>
                    <div 
                        class="sidebar-item" 
                        :class="{ 'active': activeTab === 'hot' }"
                        @click="switchTab('hot')"
                    >
                        <span class="sidebar-icon">&#xf06d;</span>
                        <span class="sidebar-text">最热</span>
                    </div>
                    <div 
                        class="sidebar-item" 
                        :class="{ 'active': activeTab === 'follow' }"
                        @click="switchTab('follow')"
                    >
                        <span class="sidebar-icon">&#xf0c0;</span>
                        <span class="sidebar-text">关注</span>
                    </div>
                </div>

                <div class="sidebar-section">
                    <div class="section-title">我的圈子</div>
                    <div 
                        class="sidebar-item circle-item"
                        v-for="circle in myCircles.slice(0, 5)"
                        :key="circle.id"
                        :class="{ 'active': activeCircle === circle.id }"
                        @click="selectCircle(circle)"
                    >
                        <span class="sidebar-text">{{ escapeHtml(circle.name) }}</span>
                    </div>
                    <div 
                        class="sidebar-item more-item"
                        v-if="myCircles.length > 5"
                        @click="$router.push('/pins/circles')"
                    >
                        <span class="sidebar-text">更多</span>
                    </div>
                </div>

                <div class="sidebar-section">
                    <div class="section-title">推荐圈子</div>
                    <div 
                        class="sidebar-item circle-item"
                        v-for="circle in recommendedCircles.slice(0, 5)"
                        :key="circle.id"
                        :class="{ 'active': activeCircle === circle.id }"
                        @click="selectCircle(circle)"
                    >
                        <span class="sidebar-text">{{ escapeHtml(circle.name) }}</span>
                    </div>
                    <div 
                        class="sidebar-item more-item"
                        @click="$router.push('/pins/circles')"
                    >
                        <span class="sidebar-text">更多</span>
                    </div>
                </div>
            </div>

            <!-- 右侧主内容区 -->
            <div class="pins-main">
                <!-- 发布框 -->
                <div class="publish-box-wrapper">
                    <PinsPublishBox
                        ref="publishBox"
                        v-model="publishContent"
                        :selectedCircle="selectedCircle"
                        :selectedTopic="selectedTopic"
                        :publishing="publishing"
                        @select-circle="showCircleSelector = true"
                        @select-topic="showTopicSelector = true"
                        @publish="handlePublish"
                        @update:selectedTopic="selectedTopic = $event"
                    />
                </div>

                <!-- 帖子列表 -->
                <div class="pins-list">
                    <div class="pins-empty" v-if="pinsList.length === 0 && !pinsLoading">
                        <span v-if="pinsError">加载失败，请检查网络后重试</span>
                        <span v-else>暂无内容</span>
                        <span class="pins-retry-btn" v-if="pinsError" @click="fetchPinsList(true)">点击重试</span>
                    </div>
                    <div class="pins-item" v-for="pins in pinsList" :key="pins.id">
                        <img :src="pins.userAvatar || defaultAvatar" class="pins-avatar" alt="avatar"
                            @mouseenter="onAuthorHover(pins.userId, $event)"
                            @mouseleave="onAuthorLeave">
                        <div class="pins-content-area">
                            <div class="pins-header">
                                <span class="pins-user" @mouseenter="onAuthorHover(pins.userId, $event)" @mouseleave="onAuthorLeave">{{ escapeHtml(pins.userName) }}</span>
                                <span class="pins-time" @mouseenter="pins.hoverTime = true" @mouseleave="pins.hoverTime = false" :class="{ 'time-hover': pins.hoverTime }" @click="goToDetail(pins)">{{ formatTime(pins.createdTime) }}</span>
                            </div>
                            <div class="pins-text">{{ escapeHtml(pins.content) }}</div>

                            <!-- 图片 -->
                            <div class="pins-images" v-if="pins.imageUrls && pins.imageUrls.length > 0">
                                <img 
                                    :src="img" 
                                    class="pins-content-image" 
                                    :class="'img-count-' + Math.min(pins.imageUrls.length, 3)"
                                    v-for="(img, idx) in pins.imageUrls" 
                                    :key="idx"
                                    alt="image"
                                >
                            </div>

                            <!-- 链接卡片 -->
                            <div class="pins-link-card" v-if="pins.linkUrl" @click="openLink(pins.linkUrl)">
                                <div class="link-card-content">
                                    <span class="link-card-domain">{{ escapeHtml(pins.linkTitle || pins.linkUrl) }}</span>
                                </div>
                            </div>

                            <div class="pins-tags">
                                <span 
                                    class="pins-circle" 
                                    v-if="pins.circleId"
                                    @click="goToCircle(pins)"
                                >{{ escapeHtml(pins.circleName) }}</span>
                                <span 
                                    class="pins-topic" 
                                    v-for="(tag, idx) in pins.topicTags" 
                                    :key="idx"
                                    @click="goToTopic(pins)"
                                >{{ escapeHtml(tag) }}</span>
                            </div>
                            <div class="pins-actions">
                                <button 
                                    class="pins-action-btn"
                                    @click="sharePins(pins)"
                                >
                                    <span class="action-icon">&#xf1e0;</span>
                                    <span>{{ pins.shareCount || 0 }}</span>
                                </button>
                                <button 
                                    class="pins-action-btn"
                                    @click="toggleComments(pins)"
                                >
                                    <span class="action-icon">&#xf075;</span>
                                    <span>{{ pins.commentCount || 0 }}</span>
                                </button>
                                <button 
                                    class="pins-action-btn"
                                    :class="{ 'active': pins.liked }"
                                    @click="toggleLike(pins)"
                                >
                                    <span class="action-icon">&#xf087;</span>
                                    <span>{{ pins.likeCount || 0 }}</span>
                                </button>
                            </div>

                            <!-- 评论区 -->
                            <div class="comments-section" v-if="pins.showComments">
                                <!-- 评论加载中 -->
                                <div class="comments-loading" v-if="pins.commentsLoading">
                                    <span>加载中...</span>
                                </div>
                                <div class="comment-list" v-else>
                                    <div class="comment-item" v-for="comment in pins.comments" :key="comment.id">
                                        <img :src="comment.userAvatar || defaultAvatar" class="comment-avatar" alt="avatar">
                                        <div class="comment-content">
                                            <div class="comment-header">
                                                <span class="comment-user">{{ escapeHtml(comment.userName) }}</span>
                                                <span class="comment-time">{{ formatTime(comment.createdTime) }}</span>
                                            </div>
                                            <div class="comment-text">{{ escapeHtml(comment.content) }}</div>
                                            <div class="comment-actions">
                                                <button 
                                                    class="comment-action-btn"
                                                    :class="{ 'active': comment.liked }"
                                                    @click="toggleCommentLike(pins, comment)"
                                                >
                                                    <span class="action-icon">&#xf087;</span>
                                                    <span>{{ comment.likeCount || 0 }}</span>
                                                </button>
                                                <button 
                                                    class="comment-action-btn"
                                                    @click="replyComment(pins, comment)"
                                                >
                                                    <span class="action-icon">&#xf112;</span>
                                                    <span>回复</span>
                                                </button>
                                            </div>

                                            <!-- 二级回复 -->
                                            <div class="reply-list" v-if="comment.replies && comment.replies.length">
                                                <div class="reply-item" v-for="reply in comment.replies" :key="reply.id">
                                                    <span class="reply-user">{{ escapeHtml(reply.userName) }}</span>
                                                    <span class="reply-text">回复 {{ escapeHtml(reply.targetName) }}：{{ escapeHtml(reply.content) }}</span>
                                                </div>
                                            </div>
                                        </div>
                                    </div>
                                </div>

                                <!-- 评论输入框 -->
                                <div class="comment-input-area">
                                    <textarea
                                        class="comment-input"
                                        :placeholder="replyingComment ? '回复 ' + escapeHtml(replyingComment.userName) : '平等表达，友善交流'"
                                        v-model="commentInput"
                                        maxlength="1000"
                                        @keydown.ctrl.enter="submitComment(pins)"
                                    ></textarea>
                                    <div class="comment-toolbar">
                                        <div class="comment-tool-actions">
                                            <button class="comment-tool-btn" @click="toggleCommentEmoji" title="表情">
                                                <span>&#xf118;</span>
                                            </button>
                                            <button class="comment-tool-btn" @click="triggerCommentImage" title="图片">
                                                <span>&#xf03e;</span>
                                            </button>
                                            <input
                                                type="file"
                                                ref="commentImageInput"
                                                accept="image/*"
                                                style="display:none"
                                                @change="handleCommentImage"
                                            >
                                            <span class="comment-count">{{ commentInput.length }}/1000</span>
                                        </div>
                                        <button
                                            class="comment-submit-btn"
                                            :disabled="!commentInput.trim()"
                                            @click="submitComment(pins)"
                                        >发送</button>
                                    </div>
                                    <!-- 表情弹窗 -->
                                    <div class="comment-emoji-picker" v-if="commentEmojiPicker">
                                        <span
                                            class="comment-emoji-item"
                                            v-for="emoji in commentEmojiList"
                                            :key="emoji"
                                            @click="insertCommentEmoji(emoji)"
                                        >{{ emoji }}</span>
                                    </div>
                                </div>
                            </div>
                        </div>
                    </div>

                    <!-- 加载更多 -->
                    <div class="pins-loading" v-if="pinsLoading">
                        <span>加载中...</span>
                    </div>
                    <div class="pins-no-more" v-if="!hasMore && pinsList.length > 0">
                        <span>没有更多了</span>
                    </div>
                </div>
            </div>

            <!-- 右侧边栏 -->
            <div class="pins-right-sidebar">
                <!-- 用户信息卡片 -->
                <div class="right-section user-card">
                    <div class="user-card-header">
                        <img :src="userInfo.avatar || defaultAvatar" class="user-card-avatar" alt="avatar">
                        <span class="user-card-nickname">{{ escapeHtml(userInfo.nickName || '未登录') }}</span>
                    </div>
                    <div class="user-card-stats">
                        <div class="user-stat-item">
                            <span class="user-stat-num">{{ sidebarData.pinsCount || 0 }}</span>
                            <span class="user-stat-label">沸点数</span>
                        </div>
                        <div class="user-stat-item">
                            <span class="user-stat-num">{{ sidebarData.circleCount || 0 }}</span>
                            <span class="user-stat-label">圈子数</span>
                        </div>
                        <div class="user-stat-item">
                            <span class="user-stat-num">{{ sidebarData.followingCount || 0 }}</span>
                            <span class="user-stat-label">关注数</span>
                        </div>
                        <div class="user-stat-item">
                            <span class="user-stat-num">{{ sidebarData.followersCount || 0 }}</span>
                            <span class="user-stat-label">关注者数</span>
                        </div>
                    </div>
                </div>

                <!-- 精选沸点 -->
                <div class="right-section featured-section">
                    <div class="right-section-title">精选沸点</div>
                    <div 
                        class="featured-item"
                        v-for="pins in sidebarData.featuredPins || []"
                        :key="pins.id"
                    >
                        <img :src="pins.userAvatar || defaultAvatar" class="featured-avatar" alt="avatar">
                        <div class="featured-info">
                            <span class="featured-name">{{ escapeHtml(pins.userName) }}</span>
                            <span class="featured-content">{{ escapeHtml(pins.content) }}</span>
                        </div>
                    </div>
                    <div class="featured-empty" v-if="!sidebarData.featuredPins || sidebarData.featuredPins.length === 0">
                        <span>暂无精选沸点</span>
                    </div>
                </div>

                <!-- 推荐话题（公共组件） -->
                <div class="right-section">
                    <recommend-topics />
                </div>
            </div>
        </div>

        <!-- 圈子选择弹窗 -->
        <div class="modal-overlay" v-if="showCircleSelector" @click="showCircleSelector = false">
            <div class="circle-modal" @click.stop>
                <div class="modal-header">
                    <span class="modal-title">选择圈子</span>
                    <button class="modal-close" @click="showCircleSelector = false">&#xf00d;</button>
                </div>
                <div class="circle-search">
                    <input type="text" class="search-input" placeholder="搜索圈子名称" v-model="circleSearchKeyword">
                </div>
                <div class="circle-modal-body">
                    <div class="circle-categories">
                        <div 
                            class="category-item"
                            :class="{ 'active': circleCategory === 'recommend' }"
                            @click="circleCategory = 'recommend'; circleSearchKeyword = ''"
                        >推荐圈子</div>
                        <div 
                            class="category-item"
                            :class="{ 'active': circleCategory === 'my' }"
                            @click="circleCategory = 'my'; circleSearchKeyword = ''"
                        >我的圈子</div>
                        <div 
                            class="category-item"
                            v-for="cat in modalCategories"
                            :key="cat.id"
                            :class="{ 'active': circleCategory === 'cat_' + cat.id }"
                            @click="circleCategory = 'cat_' + cat.id; circleSearchKeyword = ''"
                        >{{ escapeHtml(cat.name) }}</div>
                    </div>
                    <div class="circle-list">
                        <div 
                            class="circle-card"
                            v-for="circle in modalFilteredCircles"
                            :key="circle.id"
                            :class="{ 'selected': tempSelectedCircle && tempSelectedCircle.id === circle.id }"
                            @click="selectCircleFromModal(circle)"
                        >
                            <div class="circle-icon">{{ circle.icon || '📌' }}</div>
                            <div class="circle-info">
                                <div class="circle-name">{{ escapeHtml(circle.name) }}</div>
                                <div class="circle-stats">{{ circle.memberCount || 0 }} 掘友 · {{ circle.pinsCount || 0 }} 沸点</div>
                            </div>
                            <div class="circle-check" v-if="tempSelectedCircle && tempSelectedCircle.id === circle.id">&#xf00c;</div>
                        </div>
                        <div class="circle-empty" v-if="modalFilteredCircles.length === 0">
                            <span>暂无圈子</span>
                        </div>
                    </div>
                </div>
                <div class="modal-footer">
                    <button class="cancel-btn" @click="handleCircleCancel">不选择圈子</button>
                    <button class="confirm-btn" @click="confirmCircleSelection">确认</button>
                </div>
            </div>
        </div>

        <!-- 话题选择弹窗 -->
        <div class="modal-overlay" v-if="showTopicSelector" @click="showTopicSelector = false">
            <div class="topic-modal" @click.stop>
                <div class="modal-header">
                    <span class="modal-title">选择话题</span>
                    <button class="modal-close" @click="showTopicSelector = false">&#xf00d;</button>
                </div>
                <div class="topic-search">
                    <input type="text" class="search-input" placeholder="搜索话题名称" v-model="topicSearchKeyword" @input="onTopicSearchInput">
                </div>
                <div class="topic-list">
                    <div 
                        class="topic-item"
                        v-for="topic in topicList"
                        :key="topic.id"
                        :class="{ 'selected': selectedTopic && selectedTopic.id === topic.id }"
                        @click="selectTopic(topic)"
                    >
                        <span class="topic-name">#{{ escapeHtml(topic.name) }}#</span>
                        <span class="topic-count">{{ topic.count || 0 }} 沸点</span>
                    </div>
                    <div class="topic-empty" v-if="topicList.length === 0 && !topicLoading">
                        <span>暂无话题</span>
                    </div>
                    <div class="topic-loading" v-if="topicLoading">
                        <span>加载中...</span>
                    </div>
                </div>
            </div>
        </div>

        <!-- 我的圈子更多弹窗 -->
        <div class="modal-overlay" v-if="showMyCirclesModal" @click="showMyCirclesModal = false">
            <div class="mycircles-modal" @click.stop>
                <div class="modal-header">
                    <span class="modal-title">我的圈子</span>
                    <button class="modal-close" @click="showMyCirclesModal = false">&#xf00d;</button>
                </div>
                <div class="mycircles-list">
                    <div 
                        class="mycircles-item"
                        v-for="circle in myCircles"
                        :key="circle.id"
                        :class="{ 'active': activeCircle === circle.id }"
                        @click="selectCircleFromMyCircles(circle)"
                    >
                        <span class="mycircles-name">{{ escapeHtml(circle.name) }}</span>
                        <span class="mycircles-arrow" v-if="activeCircle === circle.id">&#xf0da;</span>
                    </div>
                </div>
            </div>
        </div>

        <!-- 作者信息悬浮卡片 -->
        <AuthorHoverCard
            :visible="showAuthorCard"
            :userId="authorCardUserId"
            :position="authorCardPosition"
            @close="showAuthorCard = false"
            @follow="onAuthorFollow"
            @message="onAuthorMessage"
        />
    </div>
</template>

<script>
import HomeBar from '@/components/bars/home_bar'
import Utils from '@/utils/env'
const defaultAvatar = 'data:image/svg+xml,%3Csvg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100"%3E%3Ccircle cx="50" cy="50" r="50" fill="%23ddd"/%3E%3C/svg%3E'
import { toast } from '@/utils/toast'
import { getMyCircles, getRecommendCircles } from '@/apis/circle'
import {
    getPinsList,
    getSidebar,
    publishPins as publishPinsApi,
    likePins,
    createComment,
    getComments,
    getTopics,
    getAllCircles
} from '@/apis/pins'
import PinsPublishBox from './components/PinsPublishBox.vue'
import RecommendTopics from '@/components/RecommendTopics.vue'
import AuthorHoverCard from '@/components/search/AuthorHoverCard.vue'
import { uploadFile } from '@/common/oss_upload'
import { followUser } from '@/apis/follow'

export default {
    name: 'Pins',
    components: { HomeBar, PinsPublishBox, RecommendTopics, AuthorHoverCard },
    data() {
        return {
            activeTab: 'latest',
            activeCircle: null,
            selectedCircle: null,
            tempSelectedCircle: null,
            selectedTopic: null,
            publishContent: '',
            commentInput: '',
            replyingComment: null,
            commentEmojiPicker: false,
            commentEmojiList: [
                '😀', '😃', '😄', '😁', '😅', '😂', '🤣', '😊', '😇', '🙂',
                '😉', '😍', '🥰', '😘', '😋', '😛', '😜', '🤪', '😝', '🤑',
                '🤗', '🤭', '🤔', '🤐', '🤨', '😐', '😏', '😒', '🙄', '😬',
                '😭', '😤', '😡', '🤯', '😴', '👍', '👎', '👏', '🙌', '💪',
                '🎉', '🎊', '✨', '🌟', '🔥', '💯', '❤️', '💙', '💚', '💜'
            ],
            circleSearchKeyword: '',
            topicSearchKeyword: '',
            topicSearchTimer: null,
            showCircleSelector: false,
            showTopicSelector: false,
            showMyCirclesModal: false,
            publishing: false,
            scrollThrottling: false,
            // 定时刷新定时器句柄
            refreshTimer: null,
            
            // 圈子分类
            categories: [],
            allCircles: [],
            circleCategory: 'recommend',
            
            // 我的圈子
            myCircles: [],
            
            // 推荐圈子
            recommendedCircles: [],
            
            // 话题列表
            topicList: [],
            topicPage: 1,
            topicTotal: 0,
            topicLoading: false,
            
            // 沸点帖子列表
            pinsList: [],
            pinsPage: 1,
            pinsSize: 10,
            pinsLoading: false,
            hasMore: true,
            noMore: false,
            // 沸点列表加载失败标记（503/超时等，用于区分"暂无内容"与"加载失败"）
            pinsError: false,

            // 作者信息悬浮卡片
            showAuthorCard: false,
            authorCardUserId: null,
            authorCardPosition: { top: 0, left: 0 },
            authorCardTimer: null,
            
            // 右侧边栏
            sidebarData: {
                pinsCount: 0,
                circleCount: 0,
                followingCount: 0,
                followersCount: 0,
                featuredPins: []
            }
        }
    },
    computed: {
        isDesktop() {
            return Utils.isDesktop()
        },
        defaultAvatar() {
            return defaultAvatar
        },
        userInfo() {
            return this.$store.state.userInfo || {}
        },
        // 圈子分类去重：过滤掉接口返回的"推荐圈子"，避免与硬编码的"推荐圈子"重复
        modalCategories() {
            return (this.categories || []).filter(cat => cat.name !== '推荐圈子')
        },
        modalFilteredCircles() {
            let result = []
            if (this.circleCategory === 'recommend') {
                result = this.recommendedCircles
            } else if (this.circleCategory === 'my') {
                result = this.myCircles
            } else if (this.circleCategory.startsWith('cat_')) {
                const catId = parseInt(this.circleCategory.replace('cat_', ''))
                const cat = this.categories.find(c => c.id === catId)
                if (cat && cat.circles) {
                    result = cat.circles
                }
            }
            if (this.circleSearchKeyword) {
                const keyword = this.circleSearchKeyword.toLowerCase()
                result = result.filter(c => c.name && c.name.toLowerCase().includes(keyword))
            }
            return result
        }
    },
    watch: {
        showCircleSelector(newVal, oldVal) {
            if (newVal && !oldVal) {
                // 打开弹窗时同步待选状态，便于回显当前已选圈子
                this.tempSelectedCircle = this.selectedCircle
                this.fetchRecommendCircles()
                this.fetchMyCircles()
                this.fetchAllCircles()
            }
        },
        showTopicSelector(newVal, oldVal) {
            if (newVal && !oldVal) {
                this.fetchTopics('')
            }
        }
    },
    mounted() {
        this.init()
        this.startRefreshTimer()
        window.addEventListener('scroll', this.handleScroll)
    },
    beforeDestroy() {
        this.stopRefreshTimer()
        window.removeEventListener('scroll', this.handleScroll)
    },
    methods: {
        init() {
            this.fetchMyCircles()
            this.fetchRecommendCircles()
            this.fetchAllCircles()
            this.fetchSidebar()
            this.fetchPinsList(true)
        },
        escapeHtml(str) {
            if (!str) return ''
            return str.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;').replace(/'/g, '&#039;')
        },
        formatTime(timestamp) {
            if (!timestamp) return ''
            const now = Date.now()
            const t = typeof timestamp === 'string' ? new Date(timestamp).getTime() : timestamp
            const diff = now - t
            const hours = Math.floor(diff / 3600000)
            const days = Math.floor(diff / 86400000)
            const months = Math.floor(diff / 2592000000)
            
            if (hours < 1) return '刚刚'
            if (hours < 24) return hours + '小时前'
            if (days < 30) return days + '天前'
            if (months < 12) return months + '个月前'
            return Math.floor(months / 12) + '年前'
        },

        // ============== 左侧边栏 ==============
        async fetchMyCircles() {
            try {
                const res = await getMyCircles()
                if (res && res.code === 200 && res.data) {
                    this.myCircles = res.data
                }
            } catch (e) {
                toast('加载我的圈子失败', 2)
            }
        },
        async fetchRecommendCircles() {
            try {
                const res = await getRecommendCircles()
                if (res && res.code === 200 && res.data) {
                    this.recommendedCircles = res.data
                }
            } catch (e) {
                toast('加载推荐圈子失败', 2)
            }
        },
        async fetchAllCircles() {
            try {
                const res = await getAllCircles()
                if (res && res.code === 200 && res.data) {
                    this.categories = res.data || []
                    const all = []
                    ;(res.data || []).forEach(cat => {
                        if (cat.circles) {
                            cat.circles.forEach(c => {
                                all.push(c)
                            })
                        }
                    })
                    this.allCircles = all
                }
            } catch (e) {
                toast('加载圈子分类失败', 2)
            }
        },
        selectCircle(circle) {
            window.open('/pins/circle/' + circle.id, '_blank')
        },
        selectCircleFromMyCircles(circle) {
            this.activeCircle = circle.id
            this.showMyCirclesModal = false
        },

        // ============== 右侧边栏 ==============
        async fetchSidebar() {
            try {
                const res = await getSidebar()
                if (res && res.code === 200 && res.data) {
                    this.sidebarData = res.data
                }
            } catch (e) {
                toast('加载侧边栏数据失败', 2)
            }
        },

        // ============== 沸点列表 ==============
        async fetchPinsList(reset) {
            if (this.pinsLoading) return
            if (reset) {
                this.pinsPage = 1
                this.pinsList = []
                this.hasMore = true
                this.noMore = false
            }
            if (!this.hasMore) return
            
            this.pinsLoading = true
            try {
                const params = {
                    tab: this.activeTab,
                    page: this.pinsPage,
                    size: this.pinsSize
                }
                const res = await getPinsList(params)
                if (res && res.code === 200 && res.data) {
                    this.pinsError = false
                    const list = res.data.list || res.data || []
                    const total = res.data.total || 0
                    if (reset) {
                        this.pinsList = list
                    } else {
                        this.pinsList = this.pinsList.concat(list)
                    }
                    this.pinsPage++
                    if (this.pinsList.length >= total || list.length < this.pinsSize) {
                        this.hasMore = false
                        this.noMore = true
                    }
                } else {
                    // 业务错误（如 503/接口异常返回）
                    if (reset) this.pinsError = true
                }
            } catch (e) {
                if (reset) {
                    this.pinsList = []
                    this.pinsError = true
                }
            } finally {
                this.pinsLoading = false
            }
        },
        goToDetail(pins) {
            if (pins && pins.id) {
                this.$router.push('/pins/detail/' + pins.id)
            }
        },
        // 点击圈子标签 -> 跳转圈子详情页
        goToCircle(pins) {
            if (pins && pins.circleId) {
                this.$router.push('/pins/circle/' + pins.circleId)
            }
        },
        // 点击话题标签 -> 跳转话题详情页
        goToTopic(pins) {
            if (pins && pins.topicId) {
                this.$router.push('/pin/topic/' + pins.topicId)
            }
        },

        // ============== 定时刷新 ==============
        // 开启定时刷新，周期性拉取最新沸点并插入列表顶部（不打断滚动加载）
        startRefreshTimer() {
            if (this.refreshTimer) return
            this.refreshTimer = setInterval(() => {
                this.refreshPins()
            }, 15000)
        },
        stopRefreshTimer() {
            if (this.refreshTimer) {
                clearInterval(this.refreshTimer)
                this.refreshTimer = null
            }
        },
        // 静默刷新：仅对“最新”分栏生效，将新出现的沸点插入列表顶部，已存在的不重复
        async refreshPins() {
            if (this.activeTab !== 'latest') return
            if (this.pinsLoading) return
            try {
                const res = await getPinsList({ tab: 'latest', page: 1, size: this.pinsSize })
                if (res && res.code === 200 && res.data) {
                    const list = res.data.list || []
                    const existingIds = new Set(this.pinsList.map(p => String(p.id)))
                    const newItems = list.filter(p => !existingIds.has(String(p.id)))
                    if (newItems.length > 0) {
                        this.pinsList = [...newItems, ...this.pinsList]
                        const total = res.data.total || 0
                        if (this.pinsList.length >= total) {
                            this.hasMore = false
                            this.noMore = true
                        }
                    }
                }
            } catch (e) {
                // 静默失败，不打断用户操作
            }
        },
        switchTab(tab) {
            if (this.activeTab === tab) return
            this.activeTab = tab
            this.selectedCircle = null
            this.activeCircle = null
            this.fetchPinsList(true)
        },
        handleScroll() {
            if (this.scrollThrottling) return
            this.scrollThrottling = true
            setTimeout(() => { this.scrollThrottling = false }, 200)
            const scrollTop = window.pageYOffset || document.documentElement.scrollTop
            const windowHeight = window.innerHeight
            const documentHeight = document.documentElement.scrollHeight
            if (scrollTop + windowHeight >= documentHeight - 200) {
                this.fetchPinsList(false)
            }
        },

        // ============== 发布框 - 话题 ==============
        async fetchTopics(keyword) {
            this.topicLoading = true
            try {
                const params = {
                    keyword: keyword || '',
                    page: this.topicPage,
                    size: 20
                }
                const res = await getTopics(params)
                if (res && res.code === 200 && res.data) {
                    this.topicList = res.data.list || []
                    this.topicTotal = res.data.total || 0
                }
            } catch (e) {
                this.topicList = []
            } finally {
                this.topicLoading = false
            }
        },
        onTopicSearchInput() {
            if (this.topicSearchTimer) {
                clearTimeout(this.topicSearchTimer)
            }
            this.topicSearchTimer = setTimeout(() => {
                this.topicPage = 1
                this.fetchTopics(this.topicSearchKeyword)
            }, 300)
        },
        selectTopic(topic) {
            this.selectedTopic = topic
            this.showTopicSelector = false
        },

        // ============== 发布框 - 圈子 ==============
        selectCircleFromModal(circle) {
            this.tempSelectedCircle = circle
        },
        // 不选择圈子：清除选中状态并关闭弹窗
        handleCircleCancel() {
            this.tempSelectedCircle = null
            this.selectedCircle = null
            this.showCircleSelector = false
        },
        confirmCircleSelection() {
            if (this.tempSelectedCircle) {
                this.selectedCircle = this.tempSelectedCircle
            }
            this.showCircleSelector = false
        },

        // ============== 发布 ==============
        async handlePublish(data) {
            if (this.publishing) return
            this.publishing = true
            try {
                const res = await publishPinsApi(data)
                if (res && res.code === 200) {
                    toast('发布成功！', 2)
                    this.publishContent = ''
                    this.selectedCircle = null
                    this.tempSelectedCircle = null
                    this.selectedTopic = null
                    this.$refs.publishBox && this.$refs.publishBox.reset()
                    // 沸点为异步审核，审核通过后由 15s 轮询自动查出并展示
                    this.refreshPins()
                } else {
                    toast((res && res.message) || '发布失败', 2)
                }
            } catch (e) {
                toast('发布失败，请重试', 2)
            } finally {
                this.publishing = false
            }
        },

        // ============== 沸点交互 ==============
        async toggleLike(pins) {
            const newLiked = !pins.liked
            try {
                const res = await likePins({ pinsId: pins.id, liked: newLiked })
                if (res && res.code === 200) {
                    pins.liked = newLiked
                    pins.likeCount = (pins.likeCount || 0) + (newLiked ? 1 : -1)
                    if (pins.likeCount < 0) pins.likeCount = 0
                }
            } catch (e) {
                toast('点赞失败', 2)
            }
        },
        async toggleComments(pins) {
            if (pins.showComments) {
                this.$set(pins, 'showComments', false)
                return
            }
            this.$set(pins, 'showComments', true)
            this.$set(pins, 'commentsLoading', true)
            this.$set(pins, 'comments', pins.comments || [])
            this.replyingComment = null
            this.commentInput = ''
            try {
                const res = await getComments({ pinsId: pins.id, page: 1, size: 10 })
                if (res && res.code === 200 && res.data) {
                    this.$set(pins, 'comments', (res.data.list || res.data || []).map(c => ({
                        ...c,
                        liked: c.liked || false,
                        replies: c.replies || []
                    })))
                }
            } catch (e) {
                this.$set(pins, 'comments', [])
            } finally {
                this.$set(pins, 'commentsLoading', false)
            }
        },
        async submitComment(pins) {
            if (!this.commentInput.trim()) return
            const content = this.commentInput.trim()
            try {
                const data = {
                    pinsId: pins.id,
                    content: content
                }
                if (this.replyingComment) {
                    data.parentId = this.replyingComment.id
                }
                const res = await createComment(data)
                if (res && res.code === 200) {
                    if (this.replyingComment) {
                        // 回复成功，刷新评论列表
                        this.replyingComment = null
                        this.commentInput = ''
                        await this.toggleComments(pins)
                        this.$set(pins, 'showComments', true)
                    } else {
                        // 新评论：清除输入并重新拉取评论列表
                        this.commentInput = ''
                        this.$set(pins, 'commentsLoading', true)
                        try {
                            const refreshRes = await getComments({ pinsId: pins.id, page: 1, size: 10 })
                            if (refreshRes && refreshRes.code === 200 && refreshRes.data) {
                                this.$set(pins, 'comments', (refreshRes.data.list || refreshRes.data || []).map(c => ({
                                    ...c,
                                    liked: c.liked || false,
                                    replies: c.replies || []
                                })))
                            }
                            this.$set(pins, 'commentCount', (pins.commentCount || 0) + 1)
                        } finally {
                            this.$set(pins, 'commentsLoading', false)
                        }
                    }
                } else {
                    toast((res && res.message) || '评论失败', 2)
                }
            } catch (e) {
                toast('评论失败，请重试', 2)
            }
        },
        replyComment(pins, comment) {
            this.replyingComment = comment
        },
        toggleCommentEmoji() {
            this.commentEmojiPicker = !this.commentEmojiPicker
        },
        insertCommentEmoji(emoji) {
            this.commentInput += emoji
            this.commentEmojiPicker = false
        },
        triggerCommentImage() {
            this.$refs.commentImageInput.click()
        },
        async handleCommentImage(e) {
            const file = e.target.files[0]
            if (!file) return
            try {
                const url = await uploadFile(file)
                // 将图片地址以 URL 形式插入评论内容
                this.commentInput += (this.commentInput ? ' ' : '') + url
            } catch (err) {
                toast('图片上传失败', 2)
            } finally {
                this.$refs.commentImageInput.value = ''
            }
        },
        async toggleCommentLike(pins, comment) {
            const newLiked = !comment.liked
            try {
                const res = await likePins({ pinsId: comment.id, liked: newLiked })
                if (res && res.code === 200) {
                    comment.liked = newLiked
                    comment.likeCount = (comment.likeCount || 0) + (newLiked ? 1 : -1)
                    if (comment.likeCount < 0) comment.likeCount = 0
                }
            } catch (e) {
                toast('评论点赞失败', 2)
            }
        },
        async sharePins(pins) {
            toast('分享功能正在努力开发中~', 2)
        },
        openLink(url) {
            if (url) {
                window.open(url, '_blank')
            }
        },
        goToCircles() {
            this.$router.push('/pins/circles')
        },

        // ============== 作者信息悬浮卡片 ==============
        onAuthorHover(userId, event) {
            if (!userId) return
            if (this.authorCardTimer) {
                clearTimeout(this.authorCardTimer)
                this.authorCardTimer = null
            }
            this.authorCardUserId = userId
            var rect = event.target.getBoundingClientRect()
            var cardTop = rect.bottom + 8
            var cardLeft = rect.left
            if (cardLeft + 240 > window.innerWidth) {
                cardLeft = window.innerWidth - 250
            }
            this.authorCardPosition = {
                top: cardTop,
                left: cardLeft,
                arrow: 'top'
            }
            this.showAuthorCard = true
        },
        onAuthorLeave() {
            var self = this
            if (this.authorCardTimer) {
                clearTimeout(this.authorCardTimer)
            }
            this.authorCardTimer = setTimeout(function () {
                self.showAuthorCard = false
            }, 300)
        },
        async onAuthorFollow(userId) {
            var currentUserId = this.userInfo && this.userInfo.userId
            if (!currentUserId) {
                toast('请先登录')
                this.$store.dispatch('showLogin')
                return
            }
            try {
                const res = await followUser(currentUserId, userId)
                if (res && res.code === 200) {
                    toast('操作成功', 2)
                } else {
                    toast((res && res.message) || '操作失败', 2)
                }
            } catch (e) {
                toast('操作失败，请重试', 2)
            }
        },
        onAuthorMessage(payload) {
            this.showAuthorCard = false
            if (!payload || !payload.userId) return
            this.$router.push({
                path: '/notification',
                query: {
                    tab: 'message',
                    peer_id: payload.userId,
                    peer_name: payload.name || '',
                    peer_avatar: payload.avatar || ''
                }
            })
        }
    }
}
</script>

<style lang="less" scoped>
@import '../../styles/common';

.pins-page {
    min-height: 100vh;
    background: #f7f8fa;
    
    &.is-desktop {
        background: transparent;
        min-height: auto;
        
        .pins-content {
            max-width: none;
            margin: 0;
            padding: 0;
        }
    }
}

.pins-content {
    max-width: 1200px;
    margin: 0 auto;
    padding: 24px;
    display: flex;
    gap: 24px;
}

/* 左侧边栏 */
.pins-sidebar {
    width: 200px;
    flex-shrink: 0;
}

.sidebar-section {
    background: #fff;
    border-radius: 8px;
    padding: 12px 0;
    margin-bottom: 16px;
    box-shadow: 0 1px 2px rgba(0,0,0,0.05);
}

.section-title {
    padding: 8px 16px;
    font-size: 12px;
    color: #8a919f;
    font-weight: 500;
}

.sidebar-item {
    display: flex;
    align-items: center;
    gap: 12px;
    padding: 10px 16px;
    cursor: pointer;
    transition: background-color 0.2s;
    &:hover {
        background: #f7f8fa;
    }
    &.active {
        background: #eaf2ff;
        .sidebar-text {
            color: #1e80ff;
        }
    }
}

.sidebar-icon {
    font-family: fontawesome;
    font-size: 16px;
    color: #8a919f;
}

.sidebar-text {
    font-size: 14px;
    color: #515767;
}

.circle-item {
    padding-left: 32px;
}

.more-item {
    padding-left: 32px;
    .sidebar-text {
        color: #1e80ff;
    }
}

/* 右侧主内容区 */
.pins-main {
    flex: 1;
    min-width: 0;
}

.publish-box-wrapper {
    margin-bottom: 16px;
}

/* 帖子列表 */
.pins-list {
    background: #fff;
    border-radius: 8px;
    padding: 8px 0;
    box-shadow: 0 1px 2px rgba(0,0,0,0.05);
}

.pins-empty {
    text-align: center;
    padding: 60px 20px;
    color: #8a919f;
    font-size: 14px;
}

.pins-retry-btn {
    display: inline-block;
    margin-top: 12px;
    padding: 6px 20px;
    border-radius: 16px;
    border: 1px solid #1E80FF;
    color: #1E80FF;
    font-size: 13px;
    cursor: pointer;
    transition: all 0.2s;
    user-select: none;
}
.pins-retry-btn:hover {
    background: rgba(30, 128, 255, 0.08);
}

.pins-item {
    display: flex;
    gap: 12px;
    padding: 16px;
    border-bottom: 1px solid #f2f3f5;
    &:last-child {
        border: none;
    }
}

.pins-avatar {
    width: 48px;
    height: 48px;
    border-radius: 50%;
    object-fit: cover;
    flex-shrink: 0;
    cursor: pointer;
}

.pins-content-area {
    flex: 1;
    min-width: 0;
}

.pins-header {
    display: flex;
    align-items: center;
    gap: 8px;
    margin-bottom: 8px;
}

.pins-user {
    font-size: 14px;
    font-weight: 500;
    color: #252933;
}

.pins-time {
    font-size: 12px;
    color: #8a919f;
    cursor: pointer;
    transition: color 0.2s;
    &.time-hover {
        color: #1e80ff;
        text-decoration: underline;
    }
}

.pins-text {
    font-size: 14px;
    color: #252933;
    line-height: 1.6;
    margin-bottom: 8px;
    word-break: break-word;
}

/* 内容图片 */
.pins-images {
    display: flex;
    gap: 8px;
    flex-wrap: wrap;
    margin-bottom: 8px;
}

.pins-content-image {
    border-radius: 6px;
    object-fit: cover;
    &.img-count-1 {
        max-width: 280px;
        max-height: 200px;
        width: auto;
        height: auto;
    }
    &.img-count-2 {
        width: 140px;
        height: 140px;
    }
    &.img-count-3 {
        width: 100px;
        height: 100px;
    }
}

/* 链接卡片 */
.pins-link-card {
    margin-bottom: 8px;
    padding: 10px 14px;
    background: #f7f8fa;
    border-radius: 6px;
    cursor: pointer;
    &:hover {
        background: #eaf2ff;
    }
}

.link-card-content {
    display: flex;
    align-items: center;
}

.link-card-domain {
    font-size: 13px;
    color: #1e80ff;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
}

.pins-tags {
    display: flex;
    align-items: center;
    gap: 8px;
    margin-bottom: 12px;
    flex-wrap: wrap;
}

.pins-circle {
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

.pins-topic {
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

.pins-actions {
    display: flex;
    align-items: center;
    gap: 24px;
}

.pins-action-btn {
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

/* 加载更多 */
.pins-loading, .pins-no-more {
    text-align: center;
    padding: 16px;
    color: #8a919f;
    font-size: 13px;
}

/* 评论区 */
.comments-section {
    margin-top: 16px;
    padding-top: 16px;
    border-top: 1px solid #f2f3f5;
}

.comments-loading {
    text-align: center;
    padding: 12px;
    color: #8a919f;
    font-size: 13px;
}

.comment-list {
    margin-bottom: 16px;
}

.comment-item {
    display: flex;
    gap: 12px;
    padding: 12px 0;
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
    padding-left: 24px;
    border-left: 2px solid #e4e6eb;
}

.reply-item {
    padding: 6px 0;
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

/* 评论输入框 */
.comment-input-area {
    position: relative;
}

.comment-input {
    width: 100%;
    padding: 10px 14px;
    border: 1px solid #e4e6eb;
    border-radius: 8px;
    font-size: 14px;
    line-height: 1.6;
    outline: none;
    resize: none;
    box-sizing: border-box;
    &:focus {
        border-color: #1e80ff;
    }
}

.comment-toolbar {
    display: flex;
    align-items: center;
    justify-content: space-between;
    margin-top: 8px;
}

.comment-tool-actions {
    display: flex;
    align-items: center;
    gap: 8px;
}

.comment-tool-btn {
    display: flex;
    align-items: center;
    justify-content: center;
    width: 28px;
    height: 28px;
    border: none;
    background: transparent;
    font-family: fontawesome;
    font-size: 16px;
    color: #8a919f;
    cursor: pointer;
    border-radius: 4px;
    &:hover {
        color: #1e80ff;
        background: #f0f5ff;
    }
}

.comment-count {
    font-size: 12px;
    color: #c4c9d1;
    margin-left: 4px;
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

/* 评论表情弹窗 */
.comment-emoji-picker {
    position: absolute;
    top: 100%;
    left: 0;
    margin-top: 6px;
    background: #fff;
    border: 1px solid #e4e6eb;
    border-radius: 8px;
    padding: 8px;
    box-shadow: 0 4px 16px rgba(0,0,0,0.12);
    z-index: 100;
    display: flex;
    flex-wrap: wrap;
    gap: 4px;
    width: 260px;
}

.comment-emoji-item {
    width: 30px;
    height: 30px;
    display: flex;
    align-items: center;
    justify-content: center;
    font-size: 18px;
    cursor: pointer;
    border-radius: 4px;
    &:hover {
        background: #f0f5ff;
    }
}

/* 右侧边栏 */
.pins-right-sidebar {
    width: 260px;
    flex-shrink: 0;
}

.right-section {
    background: #fff;
    border-radius: 8px;
    padding: 16px;
    margin-bottom: 16px;
    box-shadow: 0 1px 2px rgba(0,0,0,0.05);
}

.right-section-title {
    font-size: 14px;
    font-weight: 600;
    color: #252933;
    margin-bottom: 12px;
    display: flex;
    align-items: center;
    justify-content: space-between;
}

/* 用户信息卡片 */
.user-card-header {
    display: flex;
    align-items: center;
    gap: 10px;
    margin-bottom: 16px;
}

.user-card-avatar {
    width: 48px;
    height: 48px;
    border-radius: 50%;
    object-fit: cover;
}

.user-card-nickname {
    font-size: 15px;
    font-weight: 600;
    color: #252933;
}

.user-card-stats {
    display: flex;
    gap: 0;
}

.user-stat-item {
    flex: 1;
    text-align: center;
}

.user-stat-num {
    display: block;
    font-size: 16px;
    font-weight: 600;
    color: #252933;
}

.user-stat-label {
    display: block;
    font-size: 12px;
    color: #8a919f;
    margin-top: 2px;
}

/* 精选沸点 */
.featured-item {
    display: flex;
    gap: 8px;
    padding: 8px 0;
    border-bottom: 1px solid #f2f3f5;
    &:last-child {
        border: none;
    }
}

.featured-avatar {
    width: 32px;
    height: 32px;
    border-radius: 50%;
    object-fit: cover;
    flex-shrink: 0;
}

.featured-info {
    flex: 1;
    min-width: 0;
}

.featured-name {
    display: block;
    font-size: 13px;
    color: #252933;
    font-weight: 500;
    margin-bottom: 2px;
}

.featured-content {
    display: block;
    font-size: 12px;
    color: #8a919f;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
}

.featured-empty {
    text-align: center;
    padding: 12px;
    color: #8a919f;
    font-size: 13px;
}

/* 推荐话题 */
.topics-refresh {
    font-size: 12px;
    color: #1e80ff;
    cursor: pointer;
    font-weight: normal;
    &:hover {
        text-decoration: underline;
    }
}

.topic-tag-item {
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding: 8px 0;
    cursor: pointer;
    border-bottom: 1px solid #f2f3f5;
    &:last-child {
        border: none;
    }
    &:hover {
        .topic-tag-name {
            color: #1e80ff;
        }
    }
}

.topic-tag-name {
    font-size: 13px;
    color: #252933;
}

.topic-tag-count {
    font-size: 12px;
    color: #8a919f;
}

.topic-more {
    text-align: center;
    padding: 8px;
    font-size: 13px;
    color: #1e80ff;
    cursor: pointer;
    margin-top: 4px;
    &:hover {
        background: #f7f8fa;
        border-radius: 4px;
    }
}

/* 弹窗 */
.modal-overlay {
    position: fixed;
    top: 0;
    left: 0;
    right: 0;
    bottom: 0;
    background: rgba(0,0,0,0.5);
    display: flex;
    align-items: center;
    justify-content: center;
    z-index: 1000;
}

.circle-modal, .topic-modal, .mycircles-modal {
    background: #fff;
    border-radius: 8px;
    width: 600px;
    max-height: 70vh;
    overflow: hidden;
    display: flex;
    flex-direction: column;
}

.mycircles-modal {
    width: 400px;
}

.modal-header {
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding: 16px 20px;
    border-bottom: 1px solid #f2f3f5;
    flex-shrink: 0;
}

.modal-title {
    font-size: 16px;
    font-weight: 600;
    color: #252933;
}

.modal-close {
    width: 32px;
    height: 32px;
    border: none;
    background: transparent;
    font-family: fontawesome;
    font-size: 16px;
    color: #8a919f;
    cursor: pointer;
    border-radius: 50%;
    &:hover {
        background: #f2f3f5;
        color: #515767;
    }
}

/* 圈子选择弹窗 */
.circle-search, .topic-search {
    padding: 12px 20px;
    flex-shrink: 0;
}

.search-input {
    width: 100%;
    padding: 10px 14px;
    border: 1px solid #e4e6eb;
    border-radius: 4px;
    font-size: 14px;
    outline: none;
    &:focus {
        border-color: #1e80ff;
    }
}

.circle-modal-body {
    display: flex;
    flex: 1;
    overflow: hidden;
}

.circle-categories {
    display: flex;
    flex-direction: column;
    width: 120px;
    flex-shrink: 0;
    padding: 8px 0;
    border-right: 1px solid #f2f3f5;
    overflow-y: auto;
}

.circle-modal .category-item {
    padding: 8px 12px;
    font-size: 13px;
    color: #515767;
    cursor: pointer;
    background: transparent;
    border-radius: 0;
    &:hover {
        background: #f7f8fa;
        color: #1e80ff;
    }
    &.active {
        background: #eaf2ff;
        color: #1e80ff;
    }
}

.circle-list {
    flex: 1;
    padding: 8px 12px;
    overflow-y: auto;
}

.circle-card {
    display: flex;
    align-items: center;
    gap: 12px;
    padding: 12px;
    border-radius: 8px;
    cursor: pointer;
    transition: background-color 0.2s;
    &:hover {
        background: #f7f8fa;
    }
    &.selected {
        background: #eaf2ff;
    }
}

.circle-icon {
    font-size: 24px;
}

.circle-info {
    flex: 1;
}

.circle-name {
    font-size: 14px;
    color: #252933;
    margin-bottom: 2px;
}

.circle-stats {
    font-size: 12px;
    color: #8a919f;
}

.circle-check {
    font-family: fontawesome;
    font-size: 16px;
    color: #1e80ff;
}

.circle-empty {
    text-align: center;
    padding: 40px 20px;
    color: #8a919f;
    font-size: 14px;
}

.modal-footer {
    display: flex;
    justify-content: flex-end;
    gap: 12px;
    padding: 16px 20px;
    border-top: 1px solid #f2f3f5;
    flex-shrink: 0;
}

.cancel-btn {
    padding: 8px 24px;
    border: 1px solid #e4e6eb;
    border-radius: 4px;
    background: #fff;
    color: #515767;
    font-size: 14px;
    cursor: pointer;
    &:hover {
        background: #f7f8fa;
    }
}

.confirm-btn {
    padding: 8px 24px;
    border: none;
    border-radius: 4px;
    background: #1e80ff;
    color: #fff;
    font-size: 14px;
    cursor: pointer;
    &:hover {
        background: #4096ff;
    }
}

/* 话题选择弹窗 */
.topic-list {
    padding: 12px 20px;
    flex: 1;
    overflow-y: auto;
}

.topic-item {
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding: 10px 12px;
    border-radius: 8px;
    cursor: pointer;
    transition: background-color 0.2s;
    &:hover {
        background: #f7f8fa;
    }
    &.selected {
        background: #eaf2ff;
        .topic-name {
            color: #1e80ff;
        }
    }
}

.topic-name {
    font-size: 14px;
    color: #252933;
}

.topic-count {
    font-size: 12px;
    color: #8a919f;
}

.topic-empty, .topic-loading {
    text-align: center;
    padding: 40px 20px;
    color: #8a919f;
    font-size: 14px;
}

/* 我的圈子弹窗 */
.mycircles-list {
    padding: 8px 0;
    overflow-y: auto;
}

.mycircles-item {
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding: 12px 20px;
    cursor: pointer;
    transition: background-color 0.2s;
    &:hover {
        background: #f7f8fa;
    }
    &.active {
        background: #eaf2ff;
        .mycircles-name {
            color: #1e80ff;
        }
    }
}

.mycircles-name {
    font-size: 14px;
    color: #252933;
}

.mycircles-arrow {
    font-family: fontawesome;
    font-size: 14px;
    color: #1e80ff;
}

/* 响应式 */
@media screen and (max-width: 768px) {
    .pins-content {
        flex-direction: column;
        padding: 12px;
    }
    .pins-sidebar {
        width: 100%;
    }
    .pins-right-sidebar {
        width: 100%;
    }
    .sidebar-section {
        margin-bottom: 12px;
    }
    .circle-modal, .topic-modal {
        width: 90%;
    }
}
</style>