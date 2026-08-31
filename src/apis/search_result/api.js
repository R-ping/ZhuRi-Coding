import conf from '@/common/conf'
import request from '@/common/request'

// 分栏 tag → 后端统一搜索 idType 映射（0 综合 / 1 文章 / 2 课程 / 3 标签 / 4 用户）
// 综合与文章页均展示文章数据，故两者都映射到 1（文章），由后端综合分支兜底为文章搜索。
const ID_TYPE_MAP = {
    article: 1,
    course: 2,
    tag: 3,
    user: 4
}

// 排序 → sortType（0 综合 / 1 最新 / 2 最热），预留透传给后端
const SORT_TYPE_MAP = {
    default: 0,
    time: 1,
    hot: 2
}

var api = {
    // 统一搜索：singleton 端点 /api/v1/search，分栏由 tag(→idType) 控制
    search: function(parms){
        let url = conf.urls.get('unified_search')
        return request.postByEquipmentId(url,{
            query: parms.keyword,
            idType: ID_TYPE_MAP[parms.tag] || 1,
            pageNum: parms.pageNum,
            pageSize: parms.pageSize || 20,
            sortType: SORT_TYPE_MAP[parms.sort] || 0
        })
    }
}

export default api