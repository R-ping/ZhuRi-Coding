import request from '@/common/request'

const BASE = '/notification/api/v1/im'

// 关注自动回复配置
export const getAutoReplySetting = () => {
  return request.get(`${BASE}/auto-reply`)
}

export const saveAutoReplySetting = (data) => {
  return request.put(`${BASE}/auto-reply`, data)
}
