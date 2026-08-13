<template>
    <div class="search-result-page">
        <!-- Tab切换 -->
        <div class="search-tabs">
            <div
                v-for="(tab, index) in tabList"
                :key="tab.id"
                class="tab-item"
                :class="{ active: currentTab === index }"
                @click="onTabClick(index)"
            >{{ tab.name }}</div>
        </div>

        <!-- 排序筛选 -->
        <div class="search-filter" v-if="currentTab === 0">
            <div class="filter-left">
                <span
                    v-for="item in sortOptions"
                    :key="item.value"
                    class="sort-item"
                    :class="{ active: currentSort === item.value }"
                    @click="onSortChange(item.value)"
                >{{ item.label }}</span>
            </div>
            <div class="filter-right">
                <span
                    v-for="item in timeOptions"
                    :key="item.value"
                    class="time-item"
                    :class="{ active: currentTime === item.value }"
                    @click="onTimeChange(item.value)"
                >{{ item.label }}</span>
            </div>
        </div>

        <!-- 搜索结果列表 -->
        <div class="result-list">
            <!-- 骨架屏加载 -->
            <div v-if="currentState && currentState.loading && currentData.length === 0" class="skeleton-list">
                <div class="skeleton-card" v-for="n in 5" :key="n">
                    <div class="sk-line sk-avatar"></div>
                    <div class="sk-line sk-title"></div>
                    <div class="sk-line sk-summary"></div>
                    <div class="sk-line sk-meta"></div>
                </div>
            </div>

            <!-- 列表项 -->
            <template v-else-if="currentData.length > 0">
                <SearchResultArticle
                    v-for="item in currentData"
                    :key="item.id"
                    :data="item"
                    :keyword="params.keyword"
                    @like="onLike"
                    @comment="onComment"
                    @tag-click="onTagClick"
                    @author-hover="onAuthorHover"
                    @author-leave="onAuthorLeave"
                    @title-click="onTitleClick"
                />

                <!-- 加载更多 -->
                <div class="loading-more" v-if="currentState && currentState.loadingMore">
                    <span class="loading-text">加载中...</span>
                </div>

                <!-- 没有更多 -->
                <div class="no-more" v-if="currentState && currentState.noMore">
                    <span>— 没有更多了 —</span>
                </div>
            </template>

            <!-- 空状态 -->
            <div class="empty-state" v-else-if="currentState && currentState.loaded && !currentState.loading">
                <span class="empty-icon">&#xf002;</span>
                <span class="empty-text">未找到与 "{{ params.keyword }}" 相关的结果</span>
                <span class="empty-tip">试试更换关键词</span>
            </div>

            <!-- 错误状态 -->
            <div class="error-state" v-else-if="currentState && currentState.error">
                <span class="error-text">{{ currentState.errorMsg }}</span>
                <span class="retry-btn" @click="load()">点击重试</span>
            </div>
        </div>

        <!-- 作者悬浮卡片 -->
        <AuthorHoverCard
            :visible="showAuthorCard"
            :author="authorCardData"
            :position="authorCardPosition"
            @close="showAuthorCard = false"
            @follow="onAuthorFollow"
            @message="onAuthorMessage"
        />
    </div>
</template>

<script>
    import { toast } from "@/utils/toast"
    import SearchResultArticle from '@/components/search/SearchResultArticle.vue'
    import AuthorHoverCard from '@/components/search/AuthorHoverCard.vue'
    import Api from '@/apis/search_result/api'

    export default {
        name: 'SearchResult',
        components: { SearchResultArticle, AuthorHoverCard },
        props: {
            keyword: {
                type: String,
                default: ''
            }
        },
        data() {
            return {
                // Tab配置
                tabList: [
                    { id: 'article', name: '综合' },
                    { id: 'article', name: '文章' },
                    { id: 'course', name: '课程' },
                    { id: 'tag', name: '标签' },
                    { id: 'user', name: '用户' }
                ],
                currentTab: 0,
                // 排序选项
                sortOptions: [
                    { label: '综合排序', value: 'default' },
                    { label: '最新优先', value: 'time' },
                    { label: '最热优先', value: 'hot' }
                ],
                currentSort: 'default',
                // 时间筛选
                timeOptions: [
                    { label: '时间不限', value: 'all' },
                    { label: '一天内', value: 'day' },
                    { label: '一周内', value: 'week' },
                    { label: '一月内', value: 'month' }
                ],
                currentTime: 'all',
                // 数据列表
                articleData: [],
                courseData: [],
                tagData: [],
                userData: [],
                // 加载状态
                articleState: {
                    loaded: false,
                    loading: false,
                    loadingMore: false,
                    noMore: false,
                    error: false,
                    errorMsg: ''
                },
                params: {
                    keyword: '',
                    pageNum: 1,
                    pageSize: 20,
                    tag: 'article',
                    sort: 'default',
                    time: 'all'
                },
                totalCount: 0,
                showAuthorCard: false,
                authorCardData: {},
                authorCardPosition: { top: 0, left: 0 }
            }
        },
        computed: {
            currentData() {
                var dataMap = [this.articleData, this.courseData, this.tagData, this.userData]
                return dataMap[this.currentTab] || []
            },
            currentState() {
                var stateMap = [this.articleState, {loaded: true, loading: false}, {loaded: true, loading: false}, {loaded: true, loading: false}]
                return stateMap[this.currentTab] || {loaded: false, loading: false}
            }
        },
        created() {
            this.params.keyword = this.$route.query.keyword || this.keyword || ''
            if (this.params.keyword) {
                this.load()
            }
        },
        methods: {
            onTabClick(index) {
                if (this.currentTab === index) return
                this.currentTab = index
                var tabIds = ['article', 'article', 'course', 'tag', 'user']
                this.params.tag = tabIds[index]
                this.params.pageNum = 1
                
                // 如果是文章tab且已加载，不重新加载
                if (index === 0 && this.articleState.loaded) {
                    return
                }
                
                if (index <= 1) {
                    this.load()
                }
            },
            onSortChange(sort) {
                this.currentSort = sort
                this.params.sort = sort
                this.params.pageNum = 1
                this.articleState.loaded = false
                this.load()
            },
            onTimeChange(time) {
                this.currentTime = time
                this.params.time = time
                this.params.pageNum = 1
                this.articleState.loaded = false
                this.load()
            },
            load() {
                if (!this.params.keyword) return
                this.$set(this.articleState, 'loading', true)
                this.$set(this.articleState, 'error', false)
                
                Api.article_search(this.params).then((d) => {
                    this.$set(this.articleState, 'loading', false)
                    this.$set(this.articleState, 'loadingMore', false)
                    this.$set(this.articleState, 'loaded', true)
                    
                    if (d && d.code === 200) {
                        if (d.data && d.data.length > 0) {
                            this.transformData(d.data)
                        } else {
                            this.$set(this.articleState, 'noMore', true)
                            if (this.params.pageNum === 1) {
                                this.articleData = []
                                this.totalCount = 0
                            }
                        }
                    } else {
                        this.$set(this.articleState, 'error', true)
                        this.$set(this.articleState, 'errorMsg', (d && d.errorMessage) || '搜索失败')
                    }
                }).catch(() => {
                    this.$set(this.articleState, 'loading', false)
                    this.$set(this.articleState, 'loadingMore', false)
                    this.$set(this.articleState, 'loaded', true)
                    this.$set(this.articleState, 'error', true)
                    this.$set(this.articleState, 'errorMsg', '网络请求失败，请检查网络连接')
                })
            },
            transformData(data) {
                if (!data || data.length === 0) {
                    this.$set(this.articleState, 'noMore', true)
                    return
                }
                
                var arr = []
                for (var i = 0; i < data.length; i++) {
                    try {
                        var item = data[i]
                        // 处理封面图
                        var coverImage = ''
                        if (item.images) {
                            var ims = []
                            if (typeof item.images === 'string') {
                                ims = item.images.replace(/[\[\]]/g, '').split(',').filter(function(s) { return s.trim() })
                            } else if (Array.isArray(item.images)) {
                                ims = item.images
                            }
                            if (ims.length > 0) {
                                coverImage = ims[0]
                            }
                        }
                        
                        // 处理标签
                        var tags = []
                        if (item.tags && Array.isArray(item.tags)) {
                            tags = item.tags
                        } else if (item.labels && Array.isArray(item.labels)) {
                            tags = item.labels.map(function(label, idx) {
                                if (typeof label === 'string') {
                                    return { id: 'tag_' + idx, name: label }
                                }
                                return label
                            })
                        } else if (item.labelList && Array.isArray(item.labelList)) {
                            tags = item.labelList
                        }
                        
                        var tmp = {
                            id: item.id,
                            title: item.h_title || item.title || '',
                            summary: item.summary || item.description || item.digest || '',
                            coverImage: coverImage,
                            authorId: item.authorId || item.userId || '',
                            authorName: item.authorName || item.source || '匿名用户',
                            authorAvatar: item.authorAvatar || item.avatar || '',
                            authorLevel: item.authorLevel || item.level || '',
                            authorBio: item.authorBio || item.bio || '',
                            publishTime: item.publishTime || item.date || '',
                            tags: tags,
                            likeCount: item.likeCount || item.likes || 0,
                            liked: item.liked || item.hasLiked || false,
                            commentCount: item.commentCount || item.comment || 0
                        }
                        arr.push(tmp)
                    } catch (e) {
                        console.warn('数据转换异常:', e)
                    }
                }
                
                if (this.params.pageNum !== 1) {
                    this.articleData = this.articleData.concat(arr)
                } else {
                    this.articleData = arr
                }
                this.totalCount = this.articleData.length
            },
            // 点赞处理
            onLike(articleId, liked) {
                var self = this
                this.articleData.forEach(function(item) {
                    if (item.id === articleId) {
                        self.$set(item, 'liked', liked)
                        if (liked) {
                            self.$set(item, 'likeCount', (item.likeCount || 0) + 1)
                        } else {
                            self.$set(item, 'likeCount', Math.max(0, (item.likeCount || 0) - 1))
                        }
                    }
                })
                
                if (!this.$store.getters.isLoggedIn) {
                    toast('请先登录后再点赞')
                    this.articleData.forEach(function(item) {
                        if (item.id === articleId) {
                            self.$set(item, 'liked', !liked)
                            if (liked) {
                                self.$set(item, 'likeCount', Math.max(0, (item.likeCount || 0) - 1))
                            } else {
                                self.$set(item, 'likeCount', (item.likeCount || 0) + 1)
                            }
                        }
                    })
                    return
                }
            },
            // 评论跳转
            onComment(articleId) {
                // 文章详情统一走 FTL SSR 页面
                window.open('/content/article/' + articleId, '_blank')
            },
            // 标题点击
            onTitleClick(articleId) {
                // 文章详情统一走 FTL SSR 页面
                window.open('/content/article/' + articleId, '_blank')
            },
            // 标签点击
            onTagClick(tagId, tagName) {
                this.$router.push({
                    path: '/search_result',
                    query: { keyword: tagName }
                })
            },
            // 作者悬浮
            onAuthorHover(authorData, event) {
                this.authorCardData = {
                    id: authorData.authorId,
                    name: authorData.authorName,
                    avatar: authorData.authorAvatar,
                    level: authorData.authorLevel,
                    bio: authorData.authorBio,
                    followCount: authorData.followCount || 0,
                    followerCount: authorData.followerCount || 0,
                    isFollowed: authorData.isFollowed || false
                }
                
                var rect = event.target.getBoundingClientRect()
                var cardTop = rect.bottom + 8
                var cardLeft = rect.left
                
                if (cardLeft + 240 > window.innerWidth) {
                    cardLeft = window.innerWidth - 250
                }
                
                this.authorCardPosition = {
                    top: cardTop,
                    left: cardLeft,
                    arrow: 'top'
                }
                this.showAuthorCard = true
            },
            // 作者离开
            onAuthorLeave() {
                var self = this
                setTimeout(function() {
                    self.showAuthorCard = false
                }, 300)
            },
            // 作者关注
            onAuthorFollow(authorId) {
                toast('关注功能开发中')
                this.showAuthorCard = false
            },
            // 作者私信
            onAuthorMessage(payload) {
                this.showAuthorCard = false
                if (!payload || !payload.userId) return
                this.$router.push({
                    path: '/notification',
                    query: {
                        tab: 'message',
                        peer_id: payload.userId,
                        peer_name: payload.name || '',
                        peer_avatar: payload.avatar || ''
                    }
                })
            }
        }
    }
</script>

<style lang="less" scoped>
    .search-result-page {
        width: 100%;
        padding-bottom: 40px;
        background-color: #fff;
        border-radius: 8px;
        min-height: 400px;
    }

    /* Tab切换 */
    .search-tabs {
        display: flex;
        gap: 0;
        margin-bottom: 0;
        border-bottom: 1px solid #f0f0f0;
    }

    .tab-item {
        padding: 12px 20px;
        font-size: 15px;
        color: #515767;
        cursor: pointer;
        position: relative;
        transition: color 0.2s;
    }

    .tab-item:hover {
        color: #1E80FF;
    }

    .tab-item.active {
        color: #1E80FF;
        font-weight: 500;
    }

    .tab-item.active::after {
        content: '';
        position: absolute;
        bottom: -1px;
        left: 50%;
        transform: translateX(-50%);
        width: 24px;
        height: 3px;
        background-color: #1E80FF;
        border-radius: 2px;
    }

    /* 排序筛选 */
    .search-filter {
        display: flex;
        justify-content: space-between;
        align-items: center;
        margin-bottom: 20px;
        padding: 10px 16px;
        background-color: #fff;
        border-radius: 8px;
    }

    .filter-left {
        display: flex;
        gap: 24px;
    }

    .filter-right {
        display: flex;
        gap: 8px;
    }

    .sort-item {
        font-size: 14px;
        color: #8A93A6;
        cursor: pointer;
        padding: 4px 8px;
        border-radius: 4px;
        transition: all 0.2s;
    }

    .sort-item:hover {
        color: #333;
    }

    .sort-item.active {
        color: #1E80FF;
        font-weight: 500;
    }

    .time-item {
        font-size: 13px;
        color: #8A93A6;
        cursor: pointer;
        padding: 4px 10px;
        border-radius: 4px;
        transition: all 0.2s;
    }

    .time-item:hover {
        color: #333;
        background-color: #f5f5f5;
    }

    .time-item.active {
        color: #1E80FF;
        background-color: #E8F3FF;
    }

    /* 结果列表 */
    .result-list {
        min-height: 300px;
    }

    /* 骨架屏 */
    .skeleton-list {
        display: flex;
        flex-direction: column;
        gap: 12px;
    }

    .skeleton-card {
        background-color: #fff;
        border-radius: 8px;
        padding: 16px;
        display: flex;
        flex-direction: column;
        gap: 12px;
    }

    .sk-line {
        height: 14px;
        background: linear-gradient(90deg, #f0f0f0 25%, #e8e8e8 50%, #f0f0f0 75%);
        background-size: 200% 100%;
        animation: skeleton-loading 1.5s infinite;
        border-radius: 4px;
    }

    .sk-avatar {
        width: 40px;
        height: 40px;
        border-radius: 50%;
    }

    .sk-title {
        height: 20px;
        width: 70%;
    }

    .sk-summary {
        height: 12px;
        width: 90%;
    }

    .sk-meta {
        height: 12px;
        width: 30%;
    }

    @keyframes skeleton-loading {
        0% { background-position: 200% 0; }
        100% { background-position: -200% 0; }
    }

    /* 加载更多 */
    .loading-more, .no-more {
        text-align: center;
        padding: 20px;
        font-size: 13px;
        color: #8A93A6;
    }

    /* 空状态 */
    .empty-state {
        display: flex;
        flex-direction: column;
        align-items: center;
        justify-content: center;
        padding: 60px 20px;
        gap: 12px;
    }

    .empty-icon {
        font-family: fontawesome;
        font-size: 48px;
        color: #d0d0d0;
    }

    .empty-text {
        font-size: 15px;
        color: #666;
    }

    .empty-tip {
        font-size: 13px;
        color: #999;
    }

    /* 错误状态 */
    .error-state {
        display: flex;
        flex-direction: column;
        align-items: center;
        padding: 40px 20px;
        gap: 16px;
    }

    .error-text {
        font-size: 14px;
        color: #F53F3F;
    }

    .retry-btn {
        padding: 8px 24px;
        background-color: #1E80FF;
        color: #fff;
        border-radius: 4px;
        font-size: 14px;
        cursor: pointer;
    }

    .retry-btn:hover {
        background-color: #1a7de8;
    }
</style>
