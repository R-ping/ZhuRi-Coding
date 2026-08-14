import request from '@/common/article_request'

/**
 * 获取用户成就勋章（11 枚静态勋章 + 2 枚等级徽章）
 * 公开只读接口，支持匿名访问他人主页
 * @param {number|string} userId 目标用户ID
 */
export const getUserAchievements = (userId) => {
  return request.get(`/api/v1/user/${userId}/achievements`)
}
