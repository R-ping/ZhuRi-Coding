import request from '@/common/article_request'

/**
 * 获取作者信息聚合接口（昵称/头像/职位/逐日等级/逐力值等级/关注数/粉丝数/是否已关注）
 * 供沸点页、文章列表等作者昵称/头像悬浮卡片使用
 */
export const getAuthorInfo = (userId) => {
  return request.get('/api/v1/author/info', { params: { userId } })
}

/**
 * 获取个人主页动态列表（点赞文章/沸点、关注用户、发布文章/沸点，时间线降序）
 */
export const getUserDynamic = (userId, size = 50) => {
  return request.get('/api/v1/user/dynamic', { params: { userId, size } })
}

/**
 * 获取个人主页头部聚合数据（基本信息 + 统计 + 等级）
 * 公开只读接口，未登录也可浏览他人主页
 * @param {number|string} userId 目标用户ID
 */
export const getUserHomeData = (userId) => {
  return request.get(`/api/v1/user/home/${userId}`)
}

/**
 * 获取用户已发布文章列表（公开）
 * @param {number|string} userId 目标用户ID
 * @param {Object} params { page, size }
 */
export const getUserHomeArticles = (userId, params = {}) => {
  return request.get(`/api/v1/user/home/${userId}/articles`, { params })
}

/**
 * 获取用户已发布专栏列表（公开）
 * @param {number|string} userId 目标用户ID
 * @param {Object} params { page, size }
 */
export const getUserHomeColumns = (userId, params = {}) => {
  return request.get(`/api/v1/user/home/${userId}/columns`, { params })
}

/**
 * 获取用户已发布沸点列表（公开）
 * @param {number|string} userId 目标用户ID
 * @param {Object} params { page, size }
 */
export const getUserHomePins = (userId, params = {}) => {
  return request.get(`/api/v1/user/home/${userId}/pins`, { params })
}

/**
 * 获取用户关注的用户列表（公开）
 * @param {number|string} userId 目标用户ID
 * @param {Object} params { page, size }
 */
export const getUserHomeFollowing = (userId, params = {}) => {
  return request.get(`/api/v1/user/home/${userId}/following`, { params })
}

/**
 * 获取用户的关注者列表（公开）
 * @param {number|string} userId 目标用户ID
 * @param {Object} params { page, size }
 */
export const getUserHomeFollowers = (userId, params = {}) => {
  return request.get(`/api/v1/user/home/${userId}/followers`, { params })
}

/**
 * 获取用户收藏集（收藏的文章列表，公开）
 * @param {number|string} userId 目标用户ID
 * @param {Object} params { page, size }
 */
export const getUserHomeCollections = (userId, params = {}) => {
  return request.get(`/api/v1/user/home/${userId}/collections`, { params })
}

/**
 * 获取用户点赞的文章/沸点列表（公开）
 * @param {number|string} userId 目标用户ID
 * @param {Object} params { page, size, type: 'article' | 'pins' }
 */
export const getUserHomeLikes = (userId, params = {}) => {
  return request.get(`/api/v1/user/home/${userId}/likes`, { params })
}

/**
 * 获取用户创作的已发布课程列表（公开）
 * @param {number|string} userId 目标用户ID
 * @param {Object} params { page, size }
 */
export const getUserHomeCourses = (userId, params = {}) => {
  return request.get(`/api/v1/user/home/${userId}/courses`, { params })
}

/**
 * 获取作者收到的打赏记录（公开）
 * 展示打赏人（昵称/头像）、金额、留言、时间及被打赏的文章
 * @param {number|string} userId 目标作者用户ID
 * @param {Object} params { page, size }
 */
export const getUserHomeTips = (userId, params = {}) => {
  return request.get(`/api/v1/user/home/${userId}/tips`, { params })
}

// ==================== 收藏夹（F4，仅本人可见） ====================

/**
 * 我的收藏夹列表（含条目数与最近使用时间，供收藏页筛选与收藏选择面板）
 */
export const getCollectionFolders = () => {
  return request.get('/api/v1/user/collection/folders')
}

/**
 * 新建收藏夹（名称 1-20 字、不可重名、单用户上限 50）
 * @param {string} name 收藏夹名称
 */
export const createCollectionFolder = (name) => {
  return request.post('/api/v1/user/collection/folders', { name })
}

/**
 * 重命名收藏夹
 * @param {number|string} folderId 收藏夹ID
 * @param {string} name 新名称
 */
export const renameCollectionFolder = (folderId, name) => {
  return request.put(`/api/v1/user/collection/folders/${folderId}`, { name })
}

/**
 * 删除收藏夹（其中收藏回退默认收藏夹，收藏记录不删除）
 * @param {number|string} folderId 收藏夹ID
 */
export const removeCollectionFolder = (folderId) => {
  return request.delete(`/api/v1/user/collection/folders/${folderId}`)
}

/**
 * 收藏夹排序（与相邻收藏夹交换）
 * @param {number|string} folderId 收藏夹ID
 * @param {'up'|'down'} direction 排序方向
 */
export const moveCollectionFolder = (folderId, direction) => {
  return request.put(`/api/v1/user/collection/folders/${folderId}/move`, null, { params: { direction } })
}