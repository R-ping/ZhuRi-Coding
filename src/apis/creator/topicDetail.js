import request from '@/common/article_request'

// 获取话题详情
export const getTopicDetail = (id) => {
  return request.get(`/api/v1/topics/${id}`)
}

// 获取话题内容 Feed 流
export const getTopicFeed = (id, params = {}) => {
  return request.get(`/api/v1/topics/${id}/feed`, { params })
}

// 获取推荐话题
export const getRecommendedTopics = (excludeId, limit = 6) => {
  return request.get(`/api/v1/topics/${excludeId}/recommended`, { params: { excludeId, limit } })
}

// 增加话题阅读量
export const incrTopicView = (id) => {
  return request.post(`/api/v1/topics/${id}/view`)
}