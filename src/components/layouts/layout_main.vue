<template>
    <div class="main-root">
        <!-- Web端三栏布局 -->
        <div class="desktop-layout" v-if="isDesktop">
            <div class="desktop-header">
                <div class="header-inner">
                    <div class="header-left">
                        <div class="brand-link" @click="goToHome">
                            <img class="brand-logo" src="/static/images/logo-icon.svg" alt="logo" />
                            <span class="logo-text">逐日Coding</span>
                        </div>
                    </div>
                    <nav class="main-nav">
                        <span class="nav-link" :class="{ active: currentNav === 'home' }" @click="goToHome">首页</span>
                        <span class="nav-link" :class="{ active: currentNav === 'pins' }" @click="goToPins">沸点</span>
                        <span class="nav-link" :class="{ active: currentNav === 'course' }" @click="goToCourse">课程</span>
                        <span class="nav-link" @click="handleUnreleasedNav">数据标注</span>
                        <span class="nav-link" @click="handleUnreleasedNav">AI Coding</span>
                        <span class="nav-link more-link" @click="handleUnreleasedNav">更多 <span class="more-arrow">&#9662;</span></span>
                    </nav>
                    <div class="header-center">
                        <div class="web-search-box" ref="searchBox">
                            <input
                                v-model="searchKeyword"
                                type="text"
                                class="web-search-input"
                                placeholder="搜索文章"
                                @focus="onSearchFocus"
                                @input="onSearchInput"
                                @blur="onSearchBlur"
                                @keyup.enter="onSearchSubmit"
                            />
                            <span class="web-search-btn" @click="onSearchSubmit">&#xf002;</span>
                            <span class="web-search-clear" v-if="searchKeyword" @click.stop="clearSearchInput">&#10005;</span>
                            <div class="search-dropdown" v-if="showSearchDropdown" @click.stop>
                                <template v-if="!searchKeyword && searchHistory.length > 0">
                                    <div class="dropdown-header">
                                        <span class="dropdown-title">搜索历史</span>
                                        <span class="dropdown-clear" @click="clearSearchHistory">清空</span>
                                    </div>
                                    <div class="history-tags">
                                        <span class="history-tag" v-for="(item, idx) in searchHistory" :key="idx" @click="doSearch(item)">
                                            {{ item }}
                                            <span class="history-delete" @click.stop="deleteHistoryItem(idx)">&#10005;</span>
                                        </span>
                                    </div>
                                </template>
                                <template v-if="searchKeyword && searchSuggestions.length > 0">
                                    <div class="suggestion-list">
                                        <div class="suggestion-item" v-for="(item, idx) in searchSuggestions" :key="idx" @click="doSearch(item)">
                                            <span class="suggestion-icon">&#xf002;</span>
                                            <span class="suggestion-text" v-html="highlightKeyword(item)"></span>
                                        </div>
                                    </div>
                                </template>
                                <div class="dropdown-empty" v-if="!searchKeyword && searchHistory.length === 0">
                                    <span>暂无搜索历史</span>
                                </div>
                                <div class="dropdown-empty" v-if="searchKeyword && searchSuggestions.length === 0">
                                    <span>暂无相关联想</span>
                                </div>
                            </div>
                        </div>
                    </div>
                    <div class="header-right">
                        <span v-if="!isLoggedIn" class="header-btn write-btn" @click="showLogin">
                            <span class="btn-icon">&#xf040;</span>写文章
                        </span>
                        <div v-if="isLoggedIn" class="header-btn creator-btn"
                        @mouseenter="showCreatorDropdown"
                        @mouseleave="hideCreatorDropdown">
                            <span class="creator-main" @click="openCreatorCenter">
                                <span class="btn-icon">&#xf040;</span>创作者中心
                            </span>
                            <span class="creator-divider"></span>
                            <span class="creator-arrow" :class="{ 'is-reverse': creatorDropdownVisible }">
                                <span class="arrow-icon">&#xf107;</span>
                            </span>
                            <CreatorDropdown
                                v-if="creatorDropdownVisible"
                                :refreshKey="creatorDropdownRefreshKey"
                                @close="hideCreatorDropdown"
                                @navigate="handleCreatorNavigate"
                            />
                        </div>
                        <span v-if="!isLoggedIn" class="header-btn login-btn" @click="showLogin">登录</span>
                        <NotificationBell v-if="isLoggedIn" :unreadTotal="unreadCount" :unreadCounts="unreadCounts" @go-to-notification="goToNotification" />
                        <div v-if="isLoggedIn" class="header-user" @click="toggleUserDropdown">
                            <img v-if="userAvatar" class="header-avatar" :src="userAvatar" alt="头像"/>
                            <span v-else class="header-avatar-default">&#xf007;</span>
                            <span class="header-username">{{ userName }}</span>
                            <UserDropdown
                                v-if="showUserDropdown"
                                :userAvatar="userAvatar"
                                :userName="userName"
                                :levelBadge="levelBadge"
                                :formattedDiamond="formattedDiamond"
                                :levelPercent="levelPercent"
                                :formattedLevelText="formattedLevelText"
                                :stats="stats"
                                @go-profile="goToProfile"
                                @go-growth="goToGrowth"
                                @go-follow="goToFollow"
                                @go-likes="goToLikes"
                                @go-collects="goToCollects"
                                @go-checkin="goToCheckin"
                                @go-courses="goToCourses"
                                @go-history="goToHistory"
                                @my-discount="handleMyDiscount"
                                @go-settings="goToSettings"
                                @logout="handleLogout"
                            />
                        </div>
                    </div>
                </div>
            </div>

            <div class="desktop-container">
                <div class="desktop-sidebar" v-if="!isUserPage && !isPinsPage && !isCoursePage && !isSearchPage">
                    <div class="sidebar-nav">
                        <div class="nav-item" :class="{ active: currentCategory === 'following' }" @click="selectCategory('following')">
                            <span class="nav-icon">&#xf004;</span>
                            <span class="nav-text">关注</span>
                        </div>
                        <div class="nav-item" :class="{ active: currentCategory === 'comprehensive' }" @click="selectCategory('comprehensive')">
                            <span class="nav-icon">&#xf015;</span>
                            <span class="nav-text">综合</span>
                        </div>
                        <div class="nav-item" :class="{ active: currentCategory === 'backend' }" @click="selectCategory('backend')">
                            <span class="nav-icon">&#xf233;</span>
                            <span class="nav-text">后端</span>
                        </div>
                        <div class="nav-item" :class="{ active: currentCategory === 'frontend' }" @click="selectCategory('frontend')">
                            <span class="nav-icon">&#xf121;</span>
                            <span class="nav-text">前端</span>
                        </div>
                        <div class="nav-item" :class="{ active: currentCategory === 'android' }" @click="selectCategory('android')">
                            <span class="nav-icon">&#xf17b;</span>
                            <span class="nav-text">Android</span>
                        </div>
                        <div class="nav-item" :class="{ active: currentCategory === 'ios' }" @click="selectCategory('ios')">
                            <span class="nav-icon">&#xf179;</span>
                            <span class="nav-text">iOS</span>
                        </div>
                        <div class="nav-item" :class="{ active: currentCategory === 'ai' }" @click="selectCategory('ai')">
                            <span class="nav-icon">&#xf2db;</span>
                            <span class="nav-text">人工智能</span>
                        </div>
                        <div class="nav-item" :class="{ active: currentCategory === 'devtools' }" @click="selectCategory('devtools')">
                            <span class="nav-icon">&#xf0ad;</span>
                            <span class="nav-text">开发工具</span>
                        </div>
                        <div class="nav-item" :class="{ active: currentCategory === 'coderslife' }" @click="selectCategory('coderslife')">
                            <span class="nav-icon">&#xf11c;</span>
                            <span class="nav-text">代码人生</span>
                        </div>
                        <div class="nav-item" :class="{ active: currentCategory === 'reading' }" @click="selectCategory('reading')">
                            <span class="nav-icon">&#xf02d;</span>
                            <span class="nav-text">阅读</span>
                        </div>
                        <div class="nav-item" :class="{ active: currentCategory === 'ranking' || isHotPage }" @click="selectCategory('ranking')">
                            <span class="nav-icon">&#xf091;</span>
                            <span class="nav-text">排行榜</span>
                        </div>
                    </div>
                </div>

                <div class="desktop-content" :class="{ 'user-page-content': isUserPage, 'search-page-content': isSearchPage }">
                    <router-view/>
                </div>

                <div class="desktop-aside" v-if="!isUserPage && !isPinsPage && !isCoursePage && !isSearchPage">
                    <!-- 签到入口 -->
                    <div class="aside-card checkin-card">
                        <div class="greeting-section">
                            <span class="greeting-text">{{ greetingText }}</span>
                        </div>
                        <div class="checkin-entry" @click="handleCheckinClick">
                            <div class="checkin-info">
                                <span class="checkin-icon">&#xf058;</span>
                                <span class="checkin-label" v-if="!isLoggedIn">每日签到</span>
                                <span class="checkin-label" v-else>每日签到</span>
                            </div>
                            <span class="checkin-btn" v-if="isLoggedIn && !checkinTodayStatus.isSignedIn">去签到</span>
                            <span class="checkin-btn signed" v-else-if="isLoggedIn && checkinTodayStatus.isSignedIn">已签到</span>
                            <span class="checkin-arrow">&#xf105;</span>
                        </div>
                    </div>

                    <!-- 推荐话题（公共组件） -->
                    <div class="aside-card">
                        <recommend-topics />
                    </div>

                    <div class="aside-footer">
                        <div class="footer-links">关于 · 联系我们 · 加入我们</div>
                        <div class="footer-copy">© 2024 逐日Coding</div>
                    </div>
                </div>
            </div>
        </div>

        <!-- 移动端原有布局 -->
        <template v-else>
            <div class="router-body"><router-view/></div>
            <div class="app-open-btn" @click="handleAppOpen">
                <span class="app-open-icon">&#xf3cd;</span>
                <span class="app-open-text">App内打开</span>
            </div>
        </template>

        </div>
</template>

<script>
    import Utils from '@/utils/env';
    import { toast } from "@/utils/toast"
    import SearchApi from '@/apis/search/api'
    import { sanitizeHighlight } from '@/utils/sanitize'
    import { getUserStatistics } from '@/apis/user'
    import { getTodayStatus, doSignCheckin } from '@/apis/checkin'
    import UserDropdown from '@/components/bars/UserDropdown.vue'
    import NotificationBell from '@/components/bars/NotificationBell.vue'
    import CreatorDropdown from './CreatorDropdown.vue'
    import RecommendTopics from '@/components/RecommendTopics.vue'
    import conf from '@/common/conf'
    import request from '@/common/request'

    var SEARCH_HISTORY_KEY = 'HEIMA_SEARCH_HISTORY'
    var MAX_HISTORY_COUNT = 6
    var MAX_SUGGESTION_COUNT = 10

    function getSearchHistory() {
        try {
            var str = localStorage.getItem(SEARCH_HISTORY_KEY)
            if (str) {
                var arr = JSON.parse(str)
                if (Array.isArray(arr)) {
                    return arr.slice(0, MAX_HISTORY_COUNT)
                }
            }
        } catch (e) {}
        return []
    }

    function saveSearchHistory(list) {
        try {
            localStorage.setItem(SEARCH_HISTORY_KEY, JSON.stringify(list.slice(0, MAX_HISTORY_COUNT)))
        } catch (e) {}
    }

    function addSearchHistory(keyword) {
        if (!keyword || !keyword.trim()) return
        keyword = keyword.trim()
        var list = getSearchHistory()
        var idx = list.indexOf(keyword)
        if (idx !== -1) {
            list.splice(idx, 1)
        }
        list.unshift(keyword)
        saveSearchHistory(list)
    }

    function deleteSearchHistoryByIndex(index) {
        var list = getSearchHistory()
        if (index >= 0 && index < list.length) {
            list.splice(index, 1)
            saveSearchHistory(list)
        }
        return list
    }

    function clearSearchHistoryAll() {
        try {
            localStorage.removeItem(SEARCH_HISTORY_KEY)
        } catch (e) {}
    }

    export default {
        name: "HeiMaLayoutMain",
        components: { UserDropdown, NotificationBell, CreatorDropdown, RecommendTopics },
        data() {
            return {
                showUserDropdown: false,
                creatorDropdownVisible: false,
                creatorDropdownRefreshKey: 0,
                _creatorHideTimer: null,
                searchKeyword: '',
                showSearchDropdown: false,
                searchHistory: [],
                searchSuggestions: [],
                searchTimer: null,
                _blurTimer: null,
                currentCategory: 'comprehensive',
                currentNav: 'home',
                stats: {
                    followCount: 0,
                    likeCount: 0,
                    collectCount: 0
                },
                diamondCount: '0',
                levelBadge: 'ZR.1',
                levelScore: 0,
                levelMax: 150,
                levelPercent: 0,
                checkinTodayStatus: {
                    isSignedIn: false,
                    consecutiveDays: 0,
                    totalOre: 0
                },
                unreadCount: 0,
                unreadCounts: { comment: 0, digg: 0, follow: 0, system: 0 },
                unreadTimer: null
            }
        },
        computed: {
            isDesktop() {
                return Utils.isDesktop()
            },
            isLoggedIn() {
                return this.$store.getters.isLoggedIn
            },
            userInfo() {
                return this.$store.getters.userInfo
            },
            userName() {
                return this.userInfo ? (this.userInfo.nickName || '用户') : '用户'
            },
            userAvatar() {
                if (this.userInfo && this.userInfo.avatar) {
                    return this.userInfo.avatar
                }
                return ''
            },
            formattedDiamond() {
                const count = parseFloat(this.diamondCount)
                if (isNaN(count)) return '0'
                if (count >= 1000) {
                    return (count / 1000).toFixed(1) + 'k'
                }
                return String(Math.floor(count))
            },
            formattedLevelText() {
                return this.levelScore + ' / ' + this.levelMax
            },
            isUserPage() {
                return this.$route.path.startsWith('/user/') || this.$route.path === '/notification';
            },
            isSearchPage() {
                return this.$route.path.startsWith('/search_result');
            },
            isPinsPage() {
                return this.$route.path.startsWith('/pins') || this.$route.path.startsWith('/pin/');
            },
            isCoursePage() {
                return this.$route.path.startsWith('/course');
            },
            isHotPage() {
                return this.$route.path === '/hot'
            },
            greetingText() {
                if (this.isLoggedIn && this.checkinTodayStatus && this.checkinTodayStatus.isSignedIn) {
                    return '连续签到 ' + this.checkinTodayStatus.consecutiveDays + ' 天'
                }
                const hour = new Date().getHours()
                if (hour < 6) return '凌晨好'
                if (hour < 12) return '上午好'
                if (hour < 14) return '中午好'
                if (hour < 18) return '下午好'
                return '晚上好'
            }
        },
        mounted() {
            this.syncCategoryFromRoute()
            this.handleResize = () => {
                this.$forceUpdate()
            }
            window.addEventListener('resize', this.handleResize)
            this.closeDropdown = (e) => {
                if (this.showUserDropdown) {
                    var userEl = this.$el.querySelector('.header-user')
                    if (userEl && !userEl.contains(e.target)) {
                        this.showUserDropdown = false
                    }
                }
                if (this.creatorDropdownVisible) {
                    var creatorEl = this.$el.querySelector('.creator-btn')
                    if (creatorEl && !creatorEl.contains(e.target)) {
                        this.creatorDropdownVisible = false
                    }
                }
                if (this.showSearchDropdown) {
                    var searchEl = this.$refs.searchBox
                    if (searchEl && !searchEl.contains(e.target)) {
                        this.showSearchDropdown = false
                    }
                }
            }
            document.addEventListener('click', this.closeDropdown)
            this.searchHistory = getSearchHistory()
            this.loadCheckinStatus()
            this.fetchUnreadCount()
            this.unreadTimer = setInterval(() => this.fetchUnreadCount(), 30000)
            // 站内信按类型清除未读后，立即刷新未读总数与分类型计数
            this.onUnreadCleared = () => this.fetchUnreadCount()
            window.addEventListener('notification-unread-cleared', this.onUnreadCleared)
        },
        watch: {
            '$route.path': function(newPath) {
                this.syncCategoryFromRoute()
                // 回到首页时刷新签到状态，确保"已签到/去签到"按钮与最新签到状态一致
                if (newPath === '/home' || newPath === '/') {
                    this.loadCheckinStatus()
                    this.fetchUnreadCount()
                }
            },
            isLoggedIn: function(newVal) {
                if (newVal) {
                    this.loadCheckinStatus()
                    this.fetchUnreadCount()
                } else {
                    this.checkinTodayStatus = { isSignedIn: false, consecutiveDays: 0, totalOre: 0 }
                    this.unreadCount = 0
                }
            }
        },
        beforeDestroy() {
            window.removeEventListener('resize', this.handleResize)
            document.removeEventListener('click', this.closeDropdown)
            if (this.onUnreadCleared) {
                window.removeEventListener('notification-unread-cleared', this.onUnreadCleared)
            }
            if (this.unreadTimer) {
                clearInterval(this.unreadTimer)
                this.unreadTimer = null
            }
            if (this.searchTimer) {
                clearTimeout(this.searchTimer)
                this.searchTimer = null
            }
            if (this._blurTimer) {
                clearTimeout(this._blurTimer)
                this._blurTimer = null
            }
            if (this._creatorHideTimer) {
                clearTimeout(this._creatorHideTimer)
                this._creatorHideTimer = null
            }
        },
        methods: {
            showLogin() {
                this.$store.dispatch('showLogin')
            },
            handleAppOpen() {
                toast('未发布App产品，敬请期待', 2)
            },
            // 未上线导航入口：toast 提示，避免死链
            handleUnreleasedNav() {
                toast('该功能即将上线，敬请期待', 2)
            },
            toggleUserDropdown(e) {
                if (this.isLoggedIn) {
                    e.stopPropagation();
                    this.showUserDropdown = !this.showUserDropdown
                    if (this.showUserDropdown) {
                        this.loadUserStats()
                    }
                } else {
                    this.showLogin()
                }
            },
            async loadUserStats() {
                try {
                    const res = await getUserStatistics()
                    if (res && res.code === 200 && res.data) {
                        const data = res.data
                        this.stats.followCount = data.followCount || 0
                        this.stats.likeCount = data.likeCount || 0
                        this.stats.collectCount = data.collectCount || 0
                        this.diamondCount = data.diamondCount || '0'
                        if (data.levelInfo) {
                            const li = data.levelInfo
                            this.levelBadge = 'ZR.' + (li.dailyLevel || 1)
                            this.levelScore = li.dailyScore || 0
                            const levelMaxMap = { 1: 150, 2: 300, 3: 500, 4: 800, 5: 1200 }
                            this.levelMax = levelMaxMap[li.dailyLevel] || 150
                            const levelBaseMap = { 1: 0, 2: 150, 3: 300, 4: 500, 5: 800 }
                            const base = levelBaseMap[li.dailyLevel] || 0
                            const currentInLevel = this.levelScore - base
                            this.levelPercent = Math.min(Math.round(currentInLevel / this.levelMax * 100), 100)
                        }
                    }
                } catch (e) {
                    // Silently fail, use defaults
                }
            },
            goToProfile() {
                this.showUserDropdown = false
                const userId = this.userInfo && this.userInfo.userId ? this.userInfo.userId : 1
                this.$router.push('/user/' + userId)
            },
            goToSettings() {
                this.showUserDropdown = false
                this.$router.push('/user/settings')
            },
            goToGrowth() {
                this.showUserDropdown = false
                this.$router.push('/user/center/growth')
            },
            goToCheckin() {
                this.showUserDropdown = false
                this.$router.push('/user/center/checkin')
            },
            goToCourses() {
                this.showUserDropdown = false
                this.$router.push('/user/courses')
            },
            goToHistory() {
                this.showUserDropdown = false
                this.$router.push('/user/history')
            },
            goToFollow() {
                this.showUserDropdown = false
                const userId = this.userInfo && this.userInfo.userId ? this.userInfo.userId : 1
                this.$router.push('/user/' + userId + '?tab=follow&subTab=following')
            },
            goToLikes() {
                this.showUserDropdown = false
                const userId = this.userInfo && this.userInfo.userId ? this.userInfo.userId : 1
                this.$router.push('/user/' + userId + '?tab=likes&subTab=article')
            },
            goToCollects() {
                this.showUserDropdown = false
                const userId = this.userInfo && this.userInfo.userId ? this.userInfo.userId : 1
                this.$router.push('/user/' + userId + '?tab=collection')
            },
            handleMyDiscount() {
                this.showUserDropdown = false
                toast('我的优惠功能开发中', 2)
            },
            handleLogout() {
                this.showUserDropdown = false
                this.creatorDropdownVisible = false
                this.$store.dispatch('logout')
                toast('已退出登录', 2)
                this.$router.push('/home')
            },
            openCreatorCenter() {
                this.$router.push('/creator/dashboard')
            },
            toggleCreatorDropdown() {
                this.creatorDropdownVisible = !this.creatorDropdownVisible
            },
            showCreatorDropdown() {
                if (this._creatorHideTimer) {
                    clearTimeout(this._creatorHideTimer)
                    this._creatorHideTimer = null
                }
                this.creatorDropdownRefreshKey++
                this.creatorDropdownVisible = true
            },
            hideCreatorDropdown() {
                if (this._creatorHideTimer) {
                    clearTimeout(this._creatorHideTimer)
                }
                this._creatorHideTimer = setTimeout(() => {
                    this.creatorDropdownVisible = false
                    this._creatorHideTimer = null
                }, 100)
            },
            handleCreatorNavigate(path, isNewWindow) {
                this.creatorDropdownVisible = false
                if (isNewWindow) {
                    window.open(path, '_blank')
                } else {
                    this.$router.push(path)
                }
            },
            goHome() {
                if (this.$route.path !== '/home') {
                    this.$router.push('/home')
                }
            },
            goToHome() {
                this.currentNav = 'home'
                this.searchKeyword = ''
                this.$router.push('/home')
                this.$nextTick(() => {
                    this.loadCheckinStatus()
                    this.fetchUnreadCount()
                })
            },
            goToPins() {
                this.currentNav = 'pins'
                this.searchKeyword = ''
                this.$router.push('/pins')
            },
            goToCourse() {
                this.currentNav = 'course'
                this.searchKeyword = ''
                this.$router.push('/course')
            },
            selectCategory(category) {
                this.currentCategory = category
                this.searchKeyword = ''
                if (category === 'ranking') {
                    this.currentNav = 'home'
                    this.$router.push('/hot')
                    return
                }
                if (category === 'following') {
                    if (!this.isLoggedIn) {
                        this.showLogin()
                        return
                    }
                    this.currentNav = 'home'
                    this.$router.push('/home/following')
                } else if (category === 'comprehensive') {
                    this.currentNav = 'home'
                    this.$router.push('/home')
                } else {
                    this.currentNav = 'home'
                    this.$router.push(`/home/${category}`)
                }
            },
            syncCategoryFromRoute() {
                var path = this.$route.path
                if (path.indexOf('/search_result') === 0) {
                    this.currentNav = ''
                    return
                }
                if (path === '/hot') {
                    this.currentCategory = 'ranking'
                    this.currentNav = 'home'
                } else if (path === '/home/following') {
                    this.currentCategory = 'following'
                    this.currentNav = 'home'
                } else if (path === '/home') {
                    this.currentCategory = 'comprehensive'
                    this.currentNav = 'home'
                } else if (path.indexOf('/home/') === 0) {
                    var cat = path.replace('/home/', '')
                    if (cat) {
                        this.currentCategory = cat
                    }
                    this.currentNav = 'home'
                } else if (path === '/pins') {
                    // 沸点广场：保持"沸点"指示
                    this.currentNav = 'pins'
                } else if (path.indexOf('/pins/circle') === 0 || path.indexOf('/pin/topic') === 0) {
                    // 话题广场、话题详情、圈子广场、圈子详情、标签页：不指示上边栏"首页/沸点"选项
                    this.currentNav = ''
                } else if (path.indexOf('/course') === 0) {
                    this.currentNav = 'course'
                }
            },
            onSearchFocus() {
                if (this._blurTimer) {
                    clearTimeout(this._blurTimer)
                    this._blurTimer = null
                }
                this.searchHistory = getSearchHistory()
                this.showSearchDropdown = true
            },
            onSearchInput() {
                var val = this.searchKeyword
                if (this.searchTimer) {
                    clearTimeout(this.searchTimer)
                }
                if (!val || !val.trim()) {
                    this.searchSuggestions = []
                    this.searchHistory = getSearchHistory()
                    this.showSearchDropdown = true
                    return
                }
                this.searchTimer = setTimeout(() => {
                    this.loadSearchSuggestions(val.trim())
                }, 300)
            },
            onSearchBlur() {
                this._blurTimer = setTimeout(() => {
                    this.showSearchDropdown = false
                }, 200)
            },
            onSearchSubmit() {
                var val = this.searchKeyword
                if (val && val.trim()) {
                    this.doSearch(val.trim())
                }
            },
            clearSearchInput() {
                this.searchKeyword = ''
                this.searchSuggestions = []
                this.searchHistory = getSearchHistory()
                var input = this.$el.querySelector('.web-search-input')
                if (input) input.focus()
            },
            loadSearchSuggestions(keyword) {
                var self = this
                SearchApi.associate_search(keyword).then(function (data) {
                    if (data && data.code === 200 && data.data) {
                        self.searchSuggestions = (data.data || []).map(function (item) {
                            return item && item.associateWords ? item.associateWords : item
                        }).slice(0, MAX_SUGGESTION_COUNT)
                    } else {
                        self.searchSuggestions = []
                    }
                    self.showSearchDropdown = true
                }).catch(function () {
                    self.searchSuggestions = []
                })
            },
            highlightKeyword(text) {
                var kw = this.searchKeyword
                if (!kw) return text
                try {
                    var reg = new RegExp('(' + kw.replace(/[.*+?^${}()|[\]\\]/g, '\\$&') + ')', 'gi')
                    var highlighted = text.replace(reg, '<em style="color:#3194ff;font-style:normal">$1</em>')
                    return sanitizeHighlight(highlighted)
                } catch (e) {
                    return text
                }
            },
            deleteHistoryItem(index) {
                this.searchHistory = deleteSearchHistoryByIndex(index)
            },
            clearSearchHistory() {
                clearSearchHistoryAll()
                this.searchHistory = []
                toast('已清空搜索历史', 2)
            },
            doSearch(keyword) {
                if (!keyword || !keyword.trim()) return
                keyword = keyword.trim()
                addSearchHistory(keyword)
                this.searchKeyword = keyword
                this.showSearchDropdown = false
                this.searchSuggestions = []
                this.$router.push({ name: 'search_result', query: { keyword: keyword } })
            },
            async loadCheckinStatus() {
                if (!this.isLoggedIn) {
                    this.checkinTodayStatus = { isSignedIn: false, consecutiveDays: 0, totalOre: 0 }
                    return
                }
                try {
                    const res = await getTodayStatus()
                    if (res && res.code === 200) {
                        this.checkinTodayStatus = res.data || { isSignedIn: false, consecutiveDays: 0, totalOre: 0 }
                    }
                } catch (e) {
                    console.error('加载签到状态失败', e)
                }
            },
            handleCheckinClick() {
                if (!this.isLoggedIn) {
                    this.showLogin()
                    return
                }
                this.$router.push('/user/center/checkin')
            },
            goToNotification(type = 'comment') {
                this.$router.push('/notification?tab=' + type)
            },
            fetchUnreadCount() {
                if (!this.isLoggedIn) return
                request.get(conf.urls.get('notifications_unread'), {}).then(d => {
                    if (d && d.code === 200 && d.data) {
                        this.unreadCount = d.data.total || 0
                        this.unreadCounts = {
                            comment: d.data.comment || 0,
                            digg: d.data.digg || 0,
                            follow: d.data.follow || 0,
                            system: d.data.system || 0
                        }
                    }
                }).catch(() => {})
            }
        }
    };
</script>

<style lang="less" scoped>
    @import '../../styles/common';

    .main-root {
        width: 100%;
        height: 100%;
        min-height: 100vh;
        background-color: #f5f5f5;
        position: relative;
    }

    .router-body {
        width: 100%;
        height: 100%;
        min-height: 100vh;
        padding-bottom: 100px;
        box-sizing: border-box;
    }

    .app-open-btn {
        position: fixed;
        bottom: 20px;
        left: 50%;
        transform: translateX(-50%);
        display: flex;
        align-items: center;
        gap: 8px;
        padding: 12px 28px;
        background: linear-gradient(135deg, #1e80ff, #4096ff);
        border-radius: 28px;
        box-shadow: 0 4px 16px rgba(30, 128, 255, 0.4);
        cursor: pointer;
        z-index: 99;
        transition: transform 0.2s, box-shadow 0.2s;
    }
    .app-open-btn:active {
        transform: translateX(-50%) scale(0.95);
        box-shadow: 0 2px 8px rgba(30, 128, 255, 0.3);
    }
    .app-open-icon {
        font-family: fontawesome;
        font-size: 22px;
        color: #fff;
    }
    .app-open-text {
        font-size: 16px;
        color: #fff;
        font-weight: 500;
    }

    /* ========== Web端样式 ========== */
    @media screen and (min-width: 768px) {
        .main-root {
            background-color: #f4f5f5;
            min-height: 100vh;
        }

        .router-body, .app-open-btn {
            display: none;
        }

        .desktop-layout {
            width: 100%;
            min-height: 100vh;
            display: flex;
            flex-direction: column;
        }

        .desktop-header {
            height: 60PX;
            background-color: #ffffff;
            box-shadow: 0 1PX 3PX rgba(0,0,0,0.08);
            position: fixed;
            top: 0;
            left: 0;
            right: 0;
            z-index: 100;
            overflow: visible;
        }

        .header-inner {
            max-width: 1440PX;
            margin: 0 auto;
            height: 60PX;
            overflow: visible;
            display: flex;
            align-items: center;
            padding: 0 24PX;
            box-sizing: border-box;
            justify-content: space-between;
        }

        .header-left {
            width: 180PX;
            flex-shrink: 0;
        }

        .brand-link {
            display: flex;
            align-items: center;
            gap: 10PX;
            cursor: pointer;
            user-select: none;
            transition: opacity 0.2s;
        }
        .brand-link:hover {
            opacity: 0.85;
        }
        .brand-logo {
            width: 32PX;
            height: 32PX;
            flex-shrink: 0;
        }
        .logo-text {
            font-size: 22PX;
            font-weight: 700;
            color: @mian-color;
            white-space: nowrap;
        }

        .main-nav {
            display: flex;
            align-items: center;
            gap: 0;
            flex-shrink: 0;
            margin: 0 24PX;
        }

        .nav-link {
            padding: 0 12PX;
            font-size: 14PX;
            color: #515767;
            cursor: pointer;
            white-space: nowrap;
            transition: color 0.2s;
            line-height: 60PX;
            position: relative;
        }
        .nav-link:hover {
            color: #1E80FF;
        }
        .nav-link.active {
            color: #1E80FF;
        }
        .nav-link.active::after {
            content: '';
            position: absolute;
            bottom: 0;
            left: 50%;
            transform: translateX(-50%);
            width: 20PX;
            height: 2PX;
            background-color: #1E80FF;
            border-radius: 1PX;
        }

        .more-link {
            display: flex;
            align-items: center;
            gap: 4PX;
        }
        .more-arrow {
            font-size: 10PX;
            margin-top: 2PX;
        }

        .header-center {
            flex: 0;
            width: 220PX;
            margin: 0 16PX;
        }

        .web-search-box {
            position: relative;
            display: flex;
            align-items: center;
            height: 40PX;
            background-color: #f4f5f5;
            border-radius: 20PX;
            padding: 0 16PX;
            width: 100%;
            box-sizing: border-box;
            transition: background-color 0.2s;
        }

        .header-right {
            display: flex;
            align-items: center;
            gap: 12PX;
            flex-shrink: 0;
        }
        .web-search-box:focus-within {
            background-color: #ffffff;
            box-shadow: 0 0 0 2PX rgba(49,148,255,0.2);
        }
        .web-search-input {
            flex: 1;
            height: 100%;
            border: none;
            outline: none;
            background-color: transparent;
            font-size: 14PX;
            color: #333;
            min-width: 0;
        }
        .web-search-input::placeholder {
            color: #999;
        }
        .web-search-clear {
            width: 20PX;
            height: 20PX;
            display: flex;
            align-items: center;
            justify-content: center;
            font-size: 12PX;
            color: #999;
            cursor: pointer;
            border-radius: 50%;
            flex-shrink: 0;
            margin-left: 8PX;
        }
        .web-search-clear:hover {
            background-color: #e8e8e8;
            color: #666;
        }
        .web-search-btn {
            font-family: fontawesome;
            width: 32PX;
            height: 32PX;
            display: flex;
            align-items: center;
            justify-content: center;
            font-size: 15PX;
            color: #999;
            cursor: pointer;
            flex-shrink: 0;
            border-radius: 50%;
            transition: color 0.2s;
        }
        .web-search-btn:hover {
            color: #1E80FF;
        }

        .search-dropdown {
            position: absolute;
            top: calc(100% + 8PX);
            left: 0;
            right: 0;
            background-color: #ffffff;
            border-radius: 8PX;
            box-shadow: 0 4PX 20PX rgba(0,0,0,0.12);
            z-index: 300;
            overflow: hidden;
            max-height: 400PX;
            overflow-y: auto;
        }

        .dropdown-header {
            display: flex;
            align-items: center;
            justify-content: space-between;
            padding: 12PX 16PX;
            border-bottom: 1PX solid #f0f0f0;
        }
        .dropdown-title {
            font-size: 13PX;
            color: #999;
        }
        .dropdown-clear {
            font-size: 13PX;
            color: #999;
            cursor: pointer;
        }
        .dropdown-clear:hover {
            color: @mian-color;
        }

        .history-tags {
            padding: 12PX 16PX;
            display: flex;
            flex-wrap: wrap;
            gap: 8PX;
        }
        .history-tag {
            display: inline-flex;
            align-items: center;
            gap: 6PX;
            padding: 6PX 12PX;
            background-color: #f5f7fa;
            border-radius: 4PX;
            font-size: 13PX;
            color: #333;
            cursor: pointer;
            transition: all 0.2s;
        }
        .history-tag:hover {
            background-color: #e8f4ff;
            color: @mian-color;
        }
        .history-delete {
            font-size: 11PX;
            color: #c0c4cc;
            width: 16PX;
            height: 16PX;
            display: flex;
            align-items: center;
            justify-content: center;
            border-radius: 50%;
        }
        .history-delete:hover {
            background-color: rgba(0,0,0,0.05);
            color: #999;
        }

        .suggestion-list {
            padding: 4PX 0;
        }
        .suggestion-item {
            display: flex;
            align-items: center;
            padding: 10PX 16PX;
            cursor: pointer;
            transition: background-color 0.2s;
            gap: 10PX;
        }
        .suggestion-item:hover {
            background-color: #f5f7fa;
        }
        .suggestion-icon {
            font-family: fontawesome;
            font-size: 13PX;
            color: #c0c4cc;
            flex-shrink: 0;
        }
        .suggestion-text {
            font-size: 14PX;
            color: #333;
            flex: 1;
            overflow: hidden;
            text-overflow: ellipsis;
            white-space: nowrap;
        }

        .dropdown-empty {
            padding: 30PX 16PX;
            text-align: center;
            font-size: 13PX;
            color: #c0c4cc;
        }

        .header-right {
            width: 180PX;
            min-width: 180PX;
            flex-shrink: 0;
            display: flex;
            align-items: center;
            justify-content: flex-end;
            gap: 12PX;
        }

        .header-btn {
            padding: 6PX 16PX;
            border-radius: 4PX;
            font-size: 14PX;
            cursor: pointer;
            white-space: nowrap;
            transition: all 0.2s;
        }

        .write-btn {
            color: #333;
            background-color: #f4f5f5;
        }
        .write-btn:hover {
            background-color: #e8e8e8;
        }

        .creator-btn {
            color: #ffffff;
            background-color: @mian-color;
            position: relative;
            display: inline-flex;
            align-items: center;
            padding: 6px 0;
            border-radius: 4px;
            overflow: visible;
            transition: background-color 0.2s;
        }
        .creator-btn:hover {
            background-color: #1a7de8;
        }
        .creator-main {
            padding: 0 16px;
            cursor: pointer;
            display: flex;
            align-items: center;
            font-size: 14px;
        }
        .creator-divider {
            width: 1px;
            height: 16px;
            background-color: rgba(255, 255, 255, 0.3);
            flex-shrink: 0;
        }
        .creator-arrow {
            padding: 0 12px;
            cursor: pointer;
            display: flex;
            align-items: center;
            justify-content: center;
            transition: background-color 0.2s;
            border-radius: 0 4px 4px 0;
        }
        .creator-arrow:hover,
        .creator-arrow.is-reverse {
            background-color: rgba(0, 0, 0, 0.15);
        }
        .arrow-icon {
            font-family: fontawesome;
            font-size: 10px;
            display: inline-block;
            transition: transform 0.2s ease;
        }
        .creator-arrow.is-reverse .arrow-icon {
            transform: rotate(180deg);
        }
        .btn-icon {
            font-family: fontawesome;
            margin-right: 4PX;
        }

        .login-btn {
            color: #ffffff;
            background-color: @mian-color;
        }
        .login-btn:hover {
            background-color: #1a7de8;
        }

        .header-user {
            display: flex;
            align-items: center;
            gap: 8PX;
            cursor: pointer;
        }

        .header-avatar {
            width: 32PX;
            height: 32PX;
            border-radius: 50%;
            object-fit: cover;
        }
        .header-avatar-default {
            width: 32PX;
            height: 32PX;
            border-radius: 50%;
            background-color: @mian-color;
            color: #fff;
            display: flex;
            align-items: center;
            justify-content: center;
            font-family: fontawesome;
            font-size: 16PX;
        }
        .header-username {
            font-size: 14PX;
            color: #333;
            max-width: 80PX;
            overflow: hidden;
            text-overflow: ellipsis;
            white-space: nowrap;
        }

        .header-user {
            position: relative;
        }

        

        .desktop-container {
            max-width: 1280PX;
            margin: 0 auto;
            padding: 80PX 24PX 24PX;
            display: flex;
            gap: 20PX;
            box-sizing: border-box;
            width: 100%;
        }

        .desktop-sidebar {
            width: 220PX;
            flex-shrink: 0;
            position: sticky;
            top: 80PX;
            height: fit-content;
        }

        .sidebar-nav {
            background-color: #ffffff;
            border-radius: 8PX;
            padding: 8PX 0;
        }

        .nav-item {
            display: flex;
            align-items: center;
            padding: 9PX 20PX;
            cursor: pointer;
            font-size: 15PX;
            color: #333;
            transition: all 0.2s;
            gap: 12PX;
        }
        .nav-item:hover {
            background-color: #f5f5f5;
        }
        .nav-item.active {
            color: #1E80FF;
            font-weight: 500;
            background-color: #E8F3FF;
        }
        .nav-icon {
            font-family: fontawesome;
            font-size: 18PX;
            width: 20PX;
            text-align: center;
        }
        .nav-text {
            flex: 1;
        }

        .desktop-content {
            flex: 1;
            min-width: 0;
            max-width: none;
        }

        .user-page-content {
            max-width: 100%;
        }

        .search-page-content {
            max-width: 700PX;
            margin: 0 auto;
        }

        .desktop-aside {
            width: 280PX;
            flex-shrink: 0;
            position: sticky;
            top: 80PX;
            height: fit-content;
        }

        .aside-card {
            background-color: #ffffff;
            border-radius: 8PX;
            padding: 16PX 20PX;
            margin-bottom: 12PX;
        }

        .aside-title {
            font-size: 15PX;
            font-weight: 600;
            color: #333;
            margin-bottom: 12PX;
            padding-bottom: 8PX;
            border-bottom: 1PX solid #f0f0f0;
        }

        /* ===== 签到入口卡片 ===== */
        .checkin-card {
            padding: 16PX 20PX;
        }
        .greeting-section {
            margin-bottom: 12PX;
        }
        .greeting-text {
            font-size: 20PX;
            color: #333;
            font-weight: 600;
        }
        .checkin-entry {
            display: flex;
            align-items: center;
            justify-content: space-between;
            cursor: pointer;
            transition: opacity 0.2s;
        }
        .checkin-entry:hover {
            opacity: 0.8;
        }
        .checkin-info {
            display: flex;
            align-items: center;
            gap: 10PX;
        }
        .checkin-icon {
            font-family: fontawesome;
            font-size: 22PX;
            color: #FFB800;
        }
        .checkin-label {
            font-size: 15PX;
            color: #333;
            font-weight: 500;
        }
        .checkin-label.signed {
            color: #1E80FF;
        }
        .checkin-btn {
            padding: 4PX 12PX;
            background: linear-gradient(135deg, #1e80ff, #4096ff);
            color: #fff;
            border-radius: 12PX;
            font-size: 12PX;
            font-weight: 500;
        }
        .checkin-btn.signed {
            background: linear-gradient(135deg, #52c41a, #73d13d);
        }
        .checkin-arrow {
            font-family: fontawesome;
            font-size: 16PX;
            color: #c0c4cc;
        }
        .checkin-extra {
            margin-top: 8PX;
            padding-left: 32PX;
        }
        .ore-text {
            font-size: 12PX;
            color: #999;
        }

        .aside-footer {
            padding: 12PX 16PX;
            text-align: center;
        }

        .footer-links {
            font-size: 12PX;
            color: #bbb;
            margin-bottom: 6PX;
        }

        .footer-copy {
            font-size: 12PX;
            color: #ddd;
        }
    }

    /* 1024px-1279px：隐藏左侧边栏 */
    @media screen and (min-width: 1024PX) and (max-width: 1279PX) {
        .desktop-sidebar {
            display: none;
        }
        .desktop-aside {
            width: 280PX;
        }
        .header-inner {
            padding: 0 20PX;
        }
        .desktop-container {
            padding: 80PX 20PX 24PX;
        }
    }

    /* 768px-1023px：隐藏左右两侧边栏 */
    @media screen and (min-width: 768PX) and (max-width: 1023PX) {
        .desktop-sidebar {
            display: none;
        }
        .desktop-aside {
            display: none;
        }
        .header-inner {
            padding: 0 16PX;
        }
        .desktop-container {
            padding: 80PX 16PX 24PX;
        }
        .main-nav {
            margin: 0 12PX;
        }
        .nav-link {
            padding: 0 8PX;
            font-size: 13PX;
        }
    }

    /* 更窄屏Web端适配：左侧导航缩为图标 */
    @media screen and (min-width: 768PX) and (max-width: 899PX) {
        .desktop-sidebar {
            width: 60PX;
        }
        .nav-text {
            display: none;
        }
        .nav-item {
            padding: 12PX 0;
            justify-content: center;
        }
        .header-center {
            margin: 0 16PX;
        }
        .header-left {
            width: auto;
        }
        .brand-logo {
            width: 24PX;
            height: 24PX;
        }
        .logo-text {
            font-size: 16PX;
        }
        .header-right {
            width: auto;
            gap: 8PX;
        }
        .write-btn {
            display: none;
        }
    }
</style>
