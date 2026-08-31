import request from '@/common/article_request'

// 推荐话题（换一换）
// 路径 command 为 recommend-topics，与文章列表的 /topics/recommend 区分，避免混淆
export const getRecommendTopics = (page = 0, size = 5) => {
  return request.get('/api/v1/topics/recommend-topics', { params: { page, size } })
}

// 话题广场列表
export const getTopicSquare = (params = {}) => {
  return request.get('/api/v1/topics/square', { params })
}

// 话题详情
export const getTopicDetail = (id) => {
  return request.get(`/api/v1/topics/${id}`)
}

// 话题内容 Feed 流
export const getTopicFeed = (id, params = {}) => {
  return request.get(`/api/v1/topics/${id}/feed`, { params })
}

// 搜索话题
export const searchTopics = (keyword, limit = 10) => {
  return request.get('/api/v1/topics/search', { params: { keyword, limit } })
}