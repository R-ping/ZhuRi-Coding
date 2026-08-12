<template>
  <div class="modal-overlay" @click="$emit('close')">
    <div class="circle-modal" @click.stop>
      <div class="modal-header">
        <span class="modal-title">选择圈子</span>
        <button class="modal-close" @click="$emit('close')">&#xf00d;</button>
      </div>
      <div class="circle-search">
        <input type="text" class="search-input" placeholder="搜索圈子名称" v-model="searchKeyword">
      </div>
      <!-- 分类标签页 -->
      <div class="circle-tabs">
        <div
          class="circle-tab"
          v-for="cat in categories"
          :key="cat.id"
          :class="{ 'active': activeCategory === cat.id }"
          @click="switchCategory(cat.id)"
        >{{ cat.name }}</div>
      </div>
      <div class="circle-list" ref="circleListRef">
        <div class="loading-tip" v-if="loading">{{ loadingText }}</div>
        <div
          class="circle-card"
          v-for="circle in filteredCircles"
          :key="circle.id"
          :class="{ 'selected': selected && selected.id === circle.id }"
          @click="handleSelect(circle)"
        >
          <div class="circle-icon">{{ circle.icon || '📌' }}</div>
          <div class="circle-info">
            <div class="circle-name">{{ circle.name }}</div>
            <div class="circle-stats">{{ circle.memberCount || 0 }} 掘友 · {{ circle.pinsCount || 0 }} 沸点</div>
          </div>
          <div class="circle-check" v-if="selected && selected.id === circle.id">&#xf00c;</div>
        </div>
        <div class="empty-tip" v-if="!loading && filteredCircles.length === 0">暂无圈子</div>
      </div>
      <div class="modal-footer">
        <button class="cancel-btn" @click="$emit('close')">不选择圈子</button>
        <button class="confirm-btn" @click="handleConfirm">确认</button>
      </div>
    </div>
  </div>
</template>

<script>
import request from '@/common/article_request'

export default {
  name: 'PinsCircleSelector',
  props: {
    circles: { type: Array, default: () => [] },
    selected: { type: Object, default: null }
  },
  data() {
    return {
      searchKeyword: '',
      loading: false,
      loadingText: '加载中...',
      activeCategory: 'recommend',
      categories: [
        { id: 'recommend', name: '推荐圈子' },
        { id: 'hot', name: '人气圈子' },
        { id: 'my', name: '我的圈子' }
      ],
      circleMap: {
        recommend: [],
        hot: [],
        my: []
      },
      localSelected: null
    }
  },
  computed: {
    filteredCircles() {
      var list = this.circleMap[this.activeCategory] || this.circles
      if (!this.searchKeyword) return list
      var kw = this.searchKeyword.toLowerCase()
      return list.filter(function(c) { return c.name && c.name.toLowerCase().includes(kw) })
    }
  },
  created() {
    this.localSelected = this.selected || null
    this.loadCategories()
  },
  methods: {
    async loadCategories() {
      await this.loadCategory('recommend', '/api/v1/circle/recommend')
      await this.loadCategory('hot', '/api/v1/circle/hot')
      await this.loadCategory('my', '/api/v1/circle/my')
    },
    async loadCategory(key, url) {
      this.loading = true
      this.loadingText = '加载中...'
      try {
        var res = await request.get(url)
        if (res && (res.code === 200 || res.code === 0)) {
          var data = res.data
          var list = []
          if (Array.isArray(data)) {
            list = data
          } else if (data && Array.isArray(data.records)) {
            list = data.records
          } else if (data && Array.isArray(data.list)) {
            list = data.list
          } else if (data && Array.isArray(data.circles)) {
            list = data.circles
          }
          this.circleMap[key] = this.normalizeCircles(list)
        } else {
          this.circleMap[key] = this.circles.length > 0 ? this.circles : []
        }
      } catch (e) {
        this.circleMap[key] = this.circles.length > 0 ? this.circles : []
      } finally {
        this.loading = false
      }
    },
    normalizeCircles(list) {
      var self = this
      return (list || []).map(function(c) {
        return {
          id: c.id || c.circleId,
          name: c.name || c.circleName || '',
          icon: c.icon || c.cover || '',
          memberCount: c.memberCount || c.member_count || 0,
          pinsCount: c.pinsCount || c.pins_count || c.postCount || 0
        }
      })
    },
    switchCategory(catId) {
      if (this.activeCategory === catId) return
      this.activeCategory = catId
      this.searchKeyword = ''
    },
    handleSelect(circle) {
      this.localSelected = circle
      this.$emit('select', circle)
    },
    handleConfirm() {
      this.$emit('confirm', this.localSelected)
      this.$emit('close')
    }
  }
}
</script>

<style lang="less" scoped>
.modal-overlay {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background: rgba(0,0,0,0.5);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1000;
}

.circle-modal {
  background: #fff;
  border-radius: 8px;
  width: 600px;
  max-height: 70vh;
  overflow: hidden;
}

.modal-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16px 20px;
  border-bottom: 1px solid #f2f3f5;
}

.modal-title {
  font-size: 16px;
  font-weight: 600;
  color: #252933;
}

.modal-close {
  width: 32px;
  height: 32px;
  border: none;
  background: transparent;
  font-family: fontawesome;
  font-size: 16px;
  color: #8a919f;
  cursor: pointer;
  border-radius: 50%;
  &:hover {
    background: #f2f3f5;
    color: #515767;
  }
}

.circle-search {
  padding: 12px 20px;
}

.circle-tabs {
  display: flex;
  gap: 0;
  padding: 0 20px;
  border-bottom: 1px solid #f2f3f5;
}

.circle-tab {
  padding: 10px 16px;
  font-size: 14px;
  color: #515767;
  cursor: pointer;
  border-bottom: 2px solid transparent;
  transition: all 0.2s;
  &:hover { color: #252933; }
  &.active {
    color: #1e80ff;
    border-bottom-color: #1e80ff;
    font-weight: 500;
  }
}

.search-input {
  width: 100%;
  padding: 10px 14px;
  border: 1px solid #e4e6eb;
  border-radius: 4px;
  font-size: 14px;
  outline: none;
  &:focus { border-color: #1e80ff; }
}

.circle-list {
  padding: 12px 20px;
  max-height: 300px;
  overflow-y: auto;
}

.loading-tip,
.empty-tip {
  text-align: center;
  padding: 40px 0;
  color: #8a919f;
  font-size: 14px;
}

.circle-card {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px;
  border-radius: 8px;
  cursor: pointer;
  transition: background-color 0.2s;
  &:hover { background: #f7f8fa; }
  &.selected { background: #eaf2ff; }
}

.circle-icon { font-size: 24px; }

.circle-info { flex: 1; }

.circle-name {
  font-size: 14px;
  color: #252933;
  margin-bottom: 2px;
}

.circle-stats {
  font-size: 12px;
  color: #8a919f;
}

.circle-check {
  font-family: fontawesome;
  font-size: 16px;
  color: #1e80ff;
}

.modal-footer {
  display: flex;
  justify-content: flex-end;
  gap: 12px;
  padding: 16px 20px;
  border-top: 1px solid #f2f3f5;
}

.cancel-btn {
  padding: 8px 24px;
  border: 1px solid #e4e6eb;
  border-radius: 4px;
  background: #fff;
  color: #515767;
  font-size: 14px;
  cursor: pointer;
  &:hover { background: #f7f8fa; }
}

.confirm-btn {
  padding: 8px 24px;
  border: none;
  border-radius: 4px;
  background: #1e80ff;
  color: #fff;
  font-size: 14px;
  cursor: pointer;
  &:hover { background: #4096ff; }
}
</style>