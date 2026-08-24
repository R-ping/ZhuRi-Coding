// 统一登录拦截工具
// 用于需要登录的写操作（发布/点赞/收藏/评论/回复等）入口统一拦截：
// 未登录时不发起任何请求，仅提示并弹出登录框；已登录返回 true 放行。

import store from '@/stores/store'
import { toast } from '@/utils/toast'

/**
 * 校验当前是否已登录。
 * @returns {boolean} 已登录返回 true；未登录弹出登录框并返回 false
 */
export function requireLogin() {
  const loggedIn = store.getters && store.getters.isLoggedIn
  if (!loggedIn) {
    toast('请先登录')
    store.dispatch && store.dispatch('showLogin')
    return false
  }
  return true
}

export default requireLogin