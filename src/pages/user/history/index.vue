<template>
    <div class="history-page">
        <div class="art-top" v-if="!isDesktop"><HomeBar /></div>
        <div class="history-content">
            <!-- 标题栏卡片 -->
            <div class="history-toolbar">
                <div class="toolbar-title">浏览记录</div>
                <div class="toolbar-actions">
                    <div class="search-box">
                        <i class="search-icon">&#xf002;</i>
                        <input
                            type="text"
                            class="search-input"
                            v-model="searchKeyword"
                            placeholder="搜索标题关键词"
                            @input="onSearchInput"
                        />
                        <i v-if="searchKeyword" class="clear-icon" @click="clearSearch">&#xf00d;</i>
                    </div>
                    <button class="clear-btn" @click="handleClearAll">清空</button>
                </div>
            </div>

            <!-- 列表区域 -->
            <div class="history-list" v-loading="loading">
                <div v-if="groupedHistory.length === 0 && !loading" class="empty-state">
                    <i class="empty-icon">&#xf187;</i>
                    <span class="empty-text">暂无浏览记录</span>
                    <button class="empty-btn" @click="goHome">去首页逛逛</button>
                </div>
                <div v-for="group in groupedHistory" :key="group.date" class="history-group">
                    <div class="date-header">{{ group.date }}</div>
                    <div class="article-list">
                        <HistoryItem
                            v-for="item in group.items"
                            :key="item.id"
                            :item="item"
                            @click="goToArticle(item.articleId, item.targetType)"
                        />
                    </div>
                </div>
            </div>

            <!-- 分页 -->
            <div class="pagination-wrap" v-if="total > pageSize">
                <el-pagination
                    background
                    layout="prev, pager, next"
                    :total="total"
                    :page-size="pageSize"
                    :current-page.sync="currentPage"
                    @current-change="onPageChange"
                />
            </div>
        </div>

        <!-- 确认弹窗 -->
        <ConfirmModal
            v-if="showClearModal"
            title="确定清空浏览记录吗？"
            content="浏览记录清除后无法恢复"
            @confirm="doClearAll"
            @cancel="showClearModal = false"
        />
    </div>
</template>

<script>
import HomeBar from '@/components/bars/home_bar'
import Utils from '@/utils/env'
import { toast } from '@/utils/toast'
import { getBrowseHistory, clearBrowseHistory } from '@/apis/history'
import ConfirmModal from '@/components/common/ConfirmModal'
import HistoryItem from './components/HistoryItem.vue'
import { formatDate } from '@/utils/format'

export default {
    name: 'UserHistory',
    components: { HomeBar, ConfirmModal, HistoryItem },
    data() {
        return {
            loading: false,
            searchKeyword: '',
            searchTimer: null,
            historyList: [],
            currentPage: 1,
            pageSize: 10,
            total: 0,
            showClearModal: false
        }
    },
    computed: {
        isDesktop() {
            return Utils.isDesktop()
        },
        groupedHistory() {
            const groups = {}
            this.historyList.forEach((item) => {
                const date = this.formatDate(item.browseTime)
                if (!groups[date]) {
                    groups[date] = []
                }
                groups[date].push(item)
            })
            return Object.keys(groups).map((date) => ({
                date,
                items: groups[date]
            }))
        }
    },
    methods: {
        async loadHistory() {
            this.loading = true
            try {
                const params = {
                    page: this.currentPage,
                    size: this.pageSize
                }
                if (this.searchKeyword) {
                    params.keyword = this.searchKeyword
                }
                const res = await getBrowseHistory(params)
                if (res && res.code === 200 && res.data) {
                    this.historyList = res.data.list || []
                    this.total = res.data.total || 0
                } else {
                    const msg = (res && res.message) || '加载失败'
                    toast(msg, 2)
                }
            } catch (e) {
                console.error('Failed to load browse history:', e)
            } finally {
                this.loading = false
            }
        },
        onSearchInput() {
            if (this.searchTimer) {
                clearTimeout(this.searchTimer)
            }
            this.searchTimer = setTimeout(() => {
                this.currentPage = 1
                this.loadHistory()
            }, 300)
        },
        clearSearch() {
            this.searchKeyword = ''
            this.currentPage = 1
            this.loadHistory()
        },
        handleClearAll() {
            this.showClearModal = true
        },
        async doClearAll() {
            this.showClearModal = false
            try {
                const res = await clearBrowseHistory()
                if (res && res.code === 200) {
                    toast('已清空浏览记录', 2)
                    this.historyList = []
                    this.total = 0
                    this.currentPage = 1
                } else {
                    const msg = (res && res.message) || '清空记录失败'
                    toast(msg, 2)
                }
            } catch (e) {
                toast('清空记录失败', 2)
            }
        },
        onPageChange(page) {
            this.currentPage = page
            this.loadHistory()
            window.scrollTo({ top: 0, behavior: 'smooth' })
        },
        goToArticle(articleId, type) {
            if (type === 2) {
                this.$router.push('/boiling/' + articleId)
            } else if (type === 3) {
                this.$router.push('/course/' + articleId)
            } else if (type === 4) {
                this.$router.push('/column/' + articleId)
            } else {
                // 文章详情统一走 FTL SSR 页面
                window.open('/content/article/' + articleId, '_blank')
            }
        },
        goHome() {
            this.$router.push('/')
        },
        formatDate
    },
    mounted() {
        this.loadHistory()
    },
    beforeDestroy() {
        if (this.searchTimer) {
            clearTimeout(this.searchTimer)
        }
    }
}
</script>

<style lang="less" scoped>
.history-page {
    min-height: 100vh;
    background: #F5F7FA;
}

.history-content {
    max-width: 960px;
    margin: 0 auto;
    padding: 24px;
}

// 标题栏卡片
.history-toolbar {
    display: flex;
    justify-content: space-between;
    align-items: center;
    margin-bottom: 20px;
    padding: 16px 20px;
    background: #fff;
    border-radius: 8px;
}

.toolbar-title {
    font-size: 20px;
    font-weight: 600;
    color: #1A1A1A;
}

.toolbar-actions {
    display: flex;
    align-items: center;
    gap: 16px;
}

.search-box {
    position: relative;
    display: flex;
    align-items: center;
    width: 200px;
    height: 36px;
    border: 1px solid #E4E6EB;
    border-radius: 6px;
    padding: 0 12px;
    transition: border-color 0.2s;
    &:focus-within {
        border-color: #1E80FF;
    }
}

.search-icon {
    font-family: fontawesome;
    font-size: 14px;
    color: #8A919F;
    margin-right: 8px;
    flex-shrink: 0;
}

.search-input {
    flex: 1;
    border: none;
    background: transparent;
    font-size: 14px;
    color: #252933;
    outline: none;
    width: 100%;
    &::placeholder {
        color: #C4C9D1;
    }
}

.clear-icon {
    font-family: fontawesome;
    font-size: 12px;
    color: #8A919F;
    cursor: pointer;
    flex-shrink: 0;
    padding: 2px;
    &:hover {
        color: #515767;
    }
}

.clear-btn {
    padding: 8px 20px;
    border: 1px solid #FF4D4F;
    border-radius: 6px;
    background: #fff;
    color: #FF4D4F;
    font-size: 14px;
    cursor: pointer;
    white-space: nowrap;
    transition: all 0.2s;
    &:hover {
        background: #FFF2F0;
    }
}

// 列表区域
.history-list {
    min-height: 300px;
}

.history-group {
    margin-bottom: 16px;
}

.date-header {
    padding: 12px 20px;
    background: #F2F3F5;
    border-radius: 8px 8px 0 0;
    font-size: 14px;
    font-weight: 600;
    color: #515767;
}

.article-list {
    background: #fff;
    border-radius: 0 0 8px 8px;
    overflow: hidden;
}

// 空状态
.empty-state {
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    padding: 80px 0;
    background: #fff;
    border-radius: 8px;
}

.empty-icon {
    font-family: fontawesome;
    font-size: 48px;
    color: #C4C9D1;
    margin-bottom: 16px;
    font-style: normal;
}

.empty-text {
    font-size: 14px;
    color: #8A919F;
}

.empty-btn {
    margin-top: 16px;
    padding: 8px 24px;
    background: #1E80FF;
    color: #fff;
    border: none;
    border-radius: 6px;
    font-size: 14px;
    cursor: pointer;
    transition: background 0.2s;
    &:hover {
        background: #1A6FD9;
    }
}

// 分页
.pagination-wrap {
    display: flex;
    justify-content: center;
    padding: 24px 0;
}

// 响应式
@media screen and (max-width: 768px) {
    .history-content {
        padding: 16px;
    }
    .history-toolbar {
        flex-direction: column;
        gap: 12px;
        align-items: flex-start;
    }
    .toolbar-actions {
        flex-direction: column;
        width: 100%;
        gap: 12px;
    }
    .search-box {
        width: 100%;
    }
    .clear-btn {
        width: 100%;
    }
}
</style>