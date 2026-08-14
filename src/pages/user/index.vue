<template>
    <div class="user-page">
        <div class="art-top" v-if="!isDesktop"><HomeBar/></div>
        <div class="user-content">
            <div class="user-header">
                <div class="header-left">
                    <img :src="userInfo.avatar || defaultAvatar" class="user-big-avatar" alt="avatar">
                    <div class="user-meta">
                        <div class="user-name">{{ userInfo.nickName || '用户' }}</div>
                        <div class="user-level-row">
                            <span class="user-level" v-if="dailyLevelBadge">
                                {{ dailyLevelBadge.name }} Lv.{{ dailyLevelBadge.level }}<template v-if="dailyLevelBadge.levelTitle"> · {{ dailyLevelBadge.levelTitle }}</template>
                            </span>
                            <span class="user-level power" v-if="powerLevelBadge">
                                {{ powerLevelBadge.name }} Lv.{{ powerLevelBadge.level }}<template v-if="powerLevelBadge.levelTitle"> · {{ powerLevelBadge.levelTitle }}</template>
                            </span>
                        </div>
                        <div class="user-intro">{{ userInfo.intro || '这个人很懒，什么都没有留下' }}</div>
                        <div class="user-stats-row">
                            <span class="stat-item">
                                <span class="stat-num">{{ stats.likedCount }}</span>
                                <span class="stat-text">文章被点赞</span>
                            </span>
                            <span class="stat-divider">·</span>
                            <span class="stat-item">
                                <span class="stat-num">{{ stats.readCount }}</span>
                                <span class="stat-text">文章被阅读</span>
                            </span>
                            <span class="stat-divider">·</span>
                            <span class="stat-item">
                                <span class="stat-num">{{ stats.followCount }}</span>
                                <span class="stat-text">关注</span>
                            </span>
                            <span class="stat-divider">·</span>
                            <span class="stat-item">
                                <span class="stat-num">{{ stats.followerCount }}</span>
                                <span class="stat-text">关注者</span>
                            </span>
                            <span class="stat-divider">·</span>
                            <span class="stat-item badge-entry" @click="openAchievementDialog">
                                <span class="stat-num">{{ stats.badgeCount }}/11</span>
                                <span class="stat-text">勋章</span>
                            </span>
                        </div>
                    </div>
                </div>
                <button class="settings-btn" @click="goToSettings">设置</button>
            </div>

            <div class="user-sidebar">
                <div class="sidebar-card achievements-card">
                    <h4 class="card-title">个人成就</h4>
                    <div class="achievement-item">
                        <span class="achievement-icon">👁️</span>
                        <span class="achievement-text">文章被阅读</span>
                        <span class="achievement-value">1</span>
                    </div>
                </div>
                <div class="sidebar-card level-card">
                    <h4 class="card-title">等级</h4>
                    <div class="level-row">
                        <span class="level-icon">☀️</span>
                        <span class="level-name">逐日等级</span>
                        <span class="level-value">Lv.{{ levelInfo.dailyLevel }}</span>
                        <span class="level-score">{{ levelInfo.dailyScore }}</span>
                    </div>
                    <div class="level-row">
                        <span class="level-icon">💪</span>
                        <span class="level-name">逐力值</span>
                        <span class="level-value">Lv.{{ levelInfo.powerLevel }}</span>
                        <span class="level-score">{{ levelInfo.powerValue }}</span>
                    </div>
                </div>
                <div class="sidebar-card stats-card">
                    <div class="stats-header">
                        <span class="stats-label">关注了</span>
                        <span class="stats-value">{{ stats.followCount }}</span>
                    </div>
                    <div class="stats-header">
                        <span class="stats-label">关注者</span>
                        <span class="stats-value">{{ stats.followerCount }}</span>
                    </div>
                    <div class="stats-header">
                        <span class="stats-label">收藏集</span>
                        <span class="stats-value">{{ stats.collectionCount }}</span>
                    </div>
                    <div class="stats-header">
                        <span class="stats-label">关注标签</span>
                        <span class="stats-value">{{ stats.tagCount }}</span>
                    </div>
                    <div class="join-date">
                        <span class="join-label">加入于</span>
                        <span class="join-value">2025-10-25</span>
                    </div>
                </div>
            </div>

            <div class="tabs-bar">
                <div 
                    class="tab-item" 
                    :class="{ 'active': activeTab === 'dynamic' }"
                    @click="switchTab('dynamic')"
                >
                    动态
                </div>
                <div 
                    class="tab-item" 
                    :class="{ 'active': activeTab === 'article' }"
                    @click="switchTab('article')"
                >
                    文章
                </div>
                <div 
                    class="tab-item" 
                    :class="{ 'active': activeTab === 'boiling' }"
                    @click="switchTab('boiling')"
                >
                    沸点
                </div>
                <div 
                    class="tab-item" 
                    :class="{ 'active': activeTab === 'column' }"
                    @click="switchTab('column')"
                >
                    专栏
                </div>
                <div 
                    class="tab-item" 
                    :class="{ 'active': activeTab === 'courses' }"
                    @click="switchTab('courses')"
                >
                    课程
                </div>
                <div 
                    class="tab-item" 
                    :class="{ 'active': activeTab === 'collection' }"
                    @click="switchTab('collection')"
                >
                    收藏集
                </div>
                <div 
                    class="tab-item" 
                    :class="{ 'active': activeTab === 'follow' }"
                    @click="switchTab('follow')"
                >
                    关注
                </div>
                <div 
                    class="tab-item" 
                    :class="{ 'active': activeTab === 'likes' }"
                    @click="switchTab('likes')"
                >
                    赞
                </div>
            </div>

            <div class="content-area">
                <div v-if="activeTab === 'dynamic'" class="tab-content">
                    <div v-if="dynamicList.length === 0" class="empty-state">
                        <div class="empty-icon">📝</div>
                        <div class="empty-text">暂无动态</div>
                    </div>
                    <div v-else class="dynamic-list">
                        <div class="dynamic-item" v-for="item in dynamicList" :key="item.id">
                            <span class="dynamic-category" :class="'cat-' + item.actionCategory">
                                {{ categoryIcon(item.actionCategory) }}
                            </span>
                            <div class="dynamic-body">
                                <div class="dynamic-text">
                                    <span class="dynamic-action">{{ item.behaviorDesc }}</span>
                                    <a
                                        class="dynamic-target"
                                        :href="item.targetUrl"
                                        @click.prevent="openTarget(item)"
                                    >{{ item.targetTitle }}</a>
                                </div>
                                <div v-if="item.targetCover" class="dynamic-cover-wrap">
                                    <img :src="item.targetCover" class="dynamic-cover" alt="cover">
                                </div>
                                <div class="dynamic-meta">
                                    <span v-if="item.targetMeta" class="dynamic-meta-text">{{ item.targetMeta }}</span>
                                    <span class="dynamic-time">{{ formatTime(item.createdTime) }}</span>
                                </div>
                            </div>
                        </div>
                    </div>
                </div>

                <div v-if="activeTab === 'article'" class="tab-content">
                    <div class="article-filter">
                        <button 
                            class="filter-btn" 
                            :class="{ 'active': articleFilter === 'hot' }"
                            @click="articleFilter = 'hot'"
                        >
                            最热
                        </button>
                        <button 
                            class="filter-btn" 
                            :class="{ 'active': articleFilter === 'new' }"
                            @click="articleFilter = 'new'"
                        >
                            最新
                        </button>
                    </div>
                    <div class="article-list">
                        <div class="article-item" v-for="article in articleList" :key="article.id">
                            <div class="article-info">
                                <div class="article-title">{{ article.title }}</div>
                                <div class="article-meta">
                                    <span class="article-time">{{ article.time }}</span>
                                    <span class="article-read">{{ article.readCount }}阅读</span>
                                    <span class="article-comment">{{ article.commentCount }}评论</span>
                                </div>
                            </div>
                        </div>
                    </div>
                </div>

                <div v-if="activeTab === 'column'" class="tab-content">
                    <div v-if="columnList.length === 0" class="empty-state">
                        <div class="empty-icon">📚</div>
                        <div class="empty-text">暂无专栏</div>
                        <button class="empty-btn" @click="showCreateColumn = true">新建专栏</button>
                    </div>
                    <div v-else class="column-list">
                        <div class="column-item" v-for="column in columnList" :key="column.id">
                            <img :src="column.cover || defaultAvatar" class="column-cover" alt="cover">
                            <div class="column-info">
                                <div class="column-name">{{ column.name }}</div>
                                <div class="column-desc">{{ column.desc }}</div>
                                <div class="column-count">{{ column.articleCount }}篇文章</div>
                            </div>
                        </div>
                    </div>
                </div>

                <div v-if="activeTab === 'boiling'" class="tab-content">
                    <div v-if="boilingList.length === 0" class="empty-state">
                        <div class="empty-icon">💧</div>
                        <div class="empty-text">暂无沸点</div>
                    </div>
                    <div v-else class="article-list">
                        <div class="article-item" v-for="item in boilingList" :key="item.id">
                            <div class="article-info">
                                <div class="article-title">{{ item.content || item.title }}</div>
                                <div class="article-meta">
                                    <span class="article-time">{{ item.createTime || item.createdAt }}</span>
                                    <span class="article-read">{{ item.likeCount || 0 }}赞</span>
                                    <span class="article-comment">{{ item.commentCount || 0 }}评论</span>
                                </div>
                            </div>
                        </div>
                    </div>
                </div>

                <div v-if="activeTab === 'collection'" class="tab-content">
                    <div v-if="collectionList.length === 0" class="empty-state">
                        <div class="empty-icon">⭐</div>
                        <div class="empty-text">暂无收藏集</div>
                    </div>
                    <div v-else class="collection-list">
                        <div 
                            class="collection-item" 
                            v-for="collection in collectionList" 
                            :key="collection.id"
                            @click="showCollectionDetail(collection)"
                        >
                            <div class="collection-icon">📁</div>
                            <div class="collection-info">
                                <div class="collection-name">{{ collection.name }}</div>
                                <div class="collection-count">{{ collection.articleCount }}篇文章</div>
                            </div>
                        </div>
                    </div>
                </div>

                <div v-if="activeTab === 'follow'" class="tab-content">
                    <div class="follow-subtabs">
                        <button 
                            class="subtab-btn" 
                            :class="{ 'active': followSubtab === 'following' }"
                            @click="followSubtab = 'following'"
                        >
                            关注的用户
                        </button>
                        <button 
                            class="subtab-btn" 
                            :class="{ 'active': followSubtab === 'followers' }"
                            @click="followSubtab = 'followers'"
                        >
                            关注者
                        </button>
                        <button 
                            class="subtab-btn" 
                            :class="{ 'active': followSubtab === 'columns' }"
                            @click="followSubtab = 'columns'"
                        >
                            订阅的专栏
                        </button>
                        <button 
                            class="subtab-btn" 
                            :class="{ 'active': followSubtab === 'tags' }"
                            @click="followSubtab = 'tags'"
                        >
                            关注标签
                        </button>
                    </div>
                    <div class="follow-content">
                        <div v-if="followSubtab === 'following'" class="following-list">
                            <div class="following-item" v-for="user in followingList" :key="user.id">
                                <img :src="user.avatar || defaultAvatar" class="following-avatar" alt="avatar">
                                <div class="following-info">
                                    <div class="following-name">{{ user.name }}</div>
                                    <div class="following-intro">{{ user.intro }}</div>
                                </div>
                                <button class="unfollow-btn">已关注</button>
                            </div>
                        </div>
                        <div v-if="followSubtab === 'followers'" class="followers-list">
                            <div class="follower-item" v-for="user in followersList" :key="user.id">
                                <img :src="user.avatar || defaultAvatar" class="follower-avatar" alt="avatar">
                                <div class="follower-info">
                                    <div class="follower-name">{{ user.name }}</div>
                                    <div class="follower-intro">{{ user.intro }}</div>
                                </div>
                                <button class="follow-btn">关注</button>
                            </div>
                        </div>
                        <div v-if="followSubtab === 'columns'" class="subscribed-columns">
                            <div class="subscribed-column-item" v-for="column in subscribedColumns" :key="column.id">
                                <img :src="column.cover || defaultAvatar" class="column-mini-cover" alt="cover">
                                <div class="column-mini-info">
                                    <div class="column-mini-name">{{ column.name }}</div>
                                    <div class="column-mini-count">{{ column.articleCount }}篇</div>
                                </div>
                            </div>
                        </div>
                        <div v-if="followSubtab === 'tags'" class="followed-tags">
                            <span class="tag-item" v-for="tag in followedTags" :key="tag.id">{{ tag.name }}</span>
                        </div>
                    </div>
                </div>

                <div v-if="activeTab === 'courses'" class="tab-content">
                    <div class="empty-state">
                        <div class="empty-icon">🎓</div>
                        <div class="empty-text">暂无课程</div>
                    </div>
                </div>

                <div v-if="activeTab === 'likes'" class="tab-content">
                    <div class="follow-subtabs">
                        <button class="subtab-btn" :class="{ 'active': likesSubtab === 'article' }" @click="likesSubtab = 'article'">文章</button>
                        <button class="subtab-btn" :class="{ 'active': likesSubtab === 'pins' }" @click="likesSubtab = 'pins'">沸点</button>
                    </div>
                    <div v-if="likesSubtab === 'article'" class="article-list">
                        <div v-if="likedArticles.length === 0" class="empty-state">
                            <div class="empty-icon">👍</div>
                            <div class="empty-text">暂无点赞的文章</div>
                        </div>
                        <div class="article-item" v-for="article in likedArticles" :key="article.id">
                            <div class="article-info">
                                <div class="article-title">{{ article.title }}</div>
                                <div class="article-meta">
                                    <span class="article-time">{{ article.time }}</span>
                                    <span class="article-read">{{ article.readCount }}阅读</span>
                                </div>
                            </div>
                        </div>
                    </div>
                    <div v-if="likesSubtab === 'pins'" class="empty-state">
                        <div class="empty-icon">💬</div>
                        <div class="empty-text">暂无点赞的沸点</div>
                    </div>
                </div>
            </div>
        </div>

        <el-dialog 
            title="新建专栏" 
            :visible.sync="showCreateColumn" 
            width="480px"
        >
            <el-form label-position="top">
                <el-form-item label="专栏名称">
                    <el-input placeholder="请输入专栏名称" v-model="columnForm.name"></el-input>
                </el-form-item>
                <el-form-item label="专栏简介">
                    <el-input type="textarea" placeholder="请输入专栏简介" v-model="columnForm.desc"></el-input>
                </el-form-item>
                <el-form-item label="专栏封面">
                    <el-upload 
                        class="column-cover-upload" 
                        action="#" 
                        :auto-upload="false"
                    >
                        <img v-if="columnForm.cover" :src="columnForm.cover" class="cover-preview">
                        <i v-else class="el-icon-plus cover-upload-icon"></i>
                    </el-upload>
                </el-form-item>
            </el-form>
            <span slot="footer" class="dialog-footer">
                <el-button @click="showCreateColumn = false">取消</el-button>
                <el-button type="primary" @click="createColumn">创建</el-button>
            </span>
        </el-dialog>

        <el-dialog 
            :title="selectedCollection?.name || '收藏集'" 
            :visible.sync="showCollectionDetailModal" 
            width="700px"
        >
            <div class="collection-detail">
                <div class="detail-header">
                    <span class="detail-count">{{ selectedCollection?.articleCount || 0 }}篇文章</span>
                </div>
                <div class="detail-list">
                    <div class="detail-item" v-for="article in selectedCollection?.articles || []" :key="article.id">
                        <div class="detail-title">{{ article.title }}</div>
                        <div class="detail-meta">{{ article.time }} · {{ article.readCount }}阅读</div>
                    </div>
                </div>
            </div>
        </el-dialog>

        <el-dialog
            title="我的勋章"
            :visible.sync="achievementDialog"
            width="720px"
            custom-class="achievement-dialog"
        >
            <div class="achievement-wall">
                <div class="ach-level-section" v-if="achievements.levels.length">
                    <div class="ach-level-card" v-for="lv in achievements.levels" :key="lv.type">
                        <div class="ach-level-icon">{{ lv.type === 'daily' ? '☀️' : '💪' }}</div>
                        <div class="ach-level-info">
                            <div class="ach-level-name">{{ lv.name }}</div>
                            <div class="ach-level-title">{{ lv.levelTitle || (lv.name + ' Lv.' + lv.level) }}</div>
                            <div class="ach-level-value">Lv.{{ lv.level }}</div>
                        </div>
                    </div>
                </div>
                <div class="ach-grid">
                    <div class="ach-item" :class="{ unlocked: item.unlocked }" v-for="item in achievements.list" :key="item.code">
                        <div class="ach-icon">{{ item.icon }}</div>
                        <div class="ach-name">{{ item.name }}</div>
                        <div class="ach-desc">{{ item.description }}</div>
                        <div class="ach-progress" v-if="item.unlocked">已解锁</div>
                        <div class="ach-progress locked" v-else>{{ item.progress }}/{{ item.threshold }}</div>
                    </div>
                </div>
            </div>
        </el-dialog>
    </div>
</template>

<script>
import HomeBar from '@/components/bars/home_bar'
import Utils from '@/utils/env'
const defaultAvatar = 'data:image/svg+xml,%3Csvg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100"%3E%3Ccircle cx="50" cy="50" r="50" fill="%23ddd"/%3E%3C/svg%3E'
import { toast } from '@/utils/toast'
import { getUserStatistics } from '@/apis/user'
import { getUserAchievements } from '@/apis/achievement'
import { getArticleList, getColumnList, getPinsList } from '@/apis/creator/content'
import { getFollowers } from '@/apis/creator/fans'
import { getUserDynamic } from '@/apis/author'

export default {
    name: 'UserProfile',
    components: { HomeBar },
    data() {
        return {
            activeTab: 'dynamic',
            articleFilter: 'hot',
            followSubtab: 'following',
            likesSubtab: 'article',
            showCreateColumn: false,
            showCollectionDetailModal: false,
            selectedCollection: null,
            userInfo: {
                nickName: '',
                avatar: '',
                intro: ''
            },
            stats: {
                followCount: 0,
                followerCount: 0,
                likedCount: 0,
                readCount: '0',
                collectionCount: 0,
                tagCount: 0,
                badgeCount: 0
            },
            levelInfo: {
                dailyScore: 0,
                dailyLevel: 0,
                dailyTitle: '',
                powerValue: 0,
                powerLevel: 0,
                powerTitle: ''
            },
            achievementDialog: false,
            achievements: {
                unlockedCount: 0,
                totalCount: 0,
                list: [],
                levels: []
            },
            columnForm: {
                name: '',
                desc: '',
                cover: ''
            },
            dynamicList: [],
            articleList: [],
            boilingList: [],
            columnList: [],
            collectionList: [],
            followingList: [],
            followersList: [],
            subscribedColumns: [],
            followedTags: [],
            likedArticles: []
        }
    },
    computed: {
        isDesktop() {
            return Utils.isDesktop()
        },
        defaultAvatar() {
            return defaultAvatar
        },
        // 当前浏览的个人主页用户ID（优先取路由参数，其次当前登录用户）
        profileUserId() {
            const routeId = this.$route.params && this.$route.params.id
            if (routeId) return routeId
            const storeUser = this.$store.getters.userInfo
            return storeUser && storeUser.id ? storeUser.id : ''
        },
        // 逐友等级徽章（取自成就接口，动态展示当前等级）
        dailyLevelBadge() {
            return this.achievements.levels.find(l => l.type === 'daily') || null
        },
        // 逐力值等级徽章
        powerLevelBadge() {
            return this.achievements.levels.find(l => l.type === 'power') || null
        }
    },
    mounted() {
        this.loadUserData()
    },
    methods: {
        async loadUserData() {
            // Read URL params for tab navigation
            if (this.$route.query.tab) {
                const tab = this.$route.query.tab
                // Normalize tab name
                const validTabs = ['dynamic', 'article', 'boiling', 'column', 'courses', 'collection', 'follow', 'likes']
                if (validTabs.includes(tab)) {
                    this.activeTab = tab
                }
            }
            if (this.$route.query.subTab) {
                const subTab = this.$route.query.subTab
                // Set the correct subTab based on the active tab
                if (this.activeTab === 'follow') {
                    const validFollowSubs = ['following', 'followers', 'columns', 'tags']
                    if (validFollowSubs.includes(subTab)) {
                        this.followSubtab = subTab
                    }
                } else if (this.activeTab === 'likes') {
                    const validLikesSubs = ['article', 'pins']
                    if (validLikesSubs.includes(subTab)) {
                        this.likesSubtab = subTab
                    }
                }
            }

            // Load user info from Vuex store
            const storeUserInfo = this.$store.getters.userInfo
            if (storeUserInfo) {
                this.userInfo = {
                    nickName: storeUserInfo.nickName || '',
                    avatar: storeUserInfo.avatar || '',
                    intro: storeUserInfo.intro || ''
                }
            }

            // Fetch user statistics
            try {
                const statsRes = await getUserStatistics()
                if (statsRes && statsRes.code === 200 && statsRes.data) {
                    const data = statsRes.data
                    this.stats = {
                        followCount: data.followCount || 0,
                        followerCount: data.followerCount || 0,
                        likedCount: data.likeCount || 0,
                        readCount: data.readCount || '0',
                        collectionCount: data.collectionCount || 0,
                        tagCount: data.tagCount || 0,
                        badgeCount: data.badgeCount || 0
                    }
                    // levelInfo is a nested object in the response
                    if (data.levelInfo) {
                        this.levelInfo = {
                            dailyScore: data.levelInfo.dailyScore || 0,
                            dailyLevel: data.levelInfo.dailyLevel || 0,
                            dailyTitle: data.levelInfo.dailyTitle || '',
                            powerValue: data.levelInfo.powerValue || 0,
                            powerLevel: data.levelInfo.powerLevel || 0,
                            powerTitle: data.levelInfo.powerTitle || ''
                        }
                    }
                }
            } catch (e) {
                // Keep default values when API fails
            }

            // 加载成就勋章（当前浏览用户）
            this.fetchAchievements()

            // Load content based on active tab
            this.loadTabContent()
        },
        async fetchAchievements() {
            try {
                const userId = this.profileUserId
                if (!userId) return
                const res = await getUserAchievements(userId)
                if (res && res.code === 200 && res.data) {
                    this.achievements = {
                        unlockedCount: res.data.unlockedCount || 0,
                        totalCount: res.data.totalCount || 0,
                        list: res.data.list || [],
                        levels: res.data.levels || []
                    }
                }
            } catch (e) {
                // 接口失败时保留默认值，不影响页面浏览
            }
        },
        openAchievementDialog() {
            this.achievementDialog = true
        },
        async loadTabContent() {
            switch (this.activeTab) {
                case 'dynamic':
                    this.fetchDynamic()
                    break
                case 'article':
                    this.fetchArticles()
                    break
                case 'column':
                    this.fetchColumns()
                    break
                case 'boiling':
                    this.fetchPins()
                    break
                case 'follow':
                    this.fetchFollowData()
                    break
                case 'collection':
                    this.fetchCollections()
                    break
                default:
                    break
            }
        },
        async fetchDynamic() {
            try {
                const userId = this.profileUserId
                const res = await getUserDynamic(userId, 50)
                if (res && res.code === 200 && Array.isArray(res.data)) {
                    this.dynamicList = res.data
                }
            } catch (e) {
                // Keep empty list when API fails
            }
        },
        categoryIcon(category) {
            if (category === 'follow') return '👥'
            if (category === 'publish') return '📝'
            return '👍'
        },
        openTarget(item) {
            if (item.targetUrl) {
                // 沸点/用户使用站内路由，文章详情新开窗口
                if (item.targetUrl.indexOf('/content/article/') === 0) {
                    window.open(item.targetUrl, '_blank')
                } else {
                    this.$router.push(item.targetUrl)
                }
            }
        },
        formatTime(time) {
            if (!time) return ''
            const date = new Date(time)
            const now = new Date()
            const diff = now - date
            const minute = 60 * 1000
            const hour = 60 * minute
            const day = 24 * hour
            if (diff < minute) return '刚刚'
            if (diff < hour) return Math.floor(diff / minute) + '分钟前'
            if (diff < day) return Math.floor(diff / hour) + '小时前'
            if (diff < 7 * day) return Math.floor(diff / day) + '天前'
            const pad = n => (n < 10 ? '0' + n : '' + n)
            return date.getFullYear() + '-' + pad(date.getMonth() + 1) + '-' + pad(date.getDate())
        },
        async fetchArticles() {
            try {
                const res = await getArticleList({ authorId: this.$store.getters.userInfo?.id })
                if (res && res.code === 200 && res.data && res.data.list) {
                    this.articleList = res.data.list.map(item => ({
                        id: item.id,
                        title: item.title,
                        time: item.createTime || item.createdAt || '',
                        readCount: item.readCount || 0,
                        commentCount: item.commentCount || 0
                    }))
                }
            } catch (e) {
                // Keep empty list when API fails
            }
        },
        async fetchColumns() {
            try {
                const res = await getColumnList()
                if (res && res.code === 200 && res.data && res.data.list) {
                    this.columnList = res.data.list.map(item => ({
                        id: item.id,
                        name: item.name,
                        desc: item.description || '',
                        cover: item.cover || defaultAvatar,
                        articleCount: item.articleCount || 0
                    }))
                }
            } catch (e) {
                // Keep empty list when API fails
            }
        },
        async fetchPins() {
            try {
                const res = await getPinsList()
                if (res && res.code === 200 && res.data && res.data.list) {
                    this.boilingList = res.data.list
                }
            } catch (e) {
                // Keep empty list when API fails
            }
        },
        async fetchFollowData() {
            try {
                // Fetch followers
                const followersRes = await getFollowers()
                if (followersRes && followersRes.code === 200 && followersRes.data) {
                    const list = followersRes.data.list || followersRes.data || []
                    this.followersList = list.map(item => ({
                        id: item.id || item.userId,
                        name: item.name || item.nickname || '',
                        avatar: item.avatar || '',
                        intro: item.intro || ''
                    }))
                }
            } catch (e) {
                // Keep empty list when API fails
            }
        },
        async fetchCollections() {
            // Collections are tracked via the collectCount in statistics
            // The actual collection list would need a dedicated API
        },
        switchTab(tab) {
            this.activeTab = tab
            this.loadTabContent()
        },
        goToSettings() {
            this.$router.push('/user/settings')
        },
        showCollectionDetail(collection) {
            this.selectedCollection = collection
            this.showCollectionDetailModal = true
        },
        createColumn() {
            if (!this.columnForm.name) {
                toast('请输入专栏名称', 2)
                return
            }
            this.columnList.push({
                id: Date.now(),
                name: this.columnForm.name,
                desc: this.columnForm.desc || '暂无简介',
                cover: this.columnForm.cover || defaultAvatar,
                articleCount: 0
            })
            this.showCreateColumn = false
            this.columnForm = { name: '', desc: '', cover: '' }
            toast('专栏创建成功', 2)
        }
    }
}
</script>

<style lang="less" scoped>
@import '../../styles/common';

.user-page {
    min-height: 100vh;
    background: #f7f8fa;
}

.user-content {
    max-width: 1200px;
    margin: 0 auto;
    padding: 24px;
    position: relative;
}

.user-header {
    display: flex;
    justify-content: space-between;
    align-items: flex-start;
    background: #fff;
    padding: 24px;
    border-radius: 8px;
    box-shadow: 0 1px 2px rgba(0,0,0,0.05);
    margin-bottom: 16px;
}

.header-left {
    display: flex;
    gap: 16px;
}

.user-big-avatar {
    width: 100px;
    height: 100px;
    border-radius: 50%;
    object-fit: cover;
    border: 3px solid #1e80ff;
}

.user-meta {
    flex: 1;
}

.user-name {
    font-size: 20px;
    font-weight: 600;
    color: #252933;
    margin-bottom: 4px;
}

.user-level-row {
    display: flex;
    gap: 8px;
    margin-bottom: 12px;
}

.user-level {
    font-size: 12px;
    color: #1e80ff;
    background: #eaf2ff;
    padding: 2px 8px;
    border-radius: 4px;
    display: inline-block;

    &.power {
        color: #9a6700;
        background: #fdf4df;
    }
}

.user-intro {
    font-size: 14px;
    color: #515767;
    line-height: 1.6;
    margin-bottom: 12px;
}

.user-stats-row {
    display: flex;
    align-items: center;
    gap: 16px;
}

.stat-item {
    display: flex;
    align-items: baseline;
    gap: 4px;
}

.stat-num {
    font-size: 16px;
    font-weight: 600;
    color: #252933;
}

.stat-text {
    font-size: 14px;
    color: #8a919f;
}

.stat-divider {
    color: #c4c9d1;
}

.settings-btn {
    padding: 8px 24px;
    border: 1px solid #e4e6eb;
    border-radius: 4px;
    background: #fff;
    color: #515767;
    font-size: 14px;
    cursor: pointer;
}

.user-sidebar {
    position: absolute;
    right: 24px;
    top: 24px;
    width: 240px;
}

.sidebar-card {
    background: #fff;
    border-radius: 8px;
    padding: 16px;
    margin-bottom: 16px;
    box-shadow: 0 1px 2px rgba(0,0,0,0.05);
}

.card-title {
    font-size: 14px;
    font-weight: 600;
    color: #252933;
    margin-bottom: 12px;
    padding-bottom: 8px;
    border-bottom: 1px solid #f2f3f5;
}

.achievement-item {
    display: flex;
    align-items: center;
    gap: 8px;
}

.achievement-icon {
    font-size: 20px;
}

.achievement-text {
    flex: 1;
    font-size: 13px;
    color: #515767;
}

.achievement-value {
    font-size: 16px;
    font-weight: 600;
    color: #252933;
}

.stats-header {
    display: flex;
    justify-content: space-between;
    padding: 8px 0;
    border-bottom: 1px solid #f2f3f5;
    &:last-child {
        border: none;
    }
}

.stats-label {
    font-size: 13px;
    color: #8a919f;
}

.stats-value {
    font-size: 16px;
    font-weight: 600;
    color: #252933;
}

.level-row {
    display: flex;
    align-items: center;
    gap: 8px;
    padding: 8px 0;
    border-bottom: 1px solid #f2f3f5;
    &:last-child {
        border: none;
    }
}

.level-icon {
    font-size: 16px;
}

.level-name {
    flex: 1;
    font-size: 13px;
    color: #515767;
}

.level-value {
    font-size: 14px;
    font-weight: 600;
    color: #1e80ff;
}

.level-score {
    font-size: 12px;
    color: #8a919f;
}

.join-date {
    display: flex;
    justify-content: space-between;
    padding-top: 12px;
    margin-top: 8px;
    border-top: 1px solid #f2f3f5;
}

.join-label {
    font-size: 13px;
    color: #8a919f;
}

.join-value {
    font-size: 13px;
    color: #515767;
}

.tabs-bar {
    display: flex;
    background: #fff;
    border-radius: 8px;
    padding: 0 16px;
    margin-bottom: 16px;
    box-shadow: 0 1px 2px rgba(0,0,0,0.05);
}

.tab-item {
    padding: 16px 24px;
    font-size: 14px;
    color: #515767;
    cursor: pointer;
    position: relative;
    transition: color 0.2s;
    &:hover {
        color: #1e80ff;
    }
    &.active {
        color: #1e80ff;
        &::after {
            content: '';
            position: absolute;
            bottom: 0;
            left: 50%;
            transform: translateX(-50%);
            width: 24px;
            height: 3px;
            background: #1e80ff;
            border-radius: 2px;
        }
    }
}

.content-area {
    margin-right: 264px;
}

.tab-content {
    background: #fff;
    border-radius: 8px;
    padding: 16px;
    min-height: 300px;
}

.dynamic-list {
    padding: 8px 0;
}

.dynamic-item {
    display: flex;
    gap: 12px;
    padding: 14px 0;
    border-bottom: 1px solid #f2f3f5;
    &:last-child {
        border: none;
    }
}

.dynamic-category {
    width: 32px;
    height: 32px;
    border-radius: 50%;
    display: flex;
    align-items: center;
    justify-content: center;
    font-size: 16px;
    flex-shrink: 0;
    background: #f2f3f5;
    &.cat-like {
        background: #eaf2ff;
    }
    &.cat-follow {
        background: #e8f8f0;
    }
    &.cat-publish {
        background: #fff3e6;
    }
}

.dynamic-body {
    flex: 1;
    min-width: 0;
}

.dynamic-text {
    font-size: 14px;
    line-height: 1.6;
}

.dynamic-action {
    color: #515767;
    margin-right: 4px;
}

.dynamic-target {
    color: #1e80ff;
    cursor: pointer;
    word-break: break-all;
    &:hover {
        text-decoration: underline;
    }
}

.dynamic-cover-wrap {
    margin-top: 8px;
}

.dynamic-cover {
    max-width: 160px;
    max-height: 100px;
    border-radius: 4px;
    object-fit: cover;
    cursor: pointer;
}

.dynamic-meta {
    display: flex;
    align-items: center;
    gap: 12px;
    margin-top: 6px;
    font-size: 12px;
    color: #8a919f;
}

.dynamic-meta-text {
    color: #8a919f;
}

.dynamic-time {
    color: #c4c9d1;
    font-size: 12px;
}

.article-filter {
    display: flex;
    gap: 8px;
    margin-bottom: 16px;
}

.filter-btn {
    padding: 6px 16px;
    border: 1px solid #e4e6eb;
    border-radius: 4px;
    background: #fff;
    color: #515767;
    font-size: 13px;
    cursor: pointer;
    &.active {
        background: #1e80ff;
        color: #fff;
        border-color: #1e80ff;
    }
}

.article-list {
    padding: 8px 0;
}

.article-item {
    padding: 12px 0;
    border-bottom: 1px solid #f2f3f5;
    &:last-child {
        border: none;
    }
}

.article-title {
    font-size: 16px;
    color: #252933;
    margin-bottom: 8px;
    cursor: pointer;
    &:hover {
        color: #1e80ff;
    }
}

.article-meta {
    display: flex;
    gap: 16px;
    font-size: 13px;
    color: #8a919f;
}

.column-list {
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
    gap: 16px;
}

.column-item {
    border: 1px solid #f2f3f5;
    border-radius: 8px;
    overflow: hidden;
    cursor: pointer;
    &:hover {
        border-color: #1e80ff;
    }
}

.column-cover {
    width: 100%;
    height: 120px;
    object-fit: cover;
}

.column-info {
    padding: 12px;
}

.column-name {
    font-size: 15px;
    font-weight: 600;
    color: #252933;
    margin-bottom: 4px;
}

.column-desc {
    font-size: 13px;
    color: #8a919f;
    margin-bottom: 8px;
}

.column-count {
    font-size: 12px;
    color: #c4c9d1;
}

.empty-state {
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    padding: 60px 0;
}

.empty-icon {
    font-size: 48px;
    margin-bottom: 16px;
}

.empty-text {
    font-size: 14px;
    color: #8a919f;
    margin-bottom: 16px;
}

.empty-btn {
    padding: 8px 24px;
    border: none;
    border-radius: 4px;
    background: #1e80ff;
    color: #fff;
    font-size: 14px;
    cursor: pointer;
}

.collection-list {
    padding: 8px 0;
}

.collection-item {
    display: flex;
    align-items: center;
    gap: 12px;
    padding: 12px 0;
    border-bottom: 1px solid #f2f3f5;
    cursor: pointer;
    &:hover {
        background: #f7f8fa;
    }
    &:last-child {
        border: none;
    }
}

.collection-icon {
    font-size: 24px;
}

.collection-name {
    font-size: 14px;
    color: #252933;
}

.collection-count {
    font-size: 12px;
    color: #8a919f;
}

.follow-subtabs {
    display: flex;
    gap: 8px;
    margin-bottom: 16px;
    padding-bottom: 16px;
    border-bottom: 1px solid #f2f3f5;
}

.subtab-btn {
    padding: 6px 16px;
    border: none;
    background: transparent;
    color: #515767;
    font-size: 14px;
    cursor: pointer;
    &.active {
        color: #1e80ff;
        font-weight: 500;
    }
}

.following-list, .followers-list {
    padding: 8px 0;
}

.following-item, .follower-item {
    display: flex;
    align-items: center;
    gap: 12px;
    padding: 12px 0;
    border-bottom: 1px solid #f2f3f5;
    &:last-child {
        border: none;
    }
}

.following-avatar, .follower-avatar {
    width: 48px;
    height: 48px;
    border-radius: 50%;
    object-fit: cover;
}

.following-info, .follower-info {
    flex: 1;
}

.following-name, .follower-name {
    font-size: 14px;
    font-weight: 500;
    color: #252933;
    margin-bottom: 4px;
}

.following-intro, .follower-intro {
    font-size: 13px;
    color: #8a919f;
}

.unfollow-btn {
    padding: 6px 16px;
    border: 1px solid #e4e6eb;
    border-radius: 4px;
    background: #fff;
    color: #8a919f;
    font-size: 13px;
    cursor: pointer;
}

.follow-btn {
    padding: 6px 16px;
    border: none;
    border-radius: 4px;
    background: #1e80ff;
    color: #fff;
    font-size: 13px;
    cursor: pointer;
}

.subscribed-columns {
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(200px, 1fr));
    gap: 12px;
}

.subscribed-column-item {
    display: flex;
    gap: 8px;
    padding: 8px;
    border: 1px solid #f2f3f5;
    border-radius: 4px;
    cursor: pointer;
}

.column-mini-cover {
    width: 60px;
    height: 60px;
    border-radius: 4px;
    object-fit: cover;
}

.column-mini-info {
    flex: 1;
}

.column-mini-name {
    font-size: 13px;
    color: #252933;
    margin-bottom: 4px;
}

.column-mini-count {
    font-size: 12px;
    color: #8a919f;
}

.followed-tags {
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
}

.tag-item {
    padding: 6px 12px;
    background: #f7f8fa;
    border-radius: 4px;
    font-size: 13px;
    color: #515767;
    cursor: pointer;
}

.collection-detail {
    max-height: 400px;
    overflow-y: auto;
}

.detail-header {
    padding-bottom: 12px;
    border-bottom: 1px solid #f2f3f5;
    margin-bottom: 12px;
}

.detail-count {
    font-size: 13px;
    color: #8a919f;
}

.detail-list {
    padding: 8px 0;
}

.detail-item {
    padding: 12px 0;
    border-bottom: 1px solid #f2f3f5;
    &:last-child {
        border: none;
    }
}

.detail-title {
    font-size: 14px;
    color: #252933;
    margin-bottom: 4px;
}

.detail-meta {
    font-size: 12px;
    color: #8a919f;
}

.column-cover-upload {
    width: 100%;
    height: 150px;
    border: 1px dashed #d9d9d9;
    border-radius: 4px;
    display: flex;
    align-items: center;
    justify-content: center;
    cursor: pointer;
}

.cover-preview {
    width: 100%;
    height: 150px;
    object-fit: cover;
    border-radius: 4px;
}

.cover-upload-icon {
    font-size: 32px;
    color: #8c939d;
}

@media screen and (max-width: 960px) {
    .user-sidebar {
        display: none;
    }
    .content-area {
        margin-right: 0;
    }
    .user-header {
        flex-direction: column;
        gap: 16px;
    }
}

.stat-item.badge-entry {
    cursor: pointer;
    transition: opacity .2s;

    &:hover {
        opacity: .7;
    }
}

.achievement-wall {
    .ach-level-section {
        display: flex;
        gap: 12px;
        margin-bottom: 20px;

        .ach-level-card {
            flex: 1;
            display: flex;
            align-items: center;
            gap: 12px;
            background: linear-gradient(135deg, #f0f6ff 0%, #eaf2ff 100%);
            border-radius: 8px;
            padding: 16px;

            .ach-level-icon {
                font-size: 32px;
            }

            .ach-level-info {
                flex: 1;

                .ach-level-name {
                    font-size: 14px;
                    font-weight: 600;
                    color: #252933;
                }

                .ach-level-title {
                    font-size: 12px;
                    color: #515767;
                    margin-top: 4px;
                }

                .ach-level-value {
                    font-size: 12px;
                    font-weight: 600;
                    color: #1e80ff;
                    margin-top: 4px;
                }
            }
        }
    }

    .ach-grid {
        display: grid;
        grid-template-columns: repeat(4, 1fr);
        gap: 16px;

        .ach-item {
            text-align: center;
            padding: 16px 8px;
            border-radius: 8px;
            background: #fff;
            border: 1px solid #f2f3f5;
            transition: all .2s;

            &:hover {
                box-shadow: 0 4px 12px rgba(0, 0, 0, .06);
            }

            .ach-icon {
                font-size: 36px;
                margin-bottom: 8px;
            }

            .ach-name {
                font-size: 14px;
                font-weight: 600;
                color: #252933;
                margin-bottom: 4px;
            }

            .ach-desc {
                font-size: 12px;
                color: #8a919f;
                margin-bottom: 8px;
            }

            .ach-progress {
                display: inline-block;
                font-size: 12px;
                color: #1e80ff;
                background: #eaf2ff;
                padding: 2px 8px;
                border-radius: 4px;

                &.locked {
                    color: #8a919f;
                    background: #f2f3f5;
                }
            }

            &.unlocked {
                border-color: #1e80ff;
                background: #f7fbff;
            }
        }
    }
}
</style>