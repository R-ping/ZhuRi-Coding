import request from '@/common/article_request'

// 推荐圈子 Top10
export const getRecommendCircles = () => {
  return request.get('/api/v1/circle/recommend')
}

// 圈子广场列表（分页）
export const getSquareCircles = (page = 1, size = 20) => {
  return request.get('/api/v1/circle/square', { params: { page, size } })
}

// 人气圈子（固定5个）
export const getHotCircles = () => {
  return request.get('/api/v1/circle/hot')
}

// 圈子详情
export const getCircleDetail = (id) => {
  return request.get(`/api/v1/circle/${id}`)
}

// 加入圈子
export const joinCircle = (id) => {
  return request.post(`/api/v1/circle/${id}/join`)
}

// 退出圈子
export const leaveCircle = (id) => {
  return request.post(`/api/v1/circle/${id}/leave`)
}

// 圈子沸点流
export const getCircleFeed = (id, params = {}) => {
  return request.get(`/api/v1/circle/${id}/feed`, { params })
}

// 我的圈子列表
export const getMyCircles = () => {
  return request.get('/api/v1/circle/my')
}

// 获取圈子分类列表
export const getCircleCategories = () => {
  return request.get('/api/v1/circle/categories')
}

// 获取分类下圈子
export const getCirclesByCategory = (categoryId, page = 1, size = 20) => {
  return request.get(`/api/v1/circle/categories/${categoryId}/circles`, { params: { page, size } })
}