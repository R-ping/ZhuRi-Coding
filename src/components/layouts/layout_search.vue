<template>
    <div class="search-layout">
        <!-- 顶部导航栏 -->
        <div class="search-header">
            <div class="header-inner">
                <div class="header-left">
                    <div class="brand-link" @click="goToHome">
                        <img class="brand-logo" src="/static/images/logo-icon.svg" alt="logo" />
                        <span class="logo-text">逐日Coding</span>
                    </div>
                </div>
                <nav class="main-nav">
                    <span class="nav-link" @click="goToHome">首页</span>
                    <span class="nav-link" @click="goToPins">沸点</span>
                    <span class="nav-link" @click="goToCourse">课程</span>
                    <span class="nav-link">数据标注</span>
                    <span class="nav-link">AI Coding</span>
                </nav>
                <div class="header-center">
                    <div class="search-box">
                        <input
                            v-model="searchKeyword"
                            type="text"
                            class="search-input"
                            placeholder="搜索文章"
                            @keyup.enter="onSearchSubmit"
                        />
                        <span class="search-btn" @click="onSearchSubmit">&#xf002;</span>
                    </div>
                </div>
                <div class="header-right">
                    <span v-if="!isLoggedIn" class="header-btn login-btn" @click="showLogin">登录</span>
                    <span v-else class="header-btn user-btn" @click="goToProfile">
                        <img v-if="userAvatar" class="header-avatar" :src="userAvatar" alt="头像"/>
                        <span class="header-username">{{ userName }}</span>
                    </span>
                </div>
            </div>
        </div>

        <!-- 内容区域 -->
        <div class="search-container">
            <div class="search-content">
                <router-view/>
            </div>
        </div>
    </div>
</template>

<script>
    export default {
        name: "SearchLayout",
        data() {
            return {
                searchKeyword: ''
            }
        },
        watch: {
            '$route.query.keyword': {
                immediate: true,
                handler(val) {
                    if (val) {
                        this.searchKeyword = val
                    }
                }
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
            }
        },
        methods: {
            goToHome() {
                this.$router.push('/home')
            },
            goToPins() {
                this.$router.push('/pins')
            },
            goToCourse() {
                this.$router.push('/course')
            },
            goToProfile() {
                const userId = this.userInfo && this.userInfo.userId ? this.userInfo.userId : 1
                this.$router.push('/user/' + userId)
            },
            showLogin() {
                this.$store.dispatch('showLogin')
            },
            onSearchSubmit() {
                var val = this.searchKeyword
                if (val && val.trim()) {
                    this.$router.push({ name: 'search_result', query: { keyword: val.trim() } })
                }
            }
        }
    };
</script>

<style lang="less" scoped>
    @import '../../styles/common';

    .search-layout {
        width: 100%;
        min-height: 100vh;
        background-color: #f5f5f5;
    }

    /* 顶部导航栏 */
    .search-header {
        height: 60px;
        background-color: #ffffff;
        box-shadow: 0 1px 3px rgba(0,0,0,0.08);
        position: fixed;
        top: 0;
        left: 0;
        right: 0;
        z-index: 100;
    }

    .header-inner {
        max-width: 1200px;
        margin: 0 auto;
        height: 60px;
        display: flex;
        align-items: center;
        padding: 0 24px;
        box-sizing: border-box;
        justify-content: space-between;
    }

    .header-left {
        width: 180px;
        flex-shrink: 0;
    }

    .brand-link {
        display: flex;
        align-items: center;
        gap: 10px;
        cursor: pointer;
        user-select: none;
    }

    .brand-logo {
        width: 32px;
        height: 32px;
    }

    .logo-text {
        font-size: 20px;
        font-weight: 700;
        color: @mian-color;
        white-space: nowrap;
    }

    .main-nav {
        display: flex;
        align-items: center;
        gap: 0;
        margin: 0 24px;
    }

    .nav-link {
        padding: 0 12px;
        font-size: 14px;
        color: #515767;
        cursor: pointer;
        white-space: nowrap;
        line-height: 60px;
        position: relative;
        transition: color 0.2s;
    }

    .nav-link:hover {
        color: #1E80FF;
    }

    .header-center {
        flex: 1;
        max-width: 400px;
        margin: 0 16px;
    }

    .search-box {
        position: relative;
        display: flex;
        align-items: center;
        height: 38px;
        background-color: #f4f5f5;
        border-radius: 19px;
        padding: 0 16px;
        width: 100%;
        box-sizing: border-box;
    }

    .search-box:focus-within {
        background-color: #ffffff;
        box-shadow: 0 0 0 2px rgba(30,128,255,0.2);
    }

    .search-input {
        flex: 1;
        height: 100%;
        border: none;
        outline: none;
        background-color: transparent;
        font-size: 14px;
        color: #333;
        min-width: 0;
    }

    .search-input::placeholder {
        color: #999;
    }

    .search-btn {
        font-family: fontawesome;
        width: 32px;
        height: 32px;
        display: flex;
        align-items: center;
        justify-content: center;
        font-size: 15px;
        color: #999;
        cursor: pointer;
        flex-shrink: 0;
        border-radius: 50%;
    }

    .search-btn:hover {
        color: #1E80FF;
    }

    .header-right {
        display: flex;
        align-items: center;
        gap: 12px;
        flex-shrink: 0;
    }

    .header-btn {
        padding: 6px 16px;
        border-radius: 4px;
        font-size: 14px;
        cursor: pointer;
        white-space: nowrap;
    }

    .login-btn {
        color: #ffffff;
        background-color: @mian-color;
    }

    .login-btn:hover {
        background-color: #1a7de8;
    }

    .user-btn {
        display: flex;
        align-items: center;
        gap: 8px;
        color: #333;
    }

    .user-btn:hover {
        color: #1E80FF;
    }

    .header-avatar {
        width: 32px;
        height: 32px;
        border-radius: 50%;
        object-fit: cover;
    }

    .header-username {
        font-size: 14px;
        max-width: 80px;
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
    }

    /* 内容区 */
    .search-container {
        padding-top: 60px;
        min-height: 100vh;
    }

    .search-content {
        max-width: 700px;
        margin: 0 auto;
        padding: 0 20px;
    }
</style>
