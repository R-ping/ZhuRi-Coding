<template>
  <div class="wrapper" :class="{ 'is-desktop': isDesktop }">
    <div class="top-body" v-if="!isDesktop"><Home_Bar/></div>
    <div class="content-body">
      <!-- 移动端Tab -->
      <wxc-tab-page v-if="!isDesktop" ref="wxc-tab-page" :tab-titles="tabTitles" :tab-styles="tabStyles"
        title-type="text" :tab-page-height="tabPageHeight"
        @wxcTabPageCurrentTabSelected="wxcTabPageCurrentTabSelected">
        <!-- 移动端子Tab和标签筛选 -->
        <div class="mobile-subheader" v-if="shouldShowSubTabs(tabTitles[currentTab].id)">
            <div class="sub-tabs">
                <span class="sub-tab" :class="{active: subTabStates[currentTab].current === 'recommend'}"
                      @click="switchSubTab(currentTab, 'recommend')">推荐</span>
                <span class="sub-tab" :class="{active: subTabStates[currentTab].current === 'latest'}"
                      @click="switchSubTab(currentTab, 'latest')">最新</span>
            </div>
            <div class="tag-filter" v-if="shouldShowTagFilter(tabTitles[currentTab].id)">
                <div class="tag-dropdown" @click.stop="toggleTagDropdown(currentTab)">
                    <span class="tag-dropdown-text">{{ subTabStates[currentTab].selectedTag === '__all__' ? '全部' : subTabStates[currentTab].selectedTag }}</span>
                    <span class="tag-arrow" :class="{up: tagDropdownOpen === currentTab}">&#9662;</span>
                </div>
                <div class="tag-panel" v-if="tagDropdownOpen === currentTab && subTabStates[currentTab].tags.length > 0">
                    <div class="tag-option" :class="{active: subTabStates[currentTab].selectedTag === '__all__'}"
                         @click="selectTag(currentTab, '__all__'); tagDropdownOpen = -1">全部</div>
                    <div class="tag-option" v-for="tag in subTabStates[currentTab].tags" :key="tag.tagName"
                         :class="{active: subTabStates[currentTab].selectedTag === tag.tagName}"
                         @click="selectTag(currentTab, tag.tagName); tagDropdownOpen = -1">
                        {{ tag.tagName }}<span class="tag-count" v-if="tag.count > 0">{{ tag.count }}</span>
                    </div>
                </div>
            </div>
        </div>
        <div v-for="(v,index) in tabList" :key="index" class="item-container"
          :style="{ height: (tabPageHeight - tabStyles.height) + 'px' }"
          ref="scrollContainers" @scroll="onScroll($event, index)">
          <div class="pull-refresh" v-if="tabStates[index] && tabStates[index].refreshing">
            <span class="loading-spinner"></span>
            <span class="loading-text">{{ load_new_text }}</span>
          </div>
          <!-- 移动端加载中骨架 -->
          <div class="loading-skeleton mobile-skeleton" v-if="tabStates[index] && tabStates[index].loading && !tabStates[index].refreshing && v.length === 0">
            <div class="skeleton-item" v-for="n in 3" :key="n">
              <div class="skeleton-content">
                <div class="skeleton-line skeleton-title"></div>
                <div class="skeleton-line"></div>
                <div class="skeleton-line skeleton-short"></div>
              </div>
            </div>
          </div>
          <div v-for="(item,key) in v" class="cell" :key="item.id || key"
            @click="wxcPanItemClicked(item)">
            <Item0 v-if="item.type === 0" :data="item" :showTime="subTabStates[index] && subTabStates[index].current === 'latest'"/>
            <Item1 v-if="item.type === 1" :data="item" :showTime="subTabStates[index] && subTabStates[index].current === 'latest'"/>
            <Item3 v-if="item.type === 3" :data="item" :showTime="subTabStates[index] && subTabStates[index].current === 'latest'"/>
          </div>
          <div class="loading" v-if="tabStates[index] && tabStates[index].loadingMore">
            <span class="loading-spinner"></span>
            <span class="loading-text">{{ load_more_text }}</span>
          </div>
          <div class="loading no-more" v-if="tabStates[index] && tabStates[index].noMore && v.length > 0">
            <span class="loading-text">— 没有更多了 —</span>
          </div>
          <div class="empty-state" v-if="tabStates[index] && tabStates[index].loaded && v.length === 0 && !tabStates[index].loading">
            <span class="empty-icon">&#xf15c;</span>
            <span class="empty-text">{{ tabStates[index].error ? '加载失败，点击重试' : '暂无内容' }}</span>
            <span class="retry-btn" v-if="tabStates[index].error" @click="handleRetry(index)">点击重试</span>
          </div>
        </div>
      </wxc-tab-page>

      <!-- Web端列表 -->
      <div class="desktop-list" v-if="isDesktop">
        <div class="list-container" @scroll="onDesktopScroll">
          <!-- Web端子Tab和标签筛选 -->
          <div class="desktop-subheader" v-if="shouldShowSubTabs(tabTitles[currentTab].id)">
              <div class="sub-tabs">
                  <span class="sub-tab" :class="{active: subTabStates[currentTab].current === 'recommend'}"
                        @click="switchSubTab(currentTab, 'recommend')">推荐</span>
                  <span class="sub-tab" :class="{active: subTabStates[currentTab].current === 'latest'}"
                        @click="switchSubTab(currentTab, 'latest')">最新</span>
              </div>
              <div class="tag-filter" v-if="shouldShowTagFilter(tabTitles[currentTab].id)">
                  <div class="tag-dropdown" @click.stop="toggleTagDropdown(currentTab)">
                      <span class="tag-dropdown-text">{{ subTabStates[currentTab].selectedTag === '__all__' ? '全部' : subTabStates[currentTab].selectedTag }}</span>
                      <span class="tag-arrow" :class="{up: tagDropdownOpen === currentTab}">&#9662;</span>
                  </div>
                  <div class="tag-panel" v-if="tagDropdownOpen === currentTab && subTabStates[currentTab].tags.length > 0">
                      <div class="tag-option" :class="{active: subTabStates[currentTab].selectedTag === '__all__'}"
                           @click="selectTag(currentTab, '__all__'); tagDropdownOpen = -1">全部</div>
                      <div class="tag-option" v-for="tag in subTabStates[currentTab].tags" :key="tag.tagName"
                           :class="{active: subTabStates[currentTab].selectedTag === tag.tagName}"
                           @click="selectTag(currentTab, tag.tagName); tagDropdownOpen = -1">
                          {{ tag.tagName }}<span class="tag-count" v-if="tag.count > 0">{{ tag.count }}</span>
                      </div>
                  </div>
              </div>
          </div>
          <div class="pull-refresh" v-if="currentState.refreshing">
            <span class="loading-spinner"></span>
            <span class="loading-text">{{ load_new_text }}</span>
          </div>
          <!-- 加载中骨架 -->
          <div class="loading-skeleton" v-if="currentState.loading && !currentState.refreshing && currentList.length === 0">
            <div class="skeleton-item" v-for="n in 5" :key="n">
              <div class="skeleton-image"></div>
              <div class="skeleton-content">
                <div class="skeleton-line skeleton-title"></div>
                <div class="skeleton-line"></div>
                <div class="skeleton-line skeleton-short"></div>
              </div>
            </div>
          </div>
          <div v-for="(item,key) in currentList" class="cell desktop-cell" :key="item.id || key"
            @click="wxcPanItemClicked(item)">
            <Item0 v-if="item.type === 0" :data="item" :showTime="currentShowTime"/>
            <Item1 v-if="item.type === 1" :data="item" :showTime="currentShowTime"/>
            <Item3 v-if="item.type === 3" :data="item" :showTime="currentShowTime"/>
          </div>
          <div class="loading" v-if="currentState.loadingMore">
            <span class="loading-spinner"></span>
            <span class="loading-text">{{ load_more_text }}</span>
          </div>
          <div class="loading no-more" v-if="currentState.noMore && currentList.length > 0">
            <span class="loading-text">— 没有更多了 —</span>
          </div>
          <div class="empty-state" v-if="currentState.loaded && currentList.length === 0 && !currentState.loading">
            <span class="empty-icon">&#xf15c;</span>
            <span class="empty-text">{{ currentState.error ? '加载失败，点击重试' : '暂无内容' }}</span>
            <span class="retry-btn" v-if="currentState.error" @click="handleRetry">点击重试</span>
          </div>
          <div class="list-bottom"></div>
        </div>
      </div>
    </div>
  </div>
</template>

<script>
  import Home_Bar from "@/components/bars/home_bar"
  import WxcTabPage from "@/components/tabs/home_tabs"
  import Utils from '@/utils/env'
  import Item0 from '../../components/cells/article_0.vue'
  import Item1 from '../../components/cells/article_1.vue'
  import Item3 from '../../components/cells/article_3.vue'
  import Config from './config'
  import feedMixin from './mixins/feedMixin'

  export default {
    name: 'HeiMa-Home',
    components: { Home_Bar, WxcTabPage, Item0, Item1, Item3 },
    mixins: [feedMixin],
    data: () => ({
      isDesktop: false,
      currentTab: 0,
      shownew: false,
      showmore: false,
      tagDropdownOpen: -1,
      tabTitles: Config.tabTitles,
      tabStyles: Config.tabStyles,
      tabList: [...Array(Config.tabTitles.length).keys()].map(() => []),
      tabPageHeight: 1334
    }),
    computed: {
      load_new_text: function () { return this.$lang.load_new_text },
      load_more_text: function () { return this.$lang.load_more_text },
      currentList: function() {
        return this.tabList[this.currentTab] || []
      },
      currentState: function() {
        return this.tabStates[this.currentTab] || {
          loaded: false, loading: false, loadingMore: false,
          refreshing: false, noMore: false, error: false, errorMsg: ''
        }
      },
      // 当前是否处于"最新"子分栏（最新分栏显示分钟级时间，推荐分栏不显示）
      currentShowTime: function() {
        return !!(this.subTabStates[this.currentTab] && this.subTabStates[this.currentTab].current === 'latest')
      }
    },
    mounted() {
      this.checkDevice()
      window.addEventListener('resize', this.checkDevice)
      this.$nextTick(() => {
        this.updateTabHeight()
        this.$nextTick(() => {
          if (!this.isDesktop && this.$refs['wxc-tab-page']) {
            this.$refs['wxc-tab-page'].setPage(0, null, true)
          }
        })
      })
      // 延迟到 checkDevice 完成后再加载数据
      this.$nextTick(() => {
        setTimeout(() => {
          if (this.isDesktop && !this.tabStates[this.currentTab].loaded && !this.recommendStates[this.currentTab].loaded) {
            this.loadCategoryFromRoute()
          }
        }, 0)
      })
    },
    watch: {
      '$route.params.category': function() {
        if (this.isDesktop) {
          this.tagDropdownOpen = -1
          this.loadCategoryFromRoute()
        }
      }
    },
    beforeDestroy() {
      window.removeEventListener('resize', this.checkDevice)
    },
    created() {
      this.tabPageHeight = Utils.getPageHeight()
    },
    methods: {
      updateTabHeight() {
        if (this.isDesktop) return
        const pageHeight = Utils.getPageHeight()
        const htmlFontSize = parseFloat(getComputedStyle(document.documentElement).fontSize)
        const topBarHeight = 90 / 75 * htmlFontSize
        this.tabPageHeight = Math.floor(pageHeight - topBarHeight)
      },
      checkDevice() {
        this.isDesktop = Utils.isDesktop()
        this.$nextTick(() => {
          this.updateTabHeight()
          this.$nextTick(() => {
            if (!this.isDesktop && this.$refs['wxc-tab-page']) {
              this.$refs['wxc-tab-page'].setPage(this.currentTab, null, false)
            }
          })
        })
      },
      loadCategoryFromRoute() {
        const category = this.$route.params.category || 'comprehensive'
        const tabIndex = this.getTabIndexByCategory(category)
        if (tabIndex !== -1) {
          if (!this.tabStates[tabIndex].loaded) {
            this.switchTab(tabIndex)
          } else {
            this.currentTab = tabIndex
          }
        }
      },
      getTabIndexByCategory(category) {
        const categoryMap = {
          'recommend': 0, 'following': 1, 'comprehensive': 2, 'backend': 3,
          'frontend': 4, 'android': 5, 'ios': 6, 'ai': 7, 'devtools': 8,
          'coderslife': 9, 'reading': 10, 'ranking': 11
        }
        return categoryMap[category] !== undefined ? categoryMap[category] : 2
      },
      wxcPanItemClicked(item) {
        if (!item || !item.id) return
        // 文章详情统一走 FTL SSR 页面
        window.open('/content/article/' + item.id, '_blank')
      },
      toggleTagDropdown(index) {
        if (this.tagDropdownOpen === index) {
          this.tagDropdownOpen = -1
        } else {
          this.tagDropdownOpen = index
          this.loadCategoryTags(index)
        }
      },
      handleRetry(index) {
        var tabIndex = (index !== undefined) ? index : this.currentTab
        var tabId = Config.tabTitles[tabIndex].id
        // 重置状态
        this.$set(this.tabStates, tabIndex, {
          loaded: false, loading: false, loadingMore: false,
          refreshing: false, noMore: false, error: false, errorMsg: ''
        })
        // 清空列表
        var newList = this.tabList.map(function(tab) { return tab.slice() })
        newList[tabIndex] = []
        this.tabList = newList
        // 根据子Tab类型重新加载
        var subTab = this.subTabStates[tabIndex] ? this.subTabStates[tabIndex].current : 'recommend'
        if (subTab === 'recommend' && this.shouldUseRecommend(tabId)) {
          this.resetRecommendState(tabIndex)
          this.recommendLoad(tabIndex)
        } else {
          this.load(tabIndex, 1)
        }
      }
    }
  };
</script>

<style lang="less" scoped src="./styles/home.less"></style>