<template>
    <div class="bar_bg">
        <span class="bar-icon menu-icon">&#xf0c9;</span>
        <Search class="search-comp" type="search" @onClick="onClick" :icon="icon" :height="56" :left-width="15" :right-width="15" placeholder="搜索文章"/>
        <span class="bar-icon login-btn" v-if="!isLoggedIn" @click="showLogin">&#xf007;</span>
        <div class="user-info" v-if="isLoggedIn">
            <div class="bell-wrapper">
                <NotificationBell
                    :unreadTotal="unreadCount"
                    :unreadCounts="unreadCounts"
                    @go-to-notification="goToNotification"
                />
            </div>
            <div class="avatar-wrapper" @click="toggleUserDropdown">
                <img v-if="userAvatar" class="user-avatar" :src="userAvatar" alt="头像" />
                <span v-else class="bar-icon user-btn">&#xf007;</span>
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
</template>

<script>
    import Search from '@/components/inputs/search_buttion';
    import UserDropdown from './UserDropdown.vue'
    import NotificationBell from './NotificationBell.vue'
    import { toast } from "@/utils/toast"
    import { getUserStatistics } from '@/apis/user'
    import request from '@/common/request'
    import conf from '@/common/conf'

    export default {
        name: "HomeBar",
        components: { Search, UserDropdown, NotificationBell },
        data:()=>{
            return {
                icon:'\uF002',
                showUserDropdown: false,
                unreadCount: 0,
                unreadCounts: { comment: 0, digg: 0, follow: 0, system: 0 },
                unreadTimer: null,
                stats: {
                    followCount: 0,
                    likeCount: 0,
                    collectCount: 0
                },
                levelScore: 0,
                levelMax: 150,
                levelPercent: 0,
                diamondCount: '0',
                levelBadge: 'ZR.1'
            }
        },
        computed: {
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
            }
        },
        methods: {
            onClick : function(){
                this.$router.push('/search')
            },
            showLogin() {
                this.$store.dispatch('showLogin')
            },
            toggleUserDropdown() {
                if (this.isLoggedIn) {
                    this.showUserDropdown = !this.showUserDropdown
                    if (this.showUserDropdown) {
                        this.loadUserStats()
                    }
                } else {
                    this.$store.dispatch('showLogin')
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
                        // 注意：接口返回的等级字段位于顶层（levelBadge/levelScore/levelMax/levelPercent/dailyLevel/dailyScore）
                        // 后端 getUserLevelData 已基于真实等级配置计算好 levelMax 与 levelPercent，前端直接使用即可
                        this.levelBadge = data.levelBadge || 'ZR.' + (data.dailyLevel || 1)
                        this.levelScore = data.levelScore || 0
                        this.levelMax = data.levelMax || 150
                        this.levelPercent = Math.min(data.levelPercent || 0, 100)
                    }
                } catch (e) {
                    // Silently fail, use defaults
                }
            },
            handleLogout() {
                this.showUserDropdown = false
                this.$store.dispatch('logout')
                toast('已退出登录', 2)
                this.$router.push('/home')
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
            goToNotification(type) {
                this.showUserDropdown = false
                this.$router.push('/notification?tab=' + (type || ''))
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
        },
        watch: {
            isLoggedIn(newVal) {
                if (!newVal) {
                    this.unreadCount = 0
                }
            }
        },
        mounted() {
            this.fetchUnreadCount()
            this.unreadTimer = setInterval(() => this.fetchUnreadCount(), 30000)
            this.closeDropdown = (e) => {
                if (this.showUserDropdown) {
                    const avatarEl = this.$el.querySelector('.avatar-wrapper')
                    if (avatarEl && !avatarEl.contains(e.target)) {
                        this.showUserDropdown = false
                    }
                }
            }
            this.escClose = (e) => {
                if (e.key === 'Escape' && this.showUserDropdown) {
                    this.showUserDropdown = false
                }
            }
            document.addEventListener('click', this.closeDropdown)
            document.addEventListener('keydown', this.escClose)
            this.onUnreadCleared = () => this.fetchUnreadCount()
            window.addEventListener('notification-unread-cleared', this.onUnreadCleared)
        },
        beforeDestroy() {
            if (this.unreadTimer) {
                clearInterval(this.unreadTimer)
                this.unreadTimer = null
            }
            if (this.onUnreadCleared) {
                window.removeEventListener('notification-unread-cleared', this.onUnreadCleared)
            }
            document.removeEventListener('click', this.closeDropdown)
            document.removeEventListener('keydown', this.escClose)
        }
    };
</script>

<style lang="less" scoped>
    @import '../../styles/common';

    .bar_bg{
        width: 100%;
        display: flex;
        flex-direction: row;
        align-items: center;
        background-color: @mian-color;
        height: @top-height;
        padding: 0 15px;
        box-sizing: border-box;
        gap: 8px;
    }
    .bar-icon{
        width: 48px;
        height: 48px;
        display: flex;
        align-items: center;
        justify-content: center;
        color: #ffffff;
        font-family: fontawesome;
        font-size: 34px;
        text-align: center;
        flex-shrink: 0;
    }
    .menu-icon {
        font-size: 36px;
    }
    .login-btn, .user-btn {
        cursor: pointer;
    }
    .search-comp {
        flex: 1;
        min-width: 0;
    }
    .user-info {
        display: flex;
        align-items: center;
        gap: 12px;
        flex-shrink: 0;
        position: relative;
    }
    .bell-wrapper {
        display: inline-flex;
        align-items: center;
    }
    // 深色背景下覆盖 NotificationBell 组件样式
    .bell-wrapper ::v-deep(.notification-bell) {
        background-color: transparent;
    }
    .bell-wrapper ::v-deep(.notification-bell:hover) {
        background-color: rgba(255,255,255,0.15);
    }
    .bell-wrapper ::v-deep(.bell-icon) {
        color: #ffffff;
    }
    .bell-wrapper ::v-deep(.unread-badge) {
        background: #ff4d4f;
        color: #fff;
    }
    .avatar-wrapper {
        position: relative;
        display: flex;
        align-items: center;
        cursor: pointer;
    }
    .user-avatar {
        width: 44px;
        height: 44px;
        border-radius: 50%;
        border: 2px solid rgba(255,255,255,0.5);
        object-fit: cover;
    }
</style>