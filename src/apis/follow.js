import request from '@/common/article_request'

/**
 * 关注/取关用户（统一走 /follow/do）
 * @param {Number} userId 当前登录用户ID
 * @param {Number} followUserId 目标用户ID
 * @param {Number} operation 0=关注（默认） 1=取消关注
 */
export const followUser = (userId, followUserId, operation = 0) => {
  return request.post('/api/v1/follow/do', null, { params: { userId, followUserId, operation } })
}