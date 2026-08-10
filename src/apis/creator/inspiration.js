import request from '@/common/article_request'

// 获取创作话题列表
export const getInspirationTopics = (params = {}) => {
  return request.get('/api/v1/inspiration/topics', { params })
}

// 获取创作活动列表
export const getInspirationActivities = (params = {}) => {
  return request.get('/api/v1/inspiration/activities', { params })
}