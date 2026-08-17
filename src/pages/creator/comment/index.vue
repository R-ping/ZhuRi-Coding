<template>
  <div class="comment-page">
    <div class="comment-card">
      <div class="card-header">
        <div class="header-title">评论管理</div>
        <div class="header-tools">
          <el-select
            v-model="filterStatus"
            class="status-select"
            placeholder="评论状态"
            clearable
            @change="handleSearch"
          >
            <el-option label="全部" value="_all" />
            <el-option label="开放" value="1" />
            <el-option label="关闭" value="0" />
          </el-select>
          <el-input
            v-model="keyword"
            class="search-input"
            placeholder="搜索文章标题"
            prefix-icon="el-icon-search"
            clearable
            @keyup.enter.native="handleSearch"
            @clear="handleSearch"
          />
          <el-button type="primary" size="small" @click="handleSearch">搜索</el-button>
        </div>
      </div>
      <div class="card-body">
        <el-table
          v-loading="loading"
          :data="commentData"
          tooltip-effect="dark"
          style="width: 100%"
          :header-cell-style="{ backgroundColor: '#fbfbfb' }"
        >
          <el-table-column label="文章标题" prop="title" min-width="240" show-overflow-tooltip />
          <el-table-column label="评论状态" align="center" width="110">
            <template slot-scope="scope">
              <el-tag :type="scope.row.commentOpen ? 'success' : 'info'" size="small">
                {{ scope.row.commentOpen ? '开放中' : '已关闭' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="评论总数" prop="commentCount" align="center" width="110" />
          <el-table-column label="发布时间" align="center" width="160">
            <template slot-scope="scope">{{ scope.row.publishTime ? formatTime(scope.row.publishTime) : '--' }}</template>
          </el-table-column>
          <el-table-column label="最新评论" align="center" width="160">
            <template slot-scope="scope">{{ scope.row.latestCommentTime ? formatTime(scope.row.latestCommentTime) : '--' }}</template>
          </el-table-column>
          <el-table-column label="操作" width="200">
            <template slot-scope="scope">
              <el-button type="text" size="small" @click="viewComments(scope.row)">查看评论</el-button>
              <el-button
                type="text"
                size="small"
                :class="scope.row.commentOpen ? 'text-danger' : 'text-success'"
                @click="toggleComment(scope.row)"
              >{{ scope.row.commentOpen ? '关闭评论' : '开启评论' }}</el-button>
            </template>
          </el-table-column>
        </el-table>
        <div class="empty-tip" v-if="!loading && commentData.length === 0">暂无文章，快去发布第一篇作品吧</div>
        <div class="pagination">
          <el-pagination
            layout="total, prev, pager, next"
            :page-size="commentPage.pageSize"
            :current-page.sync="commentPage.currentPage"
            :total="commentPage.total"
            @current-change="pageChange"
          />
        </div>
      </div>
    </div>
  </div>
</template>

<script>
import {
  getArticleCommentManageList,
  setArticleCommentStatus
} from '@/apis/creator/comment'

export default {
  name: 'CreatorCommentManage',
  data() {
    return {
      commentData: [],
      loading: false,
      keyword: '',
      filterStatus: '_all',
      commentPage: {
        pageSize: 10,
        currentPage: 1,
        total: 0
      }
    }
  },
  created() {
    this.loadList(1)
  },
  methods: {
    async loadList(page) {
      this.loading = true
      try {
        const status = this.filterStatus === '_all' ? null : Number(this.filterStatus)
        const res = await getArticleCommentManageList({
          page: page || this.commentPage.currentPage,
          size: this.commentPage.pageSize,
          status,
          keyword: this.keyword || undefined
        })
        if (res && res.code === 200 && res.data) {
          this.commentData = res.data.list || []
          this.commentPage.total = res.data.total || 0
        } else {
          this.$message.error((res && res.message) || '获取评论数据失败')
        }
      } catch (e) {
        this.$message.error('获取评论数据失败')
      } finally {
        this.loading = false
      }
    },
    handleSearch() {
      this.commentPage.currentPage = 1
      this.loadList(1)
    },
    pageChange(page) {
      this.commentPage.currentPage = page
      this.loadList(page)
    },
    viewComments(row) {
      if (row && row.id) {
        this.$router.push({ path: '/creator/comment/detail', query: { articleId: row.id, title: row.title } })
      }
    },
    toggleComment(row) {
      const nextOpen = !row.commentOpen
      const tip = nextOpen ? '开启后读者可对该文章发表评论，是否开启？' : '关闭后读者将不能再对该文章发表评论，是否关闭？'
      this.$confirm(tip, '提示', { confirmButtonText: '确定', cancelButtonText: '取消', type: 'warning' }).then(async () => {
        try {
          const res = await setArticleCommentStatus({ articleId: row.id, status: nextOpen ? 1 : 0 })
          if (res && res.code === 200) {
            row.commentOpen = nextOpen
            this.$message.success('操作成功')
          } else {
            this.$message.error((res && res.message) || '操作失败，请稍后重试')
          }
        } catch (e) {
          this.$message.error('操作失败，请稍后重试')
        }
      }).catch(() => {})
    },
    formatTime(val) {
      if (!val) return '--'
      const d = new Date(val)
      if (isNaN(d.getTime())) return val
      const pad = n => String(n).padStart(2, '0')
      return d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate()) + ' ' +
        pad(d.getHours()) + ':' + pad(d.getMinutes())
    }
  }
}
</script>

<style rel="stylesheet/less" lang="less" scoped>
@import '../layout/styles/variables.less';
.comment-page {
  min-height: calc(100vh - 70px);
  background-color: @bgGray;
  padding: 20px;
  .comment-card {
    background-color: #ffffff;
    border-radius: @cardRadius;
    box-shadow: @cardShadow;
    overflow: hidden;
    .card-header {
      display: flex;
      align-items: center;
      justify-content: space-between;
      padding: 16px 24px;
      border-bottom: 1px solid #f2f3f5;
      .header-title {
        font-size: 16px;
        font-weight: 500;
        color: @textPrimary;
      }
      .header-tools {
        display: flex;
        align-items: center;
        gap: 10px;
        .status-select {
          width: 130px;
        }
        .search-input {
          width: 240px;
        }
      }
    }
    .card-body {
      padding: 20px 24px 24px;
      .text-danger {
        color: #f56c6c;
      }
      .text-success {
        color: #67c23a;
      }
      .empty-tip {
        padding: 48px 0;
        text-align: center;
        color: @textMuted;
        font-size: 14px;
      }
      .pagination {
        margin-top: 24px;
        text-align: right;
      }
    }
  }
}
</style>