<template>
  <div class="course-editor-page">
    <!-- 顶部工具栏 -->
    <div class="editor-toolbar">
      <div class="toolbar-left">
        <el-button icon="el-icon-arrow-left" size="small" @click="goBack">返回</el-button>
        <el-input
          v-model="courseInfo.title"
          placeholder="课程标题"
          class="title-input"
          size="small"
          @input="markDirty"
        />
        <span class="save-status" :class="saveStatusClass">{{ saveStatusText }}</span>
      </div>
      <div class="toolbar-right">
        <el-button size="small" @click="handleSave">保存</el-button>
        <el-button
          type="primary"
          size="small"
          :disabled="!canPublish"
          @click="handleSubmitReview"
        >发布</el-button>
      </div>
    </div>

    <div class="editor-body" v-loading="loading">
      <!-- 左侧：课程信息 + 章节树 -->
      <div class="editor-sidebar">
        <!-- 课程信息编辑 -->
        <div class="course-info-section">
          <h4 class="section-title">课程信息</h4>
          <el-form label-position="top" size="small">
            <el-form-item label="副标题">
              <el-input v-model="courseInfo.subtitle" placeholder="课程副标题" @input="markDirty" />
            </el-form-item>
            <el-form-item label="课程摘要">
              <el-input
                type="textarea"
                v-model="courseInfo.description"
                placeholder="简要描述课程内容"
                :rows="3"
                @input="markDirty"
              />
            </el-form-item>
            <el-form-item label="封面图片">
              <el-input v-model="courseInfo.coverImage" placeholder="封面图片URL" @input="markDirty" />
            </el-form-item>
            <el-form-item label="价格">
              <el-input-number
                v-model="courseInfo.price"
                :min="0"
                :precision="2"
                :step="1"
                controls-position="right"
                style="width: 100%"
              />
            </el-form-item>
            <el-form-item label="原价">
              <el-input-number
                v-model="courseInfo.originalPrice"
                :min="0"
                :precision="2"
                :step="1"
                controls-position="right"
                style="width: 100%"
              />
            </el-form-item>
            <el-form-item label="分类">
              <el-select v-model="courseInfo.categoryId" placeholder="选择分类" style="width: 100%">
                <el-option label="后端" :value="1" />
                <el-option label="前端" :value="2" />
                <el-option label="Android" :value="3" />
                <el-option label="iOS" :value="4" />
                <el-option label="人工智能" :value="5" />
                <el-option label="开发工具" :value="6" />
                <el-option label="代码人生" :value="7" />
                <el-option label="阅读" :value="8" />
              </el-select>
            </el-form-item>
          </el-form>
        </div>

        <!-- 章节目录 -->
        <div class="chapter-tree-section">
          <div class="section-header">
            <h4 class="section-title">章节目录</h4>
            <el-button type="primary" size="mini" icon="el-icon-plus" @click="addChapter">新增</el-button>
          </div>
          <div class="chapter-list" v-if="chapters.length > 0">
            <div
              v-for="(ch, idx) in chapters"
              :key="ch.id"
              class="chapter-item"
              :class="{ active: activeChapterId === ch.id }"
              @click="selectChapter(ch)"
            >
              <span class="chapter-order">{{ idx + 1 }}</span>
              <span class="chapter-title-text">{{ ch.title || '未命名章节' }}</span>
              <span class="chapter-free" v-if="ch.isFree === 1">试读</span>
              <span class="chapter-actions">
                <el-button
                  type="text"
                  size="mini"
                  icon="el-icon-delete"
                  @click.stop="deleteChapter(ch)"
                />
              </span>
            </div>
          </div>
          <div class="chapter-empty" v-else>
            <p>暂无章节，点击"新增"创建</p>
          </div>
        </div>
      </div>

      <!-- 中间：编辑器 -->
      <div class="editor-main" v-if="activeChapterId">
        <div class="chapter-editor-header">
          <el-input
            v-model="activeChapter.title"
            placeholder="章节标题"
            size="small"
            @input="markDirty"
          />
          <div class="chapter-options">
            <el-checkbox v-model="activeChapter.isFreeBool" @change="onFreeChange">设为试读章节</el-checkbox>
            <el-input-number
              v-model="activeChapter.estimatedMinutes"
              :min="1"
              :max="120"
              size="mini"
              controls-position="right"
              style="width: 100px"
              placeholder="预估阅读时长"
            />
            <span class="option-label">分钟</span>
          </div>
        </div>
        <ByteMdEditor
          ref="editor"
          :value="activeChapter.content"
          @change="onContentChange"
          placeholder="请输入章节内容，支持 Markdown 语法..."
        />
      </div>

      <!-- 右侧：预览 -->
      <div class="editor-preview" v-if="activeChapterId">
        <div class="preview-header">预览</div>
        <div class="preview-content markdown-body" v-html="renderedContent"></div>
      </div>

      <!-- 空状态 -->
      <div class="editor-empty" v-if="!activeChapterId && !loading">
        <i class="el-icon-edit-outline"></i>
        <p>选择或创建一个章节开始编辑</p>
      </div>
    </div>
  </div>
</template>

<script>
import courseApi from '@/apis/course'
import ByteMdEditor from '@/pages/creator/components/editor/ByteMdEditor.vue'
import { marked } from 'marked'
import { sanitizeHtml } from '@/utils/sanitize'

export default {
  name: 'CreatorCourseEdit',
  components: { ByteMdEditor },
  data() {
    return {
      loading: true,
      courseId: null,
      courseInfo: {
        title: '',
        subtitle: '',
        description: '',
        coverImage: '',
        price: 0,
        originalPrice: 0,
        categoryId: null
      },
      chapters: [],
      activeChapterId: null,
      activeChapter: null,
      dirty: false,
      autoSaveTimer: null,
      saveStatus: 'saved'
    }
  },
  computed: {
    canPublish() {
      return this.chapters.length > 0 && this.courseInfo.title && this.courseInfo.title !== '未命名课程'
    },
    saveStatusClass() {
      return {
        'status-saving': this.saveStatus === 'saving',
        'status-saved': this.saveStatus === 'saved',
        'status-dirty': this.saveStatus === 'dirty'
      }
    },
    saveStatusText() {
      const map = { saving: '保存中...', saved: '已保存', dirty: '未保存' }
      return map[this.saveStatus] || ''
    },
    renderedContent() {
      if (!this.activeChapter || !this.activeChapter.content) return ''
      // v-html 渲染前必须 sanitize，防止 Markdown 内嵌脚本等存储型 XSS
      return sanitizeHtml(marked(this.activeChapter.content))
    }
  },
  mounted() {
    this.courseId = this.$route.query.courseId
    if (this.courseId) {
      this.loadCourseDetail()
    }
  },
  beforeDestroy() {
    if (this.autoSaveTimer) clearTimeout(this.autoSaveTimer)
  },
  methods: {
    async loadCourseDetail() {
      this.loading = true
      try {
        const res = await courseApi.getManageDetail({ courseId: this.courseId })
        if (res && res.code === 200 && res.data) {
          const course = res.data.course
          this.courseInfo = {
            title: course.title || '',
            subtitle: course.subtitle || '',
            description: course.description || '',
            coverImage: course.coverImage || '',
            price: course.price || 0,
            originalPrice: course.originalPrice || 0,
            categoryId: course.categoryId || null
          }
          this.chapters = (res.data.chapters || []).map(ch => ({
            ...ch,
            isFreeBool: ch.isFree === 1
          }))
          if (this.chapters.length > 0) {
            this.selectChapter(this.chapters[0])
          }
        }
      } catch (e) {
        console.error('加载课程详情失败', e)
        this.$message.error('加载课程失败')
      } finally {
        this.loading = false
      }
    },
    selectChapter(ch) {
      this.activeChapterId = ch.id
      this.activeChapter = { ...ch }
    },
    onContentChange(val) {
      if (this.activeChapter) {
        this.activeChapter.content = val
        this.markDirty()
        this.autoSave()
      }
    },
    async addChapter() {
      try {
        const res = await courseApi.createChapter({ courseId: this.courseId })
        if (res && res.code === 200 && res.data) {
          await this.loadCourseDetail()
          const newChapter = this.chapters.find(ch => ch.id === res.data.id)
          if (newChapter) this.selectChapter(newChapter)
        }
      } catch (e) {
        this.$message.error('创建章节失败')
      }
    },
    async deleteChapter(ch) {
      try {
        await this.$confirm('确定要删除该章节吗？', '提示', { type: 'warning' })
        await courseApi.deleteChapter(ch.id)
        if (this.activeChapterId === ch.id) {
          this.activeChapterId = null
          this.activeChapter = null
        }
        await this.loadCourseDetail()
        if (this.chapters.length > 0 && !this.activeChapterId) {
          this.selectChapter(this.chapters[0])
        }
      } catch (e) {
        if (e !== 'cancel') this.$message.error('删除失败')
      }
    },
    onFreeChange(val) {
      if (this.activeChapter) {
        this.activeChapter.isFree = val ? 1 : 0
        this.markDirty()
        this.autoSaveChapter()
      }
    },
    async autoSaveChapter() {
      if (!this.activeChapter || !this.activeChapterId) return
      try {
        await courseApi.updateChapter({
          id: this.activeChapter.id,
          title: this.activeChapter.title,
          content: this.activeChapter.content,
          isFree: this.activeChapter.isFree,
          estimatedMinutes: this.activeChapter.estimatedMinutes
        })
      } catch (e) {
        console.error('自动保存章节失败', e)
      }
    },
    markDirty() {
      this.dirty = true
      this.saveStatus = 'dirty'
    },
    autoSave() {
      if (this.autoSaveTimer) clearTimeout(this.autoSaveTimer)
      this.autoSaveTimer = setTimeout(() => {
        this.doSave()
      }, 2000)
    },
    async doSave() {
      if (!this.dirty) return
      this.saveStatus = 'saving'
      try {
        // 保存课程信息
        await courseApi.updateCourse({
          id: this.courseId,
          title: this.courseInfo.title,
          subtitle: this.courseInfo.subtitle,
          description: this.courseInfo.description,
          coverImage: this.courseInfo.coverImage,
          price: this.courseInfo.price,
          originalPrice: this.courseInfo.originalPrice,
          categoryId: this.courseInfo.categoryId
        })
        // 保存当前章节
        await this.autoSaveChapter()
        this.dirty = false
        this.saveStatus = 'saved'
      } catch (e) {
        console.error('保存失败', e)
        this.saveStatus = 'dirty'
      }
    },
    handleSave() {
      this.doSave()
    },
    async handleSubmitReview() {
      if (!this.canPublish) {
        this.$message.warning('请完善课程标题和至少一个章节')
        return
      }
      try {
        await this.doSave()
        await this.$confirm('确定要提交审核吗？提交后不可编辑。', '提示', { type: 'warning' })
        const res = await courseApi.submitForReview({ courseId: this.courseId })
        if (res && res.code === 200) {
          this.$message.success('已提交审核')
          this.$router.push('/creator/course/list')
        }
      } catch (e) {
        if (e !== 'cancel') this.$message.error('提交失败')
      }
    },
    goBack() {
      this.$router.push('/creator/course/list')
    }
  }
}
</script>

<style lang="less" scoped>
.course-editor-page {
  height: 100vh;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.editor-toolbar {
  height: 48px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 16px;
  background: #fff;
  border-bottom: 1px solid #e8e8e8;
  flex-shrink: 0;
}

.toolbar-left {
  display: flex;
  align-items: center;
  gap: 12px;
}

.title-input {
  width: 300px;
}

.save-status {
  font-size: 12px;
  padding: 2px 8px;
  border-radius: 4px;
}
.status-saving { color: #e6a23c; }
.status-saved { color: #67c23a; }
.status-dirty { color: #909399; }

.toolbar-right {
  display: flex;
  gap: 8px;
}

.editor-body {
  flex: 1;
  display: flex;
  overflow: hidden;
}

.editor-sidebar {
  width: 280px;
  background: #fafafa;
  border-right: 1px solid #e8e8e8;
  overflow-y: auto;
  flex-shrink: 0;
}

.course-info-section {
  padding: 16px;
  border-bottom: 1px solid #e8e8e8;
}

.section-title {
  margin: 0 0 12px;
  font-size: 14px;
  font-weight: 600;
  color: #333;
}

.chapter-tree-section {
  padding: 16px;
}

.section-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
}

.chapter-list {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.chapter-item {
  display: flex;
  align-items: center;
  padding: 8px 10px;
  border-radius: 6px;
  cursor: pointer;
  transition: background 0.2s;
  gap: 8px;
  &:hover {
    background: #e8f4ff;
  }
  &.active {
    background: #d4e8ff;
    color: #1e80ff;
  }
}

.chapter-order {
  width: 20px;
  height: 20px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: #e8e8e8;
  border-radius: 50%;
  font-size: 12px;
  flex-shrink: 0;
}

.chapter-title-text {
  flex: 1;
  font-size: 13px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.chapter-free {
  font-size: 11px;
  color: #67c23a;
  background: #f0f9eb;
  padding: 1px 6px;
  border-radius: 3px;
  flex-shrink: 0;
}

.chapter-empty {
  text-align: center;
  padding: 20px;
  color: #999;
  font-size: 13px;
}

.editor-main {
  flex: 1;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  min-width: 0;
}

.chapter-editor-header {
  padding: 12px 16px;
  background: #fff;
  border-bottom: 1px solid #e8e8e8;
  display: flex;
  align-items: center;
  gap: 16px;
}

.chapter-options {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
}

.option-label {
  font-size: 12px;
  color: #999;
}

.editor-preview {
  width: 360px;
  background: #fff;
  border-left: 1px solid #e8e8e8;
  overflow-y: auto;
  flex-shrink: 0;
}

.preview-header {
  padding: 12px 16px;
  font-size: 13px;
  font-weight: 600;
  color: #999;
  border-bottom: 1px solid #e8e8e8;
}

.preview-content {
  padding: 16px;
  font-size: 14px;
  line-height: 1.8;
  color: #333;
}

.editor-empty {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  color: #999;
  i {
    font-size: 48px;
    margin-bottom: 12px;
  }
  p {
    font-size: 14px;
  }
}
</style>