import request from '@/common/request'
import articleRequest from '@/common/article_request'

/**
 * 标签详情页 API
 */

// 标签详情（用户模块）：按标签名查询 id/标签名/关注数/是否已关注
export const getTagDetail = (tagName) => {
  return request.get('/user/api/v1/tags/' + encodeURIComponent(tagName) + '/detail')
}

// 标签下文章列表（内容模块）：分页 + 排序
export const getTagArticles = (tagName, params = {}) => {
  return articleRequest.get('/api/v1/tag/' + encodeURIComponent(tagName) + '/articles', {
    params: {
      page: 1,
      size: 20,
      sort: 'hot',
      ...params
    }
  })
}

// 关注标签（用户模块，按标签ID）
export const followTag = (tagId) => {
  return request.post('/user/api/v1/tags/follow/' + tagId)
}

// 取消关注标签（用户模块，按标签ID）
export const unfollowTag = (tagId) => {
  return request.del('/user/api/v1/tags/follow/' + tagId)
}

// 举报文章（内容模块）
export const reportArticle = (articleId, data) => {
  return articleRequest.post('/api/v1/article/' + articleId + '/report', data)
}