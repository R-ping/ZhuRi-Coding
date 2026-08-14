import axios from 'axios'
import BigInt from 'json-bigint'
import store from '@/stores/store'
import tokenManager from './tokenManager'

// create an axios instance
const service = axios.create({
  baseURL: '/content',
  timeout: 10000,
  transformResponse(data) {
    if (data)
      return BigInt.parse(data)
  }
})

const isImgUpload = (config) => {
  return config.data instanceof FormData
}

// request interceptor
service.interceptors.request.use(
  config => {
    const accessToken = store.state.accessToken
    // 记录本次请求是否使用了有效用户 token（用于 401/444 时决定是否触发刷新重放）
    config._usedUserToken = !!accessToken
    if (accessToken) {
      if (!isImgUpload(config)) {
        config.headers['Content-Type'] = 'application/json'
      } else {
        // FormData 上传：删除手动 Content-Type，让浏览器自动设置带 boundary 的值
        delete config.headers['Content-Type']
      }
      config.headers['accToken'] = accessToken
    }
    return config
  },
  error => {
    return Promise.reject(error)
  }
)

// response interceptor
service.interceptors.response.use(
  response => {
    const data = response.data
    // 检查响应code字段，非200时视为错误
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
      console.warn('[article_request.js] 403 Forbidden:', error.response.config.url)
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

// 刷新token并重放请求
function refreshTokenAndRetry(config) {
  const refreshToken = store.state.refreshToken
  if (!refreshToken) {
    store.dispatch('sessionExpired')
    return Promise.reject({ code: 444, errorMessage: '登录已过期，请重新登录' })
  }
  // 使用统一token管理器，避免两个拦截器并发刷新冲突
  return tokenManager.refresh(function (newToken) {
    config.headers['accToken'] = newToken
    return service(config)
  })
}

export default service
