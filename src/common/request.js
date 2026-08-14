import axios from 'axios'
import crypto from 'crypto-js'
import store from '@/stores/store'
import tokenManager from './tokenManager'
import { normalizeOssUrl, normalizeResponseData } from './ossUrl'

function Request() {
    this.store = null;
}
Request.prototype = {
    setStore: function (store) {
        this.store = store
    },
    postByEquipmentId: function (url, body) {
        var _this = this
        return this.store.getEquipmentId().then(function (equipmentId) {
            body['equipmentId'] = equipmentId
            return _this.post(url, body)
        }).catch(function () {
            return _this.post(url, body)
        })
    },
    getByEquipmentId: function (url, body) {
        var _this = this
        return this.store.getEquipmentId().then(function (equipmentId) {
            body['equipmentId'] = equipmentId
            return _this.get(url, body)
        }).catch(function () {
            return _this.get(url, body)
        })
    },
    post: function (path, body, parms) {
        var _this = this
        var time = new Date().getTime()
        if (parms == undefined) parms = {}
        else {
            path = path + '?' + objToQueryString(parms)
        }
        parms['t'] = time
        return this.store.getToken().then(function (token) {
            return _this.__fetch('POST', path, token, time, parms, body)
        }).catch(function (e) {
            // 没有登录token，尝试使用guest token
            return _this.store.getGuestToken().then(function (guestToken) {
                if (guestToken) {
                    return _this.__fetch('POST', path, guestToken, time, parms, body)
                }
                return _this.__fetch('POST', path, '', time, parms, body)
            }).catch(function () {
                return _this.__fetch('POST', path, '', time, parms, body)
            })
        })
    },
    get: function (path, parms) {
        var _this = this
        if (parms) {
            if (path.indexOf('?') === -1) {
                path += '?' + objToQueryString(parms)
            } else {
                path += '&' + objToQueryString(parms)
            }
        }
        var time = new Date().getTime()
        var allParms = parms || {}
        allParms['t'] = time
        return this.store.getToken().then(function (token) {
            return _this.__fetch('GET', path, token, time, allParms)
        }).catch(function (e) {
            // 没有登录token，尝试使用guest token
            return _this.store.getGuestToken().then(function (guestToken) {
                if (guestToken) {
                    return _this.__fetch('GET', path, guestToken, time, allParms)
                }
                return _this.__fetch('GET', path, '', time, allParms)
            }).catch(function () {
                return _this.__fetch('GET', path, '', time, allParms)
            })
        })
    },
    del: function (path, parms) {
        var _this = this
        var time = new Date().getTime()
        if (parms == undefined) parms = {}
        else {
            path = path + '?' + objToQueryString(parms)
        }
        parms['t'] = time
        return this.store.getToken().then(function (token) {
            return _this.__fetch('DELETE', path, token, time, parms)
        }).catch(function (e) {
            return _this.store.getGuestToken().then(function (guestToken) {
                if (guestToken) {
                    return _this.__fetch('DELETE', path, guestToken, time, parms)
                }
                return _this.__fetch('DELETE', path, '', time, parms)
            }).catch(function () {
                return _this.__fetch('DELETE', path, '', time, parms)
            })
        })
    },
    put: function (path, body, parms) {
        var _this = this
        var time = new Date().getTime()
        if (parms == undefined) parms = {}
        else {
            path = path + '?' + objToQueryString(parms)
        }
        parms['t'] = time
        return this.store.getToken().then(function (token) {
            return _this.__fetch('PUT', path, token, time, parms, body)
        }).catch(function (e) {
            // 没有登录token，尝试使用guest token
            return _this.store.getGuestToken().then(function (guestToken) {
                if (guestToken) {
                    return _this.__fetch('PUT', path, guestToken, time, parms, body)
                }
                return _this.__fetch('PUT', path, '', time, parms, body)
            }).catch(function () {
                return _this.__fetch('PUT', path, '', time, parms, body)
            })
        })
    },
    __fetch: function (type, path, token, time, parms, body, retryCount) {
        retryCount = retryCount || 0
        var _this = this
        var headers = {
            'Content-Type': 'application/json; charset=UTF-8',
            'accToken': token,
            't': '' + time,
            // 'md': this.sign(parms)
        }
        // FormData 上传（如头像）：移除手动 Content-Type，让浏览器自动设置带 boundary 的值
        if (body && typeof FormData !== 'undefined' && body instanceof FormData) {
            delete headers['Content-Type']
        }
        var config = {
            method: type,
            url: path,
            headers: headers,
            timeout: 15000 // 15秒超时
        }
        if (body) {
            config.data = body
        }
        // 判断本次请求是否使用了当前用户的 accessToken（而非游客/空 token）
        var usedUserToken = !!token && token === _this.store.state.accessToken
        return axios(config).then(function (response) {
            var data = response.data
            // 兼容后端直接返回字符串的情况（如文章内容）
            if (typeof data === 'string') {
                return { code: 200, data: normalizeOssUrl(data) }
            }
            // 检查响应code字段，非200时视为错误，抛出带message的异常
            if (data && data.code !== undefined && data.code !== 200) {
                return Promise.reject({ code: data.code, message: data.message || '服务器内部错误', data: data.data })
            }
            // 规范化响应数据中的 OSS URL（去除过期签名参数）
            data = normalizeResponseData(data)
            return data
        }).catch(function (error) {
            // 444 — accessToken过期，仅当原请求使用用户token时才尝试刷新后重放
            if (error.response && error.response.status === 444 && retryCount < 1 && usedUserToken) {
                return _this.__refreshAndRetry(type, path, time, parms, body, retryCount)
            }
            // 401未授权 — 刷新失败/ref token失效等最终认证失败的信号，不再刷新，清除过期token并跳回首页（不弹登录框）。
            // 仅当原请求使用用户token时处理，匿名/游客请求静默reject
            if (error.response && error.response.status === 401 && retryCount < 1 && usedUserToken) {
                _this.store.dispatch('sessionExpired')
                return Promise.reject(error.response || error)
            }
            // 403权限不足
            if (error.response && error.response.status === 403) {
                console.warn('[request.js] 403 Forbidden:', error.response.config.url)
                return Promise.reject(error.response)
            }
            // 网络错误
            if (error.code === 'ECONNABORTED' || error.message === 'Network Error') {
                return Promise.reject({ code: -1, errorMessage: '网络连接超时，请检查网络' })
            }
            if (error.response) {
                return Promise.reject(error.response)
            }
            return Promise.reject(error)
        })
    },
    /**
     * 444状态码处理：用refreshToken刷新双Token后重放原请求
     * - 无accessToken：用户从未登录，直接reject，不触发logout和showLogin
     * - 有accessToken无refreshToken：无法刷新，清除过期token后跳回首页
     * - 有accessToken和refreshToken：尝试刷新，失败才清除全部登录状态
     */
    __refreshAndRetry: function (type, path, time, parms, body, retryCount) {
        var _this = this
        // 先检查本地是否有accessToken，没有说明用户从未登录，直接 reject
        var accessToken = _this.store.state.accessToken
        if (!accessToken) {
            return Promise.reject({ code: 444, errorMessage: '登录已过期，请重新登录' })
        }
        var refreshToken = _this.store.state.refreshToken
        if (!refreshToken) {
            // 有accessToken但没有refreshToken，无法刷新，清除过期token后跳回首页（不弹登录框）
            _this.store.dispatch('sessionExpired')
            return Promise.reject({ code: 444, errorMessage: '登录已过期，请重新登录' })
        }
        // 使用统一token管理器，避免两个拦截器并发刷新冲突
        return tokenManager.refresh(function (newToken) {
            return _this.__fetch(type, path, newToken, time, parms, body, retryCount + 1)
        })
    },
    sign: function (parms) {
        return ''
    }
}

function objToQueryString(obj) {
    var parts = []
    for (var key in obj) {
        parts.push(encodeURIComponent(key) + '=' + encodeURIComponent(obj[key]))
    }
    return parts.join('&')
}

export default new Request()