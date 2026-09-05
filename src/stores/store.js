import Vue from 'vue'
import Vuex from 'vuex'

Vue.use(Vuex)

// ========== localStorage 缓存工具 ==========
// 安全说明：Token 存储在 localStorage 中存在 XSS 窃取风险。
// 建议在生产环境使用 httpOnly Secure Cookie 配合 BFF 模式存储 Token。
// 当前方案通过以下措施缓解风险：
// 1. 所有 v-html 渲染前经过 DOMPurify 净化（见 src/utils/sanitize.js）
// 2. accessToken 短期有效（1小时），refreshToken 为一次性使用（防重放）
// 3. 请求拦截器中处理 444/401 错误时自动刷新 Token
function Cache() {
    this.tokenKey = 'ACCESS_TOKEN'
    this.refreshTokenKey = 'REFRESH_TOKEN'
    this.userInfoKey = 'USER_INFO'
    this.equipmentidKey = 'EQUIPMENTID_KEY'
    // 设置默认设备ID
    if (!localStorage.getItem(this.equipmentidKey)) {
        localStorage.setItem(this.equipmentidKey, '8D3E8E0CF883C4E99329AF8A29300AB6')
    }
}
Cache.prototype = {
    setToken: function (token) {
        return this.__setItem(this.tokenKey, token)
    },
    getToken: function () {
        return this.__getItem(this.tokenKey)
    },
    setRefreshToken: function (token) {
        return this.__setItem(this.refreshTokenKey, token)
    },
    getRefreshToken: function () {
        return this.__getItem(this.refreshTokenKey)
    },
    setUserInfo: function (info) {
        try {
            localStorage.setItem(this.userInfoKey, JSON.stringify(info))
            return Promise.resolve(true)
        } catch (e) {
            return Promise.reject(false)
        }
    },
    getUserInfo: function () {
        try {
            var val = localStorage.getItem(this.userInfoKey)
            if (val) {
                return Promise.resolve(JSON.parse(val))
            }
            return Promise.reject({ result: 'fail', data: null })
        } catch (e) {
            return Promise.reject(e)
        }
    },
    setEquipmentId: function (equipmentId) {
        return this.__setItem(this.equipmentidKey, equipmentId)
    },
    getEquipmentId: function () {
        return this.__getItem(this.equipmentidKey)
    },
    clearAll: function () {
        try {
            localStorage.removeItem(this.tokenKey)
            localStorage.removeItem(this.refreshTokenKey)
            localStorage.removeItem(this.userInfoKey)
            return Promise.resolve(true)
        } catch (e) {
            return Promise.reject(false)
        }
    },
    __setItem: function (key, value) {
        try {
            localStorage.setItem(key, value)
            return Promise.resolve(true)
        } catch (e) {
            return Promise.reject(false)
        }
    },
    __getItem: function (key) {
        try {
            var val = localStorage.getItem(key)
            if (val !== null && val !== undefined) {
                return Promise.resolve(val)
            } else {
                return Promise.reject({ result: 'fail', data: null })
            }
        } catch (e) {
            return Promise.reject(e)
        }
    }
}
var cache = new Cache()

// ========== 同步从 localStorage 恢复登录状态 ==========
// 在创建 Vuex.Store 之前同步读取，避免应用渲染并发送 API 请求时 token 仍为空字符串
var cachedAccessToken = localStorage.getItem(cache.tokenKey) || ''
var cachedRefreshToken = localStorage.getItem(cache.refreshTokenKey) || ''
var cachedUserInfo = null
try {
    var cachedUserInfoStr = localStorage.getItem(cache.userInfoKey)
    if (cachedUserInfoStr) {
        cachedUserInfo = JSON.parse(cachedUserInfoStr)
    }
} catch (e) {
    cachedUserInfo = null
}

// ========== Vuex Store ==========
var store = new Vuex.Store({
    state: {
        accessToken: cachedAccessToken,
        refreshToken: cachedRefreshToken,
        guestToken: '',
        userInfo: cachedUserInfo,  // { userId, nickName, avatar, phone }
        showLoginModal: false,
        isLoginPopupShowing: false,  // 防止重复弹出登录框
        showSocialBindModal: false,
        // 社交登录绑定状态（暂存，用于OAuth回调后弹出绑定弹窗）
        socialBindInfo: null,  // { platform, platformUid }
        // 任务进度刷新信号：任何影响"社区活跃/逐日分"的用户行为成功后自增，
        // 成长页/创作者任务的展示组件 watch 该值以即时刷新进度（无需整页刷新）
        taskVersion: 0
    },
    mutations: {
        SET_ACCESS_TOKEN(state, token) {
            state.accessToken = token
            cache.setToken(token)
        },
        SET_REFRESH_TOKEN(state, token) {
            state.refreshToken = token
            cache.setRefreshToken(token)
        },
        CLEAR_AUTH(state) {
            state.accessToken = ''
            state.refreshToken = ''
            state.userInfo = null
            state.guestToken = ''
            cache.clearAll()
        },
        SET_GUEST_TOKEN(state, token) {
            state.guestToken = token
        },
        SET_USER_INFO(state, info) {
            state.userInfo = info
            cache.setUserInfo(info)
        },
        SHOW_LOGIN_MODAL(state) {
            state.showLoginModal = true
            state.isLoginPopupShowing = true
        },
        HIDE_LOGIN_MODAL(state) {
            state.showLoginModal = false
            state.isLoginPopupShowing = false
        },
        SHOW_SOCIAL_BIND_MODAL(state) {
            state.showSocialBindModal = true
        },
        HIDE_SOCIAL_BIND_MODAL(state) {
            state.showSocialBindModal = false
        },
        SET_SOCIAL_BIND_INFO(state, info) {
            state.socialBindInfo = info
        },
        CLEAR_SOCIAL_BIND_INFO(state) {
            state.socialBindInfo = null
        },
        BUMP_TASK_VERSION(state) {
            state.taskVersion += 1
        }
    },
    actions: {
        /**
         * 登录成功：存储双Token + 用户信息
         * @param {Object} loginResult - { accessToken, refreshToken, userId, nickName, avatar, phone }
         */
        login({ commit }, loginResult) {
            if (loginResult.accessToken) {
                commit('SET_ACCESS_TOKEN', loginResult.accessToken)
            }
            if (loginResult.refreshToken) {
                commit('SET_REFRESH_TOKEN', loginResult.refreshToken)
            }
            if (loginResult.userId || loginResult.nickName) {
                commit('SET_USER_INFO', {
                    userId: loginResult.userId || '',
                    nickName: loginResult.nickName || '',
                    avatar: loginResult.avatar || '',
                    phone: loginResult.phone || ''
                })
            }
            commit('HIDE_LOGIN_MODAL')
        },
        logout({ commit }) {
            commit('CLEAR_AUTH')
        },
        /**
         * 登录态失效统一处理：清空登录态并跳回首页，不弹出登录框。
         * 未登录状态下用户仍可正常基础浏览（首页/沸点/文章等公开页面）。
         */
        sessionExpired({ commit }) {
            commit('CLEAR_AUTH')
            // 已处于首页则无需重复跳转（避免并发 401 触发多次跳转）
            var path = window.location.pathname
            if (path !== '/' && path !== '/home') {
                // 整页跳转回首页，彻底重置前端内存态并重新加载应用
                window.location.href = '/'
            }
        },
        guestLogin({ commit }, token) {
            commit('SET_GUEST_TOKEN', token)
        },
        showLogin({ commit }) {
            commit('SHOW_LOGIN_MODAL')
        },
        hideLogin({ commit }) {
            commit('HIDE_LOGIN_MODAL')
        },
        showSocialBind({ commit }) {
            commit('SHOW_SOCIAL_BIND_MODAL')
        },
        hideSocialBind({ commit }) {
            commit('HIDE_SOCIAL_BIND_MODAL')
        },
        setSocialBindInfo({ commit }, info) {
            commit('SET_SOCIAL_BIND_INFO', info)
        },
        clearSocialBindInfo({ commit }) {
            commit('CLEAR_SOCIAL_BIND_INFO')
        },
        /**
         * 用户行为（点赞/收藏/关注/评论/发布等）成功后通知：任务展示组件据此即时刷新进度
         */
        notifyTaskChange({ commit }) {
            commit('BUMP_TASK_VERSION')
        }
    },
    getters: {
        isLoggedIn: function (state) {
            return !!state.accessToken
        },
        showLoginModal: function (state) {
            return state.showLoginModal
        },
        showSocialBindModal: function (state) {
            return state.showSocialBindModal
        },
        userInfo: function (state) {
            return state.userInfo
        },
        socialBindInfo: function (state) {
            return state.socialBindInfo
        }
    }
})

// 初始化时从localStorage恢复状态
cache.getToken().then(function (token) {
    if (token) {
        store.commit('SET_ACCESS_TOKEN', token)
    }
}).catch(function () { })

cache.getRefreshToken().then(function (token) {
    if (token) {
        store.commit('SET_REFRESH_TOKEN', token)
    }
}).catch(function () { })

cache.getUserInfo().then(function (info) {
    if (info) {
        store.commit('SET_USER_INFO', info)
    }
}).catch(function () { })

// ========== 兼容旧API（供request.js使用） ==========
store.setToken = function (token) {
    store.commit('SET_ACCESS_TOKEN', token)
}
store.getToken = function () {
    return cache.getToken()
}
store.clearToken = function () {
    store.commit('CLEAR_AUTH')
}
store.getEquipmentId = function () {
    return cache.getEquipmentId()
}
store.setEquipmentId = function (equipmentId) {
    return cache.setEquipmentId(equipmentId)
}
store.getGuestToken = function () {
    return Promise.resolve(store.state.guestToken || '')
}
store.getRefreshToken = function () {
    return cache.getRefreshToken()
}

export default store