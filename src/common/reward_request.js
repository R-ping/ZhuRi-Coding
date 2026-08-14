import axios from 'axios'
import store from '@/stores/store'
import tokenManager from './tokenManager'

const service = axios.create({
  baseURL: '/reward',
  timeout: 10000
})

service.interceptors.request.use(
  config => {
    const accessToken = store.state.accessToken
    // 记录本次请求是否使用了有效用户 token（用于 401/444 时决定是否触发刷新重放）
    config._usedUserToken = !!accessToken
    if (accessToken) {
      config.headers['Content-Type'] = 'application/json'
      config.headers['accToken'] = accessToken
    }
    return config
  },
  error => {
    return Promise.reject(error)
  }
)

service.interceptors.response.use(
  response => {
    const data = response.data
    if (data && data.code !== undefined && data.code !== 200) {
      return Promise.reject({ code: data.code, message: data.message || '服务器内部错误', data: data.data })
    }
    return data
  },
  error => {
    // 401未授权 — 刷新失败/ref token失效等最终认证失败的信号，不再刷新，清除登录态并跳回首页（不弹登录框）；
    // 仅当本次请求携带了有效用户 token 时才处理，匿名/游客请求静默 reject，不影响基础浏览
    if (error.response && error.response.status === 401) {
      if (error.config && error.config._usedUserToken) {
        store.dispatch('sessionExpired')
      }
      return Promise.reject(error)
    }
    // 403权限不足
    if (error.response && error.response.status === 403) {
      console.warn('[reward_request.js] 403 Forbidden:', error.response.config.url)
      return Promise.reject(error)
    }
    // 444 — accessToken过期，尝试刷新后重放；
    // 仅当本次请求携带了有效用户 token 时才处理，匿名/游客请求静默 reject，不影响基础浏览
    if (error.response && error.response.status === 444) {
      if (error.config && error.config._usedUserToken) {
        return refreshTokenAndRetry(error.config)
      }
      return Promise.reject(error)
    }
    return Promise.reject(error)
  }
)

function refreshTokenAndRetry(config) {
  const refreshToken = store.state.refreshToken
  if (!refreshToken) {
    store.dispatch('sessionExpired')
    return Promise.reject({ code: 444, errorMessage: '登录已过期，请重新登录' })
  }
  return tokenManager.refresh(function (newToken) {
    config.headers['accToken'] = newToken
    return service(config)
  })
}

export default service