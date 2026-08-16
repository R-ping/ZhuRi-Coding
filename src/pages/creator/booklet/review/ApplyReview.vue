<template>
  <div class="review-page">
    <div class="review-header">
      <div class="review-header-left">
        <el-button size="small" icon="el-icon-back" @click="$router.push('/creator/dashboard')">返回创作中心</el-button>
        <h3 class="page-title">申报审核</h3>
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
        <el-table-column prop="authorName" label="作者" width="120" />
        <el-table-column label="申报时间" width="170">
          <template slot-scope="scope">{{ formatTime(scope.row.applyTime) }}</template>
        </el-table-column>
        <el-table-column label="申报内容" min-width="240">
          <template slot-scope="scope">
            <div class="apply-content" @click="viewApply(scope.row)">{{ applySummary(scope.row) }}</div>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="160" fixed="right">
          <template slot-scope="scope">
            <el-button size="mini" type="primary" @click="approve(scope.row)">通过</el-button>
            <el-button size="mini" type="danger" @click="openReject(scope.row)">拒绝</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-else description="暂无待审核申报" />
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

    <!-- 申报内容查看 -->
    <el-dialog title="申报内容" :visible.sync="applyDetailVisible" width="560px">
      <div class="apply-detail" v-if="applyDetail">
        <p><b>选题：</b>{{ applyDetail.topic || '-' }}</p>
        <p><b>大纲：</b>{{ applyDetail.outline || '-' }}</p>
        <p><b>简介：</b>{{ applyDetail.summary || '-' }}</p>
        <p><b>样章：</b>{{ applyDetail.sample || '-' }}</p>
      </div>
    </el-dialog>

    <!-- 拒绝原因 -->
    <el-dialog title="拒绝申报" :visible.sync="rejectVisible" width="420px">
      <el-input v-model="rejectReason" type="textarea" :rows="3" placeholder="请输入拒绝原因（作者可见）" />
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
  name: 'BookletApplyReview',
  data() {
    return {
      list: [],
      total: 0,
      page: 1,
      size: 10,
      keyword: '',
      loading: false,
      submitting: false,
      applyDetailVisible: false,
      applyDetail: null,
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
    applySummary(row) {
      try {
        const data = JSON.parse(row.applyContent || '{}')
        return data.topic || data.summary || '（未填写）'
      } catch (e) {
        return row.applyContent || ''
      }
    },
    viewApply(row) {
      try {
        this.applyDetail = JSON.parse(row.applyContent || '{}')
      } catch (e) {
        this.applyDetail = { topic: row.applyContent }
      }
      this.applyDetailVisible = true
    },
    async loadList() {
      this.loading = true
      try {
        const res = await courseApi.getApplyReviewList({ page: this.page, size: this.size, keyword: this.keyword })
        if (res && res.code === 200 && res.data) {
          this.list = res.data.list || []
          this.total = res.data.total || 0
        }
      } finally {
        this.loading = false
      }
    },
    handleSearch() {
      this.page = 1
      this.loadList()
    },
    async approve(row) {
      this.$confirm(`确定通过「${row.title}」的申报？通过后作者可开始写作。`, '通过申报', {
        confirmButtonText: '通过',
        cancelButtonText: '取消',
        type: 'success'
      }).then(async () => {
        const res = await courseApi.approveApply({ courseId: row.id })
        if (res && res.code === 200) {
          toast('已通过申报')
          this.loadList()
        } else {
          toast((res && res.message) || '操作失败', 2)
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
        const res = await courseApi.rejectApply({ courseId: this.rejectTarget.id, reason: this.rejectReason })
        if (res && res.code === 200) {
          toast('已拒绝申报')
          this.rejectVisible = false
          this.loadList()
        } else {
          toast((res && res.message) || '操作失败', 2)
        }
      } finally {
        this.submitting = false
      }
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
.apply-content {
  color: #1e80ff;
  cursor: pointer;
  font-size: 13px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.apply-detail p {
  margin: 8px 0;
  line-height: 1.8;
  font-size: 14px;
  color: #333;
}
.pagination {
  margin-top: 16px;
  text-align: right;
}
</style>