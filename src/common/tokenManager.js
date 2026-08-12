import axios from 'axios'
import store from '@/stores/store'

// 全局唯一的状态标志和请求队列，解决 request.js 和 article_request.js 各自独立刷新导致并发冲突
let _refreshing = false
let _pendingRequests = []

// 网络异常时的重试次数与间隔（毫秒）
const REFRESH_RETRY_TIMES = 2
const REFRESH_RETRY_DELAY = 800

/**
 * 统一 Token 刷新管理器
 *
 * 两个请求拦截器（request.js / article_request.js）都通过此函数刷新 token，
 * 确保同一时间只有一个刷新请求在执行，其他等待请求排队，刷新成功后统一重放。
 *
 * 关键策略：只在服务器“明确返回 token 无效”时才登出；
 * 网络超时 / 服务 5xx 等瞬时异常只做有限重试，绝不强制登出，
 * 避免服务重启或网络抖动导致用户被频繁踢下线。
 *
 * @param {Function} retryFn - 重放请求的函数，接收 (newToken) 作为参数，返回 Promise
 * @returns {Promise} 重放结果
 */
function refresh(retryFn) {
  const refreshToken = store.state.refreshToken

  // 无 refresh_token 说明从未登录或已登出，才清除登录态并弹窗
  if (!refreshToken) {
    store.dispatch('logout')
    store.dispatch('showLogin')
    return Promise.reject({ code: 444, errorMessage: '登录已过期，请重新登录' })
  }

  // 防止并发刷新：多个请求同时触发 444 时，只执行一次刷新
  if (_refreshing) {
    return new Promise(function (resolve, reject) {
      _pendingRequests.push({ resolve: resolve, reject: reject, retryFn: retryFn })
    })
  }

  _refreshing = true
  return doRefresh(refreshToken, retryFn, 0)
}

/**
 * 真正发起刷新请求，网络异常时按次数重试
 */
function doRefresh(refreshToken, retryFn, retryCount) {
  const refreshUrl = '/user/api/v1/token/refresh'
  const refreshTime = new Date().getTime()

  return axios({
    method: 'POST',
    url: refreshUrl,
    headers: {
      'Content-Type': 'application/json; charset=UTF-8',
      'accToken': store.state.accessToken,
      't': '' + refreshTime
    },
    timeout: 10000,
    data: { refreshToken: refreshToken }
  }).then(function (response) {
    const d = response.data
    if (d && d.code === 200 && d.data && d.data.accessToken) {
      // 刷新成功，存储新 token
      store.dispatch('login', d.data)
      _refreshing = false
      // 重放所有等待中的请求
      var pending = _pendingRequests.splice(0)
      pending.forEach(function (req) {
        req.retryFn(d.data.accessToken).then(req.resolve).catch(req.reject)
      })
      // 重放当前请求
      return retryFn(d.data.accessToken)
    }
    // 服务器明确返回“token 无效”，属于真正的登录失效，才登出
    return handleRefreshInvalid()
  }).catch(function () {
    // 网络异常 / 超时 / 服务 5xx：refresh_token 本身未必失效，绝不登出。
    // 做有限次重试，仍失败则仅驳回当前请求，保留登录态，由下次请求再触发刷新。
    if (retryCount < REFRESH_RETRY_TIMES) {
      return new Promise(function (resolve) {
        setTimeout(resolve, REFRESH_RETRY_DELAY)
      }).then(function () {
        return doRefresh(refreshToken, retryFn, retryCount + 1)
      })
    }
    _refreshing = false
    var failPending = _pendingRequests.splice(0)
    failPending.forEach(function (req) {
      req.reject({ code: -1, errorMessage: '网络异常，请稍后重试' })
    })
    return Promise.reject({ code: -1, errorMessage: '网络异常，请稍后重试' })
  })
}

/**
 * 真正的登录失效处理：刷新接口明确返回 token 无效
 */
function handleRefreshInvalid() {
  _refreshing = false
  var failPending = _pendingRequests.splice(0)
  failPending.forEach(function (req) {
    req.reject({ code: 444, errorMessage: '登录已过期，请重新登录' })
  })
  store.dispatch('logout')
  store.dispatch('showLogin')
  return Promise.reject({ code: 444, errorMessage: '登录已过期，请重新登录' })
}

export default { refresh }