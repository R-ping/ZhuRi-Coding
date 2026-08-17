import request from '@/common/article_request'

const API_COMMENT_LIST = '/api/v1/comment/list'
const API_MANAGE_LIST = '/api/v1/comment/manage/list'
const API_MANAGE_STATUS = '/api/v1/comment/manage/status'
const API_CLOSECOMMENTS = '/api/v1/comment/status'
const API_ADMIRECOMMENT = '/api/v1/comment/likings'
const API_CANCELADMIRECOMMENT = '/api/v1/comment/likings/'
const API_COMMENTS = '/api/v1/comment'

// 获取评论列表（对应后端 /api/v1/comment/list，POST + CommentDto）
export const getCommentList = (data) => {
  return request.post(API_COMMENT_LIST, data)
}

// 创作者评论管理：分页查询当前用户文章及其评论开关状态
export const getArticleCommentManageList = (data) => {
  return request.post(API_MANAGE_LIST, data)
}

// 创作者评论管理：开启/关闭某篇文章的评论开关
export const setArticleCommentStatus = (data) => {
  return request.put(API_MANAGE_STATUS, data)
}

// 获取某篇文章的评论列表（游标分页）
export const getArticleComments = (articleId, cursor, size) => {
  return request.get('/api/v1/comment/article/' + articleId + '/comments', {
    params: { cursor, size }
  })
}

// 关闭或打开评论（古接口，已由 setArticleCommentStatus 取代）
export const closeOrOpenComment = (data) => {
  return request.put(API_CLOSECOMMENTS, data)
}

// 点赞评论
export const admireComment = (data) => {
  return request.post(API_ADMIRECOMMENT, data)
}

// 取消点赞评论
export const cancleAdmire = (commentId) => {
  return request.delete(API_CANCELADMIRECOMMENT + commentId)
}

// 置顶评论
export const changeTop = (data) => {
  return request.put(API_COMMENTS, data)
}

// 新增评论/回复
export const addComments = (data) => {
  return request.post(API_COMMENTS, data)
}