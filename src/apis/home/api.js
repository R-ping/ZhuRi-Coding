import conf from '@/common/conf'
import request from '@/common/request'
import store from '@/stores/store'
import articleRequest from '@/common/article_request'

function Api(){}
Api.prototype = {
    // 加载数据
    loaddata : function(params){
        let dir = params.loaddir
        let url = this.getLoadUrl(dir)
        // 后端 ArticleHomeDto 使用驼峰字段
        let body = {
            tag: params.tag,
            size: params.size || 10
        }
        if (params.tagName && params.tagName !== '__all__') {
            body.tagName = params.tagName
        }
        // maxBehotTime/minBehotTime 为数字时间戳，发送给后端 Date 类型
        if (params.max_behot_time && params.max_behot_time > 0) {
            body.maxBehotTime = params.max_behot_time
        }
        if (params.min_behot_time && params.min_behot_time > 0 && params.min_behot_time < 20000000000000) {
            body.minBehotTime = params.min_behot_time
        }
        return store.getEquipmentId().then(equipmentId => {
            body.equipmentId = equipmentId
            return new Promise((resolve, reject) => {
                request.post(url, body, {}).then((d) => {
                    resolve(d)
                }).catch((e) => {
                    reject(e)
                })
            })
        }).catch(e => {
            return new Promise((resolve, reject) => {
                reject(e)
            })
        })
    },
    // 文章列表统一入口（recommend 系列接口，实现流量分流）
    // params.endpoint 决定分流接口：
    //   all    -> /recommend_all     综合频道（推荐/最新分栏通过 subTab 区分）
    //   follow -> /recommend_follow  关注分栏
    //   cate   -> /recommend_cate    分类频道（推荐/最新分栏通过 subTab 区分）
    // 推荐/最新分栏通过 params.subTab 区分（recommend-推荐 / latest-最新）
    recommendLoad: function(params) {
        var url = '/api/v1/article/recommend_all'
        if (params.endpoint === 'follow') {
            url = '/api/v1/article/recommend_follow'
        } else if (params.endpoint === 'cate') {
            url = '/api/v1/article/recommend_cate'
        }
        var body = {
            channel: params.channel || '__all__',
            size: params.size || 10,
            subTab: params.subTab || 'recommend'
        }
        if (params.tagName && params.tagName !== '__all__') {
            body.tagName = params.tagName
        }
        if (params.seed) {
            body.seed = params.seed
        }
        if (params.page !== undefined && params.page !== null) {
            body.page = params.page
        }
        return store.getEquipmentId().then(function(equipmentId) {
            body.equipmentId = equipmentId
            return new Promise(function(resolve, reject) {
                articleRequest.post(url, body, {}).then(function(d) {
                    resolve(d)
                }).catch(function(e) {
                    reject(e)
                })
            })
        }).catch(function(e) {
            return new Promise(function(resolve, reject) {
                reject(e)
            })
        })
    },
    // 区别请求哪个URL
    getLoadUrl : function(dir){
        let url = conf.urls.get('load')
        if (dir === 0)
            url = conf.urls.get('loadnew')
        else if (dir === 2)
            url = conf.urls.get('loadmore')
        return url
    },
    // 按分类获取标签列表（支持关键字搜索，size 固定取 top15）
    getTagsByCategory: function(categoryId, keyword) {
        return new Promise((resolve, reject) => {
            articleRequest.get('/api/v1/tag/category-top', { params: { categoryId, keyword, size: 15 } }).then((d) => {
                resolve(d)
            }).catch((e) => {
                reject(e)
            })
        })
    }
}

export default new Api()