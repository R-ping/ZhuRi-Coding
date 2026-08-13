import request from '@/common/article_request'

/**
 * 获取作者信息聚合接口（昵称/头像/职位/逐日等级/逐力值等级/关注数/粉丝数/是否已关注）
 * 供沸点页、文章列表等作者昵称/头像悬浮卡片使用
 */
export const getAuthorInfo = (userId) => {
  return request.get('/api/v1/author/info', { params: { userId } })
}