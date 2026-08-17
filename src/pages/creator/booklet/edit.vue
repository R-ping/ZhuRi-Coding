<template>
  <div class="booklet-editor-page">
    <BookletTopBar
      :title="course.title"
      :save-status="saveStatus"
      :course-status="course.status"
      :is-editor="isEditor"
      @title-change="onTitleChange"
      @action="onMenuAction"
    />
    <div class="editor-body">
      <BookletToc
        :chapters="chapters"
        :active-chapter-id="activeChapterId"
        :collapsed="tocCollapsed"
        @toggle="tocCollapsed = !tocCollapsed"
        @select="onSelectChapter"
        @add="addChapter"
        @rename="onRenameChapter"
        @delete="onDeleteChapter"
        @sort="onSortChapters"
      />
      <div class="editor-main" v-loading="loading">
        <!-- 小册介绍 -->
        <template v-if="activeChapterId === 'intro'">
          <div class="editor-section-header">
            <span class="section-title">小册介绍</span>
            <span class="section-tip">介绍小册的定位与内容大纲，读者购买前可见</span>
          </div>
          <div class="editor-content-wrap" :class="{ 'no-preview': previewCollapsed }">
            <ByteMdEditor
              v-model="introContent"
              placeholder="介绍小册的定位、目录与内容大纲..."
              @change="onIntroChange"
            />
          </div>
        </template>
        <!-- 小节编辑 -->
        <template v-else-if="activeChapter">
          <div class="editor-section-header">
            <el-input
              v-model="activeChapter.title"
              class="section-title-input"
              placeholder="小节标题"
              size="small"
              @change="onRenameChapter(activeChapter)"
            />
            <el-checkbox v-model="activeChapter.isFree" :true-label="1" :false-label="0" size="small" @change="onToggleFree(activeChapter)">
              设为试读
            </el-checkbox>
            <span v-if="isChapterLocked" class="section-lock">
              <i class="el-icon-lock"></i> 系统锁定
            </span>
          </div>
          <div v-if="isChapterLocked" class="editor-lock-banner">
            <template v-if="activeChapter.status === 2">
              <div class="lock-banner-title"><i class="el-icon-lock"></i> 该小节正在审核中，系统已锁定</div>
              <div class="lock-banner-desc">审核通过前无法编辑，仅可在下方预览当前提交的完整内容。</div>
            </template>
            <template v-else>
              <div class="lock-banner-title"><i class="el-icon-lock"></i> 该小节已审核通过，默认锁定</div>
              <div class="lock-banner-desc">对已审核通过的小节编辑可能混淆原有内容，编辑后需重新提交编审；如无需修改，推荐直接在下方预览浏览。</div>
              <el-button size="mini" type="primary" plain @click="confirmUnlock">解锁编辑</el-button>
            </template>
          </div>
          <div class="editor-content-wrap" :class="{ 'no-preview': previewCollapsed }">
            <ByteMdEditor
              v-model="activeChapter.content"
              :placeholder="'正在编辑：' + (activeChapter.title || '未命名章节')"
              :readonly="isChapterLocked"
              @change="onChapterChange"
            />
          </div>
        </template>
        <div class="editor-empty" v-else>
          <i class="el-icon-document"></i>
          <p>在左侧目录选择「小册介绍」或一个小节开始编写</p>
        </div>
      </div>
      <div class="editor-bottom-bar">
        <div class="bar-left">
          <el-button size="small" @click="tocCollapsed = !tocCollapsed">
            <i :class="tocCollapsed ? 'el-icon-s-unfold' : 'el-icon-s-fold'"></i>
            {{ tocCollapsed ? '展开目录' : '收起目录' }}
          </el-button>
        </div>
        <div class="bar-right">
          <el-button size="small" @click="previewCollapsed = !previewCollapsed">
            <i :class="previewCollapsed ? 'el-icon-view' : 'el-icon-hide'"></i>
            {{ previewCollapsed ? '展开预览' : '收起预览' }}
          </el-button>
        </div>
      </div>
    </div>

    <!-- 申报表单弹窗 -->
    <el-dialog title="小册申报" :visible.sync="applyDialogVisible" width="520px" :close-on-click-modal="false">
      <el-form :model="applyForm" label-width="80px">
        <el-form-item label="选题">
          <el-input v-model="applyForm.topic" placeholder="说明小册的主题与目标读者" type="textarea" :rows="2" />
        </el-form-item>
        <el-form-item label="大纲">
          <el-input v-model="applyForm.outline" placeholder="简要描述章节规划" type="textarea" :rows="3" />
        </el-form-item>
        <el-form-item label="简介">
          <el-input v-model="applyForm.summary" placeholder="小册的一句话简介" />
        </el-form-item>
        <el-form-item label="样章">
          <el-input v-model="applyForm.sample" placeholder="说明样章内容（可选）" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <div slot="footer">
        <el-button size="small" @click="applyDialogVisible = false">取消</el-button>
        <el-button size="small" type="primary" :loading="submitting" @click="submitApply">提交申报</el-button>
      </div>
    </el-dialog>

    <!-- 拒绝原因弹窗（拒绝申报/驳回上架共用） -->
    <el-dialog :title="rejectDialogTitle" :visible.sync="rejectDialogVisible" width="420px">
      <el-input v-model="rejectReason" type="textarea" :rows="3" placeholder="请输入拒绝原因（作者可见）" />
      <div slot="footer">
        <el-button size="small" @click="rejectDialogVisible = false">取消</el-button>
        <el-button size="small" type="danger" :loading="submitting" @click="confirmReject">确定</el-button>
      </div>
    </el-dialog>
  </div>
</template>

<script>
import { mapGetters } from 'vuex'
import BookletTopBar from './components/BookletTopBar.vue'
import BookletToc from './components/BookletToc.vue'
import ByteMdEditor from '@/pages/creator/components/editor/ByteMdEditor.vue'
import courseApi from '@/apis/course'
import { permission } from '@/utils/permission'
import { toast } from '@/utils/toast'

export default {
  name: 'BookletEdit',
  components: { BookletTopBar, BookletToc, ByteMdEditor },
  data() {
    return {
      course: { title: '未命名小册', status: 0, description: '' },
      chapters: [],
      activeChapterId: 'intro',
      tocCollapsed: false,
      previewCollapsed: false,
      saveStatus: '', // '' | saving | saved | error
      loading: false,
      isEditor: false,
      // 申报
      applyDialogVisible: false,
      applyForm: { topic: '', outline: '', summary: '', sample: '' },
      // 拒绝
      rejectDialogVisible: false,
      rejectDialogTitle: '',
      rejectTarget: '', // reject-apply | reject-publish
      rejectReason: '',
      submitting: false,
      // 已发布小节的解锁态（每个小节独立，切换/重载节点后复位）
      chapterUnlocked: false,
      _saveTimer: null,
      _dirty: false
    }
  },
  computed: {
    ...mapGetters(['userInfo']),
    activeChapter() {
      return this.chapters.find(c => c.id === this.activeChapterId) || null
    },
    // 当前小节是否处于锁定态：草稿(0)自由编辑；审核中(2)恒锁定不可解锁；已发布(1)默认锁定、可解锁
    isChapterLocked() {
      if (!this.activeChapter || this.activeChapterId === 'intro') return false
      if (this.activeChapter.status === 2) return true
      if (this.activeChapter.status === 1) return !this.chapterUnlocked
      return false
    },
    introContent: {
      get() {
        return this.course.description || ''
      },
      set(val) {
        this.course.description = val
      }
    }
  },
  beforeMount() {
    const token = localStorage.getItem('ACCESS_TOKEN')
    if (!token) {
      this.$router.replace('/home')
      return
    }
    this.isEditor = permission.isEditor()
  },
  mounted() {
    this.init()
    window.addEventListener('beforeunload', this.handleBeforeUnload)
  },
  beforeDestroy() {
    window.removeEventListener('beforeunload', this.handleBeforeUnload)
    if (this._saveTimer) clearTimeout(this._saveTimer)
  },
  methods: {
    async init() {
      const courseId = this.$route.query.courseId
      if (courseId) {
        await this.loadCourse(courseId)
      } else {
        await this.createDraft()
      }
    },
    async createDraft() {
      this.loading = true
      try {
        const res = await courseApi.createCourse({ title: '未命名小册' })
        if (res && res.code === 200 && res.data && res.data.id) {
          this.$router.replace({ path: '/booklet/edit', query: { courseId: res.data.id } })
          await this.loadCourse(res.data.id)
        } else {
          toast((res && res.message) || '创建小册失败', 2)
        }
      } catch (e) {
        console.error('创建小册失败', e)
        toast('创建小册失败', 2)
      } finally {
        this.loading = false
      }
    },
    async loadCourse(courseId) {
      this.loading = true
      try {
        const res = await courseApi.getManageDetail({ courseId })
        if (res && res.code === 200 && res.data) {
          this.course = res.data.course || this.course
          this.chapters = (res.data.chapters || []).map(c => ({
            ...c,
            isFree: c.isFree == null ? 0 : c.isFree,
            content: c.content || ''
          }))
          if (this.chapters.length > 0) {
            this.activeChapterId = this.chapters[0].id
          } else {
            this.activeChapterId = 'intro'
          }
          this.chapterUnlocked = false
          this.saveStatus = 'saved'
        } else {
          toast((res && res.message) || '加载小册失败', 2)
        }
      } catch (e) {
        console.error('加载小册失败', e)
        toast('加载小册失败', 2)
      } finally {
        this.loading = false
      }
    },
    // ===== 选择 =====
    onSelectChapter(ch) {
      // 切换小节前先落盘当前小节，防止防抖窗口内切换导致内容丢失
      this.flushPendingChapterSave()
      // 切换小节时复位已发布小节的解锁态，回到默认锁定
      this.chapterUnlocked = false
      if (ch === 'intro') {
        this.activeChapterId = 'intro'
      } else if (ch && ch.id) {
        this.activeChapterId = ch.id
      }
    },
    // 解锁已发布小节：弹框提示风险，确认后放行编辑
    confirmUnlock() {
      this.$confirm(
        '对已审核通过的小节编辑可能混淆原有内容，编辑后需重新提交编审。推荐直接在预览区浏览，确认解锁编辑？',
        '解锁编辑',
        { confirmButtonText: '解锁', cancelButtonText: '取消', type: 'warning' }
      ).then(() => {
        this.chapterUnlocked = true
      }).catch(() => {})
    },
    // ===== 标题/内容自动保存 =====
    onTitleChange(title) {
      this.course.title = title
      this._dirty = true
      this.scheduleCourseSave()
    },
    onIntroChange() {
      this._dirty = true
      this.scheduleCourseSave()
    },
    onChapterChange() {
      this._dirty = true
      this.scheduleChapterSave()
    },
    onToggleFree(ch) {
      this.saveChapter(ch)
    },
    scheduleCourseSave() {
      this.saveStatus = 'saving'
      if (this._courseSaveTimer) clearTimeout(this._courseSaveTimer)
      this._courseSaveTimer = setTimeout(async () => {
        const res = await courseApi.updateCourse({
          id: this.course.id,
          title: this.course.title,
          description: this.course.description
        })
        this.saveStatus = res && res.code === 200 ? 'saved' : 'error'
        if (res && res.code === 200) this._dirty = false
      }, 800)
    },
    scheduleChapterSave() {
      if (!this.activeChapter) return
      // 锁定当前小节引用：若用户在防抖窗口内切换小节，仍保存原小节内容，避免数据丢失
      const ch = this.activeChapter
      this.saveStatus = 'saving'
      if (this._chapterSaveTimer) clearTimeout(this._chapterSaveTimer)
      this._chapterSaveTimer = setTimeout(() => {
        this._chapterSaveTimer = null
        this.saveChapter(ch)
      }, 800)
    },
    /** 切换小节前立即落盘当前小节，防止防抖窗口内切换导致内容丢失 */
    flushPendingChapterSave() {
      if (!this._chapterSaveTimer) return
      clearTimeout(this._chapterSaveTimer)
      this._chapterSaveTimer = null
      const ch = this.activeChapter
      if (ch) this.saveChapter(ch)
    },
    async saveChapter(ch) {
      if (!ch || !ch.id) return
      try {
        const res = await courseApi.updateChapter({
          id: ch.id,
          courseId: ch.courseId,
          title: ch.title,
          content: ch.content,
          isFree: ch.isFree
        })
        this.saveStatus = res && res.code === 200 ? 'saved' : 'error'
        if (res && res.code === 200) this._dirty = false
      } catch (e) {
        this.saveStatus = 'error'
      }
    },
    // ===== 章节管理 =====
    async addChapter() {
      const res = await courseApi.createChapter({ courseId: this.course.id, title: '未命名章节' })
      if (res && res.code === 200 && res.data) {
        this.chapters.push({ ...res.data, isFree: res.data.isFree || 0, content: res.data.content || '' })
        this.activeChapterId = res.data.id
      } else {
        toast((res && res.message) || '添加章节失败', 2)
      }
    },
    onRenameChapter(ch) {
      this.saveChapter(ch)
    },
    async onDeleteChapter(ch) {
      const res = await courseApi.deleteChapter(ch.id)
      if (res && res.code === 200) {
        const idx = this.chapters.findIndex(c => c.id === ch.id)
        this.chapters.splice(idx, 1)
        if (this.activeChapterId === ch.id) {
          this.activeChapterId = this.chapters.length > 0 ? this.chapters[0].id : 'intro'
        }
      } else {
        toast((res && res.message) || '删除章节失败', 2)
      }
    },
    async onSortChapters(list) {
      // 更新本地顺序并持久化 sortOrder
      this.chapters = list.map((c, i) => ({ ...c, sortOrder: i + 1 }))
      const res = await courseApi.updateChapterSort({
        courseId: this.course.id,
        items: this.chapters.map(c => ({ id: c.id, sortOrder: c.sortOrder }))
      })
      if (!res || res.code !== 200) {
        toast('排序保存失败', 2)
      }
    },
    // ===== 顶部操作 =====
    async onMenuAction(command) {
      switch (command) {
        case 'apply':
          this.openApplyDialog()
          break
        case 'submit-review':
          this.confirmSubmitReview()
          break
        case 'approve-apply':
          this.approveApply()
          break
        case 'reject-apply':
          this.openRejectDialog('reject-apply', '拒绝申报')
          break
        case 'approve-publish':
          this.approvePublish()
          break
        case 'reject-publish':
          this.openRejectDialog('reject-publish', '驳回上架')
          break
        case 'unpublish':
          this.confirmUnpublish()
          break
        case 're-publish':
          this.approvePublish()
          break
      }
    },
    openApplyDialog() {
      this.applyDialogVisible = true
    },
    async submitApply() {
      this.submitting = true
      try {
        const res = await courseApi.applyBooklet({
          courseId: this.course.id,
          applyContent: JSON.stringify(this.applyForm)
        })
        if (res && res.code === 200) {
          toast('申报提交成功，等待编辑审核')
          this.applyDialogVisible = false
          this.course.status = 1
        } else {
          toast((res && res.message) || '申报提交失败', 2)
        }
      } finally {
        this.submitting = false
      }
    },
    confirmSubmitReview() {
      this.$confirm('提交后将进入编辑上架审核，提交后不可修改，确定提交？', '提交上架审核', {
        confirmButtonText: '提交',
        cancelButtonText: '取消',
        type: 'warning'
      }).then(async () => {
        const res = await courseApi.submitForReview({ courseId: this.course.id })
        if (res && res.code === 200) {
          toast('已提交上架审核')
          this.course.status = 5
        } else {
          toast((res && res.message) || '提交失败', 2)
        }
      }).catch(() => {})
    },
    async approveApply() {
      const res = await courseApi.approveApply({ courseId: this.course.id })
      if (res && res.code === 200) {
        toast('已通过申报')
        this.course.status = 4
      } else {
        toast((res && res.message) || '操作失败', 2)
      }
    },
    openRejectDialog(target, title) {
      this.rejectTarget = target
      this.rejectDialogTitle = title
      this.rejectReason = ''
      this.rejectDialogVisible = true
    },
    async confirmReject() {
      this.submitting = true
      try {
        const payload = { courseId: this.course.id, reason: this.rejectReason }
        const res = this.rejectTarget === 'reject-apply'
          ? await courseApi.rejectApply(payload)
          : await courseApi.rejectPublish(payload)
        if (res && res.code === 200) {
          toast(this.rejectTarget === 'reject-apply' ? '已拒绝申报' : '已驳回上架')
          this.course.status = this.rejectTarget === 'reject-apply' ? 2 : 4
          this.rejectDialogVisible = false
        } else {
          toast((res && res.message) || '操作失败', 2)
        }
      } finally {
        this.submitting = false
      }
    },
    async approvePublish() {
      const res = await courseApi.approvePublish({ courseId: this.course.id })
      if (res && res.code === 200) {
        toast('已上架，全部小节已发布')
        this.course.status = 9
      } else {
        toast((res && res.message) || '上架失败', 2)
      }
    },
    confirmUnpublish() {
      this.$confirm('下架后读者将无法购买/阅读，确定下架？', '下架小册', {
        confirmButtonText: '下架',
        cancelButtonText: '取消',
        type: 'warning'
      }).then(async () => {
        const res = await courseApi.reviewUnpublish({ courseId: this.course.id })
        if (res && res.code === 200) {
          toast('已下架')
          this.course.status = 3
        } else {
          toast((res && res.message) || '下架失败', 2)
        }
      }).catch(() => {})
    },
    handleBeforeUnload(e) {
      if (this.saveStatus === 'saving') {
        e.preventDefault()
        e.returnValue = ''
      }
    }
  }
}
</script>

<style scoped>
.booklet-editor-page {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  display: flex;
  flex-direction: column;
  background: #fff;
  z-index: 1000;
}
.editor-body {
  flex: 1;
  display: flex;
  min-height: 0;
}
.editor-main {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
}
.editor-section-header {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 20px;
  border-bottom: 1px solid #f0f0f0;
  flex-shrink: 0;
}
.section-title {
  font-size: 15px;
  font-weight: 600;
  color: #252933;
}
.section-tip {
  font-size: 12px;
  color: #999;
}
.section-title-input {
  max-width: 400px;
}
.section-lock {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 12px;
  color: #c0c4cc;
  margin-left: auto;
}
.editor-lock-banner {
  flex-shrink: 0;
  padding: 12px 20px;
  border-bottom: 1px solid #f5d5a6;
  background: #fdf6ec;
  display: flex;
  align-items: center;
  gap: 4px;
  flex-wrap: wrap;
}
.editor-lock-banner .lock-banner-title {
  font-size: 13px;
  font-weight: 600;
  color: #e6a23c;
  display: flex;
  align-items: center;
  gap: 4px;
}
.editor-lock-banner .lock-banner-desc {
  font-size: 12px;
  color: #b88230;
  flex-basis: 100%;
  margin: 4px 0;
  line-height: 1.6;
}
.editor-content-wrap {
  flex: 1;
  min-height: 0;
}
/* 收起预览：隐藏 bytemd 内置预览区，编辑区铺满 */
.editor-content-wrap.no-preview :deep(.bytemd-editor) {
  flex: 0 0 100% !important;
}
.editor-content-wrap.no-preview :deep(.bytemd-preview) {
  display: none !important;
}
.editor-empty {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  color: #c0c4cc;
}
.editor-empty i {
  font-size: 48px;
  margin-bottom: 12px;
}
.editor-empty p {
  font-size: 14px;
}
.editor-bottom-bar {
  height: 44px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 12px;
  border-top: 1px solid #e4e6eb;
  background: #fff;
  flex-shrink: 0;
}
</style>