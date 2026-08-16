<template>
  <div class="review-page">
    <div class="review-header">
      <div class="review-header-left">
        <el-button size="small" icon="el-icon-back" @click="$router.push('/creator/dashboard')">返回创作中心</el-button>
        <h3 class="page-title">上架审核</h3>
        <el-radio-group v-model="status" size="small" @change="handleStatusChange">
          <el-radio-button :label="5">待上架</el-radio-button>
          <el-radio-button :label="9">已上架</el-radio-button>
          <el-radio-button :label="3">已下架</el-radio-button>
        </el-radio-group>
      </div>
      <el-input
        v-model="keyword"
        placeholder="搜索小册标题"
        size="small"
        class="search-input"
        clearable
        @keyup.enter="handleSearch"
        @clear="handleSearch"
      >
        <i slot="prefix" class="el-input__icon el-icon-search"></i>
      </el-input>
    </div>

    <div class="review-body" v-loading="loading">
      <el-table :data="list" style="width: 100%" v-if="list.length > 0">
        <el-table-column prop="title" label="小册标题" min-width="200" show-overflow-tooltip />
        <el-table-column prop="authorName" label="作者" width="110" />
        <el-table-column prop="chapterCount" label="章节数" width="80" />
        <el-table-column label="上架/审核时间" width="170">
          <template slot-scope="scope">{{ formatTime(scope.row.publishedAt || scope.row.reviewTime) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="220" fixed="right">
          <template slot-scope="scope">
            <template v-if="status === 5">
              <el-button size="mini" type="primary" @click="approve(scope.row)">上架</el-button>
              <el-button size="mini" type="danger" @click="openReject(scope.row)">驳回</el-button>
            </template>
            <template v-else-if="status === 9">
              <el-button size="mini" type="warning" @click="unpublish(scope.row)">下架</el-button>
            </template>
            <template v-else-if="status === 3">
              <el-button size="mini" type="primary" @click="rePublish(scope.row)">重新上架</el-button>
            </template>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-else description="暂无数据" />
      <div class="pagination" v-if="total > 0">
        <el-pagination
          background
          layout="prev, pager, next, total"
          :total="total"
          :page-size="size"
          :current-page.sync="page"
          @current-change="loadList"
        />
      </div>
    </div>

    <!-- 驳回原因 -->
    <el-dialog title="驳回上架" :visible.sync="rejectVisible" width="420px">
      <el-input v-model="rejectReason" type="textarea" :rows="3" placeholder="请输入驳回原因（作者可见）" />
      <div slot="footer">
        <el-button size="small" @click="rejectVisible = false">取消</el-button>
        <el-button size="small" type="danger" :loading="submitting" @click="reject">确定</el-button>
      </div>
    </el-dialog>
  </div>
</template>

<script>
import courseApi from '@/apis/course'
import { toast } from '@/utils/toast'

export default {
  name: 'BookletPublishReview',
  data() {
    return {
      list: [],
      total: 0,
      page: 1,
      size: 10,
      status: 5,
      keyword: '',
      loading: false,
      submitting: false,
      rejectVisible: false,
      rejectReason: '',
      rejectTarget: null
    }
  },
  mounted() {
    this.loadList()
  },
  methods: {
    formatTime(t) {
      if (!t) return '-'
      const d = new Date(t)
      const pad = n => (n < 10 ? '0' + n : n)
      return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
    },
    async loadList() {
      this.loading = true
      try {
        const res = await courseApi.getPublishReviewList({ page: this.page, size: this.size, status: this.status, keyword: this.keyword })
        if (res && res.code === 200 && res.data) {
          this.list = res.data.list || []
          this.total = res.data.total || 0
        }
      } finally {
        this.loading = false
      }
    },
    handleStatusChange() {
      this.page = 1
      this.loadList()
    },
    handleSearch() {
      this.page = 1
      this.loadList()
    },
    async approve(row) {
      this.$confirm(`确定上架「${row.title}」？上架后全部小节将发布，读者可见。`, '上架小册', {
        confirmButtonText: '上架',
        cancelButtonText: '取消',
        type: 'success'
      }).then(async () => {
        const res = await courseApi.approvePublish({ courseId: row.id })
        if (res && res.code === 200) {
          toast('已上架并发布全部小节')
          this.loadList()
        } else {
          toast((res && res.message) || '上架失败', 2)
        }
      }).catch(() => {})
    },
    openReject(row) {
      this.rejectTarget = row
      this.rejectReason = ''
      this.rejectVisible = true
    },
    async reject() {
      if (!this.rejectTarget) return
      this.submitting = true
      try {
        const res = await courseApi.rejectPublish({ courseId: this.rejectTarget.id, reason: this.rejectReason })
        if (res && res.code === 200) {
          toast('已驳回上架')
          this.rejectVisible = false
          this.loadList()
        } else {
          toast((res && res.message) || '操作失败', 2)
        }
      } finally {
        this.submitting = false
      }
    },
    unpublish(row) {
      this.$confirm(`确定下架「${row.title}」？读者将无法购买/阅读。`, '下架小册', {
        confirmButtonText: '下架',
        cancelButtonText: '取消',
        type: 'warning'
      }).then(async () => {
        const res = await courseApi.reviewUnpublish({ courseId: row.id })
        if (res && res.code === 200) {
          toast('已下架')
          this.loadList()
        } else {
          toast((res && res.message) || '下架失败', 2)
        }
      }).catch(() => {})
    },
    rePublish(row) {
      this.$confirm(`确定重新上架「${row.title}」？`, '重新上架', {
        confirmButtonText: '上架',
        cancelButtonText: '取消',
        type: 'warning'
      }).then(async () => {
        const res = await courseApi.approvePublish({ courseId: row.id })
        if (res && res.code === 200) {
          toast('已重新上架')
          this.loadList()
        } else {
          toast((res && res.message) || '上架失败', 2)
        }
      }).catch(() => {})
    }
  }
}
</script>

<style scoped>
.review-page {
  min-height: 100vh;
  background: #f5f6f7;
  padding: 24px;
}
.review-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  max-width: 1100px;
  margin: 0 auto 16px;
}
.review-header-left {
  display: flex;
  align-items: center;
  gap: 16px;
}
.page-title {
  margin: 0;
  font-size: 18px;
  color: #252933;
}
.search-input {
  width: 260px;
}
.review-body {
  max-width: 1100px;
  margin: 0 auto;
  background: #fff;
  border-radius: 8px;
  padding: 16px;
  min-height: 400px;
}
.pagination {
  margin-top: 16px;
  text-align: right;
}
</style>