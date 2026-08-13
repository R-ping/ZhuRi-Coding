import request from '@/common/article_request'

// 获取沸点列表 (tab: latest, hot, following)
export const getPinsList = (params = {}) => {
  return request.get('/api/v1/pins/list', { params })
}

// 获取沸点详情
export const getPinsDetail = (pinsId) => {
  return request.get(`/api/v1/pins/${pinsId}`)
}

// 增加沸点浏览量
export const incrPinView = (pinsId) => {
  return request.post(`/api/v1/pins/${pinsId}/view`)
}

// 获取右侧边栏数据 (用户统计+精选沸点+推荐话题)
export const getSidebar = () => {
  return request.get('/api/v1/pins/sidebar')
}

// 发布沸点
export const publishPins = (data) => {
  return request.post('/api/v1/pins/publish', data)
}

// 点赞/取消点赞沸点
export const likePins = (data) => {
  return request.post('/api/v1/pins/like', data)
}

// 发表评论/回复
export const createComment = (data) => {
  return request.post('/api/v1/pins/comment/create', data)
}

// 获取评论列表
export const getComments = (params) => {
  return request.get('/api/v1/pins/comment/list', { params })
}

// 分享沸点
export const sharePins = (data) => {
  return request.post('/api/v1/pins/share', data)
}

// 获取话题列表（分页）
export const getTopics = (params = {}) => {
  return request.get('/api/v1/pins/topics', { params })
}

// 获取所有圈子（按类别分组）
export const getAllCircles = () => {
  return request.get('/api/v1/pins/circles')
}

// 链接预览
export const previewLink = (data) => {
  return request.post('/api/v1/pins/link-preview', data)
}