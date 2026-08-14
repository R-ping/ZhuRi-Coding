import Api from '@/apis/home/api'
import Config from '../config'

export default {
  data() {
    return {
      tabStates: [...Array(Config.tabTitles.length).keys()].map(() => ({
        loaded: false,
        loading: false,
        loadingMore: false,
        refreshing: false,
        noMore: false,
        error: false,
        errorMsg: ''
      })),
      params: {
        loaddir: 1,
        index: 0,
        tag: '__all__',
        size: 10,
        max_behot_time: 0,
        min_behot_time: 20000000000000
      },
      // 每个标签页独立的推荐状态（seed + page）
      recommendStates: [...Array(Config.tabTitles.length).keys()].map(() => ({
        loaded: false,
        loading: false,
        loadingMore: false,
        refreshing: false,
        noMore: false,
        error: false,
        errorMsg: '',
        seed: null,
        page: 0
      })),
      // 每个标签页的子Tab状态（推荐/最新）
      subTabStates: [...Array(Config.tabTitles.length).keys()].map(() => ({
        current: 'recommend',
        tags: [],
        selectedTag: '__all__',
        tagsLoaded: false
      }))
    }
  },
  methods: {
    /**
     * 判断某个标签页是否应使用推荐算法
     * 特殊标签（关注/阅读/排行榜）保留原有行为
     */
    shouldUseRecommend(tabId) {
      if (tabId === '__follow__' || tabId === '__latest__' || tabId === '__hot__') {
        return false
      }
      return true
    },

    /**
     * 获取推荐API使用的channel参数
     */
    getRecommendChannel(tabId) {
      if (tabId === '__recommend__' || tabId === '__all__') {
        return '__all__'
      }
      return String(tabId)
    },

    load(index, loaddir) {
      var idx = (index !== undefined) ? index : this.params.index
      var dir = (loaddir !== undefined) ? loaddir : this.params.loaddir
      var state = this.tabStates[idx]
      if (!state) return
      this.$set(state, 'loading', true)
      this.$set(state, 'error', false)
      var reqParams = {
        loaddir: dir,
        index: idx,
        tag: Config.tabTitles[idx].id,
        tagName: this.subTabStates[idx] ? this.subTabStates[idx].selectedTag : '__all__',
        size: this.params.size || 10,
        max_behot_time: this.params.max_behot_time,
        min_behot_time: this.params.min_behot_time
      }
      var self = this
      return Api.loaddata(reqParams).then((d) => {
        self.$set(state, 'loading', false)
        self.$set(state, 'loadingMore', false)
        self.$set(state, 'refreshing', false)
        self.$set(state, 'loaded', true)
        if (d && d.code === 200) {
          if (d.data && d.data.length > 0) {
            self.tanfer(d.data, idx, dir)
          } else {
            if (dir === 2) {
              self.$set(state, 'noMore', true)
            }
          }
        } else {
          self.$set(state, 'error', true)
          self.$set(state, 'errorMsg', (d && d.errorMessage) || '加载失败，请检查网络')
        }
      }).catch(() => {
        self.$set(state, 'loading', false)
        self.$set(state, 'loadingMore', false)
        self.$set(state, 'refreshing', false)
        self.$set(state, 'loaded', true)
        self.$set(state, 'error', true)
        self.$set(state, 'errorMsg', '网络请求失败，请检查网络连接')
      })
    },

    loadmore(index) {
      var state = this.tabStates[index]
      if (!state || state.loadingMore || state.noMore) return
      this.$set(state, 'loadingMore', true)
      this.load(index, 2)
    },

    loadnew(index) {
      var tabId = Config.tabTitles[index].id
      // 推荐标签页：刷新时重新生成种子
      if (this.shouldUseRecommend(tabId)) {
        this.resetRecommendState(index)
        return this.recommendLoad(index)
      }
      // 特殊标签页：保持原有行为
      var state = this.tabStates[index]
      if (!state || state.loading || state.refreshing) return Promise.resolve()
      this.$set(state, 'refreshing', true)
      return this.load(index, 0)
    },

    tanfer(data, curIndex, loaddir) {
      if (!data || data.length === 0) {
        var state = this.tabStates[curIndex]
        if (state) this.$set(state, 'noMore', true)
        return
      }
      var arr = []
      for (var i = 0; i < data.length; i++) {
        try {
          var item = data[i]
          var ims = []
          if (item.images) {
            var imagesStr = item.images
            if (typeof imagesStr === 'string') {
              ims = imagesStr.replace(/[\[\]]/g, '').split(',').filter(function (s) { return s.trim() })
            } else if (Array.isArray(imagesStr)) {
              ims = imagesStr
            }
          }
          // 无多图时，用单张封面作为卡片右侧封面
          var coverImage = item.coverImage || ''
          if (ims.length === 0 && coverImage) {
            ims = [coverImage]
          }
          var pubTime = item.publishTime
          if (pubTime) {
            if (typeof pubTime === 'string') {
              pubTime = new Date(pubTime).getTime()
            }
            if (isNaN(pubTime)) pubTime = Date.now()
          } else {
            pubTime = Date.now()
          }
          var imgCount = ims.length
          var articleType = imgCount >= 3 ? 3 : (imgCount >= 1 ? 1 : 0)
          var tmp = {
            id: item.id,
            title: item.title || '',
            comment: item.comment || 0,
            views: item.views || 0,
            likes: item.likes || 0,
            authorId: item.authorId,
            source: item.authorName || '',
            authorImage: item.authorImage || '',
            date: pubTime,
            type: articleType,
            image: ims,
            coverImage: coverImage,
            tags: item.tags || [],
            icon: '\uf06d',
            staticUrl: item.staticUrl || ''
          }
          if (pubTime && this.params.max_behot_time < pubTime) {
            this.params.max_behot_time = pubTime
          }
          if (pubTime && this.params.min_behot_time > pubTime) {
            this.params.min_behot_time = pubTime
          }
          arr.push(tmp)
        } catch (e) {
          // ignore malformed items
        }
      }
      var newList = this.tabList.map(function (tab) { return tab.slice() })
      if (loaddir === 0) {
        newList[curIndex] = arr.concat(newList[curIndex])
      } else {
        newList[curIndex] = newList[curIndex].concat(arr)
      }
      this.tabList = newList
      this.showmore = false
      this.shownew = false
    },

    switchTab(index) {
      if (this.currentTab === index) return
      this.currentTab = index
      this.params.loaddir = 1
      this.params.index = index
      this.params.tag = Config.tabTitles[index].id
      this.params.max_behot_time = 0
      this.params.min_behot_time = 20000000000000

      var tabId = Config.tabTitles[index].id

      // 重置标签页状态
      this.$set(this.tabStates, index, {
        loaded: false, loading: false, loadingMore: false,
        refreshing: false, noMore: false, error: false, errorMsg: ''
      })
      var newList = this.tabList.map(function (tab) { return tab.slice() })
      newList[index] = []
      this.tabList = newList

      // Reset sub-tab state
      this.$set(this.subTabStates, index, {
        current: 'recommend',
        tags: [],
        selectedTag: '__all__',
        tagsLoaded: false
      })

      // 所有频道标签页（推荐/综合/后端/前端/Android/iOS/人工智能等）统一使用推荐算法
      if (this.shouldUseRecommend(tabId)) {
        this.resetRecommendState(index)
        this.recommendLoad(index)
      } else {
        this.load(index, 1)
      }

      // Load category tags
      if (this.shouldShowTagFilter(tabId)) {
        this.loadCategoryTags(index)
      }
    },

    wxcTabPageCurrentTabSelected(e) {
      var index = e.page
      this.params.loaddir = 1
      this.params.index = index
      this.params.tag = Config.tabTitles[index].id
      this.params.max_behot_time = 0
      this.params.min_behot_time = 20000000000000

      var tabId = Config.tabTitles[index].id

      this.$set(this.tabStates, index, {
        loaded: false, loading: false, loadingMore: false,
        refreshing: false, noMore: false, error: false, errorMsg: ''
      })
      var newList = this.tabList.map(function (tab) { return tab.slice() })
      newList[index] = []
      this.tabList = newList

      // Reset sub-tab state
      this.$set(this.subTabStates, index, {
        current: 'recommend',
        tags: [],
        selectedTag: '__all__',
        tagsLoaded: false
      })

      if (this.shouldUseRecommend(tabId)) {
        this.resetRecommendState(index)
        this.recommendLoad(index)
      } else {
        this.load(index, 1)
      }

      // Load category tags
      if (this.shouldShowTagFilter(tabId)) {
        this.loadCategoryTags(index)
      }
    },

    /**
     * 重置推荐状态（清空种子和页码）
     */
    resetRecommendState(index) {
      this.$set(this.recommendStates, index, {
        loaded: false, loading: false, loadingMore: false,
        refreshing: false, noMore: false, error: false, errorMsg: '',
        seed: null, page: 0
      })
    },

    /**
     * 推荐加载（首屏/刷新）
     * 每次调用不传 seed 时，后端生成新种子 → 不同的洗牌结果
     */
    recommendLoad(index) {
      var self = this
      var state = self.recommendStates[index]
      if (state.loading) return
      self.$set(state, 'loading', true)
      self.$set(state, 'error', false)
      // 同步 tabStates 状态给模板使用
      self.$set(self.tabStates[index], 'loading', true)
      self.$set(self.tabStates[index], 'error', false)
      // 重置种子和页码（新请求）
      self.$set(state, 'seed', null)
      self.$set(state, 'page', 0)

      var tabId = Config.tabTitles[index].id
      var channel = self.getRecommendChannel(tabId)
      var reqParams = {
        channel: channel,
        size: self.params.size || 10,
        tagName: self.subTabStates[index] ? self.subTabStates[index].selectedTag : '__all__'
      }
      return Api.recommendLoad(reqParams).then(function(d) {
        self.$set(state, 'loading', false)
        self.$set(state, 'loaded', true)
        self.$set(self.tabStates[index], 'loading', false)
        self.$set(self.tabStates[index], 'loaded', true)
        if (d && d.code === 200 && d.data) {
          var data = d.data
          self.$set(state, 'seed', data.seed)
          self.$set(state, 'page', data.page || 0)
          self.$set(state, 'noMore', !data.hasMore)
          self.$set(self.tabStates[index], 'noMore', !data.hasMore)
          if (data.list && data.list.length > 0) {
            self.tanfer(data.list, index, 1)
          }
        } else {
          self.$set(state, 'error', true)
          self.$set(state, 'errorMsg', (d && d.errorMessage) || '加载失败，请检查网络')
          self.$set(self.tabStates[index], 'error', true)
          self.$set(self.tabStates[index], 'errorMsg', (d && d.errorMessage) || '加载失败，请检查网络')
        }
      }).catch(function() {
        self.$set(state, 'loading', false)
        self.$set(state, 'loaded', true)
        self.$set(state, 'error', true)
        self.$set(state, 'errorMsg', '网络请求失败，请检查网络连接')
        self.$set(self.tabStates[index], 'loading', false)
        self.$set(self.tabStates[index], 'loaded', true)
        self.$set(self.tabStates[index], 'error', true)
        self.$set(self.tabStates[index], 'errorMsg', '网络请求失败，请检查网络连接')
      })
    },

    /**
     * 推荐加载更多（无限滚动分页）
     * 使用当前种子 + 递增页码，保证同一会话内分页一致性
     */
    recommendLoadMore(index) {
      var self = this
      var state = self.recommendStates[index]
      if (state.loadingMore || state.noMore || state.loading) return
      self.$set(state, 'loadingMore', true)
      self.$set(self.tabStates[index], 'loadingMore', true)
      var nextPage = (state.page || 0) + 1

      var tabId = Config.tabTitles[index].id
      var channel = self.getRecommendChannel(tabId)
      var reqParams = {
        channel: channel,
        size: self.params.size || 10,
        seed: state.seed,
        page: nextPage
      }
      Api.recommendLoad(reqParams).then(function(d) {
        self.$set(state, 'loadingMore', false)
        self.$set(self.tabStates[index], 'loadingMore', false)
        if (d && d.code === 200 && d.data) {
          var data = d.data
          self.$set(state, 'page', data.page || nextPage)
          self.$set(state, 'noMore', !data.hasMore)
          self.$set(self.tabStates[index], 'noMore', !data.hasMore)
          if (data.list && data.list.length > 0) {
            self.tanfer(data.list, index, 1)
          } else {
            self.$set(state, 'noMore', true)
            self.$set(self.tabStates[index], 'noMore', true)
          }
        }
      }).catch(function() {
        self.$set(state, 'loadingMore', false)
        self.$set(self.tabStates[index], 'loadingMore', false)
      })
    },

    /**
     * 推荐滚动事件处理（无限滚动检测）
     */
    recommendOnScroll(e, index) {
      var el = e.target
      var scrollTop = el.scrollTop
      var scrollHeight = el.scrollHeight
      var clientHeight = el.clientHeight
      var state = this.recommendStates[index]
      if (scrollHeight - scrollTop - clientHeight < 150) {
        if (state && !state.loadingMore && !state.noMore && !state.loading && state.loaded) {
          this.recommendLoadMore(index)
        }
      }
    },

    /**
     * 滚动事件入口
     * 所有推荐标签页走 recommendOnScroll，特殊标签页保持原有逻辑
     */
    onScroll(e, index) {
      var tabId = Config.tabTitles[index].id
      if (this.shouldUseRecommend(tabId)) {
        this.recommendOnScroll(e, index)
        return
      }
      var el = e.target
      var scrollTop = el.scrollTop
      var scrollHeight = el.scrollHeight
      var clientHeight = el.clientHeight
      var state = this.tabStates[index]
      if (scrollHeight - scrollTop - clientHeight < 100) {
        if (state && !state.loadingMore && !state.noMore && !state.loading && state.loaded) {
          this.loadmore(index)
        }
      }
    },

    onDesktopScroll(e) {
      var tabId = Config.tabTitles[this.currentTab].id
      if (this.shouldUseRecommend(tabId)) {
        this.recommendOnScroll(e, this.currentTab)
        return
      }
      var el = e.target
      var scrollTop = el.scrollTop
      var scrollHeight = el.scrollHeight
      var clientHeight = el.clientHeight
      var state = this.currentState
      if (scrollHeight - scrollTop - clientHeight < 150) {
        if (state && !state.loadingMore && !state.noMore && !state.loading && state.loaded) {
          this.loadmore(this.currentTab)
        }
      }
    },

    resetTabState(index) {
      this.$set(this.tabStates, index, {
        loaded: false, loading: false, loadingMore: false,
        refreshing: false, noMore: false, error: false, errorMsg: ''
      })
    },

    clearTabList(index) {
      var newList = this.tabList.map(function (tab) { return tab.slice() })
      newList[index] = []
      this.tabList = newList
    },

    /**
     * 判断是否显示子Tab（推荐/最新）
     * 分类频道(数字ID + __latest__)显示，关注/推荐/综合/排行榜不显示
     */
    shouldShowSubTabs(tabId) {
      if (tabId === '__follow__' || tabId === '__recommend__' || tabId === '__hot__') {
        return false
      }
      return true
    },

    /**
     * 判断是否显示标签筛选
     * 仅分类频道(数字ID)显示
     */
    shouldShowTagFilter(tabId) {
      if (typeof tabId === 'number') {
        return true
      }
      return false
    },

    /**
     * 切换子Tab
     */
    switchSubTab(index, subTab) {
      if (this.subTabStates[index].current === subTab) return
      this.$set(this.subTabStates[index], 'current', subTab)
      this.$set(this.subTabStates[index], 'selectedTag', '__all__')
      this.$set(this.subTabStates[index], 'tagsLoaded', false)

      // Reset tab state and clear list
      this.$set(this.tabStates, index, {
        loaded: false, loading: false, loadingMore: false,
        refreshing: false, noMore: false, error: false, errorMsg: ''
      })
      var newList = this.tabList.map(function(tab) { return tab.slice() })
      newList[index] = []
      this.tabList = newList

      var tabId = Config.tabTitles[index].id

      if (subTab === 'recommend') {
        this.resetRecommendState(index)
        this.recommendLoad(index)
      } else {
        this.load(index, 1)
      }

      // Load tags for this category
      if (this.shouldShowTagFilter(tabId)) {
        this.loadCategoryTags(index)
      }
    },

    /**
     * 选择标签
     */
    selectTag(index, tagName) {
      this.$set(this.subTabStates[index], 'selectedTag', tagName)

      // Reset tab state and clear list
      this.$set(this.tabStates, index, {
        loaded: false, loading: false, loadingMore: false,
        refreshing: false, noMore: false, error: false, errorMsg: ''
      })
      var newList = this.tabList.map(function(tab) { return tab.slice() })
      newList[index] = []
      this.tabList = newList

      var subTab = this.subTabStates[index].current
      if (subTab === 'recommend') {
        this.resetRecommendState(index)
        this.recommendLoad(index)
      } else {
        this.load(index, 1)
      }
    },

    /**
     * 加载分类标签列表
     */
    loadCategoryTags(index) {
      var tabId = Config.tabTitles[index].id
      if (!this.shouldShowTagFilter(tabId)) return
      if (this.subTabStates[index].tagsLoaded) return

      var self = this
      Api.getTagsByCategory(tabId).then(function(d) {
        if (d && d.code === 200) {
          var tags = d.data || []
          self.$set(self.subTabStates[index], 'tags', tags)
          self.$set(self.subTabStates[index], 'tagsLoaded', true)
        }
      }).catch(function() {
        self.$set(self.subTabStates[index], 'tags', [])
        self.$set(self.subTabStates[index], 'tagsLoaded', true)
      })
    }
  }
}