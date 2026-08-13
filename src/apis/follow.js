import request from '@/common/article_request'

/**
 * 关注/取关用户
 * @param {Number} userId 当前登录用户ID
 * @param {Number} followUserId 目标用户ID
 */
export const followUser = (userId, followUserId) => {
  return request.post('/api/v1/follow/do', null, { params: { userId, followUserId } })
}