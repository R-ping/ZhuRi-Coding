<template>
    <div class="bar_bg">
        <span class="bar-icon menu-icon">&#xf0c9;</span>
        <Search class="search-comp" type="search" @onClick="onClick" :icon="icon" :height="56" :left-width="15" :right-width="15" placeholder="搜索文章"/>
        <span class="bar-icon login-btn" v-if="!isLoggedIn" @click="showLogin">&#xf007;</span>
        <div class="user-info" v-if="isLoggedIn" @click="toggleUserDropdown">
            <div class="notification-bell" @click.stop="goToNotification">
                <span class="bell-icon">&#xf0f3;</span>
                <span v-if="unreadCount > 0" class="unread-badge">{{ unreadCount > 99 ? '99+' : unreadCount }}</span>
            </div>
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
</template>

<script>
    import Search from '@/components/inputs/search_buttion';
    import UserDropdown from './UserDropdown.vue'
    import { toast } from "@/utils/toast"
    import { getUserStatistics } from '@/apis/user'
    import request from '@/common/request'
    import conf from '@/common/conf'

    export default {
        name: "HomeBar",
        components: { Search, UserDropdown },
        data:()=>{
            return {
                icon:'\uF002',
                showUserDropdown: false,
                unreadCount: 0,
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
                        if (data.levelInfo) {
                            const li = data.levelInfo
                            this.levelBadge = 'ZR.' + (li.dailyLevel || 1)
                            this.levelScore = li.dailyScore || 0
                            // Calculate max score for current level
                            const levelMaxMap = { 1: 150, 2: 300, 3: 500, 4: 800, 5: 1200 }
                            this.levelMax = levelMaxMap[li.dailyLevel] || 150
                            // Calculate base score for current level
                            const levelBaseMap = { 1: 0, 2: 150, 3: 300, 4: 500, 5: 800 }
                            const base = levelBaseMap[li.dailyLevel] || 0
                            const currentInLevel = this.levelScore - base
                            this.levelPercent = Math.min(Math.round(currentInLevel / (this.levelMax - base) * 100), 100)
                        }
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
            goToNotification() {
                this.showUserDropdown = false
                this.$router.push('/notification')
            },
            fetchUnreadCount() {
                if (!this.isLoggedIn) return
                request.get(conf.urls.get('notifications_unread'), {}).then(d => {
                    if (d && d.code === 200 && d.data) {
                        this.unreadCount = d.data.total || 0
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
            this.closeDropdown = (e) => {
                if (this.showUserDropdown && !this.$el.querySelector('.user-info').contains(e.target)) {
                    this.showUserDropdown = false
                }
            }
            this.escClose = (e) => {
                if (e.key === 'Escape' && this.showUserDropdown) {
                    this.showUserDropdown = false
                }
            }
            document.addEventListener('click', this.closeDropdown)
            document.addEventListener('keydown', this.escClose)
            // 站内信按类型清除未读后，立即刷新顶部铃铛总未读数
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
        gap: 8px;
        cursor: pointer;
        flex-shrink: 0;
        position: relative;
    }
    .user-avatar {
        width: 44px;
        height: 44px;
        border-radius: 50%;
        border: 2px solid rgba(255,255,255,0.5);
        object-fit: cover;
    }

    .notification-bell {
        position: relative;
        margin-right: 16px;
        font-size: 20px;
        color: #515767;
        cursor: pointer;
        width: 44px;
        height: 44px;
        display: flex;
        align-items: center;
        justify-content: center;
        border-radius: 50%;
        transition: background-color 0.2s;
    }
    .notification-bell:hover {
        background-color: rgba(255,255,255,0.15);
    }
    .bell-icon {
        font-family: fontawesome;
        font-size: 24px;
        color: #ffffff;
    }
    .unread-badge {
        position: absolute;
        top: -6px;
        right: -10px;
        min-width: 16px;
        height: 16px;
        line-height: 16px;
        text-align: center;
        background: #ff4d4f;
        color: #fff;
        font-size: 10px;
        border-radius: 8px;
        padding: 0 4px;
    }
</style>