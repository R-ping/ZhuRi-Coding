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
                <button class="settings-btn" v-if="isOwnProfile" @click="goToSettings">设置</button>
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
                <div 
                    class="tab-item" 
                    :class="{ 'active': activeTab === 'tips' }"
                    @click="switchTab('tips')"
                >
                    打赏
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
                        <button class="empty-btn" v-if="isOwnProfile" @click="showCreateColumn = true">新建专栏</button>
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
                    <div v-else class="article-list">
                        <div class="article-item" v-for="collection in collectionList" :key="collection.id">
                            <div class="article-title">{{ collection.title }}</div>
                            <div class="article-meta">
                                <span class="article-time">{{ formatTime(collection.time) }}</span>
                                <span class="article-read">{{ collection.readCount }}阅读</span>
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
                    <div v-if="coursesList.length === 0" class="empty-state">
                        <div class="empty-icon">🎓</div>
                        <div class="empty-text">暂无课程</div>
                    </div>
                    <div v-else class="course-list">
                        <div class="course-item" v-for="course in coursesList" :key="course.id">
                            <img v-if="course.coverImage" :src="course.coverImage" class="course-cover" alt="cover">
                            <div class="course-info">
                                <div class="course-title">{{ course.title }}</div>
                                <div class="course-subtitle" v-if="course.subtitle">{{ course.subtitle }}</div>
                                <div class="course-meta">
                                    <span class="course-chapters">{{ course.chapterCount || 0 }}章节</span>
                                    <span class="course-study">{{ course.studyCount || 0 }}人在学</span>
                                    <span class="course-price" :class="{ 'is-free': Number(course.price) === 0 }">
                                        {{ Number(course.price) === 0 ? '免费' : '¥' + course.price }}
                                    </span>
                                </div>
                            </div>
                        </div>
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
                    <div v-if="likesSubtab === 'pins'" class="article-list">
                        <div v-if="likedPinsList.length === 0" class="empty-state">
                            <div class="empty-icon">💬</div>
                            <div class="empty-text">暂无点赞的沸点</div>
                        </div>
                        <div class="article-item" v-for="pin in likedPinsList" :key="pin.id">
                            <div class="article-title">{{ pin.content }}</div>
                            <div class="article-meta">
                                <span class="article-time">{{ formatTime(pin.time) }}</span>
                                <span class="article-read">{{ pin.likeCount }}赞</span>
                            </div>
                        </div>
                    </div>
                </div>

                <div v-if="activeTab === 'tips'" class="tab-content">
                    <div v-if="tipRecords.length === 0" class="empty-state">
                        <div class="empty-icon">💝</div>
                        <div class="empty-text">暂无打赏记录</div>
                    </div>
                    <div v-else class="tip-list">
                        <div class="tip-item" v-for="tip in tipRecords" :key="tip.id">
                            <img :src="tip.avatar || defaultAvatar" class="tip-avatar" alt="avatar">
                            <div class="tip-body">
                                <div class="tip-header">
                                    <span class="tip-user">{{ tip.nickName }}</span>
                                    <span class="tip-amount">打赏了 <b>¥{{ tip.amount }}</b></span>
                                </div>
                                <a
                                    class="tip-article"
                                    :href="'/content/article/' + tip.articleId"
                                    @click.prevent="openArticle(tip.articleId)"
                                >《{{ tip.articleTitle }}》</a>
                                <div v-if="tip.message" class="tip-message">“{{ tip.message }}”</div>
                                <div class="tip-meta">
                                    <span class="tip-time">{{ formatTime(tip.createdTime) }}</span>
                                </div>
                            </div>
                        </div>
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
import { getUserAchievements } from '@/apis/achievement'
import { getUserDynamic, getUserHomeData, getUserHomeArticles, getUserHomeColumns, getUserHomePins, getUserHomeFollowing, getUserHomeFollowers, getUserHomeCollections, getUserHomeLikes, getUserHomeCourses, getUserHomeTips } from '@/apis/author'

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
            coursesList: [],
            followingList: [],
            followersList: [],
            subscribedColumns: [],
            followedTags: [],
            likedArticles: [],
            likedPinsList: [],
            tipRecords: []
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
            return storeUser && storeUser.userId ? storeUser.userId : ''
        },
        // 是否自己的主页（决定是否展示「设置」「新建专栏」等仅本人可见的操作）
        isOwnProfile() {
            const routeId = this.$route.params && this.$route.params.id
            if (!routeId) return true
            const storeUser = this.$store.getters.userInfo
            if (!storeUser || !storeUser.userId) return false
            return String(routeId) === String(storeUser.userId)
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
                const validTabs = ['dynamic', 'article', 'boiling', 'column', 'courses', 'collection', 'follow', 'likes', 'tips']
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

            // Load user info from Vuex store（本人主页时先立即渲染，再以接口数据校正）
            const storeUserInfo = this.$store.getters.userInfo
            if (storeUserInfo) {
                this.userInfo = {
                    nickName: storeUserInfo.nickName || '',
                    avatar: storeUserInfo.avatar || '',
                    intro: storeUserInfo.intro || ''
                }
            }

            // 加载当前浏览用户（profileUserId）的头部信息：基本信息 + 统计 + 等级（公开接口，未登录也可用）
            this.fetchHomeData()

            // 加载成就勋章（当前浏览用户）
            this.fetchAchievements()

            // Load content based on active tab
            this.loadTabContent()
        },
        // 加载主页头部聚合数据（基本信息 + 统计 + 等级），以 profileUserId 为准
        async fetchHomeData() {
            const userId = this.profileUserId
            if (!userId) return
            try {
                const res = await getUserHomeData(userId)
                if (res && res.code === 200 && res.data) {
                    const data = res.data
                    // 基本信息
                    if (data.user) {
                        this.userInfo = {
                            nickName: data.user.nickname || data.user.nickName || '',
                            avatar: data.user.avatar || '',
                            intro: data.user.intro || ''
                        }
                    }
                    // 统计
                    this.stats = {
                        followCount: data.followCount || 0,
                        followerCount: data.followerCount || 0,
                        likedCount: data.likeCount || 0,
                        readCount: data.readCount || '0',
                        collectionCount: data.collectionCount || 0,
                        tagCount: data.tagCount || 0,
                        badgeCount: data.badgeCount || 0
                    }
                    // 等级（逐日/逐力值）
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
                // 接口失败时保留默认值，不影响页面浏览
            }
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
                case 'courses':
                    this.fetchCourses()
                    break
                case 'likes':
                    this.fetchLikes()
                    break
                case 'tips':
                    this.fetchTips()
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
            const userId = this.profileUserId
            if (!userId) return
            try {
                const res = await getUserHomeArticles(userId, { page: 1, size: 10 })
                if (res && res.code === 200 && res.data && res.data.list) {
                    this.articleList = res.data.list.map(item => ({
                        // 雪花ID为json-bigint大数对象，转为字符串作为Vue key
                        id: String(item.id),
                        title: item.title,
                        time: item.createTime || '',
                        readCount: item.readCount || 0,
                        commentCount: item.commentCount || 0
                    }))
                }
            } catch (e) {
                // Keep empty list when API fails
            }
        },
        async fetchColumns() {
            const userId = this.profileUserId
            if (!userId) return
            try {
                const res = await getUserHomeColumns(userId, { page: 1, size: 10 })
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
            const userId = this.profileUserId
            if (!userId) return
            try {
                const res = await getUserHomePins(userId, { page: 1, size: 10 })
                if (res && res.code === 200 && res.data && res.data.list) {
                    this.boilingList = res.data.list.map(item => ({
                        id: String(item.id),
                        content: item.content,
                        title: item.content,
                        createTime: item.createTime,
                        likeCount: item.likeCount || 0,
                        commentCount: item.commentCount || 0
                    }))
                }
            } catch (e) {
                // Keep empty list when API fails
            }
        },
        async fetchFollowData() {
            const userId = this.profileUserId
            if (!userId) return
            try {
                // 关注的用户（公开接口，按 profileUserId 查询）
                const followingRes = await getUserHomeFollowing(userId, { page: 1, size: 20 })
                if (followingRes && followingRes.code === 200 && followingRes.data) {
                    const list = followingRes.data.list || []
                    this.followingList = list.map(item => ({
                        id: item.id,
                        name: item.nickname || '',
                        avatar: item.avatar || '',
                        intro: item.intro || ''
                    }))
                }
                // 关注者（公开接口，按 profileUserId 查询）
                const followersRes = await getUserHomeFollowers(userId, { page: 1, size: 20 })
                if (followersRes && followersRes.code === 200 && followersRes.data) {
                    const list = followersRes.data.list || []
                    this.followersList = list.map(item => ({
                        id: item.id,
                        name: item.nickname || '',
                        avatar: item.avatar || '',
                        intro: item.intro || ''
                    }))
                }
            } catch (e) {
                // Keep empty list when API fails
            }
        },
        async fetchCollections() {
            const userId = this.profileUserId
            if (!userId) return
            try {
                const res = await getUserHomeCollections(userId, { page: 1, size: 20 })
                if (res && res.code === 200 && res.data) {
                    const list = res.data.list || []
                    this.collectionList = list.map(item => ({
                        id: item.id,
                        title: item.title,
                        cover: item.coverImage || defaultAvatar,
                        authorId: item.authorId,
                        authorName: item.authorName || '',
                        time: item.collectTime || '',
                        readCount: item.readCount || 0
                    }))
                }
            } catch (e) {
                // Keep empty list when API fails
            }
        },
        async fetchCourses() {
            const userId = this.profileUserId
            if (!userId) return
            try {
                const res = await getUserHomeCourses(userId, { page: 1, size: 10 })
                if (res && res.code === 200 && res.data) {
                    this.coursesList = res.data.list || []
                }
            } catch (e) {
                // Keep empty list when API fails
            }
        },
        async fetchLikes() {
            const userId = this.profileUserId
            if (!userId) return
            try {
                // 文章（公开接口）
                const articleRes = await getUserHomeLikes(userId, { page: 1, size: 10, type: 'article' })
                if (articleRes && articleRes.code === 200 && articleRes.data) {
                    const list = articleRes.data.list || []
                    this.likedArticles = list.map(item => ({
                        id: String(item.id),
                        title: item.title,
                        time: item.likeTime || '',
                        readCount: item.readCount || 0
                    }))
                }
                // 沸点（公开接口）
                const pinsRes = await getUserHomeLikes(userId, { page: 1, size: 10, type: 'pins' })
                if (pinsRes && pinsRes.code === 200 && pinsRes.data) {
                    const list = pinsRes.data.list || []
                    this.likedPinsList = list.map(item => ({
                        id: String(item.id),
                        content: item.title || '',
                        time: item.likeTime || '',
                        likeCount: item.likeCount || 0
                    }))
                }
            } catch (e) {
                // Keep empty list when API fails
            }
        },
        switchTab(tab) {
            this.activeTab = tab
            this.loadTabContent()
        },
        async fetchTips() {
            const userId = this.profileUserId
            if (!userId) return
            try {
                const res = await getUserHomeTips(userId, { page: 1, size: 20 })
                if (res && res.code === 200 && res.data) {
                    const list = res.data.list || []
                    this.tipRecords = list.map(item => ({
                        id: item.id,
                        articleId: item.articleId || '',
                        articleTitle: item.articleTitle || '',
                        nickName: item.nickName || '',
                        avatar: item.avatar || '',
                        amount: item.amount || 0,
                        message: item.message || '',
                        createdTime: item.createdTime || ''
                    }))
                }
            } catch (e) {
                // Keep empty list when API fails
            }
        },
        openArticle(articleId) {
            if (!articleId) return
            // 文章详情走服务端渲染，新开窗口访问
            window.open('/content/article/' + articleId, '_blank')
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

.tip-list {
    padding: 8px 0;
}

.tip-item {
    display: flex;
    gap: 12px;
    padding: 14px 0;
    border-bottom: 1px solid #f2f3f5;
    &:last-child {
        border: none;
    }
}

.tip-avatar {
    width: 40px;
    height: 40px;
    border-radius: 50%;
    object-fit: cover;
    flex-shrink: 0;
}

.tip-body {
    flex: 1;
    min-width: 0;
}

.tip-header {
    display: flex;
    align-items: baseline;
    gap: 8px;
    margin-bottom: 4px;
}

.tip-user {
    font-size: 14px;
    font-weight: 600;
    color: #252933;
}

.tip-amount {
    font-size: 13px;
    color: #515767;
    b {
        color: #f56a00;
        font-weight: 600;
    }
}

.tip-article {
    display: inline-block;
    font-size: 14px;
    color: #1e80ff;
    cursor: pointer;
    margin-bottom: 4px;
    &:hover {
        text-decoration: underline;
    }
}

.tip-message {
    font-size: 13px;
    color: #515767;
    background: #f7f8fa;
    border-radius: 4px;
    padding: 6px 10px;
    margin-bottom: 6px;
}

.tip-meta {
    font-size: 12px;
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

.course-list {
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(300px, 1fr));
    gap: 16px;
    padding: 8px 0;
}

.course-item {
    display: flex;
    gap: 12px;
    border: 1px solid #f2f3f5;
    border-radius: 8px;
    padding: 12px;
    cursor: pointer;
    transition: box-shadow .2s;

    &:hover {
        box-shadow: 0 4px 12px rgba(0, 0, 0, .06);
        border-color: #e4e6eb;
    }
}

.course-cover {
    width: 96px;
    height: 72px;
    border-radius: 4px;
    object-fit: cover;
    flex-shrink: 0;
    background: #f2f3f5;
}

.course-info {
    flex: 1;
    min-width: 0;
}

.course-title {
    font-size: 15px;
    font-weight: 600;
    color: #252933;
    margin-bottom: 4px;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
}

.course-subtitle {
    font-size: 13px;
    color: #8a919f;
    margin-bottom: 8px;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
}

.course-meta {
    display: flex;
    align-items: center;
    gap: 12px;
    font-size: 12px;
    color: #8a919f;
}

.course-price {
    margin-left: auto;
    color: #f04142;
    font-weight: 600;

    &.is-free {
        color: #00b42a;
    }
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