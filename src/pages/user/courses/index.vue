<template>
    <div class="courses-page">
        <div class="art-top" v-if="!isDesktop"><HomeBar/></div>
        <div class="courses-content">
            <!-- 选项卡 -->
            <div class="course-tabs">
                <div 
                    class="tab-item" 
                    :class="{ active: activeTab === 'courses' }"
                    @click="switchTab('courses')"
                >我的小册</div>
                <div 
                    class="tab-item" 
                    :class="{ active: activeTab === 'history' }"
                    @click="switchTab('history')"
                >浏览记录</div>
            </div>

            <!-- 我的小册内容 -->
            <div v-show="activeTab === 'courses'">
                <!-- 筛选选项卡 -->
                <div class="filter-tabs">
                    <div
                        v-for="tab in filterTabs"
                        :key="tab.key"
                        class="filter-tab"
                        :class="{ active: activeFilter === tab.key }"
                        @click="switchFilter(tab.key)"
                    >
                        {{ tab.label }}
                    </div>
                </div>

                <!-- 空状态：VIP借阅无数据 -->
                <div v-if="activeFilter === 'vip' && courses.length === 0 && !loading" class="empty-state">
                    <p class="empty-text">你还未借阅过任何课程～快去课程首页看看吧～！</p>
                    <button class="empty-btn" @click="$router.push('/course')">前往课程首页</button>
                </div>

                <!-- 全部 tab：网格视图 -->
                <div v-else-if="activeFilter === 'all' && courses.length > 0" class="course-grid">
                    <CourseGridCard
                        v-for="course in courses"
                        :key="course.id"
                        :course="course"
                        @click="goToDetail(course.id)"
                    />
                </div>

                <!-- 已购 / VIP借阅 tab：列表视图 -->
                <div v-else-if="activeFilter !== 'all' && courses.length > 0" class="course-list">
                    <CourseListItem
                        v-for="course in courses"
                        :key="course.id"
                        :course="course"
                        @click="goToDetail(course.id)"
                    />
                </div>

                <!-- 其他筛选无数据 -->
                <div v-else-if="courses.length === 0 && !loading" class="empty-state">
                    <p class="empty-text">暂无课程数据</p>
                </div>
            </div>

            <!-- 浏览记录 -->
            <div v-if="activeTab === 'history'" class="history-section">
                <!-- 搜索和清空 -->
                <div class="history-actions">
                    <div class="history-search">
                        <input v-model="historyKeyword" placeholder="搜索浏览记录" @keyup.enter="searchHistory" />
                        <span class="search-icon" @click="searchHistory">&#xf002;</span>
                    </div>
                    <span class="clear-btn" @click="clearHistory" v-if="historyList.length > 0">清空记录</span>
                </div>
                
                <!-- 加载中 -->
                <div v-if="historyLoading" class="loading-state">加载中...</div>
                
                <!-- 空状态 -->
                <div v-else-if="historyList.length === 0" class="empty-state">
                    <p class="empty-text">暂无浏览记录</p>
                </div>
                
                <!-- 浏览记录列表 - 按日期分组 -->
                <div v-else class="history-list">
                    <div v-for="group in groupedHistory" :key="group.date" class="history-group">
                        <div class="group-date">{{ group.date }}</div>
                        <div v-for="item in group.items" :key="item.id" class="history-item" @click="goToHistoryItem(item)">
                            <div class="item-title">{{ item.title || item.contentTitle || '无标题' }}</div>
                            <div class="item-meta">
                                <span class="item-type">{{ getTypeName(item.targetType || item.type) }}</span>
                                <span class="item-time">{{ formatTime(item.viewTime || item.createTime) }}</span>
                            </div>
                        </div>
                    </div>
                </div>
                
                <!-- 分页 -->
                <div v-if="historyPage.total > historyPage.size" class="history-pagination">
                    <span :class="{ disabled: historyPage.current <= 1 }" @click="historyPage.current--; loadHistory()">上一页</span>
                    <span>{{ historyPage.current }} / {{ Math.ceil(historyPage.total / historyPage.size) }}</span>
                    <span :class="{ disabled: historyPage.current >= Math.ceil(historyPage.total / historyPage.size) }" @click="historyPage.current++; loadHistory()">下一页</span>
                </div>
            </div>
        </div>
    </div>
</template>

<script>
import HomeBar from '@/components/bars/home_bar'
import Utils from '@/utils/env'
import CourseGridCard from './components/CourseGridCard.vue'
import CourseListItem from './components/CourseListItem.vue'
import request from '@/common/article_request'
import { toast } from '@/utils/toast'

export default {
    name: 'UserCourses',
    components: { HomeBar, CourseGridCard, CourseListItem },
    data() {
        return {
            activeFilter: 'all',
            courses: [],
            loading: false,
            filterTabs: [
                { key: 'all', label: '全部' },
                { key: 'purchased', label: '已购' },
                { key: 'vip', label: 'VIP借阅' }
            ],
            activeTab: 'courses',
            historyList: [],
            historyLoading: false,
            historyPage: { current: 1, size: 10, total: 0 },
            historyKeyword: ''
        }
    },
    computed: {
        isDesktop() {
            return Utils.isDesktop()
        },
        groupedHistory() {
            const groups = {}
            this.historyList.forEach(item => {
                const time = item.viewTime || item.createTime
                const date = time ? time.split(' ')[0] || time.split('T')[0] : '未知'
                if (!groups[date]) {
                    groups[date] = { date, items: [] }
                }
                groups[date].items.push(item)
            })
            return Object.values(groups)
        }
    },
    mounted() {
        this.loadCourses()
    },
    methods: {
        switchFilter(key) {
            this.activeFilter = key
            this.courses = []
            this.loadCourses()
        },
        async loadCourses() {
            this.loading = true
            try {
                const res = await request.get('/api/v1/content/my', {
                    params: { filter: this.activeFilter }
                })
                if (res && res.code === 200 && res.data) {
                    this.courses = res.data.list || []
                }
            } catch (e) {
                // Keep empty courses when API fails
            } finally {
                this.loading = false
            }
        },
        goToDetail(courseId) {
            this.$router.push('/course/' + courseId)
        },
        switchTab(tab) {
            this.activeTab = tab
            if (tab === 'history' && this.historyList.length === 0) {
                this.loadHistory()
            }
        },
        async loadHistory() {
            this.historyLoading = true
            try {
                const res = await request.get('/api/v1/browse-history/list', {
                    params: { 
                        page: this.historyPage.current, 
                        size: this.historyPage.size,
                        keyword: this.historyKeyword || undefined
                    }
                })
                if (res && res.code === 200 && res.data) {
                    this.historyList = res.data.list || res.data.records || []
                    this.historyPage.total = res.data.total || 0
                }
            } catch (e) {
                // Silent fail
            } finally {
                this.historyLoading = false
            }
        },
        async clearHistory() {
            try {
                const res = await request.delete('/api/v1/browse-history/clear')
                if (res && res.code === 200) {
                    this.historyList = []
                    this.historyPage.total = 0
                    toast('已清空浏览记录', 2)
                }
            } catch (e) {
                toast('清空失败', 2)
            }
        },
        searchHistory() {
            this.historyPage.current = 1
            this.loadHistory()
        },
        getTypeName(type) {
            const map = { 1: '文章', 2: '沸点', 3: '课程', 4: '专栏' }
            return map[type] || '其他'
        },
        formatTime(time) {
            if (!time) return ''
            const d = new Date(time)
            const now = new Date()
            const diff = now - d
            if (diff < 60000) return '刚刚'
            if (diff < 3600000) return Math.floor(diff / 60000) + '分钟前'
            if (diff < 86400000) return Math.floor(diff / 3600000) + '小时前'
            return d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0') + '-' + String(d.getDate()).padStart(2, '0')
        },
        goToHistoryItem(item) {
            const type = item.targetType || item.type
            const targetId = item.targetId || item.id
            if (type === 1 || type === '1') {
                // 文章详情统一走 FTL SSR 页面
                window.open('/content/article/' + targetId, '_blank')
            } else if (type === 2 || type === '2') {
                this.$router.push('/pins/' + targetId)
            } else if (type === 3 || type === '3') {
                this.$router.push('/course/' + targetId)
            } else if (type === 4 || type === '4') {
                this.$router.push('/column/' + targetId)
            }
        }
    }
}
</script>

<style lang="less" scoped>
@import '../../../styles/common';

.courses-page {
    min-height: 100vh;
    background: #f4f5f7;
}

.courses-content {
    max-width: 1200px;
    margin: 0 auto;
    padding: 24px;
}

.course-tabs {
    display: flex;
    background: #fff;
    border-radius: 16px 16px 0 0;
    box-shadow: 0 2px 12px rgba(0,0,0,0.06);
    overflow: hidden;
}

.tab-item {
    flex: 1;
    text-align: center;
    padding: 14px 0;
    font-size: 15px;
    color: #666;
    cursor: pointer;
    position: relative;
    transition: all 0.2s;
    
    &:hover { color: #1e80ff; }
    
    &.active {
        color: #1e80ff;
        font-weight: 600;
        &::after {
            content: '';
            position: absolute;
            bottom: 0;
            left: 50%;
            transform: translateX(-50%);
            width: 40px;
            height: 2px;
            background: #1e80ff;
            border-radius: 1px;
        }
    }
}

.filter-tabs {
    display: flex;
    background: #fff;
    padding: 0 24px;
    border-bottom: 1px solid #f0f2f5;
    border-radius: 0;
    box-shadow: 0 2px 12px rgba(0,0,0,0.06);
    margin-bottom: 24px;
}

.filter-tab {
    padding: 12px 0;
    margin-right: 24px;
    font-size: 15px;
    color: #666;
    cursor: pointer;
    position: relative;
    transition: color 0.2s;

    &:hover {
        color: #1e80ff;
    }

    &.active {
        color: #1e80ff;
        font-weight: 600;

        &::after {
            content: '';
            position: absolute;
            bottom: 0;
            left: 0;
            right: 0;
            height: 2px;
            background: #1e80ff;
            border-radius: 1px;
        }
    }
}

.course-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 24px;
}

.course-list {
  display: flex;
  flex-direction: column;
  background: #fff;
  border-radius: 12px;
  box-shadow: 0 2px 8px rgba(0,0,0,0.06);
  overflow: hidden;
}

.empty-state {
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    padding: 80px 0;
    background: #fff;
    border-radius: 12px;
    box-shadow: 0 2px 8px rgba(0,0,0,0.06);
}

.empty-text {
    font-size: 15px;
    color: #999;
    text-align: center;
    margin-bottom: 24px;
}

.empty-btn {
    padding: 8px 32px;
    border: none;
    border-radius: 20px;
    background: #1e80ff;
    color: #fff;
    font-size: 14px;
    cursor: pointer;
    transition: background-color 0.2s;

    &:hover {
        background: #4096ff;
    }
}

.history-section {
    background: #fff;
    border-radius: 0 0 16px 16px;
    box-shadow: 0 2px 12px rgba(0,0,0,0.06);
    padding: 16px 24px;
}

.history-actions {
    display: flex;
    justify-content: space-between;
    align-items: center;
    margin-bottom: 16px;
    padding-bottom: 12px;
    border-bottom: 1px solid #f0f2f5;
}

.history-search {
    display: flex;
    align-items: center;
    background: #f5f7fa;
    border-radius: 20px;
    padding: 6px 16px;
    width: 280px;
    
    input {
        flex: 1;
        border: none;
        outline: none;
        background: transparent;
        font-size: 14px;
        color: #333;
        &::placeholder { color: #999; }
    }
    
    .search-icon {
        font-family: fontawesome;
        font-size: 14px;
        color: #999;
        cursor: pointer;
        margin-left: 8px;
    }
}

.clear-btn {
    font-size: 13px;
    color: #999;
    cursor: pointer;
    &:hover { color: #ff4d4f; }
}

.history-list {
    .history-group {
        margin-bottom: 16px;
    }
    
    .group-date {
        font-size: 13px;
        color: #999;
        margin-bottom: 8px;
        padding-left: 4px;
    }
}

.history-item {
    padding: 12px;
    border-radius: 8px;
    cursor: pointer;
    transition: background 0.2s;
    margin-bottom: 4px;
    
    &:hover { background: #f5f7fa; }
    
    .item-title {
        font-size: 14px;
        color: #333;
        margin-bottom: 4px;
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
    }
    
    .item-meta {
        display: flex;
        gap: 12px;
        font-size: 12px;
        color: #999;
    }
}

.history-pagination {
    display: flex;
    justify-content: center;
    align-items: center;
    gap: 16px;
    padding: 16px 0;
    font-size: 13px;
    color: #666;
    
    span {
        cursor: pointer;
        &.disabled { color: #ccc; cursor: not-allowed; }
    }
}

.loading-state {
    text-align: center;
    padding: 40px 0;
    color: #999;
    font-size: 14px;
}

@media screen and (max-width: 1199px) {
    .course-grid {
        grid-template-columns: repeat(2, 1fr);
    }
}

@media screen and (max-width: 767px) {
    .courses-content {
        padding: 16px;
    }

    .course-grid {
        grid-template-columns: 1fr;
        gap: 16px;
    }

    .course-tabs {
        border-radius: 12px 12px 0 0;
    }

    .filter-tabs {
        padding: 0 16px;
        border-radius: 0;
    }

    .empty-state {
        padding: 60px 0;
    }
}
</style>