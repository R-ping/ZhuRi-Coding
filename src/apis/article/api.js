import conf from '@/common/conf'
import request from '@/common/request'

function Api(){}
Api.prototype = {
    // 喜欢/点赞（operation: 0=点赞 1=取消点赞，统一走事件总线 like/unlike）
    like : function(data){
        let url = conf.urls.get(data.operation === 0 ? 'like_behavior' : 'unlike_behavior')
        return new Promise((resolve, reject) => {
            request.post(url, {
                targetType: 1,
                targetId: data.articleId,
                targetUserId: data.targetUserId || null
            }).then((d) => {
                resolve(d)
            }).catch((e) => {
                reject(e)
            })
        })
    },
    // 取消点赞/不喜欢文章（统一走事件总线 unlike）
    unlike : function(data){
        let url = conf.urls.get('unlike_behavior')
        return new Promise((resolve, reject) => {
            request.post(url, {
                targetType: 1,
                targetId: data.articleId
            }).then((d) => {
                resolve(d)
            }).catch((e) => {
                reject(e)
            })
        })
    },
    // 阅读行为（统一走事件总线 browse）
    read : function(data){
        let url = conf.urls.get('read_behavior')
        return new Promise((resolve, reject) => {
            request.post(url, {
                targetType: 1,
                targetId: data.articleId,
                targetUserId: data.targetUserId || null
            }).then((d) => {
                resolve(d)
            }).catch((e) => {
                reject(e)
            })
        })
    },
    // 获取文章元数据
    getInfo: function (articleId) {
        let url = conf.urls.get('article_info')
        return new Promise((resolve, reject) => {
            request.get(url, { articleId: articleId }).then((d) => {
                resolve(d)
            }).catch((e) => {
                reject(e)
            })
        })
    },
    // 获取文章内容
    getContent: function (articleId) {
        let url = conf.urls.get('article_content')
        return new Promise((resolve, reject) => {
            request.get(url, { articleId: articleId }).then((d) => {
                resolve(d)
            }).catch((e) => {
                reject(e)
            })
        })
    },
    // 获取评论列表
    getCommentList: function (articleId, page, size) {
        let url = conf.urls.get('comment_list')
        return new Promise((resolve, reject) => {
            request.post(url, {
                articleId: articleId,
                page: page || 1,
                size: size || 3
            }).then((d) => {
                resolve(d)
            }).catch((e) => {
                reject(e)
            })
        })
    },
    // 发表评论
    addComment: function (data) {
        let url = conf.urls.get('comment_add')
        return new Promise((resolve, reject) => {
            request.post(url, {
                articleId: data.articleId,
                parentId: data.parentId || null,
                content: data.content
            }).then((d) => {
                resolve(d)
            }).catch((e) => {
                reject(e)
            })
        })
    },
    // 点赞评论
    likeComment: function (commentId) {
        let url = conf.urls.get('comment_like')
        return new Promise((resolve, reject) => {
            request.post(url, {
                commentId: commentId
            }).then((d) => {
                resolve(d)
            }).catch((e) => {
                reject(e)
            })
        })
    },
    // 收藏（operation: 0=收藏 1=取消收藏，统一走事件总线 collect/uncollect）
    collect: function (data) {
        let url = conf.urls.get(data.operation === 0 ? 'collection_behavior' : 'uncollect_behavior')
        return new Promise((resolve, reject) => {
            request.post(url, {
                targetType: 1,
                targetId: data.articleId,
                targetUserId: data.targetUserId || null
            }).then((d) => {
                resolve(d)
            }).catch((e) => {
                reject(e)
            })
        })
    },
    // 关注/取关已统一收敛到 /api/v1/follow/do（见 src/apis/follow.js），此处不再走事件总线
}

export default new Api()