<template>
  <div class="booklet-topbar">
    <div class="topbar-left">
      <input
        v-model="localTitle"
        class="title-input"
        placeholder="未命名小册"
        @blur="handleTitleBlur"
        @keyup.enter="$event.target.blur()"
      />
    </div>
    <div class="topbar-center">
      <span class="save-status" :class="saveStatus">
        <template v-if="saveStatus === 'saving'">保存中...</template>
        <template v-else-if="saveStatus === 'saved'">已保存</template>
        <template v-else-if="saveStatus === 'error'">保存失败</template>
      </span>
      <span class="course-status-tag" v-if="courseStatusText">{{ courseStatusText }}</span>
    </div>
    <div class="topbar-right">
      <el-dropdown trigger="click" @command="handleAction">
        <el-button size="small" type="primary">
          {{ actionButtonText }} <i class="el-icon-arrow-down el-icon--right"></i>
        </el-button>
        <el-dropdown-menu slot="dropdown">
          <el-dropdown-item
            v-for="act in availableActions"
            :key="act.command"
            :command="act.command"
            :divided="act.divided"
          >{{ act.label }}</el-dropdown-item>
        </el-dropdown-menu>
      </el-dropdown>
      <div class="user-avatar">
        <img v-if="userAvatar" :src="userAvatar" class="avatar-img" />
        <span v-else class="avatar-placeholder">&#xf007;</span>
      </div>
    </div>
  </div>
</template>

<script>
import { mapGetters } from 'vuex'

export default {
  name: 'BookletTopBar',
  props: {
    title: { type: String, default: '' },
    saveStatus: { type: String, default: '' },
    courseStatus: { type: Number, default: 0 },
    isEditor: { type: Boolean, default: false }
  },
  data() {
    return {
      localTitle: this.title
    }
  },
  computed: {
    ...mapGetters(['userInfo']),
    userAvatar() {
      return this.userInfo && this.userInfo.avatar ? this.userInfo.avatar : ''
    },
    courseStatusText() {
      const map = {
        0: '草稿',
        1: '申报审核中',
        2: '申报被拒',
        4: '写作中',
        5: '上架审核中',
        9: '已上架',
        3: '已下架'
      }
      return map[this.courseStatus] || ''
    },
    availableActions() {
      const actions = []
      const s = this.courseStatus
      // 作者操作
      if (!this.isEditor) {
        if (s === 0 || s === 2) {
          actions.push({ command: 'apply', label: '提交申报' })
        }
        if (s === 4) {
          actions.push({ command: 'submit-review', label: '提交上架审核' })
        }
      } else {
        // 编辑操作
        if (s === 1) {
          actions.push({ command: 'approve-apply', label: '通过申报' })
          actions.push({ command: 'reject-apply', label: '拒绝申报' })
        }
        if (s === 5) {
          actions.push({ command: 'approve-publish', label: '上架' })
          actions.push({ command: 'reject-publish', label: '驳回上架' })
        }
        if (s === 9) {
          actions.push({ command: 'unpublish', label: '下架', divided: true })
        }
        if (s === 3) {
          actions.push({ command: 're-publish', label: '重新上架' })
        }
      }
      return actions
    },
    actionButtonText() {
      if (this.availableActions.length === 0) return '操作'
      return this.availableActions[0].label
    }
  },
  watch: {
    title(val) {
      this.localTitle = val
    }
  },
  methods: {
    handleTitleBlur() {
      if (this.localTitle !== this.title) {
        this.$emit('update:title', this.localTitle)
        this.$emit('title-change', this.localTitle)
      }
    },
    handleAction(command) {
      this.$emit('action', command)
    }
  }
}
</script>

<style scoped>
.booklet-topbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: 52px;
  padding: 0 20px;
  border-bottom: 1px solid #e4e6eb;
  background: #fff;
  flex-shrink: 0;
}
.topbar-left {
  flex: 0 0 260px;
}
.title-input {
  width: 100%;
  border: none;
  outline: none;
  font-size: 16px;
  font-weight: 600;
  color: #252933;
  background: transparent;
}
.title-input::placeholder {
  color: #c0c4cc;
}
.topbar-center {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 12px;
}
.save-status {
  font-size: 12px;
  color: #999;
}
.save-status.saving { color: #e6a23c; }
.save-status.saved { color: #67c23a; }
.save-status.error { color: #f56c6c; }
.course-status-tag {
  font-size: 12px;
  color: #909399;
  background: #f0f2f5;
  padding: 2px 10px;
  border-radius: 10px;
}
.topbar-right {
  display: flex;
  align-items: center;
  gap: 12px;
  flex: 0 0 260px;
  justify-content: flex-end;
}
.user-avatar {
  width: 32px;
  height: 32px;
  border-radius: 50%;
  overflow: hidden;
  flex-shrink: 0;
}
.avatar-img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}
.avatar-placeholder {
  font-family: fontawesome;
  font-size: 18px;
  color: #c0c4cc;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 100%;
  height: 100%;
  background: #f2f3f5;
}
</style>