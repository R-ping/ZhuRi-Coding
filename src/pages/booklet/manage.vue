<template>
  <div class="booklet-manage-page" v-loading="loading">
    <!-- 顶部：返回 + 写作入口 -->
    <div class="manage-topbar">
      <div class="topbar-left">
        <el-button size="small" icon="el-icon-back" @click="$router.push('/creator/booklet')">返回小册站</el-button>
      </div>
      <div class="topbar-right">
        <el-button type="primary" size="small" icon="el-icon-edit" @click="openEdit">在小册站写作</el-button>
      </div>
    </div>

    <template v-if="course">
      <!-- 基础信息编辑 -->
      <div class="manage-section">
        <h3 class="section-title">小册基础信息</h3>
        <el-form :model="form" label-width="90px" class="base-form">
          <el-form-item label="标题">
            <el-input v-model="form.title" :maxlength="60" />
          </el-form-item>
          <el-form-item label="副标题">
            <el-input v-model="form.subtitle" :maxlength="120" />
          </el-form-item>
          <el-form-item label="价格(元)">
            <el-input-number v-model="form.price" :min="0" :precision="2" />
          </el-form-item>
          <el-form-item label="原始价(元)">
            <el-input-number v-model="form.originalPrice" :min="0" :precision="2" />
          </el-form-item>
          <el-form-item label="封面">
            <div class="cover-row">
              <img v-if="form.coverImage" :src="form.coverImage" class="cover-preview" alt="cover" />
              <el-input v-model="form.coverImage" placeholder="封面图片 URL" />
            </div>
          </el-form-item>
          <el-form-item label="简介">
            <el-input type="textarea" v-model="form.description" :rows="3" />
          </el-form-item>
          <el-form-item>
            <el-button type="primary" size="small" :loading="saving" @click="saveBase">保存修改</el-button>
          </el-form-item>
        </el-form>
      </div>

      <!-- 小节列表 -->
      <div class="manage-section">
        <div class="section-head">
          <h3 class="section-title">小节列表</h3>
          <el-button type="text" icon="el-icon-document-add" @click="addChapter">新增小节</el-button>
        </div>

        <div class="chapter-list">
          <div class="chapter-item" v-for="(ch, idx) in chapters" :key="ch.id">
            <div class="chapter-index">{{ idx + 1 }}</div>
            <div class="chapter-info">
              <div class="chapter-title-row">
                <span class="chapter-title">{{ ch.title || '未命名小节' }}</span>
                <el-tag :type="chapterStatusType(ch.status)" size="mini">{{ chapterStatusText(ch.status) }}</el-tag>
              </div>
              <div class="chapter-meta">
                <span>字数 {{ ch.wordCount || 0 }}</span>
                <el-checkbox :value="ch.isFree === 1" @change="v => toggleFree(ch, v)" size="small">试读</el-checkbox>
              </div>
              <div class="chapter-note" v-if="ch.reviewNote">提交审核留言：{{ ch.reviewNote }}</div>
            </div>
            <div class="chapter-actions">
              <el-button type="text" size="small" :disabled="ch.status === 1 || ch.status === 2" @click="submitChapterReview(ch)">提交审核</el-button>
              <el-button type="text" size="small" @click="openEditChapter(ch)">编辑</el-button>
              <el-button type="text" size="small" class="del" @click="removeChapter(ch)">删除</el-button>
            </div>
          </div>
          <div class="chapter-empty" v-if="chapters.length === 0">还没有小节，点击「新增小节」开始写作</div>
        </div>
      </div>
    </template>
  </div>
</template>

<script>
import courseApi from '@/apis/course'

export default {
  name: 'BookletManage',
  data() {
    return {
      courseId: null,
      course: null,
      chapters: [],
      form: {
        title: '',
        subtitle: '',
        price: 0,
        originalPrice: 0,
        coverImage: '',
        description: ''
      },
      loading: false,
      saving: false
    }
  },
  mounted() {
    this.courseId = Number(this.$route.query.courseId)
    this.loadDetail()
  },
  methods: {
    chapterStatusText(s) {
      return { 0: '草稿', 1: '已发布', 2: '审核中' }[s] || '草稿'
    },
    chapterStatusType(s) {
      return { 0: 'info', 1: 'success', 2: 'warning' }[s] || 'info'
    },
    async loadDetail() {
      this.loading = true
      try {
        const res = await courseApi.getManageDetail({ courseId: this.courseId })
        if (res && res.code === 200 && res.data) {
          this.course = res.data.course
          this.chapters = res.data.chapters || []
          const c = this.course
          this.form = {
            title: c.title || '',
            subtitle: c.subtitle || '',
            price: Number(c.price || 0),
            originalPrice: Number(c.originalPrice || 0),
            coverImage: c.coverImage || '',
            description: c.description || ''
          }
        } else {
          this.$message.error((res && res.errorMessage) || '加载失败')
        }
      } catch (e) {
        this.$message.error('加载失败')
      } finally {
        this.loading = false
      }
    },
    async saveBase() {
      this.saving = true
      try {
        const res = await courseApi.updateCourse({ ...this.form, id: this.courseId })
        if (res && res.code === 200) {
          this.$message.success('已保存')
        } else {
          this.$message.error((res && res.errorMessage) || '保存失败')
        }
      } catch (e) {
        this.$message.error('保存失败')
      } finally {
        this.saving = false
      }
    },
    async addChapter() {
      try {
        const res = await courseApi.createChapter({ courseId: this.courseId, title: '未命名小节' })
        if (res && res.code === 200) {
          this.loadDetail()
        }
      } catch (e) {
        this.$message.error('新增小节失败')
      }
    },
    async toggleFree(ch, val) {
      try {
        await courseApi.updateChapter({ id: ch.id, isFree: val ? 1 : 0 })
        ch.isFree = val ? 1 : 0
      } catch (e) {
        /* 忽略 */
      }
    },
    removeChapter(ch) {
      this.$confirm('确认删除该小节？', '提示', { type: 'warning' }).then(async () => {
        try {
          await courseApi.deleteChapter(ch.id)
          this.chapters = this.chapters.filter(x => x.id !== ch.id)
        } catch (e) {
          this.$message.error('删除失败')
        }
      }).catch(() => {})
    },
    submitChapterReview(ch) {
      this.$prompt('填写提交审核留言（可选）', '提交小节审核', {
        inputPlaceholder: '如：重新定义了xxx / 对内容进行了重新排版',
        confirmButtonText: '提交',
        cancelButtonText: '取消'
      }).then(async ({ value }) => {
        try {
          const res = await courseApi.submitChapterReview(ch.id, value || '')
          if (res && res.code === 200) {
            this.$message.success('已提交审核')
            this.loadDetail()
          } else {
            this.$message.error((res && res.errorMessage) || '提交失败')
          }
        } catch (e) {
          this.$message.error('提交失败')
        }
      }).catch(() => {})
    },
    openEdit() {
      window.open(`/booklet/edit?courseId=${this.courseId}`, '_blank')
    },
    openEditChapter(ch) {
      this.openEdit()
    }
  }
}
</script>

<style lang="less" scoped>
.booklet-manage-page {
  min-height: 100vh;
  background-color: #f4f5f7;
  padding: 16px 24px 60px;

  .manage-topbar {
    display: flex;
    align-items: center;
    justify-content: space-between;
    margin-bottom: 16px;
  }

  .manage-section {
    background: #fff;
    border-radius: 12px;
    padding: 20px 24px;
    margin-bottom: 16px;

    .section-title {
      font-size: 16px;
      font-weight: 600;
      color: #252933;
      border-bottom: 1px solid #f0f1f5;
      padding-bottom: 12px;
      margin-bottom: 16px;
    }
    .section-head {
      display: flex;
      align-items: center;
      justify-content: space-between;
      border-bottom: 1px solid #f0f1f5;
      padding-bottom: 12px;
      margin-bottom: 16px;
      .section-title {
        border: none;
        margin: 0;
        padding: 0;
      }
    }
    .base-form {
      max-width: 640px;
      .cover-row {
        display: flex;
        align-items: center;
        gap: 12px;
        .cover-preview {
          width: 48px;
          height: 64px;
          object-fit: cover;
          border-radius: 6px;
          background: #f0f1f5;
        }
      }
    }

    .chapter-list {
      .chapter-item {
        display: flex;
        align-items: center;
        padding: 12px 0;
        border-bottom: 1px solid #f7f8fa;
        &:last-child {
          border-bottom: none;
        }

        .chapter-index {
          width: 28px;
          height: 28px;
          border-radius: 50%;
          background: #f0f1f5;
          color: #515767;
          display: flex;
          align-items: center;
          justify-content: center;
          font-size: 13px;
          flex-shrink: 0;
        }
        .chapter-info {
          flex: 1;
          margin-left: 14px;
          min-width: 0;

          .chapter-title-row {
            display: flex;
            align-items: center;
            gap: 8px;
            .chapter-title {
              font-size: 14px;
              font-weight: 500;
              color: #252933;
              overflow: hidden;
              text-overflow: ellipsis;
              white-space: nowrap;
            }
          }
          .chapter-meta {
            margin-top: 4px;
            font-size: 12px;
            color: #8a919f;
            display: flex;
            align-items: center;
            gap: 12px;
          }
          .chapter-note {
            margin-top: 4px;
            font-size: 12px;
            color: #e6a23c;
          }
        }
        .chapter-actions {
          flex-shrink: 0;
          .del {
            color: #f56c6c;
          }
        }
      }
      .chapter-empty {
        text-align: center;
        padding: 32px 0;
        color: #8a919f;
        font-size: 14px;
      }
    }
  }
}
</style>