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
                <span class="sub-tab" v-for="st in getSubTabs(tabTitles[currentTab].id)" :key="st.key"
                      :class="{active: subTabStates[currentTab].current === st.key}"
                      @click="switchSubTab(currentTab, st.key)">{{ st.title }}</span>
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
            <Item0 v-if="item.type === 0" :data="item" :showTime="subTabStates[index] && subTabStates[index].current === 'latest'" @author-hover="onAuthorHover" @author-leave="onAuthorLeave" @author-click="onAuthorClick" @tag-click="onTagClick"/>
            <Item1 v-if="item.type === 1" :data="item" :showTime="subTabStates[index] && subTabStates[index].current === 'latest'" @author-hover="onAuthorHover" @author-leave="onAuthorLeave" @author-click="onAuthorClick" @tag-click="onTagClick"/>
            <Item3 v-if="item.type === 3" :data="item" :showTime="subTabStates[index] && subTabStates[index].current === 'latest'" @author-hover="onAuthorHover" @author-leave="onAuthorLeave" @author-click="onAuthorClick" @tag-click="onTagClick"/>
            <div class="feed-more" @mouseenter="openFeedMenu($event, item)" @mouseleave="onFeedMenuLeave">
              <span class="feed-more-dot">&#8942;</span>
              <div class="feed-menu" v-if="activeMenuId === (item.id || key)">
                <div class="feed-menu-item" @click.stop="onNotInterested(item)"><i class="fm-i">&#xf070;</i><span>不感兴趣</span></div>
                <div class="feed-menu-item" @click.stop="onBlockAuthor(item)"><i class="fm-i">&#xf05e;</i><span>屏蔽作者：{{item.source}}</span></div>
                <div class="feed-menu-item" v-if="item.tags && item.tags.length" @click.stop="onBlockTag(item)"><i class="fm-i">&#xf05e;</i><span>屏蔽标签：{{item.tags[0]}}</span></div>
                <div class="feed-menu-item" @click.stop="onReport(item)"><i class="fm-i">&#xf1d8;</i><span>举报</span></div>
              </div>
            </div>
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
      <div class="desktop-list" ref="desktopList" v-if="isDesktop">
        <div class="list-container" @scroll="onDesktopScroll">
          <!-- Web端子Tab和标签筛选 -->
          <div class="desktop-subheader">
              <div class="sub-tabs" v-if="shouldShowSubTabs(tabTitles[currentTab].id)">
                  <span class="sub-tab" v-for="st in getSubTabs(tabTitles[currentTab].id)" :key="st.key"
                        :class="{active: subTabStates[currentTab].current === st.key}"
                        @click="switchSubTab(currentTab, st.key)">{{ st.title }}</span>
              </div>
              <div class="tag-filter" v-if="shouldShowTagFilter(tabTitles[currentTab].id)">
                  <div class="tag-dropdown" @click.stop="toggleTagDropdown(currentTab)">
                      <span class="tag-dropdown-text">{{ subTabStates[currentTab].selectedTag === '__all__' ? '全部' : subTabStates[currentTab].selectedTag }}</span>
                      <span class="tag-arrow" :class="{up: tagDropdownOpen === currentTab}">&#9662;</span>
                  </div>
                  <div class="tag-panel" v-if="tagDropdownOpen === currentTab && subTabStates[currentTab].tags.length > 0">
                      <div class="tag-search-box">
                          <input class="tag-search-input" type="text" placeholder="搜索标签"
                                 v-model="tagSearchKeyword" @input="onTagSearchInput" />
                      </div>
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
            <Item0 v-if="item.type === 0" :data="item" :showTime="currentShowTime" @author-hover="onAuthorHover" @author-leave="onAuthorLeave" @author-click="onAuthorClick" @tag-click="onTagClick"/>
            <Item1 v-if="item.type === 1" :data="item" :showTime="currentShowTime" @author-hover="onAuthorHover" @author-leave="onAuthorLeave" @author-click="onAuthorClick" @tag-click="onTagClick"/>
            <Item3 v-if="item.type === 3" :data="item" :showTime="currentShowTime" @author-hover="onAuthorHover" @author-leave="onAuthorLeave" @author-click="onAuthorClick" @tag-click="onTagClick"/>
            <div class="feed-more" @click.stop @mouseenter="openFeedMenu($event, item)" @mouseleave="onFeedMenuLeave">
              <span class="feed-more-dot">&#8942;</span>
              <div class="feed-menu" v-if="activeMenuId === (item.id || key)">
                <div class="feed-menu-item" @click.stop="onNotInterested(item)"><i class="fm-i">&#xf070;</i><span>不感兴趣</span></div>
                <div class="feed-menu-item" @click.stop="onBlockAuthor(item)"><i class="fm-i">&#xf05e;</i><span>屏蔽作者：{{item.source}}</span></div>
                <div class="feed-menu-item" v-if="item.tags && item.tags.length" @click.stop="onBlockTag(item)"><i class="fm-i">&#xf05e;</i><span>屏蔽标签：{{item.tags[0]}}</span></div>
                <div class="feed-menu-item" @click.stop="onReport(item)"><i class="fm-i">&#xf1d8;</i><span>举报</span></div>
              </div>
            </div>
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

    <!-- 作者信息悬浮卡片 -->
    <AuthorHoverCard
      ref="authorHoverCard"
      :visible="showAuthorCard"
      :userId="authorCardUserId"
      :position="authorCardPosition"
      @close="closeAuthorHoverCard"
      @follow="onAuthorFollow"
      @message="onAuthorMessage"
      @go-profile="goToUserHome"
      @card-enter="onAuthorCardEnter"
      @card-leave="onAuthorCardLeave"
    />

    <!-- 桌面端回顶按钮 -->
    <div class="back-to-top" v-if="isDesktop && showBackToTop" @click="scrollToTop">
      <span class="icon">&#xf106;</span>
    </div>

    <!-- 刷新成功提示 -->
    <div class="refresh-toast" v-if="isDesktop && refreshToast.visible">
      {{ refreshToast.text }}
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
  import authorHoverCardMixin from '@/mixins/authorHoverCardMixin'
  import AuthorHoverCard from '@/components/search/AuthorHoverCard.vue'
  import { followUser } from '@/apis/follow'
  import { addBlock } from '@/apis/user'
  import { reportArticle, getTagDetail } from '@/apis/tag'
  import { toast, confirmDialog } from '@/utils/toast'

  export default {
    name: 'HeiMa-Home',
    components: { Home_Bar, WxcTabPage, Item0, Item1, Item3, AuthorHoverCard },
    mixins: [feedMixin, authorHoverCardMixin],
    data: () => ({
      isDesktop: false,
      currentTab: 0,
      shownew: false,
      showmore: false,
      tagDropdownOpen: -1,
      tabTitles: Config.tabTitles,
      tabStyles: Config.tabStyles,
      tabList: [...Array(Config.tabTitles.length).keys()].map(() => []),
      tabPageHeight: 1334,
      // 分栏切换后列表加载完成时触发内容淡入
      _pendingFade: false,
      // 桌面端回顶按钮：滚动超一屏时显示
      showBackToTop: false,
      // 刷新成功提示：已更新 N 条新内容
      refreshToast: { visible: false, text: '' },
      refreshToastTimer: null,
      // 三个点菜单：当前展开菜单所属的文章 id
      activeMenuId: -1,
      // 分类标签下拉搜索框：输入关键字与防抖计时器
      tagSearchKeyword: '',
      tagSearchTimer: null
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
      // 顶部频道分栏点击当前频道时触发主动刷新
      window.addEventListener('feed-refresh', this.handleGlobalRefresh)
      // 桌面端监听window滚动控制回顶按钮显示
      if (this.isDesktop) {
        window.addEventListener('scroll', this.handleWindowScroll)
      }
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
          // 标记分栏切换：列表加载完成后触发内容淡入动画
          this._pendingFade = true
          this.loadCategoryFromRoute()
        }
      },
      // 分栏切换后，列表从空到有数据时触发内容淡入
      currentList: function(newList) {
        if (this.isDesktop && this._pendingFade && newList && newList.length > 0) {
          this._pendingFade = false
          this.$nextTick(this.triggerContentFade)
        }
      }
    },
    created() {
      this.tabPageHeight = Utils.getPageHeight()
    },
    beforeDestroy() {
      if (this.refreshToastTimer) {
        clearTimeout(this.refreshToastTimer)
      }
      window.removeEventListener('resize', this.checkDevice)
      window.removeEventListener('feed-refresh', this.handleGlobalRefresh)
      if (this.isDesktop) {
        window.removeEventListener('scroll', this.handleWindowScroll)
      }
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
      // 分栏内容淡入：重新触发 desktop-list 的淡入动画
      triggerContentFade() {
        const el = this.$refs.desktopList
        if (!el) return
        el.classList.remove('content-fade')
        // 强制回流，确保动画重新触发
        void el.offsetWidth
        el.classList.add('content-fade')
      },
      getTabIndexByCategory(category) {
        const categoryMap = {
          'comprehensive': 0, 'backend': 1, 'frontend': 2, 'android': 3,
          'ios': 4, 'ai': 5, 'devtools': 6, 'coderslife': 7, 'reading': 8
        }
        return categoryMap[category] !== undefined ? categoryMap[category] : 0
      },
      wxcPanItemClicked(item) {
        if (!item || !item.id) return
        // 文章详情统一走 FTL SSR 页面
        window.open('/content/article/' + item.id, '_blank')
      },
      toggleTagDropdown(index) {
        // 打开下拉时清空搜索词并重置计时器，重新拉取全量 top15
        if (this.tagSearchKeyword) this.tagSearchKeyword = ''
        if (this.tagSearchTimer) { clearTimeout(this.tagSearchTimer); this.tagSearchTimer = null }
        if (this.tagDropdownOpen === index) {
          this.tagDropdownOpen = -1
        } else {
          this.tagDropdownOpen = index
          this.loadCategoryTags(index, '')
        }
      },
      // 标签搜索输入防抖：300ms 后按关键字重新拉取标签
      onTagSearchInput() {
        if (this.tagSearchTimer) clearTimeout(this.tagSearchTimer)
        var self = this
        this.tagSearchTimer = setTimeout(function() {
          self.loadCategoryTags(self.currentTab, self.tagSearchKeyword)
        }, 300)
      },
      handleRetry(index) {
        var tabIndex = (index !== undefined) ? index : this.currentTab
        // 重置状态
        this.$set(this.tabStates, tabIndex, {
          loaded: false, loading: false, loadingMore: false,
          refreshing: false, noMore: false, error: false, errorMsg: ''
        })
        // 清空列表
        var newList = this.tabList.map(function(tab) { return tab.slice() })
        newList[tabIndex] = []
        this.tabList = newList
        // 统一走 recommend 系列接口（subTab 由子分栏状态决定）
        this.resetRecommendState(tabIndex)
        this.recommendLoad(tabIndex)
      },
      // ============== 作者信息悬浮卡片 ==============
      onAuthorHover(payload) {
        if (!payload || !payload.userId) return
        this.showAuthorHoverCard(payload.userId, payload.event)
      },
      onAuthorClick(userId) {
        this.goToUserHome(userId)
      },
      async onAuthorFollow(userId) {
        var currentUserId = this.$store.state.userInfo && this.$store.state.userInfo.userId
        if (!currentUserId) {
          toast('请先登录')
          this.$store.dispatch('showLogin')
          return
        }
        try {
          const res = await followUser(currentUserId, userId)
          if (res && res.code === 200) {
            toast('操作成功', 2)
          } else {
            toast((res && res.message) || '操作失败', 2)
          }
        } catch (e) {
          toast('操作失败，请重试', 2)
        }
      },
      onAuthorMessage(payload) {
        this.closeAuthorHoverCard()
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
      },
      // 顶部频道分栏点击当前频道时触发主动刷新：重新加载当前分栏数据
      handleGlobalRefresh() {
        if (!this.isDesktop) return
        this.loadnew(this.currentTab)
      },
      showRefreshToast(newCount) {
        if (newCount === 0) {
          this.refreshToast.text = '已是最新内容'
        } else {
          this.refreshToast.text = `已更新 ${newCount} 条新内容`
        }
        this.refreshToast.visible = true
        // 清除旧定时器
        if (this.refreshToastTimer) {
          clearTimeout(this.refreshToastTimer)
        }
        // 2秒后自动隐藏
        this.refreshToastTimer = setTimeout(() => {
          this.refreshToast.visible = false
        }, 2000)
      },
      /**
       * 滚动回顶部：平滑滚动
       */
      scrollToTop() {
        const scrollContainer = this.isDesktop ? window : (this.$refs.scrollContainers && this.$refs.scrollContainers[this.currentTab])
        if (!scrollContainer) return
        if (this.isDesktop) {
          window.scrollTo({ top: 0, behavior: 'smooth' })
        } else {
          scrollContainer.scrollTo({ top: 0, behavior: 'smooth' })
        }
      },
      /**
       * 监听window滚动：滚动超一屏时显示回顶按钮
       */
      handleWindowScroll() {
        var scrollTop = window.pageYOffset || document.documentElement.scrollTop || document.body.scrollTop || 0
        this.showBackToTop = scrollTop > 500
      },
      // ============== 标签跳转 ==============
      // 点击文章卡片上的标签 -> 进入标签详情页
      onTagClick(tag) {
        if (!tag) return
        this.$router.push('/tag/' + encodeURIComponent(tag))
      },
      // ============== 三个点菜单 ==============
      // 鼠标移入"三个点"打开菜单
      openFeedMenu(e, item) {
        this.activeMenuId = item.id || -1
      },
      // 鼠标移出"三个点"关闭菜单
      onFeedMenuLeave() {
        this.activeMenuId = -1
      },
      // 校验登录态，未登录时弹出登录框
      ensureLogin() {
        const loggedIn = this.$store.getters && this.$store.getters.isLoggedIn
        if (!loggedIn) {
          toast('请先登录')
          this.$store.dispatch && this.$store.dispatch('showLogin')
          return false
        }
        return true
      },
      // 从当前所有分栏列表中移除指定文章
      removeArticleFromList(articleId) {
        var newList = this.tabList.map((tab) => {
          if (!tab) return tab
          return tab.filter((it) => !it || it.id !== articleId)
        })
        this.tabList = newList
      },
      // 从当前所有分栏列表中移除该作者的文章
      removeAuthorFromList(authorId) {
        var newList = this.tabList.map((tab) => {
          if (!tab) return tab
          return tab.filter((it) => !it || !authorId || it.authorId !== authorId)
        })
        this.tabList = newList
      },
      // 从当前所有分栏列表中移除含指定标签的文章
      removeTagFromList(tagName) {
        var newList = this.tabList.map((tab) => {
          if (!tab) return tab
          return tab.filter((it) => {
            if (!it || !it.tags || it.tags.length === 0) return true
            return it.tags.indexOf(tagName) === -1
          })
        })
        this.tabList = newList
      },
      // 不感兴趣：从信息流移除此文章（减少同类内容展示）
      onNotInterested(item) {
        if (!item || !item.id) return
        this.removeArticleFromList(item.id)
        toast('已为你减少此类内容推荐')
      },
      // 屏蔽作者：写入屏蔽关系 + 从信息流移除该作者全部文章
      async onBlockAuthor(item) {
        if (!item || !item.authorId) return
        var authorId = Number(item.authorId)
        if (!authorId || authorId === 0) {
          toast('该作者信息异常，无法屏蔽', 2)
          return
        }
        if (!this.ensureLogin()) return
        confirmDialog('确定屏蔽作者「' + (item.source || '该作者') + '」？屏蔽后该作者的文章将不再展示。', async (result) => {
          if (result !== 'OK') return
          try {
            const res = await addBlock({ type: 1, targetId: authorId })
            if (res && res.code === 200) {
              // 屏蔽成功后即时从列表移除（同口径：屏蔽关系也可在后台查询）
              this.removeAuthorFromList(authorId)
              toast('已屏蔽该作者')
            } else {
              toast((res && res.message) || '屏蔽失败，请稍后重试', 2)
            }
          } catch (e) {
            toast('屏蔽失败，请稍后重试', 2)
          }
        })
      },
      // 屏蔽标签：先按标签名解析标签ID，再写入屏蔽关系 + 从信息流移除含该标签的文章
      async onBlockTag(item) {
        if (!item || !item.tags || item.tags.length === 0) return
        var tagName = item.tags[0]
        if (!this.ensureLogin()) return
        confirmDialog('确定屏蔽标签「' + tagName + '」？屏蔽后含该标签的文章将不再展示。', async (result) => {
          if (result !== 'OK') return
          try {
            // 解析标签ID（屏蔽关系以 sys_tags.id 存储）
            var tagId = null
            const detail = await getTagDetail(tagName)
            if (detail && detail.code === 200 && detail.data && detail.data.id) {
              tagId = Number(detail.data.id)
            }
            if (!tagId) {
              toast('该标签不存在或无法屏蔽', 2)
              return
            }
            const res = await addBlock({ type: 2, targetId: tagId })
            if (res && res.code === 200) {
              this.removeTagFromList(tagName)
              toast('已屏蔽标签「' + tagName + '」')
            } else {
              toast((res && res.message) || '屏蔽失败，请稍后重试', 2)
            }
          } catch (e) {
            toast('屏蔽失败，请稍后重试', 2)
          }
        })
      },
      // 举报文章：确认后提交举报
      onReport(item) {
        if (!item || !item.id) return
        if (!this.ensureLogin()) return
        confirmDialog('举报该文章？举报内容将由平台审核处理。', async (result) => {
          if (result !== 'OK') return
          try {
            const res = await reportArticle(item.id, { reason: '内容违规', description: '' })
            if (res && res.code === 200) {
              toast('举报成功，感谢你的反馈')
            } else {
              toast((res && res.message) || '举报失败，请稍后重试', 2)
            }
          } catch (e) {
            toast('举报失败，请稍后重试', 2)
          }
        })
      }
    }
  };
</script>

<style lang="less" scoped src="./styles/home.less"></style>