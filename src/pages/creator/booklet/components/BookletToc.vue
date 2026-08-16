<template>
  <div class="booklet-toc" :class="{ collapsed: collapsed }">
    <div class="toc-header">
      <span class="toc-title">目录</span>
      <i class="el-icon-close" @click="$emit('toggle')" v-if="!collapsed"></i>
    </div>
    <div class="toc-body" v-if="!collapsed">
      <!-- 小册介绍（固定首位） -->
      <div class="toc-item toc-intro" :class="{ active: activeChapterId === 'intro' }" @click="$emit('select', 'intro')">
        <i class="el-icon-document"></i>
        <span class="toc-item-title">小册介绍</span>
      </div>
      <!-- 章节列表 -->
      <div
        v-for="(ch, index) in chapters"
        :key="ch.id"
        class="toc-item"
        :class="{ active: activeChapterId === ch.id }"
        @click="$emit('select', ch)"
      >
        <i class="el-icon-rank toc-drag-handle"></i>
        <span class="toc-item-num">{{ index + 1 }}</span>
        <span class="toc-item-title" v-show="editingChapterId !== ch.id" @dblclick.stop="startEdit(ch)">
          {{ ch.title || '未命名章节' }}
        </span>
        <input
          v-show="editingChapterId === ch.id"
          v-model="ch.title"
          class="toc-item-edit-input"
          :ref="'editInput' + ch.id"
          @blur="finishEdit(ch)"
          @keyup.enter="finishEdit(ch)"
          @click.stop
        />
        <span class="toc-item-badge" v-if="ch.isFree === 1">试读</span>
        <i class="el-icon-arrow-up toc-item-sort" :class="{ disabled: index === 0 }" @click.stop="move(ch, -1)"></i>
        <i class="el-icon-arrow-down toc-item-sort" :class="{ disabled: index === chapters.length - 1 }" @click.stop="move(ch, 1)"></i>
        <i class="el-icon-delete toc-item-del" @click.stop="handleDelete(ch)" title="删除章节"></i>
      </div>
      <!-- 添加章节按钮 -->
      <div class="toc-add-btn" @click="$emit('add')">
        <i class="el-icon-plus"></i> 添加章节
      </div>
    </div>
  </div>
</template>

<script>
export default {
  name: 'BookletToc',
  props: {
    chapters: { type: Array, default: () => [] },
    activeChapterId: { type: [Number, String], default: null },
    collapsed: { type: Boolean, default: false }
  },
  data() {
    return {
      editingChapterId: null
    }
  },
  methods: {
    startEdit(ch) {
      this.editingChapterId = ch.id
      this.$nextTick(() => {
        const input = this.$refs['editInput' + ch.id]
        if (input && input[0]) {
          input[0].focus()
          input[0].select()
        }
      })
    },
    finishEdit(ch) {
      this.editingChapterId = null
      this.$emit('rename', ch)
    },
    handleDelete(ch) {
      this.$confirm(`确定删除章节「${ch.title || '未命名章节'}」？`, '提示', {
        confirmButtonText: '确定',
        cancelButtonText: '取消',
        type: 'warning'
      }).then(() => {
        this.$emit('delete', ch)
      }).catch(() => {})
    },
    move(ch, dir) {
      const index = this.chapters.findIndex(c => c.id === ch.id)
      const target = index + dir
      if (target < 0 || target >= this.chapters.length) return
      const list = this.chapters.slice()
      const [item] = list.splice(index, 1)
      list.splice(target, 0, item)
      this.$emit('sort', list)
    }
  }
}
</script>

<style scoped>
.booklet-toc {
  width: 260px;
  min-width: 260px;
  background: #fafafa;
  border-right: 1px solid #e4e6eb;
  display: flex;
  flex-direction: column;
  height: 100%;
  overflow: hidden;
  transition: width 0.2s, min-width 0.2s;
}
.booklet-toc.collapsed {
  width: 0;
  min-width: 0;
  border-right: none;
}
.toc-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 16px;
  border-bottom: 1px solid #e4e6eb;
  flex-shrink: 0;
}
.toc-title {
  font-size: 15px;
  font-weight: 600;
  color: #252933;
}
.toc-body {
  flex: 1;
  overflow-y: auto;
  padding: 8px 0;
}
.toc-item {
  display: flex;
  align-items: center;
  padding: 8px 12px;
  cursor: pointer;
  transition: background 0.15s;
  gap: 5px;
  font-size: 13px;
  color: #515767;
}
.toc-item:hover {
  background: #f0f0f0;
}
.toc-item.active {
  background: #e8f0fe;
  color: #1e80ff;
}
.toc-intro {
  font-weight: 500;
  border-bottom: 1px solid #e4e6eb;
  margin-bottom: 4px;
}
.toc-drag-handle {
  color: #c0c4cc;
  font-size: 14px;
  flex-shrink: 0;
}
.toc-item-num {
  width: 16px;
  text-align: right;
  font-size: 12px;
  color: #999;
  flex-shrink: 0;
}
.toc-item-title {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.toc-item-edit-input {
  flex: 1;
  border: 1px solid #1e80ff;
  border-radius: 4px;
  padding: 2px 6px;
  font-size: 13px;
  outline: none;
}
.toc-item-badge {
  font-size: 11px;
  color: #fff;
  background: #1e80ff;
  border-radius: 3px;
  padding: 1px 5px;
  flex-shrink: 0;
}
.toc-item-sort {
  font-size: 13px;
  color: #909399;
  flex-shrink: 0;
}
.toc-item-sort:hover {
  color: #1e80ff;
}
.toc-item-sort.disabled {
  color: #e4e6eb;
  pointer-events: none;
}
.toc-item-del {
  color: #f56c6c;
  font-size: 14px;
  flex-shrink: 0;
  opacity: 0;
  transition: opacity 0.15s;
}
.toc-item:hover .toc-item-del {
  opacity: 1;
}
.toc-add-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 10px 16px;
  margin: 8px 16px;
  border: 1px dashed #d0d7de;
  border-radius: 6px;
  color: #1e80ff;
  font-size: 13px;
  cursor: pointer;
  transition: background 0.15s;
}
.toc-add-btn:hover {
  background: #e8f0fe;
}
</style>