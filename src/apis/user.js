import request from '@/common/request'

export const getUserStatistics = () => {
  return request.get('/user/api/v1/user/statistics')
}

export const getUserProfile = () => {
  return request.get('/user/api/v1/user/profile')
}

export const updateUserProfile = (data) => {
  return request.put('/user/api/v1/user/profile', data)
}

export const uploadAvatar = (file) => {
  const formData = new FormData()
  formData.append('file', file)
  return request.post('/user/api/v1/user/avatar', formData)
}

// 获取账号绑定状态
export const getBindings = () => {
  return request.get('/user/api/v1/user/bindings')
}

// 修改密码
export const updatePassword = (data) => {
  return request.put('/user/api/v1/user/password', data)
}

// 注销账号
export const deleteAccount = () => {
  return request.del('/user/api/v1/user/account')
}

// 更新私信权限
export const updatePrivacyMessage = (data) => {
  return request.put('/user/api/v1/user/privacy/message', data)
}

// 获取屏蔽列表
export const getBlocks = (type, page = 1, size = 10) => {
  return request.get('/user/api/v1/user/blocks', { params: { type, page, size } })
}

// 解除屏蔽
export const removeBlock = (id) => {
  return request.del(`/user/api/v1/user/blocks/${id}`)
}

// 新增屏蔽（type 1-作者 2-标签）
export const addBlock = (data) => {
  return request.post('/user/api/v1/user/blocks', data)
}

// 获取标签发现列表
export const getTagsDiscover = (params) => {
  return request.get('/user/api/v1/tags/discover', { params })
}

// 获取已关注标签
export const getFollowedTags = () => {
  return request.get('/user/api/v1/tags/followed')
}

// 关注标签
export const followTag = (tagId) => {
  return request.post(`/user/api/v1/tags/follow/${tagId}`)
}

// 取关标签
export const unfollowTag = (tagId) => {
  return request.del(`/user/api/v1/tags/follow/${tagId}`)
}