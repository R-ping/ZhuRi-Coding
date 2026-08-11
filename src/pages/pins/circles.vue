<template>
    <div class="circles-page">
        <div class="art-top" v-if="!isDesktop"><HomeBar/></div>
        
        <div class="circles-content">
            <div class="circles-main">
                <!-- 我的圈子 -->
                <div class="section">
                    <h2 class="section-title">我的圈子</h2>
                    <div class="circles-grid" v-if="myCircles.length > 0">
                        <CircleCard
                            v-for="circle in myCircles"
                            :key="circle.id"
                            :circle="circle"
                            :joined="true"
                            :show-desc="true"
                            @toggle-join="toggleJoin"
                            @click.native="goToCircleDetail(circle)"
                        />
                    </div>
                    <div class="empty-state" v-else>暂无圈子</div>
                </div>

                <!-- 圈子广场 -->
                <div class="section">
                    <h2 class="section-title">圈子广场</h2>
                    <div class="circles-tabs">
                        <div 
                            class="tab-item"
                            v-for="cat in categories"
                            :key="cat.id"
                            :class="{ 'active': activeCategoryId === cat.id }"
                            @click="fetchCategoryCircles(cat.id)"
                        >{{ cat.name }}</div>
                    </div>

                    <div class="circles-loading" v-if="categoryLoading">加载中...</div>
                    <div class="circles-grid" v-else>
                        <CircleCard
                            v-for="circle in categoryCircles"
                            :key="circle.id"
                            :circle="circle"
                            :joined="isJoined(circle.id)"
                            :show-desc="true"
                            @toggle-join="toggleJoin"
                            @click.native="goToCircleDetail(circle)"
                        />
                        <div class="empty-state" v-if="categoryCircles.length === 0 && !categoryLoading">暂无圈子</div>
                    </div>
                </div>
            </div>

            <!-- 侧边栏 -->
            <div class="circles-sidebar">
                <div class="sidebar-section">
                    <h3 class="sidebar-title">人气圈子</h3>
                    <div class="popular-list">
                        <PopularCircleItem
                            v-for="circle in popularCircles"
                            :key="circle.id"
                            :circle="circle"
                            :joined="isJoined(circle.id)"
                            @toggle-join="toggleJoin"
                            @click.native="goToCircleDetail(circle)"
                        />
                    </div>
                </div>
            </div>
        </div>
    </div>
</template>

<script>
import HomeBar from '@/components/bars/home_bar'
import Utils from '@/utils/env'
import { toast } from '@/utils/toast'
import CircleCard from './components/CircleCard.vue'
import PopularCircleItem from './components/PopularCircleItem.vue'
import { getMyCircles, getSquareCircles, getHotCircles, joinCircle, leaveCircle, getCircleCategories, getCirclesByCategory } from '@/apis/circle'

export default {
    name: 'Circles',
    components: { HomeBar, CircleCard, PopularCircleItem },
    data() {
        return {
            activeTab: 'recommend',
            activeCategoryId: null,
            myCircles: [],
            allCircles: [],
            categories: [],
            categoryCircles: [],
            popularCircles: [],
            joinedCircleIds: [],
            squarePage: 1,
            squareSize: 20,
            hasMore: true,
            loading: false,
            categoryLoading: false
        }
    },
    computed: {
        isDesktop() {
            return Utils.isDesktop()
        },
        filteredCircles() {
            return this.allCircles
        }
    },
    mounted() {
        this.fetchMyCircles()
        this.fetchSquareCircles()
        this.fetchHotCircles()
        this.fetchCategories()
    },
    methods: {
        async fetchMyCircles() {
            try {
                const res = await getMyCircles()
                if (res && res.code === 200 && res.data) {
                    this.myCircles = res.data
                    this.joinedCircleIds = res.data.map(c => c.id)
                }
            } catch (e) {}
        },
        async fetchSquareCircles() {
            if (this.loading || !this.hasMore) return
            this.loading = true
            try {
                const res = await getSquareCircles(this.squarePage, this.squareSize)
                if (res && res.code === 200 && res.data) {
                    const data = res.data
                    const list = data.list || data.records || []
                    this.allCircles = this.squarePage === 1 ? list : [...this.allCircles, ...list]
                    this.hasMore = (data.has_more !== undefined) ? data.has_more : (list.length >= this.squareSize)
                }
            } catch (e) {} finally {
                this.loading = false
            }
        },
        async fetchHotCircles() {
            try {
                const res = await getHotCircles()
                if (res && res.code === 200 && res.data) {
                    this.popularCircles = res.data
                }
            } catch (e) {}
        },
        async fetchCategories() {
            try {
                const res = await getCircleCategories()
                if (res && res.code === 200 && res.data) {
                    this.categories = res.data
                    if (res.data.length > 0 && !this.activeCategoryId) {
                        this.activeCategoryId = res.data[0].id
                        this.fetchCategoryCircles(res.data[0].id)
                    }
                }
            } catch (e) {}
        },
        async fetchCategoryCircles(categoryId) {
            if (!categoryId) return
            this.activeCategoryId = categoryId
            this.categoryLoading = true
            try {
                const res = await getCirclesByCategory(categoryId, 1, 50)
                if (res && res.code === 200 && res.data) {
                    this.categoryCircles = res.data.list || res.data || []
                }
            } catch (e) {
                this.categoryCircles = []
            } finally {
                this.categoryLoading = false
            }
        },
        isJoined(circleId) {
            return this.joinedCircleIds.includes(circleId)
        },
        async toggleJoin(circle) {
            const isJoined = this.joinedCircleIds.includes(circle.id)
            try {
                if (isJoined) {
                    const res = await leaveCircle(circle.id)
                    if (res && res.code === 200) {
                        this.joinedCircleIds = this.joinedCircleIds.filter(id => id !== circle.id)
                        toast('已退出圈子', 2)
                        this.fetchMyCircles()
                    }
                } else {
                    const res = await joinCircle(circle.id)
                    if (res && res.code === 200) {
                        this.joinedCircleIds.push(circle.id)
                        toast('加入成功', 2)
                        this.fetchMyCircles()
                    }
                }
            } catch (e) {
                toast('操作失败，请重试', 2)
            }
        },
        loadMore() {
            if (this.hasMore && !this.loading) {
                this.squarePage++
                this.fetchSquareCircles()
            }
        },
        goToCircleDetail(circle) {
            this.$router.push(`/pins/circle/${circle.id}`)
        }
    }
}
</script>

<style lang="less" scoped>
@import '../../styles/common';

.circles-page {
    min-height: 100vh;
    background: #f7f8fa;
}

.circles-content {
    max-width: 1200px;
    margin: 0 auto;
    padding: 24px;
    display: flex;
    gap: 24px;
}

.circles-main {
    flex: 1;
}

.section {
    background: #fff;
    border-radius: 8px;
    padding: 20px;
    margin-bottom: 16px;
    box-shadow: 0 1px 2px rgba(0,0,0,0.05);
}

.section-title {
    font-size: 18px;
    font-weight: 600;
    color: #252933;
    margin-bottom: 16px;
}

/* 圈子广场 */
.circles-tabs {
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
    margin-bottom: 16px;
}

.tab-item {
    padding: 8px 16px;
    border-radius: 4px;
    font-size: 14px;
    color: #515767;
    cursor: pointer;
    background: #f7f8fa;
    transition: all 0.2s;
    &:hover {
        background: #eaf2ff;
        color: #1e80ff;
    }
    &.active {
        background: #1e80ff;
        color: #fff;
    }
}

.more-tab {
    color: #1e80ff;
    &:hover {
        background: #eaf2ff;
    }
}

.circles-grid {
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
    gap: 12px;
}

.circles-loading {
    text-align: center;
    padding: 40px;
    color: #c2c8d1;
}

.empty-state {
    text-align: center;
    padding: 40px;
    color: #c2c8d1;
    grid-column: 1 / -1;
}

/* 侧边栏 */
.circles-sidebar {
    width: 280px;
    flex-shrink: 0;
}

.sidebar-section {
    background: #fff;
    border-radius: 8px;
    padding: 16px;
    box-shadow: 0 1px 2px rgba(0,0,0,0.05);
}

.sidebar-title {
    font-size: 15px;
    font-weight: 600;
    color: #252933;
    margin-bottom: 12px;
}

.popular-list {
    display: flex;
    flex-direction: column;
    gap: 12px;
}

/* 响应式 */
@media screen and (max-width: 768px) {
    .circles-content {
        flex-direction: column;
        padding: 12px;
    }
    .circles-sidebar {
        width: 100%;
    }
    .circles-grid {
        grid-template-columns: 1fr;
    }
}
</style>